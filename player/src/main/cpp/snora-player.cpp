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
#include <queue>
#include <mutex>
#include <condition_variable>
#include <sstream>
#include <algorithm>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
}

#define LOG_TAG "SnoraNativePlayer"
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

static void videoDecoderThread(AVCodecContext* codecCtx, int streamIdx, double timeBase) {
    AVFrame* frame = av_frame_alloc();
    AVFrame* rgbaFrame = av_frame_alloc();
    SwsContext* swsCtx = nullptr;
    uint8_t* rgbaBuffer = nullptr;
    int bufferSize = 0;
    
    MyPacket mp;
    while (g_isPlaying) {
        if (g_videoQueue.get(mp) < 0) break;

        if (mp.is_flush) {
            avcodec_flush_buffers(codecCtx);
            continue;
        }

        if (avcodec_send_packet(codecCtx, mp.pkt) == 0) {
            while (avcodec_receive_frame(codecCtx, frame) == 0) {
                if (!g_isPlaying) break;

                g_videoWidth.store(codecCtx->width);
                g_videoHeight.store(codecCtx->height);

                if (!swsCtx) {
                    swsCtx = sws_getContext(codecCtx->width, codecCtx->height, codecCtx->pix_fmt,
                                            codecCtx->width, codecCtx->height, AV_PIX_FMT_RGBA,
                                            SWS_BILINEAR, nullptr, nullptr, nullptr);
                    bufferSize = av_image_get_buffer_size(AV_PIX_FMT_RGBA, codecCtx->width, codecCtx->height, 1);
                    rgbaBuffer = (uint8_t*)av_malloc(bufferSize);
                    av_image_fill_arrays(rgbaFrame->data, rgbaFrame->linesize, rgbaBuffer, 
                                         AV_PIX_FMT_RGBA, codecCtx->width, codecCtx->height, 1);
                }

                sws_scale(swsCtx, frame->data, frame->linesize, 0, codecCtx->height, rgbaFrame->data, rgbaFrame->linesize);

                if (frame->pts != AV_NOPTS_VALUE) {
                    int64_t ptsMs = frame->pts * timeBase * 1000;
                    g_currentPositionMs.store(ptsMs);
                    
                    int64_t aClock = g_audioClockMs.load();
                    int64_t diff = ptsMs - aClock;
                    if (diff > 10 && diff < 5000) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(diff));
                    }
                }

                pthread_mutex_lock(&g_windowMutex);
                if (g_nativeWindow) {
                    ANativeWindow_setBuffersGeometry(g_nativeWindow, codecCtx->width, codecCtx->height, WINDOW_FORMAT_RGBA_8888);
                    ANativeWindow_Buffer winBuf;
                    if (ANativeWindow_lock(g_nativeWindow, &winBuf, nullptr) == 0) {
                        uint8_t* dst = (uint8_t*)winBuf.bits;
                        uint8_t* src = rgbaFrame->data[0];
                        int copyWidth = std::min(codecCtx->width, winBuf.width);
                        int copyHeight = std::min(codecCtx->height, winBuf.height);
                        int dstStrideBytes = winBuf.stride * 4;
                        int srcStrideBytes = rgbaFrame->linesize[0];

                        for (int y = 0; y < copyHeight; ++y) {
                            memcpy(dst + (y * dstStrideBytes), src + (y * srcStrideBytes), copyWidth * 4);
                        }
                        ANativeWindow_unlockAndPost(g_nativeWindow);
                    }
                }
                pthread_mutex_unlock(&g_windowMutex);
            }
        }
        av_packet_free(&mp.pkt);
    }
    
    if (swsCtx) sws_freeContext(swsCtx);
    if (rgbaBuffer) av_free(rgbaBuffer);
    av_frame_free(&rgbaFrame);
    av_frame_free(&frame);
}

static void audioDecoderThread(AVCodecContext* codecCtx, int streamIdx, double timeBase) {
    AVFrame* frame = av_frame_alloc();
    SwrContext* swrCtx = nullptr;
    AAudioStream* aaudioStream = nullptr;
    int16_t outBuffer[192000];

    AVChannelLayout outLayout = AV_CHANNEL_LAYOUT_STEREO;
    swr_alloc_set_opts2(&swrCtx, &outLayout, AV_SAMPLE_FMT_S16, 48000,
                        &codecCtx->ch_layout, codecCtx->sample_fmt, codecCtx->sample_rate, 0, nullptr);
    swr_init(swrCtx);

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
        if (g_audioQueue.get(mp) < 0) break;

        if (mp.is_flush) {
            avcodec_flush_buffers(codecCtx);
            AAudioStream_requestFlush(aaudioStream);
            AAudioStream_requestStart(aaudioStream);
            continue;
        }

        if (avcodec_send_packet(codecCtx, mp.pkt) == 0) {
            while (avcodec_receive_frame(codecCtx, frame) == 0) {
                if (!g_isPlaying) break;

                if (frame->pts != AV_NOPTS_VALUE) {
                    g_audioClockMs.store(frame->pts * timeBase * 1000);
                }

                uint8_t* outData[1] = { (uint8_t*)outBuffer };
                int outSamples = swr_convert(swrCtx, outData, 48000, (const uint8_t**)frame->data, frame->nb_samples);
                if (outSamples > 0 && aaudioStream) {
                    // Blocking write paces the decoding loop to real-time audio speed
                    AAudioStream_write(aaudioStream, outBuffer, outSamples, 100000000LL); 
                }
            }
        }
        av_packet_free(&mp.pkt);
    }

    if (aaudioStream) {
        AAudioStream_requestStop(aaudioStream);
        AAudioStream_close(aaudioStream);
    }
    if (swrCtx) swr_free(&swrCtx);
    av_frame_free(&frame);
}

