#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <pthread.h>
#include <string>
#include <atomic>
#include <thread>
#include <chrono>
#include <vector>
#include <algorithm>
#include <sstream>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
}

#define LOG_TAG "SnoraNativePlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static ANativeWindow* g_nativeWindow = nullptr;
static pthread_mutex_t g_windowMutex = PTHREAD_MUTEX_INITIALIZER;
static std::atomic<bool> g_isPlaying(false);
static std::thread g_playbackThread;
static std::atomic<int64_t> g_currentPositionMs(0);
static std::atomic<int64_t> g_durationMs(0);
static std::atomic<int64_t> g_seekTargetMs(-1);
static std::atomic<int64_t> g_audioClockMs(0);

static std::atomic<int> g_selectedAudioStreamIndex(-1);
static std::atomic<int> g_selectedSubtitleStreamIndex(-1);
static std::atomic<int> g_requestedAudioStreamIndex(-2);

struct NativeTrackInfo {
    int index;
    int type;
    std::string title;
    std::string lang;
    std::string codec;
};

static std::vector<NativeTrackInfo> g_tracks;
static pthread_mutex_t g_tracksMutex = PTHREAD_MUTEX_INITIALIZER;

static void cleanupWindow() {
    pthread_mutex_lock(&g_windowMutex);
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
    pthread_mutex_unlock(&g_windowMutex);
}

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

