package com.xzledger.app.autorecord

import android.app.Notification
import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache

/**
 * 通知监听服务。
 *
 * 只在用户显式开启并授予「通知使用权」后才工作；回调里只做轻量的字段提取，
 * 解析与写库都交给 [AutoRecordEngine] 的后台单线程执行器。
 *
 * 明确不做的事：不申请无障碍、不 root、不读短信数据库、不申请 READ_SMS，
 * 不点击、不回复、不代替用户进行任何银行操作。
 */
class NotifyListenerService : NotificationListenerService() {

    companion object {
        /** 系统是否已把服务绑定成功（用于设置页展示实时状态）。 */
        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        var lastEventAt: Long = 0L
            private set
    }

    /** 同一条通知被反复更新时，避免同一秒内重复解析。key = 通知 key，value = 上次内容哈希。 */
    private val recent = LruCache<String, String>(64)
    private val recentAt = LruCache<String, Long>(64)

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        // 部分系统（ColorOS 等）会断开监听；尝试重新绑定，避免用户以为一直在记。
        try {
            requestRebind(ComponentName(this, NotifyListenerService::class.java))
        } catch (ignored: Throwable) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == packageName + ".debug") return
        // 只处理用户勾选过的应用，其他通知连扩展信息都不读
        val store = try {
            com.xzledger.app.XzApp.store
        } catch (t: Throwable) {
            return
        }
        if (!NotifyPrefs.enabled(store)) return
        if (!NotifyPrefs.packages(store).contains(pkg)) return

        // 分组摘要不是交易本体，读了只会重复
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // 常驻/进行中的通知（如正在播放、正在下载）通常不是交易结果
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val title = charOf(notification, Notification.EXTRA_TITLE)
        val big = charOf(notification, Notification.EXTRA_BIG_TEXT)
        val body = charOf(notification, Notification.EXTRA_TEXT)
        val sub = charOf(notification, Notification.EXTRA_SUB_TEXT)
        val lines = notification.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" ") { it.toString() } ?: ""
        val text = listOf(big, body, lines, sub)
            .firstOrNull { it.isNotBlank() } ?: ""

        // 同一条通知内容在 2 秒内重复投递：忽略（系统更新通知、我们自己的 requestRebind 抖动）
        val now = SystemClock.elapsedRealtime()
        val hash = (title + "\u0001" + text).hashCode().toString()
        val key = sbn.key ?: hash
        val prevHash = recent.get(key)
        val prevAt = recentAt.get(key) ?: 0L
        if (prevHash == hash && now - prevAt < 2_000L) return
        recent.put(key, hash)
        recentAt.put(key, now)
        lastEventAt = System.currentTimeMillis()

        val appLabel = try {
            NotifyPrefs.labelOf(this, pkg)
        } catch (t: Throwable) {
            pkg
        }
        AutoRecordEngine.submit(
            this,
            NoticeInput(
                pkg = pkg,
                appLabel = appLabel,
                title = title,
                text = text,
                postedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        )
    }

    private fun charOf(n: Notification, key: String): String {
        val v = n.extras?.getCharSequence(key) ?: return ""
        return v.toString().trim()
    }
}
