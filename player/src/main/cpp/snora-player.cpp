#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <string>
#include <atomic>
#include <thread>
#include <chrono>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
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

static void cleanupWindow() {
    pthread_mutex_lock(&g_windowMutex);
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
    pthread_mutex_unlock(&g_windowMutex);
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
    }

    int videoStreamIndex = -1;
    for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
        if (formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIndex = static_cast<int>(i);
            break;
        }
    }

    if (videoStreamIndex == -1) {
        LOGE("No video stream found");
        avformat_close_input(&formatContext);
        g_isPlaying = false;
        return;
    }

    AVStream* videoStream = formatContext->streams[videoStreamIndex];
    AVCodecParameters* codecParameters = videoStream->codecpar;
    const AVCodec* codec = avcodec_find_decoder(codecParameters->codec_id);
    if (!codec) {
        LOGE("Codec not found for ID: %d", codecParameters->codec_id);
        avformat_close_input(&formatContext);
        g_isPlaying = false;
        return;
    }

    AVCodecContext* codecContext = avcodec_alloc_context3(codec);
    if (!codecContext) {
        LOGE("Failed to allocate codec context");
        avformat_close_input(&formatContext);
        g_isPlaying = false;
        return;
    }

    if (avcodec_parameters_to_context(codecContext, codecParameters) < 0) {
        LOGE("Failed to copy codec parameters");
        avcodec_free_context(&codecContext);
        avformat_close_input(&formatContext);
        g_isPlaying = false;
        return;
    }

    codecContext->thread_count = 4;
    codecContext->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;

    if (avcodec_open2(codecContext, codec, nullptr) < 0) {
        LOGE("Failed to open codec");
        avcodec_free_context(&codecContext);
        avformat_close_input(&formatContext);
        g_isPlaying = false;
        return;
    }

    int width = codecContext->width;
    int height = codecContext->height;
    LOGI("Video size: %dx%d", width, height);

    pthread_mutex_lock(&g_windowMutex);
    if (g_nativeWindow) {
        ANativeWindow_setBuffersGeometry(g_nativeWindow, width, height, WINDOW_FORMAT_RGBA_8888);
    }
    pthread_mutex_unlock(&g_windowMutex);

    struct SwsContext* swsContext = sws_getContext(
        width,
        height,
        codecContext->pix_fmt,
        width,
        height,
        AV_PIX_FMT_RGBA,
        SWS_BILINEAR,
        nullptr,
        nullptr,
        nullptr
    );

    if (!swsContext) {
        LOGE("Failed to initialize SwsContext");
        avcodec_free_context(&codecContext);
        avformat_close_input(&formatContext);
        g_isPlaying = false;
        return;
    }

    AVFrame* frame = av_frame_alloc();
    AVFrame* rgbaFrame = av_frame_alloc();
    AVPacket* packet = av_packet_alloc();

    int numBytes = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
    uint8_t* rgbaBuffer = static_cast<uint8_t*>(av_malloc(numBytes * sizeof(uint8_t)));
    av_image_fill_arrays(rgbaFrame->data, rgbaFrame->linesize, rgbaBuffer, AV_PIX_FMT_RGBA, width, height, 1);

    double timeBase = av_q2d(videoStream->time_base);
    int64_t lastPts = 0;
    auto lastFrameTime = std::chrono::steady_clock::now();

    while (g_isPlaying) {
        int readResult = av_read_frame(formatContext, packet);
        if (readResult < 0) {
            LOGI("End of stream or read error");
            break;
        }

        if (packet->stream_index == videoStreamIndex) {
            int sendResult = avcodec_send_packet(codecContext, packet);
            if (sendResult == 0) {
                while (avcodec_receive_frame(codecContext, frame) == 0) {
                    if (!g_isPlaying) break;

                    sws_scale(
                        swsContext,
                        frame->data,
                        frame->linesize,
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
                            int srcStride = rgbaFrame->linesize[0];
                            int dstStride = windowBuffer.stride * 4;
                            int copyBytes = width * 4;

                            for (int y = 0; y < height; ++y) {
                                memcpy(dst + y * dstStride, src + y * srcStride, copyBytes);
                            }

                            ANativeWindow_unlockAndPost(g_nativeWindow);
                        }
                    }
                    pthread_mutex_unlock(&g_windowMutex);

                    if (frame->pts != AV_NOPTS_VALUE) {
                        int64_t currentPtsMs = static_cast<int64_t>(frame->pts * timeBase * 1000);
                        g_currentPositionMs = currentPtsMs;

                        if (lastPts > 0 && currentPtsMs > lastPts) {
                            int64_t delayMs = currentPtsMs - lastPts;
                            if (delayMs > 0 && delayMs < 200) {
                                std::this_thread::sleep_for(std::chrono::milliseconds(delayMs));
                            }
                        }
                        lastPts = currentPtsMs;
                    } else {
                        std::this_thread::sleep_for(std::chrono::milliseconds(33));
                    }
                }
            }
        }
        av_packet_unref(packet);
    }

    av_free(rgbaBuffer);
    av_frame_free(&rgbaFrame);
    av_frame_free(&frame);
    av_packet_free(&packet);
    sws_freeContext(swsContext);
    avcodec_free_context(&codecContext);
    avformat_close_input(&formatContext);

    g_isPlaying = false;
    LOGI("Native playback finished");
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

}
