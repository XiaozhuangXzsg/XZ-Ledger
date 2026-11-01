package com.xzledger.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 调用系统安装器安装已校验的 APK；不做静默安装，也不引导用户卸载旧版本。 */
object UpdateInstaller {
    fun canInstall(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        false
    }

    /** 打开「安装未知应用」授权页；由用户自己决定是否允许。 */
    fun openPermission(context: Context): Boolean = try {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    fun install(context: Context, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    fun apkFile(context: Context, versionCode: Int): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "xz-ledger-" + versionCode + ".apk")
    }

    fun clearCache(context: Context) {
        try {
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
