package com.xzledger.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CardMembership
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.LocalMall
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.LocalTaxi
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** 分类／账户图标：简洁线条风格（Material Outlined）+ 富色彩，按名称或 emoji 匹配。 */
object CatIcons {

    data class Look(val icon: ImageVector, val color: Color)

    private val ORANGE = Color(0xFFFF9B6B)
    private val AMBER = Color(0xFFFFC46B)
    private val BLUE = Color(0xFF74B9FF)
    private val SKY = Color(0xFF6FD3F2)
    private val GREEN = Color(0xFF7ED49B)
    private val TEAL = Color(0xFF6FD0C4)
    private val PURPLE = Color(0xFFB79BFF)
    private val PINK = Color(0xFFFF9BC2)
    private val RED = Color(0xFFFF8A8A)
    private val BROWN = Color(0xFFD3B29A)
    private val GOLD = Color(0xFFE8C46B)
    private val GRAY = Color(0xFFA8B0BC)
    private val INDIGO = Color(0xFF9AA8FF)

    /** 名称优先（用户新建分类常沿用内置名），其次 emoji，最后给中性默认值。 */
    fun look(icon: String?, name: String? = null): Look {
        val n = name?.trim().orEmpty()
        byName[n]?.let { return it }
        if (n.isNotEmpty()) {
            keyword(n)?.let { return it }
        }
        val e = icon?.trim().orEmpty()
        byEmoji[e]?.let { return it }
        if (e.isNotEmpty()) {
            keyword(e)?.let { return it }
        }
        // 兜底：同一个名字总是得到同一种颜色，避免图标忽明忽暗。
        val fallback = listOf(ORANGE, BLUE, GREEN, PURPLE, PINK, TEAL, AMBER, INDIGO)
        return Look(Icons.Outlined.Label, fallback[Math.floorMod(e.hashCode(), fallback.size)])
    }

    private fun keyword(text: String): Look? {
        val t = text
        fun hit(vararg words: String): Boolean = words.any { t.contains(it) }
        return when {
            hit("餐饮", "饭", "餐", "外卖", "食堂", "早", "午", "晚", "烧烤", "火锅", "小吃") -> Look(Icons.Outlined.Restaurant, ORANGE)
            hit("咖啡", "奶茶", "茶", "饮") -> Look(Icons.Outlined.LocalCafe, BROWN)
            hit("零食", "超市", "水果", "生鲜", "菜") -> Look(Icons.Outlined.LocalGroceryStore, GREEN)
            hit("购物", "网购", "淘宝", "京东", "商城") -> Look(Icons.Outlined.LocalMall, AMBER)
            hit("交通", "公交", "地铁", "打车", "出租", "出行") -> Look(Icons.Outlined.DirectionsBus, BLUE)
            hit("火车", "高铁") -> Look(Icons.Outlined.Train, BLUE)
            hit("飞机", "机票") -> Look(Icons.Outlined.Flight, SKY)
            hit("单车", "骑行") -> Look(Icons.Outlined.DirectionsBike, TEAL)
            hit("油", "停车", "开车") -> Look(Icons.Outlined.DirectionsCar, BLUE)
            hit("电子", "数码", "电脑", "手机") -> Look(Icons.Outlined.Devices, TEAL)
            hit("家具", "沙发") -> Look(Icons.Outlined.Chair, BROWN)
            hit("服饰", "衣", "鞋", "帽", "包") -> Look(Icons.Outlined.Checkroom, PINK)
            hit("学习", "书", "文具", "培训", "考试") -> Look(Icons.Outlined.MenuBook, INDIGO)
            hit("医", "药", "体检", "挂号") -> Look(Icons.Outlined.MedicalServices, RED)
            hit("房租", "房", "住宿", "酒店") -> Look(Icons.Outlined.Home, BROWN)
            hit("话费", "流量", "宽带", "网费") -> Look(Icons.Outlined.Smartphone, SKY)
            hit("会员", "订阅", "VIP") -> Look(Icons.Outlined.CardMembership, GOLD)
            hit("游戏", "充值", "点券") -> Look(Icons.Outlined.SportsEsports, PURPLE)
            hit("AI", "智能") -> Look(Icons.Outlined.SmartToy, TEAL)
            hit("娱乐", "电影", "演出", "KTV") -> Look(Icons.Outlined.Movie, PURPLE)
            hit("音乐", "演唱会") -> Look(Icons.Outlined.MusicNote, PINK)
            hit("健身", "运动", "球") -> Look(Icons.Outlined.FitnessCenter, GREEN)
            hit("美容", "理发", "SPA", "化妆") -> Look(Icons.Outlined.Spa, PINK)
            hit("宠物", "猫", "狗", "粮") -> Look(Icons.Outlined.Pets, AMBER)
            hit("水电", "电费", "水费", "燃气") -> Look(Icons.Outlined.Bolt, GOLD)
            hit("日用品", "生活", "清洁", "纸") -> Look(Icons.Outlined.CleaningServices, GREEN)
            hit("理发", "剪发") -> Look(Icons.Outlined.ContentCut, PINK)
            hit("生活费", "零花") -> Look(Icons.Outlined.Savings, GREEN)
            hit("奖", "助学金", "补贴") -> Look(Icons.Outlined.EmojiEvents, GOLD)
            hit("兼", "实习", "打工", "工资", "薪水") -> Look(Icons.Outlined.WorkOutline, SKY)
            hit("红包", "礼", "转账", "收") -> Look(Icons.Outlined.Redeem, RED)
            hit("理财", "基金", "股票", "利息", "收益") -> Look(Icons.Outlined.TrendingUp, TEAL)
            hit("现金", "钱包", "余额") -> Look(Icons.Outlined.Payments, GREEN)
            hit("支付宝") -> Look(Icons.Outlined.AccountBalanceWallet, BLUE)
            hit("微信") -> Look(Icons.Outlined.ChatBubbleOutline, GREEN)
            hit("储蓄", "银行卡", "借记") -> Look(Icons.Outlined.AccountBalance, GOLD)
            hit("信用", "信用卡", "花呗", "白条") -> Look(Icons.Outlined.CreditCard, PINK)
            hit("其他", "未分类", "无") -> Look(Icons.Outlined.MoreHoriz, GRAY)
            else -> null
        }
    }

