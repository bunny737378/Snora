# SNORA Media Player: Native C++ GPU Renderer & Advanced Engine Upgrade

Upgrading the SNORA Android media player JNI engine to a high-performance, GPU-accelerated pipeline powered by OpenGL ES 2.0/3.0, EGL, MediaCodec hardware decoding, pitch-preserving audio speed filters, native subtitle overlay, and exact aspect ratio transformation matrices while preserving our 3-thread demuxing/decoding architecture and Jetpack Compose UI.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural directives were explicitly confirmed by the user:

- **Core Engine Architecture**: Retain custom JNI `snora-player.cpp` and 3-thread model (Demuxer, Audio Master Clock, Video Decoder). Do **NOT** use `libmpv` wrapper binaries.
- **Rendering Pipeline**: Replace CPU `sws_scale` and `ANativeWindow_lock` with EGL + OpenGL ES 2.0/3.0 YUV-to-RGB fragment shaders rendering directly onto `ANativeWindow` / `SurfaceView`.
- **Hardware Acceleration**: Integrate FFmpeg `hwcontext_mediacodec` (`AV_HWDEVICE_TYPE_MEDIACODEC`) with automatic fallback to software decoding on codec error.
- **Aspect Ratio & Zooming**: Implement vertex matrix transformations in GLES shaders supporting Fit, Crop, Stretch, and custom Zoom levels.
- **Audio Pitch-Preserving Speed**: Utilize FFmpeg `atempo` audio filter graph (`libavfilter`) to preserve pitch when changing speed (0.25x – 4.0x) paired with master audio clock adjustment.
- **Subtitle Rendering**: Decode native ASS/SRT tracks using `avcodec_decode_subtitle2` and render bitmap/text overlays as alpha-blended GLES textures on top of the video frame.

---

## 1. Overview & Core Concept

- **Goal**: Deliver a desktop-grade, zero-stutter Android media player capable of smoothly playing high-bitrate 4K, 10bit HDR, MKV, MP4, and web streams without memory alignment crashes or CPU bottlenecking.
- **Target Experience**: Seamless playback control with instant seek response, hardware acceleration status indicators, pitch-corrected playback speed adjustments, and accurate subtitle display.

---

## 2. User Experience & Visual Design

- **Player Interface**: Material Design 3 Jetpack Compose controls overlaid on `SurfaceView`.
- **Visual Controls**:
  - Aspect ratio toggle button (Fit / Crop / Stretch / 16:9 / 4:3) with smooth animated state transitions.
  - Speed selector chip (0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x, 3.0x) showing active `atempo` state.
  - Subtitle track & Audio track selection menus parsed dynamically from native demuxer JSON output.
  - Hardware/Software acceleration chip (`HW (MediaCodec)` vs `SW (FFmpeg)`).

---

## 3. Key Product Decisions & Trade-Offs

1. **EGL / GLES Shader Rendering vs CPU `sws_scale`**:
   - *Chosen Approach*: EGL window surface with GLSL YUV2RGB shaders (Y, U, V luminance/chrominance texture uploading).
   - *Why*: Completely eliminates CPU copy bottlenecks and stride alignment crashes (`__memcpy_aarch64_simd` fault) on odd-resolution screen recordings.
2. **FFmpeg `atempo` Filter Graph for Speed Control**:
   - *Chosen Approach*: Construct a dynamic `AVFilterGraph` containing `atempo` filters for AAudio output.
   - *Why*: Prevents high-pitched "chipmunk" or low-pitched audio distortion when playing at non-1.0x speeds.
3. **`hwcontext_mediacodec` with SW Fallback**:
   - *Chosen Approach*: Attempt `av_hwdevice_ctx_create` for MediaCodec first; if `avcodec_open2` or `avcodec_send_packet` fails, seamlessly fall back to software CPU decoding.
   - *Why*: Guarantees playability across all Android devices and unusual codec profiles.

---