static void nativePlaybackWorker(std::string videoUrl) {
    LOGI("Starting native playback for: %s", videoUrl.c_str());

    AVFormatContext* formatContext = avformat_alloc_context();
    if (!formatContext) {
        LOGE("Failed to allocate AVFormatContext");
        g_isPlaying = false;
        return;
    }

    if (avformat_open_input(&formatContext, videoUrl.c_str(), nullptr, nullptr) != 0) {
        LOGE("Failed to open input: %s", videoUrl.c_str());
        avformat_free_context(formatContext);
        g_isPlaying = false;
        return;
    }

    if (avformat_find_stream_info(formatContext, nullptr) < 0) {
        LOGE("Failed to find stream info");
        avformat_close_input(&formatContext);
        g_isPlaying = false;
        return;
    }

    if (formatContext->duration != AV_NOPTS_VALUE) {
        g_durationMs = formatContext->duration / (AV_TIME_BASE / 1000);
    } else {
        g_durationMs = 0;
    }

    pthread_mutex_lock(&g_tracksMutex);
    g_tracks.clear();

    int defaultVideoStream = -1;
    int defaultAudioStream = -1;

    for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
        AVStream* st = formatContext->streams[i];
        AVMediaType type = st->codecpar->codec_type;

        std::string lang = "und";
        std::string title = "";

        AVDictionaryEntry* langEntry = av_dict_get(st->metadata, "language", nullptr, 0);
        if (langEntry && langEntry->value) {
            lang = langEntry->value;
        }

        AVDictionaryEntry* titleEntry = av_dict_get(st->metadata, "title", nullptr, 0);
        if (titleEntry && titleEntry->value) {
            title = titleEntry->value;
        }

        const AVCodec* c = avcodec_find_decoder(st->codecpar->codec_id);
        std::string codecName = c ? c->name : "unknown";

        if (type == AVMEDIA_TYPE_VIDEO) {
            if (defaultVideoStream == -1) defaultVideoStream = static_cast<int>(i);
            if (title.empty()) title = "Video Track " + std::to_string(i);
            g_tracks.push_back({ static_cast<int>(i), 0, title, lang, codecName });
        } else if (type == AVMEDIA_TYPE_AUDIO) {
            if (defaultAudioStream == -1) defaultAudioStream = static_cast<int>(i);
            if (title.empty()) title = "Audio Track " + std::to_string(i) + " (" + lang + ")";
            g_tracks.push_back({ static_cast<int>(i), 1, title, lang, codecName });
        } else if (type == AVMEDIA_TYPE_SUBTITLE) {
            if (title.empty()) title = "Subtitle " + std::to_string(i) + " (" + lang + ")";
            g_tracks.push_back({ static_cast<int>(i), 2, title, lang, codecName });
        }
    }
    pthread_mutex_unlock(&g_tracksMutex);

    int videoStreamIndex = defaultVideoStream;
    int audioStreamIndex = defaultAudioStream;
    g_selectedAudioStreamIndex.store(audioStreamIndex);

    AVCodecContext* videoCodecContext = nullptr;
    const AVCodec* videoCodec = nullptr;
    struct SwsContext* swsContext = nullptr;
    int width = 0;
    int height = 0;
    double videoTimeBase = 0.0;

    if (videoStreamIndex != -1) {
        AVStream* vs = formatContext->streams[videoStreamIndex];
        videoTimeBase = av_q2d(vs->time_base);
        videoCodec = avcodec_find_decoder(vs->codecpar->codec_id);
        if (videoCodec) {
            videoCodecContext = avcodec_alloc_context3(videoCodec);
            if (videoCodecContext && avcodec_parameters_to_context(videoCodecContext, vs->codecpar) >= 0) {
                videoCodecContext->thread_count = 4;
                videoCodecContext->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
                if (avcodec_open2(videoCodecContext, videoCodec, nullptr) >= 0) {
                    width = videoCodecContext->width;
                    height = videoCodecContext->height;
                    pthread_mutex_lock(&g_windowMutex);
                    if (g_nativeWindow) {
                        ANativeWindow_setBuffersGeometry(g_nativeWindow, width, height, WINDOW_FORMAT_RGBA_8888);
                    }
                    pthread_mutex_unlock(&g_windowMutex);

                    swsContext = sws_getContext(
                        width, height, videoCodecContext->pix_fmt,
                        width, height, AV_PIX_FMT_RGBA,
                        SWS_BILINEAR, nullptr, nullptr, nullptr
                    );
                }
            }
        }
    }

    AVCodecContext* audioCodecContext = nullptr;
    SwrContext* swrContext = nullptr;
    AAudioStream* aaudioStream = nullptr;
    double audioTimeBase = 0.0;

    auto setupAudio = [&](int streamIdx) {
        if (aaudioStream) {
            AAudioStream_requestStop(aaudioStream);
            AAudioStream_close(aaudioStream);
            aaudioStream = nullptr;
        }
        if (swrContext) {
            swr_free(&swrContext);
            swrContext = nullptr;
        }
        if (audioCodecContext) {
            avcodec_free_context(&audioCodecContext);
            audioCodecContext = nullptr;
        }

        if (streamIdx < 0 || streamIdx >= static_cast<int>(formatContext->nb_streams)) return;

        AVStream* as = formatContext->streams[streamIdx];
        audioTimeBase = av_q2d(as->time_base);
        const AVCodec* ac = avcodec_find_decoder(as->codecpar->codec_id);
        if (!ac) return;

        audioCodecContext = avcodec_alloc_context3(ac);
        if (!audioCodecContext) return;

        if (avcodec_parameters_to_context(audioCodecContext, as->codecpar) < 0 ||
            avcodec_open2(audioCodecContext, ac, nullptr) < 0) {
            avcodec_free_context(&audioCodecContext);
            audioCodecContext = nullptr;
            return;
        }

        AVChannelLayout outLayout = AV_CHANNEL_LAYOUT_STEREO;
        swr_alloc_set_opts2(
            &swrContext,
            &outLayout,
            AV_SAMPLE_FMT_S16,
            48000,
            &audioCodecContext->ch_layout,
            audioCodecContext->sample_fmt,
            audioCodecContext->sample_rate,
            0,
            nullptr
        );

        if (swrContext && swr_init(swrContext) < 0) {
            swr_free(&swrContext);
            swrContext = nullptr;
        }

        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) == AAUDIO_OK) {
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setSampleRate(builder, 48000);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            if (AAudioStreamBuilder_openStream(builder, &aaudioStream) == AAUDIO_OK) {
                AAudioStream_requestStart(aaudioStream);
            }
            AAudioStreamBuilder_delete(builder);
        }
    };

    if (audioStreamIndex != -1) {
        setupAudio(audioStreamIndex);
    }

    AVFrame* videoFrame = av_frame_alloc();
    AVFrame* rgbaFrame = av_frame_alloc();
    AVFrame* audioFrame = av_frame_alloc();
    AVPacket* packet = av_packet_alloc();

    uint8_t* rgbaBuffer = nullptr;
    if (width > 0 && height > 0) {
        int numBytes = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
        rgbaBuffer = static_cast<uint8_t*>(av_malloc(numBytes * sizeof(uint8_t)));
        av_image_fill_arrays(rgbaFrame->data, rgbaFrame->linesize, rgbaBuffer, AV_PIX_FMT_RGBA, width, height, 1);
    }

    int16_t audioOutBuffer[192000];

    while (g_isPlaying) {
        int reqAudio = g_requestedAudioStreamIndex.exchange(-2);
        if (reqAudio >= -1) {
            audioStreamIndex = reqAudio;
            g_selectedAudioStreamIndex.store(audioStreamIndex);
            setupAudio(audioStreamIndex);
        }

        int64_t targetSeek = g_seekTargetMs.exchange(-1);
        if (targetSeek >= 0) {
            int64_t seekTargetUs = targetSeek * 1000;
            av_seek_frame(formatContext, -1, seekTargetUs, AVSEEK_FLAG_BACKWARD);
            if (videoCodecContext) avcodec_flush_buffers(videoCodecContext);
            if (audioCodecContext) avcodec_flush_buffers(audioCodecContext);
            g_currentPositionMs.store(targetSeek);
            g_audioClockMs.store(targetSeek);
        }

        int readResult = av_read_frame(formatContext, packet);
        if (readResult < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            break;
        }

        if (packet->stream_index == videoStreamIndex && videoCodecContext && swsContext) {
            if (avcodec_send_packet(videoCodecContext, packet) == 0) {
                while (avcodec_receive_frame(videoCodecContext, videoFrame) == 0) {
                    if (!g_isPlaying) break;

                    sws_scale(
                        swsContext,
                        videoFrame->data,
                        videoFrame->linesize,
                        0,
                        height,
                        rgbaFrame->data,
                        rgbaFrame->linesize
                    );

                    pthread_mutex_lock(&g_windowMutex);
                    if (g_nativeWindow) {
                        ANativeWindow_Buffer windowBuffer;
                        if (ANativeWindow_lock(g_nativeWindow, &windowBuffer, nullptr) == 0) {
                            uint8_t* dst = static_cast<uint8_t*>(windowBuffer.bits);
                            uint8_t* src = rgbaFrame->data[0];

                            int copyWidth = std::min(width, windowBuffer.width);
                            int copyHeight = std::min(height, windowBuffer.height);

                            int dstStrideBytes = windowBuffer.stride * 4;
                            int srcStrideBytes = rgbaFrame->linesize[0];

                            for (int y = 0; y < copyHeight; ++y) {
                                memcpy(dst + (y * dstStrideBytes), src + (y * srcStrideBytes), copyWidth * 4);
                            }
                            ANativeWindow_unlockAndPost(g_nativeWindow);
                        }
                    }
                    pthread_mutex_unlock(&g_windowMutex);

                    if (videoFrame->pts != AV_NOPTS_VALUE) {
                        int64_t videoPtsMs = static_cast<int64_t>(videoFrame->pts * videoTimeBase * 1000);
                        g_currentPositionMs.store(videoPtsMs);

                        if (aaudioStream && audioCodecContext) {
                            int64_t currentAudioMs = g_audioClockMs.load();
                            int64_t diff = videoPtsMs - currentAudioMs;
                            if (diff > 10 && diff < 150) {
                                std::this_thread::sleep_for(std::chrono::milliseconds(diff));
                            }
                        } else {
                            std::this_thread::sleep_for(std::chrono::milliseconds(33));
                        }
                    } else {
                        std::this_thread::sleep_for(std::chrono::milliseconds(33));
                    }
                }
            }
        } else if (packet->stream_index == audioStreamIndex && audioCodecContext && swrContext && aaudioStream) {
            if (avcodec_send_packet(audioCodecContext, packet) == 0) {
                while (avcodec_receive_frame(audioCodecContext, audioFrame) == 0) {
                    if (!g_isPlaying) break;

                    uint8_t* outData[1] = { reinterpret_cast<uint8_t*>(audioOutBuffer) };
                    int outSamples = swr_convert(
                        swrContext,
                        outData,
                        48000,
                        (const uint8_t**)audioFrame->data,
                        audioFrame->nb_samples
                    );

                    if (outSamples > 0) {
                        AAudioStream_write(aaudioStream, audioOutBuffer, outSamples, 100000000LL);
                    }

                    if (audioFrame->pts != AV_NOPTS_VALUE) {
                        int64_t audioPtsMs = static_cast<int64_t>(audioFrame->pts * audioTimeBase * 1000);
                        g_audioClockMs.store(audioPtsMs);
                    }
                }
            }
        }

        av_packet_unref(packet);
    }

    if (aaudioStream) {
        AAudioStream_requestStop(aaudioStream);
        AAudioStream_close(aaudioStream);
        aaudioStream = nullptr;
    }
    if (swrContext) {
        swr_free(&swrContext);
        swrContext = nullptr;
    }
    if (audioCodecContext) {
        avcodec_free_context(&audioCodecContext);
        audioCodecContext = nullptr;
    }
    if (swsContext) {
        sws_freeContext(swsContext);
        swsContext = nullptr;
    }
    if (videoCodecContext) {
        avcodec_free_context(&videoCodecContext);
        videoCodecContext = nullptr;
    }
    if (rgbaBuffer) {
        av_free(rgbaBuffer);
    }
    av_frame_free(&rgbaFrame);
    av_frame_free(&videoFrame);
    av_frame_free(&audioFrame);
    av_packet_free(&packet);
    avformat_close_input(&formatContext);

    g_isPlaying = false;
    LOGI("Native playback loop finished");
}

