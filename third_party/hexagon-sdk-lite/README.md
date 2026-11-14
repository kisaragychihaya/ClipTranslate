# hexagon-sdk-lite

Hexagon SDK 6.7.0.1（`C:\Qualcomm\Hexagon_SDK\6.7.0.1`）里**宿主侧编译**用得着的那一小部分，
只为了在 CI / 没有装 SDK 的机器上把 `ggml-hexagon` 的 host 库编出来。

内容：`incs/` + `incs/stddef/` 的全部 `.h`（36 个文件，约 410 KB），其余一律没放：

| 没放的东西 | 为什么 |
| --- | --- |
| `build/cmake/*.cmake`（`hexagon_fun.cmake` 等） | 只在编 DSP skel 和跑 `qaic` 时用；补丁已经把这两步都去掉了，而 `hexagon_fun.cmake` 被 include 时会按 host 平台分支（Windows 要 `addons/wos/...`）、还会对空 `PREBUILT_LIB_DIR` 报错，留着只会坏事 |
| `ipc/fastrpc/qaic/*`、`tools/HEXAGON_Tools/*` | DSP 侧工具链，CI 用不上；`htp_iface.idl` 的产物已经预生成进补丁里（`ggml/src/ggml-hexagon/htp-idl/`） |
| `*.md` / `*.idl` / `dynsymbols.lst` | 文档和源 idl，编译不需要 |
| `utils/examples/`、`addons/`、`libs/`、`rtos/`、`ipc/fastrpc/remote/*` | 宿主侧头文件不引用；`addons/wos` 的 `remote_wos_ext.h` 本来也不在这个 SDK 安装里 |

只保留头文件是够的：`ggml-hexagon` 的 host 侧（`ggml-hexagon.cpp`、`htp-drv.cpp` …）
需要的 SDK 头就是 `<AEEStdDef.h>` `<AEEStdErr.h>` `<remote.h>` `<rpcmem.h>` `<domain.h>`
`<dspqueue.h>` `<HAP_farf.h>` `<qtest_stdlib.h>`，全在 `incs/` 里。

升级 SDK 时要做的：把新版本 `incs/` 下的 `*.h`（含 `incs/stddef/`）整份覆盖过来，
然后照 `third_party/patches/apply-llama-patch.cmake` 的说明重新生成一次 idl 产物。

注意：这些头文件是高通 SDK 的一部分，版权归高通（见各文件头部的许可声明）。
