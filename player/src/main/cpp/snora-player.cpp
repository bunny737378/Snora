#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <pthread.h>
#include <string>
#include <atomic>
#include <thread>
#include <chrono>
#include <vector>
#include <queue>
#include <mutex>
#include <condition_variable>
#include <sstream>
#include <algorithm>
#include <cmath>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
}

#ifndef GL_UNPACK_ROW_LENGTH
#define GL_UNPACK_ROW_LENGTH 0x0CF2
#endif

#define LOG_TAG "SnoraNativePlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static ANativeWindow* g_nativeWindow = nullptr;
static pthread_mutex_t g_windowMutex = PTHREAD_MUTEX_INITIALIZER;

static std::atomic<bool> g_isPlaying(false);
static std::thread g_demuxThread;

static std::atomic<int64_t> g_currentPositionMs(0);
static std::atomic<int64_t> g_durationMs(0);
static std::atomic<int64_t> g_audioClockMs(0);
static std::atomic<int64_t> g_seekTargetMs(-1);

static std::atomic<int> g_videoWidth(0);
static std::atomic<int> g_videoHeight(0);

static std::atomic<float> g_playbackSpeed(1.0f);
static std::atomic<int> g_aspectRatioMode(0);
static std::atomic<float> g_zoomScale(1.0f);
static std::atomic<bool> g_useHwAcceleration(true);

static std::atomic<int> g_selectedAudioStreamIndex(-1);
static std::atomic<int> g_selectedSubtitleStreamIndex(-1);
static std::atomic<int> g_requestedAudioStreamIndex(-2);
static std::atomic<int> g_requestedSubtitleStreamIndex(-2);

struct NativeTrackInfo {
    int index;
    int type;
    std::string title;
    std::string lang;
    std::string codec;
};
static std::vector<NativeTrackInfo> g_tracks;
static pthread_mutex_t g_tracksMutex = PTHREAD_MUTEX_INITIALIZER;

struct NativeChapterInfo {
    int id;
    std::string title;
    int64_t startMs;
    int64_t endMs;
};
static std::vector<NativeChapterInfo> g_chapters;
static pthread_mutex_t g_chaptersMutex = PTHREAD_MUTEX_INITIALIZER;

static std::string g_currentSubText = "";
static std::mutex g_subTextMutex;

struct MyPacket {
    AVPacket* pkt;
    bool is_flush;
};

class PacketQueue {
public:
    std::queue<MyPacket> q;
    std::mutex mutex;
    std::condition_variable cond;
    bool abort_request = false;

    void put(AVPacket* pkt) {
        std::lock_guard<std::mutex> lock(mutex);
        if (abort_request) return;
        AVPacket* p = av_packet_alloc();
        av_packet_move_ref(p, pkt);
        q.push({p, false});
        cond.notify_one();
    }
    void put_flush() {
        std::lock_guard<std::mutex> lock(mutex);
        if (abort_request) return;
        q.push({nullptr, true});
        cond.notify_one();
    }
    int get(MyPacket& mypkt) {
        std::unique_lock<std::mutex> lock(mutex);
        cond.wait(lock, [this] { return !q.empty() || abort_request; });
        if (abort_request) return -1;
        mypkt = q.front();
        q.pop();
        return 1;
    }
    void flush() {
        std::lock_guard<std::mutex> lock(mutex);
        while (!q.empty()) {
            MyPacket mp = q.front();
            q.pop();
            if (mp.pkt) av_packet_free(&mp.pkt);
        }
    }
    void abort() {
        std::lock_guard<std::mutex> lock(mutex);
        abort_request = true;
        cond.notify_all();
    }
    void start() {
        std::lock_guard<std::mutex> lock(mutex);
        abort_request = false;
    }
};

static PacketQueue g_videoQueue;
static PacketQueue g_audioQueue;
static PacketQueue g_subtitleQueue;

static const char* VERTEX_SHADER_SOURCE = R"(
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uMVPMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTexCoord = aTexCoord;
}
)";

static const char* FRAGMENT_SHADER_YUV = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTextureY;
uniform sampler2D uTextureU;
uniform sampler2D uTextureV;
void main() {
    float y = texture2D(uTextureY, vTexCoord).r;
    float u = texture2D(uTextureU, vTexCoord).r - 0.5;
    float v = texture2D(uTextureV, vTexCoord).r - 0.5;
    float r = y + 1.402 * v;
    float g = y - 0.344136 * u - 0.714136 * v;
    float b = y + 1.772 * u;
    gl_FragColor = vec4(r, g, b, 1.0);
}
)";

