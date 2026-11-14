cmake_minimum_required(VERSION 3.22.1)

# libcliptranslate_jni.so = JNI 桥 + 静态链进去的 llama.cpp。
# backend 有三个，全部静态注册（GGML_BACKEND_DL=OFF）：CPU / OpenCL(Adreno) / Vulkan，
# 具体用哪个由 cliptranslate_jni.cpp 按 Kotlin 传来的 gpuBackend 在运行时决定。
#
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
# 后端开关：默认三个都上；CI/真机上某个后端出问题时改这里（不动下面的逻辑），
# 关掉 GPU 后端后 .so 就退回 CPU-only 版本，Kotlin 侧不需要跟着改。
# ---------------------------------------------------------------------------
option(CLIPTRANSLATE_GPU_OPENCL "编译 ggml 的 OpenCL backend（Adreno GPU）" ON)
option(CLIPTRANSLATE_GPU_VULKAN "编译 ggml 的 Vulkan backend" ON)

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

# CMake 3.24+ 默认策略 CMP0135 会为 URL 下载报警告；3.22 不认识这个关键字，
# 所以按版本给。CI 用的是 3.22.1，这一支只是给将来升级留的。
set(_fetch_extra "")
if(CMAKE_VERSION VERSION_GREATER_EQUAL 3.24)
    set(_fetch_extra DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
endif()

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
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)   # 三个 backend 都静态注册，不 dlopen
set(GGML_CPU        ON  CACHE BOOL "" FORCE)
# GGML_CPU_KLEIDIAI 保持默认 OFF：它会再去 github.com/ARM-software 拉一个 tarball
# （多一次网络依赖 + 更长的 CI 编译时间）。要压榨 arm64 性能时再打开。
# GGML_STATIC 千万别开：它会加 -static 链接选项，Android 上直接链不上。

