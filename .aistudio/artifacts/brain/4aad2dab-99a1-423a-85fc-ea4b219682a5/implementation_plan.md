# SNORA — Android Video Player Application

SNORA is a high-performance, modern Android video player built on the MPV playback core, packaged with custom precompiled FFmpeg modular libraries from `NEWWFFMCREATEE`, and designed with an independent modular architecture and dynamic Material 3 interface.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural decisions reflect user feedback from Phase 1 and established requirements:

- **Confirmed Decision 1 (Theming & Styling)**: Material You dynamic theming (`dynamicLightColorScheme` / `dynamicDarkColorScheme`) based on system wallpaper, complemented with deep surface styling for video rendering and player controls.
- **Confirmed Decision 2 (Target Architectures)**: `arm64-v8a` (primary 64-bit modern devices) and `armeabi-v7a` (32-bit legacy devices) packaged into the final APK.
- **Confirmed Decision 3 (Default Screen)**: Videos screen serving as the default start destination, offering immediate access to device media, video thumbnails, durations, and quick resume badges.
- **Code Cleanliness Requirement**: Strict compliance with the zero-comment requirement across all Kotlin and Java application source code (no inline, block, or TODO comments).

---

### 1. Overview & Core Concept

- **What It Does**: SNORA is a standalone video player for Android capable of playing virtually any video or audio format (MKV, MP4, AVI, MOV, WebM, FLV, TS, MP3, FLAC, Opus, AAC) with hardware-accelerated decoding (MediaCodec via FFmpeg), rich subtitle rendering (SSA/ASS, SRT, PGS, VobSub), multi-audio stream switching, and modern gesture-driven playback controls.
- **Target Audience / Persona**: Users who demand high-fidelity playback of local and network media files with complex codecs (10-bit HDR HEVC, AV1, DTS-HD, TrueHD, EAC3/Atmos) and styled anime/movie subtitles that standard system players cannot handle.
- **Key Value**: Delivers the playback power of MPV and custom FFmpeg builds without requiring native compilation during Gradle builds, wrapped in a clean, modern, modular Android application.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. *Browse & Select*: User launches SNORA directly into the **Videos** library tab with instant thumbnails, durations, file resolutions, and progress bars.
  2. *Folder Navigation*: User switches to the **Folders** tab to navigate specific internal storage directories or external SD/USB storage paths.
  3. *Playback & Gestures*: Tapping any video launches the fullscreen immersive **PlayerView**:
     - Swipe vertically on the left side to adjust brightness.
     - Swipe vertically on the right side to adjust volume.
     - Swipe horizontally or double-tap left/right to seek (10s increments).
     - Tap anywhere to toggle on-screen HUD (play/pause, seekbar, track selector, aspect ratio, speed, lock).
  4. *Audio & Subtitle Track Control*: Fast bottom sheet to switch between multiple embedded or external subtitle tracks and audio streams with synchronization offset adjustments (-5.0s to +5.0s).
  5. *History & Favorites*: Quick resume from the **History** tab or bookmarking favorite videos in the **Favorites** tab.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Refined Material Design 3 (M3) with fluid edge-to-edge layout, rounded container corners (16dp-24dp), and high-contrast video playback overlays.
  - *Color Palette & Mood*: Dynamic Material You color scheme pulling tonal palettes from user wallpaper, with black/translucent overlay surfaces (`Color(0xCC0A0A0E)`) during media playback for optimal visual focus.
  - *Typography & Hierarchy*: Bold M3 Headline/Title typography with clear tabular figures for video timestamps (`01:23:45 / 02:10:00`) and metadata chips.
  - *Component Styling & Layout*: Clean bottom navigation bar (`NavigationBar`), card-based media items with thumbnail placeholders, duration badges, and smooth spring-animated player controls.

- **Interactive Feedback & Motion**:
  - Controls auto-fade after 3.5 seconds of inactivity.
  - Haptic feedback on gesture seek boundaries, lock toggle, and track selection.
  - Smooth animated visibility transitions for volume and brightness HUD indicators.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Modular Multi-Project Architecture (`:app` and `:player`)**
  - *Chosen Approach*: Decouple into a `:player` Android library module (`arc.snora.player`) containing the MPV/FFmpeg engine, JNI bridge, and player controllers, and an `:app` module (`arc.snora`) containing navigation, media indexing, and UI screens.
  - *Why*: Ensures strict separation of concerns, high reusability of the playback engine, and clean dependency boundaries.
  - *Alternatives Considered*: Single monolithic module (rejected due to user requirement for an independent, reusable player library).

- **Decision 2: Direct Precompiled FFmpeg & MPV Native Integration**
  - *Chosen Approach*: Integrate precompiled shared libraries (`.so`) directly into `native-libs/` / `jniLibs` (`arm64-v8a` and `armeabi-v7a`) downloaded from the latest `NEWWFFMCREATEE` release (`ffmpeg.zip`) and upstream MPV binaries, eliminating any source-level native compilation during standard Gradle builds.
  - *Why*: Eliminates brittle NDK build script dependencies, cuts build time from 40+ minutes to seconds, and guarantees validated codec support (HEVC 10-bit, AV1, DTS, TrueHD, AC3, MediaCodec).
  - *Alternatives Considered*: Running NDK CMake/ndk-build during every build (rejected per explicit prompt instructions).