static const char* FRAGMENT_SHADER_RGBA = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
)";

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static GLuint createProgram(const char* vertSrc, const char* fragSrc) {
    GLuint vert = compileShader(GL_VERTEX_SHADER, vertSrc);
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, fragSrc);
    if (!vert || !frag) return 0;
    GLuint program = glCreateProgram();
    glAttachShader(program, vert);
    glAttachShader(program, frag);
    glLinkProgram(program);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

struct SubtitleOverlay {
    int width = 0;
    int height = 0;
    std::vector<uint32_t> rgbaData;
    bool active = false;
};
static SubtitleOverlay g_subOverlay;
static std::mutex g_subOverlayMutex;

static std::string escapeJson(const std::string& s) {
    std::ostringstream o;
    for (char c : s) {
        if (c == '"') o << "\\\"";
        else if (c == '\\') o << "\\\\";
        else if (c == '\b') o << "\\b";
        else if (c == '\f') o << "\\f";
        else if (c == '\n') o << "\\n";
        else if (c == '\r') o << "\\r";
        else if (c == '\t') o << "\\t";
        else o << c;
    }
    return o.str();
}

static void calculateMvpMatrix(int videoWidth, int videoHeight, int winWidth, int winHeight, int mode, float zoom, float* matrix) {
    for (int i = 0; i < 16; ++i) matrix[i] = 0.0f;
    matrix[0] = 1.0f; matrix[5] = 1.0f; matrix[10] = 1.0f; matrix[15] = 1.0f;

    if (videoWidth <= 0 || videoHeight <= 0 || winWidth <= 0 || winHeight <= 0) return;

    float videoAspect = (float)videoWidth / (float)videoHeight;
    float winAspect = (float)winWidth / (float)winHeight;

    float scaleX = 1.0f;
    float scaleY = 1.0f;

    if (mode == 0) {
        if (videoAspect > winAspect) {
            scaleX = 1.0f;
            scaleY = winAspect / videoAspect;
        } else {
            scaleX = videoAspect / winAspect;
            scaleY = 1.0f;
        }
    } else if (mode == 1) {
        if (videoAspect > winAspect) {
            scaleX = videoAspect / winAspect;
            scaleY = 1.0f;
        } else {
            scaleX = 1.0f;
            scaleY = winAspect / videoAspect;
        }
    } else if (mode == 2) {
        scaleX = 1.0f;
        scaleY = 1.0f;
    }

    scaleX *= zoom;
    scaleY *= zoom;

    matrix[0] = scaleX;
    matrix[5] = scaleY;
}