# ---------------------------------------------------------------------------
# OpenCL backend 的前置条件（必须在 FetchContent_MakeAvailable(llama_cpp) 之前准备好）
#
# ggml/src/ggml-opencl/CMakeLists.txt 第一行就是 find_package(OpenCL REQUIRED)，
# 而 NDK 里既没有 CL 头、也没有 libOpenCL.so，所以两样都得我们自己给：
#
#   1) 头文件：Khronos OpenCL-Headers（纯头文件仓库），版本钉死。
#   2) 链接期的库：真机上 /vendor/etc/public.libraries.txt 里登记了 libOpenCL.so
#      （高通自带的 OpenCL 实现，内部再加载 libOpenCL_adreno.so），应用进程可以直接
#      按 soname 解析到 /vendor/lib64/libOpenCL.so。所以 APK 里不需要、也不应该塞
#      任何 ICD loader——Khronos 那个 loader 要靠 /vendor/etc/OpenCL/vendors/*.icd
#      找驱动，而小米 15 Ultra 上这个目录根本不存在。
#
#      这里需要的是一个**只用于链接**的桩：把 CL 头里所有 clXxx 函数名抠出来，生成
#      空函数体，编成一个 SONAME=libOpenCL.so 的 .so，交给 find_package(OpenCL)。
#      于是我们的 .so 记录 DT_NEEDED=libOpenCL.so，运行时由系统的 vendor 库满足；
#      桩本身不打进 APK（它不是 CMake 里的 target，AGP 只打包 native target 的产物，
#      CI 里还有一道守卫检查 APK 里没有 libOpenCL.so）。
#      已核对：ggml-opencl 引用到的 37 个 CL 函数在该真机 /vendor/lib64/libOpenCL.so
#      里全部有导出，不会出现 “cannot locate symbol” 导致整个 .so 加载失败。
# ---------------------------------------------------------------------------
if(ANDROID AND CLIPTRANSLATE_GPU_OPENCL)
    set(OPENCL_HEADERS_TAG "v2025.07.22" CACHE STRING "Khronos OpenCL-Headers release tag")
    FetchContent_Declare(
            opencl_headers
            URL https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/${OPENCL_HEADERS_TAG}.tar.gz
            ${_fetch_extra}
    )
    # 只下载解包，不加进构建：我们只借它的 CL/*.h，不需要它自己的 CMake 工程。
    FetchContent_GetProperties(opencl_headers)
    if(NOT opencl_headers_POPULATED)
        FetchContent_Populate(opencl_headers)
    endif()
    if(NOT EXISTS "${opencl_headers_SOURCE_DIR}/CL/cl.h")
        message(FATAL_ERROR "OpenCL-Headers 里没找到 CL/cl.h，检查 OPENCL_HEADERS_TAG")
    endif()

    # FindOpenCL 就认这两个变量名。
    set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "Khronos OpenCL headers" FORCE)

    file(READ "${opencl_headers_SOURCE_DIR}/CL/cl.h" _cl_h)
    file(READ "${opencl_headers_SOURCE_DIR}/CL/cl_ext.h" _cl_ext)
    # CL 的 C API 声明形如 `cl_int CL_API_CALL clGetPlatformIDs(cl_uint, ...)`，
    # 函数名一律是 cl + 大写字母开头；类型/宏都是小写或全大写，不会误抓。
    string(REGEX MATCHALL "cl[A-Z][A-Za-z0-9_]*" _cl_names "${_cl_h}\n${_cl_ext}")
    list(REMOVE_DUPLICATES _cl_names)
    list(LENGTH _cl_names _cl_name_count)
    if(_cl_name_count LESS 100)
        message(FATAL_ERROR "从 OpenCL 头里只抠出 ${_cl_name_count} 个函数名，正则失效了")
    endif()

    set(_cl_stub_dir "${CMAKE_CURRENT_BINARY_DIR}/opencl-link-stub")
    file(MAKE_DIRECTORY "${_cl_stub_dir}")
    set(_cl_stub_src "/* 自动生成，别手改：CL 入口的空实现，只用来让链接器定下\n"
                     "   DT_NEEDED=libOpenCL.so，运行时装的是系统 vendor 那份实现。 */\n")
    foreach(_cl_name IN LISTS _cl_names)
        string(APPEND _cl_stub_src "void ${_cl_name}(void) {}\n")
    endforeach()
    file(WRITE "${_cl_stub_dir}/opencl_link_stub.c" "${_cl_stub_src}")

    set(_cl_stub_so "${_cl_stub_dir}/libOpenCL.so")
    set(_cl_target_flag "")
    if(CMAKE_C_COMPILER_TARGET)
        # NDK 的 clang 不带 --target 就按 host（x86_64）出 ELF，aarch64 链接器会拒绝。
        set(_cl_target_flag "--target=${CMAKE_C_COMPILER_TARGET}")
    endif()
    # 空函数体不碰 libc，用 -nostdlib 保持最小；SONAME 必须是 libOpenCL.so，
    # 这才是运行时去 vendor 库解析时用的名字。
    execute_process(
            COMMAND "${CMAKE_C_COMPILER}" ${_cl_target_flag} -shared -fPIC -nostdlib
                    -Wl,-soname,libOpenCL.so
                    -o "${_cl_stub_so}" "${_cl_stub_dir}/opencl_link_stub.c"
            RESULT_VARIABLE _cl_stub_rc
            OUTPUT_VARIABLE _cl_stub_out
            ERROR_VARIABLE _cl_stub_err
    )
    if(NOT _cl_stub_rc EQUAL 0 OR NOT EXISTS "${_cl_stub_so}")
        message(FATAL_ERROR "生成 OpenCL 链接桩失败 (rc=${_cl_stub_rc})\n${_cl_stub_out}${_cl_stub_err}")
    endif()

    set(OpenCL_LIBRARY "${_cl_stub_so}" CACHE FILEPATH "OpenCL 链接桩（不进 APK）" FORCE)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    message(STATUS "OpenCL backend: 链接桩 ${_cl_stub_so}（${_cl_name_count} 个入口，运行时装 vendor libOpenCL.so）")
else()
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
endif()