- **Decision 3: JNI & Package Namespace Compatibility Bridge**
  - *Chosen Approach*: Provide complete rebranding under package `arc.snora` for the application, while structuring the low-level JNI interface in `:player` to seamlessly bridge native JNI symbols to `arc.snora.player.SnoraPlayer` and `PlayerController`.
  - *Why*: Prevents `UnsatisfiedLinkError` when linking against precompiled C/C++ libraries while exposing a 100% clean, rebranded Kotlin API (`arc.snora.player.*`).
  - *Alternatives Considered*: Hardcoded namespace renaming without binary symbol validation (risks fatal runtime linker crashes).

- **Decision 4: Modern Jetpack Compose UI with MediaStore Indexer**
  - *Chosen Approach*: Implement modern Jetpack Compose for the entire application interface, replacing legacy XML fragments while preserving the simple, intuitive layout hierarchy of the original app.
  - *Why*: First-class support for Material You dynamic theming, declarative state management, edge-to-edge support, and seamless lifecycle handling.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

```
┌────────────────────────────────────────────────────────────────────────┐
│                              SNORA APP                                 │
│                           (arc.snora)                                  │
├────────────────────────────────────────────────────────────────────────┤
│ Navigation: Home | Videos | Folders | History | Favorites | Settings   │
│ Media Repository: MediaStore ContentResolver & File Tree Traversal     │
│ Local Database (Room): Resume Positions, History, Bookmarks            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ implementation(project(":player"))
┌───────────────────────────────────▼────────────────────────────────────┐
│                            PLAYER MODULE                               │
│                         (arc.snora.player)                             │
├────────────────────────────────────────────────────────────────────────┤
│ SnoraPlayer (High-level API)  │ PlayerController (Playback Logic)      │
│ PlayerView (Surface / Texture)│ SubtitleManager & AudioManager         │
│ AspectRatio & Speed Handlers  │ GestureOverlay (Brightness / Volume)   │
├────────────────────────────────────────────────────────────────────────┤
│ JNI Bridge Wrapper Layer      │ MPV Event Loop & Property Dispatcher   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ System.loadLibrary()
┌───────────────────────────────────▼────────────────────────────────────┐
│                       PRECOMPILED NATIVE LIBS                          │
│                      (arm64-v8a / armeabi-v7a)                         │
├────────────────────────────────────────────────────────────────────────┤
│ libmpv.so                     │ libplayer.so                           │
│ libavcodec.so                 │ libavformat.so                         │
│ libavutil.so                  │ libswresample.so                       │
│ libswscale.so                 │ libavfilter.so                         │
│ libass.so                     │ libdav1d.so                            │
└────────────────────────────────────────────────────────────────────────┘
```

#### Core Data Models & State
- `VideoMediaItem`: ID, title, URI, file path, duration, resolution, size, date modified, thumbnail URI, last playback position.
- `PlaybackState`: Idle, Buffering, Playing, Paused, Completed, Error(code, message).
- `TrackInfo`: Track ID, track type (Audio, Subtitle, Video), language, title, codec, isSelected.
- `PlayerPreferences`: Hardware decoding mode (`auto`, `mediacodec`, `sw`), subtitle font size, audio delay, aspect ratio mode (`fit`, `crop`, `stretch`, `16:9`, `4:3`).

#### Interactive Component & State Mapping
- `PlayerControls`: Exposes play/pause toggle, seek slider with real-time scrub preview, 10-second skip buttons, track selection dialogs, and control lock.
- `GestureDetector`: Handles vertical drag on left/right halves for brightness/volume and horizontal drag for fast seek with on-screen HUD feedback.
- `TrackSelectionSheet`: Modal bottom sheet listing embedded and external subtitles and audio tracks with single-tap activation.
- `FolderBrowser`: LazyColumn file explorer navigating directories with breadcrumbs and direct video launching.

---

### 5. Step-by-Step Implementation Strategy

1. **Native Libraries Acquisition & Placement**:
   - Download the precompiled FFmpeg artifacts (`ffmpeg.zip`) from the latest release of `NEWWFFMCREATEE`.
   - Extract and position the shared libraries for `arm64-v8a` and `armeabi-v7a` into the project's native library directories.
   - Configure Gradle `jniLibs.srcDirs` to package the native libraries without running any FFmpeg compilation tasks.
2. **Project Modularization**:
   - Update `settings.gradle.kts` to include `:app` and `:player`.
   - Create the `:player` Android Library module with its own `build.gradle.kts`, JNI bridge, and playback controllers.
   - Configure `:app` to depend on `:player`.
3. **Player Core Implementation (`arc.snora.player`)**:
   - Implement `SnoraPlayer`, `PlayerController`, `PlayerView`, `SubtitleManager`, `AudioManager`, and `PlaybackState`.
   - Wire hardware acceleration selection, aspect ratio manipulation, track inspection, and event handling.
4. **App UI & Navigation Implementation (`arc.snora`)**:
   - Implement M3 Material You themed scaffold with bottom navigation.
   - Build **Videos**, **Folders**, **History**, **Favorites**, and **Settings** screens.
   - Integrate `SnoraPlayerView` in full-screen immersive mode with gestures and HUD controls.
5. **Storage & Media Indexing**:
   - Integrate `MediaStore` querying for on-device videos with thumbnail loading and duration formatting.
   - Implement Room database for persistent playback resume positions and watch history.
6. **Rebranding & Verification**:
   - Configure application ID `arc.snora`, app name "SNORA", and output APK target `SNORA.apk`.
   - Verify compilation using `compile_applet` and inspect dependencies and packaging.