static void videoDecoderThread(AVCodecContext* codecCtx, int streamIdx, double timeBase) {
    AVFrame* frame = av_frame_alloc();
    AVFrame* convertedFrame = av_frame_alloc();
    SwsContext* swsContext = nullptr;
    int swsWidth = 0, swsHeight = 0;
    MyPacket mp;

    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;

    GLuint yuvProgram = 0;
    GLuint subProgram = 0;
    GLuint texY = 0, texU = 0, texV = 0, texSub = 0;

    GLint aPosLocation = -1, aTexLocation = -1, uMvpLocation = -1;
    GLint uTexYLoc = -1, uTexULoc = -1, uTexVLoc = -1;

    GLint subPosLoc = -1, subTexLoc = -1, subMvpLoc = -1, subTexSamplerLoc = -1;

    float vertices[] = {
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f
    };

    auto initGL = [&]() -> bool {
        pthread_mutex_lock(&g_windowMutex);
        if (!g_nativeWindow) {
            pthread_mutex_unlock(&g_windowMutex);
            return false;
        }

        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY) {
            pthread_mutex_unlock(&g_windowMutex);
            return false;
        }

        eglInitialize(display, nullptr, nullptr);

        const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };

        EGLConfig config;
        EGLint numConfigs;
        eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);

        surface = eglCreateWindowSurface(display, config, g_nativeWindow, nullptr);
        pthread_mutex_unlock(&g_windowMutex);

        if (surface == EGL_NO_SURFACE) return false;

        const EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
        };

        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
        if (context == EGL_NO_CONTEXT) return false;

        if (!eglMakeCurrent(display, surface, surface, context)) return false;

        yuvProgram = createProgram(VERTEX_SHADER_SOURCE, FRAGMENT_SHADER_YUV);
        subProgram = createProgram(VERTEX_SHADER_SOURCE, FRAGMENT_SHADER_RGBA);

        if (!yuvProgram || !subProgram) return false;

        aPosLocation = glGetAttribLocation(yuvProgram, "aPosition");
        aTexLocation = glGetAttribLocation(yuvProgram, "aTexCoord");
        uMvpLocation = glGetUniformLocation(yuvProgram, "uMVPMatrix");
        uTexYLoc = glGetUniformLocation(yuvProgram, "uTextureY");
        uTexULoc = glGetUniformLocation(yuvProgram, "uTextureU");
        uTexVLoc = glGetUniformLocation(yuvProgram, "uTextureV");

        subPosLoc = glGetAttribLocation(subProgram, "aPosition");
        subTexLoc = glGetAttribLocation(subProgram, "aTexCoord");
        subMvpLoc = glGetUniformLocation(subProgram, "uMVPMatrix");
        subTexSamplerLoc = glGetUniformLocation(subProgram, "uTexture");

        glGenTextures(1, &texY);
        glBindTexture(GL_TEXTURE_2D, texY);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glGenTextures(1, &texU);
        glBindTexture(GL_TEXTURE_2D, texU);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glGenTextures(1, &texV);
        glBindTexture(GL_TEXTURE_2D, texV);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glGenTextures(1, &texSub);
        glBindTexture(GL_TEXTURE_2D, texSub);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        return true;
    };

    bool glInitialized = false;

    while (g_isPlaying) {
        if (!glInitialized) {
            glInitialized = initGL();
            if (!glInitialized) {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                continue;
            }
        }

        if (g_videoQueue.get(mp) < 0) break;

        if (mp.is_flush) {
            avcodec_flush_buffers(codecCtx);
            continue;
        }

        if (avcodec_send_packet(codecCtx, mp.pkt) == 0) {
            while (avcodec_receive_frame(codecCtx, frame) == 0) {
                if (!g_isPlaying) break;

                int width = codecCtx->width;
                int height = codecCtx->height;
                g_videoWidth.store(width);
                g_videoHeight.store(height);

                AVFrame* yuvFrame = frame;
                if (frame->format != AV_PIX_FMT_YUV420P) {
                    if (!swsContext || swsWidth != width || swsHeight != height) {
                        if (swsContext) sws_freeContext(swsContext);
                        swsContext = sws_getContext(width, height, (AVPixelFormat)frame->format,
                                                    width, height, AV_PIX_FMT_YUV420P,
                                                    SWS_BILINEAR, nullptr, nullptr, nullptr);
                        swsWidth = width;
                        swsHeight = height;
                    }
                    if (swsContext) {
                        convertedFrame->width = width;
                        convertedFrame->height = height;
                        convertedFrame->format = AV_PIX_FMT_YUV420P;
                        if (!convertedFrame->data[0]) {
                            av_frame_get_buffer(convertedFrame, 32);
                        }
                        sws_scale(swsContext, frame->data, frame->linesize, 0, height, convertedFrame->data, convertedFrame->linesize);
                        yuvFrame = convertedFrame;
                    }
                }

                if (frame->pts != AV_NOPTS_VALUE) {
                    int64_t ptsMs = static_cast<int64_t>(frame->pts * timeBase * 1000);
                    g_currentPositionMs.store(ptsMs);

                    int64_t aClock = g_audioClockMs.load();
                    int64_t diff = ptsMs - aClock;
                    if (diff > 10 && diff < 5000) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(diff));
                    }
                }

                EGLint winW = 0, winH = 0;
                eglQuerySurface(display, surface, EGL_WIDTH, &winW);
                eglQuerySurface(display, surface, EGL_HEIGHT, &winH);

                glViewport(0, 0, winW, winH);
                glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);

                glUseProgram(yuvProgram);

                float mvp[16];
                calculateMvpMatrix(width, height, winW, winH, g_aspectRatioMode.load(), g_zoomScale.load(), mvp);
                glUniformMatrix4fv(uMvpLocation, 1, GL_FALSE, mvp);

                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texY);
                glPixelStorei(GL_UNPACK_ROW_LENGTH, yuvFrame->linesize[0]);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width, height, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, yuvFrame->data[0]);
                glUniform1i(uTexYLoc, 0);

                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, texU);
                glPixelStorei(GL_UNPACK_ROW_LENGTH, yuvFrame->linesize[1]);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width / 2, height / 2, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, yuvFrame->data[1]);
                glUniform1i(uTexULoc, 1);

                glActiveTexture(GL_TEXTURE2);
                glBindTexture(GL_TEXTURE_2D, texV);
                glPixelStorei(GL_UNPACK_ROW_LENGTH, yuvFrame->linesize[2]);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width / 2, height / 2, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, yuvFrame->data[2]);
                glUniform1i(uTexVLoc, 2);

                glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);

                glVertexAttribPointer(aPosLocation, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices);
                glEnableVertexAttribArray(aPosLocation);
                glVertexAttribPointer(aTexLocation, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices + 2);
                glEnableVertexAttribArray(aTexLocation);

                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

                {
                    std::lock_guard<std::mutex> subLock(g_subOverlayMutex);
                    if (g_subOverlay.active && g_subOverlay.width > 0 && g_subOverlay.height > 0 && !g_subOverlay.rgbaData.empty()) {
                        glUseProgram(subProgram);
                        glUniformMatrix4fv(subMvpLoc, 1, GL_FALSE, mvp);

                        glActiveTexture(GL_TEXTURE3);
                        glBindTexture(GL_TEXTURE_2D, texSub);
                        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g_subOverlay.width, g_subOverlay.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, g_subOverlay.rgbaData.data());
                        glUniform1i(subTexSamplerLoc, 3);

                        glVertexAttribPointer(subPosLoc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices);
                        glEnableVertexAttribArray(subPosLoc);
                        glVertexAttribPointer(subTexLoc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices + 2);
                        glEnableVertexAttribArray(subTexLoc);

                        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    }
                }

                eglSwapBuffers(display, surface);
            }
        }
        av_packet_free(&mp.pkt);
    }

    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
    }

    if (swsContext) sws_freeContext(swsContext);
    av_frame_free(&convertedFrame);
    av_frame_free(&frame);
}

