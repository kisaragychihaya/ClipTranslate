cmake_minimum_required(VERSION 3.22.1)

# libcliptranslate_jni.so = JNI 桥 + 静态链进去的 llama.cpp。
# backend 有三个，静态注册（GGML_BACKEND_DL=OFF）：CPU / OpenCL(Adreno GPU) /
# Hexagon(高通 NPU)，具体用哪个由 cliptranslate_jni.cpp 按 Kotlin 传来的 gpuBackend
# 在运行时决定。
# Vulkan 曾经接过，但 Adreno 830 的驱动在 createComputePipeline 上直接失败
# （ErrorUnknown，整个进程被杀），是驱动 bug、没有通用解，所以整体移除了。
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
# 后端开关：默认开 OpenCL；真机上出问题时改这里（不动下面的逻辑），关掉后 .so
# 就退回 CPU-only 版本，Kotlin 侧不需要跟着改。
# ---------------------------------------------------------------------------
option(CLIPTRANSLATE_GPU_OPENCL "编译 ggml 的 OpenCL backend（Adreno GPU）" ON)
option(CLIPTRANSLATE_NPU_HEXAGON "编译 ggml 的 Hexagon backend（高通 NPU/HTP）" ON)

# 仓库根目录（app/src/main/cpp 往上四级），后面引用 third_party/ 下的东西都从这里拼。
get_filename_component(CLIPTRANSLATE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)

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
# mtmd 是 llama.cpp 的多模态库（mtmd.h / mtmd-helper.h），Index-Translate 2B 的
# 图片翻译走它。b12375 里这个 option 的含义是「tools 树不建时单独建 libmtmd」：
# 根 CMakeLists 的条件是 LLAMA_BUILD_MTMD AND NOT (LLAMA_BUILD_COMMON AND
# LLAMA_BUILD_TOOLS)，我们正好两项都 OFF，所以只会 add_subdirectory 一次，不会
# 和 tools/ 里的那一份重复。
# 它不会把 common 拖进来（common 才带 httplib/OpenSSL）：mtmd 的依赖只有
#   PUBLIC  ggml llama Threads::Threads
#   PRIVATE vendor::{hash,miniaudio,stb,sheredom}
# 后三个是 header-only 的 INTERFACE target，hash 是 xxhash/sha1/sha256 的小静态库，
# vendor/CMakeLists.txt 里无条件 add_subdirectory，不受 LLAMA_BUILD_COMMON 影响。
# MTMD_VIDEO 需要的 sheredom/subprocess 在 LLAMA_SUBPROCESS=OFF 时会被 mtmd 自己的
# CMakeLists 强制关掉（见下面的 LLAMA_SUBPROCESS），所以不会给 Android 链进
# ffmpeg/进程相关的代码。
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)
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
#      空函数体，configure 期直接用一个 execute_process 编成 SONAME=libOpenCL.so 的
#      共享库（不是 CMake target，用不到 find_package 的 target 形态，只要路径）。
#      于是我们的 .so 记录 DT_NEEDED=libOpenCL.so，运行时由系统的 vendor 库满足。
#      这个桩绝不能进 APK：AGP 会把 CMake 输出目录里的 *.so 自动打包，所以桩的文件名
#      起成 libOpenCL.linkstub（运行时名字只由 soname 决定），build.gradle.kts 里另有
#      packaging.jniLibs.excludes 兜底，CI 守卫还会检查 APK 里没有 libOpenCL*。
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

    # 桩的文件名故意不以 .so 结尾：AGP 会把 CMake 输出目录里的 *.so 自动收进 APK
    # （上一轮 CI 就是这么把 libOpenCL.so 桩打进包、被守卫抓到的），而运行时用的名字
    # 只由 -Wl,-soname 决定，跟磁盘上的文件名无关 —— DT_NEEDED 照样是 libOpenCL.so，
    # 运行时由设备 /vendor/lib64/libOpenCL.so 满足。build.gradle.kts 的
    # packaging.jniLibs.excludes 还有一层兜底。
    set(_cl_stub_so "${_cl_stub_dir}/libOpenCL.linkstub")
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
# Hexagon backend（高通 NPU / HTP）的前置条件
#
# ggml-hexagon 分两半：
#   * 宿主侧（ggml-hexagon.cpp / htp-drv.cpp …）编进我们的 .so，靠 fastrpc 跟 DSP
#     通信，只需要 SDK 的头文件；
#   * DSP 侧 skel（libggml-htp-vXX.so）必须用 Hexagon SDK 的 DSP 工具链交叉编译，
#     CI（ubuntu-latest）上没有、也不能装。
#
# 所以：
#   1) 头文件用仓库里裁剪过的 third_party/hexagon-sdk-lite（只放 SDK 6.7.0.1 的
#      incs/ 头文件，419 KB），HEXAGON_SDK_ROOT 直接指过去 —— CI 不装 SDK、
#      workflow 也不用加环境变量；
#   2) 用仓库内补丁把 llama.cpp 里“编 5 个 skel + install”整段摘掉，同时把
#      htp_iface.idl 的 qaic 生成换成仓库里预生成好的 htp-idl/（SDK 6.7 的
#      hexagon_fun.cmake 写死了 qaic//bin/qaic，Linux 下这个路径不存在）；
#   3) skel 是已经编好的产物：third_party/htp-skel/libggml-htp-vXX.so。运行时由
#      Kotlin 解压到 filesDir 再通过 GGML_HEXAGON_LIBHTP_DIR 告诉 native 侧。
#      skel 必须是有真实路径的独立文件（fastrpc 拿文件路径去加载，AGP 打包的
#      jniLibs 是从 APK 里 mmap 的，没有可读路径），所以不能放 jniLibs。
# ---------------------------------------------------------------------------
set(_llama_patch_args "")
if(CLIPTRANSLATE_NPU_HEXAGON)
    set(HEXAGON_SDK_ROOT "${CLIPTRANSLATE_ROOT}/third_party/hexagon-sdk-lite"
            CACHE PATH "裁剪过的 Hexagon SDK 头文件（仓库内）" FORCE)
    if(NOT EXISTS "${HEXAGON_SDK_ROOT}/incs/remote.h" OR
       NOT EXISTS "${HEXAGON_SDK_ROOT}/incs/stddef/AEEStdDef.h")
        message(FATAL_ERROR "third_party/hexagon-sdk-lite 不完整：缺 ${HEXAGON_SDK_ROOT}/incs/remote.h 或 incs/stddef/AEEStdDef.h")
    endif()
    set(_llama_patch "${CLIPTRANSLATE_ROOT}/third_party/patches/llama-hexagon-no-skel-build.patch")
    set(_llama_patch_script "${CLIPTRANSLATE_ROOT}/third_party/patches/apply-llama-patch.cmake")
    if(NOT EXISTS "${_llama_patch}" OR NOT EXISTS "${_llama_patch_script}")
        message(FATAL_ERROR "缺补丁或打补丁脚本：${_llama_patch} / ${_llama_patch_script}")
    endif()
    # 走小脚本而不是直接 `git apply`：脚本里先反查一次，补丁已打过就跳过（复用旧
    # build 目录时不至于整份失败），打失败时把 git 的 stderr 带出来。
    set(_llama_patch_args
            PATCH_COMMAND ${CMAKE_COMMAND}
            "-DPATCH_FILE=${_llama_patch}"
            -P "${_llama_patch_script}")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    message(STATUS "Hexagon backend: ON（skel 取运行时解压的那些，CI 不编 DSP）")
