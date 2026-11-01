package com.xzledger.app.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 读取 GitHub Releases，只认正式版本（draft=false、prerelease=false）。
 *
 * 版本高低一律用 versionCode 比较：versionName 只用于展示。
 * versionCode 与 SHA-256 来自发布流程生成的 update.json 资产；
 * 缺少元数据时宁可不动，也不凭 versionName 猜一个版本去装。
 */
object UpdateChecker {
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val sha256: String,
        val htmlUrl: String,
    )

    data class Result(val info: UpdateInfo?, val error: String?, val note: String?)

    fun check(): Result = try {
        checkInternal()
    } catch (e: Exception) {
        Result(null, friendly(e), null)
    }

    private fun checkInternal(): Result {
        val body = httpGet(UpdateConfig.RELEASES_API)
        val arr = JSONArray(body)
        if (arr.length() == 0) return Result(null, null, "GitHub 上还没有正式 Release")

        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft", false)) continue
            if (rel.optBoolean("prerelease", false)) continue

            val tag = rel.optString("tag_name", "")
            val html = rel.optString("html_url", UpdateConfig.RELEASES_PAGE)
            val bodyText = rel.optString("body", "")
            val assets = rel.optJSONArray("assets") ?: JSONArray()
            val list = ArrayList<JSONObject>(assets.length())
            for (j in 0 until assets.length()) assets.optJSONObject(j)?.let { list.add(it) }

            val apkAsset = list.firstOrNull { it.optString("name").endsWith(".apk", true) }
                ?: return Result(null, null, "最新 Release（" + tag + "）还没有上传 APK")

            val metaAsset = list.firstOrNull { it.optString("name") == UpdateConfig.METADATA_ASSET }
                ?: return Result(
                    null, null,
                    "最新 Release（" + tag + "）缺少 " + UpdateConfig.METADATA_ASSET +
                        "，无法核对 versionCode 与 SHA-256，已跳过本次更新"
                )

            val metaUrl = metaAsset.optString("browser_download_url")
            val meta = JSONObject(httpGet(metaUrl))
            val code = meta.optInt("versionCode", 0)
            if (code <= 0) {
                return Result(null, null, "最新 Release 的更新元数据缺少 versionCode，已跳过本次更新")
            }

            val apkName = meta.optString("apk", "")
            val chosen = list.firstOrNull { apkName.isNotEmpty() && it.optString("name") == apkName } ?: apkAsset
            val notes = meta.optString("notes", "").ifBlank { bodyText }

            return Result(
                UpdateInfo(
                    versionCode = code,
                    versionName = meta.optString("versionName", tag.removePrefix("v")),
                    notes = notes,
                    apkUrl = chosen.optString("browser_download_url"),
                    apkSize = chosen.optLong("size", 0L),
                    sha256 = meta.optString("sha256", ""),
                    htmlUrl = html,
                ),
                null,
                null
            )
        }
        return Result(null, null, "GitHub 上还没有正式 Release")
    }

    private fun httpGet(url: String): String {
        if (url.isEmpty()) throw IOException("缺少下载地址")
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("接口跳转缺少地址")
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    redirects++
                    if (redirects > 6) throw IOException("接口跳转次数过多")
                    continue
                }
                if (code != 200) {
                    val remaining = conn.getHeaderField("X-RateLimit-Remaining")
                    val msg = when {
                        code == 403 && remaining == "0" -> "GitHub 接口访问次数已达上限（速率限制），请稍后再试"
                        code == 403 -> "GitHub 拒绝访问（HTTP 403），请稍后再试"
                        code == 404 -> "找不到仓库或 Release（HTTP 404）"
                        else -> "GitHub 返回 HTTP " + code
                    }
                    throw IOException(msg)
                }
                return conn.inputStream.use { readAll(it) }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun friendly(e: Exception): String {
        val raw = e.message ?: e.javaClass.simpleName
        val lower = raw.lowercase()
        return when {
            raw.startsWith("GitHub") || raw.startsWith("找不到") || raw.startsWith("接口") -> raw
            lower.contains("timeout") || lower.contains("timed out") -> "连接 GitHub 超时，请检查网络后重试"
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                "无法解析 GitHub 域名，请检查网络或代理设置"
            lower.contains("connect") -> "无法连接 GitHub，请检查网络后重试"
            else -> "检查更新失败：" + raw
        }
    }
}