# ---------------------------------------------------------------------------
# Vulkan backend 的前置条件
#
# ggml/src/ggml-vulkan/CMakeLists.txt 要求：
#   * find_package(Vulkan COMPONENTS glslc REQUIRED)
#       - 目标侧：NDK sysroot 里的 vulkan 头 + 各 API level 的 libvulkan.so stub
#         （libvulkan.so 在 /system/lib64，属于 Android 的 public library，运行时能加载）
#       - 宿主侧：glslc（shaderc 的命令行编译器）必须在 PATH 上。CI 里 apt 装
#         glslc（universe 源，见 .github/workflows/build-apk.yml）。
#         注意不是 glslang-tools：b12375 只用 glslc 编 shader，glslangValidator 已经不用了。
#   * find_package(SPIRV-Headers CONFIG REQUIRED)：ggml-vulkan-types.h 要
#     <spirv/unified1/spirv.hpp>。CI 里 apt 装 spirv-headers；万一没有就自己拉一份头，
#     并给 find_package(CONFIG) 造一个最小的 config 文件顶上。
#   * ggml-vulkan-types.h 还要 <vulkan/vulkan.hpp>（Vulkan-Hpp，C++ 头），而 NDK 的
#     sysroot 只有 C 头 vulkan.h / vulkan_core.h —— 所以另外 FetchContent 一份
#     Khronos Vulkan-Headers，在 llama.cpp 的目标建好之后用 BEFORE 挂到 ggml-vulkan 上
#     （vulkan.hpp 里 static_assert VK_HEADER_VERSION == 350，C 头必须同版本）。
#   * shader 生成用 ExternalProject 把 vulkan-shaders-gen 用**宿主**编译器编出来
#     （cmake/host-toolchain.cmake.in 自动探测宿主的 gcc/clang），然后对每个 .comp
#     调一次 glslc 生成 .spv 并内嵌进库。这一步是纯 CPU 活，交叉编译不受影响，
#     但会让 CI 多花几分钟。
# 源码里没有 #ifdef __ANDROID__ 分支，Android 上走的就是普通 Linux 路径。
# ---------------------------------------------------------------------------
if(ANDROID AND CLIPTRANSLATE_GPU_VULKAN)
    # NDK 根目录：CMake 的 Android 平台模块给 CMAKE_ANDROID_NDK，NDK 自己的
    # toolchain 文件给 ANDROID_NDK，两个都兜一下（只用于下面给 find_package 的提示）。
    set(_ndk_root "${CMAKE_ANDROID_NDK}")
    if(NOT _ndk_root)
        set(_ndk_root "${ANDROID_NDK}")
    endif()
    # NDK toolchain 的 implicit link dirs 一般够 find_package 用；这里再显式给一次候选，
    # 免得 CMake 版本差异导致 “Could NOT find Vulkan”。只在没找到时才覆盖。
    if(NOT Vulkan_INCLUDE_DIR)
        file(GLOB _vk_headers "${_ndk_root}/toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan/vulkan.h")
        if(_vk_headers)
            list(GET _vk_headers 0 _vk_header)
            get_filename_component(_vk_include "${_vk_header}" DIRECTORY)   # .../include/vulkan
            get_filename_component(_vk_include "${_vk_include}" DIRECTORY)  # .../include
            set(Vulkan_INCLUDE_DIR "${_vk_include}" CACHE PATH "NDK vulkan headers" FORCE)
        endif()
    endif()
    if(NOT Vulkan_LIBRARY)
        if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
            set(_vk_lib_abi "aarch64-linux-android")
        elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
            set(_vk_lib_abi "arm-linux-androideabi")
        elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
            set(_vk_lib_abi "x86_64-linux-android")
        else()
            set(_vk_lib_abi "i686-linux-android")
        endif()
        file(GLOB _vk_stubs "${_ndk_root}/toolchains/llvm/prebuilt/*/sysroot/usr/lib/${_vk_lib_abi}/*/libvulkan.so")
        if(_vk_stubs)
            # 目录名就是 API level（26/28/30…），取最大的那份 stub。
            list(SORT _vk_stubs)
            list(LENGTH _vk_stubs _vk_stub_count)
            math(EXPR _vk_last "${_vk_stub_count} - 1")
            list(GET _vk_stubs ${_vk_last} _vk_stub)
            set(Vulkan_LIBRARY "${_vk_stub}" CACHE FILEPATH "NDK libvulkan.so stub" FORCE)
        endif()
    endif()

    find_package(SPIRV-Headers CONFIG QUIET)
    if(NOT SPIRV-Headers_FOUND)
        set(SPIRV_HEADERS_TAG "vulkan-sdk-1.4.350.0" CACHE STRING "Khronos SPIRV-Headers release tag")
        FetchContent_Declare(
                spirv_headers
                URL https://github.com/KhronosGroup/SPIRV-Headers/archive/refs/tags/${SPIRV_HEADERS_TAG}.tar.gz
                ${_fetch_extra}
        )
        FetchContent_GetProperties(spirv_headers)
        if(NOT spirv_headers_POPULATED)
            FetchContent_Populate(spirv_headers)
        endif()
        # 头直接进全局 include（ggml-vulkan 的子目录会继承），
        # config 只是为了让上游那句 find_package(SPIRV-Headers CONFIG REQUIRED) 通过。
        include_directories("${spirv_headers_SOURCE_DIR}/include")
        set(_spirv_fake_cfg "${CMAKE_CURRENT_BINARY_DIR}/spirv-headers-config")
        file(MAKE_DIRECTORY "${_spirv_fake_cfg}")
        file(WRITE "${_spirv_fake_cfg}/SPIRV-HeadersConfig.cmake"
                "# 占位：头文件已经通过 include_directories 加进去了\nset(SPIRV-Headers_FOUND TRUE)\n")
        set(SPIRV-Headers_DIR "${_spirv_fake_cfg}" CACHE PATH "SPIRV-Headers 占位 config" FORCE)
        message(STATUS "SPIRV-Headers: 系统里没有，改用 FetchContent ${SPIRV_HEADERS_TAG}")
    endif()

    # Vulkan-Hpp（C++ 头 vulkan/vulkan.hpp）：NDK r27 的 sysroot 只有 C 头
    # vulkan.h / vulkan_core.h，没有 vulkan.hpp，而 ggml-vulkan-types.h 直接
    # `#include <vulkan/vulkan.hpp>` 用 vk:: 命名空间，所以必须自己拉一份。
    # 版本要和 SPIRV-Headers 同代，这里两个都用 vulkan-sdk-1.4.350.0
    # （Vulkan-Headers 里这个 tag 确实存在，git ls-remote 核过）。
    # 注意：vulkan.hpp 第 40 行有 `VULKAN_HPP_STATIC_ASSERT(VK_HEADER_VERSION == 350, ...)`，
    # 所以 C 头也必须是这份 1.4.350 的，不能是 NDK 里那个旧版本 —— 下面的
    # target_include_directories(... BEFORE ...) 就是为了抢在 NDK sysroot 前面。
    set(VULKAN_HEADERS_TAG "vulkan-sdk-1.4.350.0" CACHE STRING "Khronos Vulkan-Headers release tag")
    FetchContent_Declare(
            vulkan_headers
            URL https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/${VULKAN_HEADERS_TAG}.tar.gz
            ${_fetch_extra}
    )
    FetchContent_GetProperties(vulkan_headers)
    if(NOT vulkan_headers_POPULATED)
        FetchContent_Populate(vulkan_headers)
    endif()
    if(NOT EXISTS "${vulkan_headers_SOURCE_DIR}/include/vulkan/vulkan.hpp")
        message(FATAL_ERROR "Vulkan-Headers 里没找到 include/vulkan/vulkan.hpp，检查 VULKAN_HEADERS_TAG")
    endif()
    message(STATUS "Vulkan-Hpp: ${vulkan_headers_SOURCE_DIR}/include (${VULKAN_HEADERS_TAG})")

    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
        llama_cpp
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_CPP_TAG}.tar.gz
        ${_fetch_extra}
)
FetchContent_MakeAvailable(llama_cpp)

