package com.xzledger.app.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** 图片附件存放于应用私有目录，避免索取不必要的存储权限。 */
object Attachments {
    fun dir(context: Context): File = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    fun importFromUri(context: Context, uri: Uri): String? = try {
        val target = File(dir(context), UUID.randomUUID().toString() + extOf(context, uri))
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        if (target.exists() && target.length() > 0) target.absolutePath else null
    } catch (e: Exception) { null }

    fun newCaptureTarget(context: Context): Pair<Uri, String> {
        val f = File(dir(context), "cam_" + UUID.randomUUID().toString() + ".jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        return uri to f.absolutePath
    }

    private fun extOf(context: Context, uri: Uri): String {
        val t = context.contentResolver.getType(uri) ?: return ".jpg"
        return when {
            t.contains("png") -> ".png"
            t.contains("webp") -> ".webp"
            t.contains("gif") -> ".gif"
            else -> ".jpg"
        }
    }
}
