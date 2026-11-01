package com.xzledger.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.xzledger.app.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * 安装前的硬校验：SHA-256 必须与发布元数据一致，包名 / versionCode / 签名必须与当前应用兼容。
 * 任何一项对不上都直接拒绝安装，并把原因如实告诉用户。
 */
object ApkVerifier {
    data class Verified(val ok: Boolean, val reason: String?)

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    fun verify(context: Context, file: File, expectedSha256: String, expectedVersionCode: Int): Verified {
        if (!file.exists() || file.length() <= 0L) return Verified(false, "安装包为空或已被删除，请重新下载")
        if (expectedSha256.isBlank()) {
            return Verified(false, "更新元数据缺少 SHA-256，出于安全考虑不予安装")
        }
        val actual = try {
            sha256(file)
        } catch (e: Exception) {
            return Verified(false, "读取安装包失败：" + (e.message ?: "未知错误"))
        }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            return Verified(false, "安装包 SHA-256 校验失败，文件可能不完整或被篡改，请重新下载")
        }

        val pm = context.packageManager
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = try {
            pm.getPackageArchiveInfo(file.absolutePath, flag)
        } catch (e: Exception) {
            null
        } ?: return Verified(false, "无法读取安装包信息，文件可能已损坏")

        if (archive.packageName != BuildConfig.APPLICATION_ID) {
            return Verified(false, "安装包包名（" + archive.packageName + "）与当前应用不一致")
        }

        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            archive.longVersionCode.toInt()
        else @Suppress("DEPRECATION") archive.versionCode
        if (archiveCode != expectedVersionCode) {
            return Verified(false, "安装包版本号（" + archiveCode + "）与更新信息（" + expectedVersionCode + "）不一致")
        }
        if (archiveCode <= BuildConfig.VERSION_CODE) {
            return Verified(false, "安装包版本不高于当前版本，已拒绝降级安装")
        }

        val ours = try {
            signerDigests(pm.getPackageInfo(context.packageName, flag))
        } catch (e: Exception) {
            emptySet<String>()
        }
        val theirs = signerDigests(archive)
        if (ours.isEmpty() || theirs.isEmpty()) {
            return Verified(false, "无法读取签名信息，出于安全考虑不予安装")
        }
        if (!ours.containsAll(theirs)) {
            return Verified(false, "安装包签名与当前应用不一致，可能来自其它渠道，已拒绝安装")
        }
        return Verified(true, null)
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        if (signatures == null) return emptySet()
        return signatures.mapNotNull { sig ->
            try {
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(sig.toByteArray()).joinToString("") { String.format("%02x", it) }
            } catch (e: Exception) {
                null
            }
        }.toSet()
    }
}