static void demuxPlaybackWorker(std::string videoUrl) {
    g_videoWidth.store(0);
    g_videoHeight.store(0);
    
    g_videoQueue.start();
    g_audioQueue.start();

    AVFormatContext* formatCtx = avformat_alloc_context();
    if (avformat_open_input(&formatCtx, videoUrl.c_str(), nullptr, nullptr) != 0) {
        LOGE("Failed to open input");
        g_isPlaying = false;
        return;
    }
    avformat_find_stream_info(formatCtx, nullptr);
    g_durationMs = formatCtx->duration != AV_NOPTS_VALUE ? formatCtx->duration / (AV_TIME_BASE / 1000) : 0;

    int vIdx = -1, aIdx = -1;
    for (unsigned int i = 0; i < formatCtx->nb_streams; ++i) {
        if (formatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && vIdx < 0) vIdx = i;
        if (formatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && aIdx < 0) aIdx = i;
    }

    AVCodecContext* vCtx = nullptr;
    std::thread vThread;
    if (vIdx >= 0) {
        const AVCodec* vCodec = avcodec_find_decoder(formatCtx->streams[vIdx]->codecpar->codec_id);
        vCtx = avcodec_alloc_context3(vCodec);
        avcodec_parameters_to_context(vCtx, formatCtx->streams[vIdx]->codecpar);
        vCtx->thread_count = 4;
        avcodec_open2(vCtx, vCodec, nullptr);
        vThread = std::thread(videoDecoderThread, vCtx, vIdx, av_q2d(formatCtx->streams[vIdx]->time_base));
    }

    AVCodecContext* aCtx = nullptr;
    std::thread aThread;
    if (aIdx >= 0) {
        const AVCodec* aCodec = avcodec_find_decoder(formatCtx->streams[aIdx]->codecpar->codec_id);
        aCtx = avcodec_alloc_context3(aCodec);
        avcodec_parameters_to_context(aCtx, formatCtx->streams[aIdx]->codecpar);
        avcodec_open2(aCtx, aCodec, nullptr);
        aThread = std::thread(audioDecoderThread, aCtx, aIdx, av_q2d(formatCtx->streams[aIdx]->time_base));
    }

    AVPacket* pkt = av_packet_alloc();
    while (g_isPlaying) {
        int64_t targetSeek = g_seekTargetMs.exchange(-1);
        if (targetSeek >= 0) {
            av_seek_frame(formatCtx, -1, targetSeek * 1000, AVSEEK_FLAG_BACKWARD);
            g_videoQueue.flush();
            g_audioQueue.flush();
            g_videoQueue.put_flush();
            g_audioQueue.put_flush();
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
        
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);

    g_videoQueue.abort();
    g_audioQueue.abort();

    if (vThread.joinable()) vThread.join();
    if (aThread.joinable()) aThread.join();

    if (vCtx) avcodec_free_context(&vCtx);
    if (aCtx) avcodec_free_context(&aCtx);
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
    if (g_demuxThread.joinable()) g_demuxThread.join();
}

JNIEXPORT void JNICALL Java_arc_snora_player_SnoraNativeEngine_seekTo(JNIEnv* env, jobject thiz, jlong posMs) {
    g_seekTargetMs.store((int64_t)posMs);
}

JNIEXPORT jlong JNICALL Java_arc_snora_player_SnoraNativeEngine_getDurationMs(JNIEnv* env, jobject thiz) { return g_durationMs.load(); }
JNIEXPORT jlong JNICALL Java_arc_snora_player_SnoraNativeEngine_getCurrentPositionMs(JNIEnv* env, jobject thiz) { return g_currentPositionMs.load(); }
JNIEXPORT jboolean JNICALL Java_arc_snora_player_SnoraNativeEngine_isPlaying(JNIEnv* env, jobject thiz) { return g_isPlaying.load(); }
JNIEXPORT jint JNICALL Java_arc_snora_player_SnoraNativeEngine_getVideoWidth(JNIEnv* env, jobject thiz) { return g_videoWidth.load(); }
JNIEXPORT jint JNICALL Java_arc_snora_player_SnoraNativeEngine_getVideoHeight(JNIEnv* env, jobject thiz) { return g_videoHeight.load(); }
}
