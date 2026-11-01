cmake_minimum_required(VERSION 3.22.1)
project("player")

set(JNI_LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libavutil.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libswresample.so)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libavformat.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libswscale.so)

add_library(avfilter SHARED IMPORTED)
set_target_properties(avfilter PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libavfilter.so)

add_library(avdevice SHARED IMPORTED)
set_target_properties(avdevice PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libavdevice.so)

add_library(snora SHARED IMPORTED)
set_target_properties(snora PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libsnora.so)

add_library(player SHARED
    main.cpp
    render.cpp
    log.cpp
    jni_utils.cpp
    property.cpp
    event.cpp
    thumbnail.cpp
    snora-player.cpp
)

target_link_libraries(player
    snora
    avformat
    avcodec
    avutil
    swscale
    swresample
    avfilter
    avdevice
    android
    aaudio
    log
    EGL
    GLESv2
    GLESv3
    jnigraphics
    z
)
