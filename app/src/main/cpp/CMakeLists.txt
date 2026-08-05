cmake_minimum_required(VERSION 3.16)
project(vipercapture LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── ViPERDSP static library ──────────────────────────────────────────────────
# Reuse the existing ViPERDSP source tree sitting next to this project.
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ViPERDSP
    ${CMAKE_CURRENT_BINARY_DIR}/ViPERDSP
)

# ── JNI bridge shared library ────────────────────────────────────────────────
add_library(
    vipercapture
    SHARED
    vipercapture_jni.cpp
)

target_include_directories(vipercapture PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ViPERDSP
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ViPERDSP/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ViPERDSP/viper
)

target_link_libraries(vipercapture
    ViPERDSP
    android
    log
)

target_compile_options(vipercapture PRIVATE -O3 -ffast-math)
