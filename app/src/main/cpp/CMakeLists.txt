cmake_minimum_required(VERSION 3.22.1)

# libcliptranslate_jni.so = JNI 桥 + 静态链进去的 llama.cpp（CPU backend）。
# llama.cpp 用 FetchContent 拉 release tarball，不用 git submodule：
# CI 上 checkout 是干净的，FetchContent 只需要网络，不会因为 submodule 没初始化而挂。
project(cliptranslate_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# llama/ggml 是静态库，最终要进我们的 .so，必须 PIC（Android 的 clang 默认就带
# -fPIC，这里写死是防止别的 toolchain 配置把它关掉）。
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# llama.cpp
# ---------------------------------------------------------------------------

# 固定的 release tag（2026-11 的最新正式 release，b12376 只有 tag 还没出 release）。
# 必须钉死版本：llama.h 的 C API 在 b7xxx → b12xxx 之间改过名/改过参数
# （llama_load_model_from_file → llama_model_load_from_file、
#   llama_new_context_with_model → llama_init_from_model、
#   llama_kv_self_clear → llama_memory_clear、
#   llama_model_params 去掉 use_mmap/use_mlock 换成 load_mode），
# cliptranslate_jni.cpp 就是照这个 tag 的头文件写的。换版本要重核 API。
set(LLAMA_CPP_TAG "b12375" CACHE STRING "llama.cpp release tag")

include(FetchContent)

# debug 包也要能真机上跑：AGP 的 Debug 构建会把 CMAKE_BUILD_TYPE 设成 Debug（-O0），
# llama.cpp 在 -O0 下推理要慢一个数量级。这里在父作用域加 -O3，llama/ggml 的目录
# 作用域会继承它，且排在 config flags 之后生效（覆盖 -O0），仍然保留 -g。
add_compile_options(-O3)

# 只编 libllama + libggml：不要 tests/examples/tools/server/app/common（common 会
# 拖进 cpp-httplib、OpenSSL、subprocess 这些 Android 上没有的东西）。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)  # OpenSSL 不在 NDK 稳定 API 里
set(LLAMA_SUBPROCESS     OFF CACHE BOOL "" FORCE)  # 移动端不支持 fork/exec
# LLAMA_CURL 在这个 tag 里已经废弃（llama_option_depr），不用再传。

# ggml：Android 交叉编译的必需项，见 llama.cpp docs/build.md “Android arm64-v8a NDK build”。
set(GGML_NATIVE     OFF CACHE BOOL "" FORCE)   # 交叉编译：绝不能按 build host 的 CPU 取优化
set(GGML_OPENMP     OFF CACHE BOOL "" FORCE)   # NDK 不带 libomp
set(GGML_LLAMAFILE  OFF CACHE BOOL "" FORCE)   # llamafile backend 在 Android 上不工作
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)   # 静态注册 CPU backend，不 dlopen
set(GGML_CPU        ON  CACHE BOOL "" FORCE)
# GGML_CPU_KLEIDIAI 保持默认 OFF：它会再去 github.com/ARM-software 拉一个 tarball
# （多一次网络依赖 + 更长的 CI 编译时间）。要压榨 arm64 性能时再打开。
# GGML_STATIC 千万别开：它会加 -static 链接选项，Android 上直接链不上。

# CMake 3.24+ 默认策略 CMP0135 会为 URL 下载报警告；3.22 不认识这个关键字，
# 所以按版本给。CI 用的是 3.22.1，这一支只是给将来升级留的。
set(_llama_fetch_extra "")
if(CMAKE_VERSION VERSION_GREATER_EQUAL 3.24)
    set(_llama_fetch_extra DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
endif()

FetchContent_Declare(
        llama_cpp
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_CPP_TAG}.tar.gz
        ${_llama_fetch_extra}
)
FetchContent_MakeAvailable(llama_cpp)

# ---------------------------------------------------------------------------
# JNI 库
# ---------------------------------------------------------------------------

add_library(cliptranslate_jni SHARED cliptranslate_jni.cpp)

target_compile_options(cliptranslate_jni PRIVATE -O3 -fPIC)

target_include_directories(cliptranslate_jni PRIVATE
        ${llama_cpp_SOURCE_DIR}/include
        ${llama_cpp_SOURCE_DIR}/ggml/include)

# llama 是静态库，PUBLIC 依赖会带上 ggml / ggml-base / ggml-cpu / Threads，
# ggml-base 自己会在 Android 上补 dl 和 m。log 是 NDK 的 liblog（__android_log_*）。
target_link_libraries(cliptranslate_jni PRIVATE llama android log)

# Android 15 起部分设备是 16KB 内存页，未对齐 16KB 的 .so 直接加载失败
# （NDK r28 才默认打开这个开关，r27 要显式给）。加上它只是让 ELF 段对齐变大，
# 4KB 页设备照样能加载。
target_link_options(cliptranslate_jni PRIVATE "-Wl,-z,max-page-size=16384")