    private val byName: Map<String, Look> = mapOf(
        "餐饮" to Look(Icons.Outlined.Restaurant, ORANGE),
        "交通" to Look(Icons.Outlined.DirectionsBus, BLUE),
        "网购" to Look(Icons.Outlined.LocalMall, AMBER),
        "超市零食" to Look(Icons.Outlined.LocalGroceryStore, GREEN),
        "娱乐" to Look(Icons.Outlined.Movie, PURPLE),
        "电子产品" to Look(Icons.Outlined.Devices, TEAL),
        "生活用品" to Look(Icons.Outlined.CleaningServices, GREEN),
        "家具" to Look(Icons.Outlined.Chair, BROWN),
        "服饰" to Look(Icons.Outlined.Checkroom, PINK),
        "学习" to Look(Icons.Outlined.MenuBook, INDIGO),
        "医疗" to Look(Icons.Outlined.MedicalServices, RED),
        "话费" to Look(Icons.Outlined.Smartphone, SKY),
        "会员订阅" to Look(Icons.Outlined.CardMembership, GOLD),
        "游戏充值" to Look(Icons.Outlined.SportsEsports, PURPLE),
        "AI会员充值" to Look(Icons.Outlined.SmartToy, TEAL),
        "房租" to Look(Icons.Outlined.Home, BROWN),
        "其他" to Look(Icons.Outlined.MoreHoriz, GRAY),
        "生活费" to Look(Icons.Outlined.Savings, GREEN),
        "奖学金" to Look(Icons.Outlined.EmojiEvents, GOLD),
        "兼职" to Look(Icons.Outlined.WorkOutline, SKY),
        "红包" to Look(Icons.Outlined.Redeem, RED),
        "其他收入" to Look(Icons.Outlined.AddCircleOutline, GREEN),
        "现金" to Look(Icons.Outlined.Payments, GREEN),
        "支付宝" to Look(Icons.Outlined.AccountBalanceWallet, BLUE),
        "微信" to Look(Icons.Outlined.ChatBubbleOutline, GREEN),
        "储蓄卡" to Look(Icons.Outlined.AccountBalance, GOLD),
        "信用卡" to Look(Icons.Outlined.CreditCard, PINK),
        "未分类" to Look(Icons.Outlined.HelpOutline, GRAY)
    )

