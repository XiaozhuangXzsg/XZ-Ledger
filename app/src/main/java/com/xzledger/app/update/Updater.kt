package com.xzledger.app.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xzledger.app.BuildConfig
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新状态机：检查 → 下载 → 校验 → 交给系统安装器。
 * 只提供安装包，不改动任何本地数据；安装失败或取消时旧版本照常可用。
 */
object Updater {
    enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

    var visible by mutableStateOf(false)
        private set
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var info by mutableStateOf<UpdateChecker.UpdateInfo?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var downloading by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** 手动检查：总是给出结果；自动检查：只在发现新版本时弹窗，失败保持安静。 */
    fun check(context: Context, store: Store, manual: Boolean) {
        if (phase == Phase.CHECKING || phase == Phase.DOWNLOADING) return
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()

        if (!manual) {
            val last = store.str(SettingsKeys.UPDATE_LAST_CHECK).toLongOrNull() ?: 0L
            if (now - last < UpdateConfig.CHECK_INTERVAL_MS) return
            val snooze = store.str(SettingsKeys.UPDATE_SNOOZE_UNTIL).toLongOrNull() ?: 0L
            if (now < snooze) return
        }
        store.setStr(SettingsKeys.UPDATE_LAST_CHECK, now.toString())

        message = null
        info = null
        progress = 0f
        phase = Phase.CHECKING
        visible = true

        checkJob?.cancel()
        checkJob = scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check() }
            if (result.error != null) {
                phase = Phase.ERROR
                message = result.error
            } else {
                val latest = result.info
                if (latest == null) {
                    phase = Phase.UP_TO_DATE
                    message = result.note ?: "当前已是最新版本"
                } else {
                    val skipped = store.str(SettingsKeys.UPDATE_SKIPPED_CODE).toIntOrNull()
                    when {
                        latest.versionCode <= BuildConfig.VERSION_CODE -> {
                            phase = Phase.UP_TO_DATE
                            message = "当前已是最新版本"
                        }
                        skipped != null && skipped == latest.versionCode -> {
                            phase = Phase.UP_TO_DATE
                            message = "已跳过 " + latest.versionName + "，可在 GitHub 手动查看"
                        }
                        else -> {
                            info = latest
                            phase = Phase.AVAILABLE
                        }
                    }
                }
            }
            if (!manual && (phase == Phase.UP_TO_DATE || phase == Phase.ERROR)) {
                visible = false
                phase = Phase.IDLE
            }
            if (!manual && phase == Phase.AVAILABLE) {
                visible = true
            }
        }
    }

    fun download(context: Context) {
        val target = info ?: return
        if (downloading) return
        downloading = true
        phase = Phase.DOWNLOADING
        progress = 0f
        message = null
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val ctx = context.applicationContext
            val part = File(UpdateInstaller.apkFile(ctx, target.versionCode).absolutePath + ".part")
            var lastError: String? = null
            for (attempt in 1..3) {
                try {
                    part.delete()
                    withContext(Dispatchers.IO) {
                        downloadTo(target.apkUrl, part) { p -> progress = p }
                    }
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: "下载失败"
                    part.delete()
                    if (attempt < 3) {
                        message = "下载中断，正在第 " + (attempt + 1) + " 次重试…"
                    }
                }
            }
            if (lastError != null) {
                downloading = false
                phase = Phase.ERROR
                message = "下载失败：" + lastError + "（可点重试，或稍后再试）"
                return@launch
            }

            phase = Phase.VERIFYING
            message = "正在校验安装包…"
            val verified = withContext(Dispatchers.IO) {
                ApkVerifier.verify(ctx, part, target.sha256, target.versionCode)
            }
            if (!verified.ok) {
                part.delete()
                downloading = false
                phase = Phase.ERROR
                message = verified.reason ?: "安装包校验未通过"
                return@launch
            }
            val dest = UpdateInstaller.apkFile(ctx, target.versionCode)
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.delete()
                downloading = false
                phase = Phase.ERROR
                message = "无法保存安装包，请检查存储空间后重试"
                return@launch
            }
            downloading = false
            phase = Phase.READY
            progress = 1f
            message = "校验通过，可以安装"
        }
    }

    /** 用户点「立即安装」：没有安装未知应用权限时只负责把用户带到授权页。 */
    fun install(context: Context): Boolean {
        val target = info ?: return false
        val file = UpdateInstaller.apkFile(context.applicationContext, target.versionCode)
        if (!file.exists()) {
            phase = Phase.ERROR
            message = "安装包不存在，请重新下载"
            return false
        }
        if (!UpdateInstaller.canInstall(context)) {
            val opened = UpdateInstaller.openPermission(context)
            message = if (opened) "请允许「安装未知应用」后返回，再点一次安装" else "无法打开授权页，请到系统设置中允许安装未知应用"
            return false
        }
        val ok = UpdateInstaller.install(context, file)
        if (!ok) {
            phase = Phase.ERROR
            message = "无法调起系统安装器"
        }
        return ok
    }

    fun snooze(store: Store) {
        store.setStr(
            SettingsKeys.UPDATE_SNOOZE_UNTIL,
            (System.currentTimeMillis() + UpdateConfig.SNOOZE_INTERVAL_MS).toString()
        )
        close()
    }

    fun skipCurrent(store: Store) {
        info?.let { store.setStr(SettingsKeys.UPDATE_SKIPPED_CODE, it.versionCode.toString()) }
        close()
    }

    fun close() {
        downloadJob?.cancel()
        downloading = false
        visible = false
        phase = Phase.IDLE
        message = null
        progress = 0f
    }

    private fun downloadTo(url: String, part: File, onProgress: (Float) -> Unit) {
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("下载跳转缺少地址")
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    redirects++
                    if (redirects > 6) throw IOException("下载跳转次数过多")
                    continue
                }
                if (code != 200) throw IOException("HTTP " + code)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        var lastPct = -1f
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0L) {
                                val p = (written.toFloat() / total).coerceIn(0f, 1f)
                                if (p - lastPct >= 0.01f || p >= 1f) {
                                    lastPct = p
                                    onProgress(p)
                                }
                            }
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }
}
