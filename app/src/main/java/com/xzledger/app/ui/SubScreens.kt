package com.xzledger.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xzledger.app.AppState

/** 压在底部三页之上的子页面路由分发。 */
@Composable
fun SubScreens(route: Route, state: AppState, nav: NavStack) {
    when (route.screen) {
        Screen.CALENDAR -> CalendarScreen(state, nav)
        Screen.SEARCH -> SearchScreen(state, nav)
        Screen.TXN_DETAIL -> TxnDetailScreen(state, nav, route.arg ?: "")
        Screen.TXN_EDIT -> TxnEditScreen(state, nav, route.arg ?: "")
        Screen.SPLIT -> SplitScreen(state, nav, route.arg ?: "")
        Screen.REFUND_PICK -> RefundPickScreen(state, nav)
        Screen.CATEGORIES -> CategoriesScreen(state, nav)
        Screen.ACCOUNTS -> AccountsScreen(state, nav)
        Screen.LEDGERS -> LedgersScreen(state, nav)
        Screen.TRASH -> TrashScreen(state, nav)
        Screen.RULES -> RulesScreen(state, nav)
        Screen.LEARNING -> LearningScreen(state, nav)
        Screen.STATS -> StatsScreen(state, nav)
        Screen.ANNUAL -> AnnualScreen(state, nav)
        Screen.BUDGET -> BudgetScreen(state, nav)
        Screen.RECURRING -> RecurringScreen(state, nav)
        Screen.AI_CONFIG -> AiConfigScreen(state, nav)
        Screen.AI_PERM -> AiPermScreen(state, nav)
        Screen.AI_CHAT -> AiChatScreen(state, nav)
        Screen.IMPORT_PICKER -> ImportPickerScreen(state, nav)
        Screen.IMPORT_PREVIEW -> ImportPreviewScreen(state, nav)
        Screen.BACKUP -> BackupScreen(state, nav)
        Screen.SETTINGS -> SettingsScreen(state, nav)
        Screen.SYNC -> SyncScreen(state, nav)
        Screen.HELP -> HelpScreen(state, nav)
        Screen.ABOUT -> AboutScreen(state, nav)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("本模块尚未接入：" + route.screen.name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
