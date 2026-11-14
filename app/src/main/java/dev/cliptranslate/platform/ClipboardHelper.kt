package dev.cliptranslate.platform

import android.content.ClipboardManager
import android.content.Context

/** 只读访问系统剪贴板。前台应用读取文本剪贴板不需要任何权限。 */
object ClipboardHelper {

    /** 返回剪贴板第一项的文本；剪贴板为空、无内容或文本全是空白时返回 null。 */
    fun readText(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        return try {
            if (!manager.hasPrimaryClip()) return null
            val clip = manager.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
            if (text.isNullOrEmpty()) null else text
        } catch (e: SecurityException) {
            // 部分机型在应用未获得焦点时读取剪贴板会抛 SecurityException，按无内容处理。
            null
        }
    }
}