static void subtitleDecoderThread(AVCodecContext* codecCtx, int streamIdx) {
    AVSubtitle subtitle;
    MyPacket mp;

    while (g_isPlaying) {
        if (g_subtitleQueue.get(mp) < 0) break;

        if (mp.is_flush) {
            std::lock_guard<std::mutex> lock(g_subOverlayMutex);
            g_subOverlay.active = false;
            {
                std::lock_guard<std::mutex> tLock(g_subTextMutex);
                g_currentSubText = "";
            }
            continue;
        }

        int gotSub = 0;
        if (avcodec_decode_subtitle2(codecCtx, &subtitle, &gotSub, mp.pkt) >= 0 && gotSub) {
            if (subtitle.num_rects > 0) {
                AVSubtitleRect* rect = subtitle.rects[0];
                if (rect->type == SUBTITLE_BITMAP && rect->w > 0 && rect->h > 0) {
                    std::lock_guard<std::mutex> lock(g_subOverlayMutex);
                    g_subOverlay.width = rect->w;
                    g_subOverlay.height = rect->h;
                    g_subOverlay.rgbaData.resize(rect->w * rect->h);
                    uint32_t* dst = g_subOverlay.rgbaData.data();
                    uint8_t* src = rect->data[0];
                    uint32_t* pal = (uint32_t*)rect->data[1];
                    for (int y = 0; y < rect->h; ++y) {
                        for (int x = 0; x < rect->w; ++x) {
                            dst[y * rect->w + x] = pal[src[y * rect->linesize[0] + x]];
                        }
                    }
                    g_subOverlay.active = true;
                } else if (rect->text || rect->ass) {
                    std::lock_guard<std::mutex> tLock(g_subTextMutex);
                    if (rect->text) g_currentSubText = rect->text;
                    else if (rect->ass) {
                        std::string assStr = rect->ass;
                        size_t pos = assStr.rfind(",");
                        if (pos != std::string::npos) g_currentSubText = assStr.substr(pos + 1);
                        else g_currentSubText = assStr;
                    }
                }
            } else {
                std::lock_guard<std::mutex> tLock(g_subTextMutex);
                g_currentSubText = "";
            }
            avsubtitle_free(&subtitle);
        }
        av_packet_free(&mp.pkt);
    }
}

