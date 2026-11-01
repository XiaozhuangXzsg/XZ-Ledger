package com.xzledger.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xzledger.app.data.Account
import com.xzledger.app.data.Category
import com.xzledger.app.data.Db
import com.xzledger.app.data.Ids
import com.xzledger.app.data.Ledger
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.Store
import com.xzledger.app.util.Dates

class XzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Db(this)
        store = Store(db)
        // 旧版本的默认主色是靛蓝，改为「桑多涅」金棕后，老用户升级时一次性迁移，
        // 否则界面上仍然是旧配色，看起来像没改。用户自己选过的其它配色不受影响。
        if (!store.bool("appearance.palette_migrated", false)) {
            if (store.str(SettingsKeys.APPEARANCE_SEED, "sandrone") == "indigo") {
                store.saveAppearance(store.appearance().copy(seed = "sandrone"))
            }
            store.setBool("appearance.palette_migrated", true)
        }
        state = AppState(db, store)
    }

    companion object {
        lateinit var instance: XzApp
        lateinit var db: Db
        lateinit var store: Store
        lateinit var state: AppState
    }
}

/**
 * 全局界面状态。所有页面共用「当前账本」范围；
 * dataVersion 在每次数据库写入后自增，页面据此重新查询，保证明细／日历／统计随时一致。
 */
class AppState(private val db: Db, val store: Store) {
    var appearance: com.xzledger.app.ui.theme.Appearance by mutableStateOf(store.appearance())

    fun updateAppearance(a: com.xzledger.app.ui.theme.Appearance) {
        appearance = a
        store.saveAppearance(a)
        // 液态玻璃开关同时决定桌面小组件的高光层，保存后立刻刷新一次（没有小组件时静默返回）。
        try {
            com.xzledger.app.widget.WidgetRefresh.updateAll(XzApp.instance)
        } catch (t: Throwable) {
        }
    }

    var ledgerId: String by mutableStateOf(store.str(SettingsKeys.LEDGER_ID, Ids.DEFAULT_LEDGER))
    var month: String by mutableStateOf(Dates.thisMonth())
    var dataVersion: Int by mutableStateOf(0)
    var ledgerVersion: Int by mutableStateOf(0)

    /** 跨页面跳转：由日历等页面请求切换到某个底部标签。 */
    var pendingTab: Int? by mutableStateOf(null)

    /** 从日历「在该日记账」进入记账页时预填的日期。 */
    var presetDate: String? by mutableStateOf(null)

    /** 退款记账：在「选择退款账单」页选中原支出后，回到记账页时用它预填物品与商户。 */
    var refundTarget: com.xzledger.app.data.Txn? by mutableStateOf(null)

    fun touch() {
        dataVersion++
        // 数据变化后立即刷新桌面小组件（没有放置小组件时内部静默返回）。
        try {
            com.xzledger.app.widget.WidgetRefresh.updateAll(XzApp.instance)
        } catch (t: Throwable) {
        }
    }

    fun reloadLedgers() {
        ledgerVersion++
    }

    fun currentLedger(): Ledger =
        db.ledger(ledgerId) ?: db.ledger(Ids.DEFAULT_LEDGER) ?: db.ledgers().first()

    fun switchLedger(id: String) {
        ledgerId = id
        store.setStr(SettingsKeys.LEDGER_ID, id)
        touch()
    }

    fun categories(): List<Category> = db.categories(ledgerId)
    fun accounts(): List<Account> = db.accounts(ledgerId)

    fun category(id: String?): Category? = if (id.isNullOrEmpty()) null else db.category(id)
    fun account(id: String?): Account? = if (id.isNullOrEmpty()) null else db.account(id)

    fun ensureDefaultLedger() {
        if (db.ledgers().isEmpty()) {
            db.upsertLedger(Ledger(id = Ids.DEFAULT_LEDGER, name = "默认账本"))
        }
        if (db.ledger(ledgerId) == null) {
            switchLedger(db.ledgers().first().id)
        }
    }
}
