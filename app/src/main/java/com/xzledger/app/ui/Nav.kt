package com.xzledger.app.ui

import androidx.compose.runtime.mutableStateListOf

/** 页面路由：底部三个主页由 tab 决定，其余页面压在栈上，系统返回手势逐层退出。 */
enum class Screen {
    CALENDAR, SEARCH, TXN_DETAIL, TXN_EDIT, AI_CONFIG, AI_PERM, AI_CHAT,
    RULES, LEARNING, TRASH, CATEGORIES, ACCOUNTS, LEDGERS, BUDGET, RECURRING,
    BACKUP, IMPORT_PICKER, IMPORT_PREVIEW, STATS, ANNUAL, SETTINGS, ABOUT, SYNC, HELP, SPLIT,
    REFUND_PICK
}

data class Route(val screen: Screen, val arg: String? = null)

class NavStack {
    val stack = mutableStateListOf<Route>()
    val current: Route? get() = stack.lastOrNull()
    fun push(screen: Screen, arg: String? = null) { stack.add(Route(screen, arg)) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
    fun clear() { stack.clear() }
    fun replace(screen: Screen, arg: String? = null) { pop(); push(screen, arg) }
}