extern "C" {

JNIEXPORT void JNICALL
Java_arc_snora_player_SnoraNativeEngine_setSurface(JNIEnv* env, jobject thiz, jobject surface) {
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

JNIEXPORT void JNICALL
Java_arc_snora_player_SnoraNativeEngine_playVideo(JNIEnv* env, jobject thiz, jstring jurl) {
    if (g_isPlaying) {
        g_isPlaying = false;
        if (g_playbackThread.joinable()) {
            g_playbackThread.join();
        }
    }

    const char* urlChars = env->GetStringUTFChars(jurl, nullptr);
    std::string url(urlChars);
    env->ReleaseStringUTFChars(jurl, urlChars);

    g_isPlaying = true;
    g_playbackThread = std::thread(nativePlaybackWorker, url);
}

JNIEXPORT void JNICALL
Java_arc_snora_player_SnoraNativeEngine_stop(JNIEnv* env, jobject thiz) {
    g_isPlaying = false;
    if (g_playbackThread.joinable()) {
        g_playbackThread.join();
    }
}

JNIEXPORT void JNICALL
Java_arc_snora_player_SnoraNativeEngine_seekTo(JNIEnv* env, jobject thiz, jlong positionMs) {
    g_seekTargetMs.store(static_cast<int64_t>(positionMs));
}

JNIEXPORT jlong JNICALL
Java_arc_snora_player_SnoraNativeEngine_getDurationMs(JNIEnv* env, jobject thiz) {
    return static_cast<jlong>(g_durationMs.load());
}

JNIEXPORT jlong JNICALL
Java_arc_snora_player_SnoraNativeEngine_getCurrentPositionMs(JNIEnv* env, jobject thiz) {
    return static_cast<jlong>(g_currentPositionMs.load());
}

JNIEXPORT jboolean JNICALL
Java_arc_snora_player_SnoraNativeEngine_isPlaying(JNIEnv* env, jobject thiz) {
    return static_cast<jboolean>(g_isPlaying.load());
}

JNIEXPORT jstring JNICALL
Java_arc_snora_player_SnoraNativeEngine_getTracksJson(JNIEnv* env, jobject thiz) {
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
             << ",\"isSelected\":" << ((t.type == 1 && t.index == g_selectedAudioStreamIndex.load()) || (t.type == 2 && t.index == g_selectedSubtitleStreamIndex.load()) ? "true" : "false")
             << "}";
    }
    json << "]";
    pthread_mutex_unlock(&g_tracksMutex);

    return env->NewStringUTF(json.str().c_str());
}

JNIEXPORT void JNICALL
Java_arc_snora_player_SnoraNativeEngine_setAudioTrack(JNIEnv* env, jobject thiz, jint streamIndex) {
    g_requestedAudioStreamIndex.store(static_cast<int>(streamIndex));
}

JNIEXPORT void JNICALL
Java_arc_snora_player_SnoraNativeEngine_setSubtitleTrack(JNIEnv* env, jobject thiz, jint streamIndex) {
    g_selectedSubtitleStreamIndex.store(static_cast<int>(streamIndex));
}

}