else()
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
        llama_cpp
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_CPP_TAG}.tar.gz
        ${_fetch_extra}
        ${_llama_patch_args}
)
FetchContent_MakeAvailable(llama_cpp)

# FetchContent 会跟着上游一起演进：上游要是把 LLAMA_BUILD_MTMD 改名/删掉，mtmd 目标
# 就静默不存在，直到链接期才报一堆未定义符号。这里提前红掉，错误信息直接指到原因。
if(NOT TARGET mtmd)
    message(FATAL_ERROR
            "llama.cpp ${LLAMA_CPP_TAG} 里没有 mtmd 目标：LLAMA_BUILD_MTMD 的语义变了，"
            "需要按新的头文件/CMake 结构重核 app/src/main/cpp/CMakeLists.txt")
endif()

# ---------------------------------------------------------------------------
# JNI 库
# ---------------------------------------------------------------------------

add_library(cliptranslate_jni SHARED cliptranslate_jni.cpp)

target_compile_options(cliptranslate_jni PRIVATE -O3 -fPIC)

target_include_directories(cliptranslate_jni PRIVATE
        ${llama_cpp_SOURCE_DIR}/include
        ${llama_cpp_SOURCE_DIR}/ggml/include
        # mtmd.h / mtmd-helper.h；mtmd 自己也把这一层作为 PUBLIC 头目录，这里写出来是
        # 为了让 cliptranslate_jni.cpp 的 #include 不依赖链接顺序。
        ${llama_cpp_SOURCE_DIR}/tools/mtmd)

# llama 是静态库，PUBLIC 依赖会带上 ggml / ggml-base / ggml-cpu / ggml-opencl /
# Threads（ggml_add_backend_library 对静态 backend 用的是 PUBLIC 链接，
# 所以这里不用单独再链一遍 backend 库），ggml-base 自己会在 Android 上补 dl 和 m。
# log 是 NDK 的 liblog（__android_log_*）。
# mtmd 同样是静态库：它的 PUBLIC（ggml llama Threads）和 PRIVATE（vendor::hash 等）
# 依赖 CMake 都会带进最终链接，不需要在这里重复列。
target_link_libraries(cliptranslate_jni PRIVATE llama mtmd android log)

# Android 15 起部分设备是 16KB 内存页，未对齐 16KB 的 .so 直接加载失败
# （NDK r28 才默认打开这个开关，r27 要显式给）。加上它只是让 ELF 段对齐变大，
# 4KB 页设备照样能加载。
target_link_options(cliptranslate_jni PRIVATE "-Wl,-z,max-page-size=16384")
