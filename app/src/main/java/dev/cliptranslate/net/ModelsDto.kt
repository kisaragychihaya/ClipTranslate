package dev.cliptranslate.net

/*
 * 网络层的模型条目。
 *
 * 层间契约：data 层的模型类型是 `dev.cliptranslate.data.ModelInfo(id, ownedBy)`，
 * 但网络层刻意不 import 任何 data 层类型 —— net/ 必须能脱离 data/ 单独编译与联调，
 * 所以这里定义结构一致的 `dev.cliptranslate.net.ModelInfo`，字段与 data 层一一对应。
 *
 * UI 层负责把 net.ModelInfo 映射为 data.ModelInfo：
 *     ModelInfo(id = netModel.id, ownedBy = netModel.ownedBy)
 * 若同文件需要同时引用两者，用 import 别名（如 `as NetModelInfo`）。
 */
data class ModelInfo(
    val id: String,
    val ownedBy: String? = null,
)
