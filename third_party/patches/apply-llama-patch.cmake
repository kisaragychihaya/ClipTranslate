# 给 FetchContent 的 PATCH_COMMAND 用：把仓库里的 llama.cpp 补丁打到刚解包的源码树上。
#
# 单独一个脚本而不直接 PATCH_COMMAND git apply 的原因：
#   * 幂等：复用旧 build 目录时（补丁文件改过 -> 打补丁那一步的 stamp 失效）FetchContent
#     会再跑一次，直接 git apply 会因为“已经打过了”整份失败。这里按目标文件里的标记判断。
#   * git 的仓库上下文坑（务必保留 GIT_CEILING_DIRECTORIES）：AGP 把 llama.cpp 解到
#     app/.cxx/.../_deps/llama_cpp-src，而这在 checkout 这个 git 仓库**内部**。这种情况下
#     git apply 会把补丁里的路径按仓库根解析，一个文件都匹配不到，于是每个文件都打
#     "Skipped patch '...'" 并且**返回 0** —— 看起来成功，其实什么都没打。把
#     GIT_CEILING_DIRECTORIES 设成源码目录的父目录，git 就不会往上找到外层仓库，行为
#     和在普通目录里一致。打完后再校验一次标记，绝不让“静默跳过”蒙混过去。
#   * git apply 的报错要能看见（失败时把 stderr 一起打进 FATAL_ERROR），不然只能看到
#     “Build step for llama_cpp failed”。
#
# 调用方式：cmake -DPATCH_FILE=<patch> -P apply-llama-patch.cmake
# 工作目录由 ExternalProject 设成解包后的源码根目录（脚本里不 cd）。

if(NOT DEFINED PATCH_FILE OR NOT EXISTS "${PATCH_FILE}")
    message(FATAL_ERROR "apply-llama-patch: PATCH_FILE 无效: '${PATCH_FILE}'")
endif()

get_filename_component(_src "." ABSOLUTE)
set(_target "${_src}/ggml/src/ggml-hexagon/CMakeLists.txt")
if(NOT EXISTS "${_target}")
    message(FATAL_ERROR "apply-llama-patch: 当前目录不像 llama.cpp 源码树（缺 ${_target}），是 '${_src}'?")
endif()

# 补丁里唯一改到的文件带着这个标记，用它判断幂等，不依赖 git 的反向检查。
set(_marker "ClipTranslate patch")

file(READ "${_target}" _before)
if(_before MATCHES "${_marker}")
    message(STATUS "apply-llama-patch: 补丁已经打过了，跳过")
    return()
endif()

get_filename_component(_ceiling "${_src}" DIRECTORY)
execute_process(
        COMMAND ${CMAKE_COMMAND} -E env "GIT_CEILING_DIRECTORIES=${_ceiling}"
                git apply --ignore-whitespace --whitespace=nowarn "${PATCH_FILE}"
        RESULT_VARIABLE _rc
        OUTPUT_VARIABLE _out
        ERROR_VARIABLE _err)
if(NOT _rc EQUAL 0)
    message(FATAL_ERROR "apply-llama-patch: git apply 失败 (rc=${_rc})\n${_out}${_err}")
endif()

file(READ "${_target}" _after)
if(NOT _after MATCHES "${_marker}")
    message(FATAL_ERROR "apply-llama-patch: git apply 返回成功但补丁没有生效（${_target} 里没有标记）。"
                        "多半是 git 把补丁路径解析到了外层仓库，检查 GIT_CEILING_DIRECTORIES。")
endif()

message(STATUS "apply-llama-patch: 已应用 ${PATCH_FILE}")
