cmake_minimum_required(VERSION 3.22)
project(hccr_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 32 位支持已移除：只允许 arm64-v8a（真机）与 x86_64（模拟器原生验证）
if(NOT ANDROID_ABI STREQUAL "arm64-v8a" AND NOT ANDROID_ABI STREQUAL "x86_64")
    message(FATAL_ERROR "本项目仅支持 arm64-v8a / x86_64，当前: ${ANDROID_ABI}（32 位支持已移除）")
endif()

# ncnn Android 预编译静态库（github release ncnn-20250503-android-vulkan.zip，
# 解压到 ime-native/third_party/ncnn-android/<abi>/，自带 16KB 页对齐支持）
set(NCNN_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/ncnn-android/${ANDROID_ABI})
if(NOT EXISTS ${NCNN_ROOT})
    message(FATAL_ERROR "ncnn-android 找不到: ${NCNN_ROOT}")
endif()

set(ncnn_DIR ${NCNN_ROOT}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(hccr_jni SHARED
    hccr_jni.cc
    preprocess.c
)
target_include_directories(hccr_jni PRIVATE ${CMAKE_CURRENT_LIST_DIR})
# 完整链接 ncnn（含 SPIRV/glslang/OpenMP）；JNI 走 Vulkan，无 Vulkan 设备时 NCNN 回退 CPU
target_link_libraries(hccr_jni
    PRIVATE
        ncnn
        android
        log
)