## 4. Technical Architecture & Data Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Jetpack Compose UI (Kotlin)                         │
│   ┌────────────────────┐  ┌─────────────────────┐  ┌────────────────────┐   │
│   │ SnoraPlayer        │  │ Audio/Subtitle Track│  │ Aspect Ratio /     │   │
│   │ Controller State   │  │ Selectors           │  │ Zoom Matrix Controls│   │
│   └─────────┬──────────┘  └──────────┬──────────┘  └─────────┬──────────┘   │
└─────────────│────────────────────────│───────────────────────│──────────────┘
              │ JNI Calls              │ JNI Tracks JSON       │ JNI Params
              ▼                        ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          C++ Native JNI Engine                              │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                          Demuxer Thread                               │  │
│  │   • avformat_open_input / av_read_frame                               │  │
│  │   • Handles Seek Requests & Flushes Queues                            │  │
│  └──────────────┬─────────────────────────────────────┬──────────────────┘  │
│                 │ AVPacket                            │ AVPacket            │
│                 ▼                                     ▼                     │
│  ┌─────────────────────────────┐       ┌─────────────────────────────┐      │
│  │     Video Queue & Decoder   │       │     Audio Queue & Decoder   │      │
│  │  • MediaCodec / SW Decoder  │       │  • AVCodec / libavfilter    │      │
│  │  • YUV Frame Decoding       │       │  • atempo Pitch Filter      │      │
│  │  • Subtitle Overlay Frame   │       │  • AAudio Stream Output     │      │
│  └──────────────┬──────────────┘       └──────────────┬──────────────┘      │
│                 │ Decoded YUV                         │ Audio Clock Sync    │
│                 ▼                                     ▼                     │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    EGL / GLES 2.0 Render Thread                       │  │
│  │  • Y, U, V Texture Uploading (GL_LUMINANCE)                           │  │
│  │  • GPU YUV-to-RGB Fragment Shader Conversion                          │  │
│  │  • Aspect Ratio Matrix Transformations (Fit/Crop/Stretch/Zoom)        │  │
│  │  • Alpha-Blended Subtitle Texture Overlay                             │  │
│  │  • eglSwapBuffers onto ANativeWindow                                  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Native Implementation Requirements

1. **`CMakeLists.txt`**: Link `EGL`, `GLESv2`, `GLESv3`, `android`, `log`, `z`, `aaudio`, and FFmpeg shared libraries (`avformat`, `avcodec`, `avutil`, `swscale`, `swresample`, `avfilter`, `avdevice`).
2. **`snora-player.cpp`**:
   - **EGL Context**: Initialize `eglGetDisplay`, `eglInitialize`, `eglCreateWindowSurface` for `ANativeWindow`.
   - **Shaders**: Compile Vertex Shader (with transformation matrix `uMVPMatrix`) and Fragment Shader (YUV420P to RGBA color space conversion).
   - **Audio `atempo` Filter**: Setup `AVFilterGraph` with `abuffer` -> `atempo` -> `abuffersink` for pitch-preserving speed changes.
   - **MediaCodec HW Context**: Try `av_hwdevice_ctx_create(..., AV_HWDEVICE_TYPE_MEDIACODEC, ...)` for HW acceleration.
   - **Native Seek & Sync**: Flush packet queues, reset PTS clocks, perform `av_seek_frame(..., AVSEEK_FLAG_BACKWARD)`.
   - **Subtitle Overlay**: Decode `AVSubtitle` rectangles and render text/bitmap texture on GLES overlay quad.
3. **`SnoraNativeEngine.kt`**: Expose `setSpeed`, `setAspectRatioMode`, `setZoom`, `setHardwareAccelerationEnabled`, `seekTo`, `setAudioTrack`, `setSubtitleTrack`.
4. **`SnoraPlayer.kt`**: Connect Compose state flows to native engine methods.


---

## 5. Recent Refinements & Fullscreen Button Restoration
- **Fullscreen Enter/Exit**: Reintroduced the fullscreen toggle button on the bottom bar next to the speed chip (`Icons.Default.Fullscreen` in portrait, `Icons.Default.FullscreenExit` in landscape).
- **Wiring**: Connected to `controller.toggleOrientation(activity)` via `onToggleOrientation` callback in `PlayerView.kt`.
- **Integrity**: All existing features (soft shadows, slide-to-seek, aspect ratio gestures, chapter menu, unboxed HW/SW, clean subtitle disable, horizontal gesture pill) remain fully intact.
- **Compilation**: Verified and build succeeded.
