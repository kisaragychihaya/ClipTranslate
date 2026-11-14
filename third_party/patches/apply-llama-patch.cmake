# 给 FetchContent 的 PATCH_COMMAND 用：把仓库里的 llama.cpp 补丁打到刚解包的源码树上。
#
# 单独一个脚本而不直接 PATCH_COMMAND git apply 的原因：
#   * 复用旧 build 目录时（补丁文件改过 -> 打补丁那一步的 stamp 失效）FetchContent 会
#     再跑一次，直接 git apply 会因为“已经打过了”整份失败；这里先反着 --check 一下，
#     已经打过就跳过；
#   * git apply 的报错要能看见（失败时把 stderr 一起打进 FATAL_ERROR），不然只能看到
#     “Build step for llama_cpp failed”。
#
# 调用方式：cmake -DPATCH_FILE=<patch> -P apply-llama-patch.cmake
# 工作目录由 ExternalProject 设成解包后的源码根目录（脚本里不 cd）。

if(NOT DEFINED PATCH_FILE OR NOT EXISTS "${PATCH_FILE}")
    message(FATAL_ERROR "apply-llama-patch: PATCH_FILE 无效: '${PATCH_FILE}'")
endif()

# 已经打过（能反向 clean 掉）就别再打一次。
execute_process(
        COMMAND git apply --reverse --check --whitespace=nowarn "${PATCH_FILE}"
        RESULT_VARIABLE _already_applied
        OUTPUT_QUIET
        ERROR_QUIET)
if(_already_applied EQUAL 0)
    message(STATUS "apply-llama-patch: 补丁已经打过了，跳过")
    return()
endif()

execute_process(
        COMMAND git apply --ignore-whitespace --whitespace=nowarn "${PATCH_FILE}"
        RESULT_VARIABLE _rc
        OUTPUT_VARIABLE _out
        ERROR_VARIABLE _err)
if(NOT _rc EQUAL 0)
    message(FATAL_ERROR "apply-llama-patch: git apply 失败 (rc=${_rc})\n${_out}${_err}")
endif()

message(STATUS "apply-llama-patch: 已应用 ${PATCH_FILE}")