static void audioDecoderThread(AVCodecContext* codecCtx, int streamIdx, double timeBase) {
    AVFrame* frame = av_frame_alloc();
    AVFrame* filterFrame = av_frame_alloc();
    AAudioStream* aaudioStream = nullptr;

    AVFilterGraph* filterGraph = nullptr;
    AVFilterContext* srcCtx = nullptr;
    AVFilterContext* sinkCtx = nullptr;

    float currentSpeed = 1.0f;

    auto setupAudioFilter = [&](float speed) {
        if (filterGraph) {
            avfilter_graph_free(&filterGraph);
            filterGraph = nullptr;
        }

        filterGraph = avfilter_graph_alloc();
        const AVFilter* abuffer = avfilter_get_by_name("abuffer");
        const AVFilter* abuffersink = avfilter_get_by_name("abuffersink");
        const AVFilter* atempo = avfilter_get_by_name("atempo");
        const AVFilter* aformat = avfilter_get_by_name("aformat");

        std::ostringstream args;
        args << "sample_rate=" << codecCtx->sample_rate
             << ":sample_fmt=" << av_get_sample_fmt_name(codecCtx->sample_fmt)
             << ":time_base=" << codecCtx->time_base.num << "/" << codecCtx->time_base.den;
        if (codecCtx->ch_layout.order != AV_CHANNEL_ORDER_UNSPEC) {
            char buf[64];
            av_channel_layout_describe(&codecCtx->ch_layout, buf, sizeof(buf));
            args << ":channel_layout=" << buf;
        } else {
            args << ":channel_layout=stereo";
        }

        avfilter_graph_create_filter(&srcCtx, abuffer, "in", args.str().c_str(), nullptr, filterGraph);
        avfilter_graph_create_filter(&sinkCtx, abuffersink, "out", nullptr, nullptr, filterGraph);

        AVFilterContext* atempoCtx = nullptr;
        std::ostringstream atempoArgs;
        atempoArgs << "tempo=" << speed;
        avfilter_graph_create_filter(&atempoCtx, atempo, "atempo", atempoArgs.str().c_str(), nullptr, filterGraph);

        AVFilterContext* aformatCtx = nullptr;
        avfilter_graph_create_filter(&aformatCtx, aformat, "aformat", "sample_fmts=s16:sample_rates=48000:channel_layouts=stereo", nullptr, filterGraph);

        avfilter_link(srcCtx, 0, atempoCtx, 0);
        avfilter_link(atempoCtx, 0, aformatCtx, 0);
        avfilter_link(aformatCtx, 0, sinkCtx, 0);

        avfilter_graph_config(filterGraph, nullptr);
        currentSpeed = speed;
    };

    setupAudioFilter(g_playbackSpeed.load());

    AAudioStreamBuilder* builder = nullptr;
    AAudio_createStreamBuilder(&builder);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_openStream(builder, &aaudioStream);
    AAudioStreamBuilder_delete(builder);
    AAudioStream_requestStart(aaudioStream);

    MyPacket mp;
    while (g_isPlaying) {
        float reqSpeed = g_playbackSpeed.load();
        if (std::abs(reqSpeed - currentSpeed) > 0.01f) {
            setupAudioFilter(reqSpeed);
        }

        if (g_audioQueue.get(mp) < 0) break;

        if (mp.is_flush) {
            avcodec_flush_buffers(codecCtx);
            if (aaudioStream) {
                AAudioStream_requestFlush(aaudioStream);
                AAudioStream_requestStart(aaudioStream);
            }
            continue;
        }

        if (avcodec_send_packet(codecCtx, mp.pkt) == 0) {
            while (avcodec_receive_frame(codecCtx, frame) == 0) {
                if (!g_isPlaying) break;

                if (frame->pts != AV_NOPTS_VALUE) {
                    g_audioClockMs.store(static_cast<int64_t>(frame->pts * timeBase * 1000));
                }

                if (av_buffersrc_add_frame_flags(srcCtx, frame, AV_BUFFERSRC_FLAG_KEEP_REF) >= 0) {
                    while (av_buffersink_get_frame(sinkCtx, filterFrame) >= 0) {
                        if (filterFrame->nb_samples > 0 && aaudioStream) {
                            AAudioStream_write(aaudioStream, filterFrame->data[0], filterFrame->nb_samples, 100000000LL);
                        }
                        av_frame_unref(filterFrame);
                    }
                }
            }
        }
        av_packet_free(&mp.pkt);
    }

    if (aaudioStream) {
        AAudioStream_requestStop(aaudioStream);
        AAudioStream_close(aaudioStream);
    }
    if (filterGraph) avfilter_graph_free(&filterGraph);
    av_frame_free(&filterFrame);
    av_frame_free(&frame);
}

