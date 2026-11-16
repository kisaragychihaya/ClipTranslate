package dev.cliptranslate.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import dev.cliptranslate.local.DecodedImage
import java.io.File

/** 一次解码的产物：喂给推理的 RGB 像素 + 给界面显示的小缩略图。 */
class DecodedPhoto(val image: DecodedImage, val thumbnail: Bitmap)

/**
 * 图片翻译的输入侧：拍照落盘、相册取图、解码降采样。
 *
 * 两条来源都刻意不申请任何运行时权限：拍照走系统相机（[android.provider.MediaStore.ACTION_IMAGE_CAPTURE]
 * 由 androidx 的 TakePicture 契约发起），相册走系统照片选择器（PickVisualMedia），
 * 应用只拿到用户当场挑中的那一张的读权限。照片先落到 `cacheDir/images/`（系统可回收的缓存目录），
 * 通过 FileProvider 交给相机写入，应用本身不需要 WRITE_EXTERNAL_STORAGE。
 */
object PhotoInput {

    private const val DIR_CACHE_IMAGES = "images"

    /**
     * 送进模型的最长边（像素）。手机原图动辄 4000×3000，视觉投影的 patch 数量随分辨率暴涨，
     * 超过 1024 之后译文质量基本不再提升，只多花时间和内存。
     */
    private const val MAX_EDGE = 1024

    /** 界面缩略图的最长边；只是让人确认「选对了图」，没必要按原尺寸画。 */
    private const val THUMB_EDGE = 240

    /**
     * 拍照的输出目标（文件 + 已授权给相机的 content URI）；建不出文件或 FileProvider 未正确配置时返回 null，
     * 调用方据此给出提示而不是抛异常崩掉。
     */
    fun createPhotoTarget(context: Context): Pair<File, Uri>? = runCatching {
        val dir = File(context.cacheDir, DIR_CACHE_IMAGES)
        dir.mkdirs()
        // 时间戳命名：同一个目录里可能留着上次没拍成的空文件，不能互相覆盖。
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.$AUTHORITY_SUFFIX",
            file,
        )
        file to uri
    }.getOrNull()

    /** 读一张图片并降采样成 RGB；读不到/解码失败返回 null（坏文件、云盘占位文件都会走到这里）。 */
    fun decode(context: Context, uri: Uri): DecodedPhoto? =
        runCatching { decodeInternal(context, uri) }.getOrNull()

    private fun decodeInternal(context: Context, uri: Uri): DecodedPhoto? {
        val resolver = context.contentResolver

        // 第一遍只读尺寸（inJustDecodeBounds）：先把原图整个解码出来才降采样的话，
        // 一张 4000×3000 的照片就要 48MB，中低端机直接 OOM。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 第二遍按 2 的幂降采样：inSampleSize 只有取 2 的幂才是无损跳采样，
        // 这样才能以不到原图 1/16 的内存拿到「最长边接近但不超过 MAX_EDGE」的位图。
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_EDGE || bounds.outHeight / sampleSize > MAX_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // 相册/相机常用 EXIF 记录朝向，BitmapFactory 不认它：不转正的话竖拍的照片会横着进模型。
        val oriented = applyOrientation(decoded, readOrientation(context, uri))
        val scaled = scaleToMaxEdge(oriented, MAX_EDGE)
        return DecodedPhoto(
            image = scaled.toRgbImage(),
            thumbnail = thumbnailOf(scaled),
        )
    }

    /** EXIF 朝向标记；读不到（无 EXIF、非 JPEG）按「不用转」处理。 */
    private fun readOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return source
        }
        // 宽高对调时 createBitmap 会新建一份；不需要的那份交给 GC（这里刻意不 recycle，
        // 免得踩到「createBitmap 原样返回同一实例」的情况把还在用的位图回收掉）。
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** 保证最长边 ≤ [maxEdge]；已经够小就原样返回（不复制、不额外占内存）。 */
    private fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toFloat() / longest
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    /** 缩略图必须是独立的一份：缩放比例已经 ≤1 时 createScaledBitmap 会原样返回源位图。 */
    private fun thumbnailOf(source: Bitmap): Bitmap {
        val scale = THUMB_EDGE.toFloat() / maxOf(source.width, source.height)
        if (scale >= 1f) {
            return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    /**
     * Bitmap（ARGB_8888）→ 紧密排列的 RGB 三字节/像素。
     *
     * native 侧按 RGB 直接读，所以在这里一次性摊平：丢掉 alpha（视觉投影不做透明合成，
     * 透明像素按黑处理即可）比在 JNI 边界上逐像素转换便宜得多。
     */
    private fun Bitmap.toRgbImage(): DecodedImage {
        val bitmapWidth = width
        val bitmapHeight = height
        val pixels = IntArray(bitmapWidth * bitmapHeight)
        getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
        val rgb = ByteArray(bitmapWidth * bitmapHeight * 3)
        var offset = 0
        for (pixel in pixels) {
            rgb[offset] = ((pixel shr 16) and 0xFF).toByte()
            rgb[offset + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgb[offset + 2] = (pixel and 0xFF).toByte()
            offset += 3
        }
        return DecodedImage(rgb = rgb, width = bitmapWidth, height = bitmapHeight)
    }

    /** 与 AndroidManifest 里 FileProvider 的 authorities 保持一致（`${applicationId}.fileprovider`）。 */
    private const val AUTHORITY_SUFFIX = "fileprovider"
}