# ---------------------------------------------------------------------------
# Vulkan-Hpp 的 include 要等 ggml-vulkan 目标存在后再补，而且必须 BEFORE：
# ggml-vulkan-types.h 先 include <vulkan/vulkan_core.h>、再 include <vulkan/vulkan.hpp>，
# 两份头必须来自同一个版本（vulkan.hpp 里 static_assert VK_HEADER_VERSION == 350），
# 所以要让 FetchContent 下来那份 1.4.350 抢在 NDK sysroot 的同名头前面。
# ggml-vulkan 其余 vulkan 相关 include 只有这两个头（对着 b12375 源码 grep 过），
# Vulkan-Headers 的 include/ 里都有（vulkan.h/.hpp/_core.h/funcs/structs/enums/handles
# + vk_video/，vulkan.hpp 内部还要的 vulkan_*.hpp 也齐）。
# ---------------------------------------------------------------------------
if(TARGET ggml-vulkan)
    if(DEFINED vulkan_headers_SOURCE_DIR)
        target_include_directories(ggml-vulkan BEFORE PRIVATE "${vulkan_headers_SOURCE_DIR}/include")
    endif()
endif()

# ---------------------------------------------------------------------------
# JNI 库
# ---------------------------------------------------------------------------

add_library(cliptranslate_jni SHARED cliptranslate_jni.cpp)

target_compile_options(cliptranslate_jni PRIVATE -O3 -fPIC)

target_include_directories(cliptranslate_jni PRIVATE
        ${llama_cpp_SOURCE_DIR}/include
        ${llama_cpp_SOURCE_DIR}/ggml/include)

# llama 是静态库，PUBLIC 依赖会带上 ggml / ggml-base / ggml-cpu / ggml-vulkan /
# ggml-opencl / Threads（ggml_add_backend_library 对静态 backend 用的是 PUBLIC 链接，
# 所以这里不用单独再链一遍 backend 库），ggml-base 自己会在 Android 上补 dl 和 m。
# log 是 NDK 的 liblog（__android_log_*）。
target_link_libraries(cliptranslate_jni PRIVATE llama android log)

# Android 15 起部分设备是 16KB 内存页，未对齐 16KB 的 .so 直接加载失败
# （NDK r28 才默认打开这个开关，r27 要显式给）。加上它只是让 ELF 段对齐变大，
# 4KB 页设备照样能加载。
target_link_options(cliptranslate_jni PRIVATE "-Wl,-z,max-page-size=16384")