static void demuxPlaybackWorker(std::string videoUrl) {
    g_videoWidth.store(0);
    g_videoHeight.store(0);
    g_videoQueue.start();
    g_audioQueue.start();
    g_subtitleQueue.start();

    AVFormatContext* formatCtx = avformat_alloc_context();
    if (avformat_open_input(&formatCtx, videoUrl.c_str(), nullptr, nullptr) != 0) {
        LOGE("Failed to open input: %s", videoUrl.c_str());
        g_isPlaying = false;
        return;
    }
    avformat_find_stream_info(formatCtx, nullptr);
    g_durationMs = formatCtx->duration != AV_NOPTS_VALUE ? formatCtx->duration / (AV_TIME_BASE / 1000) : 0;

    pthread_mutex_lock(&g_tracksMutex);
    g_tracks.clear();
    int vIdx = -1, aIdx = -1, subIdx = -1;
    for (unsigned int i = 0; i < formatCtx->nb_streams; ++i) {
        AVStream* st = formatCtx->streams[i];
        AVMediaType type = st->codecpar->codec_type;

        std::string lang = "und", title = "";
        AVDictionaryEntry* langEntry = av_dict_get(st->metadata, "language", nullptr, 0);
        if (langEntry && langEntry->value) lang = langEntry->value;
        AVDictionaryEntry* titleEntry = av_dict_get(st->metadata, "title", nullptr, 0);
        if (titleEntry && titleEntry->value) title = titleEntry->value;

        const AVCodec* c = avcodec_find_decoder(st->codecpar->codec_id);
        std::string codecName = c ? c->name : "unknown";

        if (type == AVMEDIA_TYPE_VIDEO) {
            if (vIdx < 0) vIdx = i;
            if (title.empty()) title = "Video Track " + std::to_string(i);
            g_tracks.push_back({(int)i, 0, title, lang, codecName});
        } else if (type == AVMEDIA_TYPE_AUDIO) {
            if (aIdx < 0) aIdx = i;
            if (title.empty()) title = "Audio Track " + std::to_string(i) + " (" + lang + ")";
            g_tracks.push_back({(int)i, 1, title, lang, codecName});
        } else if (type == AVMEDIA_TYPE_SUBTITLE) {
            if (subIdx < 0) subIdx = i;
            if (title.empty()) title = "Subtitle " + std::to_string(i) + " (" + lang + ")";
            g_tracks.push_back({(int)i, 2, title, lang, codecName});
        }
    }
    pthread_mutex_unlock(&g_tracksMutex);

    pthread_mutex_lock(&g_chaptersMutex);
    g_chapters.clear();
    for (unsigned int i = 0; i < formatCtx->nb_chapters; ++i) {
        AVChapter* ch = formatCtx->chapters[i];
        std::string title = "Chapter " + std::to_string(i + 1);
        AVDictionaryEntry* tEntry = av_dict_get(ch->metadata, "title", nullptr, 0);
        if (tEntry && tEntry->value) title = tEntry->value;

        int64_t startMs = static_cast<int64_t>(ch->start * av_q2d(ch->time_base) * 1000);
        int64_t endMs = static_cast<int64_t>(ch->end * av_q2d(ch->time_base) * 1000);
        g_chapters.push_back({(int)i, title, startMs, endMs});
    }
    pthread_mutex_unlock(&g_chaptersMutex);

    g_selectedAudioStreamIndex.store(aIdx);
    g_selectedSubtitleStreamIndex.store(subIdx);

    AVCodecContext* vCtx = nullptr;
    std::thread vThread;
    if (vIdx >= 0) {
        const AVCodec* vCodec = nullptr;
        if (g_useHwAcceleration.load()) {
            vCodec = avcodec_find_decoder_by_name("h264_mediacodec");
            if (!vCodec) vCodec = avcodec_find_decoder(formatCtx->streams[vIdx]->codecpar->codec_id);
        } else {
            vCodec = avcodec_find_decoder(formatCtx->streams[vIdx]->codecpar->codec_id);
        }

        if (vCodec) {
            vCtx = avcodec_alloc_context3(vCodec);
            avcodec_parameters_to_context(vCtx, formatCtx->streams[vIdx]->codecpar);
            vCtx->thread_count = 4;
            if (avcodec_open2(vCtx, vCodec, nullptr) < 0 && g_useHwAcceleration.load()) {
                avcodec_free_context(&vCtx);
                vCodec = avcodec_find_decoder(formatCtx->streams[vIdx]->codecpar->codec_id);
                if (vCodec) {
                    vCtx = avcodec_alloc_context3(vCodec);
                    avcodec_parameters_to_context(vCtx, formatCtx->streams[vIdx]->codecpar);
                    vCtx->thread_count = 4;
                    avcodec_open2(vCtx, vCodec, nullptr);
                }
            }
            if (vCtx) {
                vThread = std::thread(videoDecoderThread, vCtx, vIdx, av_q2d(formatCtx->streams[vIdx]->time_base));
            }
        }
    }

    AVCodecContext* aCtx = nullptr;
    std::thread aThread;
    auto initAudioDecoder = [&](int streamIndex) {
        if (aThread.joinable()) {
            g_audioQueue.abort();
            aThread.join();
            g_audioQueue.start();
        }
        if (aCtx) {
            avcodec_free_context(&aCtx);
            aCtx = nullptr;
        }
        if (streamIndex >= 0 && streamIndex < (int)formatCtx->nb_streams) {
            const AVCodec* aCodec = avcodec_find_decoder(formatCtx->streams[streamIndex]->codecpar->codec_id);
            if (aCodec) {
                aCtx = avcodec_alloc_context3(aCodec);
                avcodec_parameters_to_context(aCtx, formatCtx->streams[streamIndex]->codecpar);
                if (avcodec_open2(aCtx, aCodec, nullptr) >= 0) {
                    aThread = std::thread(audioDecoderThread, aCtx, streamIndex, av_q2d(formatCtx->streams[streamIndex]->time_base));
                }
            }
        }
    };

    if (aIdx >= 0) {
        initAudioDecoder(aIdx);
    }

    AVCodecContext* subCtx = nullptr;
    std::thread subThread;
    auto initSubtitleDecoder = [&](int streamIndex) {
        if (subThread.joinable()) {
            g_subtitleQueue.abort();
            subThread.join();
            g_subtitleQueue.start();
        }
        if (subCtx) {
            avcodec_free_context(&subCtx);
            subCtx = nullptr;
        }
        if (streamIndex >= 0 && streamIndex < (int)formatCtx->nb_streams) {
            const AVCodec* subCodec = avcodec_find_decoder(formatCtx->streams[streamIndex]->codecpar->codec_id);
            if (subCodec) {
                subCtx = avcodec_alloc_context3(subCodec);
                avcodec_parameters_to_context(subCtx, formatCtx->streams[streamIndex]->codecpar);
                if (avcodec_open2(subCtx, subCodec, nullptr) >= 0) {
                    subThread = std::thread(subtitleDecoderThread, subCtx, streamIndex);
                }
            }
        }
    };

    if (subIdx >= 0) {
        initSubtitleDecoder(subIdx);
    }

    AVPacket* pkt = av_packet_alloc();
    while (g_isPlaying) {
        int reqAudio = g_requestedAudioStreamIndex.exchange(-2);
        if (reqAudio >= -1) {
            aIdx = reqAudio;
            g_selectedAudioStreamIndex.store(aIdx);
            g_audioQueue.flush();
            initAudioDecoder(aIdx);
        }

        int reqSub = g_requestedSubtitleStreamIndex.exchange(-2);
        if (reqSub >= -1) {
            subIdx = reqSub;
            g_selectedSubtitleStreamIndex.store(subIdx);
            g_subtitleQueue.flush();
            initSubtitleDecoder(subIdx);
        }

        int64_t targetSeek = g_seekTargetMs.exchange(-1);
        if (targetSeek >= 0) {
            av_seek_frame(formatCtx, -1, targetSeek * 1000, AVSEEK_FLAG_BACKWARD);
            g_videoQueue.flush();
            g_audioQueue.flush();
            g_subtitleQueue.flush();
            g_videoQueue.put_flush();
            g_audioQueue.put_flush();
            g_subtitleQueue.put_flush();
            g_audioClockMs.store(targetSeek);
            g_currentPositionMs.store(targetSeek);
        }

        if (g_videoQueue.q.size() > 50 || g_audioQueue.q.size() > 100) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        if (av_read_frame(formatCtx, pkt) < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        if (pkt->stream_index == vIdx) g_videoQueue.put(pkt);
        else if (pkt->stream_index == aIdx) g_audioQueue.put(pkt);
        else if (pkt->stream_index == subIdx) g_subtitleQueue.put(pkt);

        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);

    g_videoQueue.abort();
    g_audioQueue.abort();
    g_subtitleQueue.abort();

    if (vThread.joinable()) vThread.join();
    if (aThread.joinable()) aThread.join();
    if (subThread.joinable()) subThread.join();

    if (vCtx) avcodec_free_context(&vCtx);
    if (aCtx) avcodec_free_context(&aCtx);
    if (subCtx) avcodec_free_context(&subCtx);
    avformat_close_input(&formatCtx);
}

extern "C" {

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_setSurface(JNIEnv* env, jobject thiz, jobject surface) {
    pthread_mutex_lock(&g_windowMutex);
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
    if (surface) {
        g_nativeWindow = ANativeWindow_fromSurface(env, surface);
    }
    pthread_mutex_unlock(&g_windowMutex);
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_playVideo(JNIEnv* env, jobject thiz, jstring jurl) {
    if (g_isPlaying) {
        g_isPlaying = false;
        g_videoQueue.abort();
        g_audioQueue.abort();
        g_subtitleQueue.abort();
        if (g_demuxThread.joinable()) g_demuxThread.join();
    }
    const char* urlChars = env->GetStringUTFChars(jurl, nullptr);
    std::string url(urlChars);
    env->ReleaseStringUTFChars(jurl, urlChars);

    g_isPlaying = true;
    g_demuxThread = std::thread(demuxPlaybackWorker, url);
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_stop(JNIEnv* env, jobject thiz) {
    g_isPlaying = false;
    g_videoQueue.abort();
    g_audioQueue.abort();
    g_subtitleQueue.abort();
    if (g_demuxThread.joinable()) g_demuxThread.join();
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_seekTo(JNIEnv* env, jobject thiz, jlong posMs) {
    g_seekTargetMs.store(static_cast<int64_t>(posMs));
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_setSpeed(JNIEnv* env, jobject thiz, jfloat speed) {
    g_playbackSpeed.store(static_cast<float>(speed));
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_setAspectRatioMode(JNIEnv* env, jobject thiz, jint mode) {
    g_aspectRatioMode.store(static_cast<int>(mode));
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_setZoom(JNIEnv* env, jobject thiz, jfloat zoom) {
    g_zoomScale.store(static_cast<float>(zoom));
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_setHardwareAccelerationEnabled(JNIEnv* env, jobject thiz, jboolean enabled) {
    g_useHwAcceleration.store(static_cast<bool>(enabled));
}

JNIEXPORT jlong JNICALL Java_arc_snora_player_SnoraNativeEngine_getDurationMs(JNIEnv* env, jobject thiz) { return g_durationMs.load(); }
JNIEXPORT jlong JNICALL Java_arc_snora_player_SnoraNativeEngine_getCurrentPositionMs(JNIEnv* env, jobject thiz) { return g_currentPositionMs.load(); }
JNIEXPORT jboolean JNICALL Java_arc_snora_player_SnoraNativeEngine_isPlaying(JNIEnv* env, jobject thiz) { return g_isPlaying.load(); }
JNIEXPORT jint JNICALL Java_arc_snora_player_SnoraNativeEngine_getVideoWidth(JNIEnv* env, jobject thiz) { return g_videoWidth.load(); }
JNIEXPORT jint JNICALL Java_arc_snora_player_SnoraNativeEngine_getVideoHeight(JNIEnv* env, jobject thiz) { return g_videoHeight.load(); }

JNIEXPORT jstring JNICALL Java_arc_snora_player_SnoraNativeEngine_getTracksJson(JNIEnv* env, jobject thiz) {
    pthread_mutex_lock(&g_tracksMutex);
    std::ostringstream json;
    json << "[";
    for (size_t i = 0; i < g_tracks.size(); ++i) {
        const auto& t = g_tracks[i];
        if (i > 0) json << ",";
        json << "{\"id\":" << t.index
             << ",\"type\":" << t.type
             << ",\"title\":\"" << escapeJson(t.title) << "\""
             << ",\"lang\":\"" << escapeJson(t.lang) << "\""
             << ",\"codec\":\"" << escapeJson(t.codec) << "\""
             << ",\"isSelected\":" << ((t.type == 1 && t.index == g_selectedAudioStreamIndex.load()) ||
                                       (t.type == 2 && t.index == g_selectedSubtitleStreamIndex.load()) ? "true" : "false")
             << "}";
    }
    json << "]";
    pthread_mutex_unlock(&g_tracksMutex);
    return env->NewStringUTF(json.str().c_str());
}

JNIEXPORT jstring JNICALL Java_arc_snora_player_SnoraNativeEngine_getChaptersJson(JNIEnv* env, jobject thiz) {
    pthread_mutex_lock(&g_chaptersMutex);
    std::ostringstream json;
    json << "[";
    for (size_t i = 0; i < g_chapters.size(); ++i) {
        const auto& c = g_chapters[i];
        if (i > 0) json << ",";
        json << "{\"id\":" << c.id
             << ",\"title\":\"" << escapeJson(c.title) << "\""
             << ",\"startMs\":" << c.startMs
             << ",\"endMs\":" << c.endMs
             << "}";
    }
    json << "]";
    pthread_mutex_unlock(&g_chaptersMutex);
    return env->NewStringUTF(json.str().c_str());
}

JNIEXPORT jstring JNICALL Java_arc_snora_player_SnoraNativeEngine_getCurrentSubtitleText(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_subTextMutex);
    return env->NewStringUTF(g_currentSubText.c_str());
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_setAudioTrack(JNIEnv* env, jobject thiz, jint streamIndex) {
    g_requestedAudioStreamIndex.store(static_cast<int>(streamIndex));
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_setSubtitleTrack(JNIEnv* env, jobject thiz, jint streamIndex) {
    g_requestedSubtitleStreamIndex.store(static_cast<int>(streamIndex));
}

}