    private val byEmoji: Map<String, Look> = mapOf(
        "🍚" to Look(Icons.Outlined.Restaurant, ORANGE),
        "🍔" to Look(Icons.Outlined.Fastfood, ORANGE),
        "☕" to Look(Icons.Outlined.LocalCafe, BROWN),
        "🚌" to Look(Icons.Outlined.DirectionsBus, BLUE),
        "🚇" to Look(Icons.Outlined.DirectionsSubway, BLUE),
        "🚕" to Look(Icons.Outlined.LocalTaxi, BLUE),
        "🚲" to Look(Icons.Outlined.DirectionsBike, TEAL),
        "✈️" to Look(Icons.Outlined.Flight, SKY),
        "📦" to Look(Icons.Outlined.LocalMall, AMBER),
        "🛒" to Look(Icons.Outlined.ShoppingCart, GREEN),
        "🎮" to Look(Icons.Outlined.SportsEsports, PURPLE),
        "🕹" to Look(Icons.Outlined.SportsEsports, PURPLE),
        "🎬" to Look(Icons.Outlined.Movie, PURPLE),
        "🎵" to Look(Icons.Outlined.MusicNote, PINK),
        "🎤" to Look(Icons.Outlined.TheaterComedy, PURPLE),
        "💻" to Look(Icons.Outlined.Devices, TEAL),
        "🧻" to Look(Icons.Outlined.CleaningServices, GREEN),
        "🛋" to Look(Icons.Outlined.Chair, BROWN),
        "👕" to Look(Icons.Outlined.Checkroom, PINK),
        "📚" to Look(Icons.Outlined.MenuBook, INDIGO),
        "📝" to Look(Icons.Outlined.School, INDIGO),
        "💊" to Look(Icons.Outlined.MedicalServices, RED),
        "🏥" to Look(Icons.Outlined.LocalPharmacy, RED),
        "📱" to Look(Icons.Outlined.Smartphone, SKY),
        "🎫" to Look(Icons.Outlined.CardMembership, GOLD),
        "🔖" to Look(Icons.Outlined.Subscriptions, GOLD),
        "🤖" to Look(Icons.Outlined.SmartToy, TEAL),
        "🏠" to Look(Icons.Outlined.Home, BROWN),
        "🏨" to Look(Icons.Outlined.Hotel, BROWN),
        "📌" to Look(Icons.Outlined.MoreHoriz, GRAY),
        "🏷" to Look(Icons.Outlined.Label, GRAY),
        "💰" to Look(Icons.Outlined.Savings, GREEN),
        "🏆" to Look(Icons.Outlined.EmojiEvents, GOLD),
        "💼" to Look(Icons.Outlined.WorkOutline, SKY),
        "🧧" to Look(Icons.Outlined.Redeem, RED),
        "🎁" to Look(Icons.Outlined.CardGiftcard, PINK),
        "➕" to Look(Icons.Outlined.AddCircleOutline, GREEN),
        "🔁" to Look(Icons.Outlined.SwapHoriz, BLUE),
        "💵" to Look(Icons.Outlined.Payments, GREEN),
        "💬" to Look(Icons.Outlined.ChatBubbleOutline, GREEN),
        "🏦" to Look(Icons.Outlined.AccountBalance, GOLD),
        "💳" to Look(Icons.Outlined.CreditCard, PINK),
        "📈" to Look(Icons.Outlined.TrendingUp, TEAL),
        "❤️" to Look(Icons.Outlined.FavoriteBorder, PINK),
        "🏃" to Look(Icons.Outlined.FitnessCenter, GREEN),
        "🐱" to Look(Icons.Outlined.Pets, AMBER),
        "💧" to Look(Icons.Outlined.WaterDrop, SKY),
        "📶" to Look(Icons.Outlined.Wifi, SKY),
        "🎂" to Look(Icons.Outlined.Cake, PINK),
        "✂️" to Look(Icons.Outlined.ContentCut, PINK),
        "🔧" to Look(Icons.Outlined.Build, GRAY),
        "🎨" to Look(Icons.Outlined.Brush, PURPLE),
        "🌐" to Look(Icons.Outlined.Cloud, SKY),
        "🎯" to Look(Icons.Outlined.AdsClick, RED),
        "🤖💰" to Look(Icons.Outlined.SmartToy, TEAL)
    )

    /** 图标选择器里展示的候选（emoji 作为存储键，界面统一画线条图标）。 */
    val pickable: List<String> = byEmoji.keys.toList()
}
