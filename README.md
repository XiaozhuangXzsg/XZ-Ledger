# 猫猫记账 / XZ Ledger

面向大学生的安卓个人记账应用。**本地优先、离线可用、真实数据与真实业务逻辑**，不含静态页面、不伪造成功提示。
优先适配 ColorOS 16（实测机型：一加 PLK110 / Android 16 / SDK 36），兼容其他 Android（minSdk 26）。

当前版本 **1.0.6 rc1**（`versionCode = 7`；改版本时需同步 `app/build.gradle.kts` 的 `versionName`/`versionCode` 与 `ui/SettingsScreens.kt` 的 `APP_VERSION`）。

应用显示名为 **猫猫记账**（`strings.xml` 的 `app_name`、通知、分享文案、默认备份/导出文件名 `catledger-backup.json` / `catledger-txns.csv`），应用内已不再出现旧名；Kotlin 包名与 `applicationId` 仍为 `com.xzledger.app`（改包名会让已安装用户的数据不兼容，故保留），备份文件内的 `format` 常量仍为 `xzledger-backup` 以兼容旧备份，另含一次性配色迁移（旧 `indigo` → `sandrone`）。

---

## 一、技术栈与工程结构

| 项 | 值 |
| --- | --- |
| 语言 / UI | Kotlin 2.2.20 + Jetpack Compose (Material3), single-activity, enableEdgeToEdge |
| 构建 | AGP 8.13.0 + Gradle 8.13，compileSdk 36 / targetSdk 36 / minSdk 26 |
| 持久化 | 原生 SQLiteOpenHelper（未用 Room/KSP，避免注解处理器风险，并可直接写数据库层唯一约束） |
| 包名 | `com.xzledger.app`（debug 追加后缀 → `com.xzledger.app.debug`） |
| 金额 | 全程整数最小单位（分），无浮点误差 |

```
app/src/main/java/com/xzledger/app/
  XzApp.kt              Application、AppState、全局 db/store
  MainActivity.kt       三主页 + 底部液态玻璃胶囊导航 + 应用锁
  data/                  Db.kt Models.kt TxnDao.kt Settings.kt Json.kt Sync.kt
                         AiEngine.kt AiImport.kt ImportFiles.kt MerchantNormalizer.kt
                         Backup 相关、DedupKeys(*)
  notify/                Reminders.kt ReminderReceiver.kt BootReceiver.kt RecurringEngine.kt
  ui/                    DetailScreen EntryScreen MineScreen TxnDetailScreen StatsScreens
                         CalendarScreen SearchScreen ImportScreens AiScreens SettingsScreens
                         BackupScreen RecurringScreen BudgetScreen SubScreens Nav Common Theme
                         CategoryIcons.kt（分类线条图标与配色映射 CatIcons.look）
  widget/               桌面小组件：WidgetData.kt（只读库快照）CatWidgetProvider.kt WidgetRefresh.kt
  util/                  Dates.kt 等
```
（* DedupKeys 位于 data 包内，随 TxnDao 一起使用）

---

## 二、构建说明

环境（本机实测）：
- JDK 21：`C:\Users\zhuangzhongao\.jdks\jbr-21.0.11`
- Android SDK：`D:\SDK`（platforms android-36、build-tools 36.0.0）
- Gradle 8.13：`C:\Users\zhuangzhongao\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat`

`local.properties` 内 `sdk.dir=D:\\SDK`。

**Debug**
```powershell
$env:JAVA_HOME='C:\Users\zhuangzhongao\.jdks\jbr-21.0.11'
$env:ANDROID_HOME='D:\SDK'; $env:ANDROID_SDK_ROOT='D:\SDK'
& 'C:\Users\zhuangzhongao\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' `
  :app:assembleDebug --no-daemon
```
产物：`app/build/outputs/apk/debug/app-debug.apk`

**Release**
```powershell
& <上面的 gradle.bat> :app:assembleRelease --no-daemon
```
产物：`app/build/outputs/apk/release/app-release.apk`（已用自带密钥库签名）

安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## 三、签名与凭据

- 密钥库：`keystore/xzledger-release.jks`（RSA 2048，有效期 10950 天，alias `xzledger`）
- 密码配置：`keystore.properties`（键：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`，**ASCII 编码，必须 ASCII 否则首个键会带 BOM 导致构建失败**）
- `app/build.gradle.kts` 读取该文件；文件缺失时回退为 debug 签名（便于无凭据环境构建）
- **凭据不出现在任何源码、日志或导出文件中**；`keystore.properties` 与 `keystore/` 不应提交到公开仓库
- 已签名 release 证书指纹：SHA-256 `2b9b3fbb5d8beffecf734840044727676ce530a537655a7ae83a7c6bd6318197`，SHA-1 `b9722ec51bd4bab01453ac83402616c080c32326`

---

## 四、配置说明（应用内）

| 配置 | 位置 | 说明 |
| --- | --- | --- |
| AI 服务 | 我的 → AI API 配置 | 服务名称 / 接口地址 / API Key / 模型；**接口地址填到 `/v1` 一级即可**（例如 `https://api.openai.com/v1`）；支持连接测试、编辑、删除；兼容 OpenAI 风格并留适配层 |
| AI 预设 | 我的 → AI API 配置 → 新增 AI 服务 | 8 个预设（DeepSeek / 智谱 GLM / 通义千问 / Kimi / 硅基流动 / OpenAI / OpenRouter / 本地 Ollama），点选服务商后只需填 API Key |
| AI 权限 | 我的 → AI 权限管理 | 「读取账本」与「新增账单」两个独立开关，默认关闭、默认仅当前账本；另有快捷自动入账开关 |
| 云同步 | 我的 → 云同步 | 不绑定任何付费服务，需自填同步服务地址与访问令牌；未配置时本地功能完全可用并明确显示未开启；待同步队列可查看 |
| GitHub / 下载地址 | 我的 → 关于 | 未配置时保留入口、不编造链接；配置后「给项目 Star」只会打开该地址，不会代替用户 Star |
| 应用锁 | 我的 → 应用锁与 ColorOS 设置帮助 | 应用内密码 + 系统生物识别/密码验证；可在设置页设定密码后于启动与回前台时校验 |
| 关于 | 我的 → 关于，或明细左上角应用图标 | 版本、开发者（一只小庄呀 / 兰大数学院 zza）与联系方式（zhuangzhongao@gmail.com、微信 Zza17751159236（仅兰大校内可加）、原神亚服 UID 857353806）；GitHub / 下载 / 反馈地址未配置时保留入口、不编造链接 |
| 日历 | 明细 → 日历图标 | 首次说明用途后再请求 READ_CALENDAR；拒绝后内部日历（由账单日期生成）完全可用 |
| 通知 / 提醒 | 我的 → 记账提醒 / 周期记账与订阅提醒 | 需 POST_NOTIFICATIONS；Android 12+ 精确闹钟不可用时自动降级为 setAndAllowWhileIdle |

---

## 五、功能实现状态

### 已实现并已完成真机验证
- 三主页（明细 / 记账 / 我的）+ 底部悬浮液态玻璃胶囊导航、内容底部留白、手势导航与安全区适配
- 手动记账（支出/收入/转账、性质与分类双字段、内嵌数字键盘、相册/拍照），保存后明细、日历、统计、账户即时一致
- 明细按日分组（日期倒序、同日按时间）、当日收支、本月汇总（结余=月收入−月支出，与账户余额区分）
- 年月滚轮浮窗（年、月两列分别滚动；确认后列表与统计刷新）、日历页（月视图、当日支出、今天/选中/有账单区分、在该日记账预填日期、内部日历不依赖系统日历）
- 搜索（关键词/商户/分类/备注/金额）与筛选浮窗（只看收入/只看支出/分类多选/金额绝对值上下限/时间区间；三组 AND、组内 OR；取消/清空筛选/确定；金额 10 下限实测只留 ≥10 的记录）
- 账单详情 / 编辑 / 删除 → 回收站 → 撤销或恢复；分类或性质被修改后弹出学习选择（仅改本笔 / 记住该商户 / 批量修改）；学习记录页展示修改历史与建议规则
- CSV 导入（微信格式）：预览分组 新增/重复/疑似重复/待确认/失败、逐笔字段与「文件第 N 行」来源、分类依据、批量改分类/性质、交给 AI 识别、确认导入、撤销本次导入、最近导入批次
- 真实微信账单 .xlsx 导入：43 笔全部解析（商户、金额、时间、账户、方向、状态），订单编码以字符串保存；**同一文件重复导入 → 新增 0 / 重复 43**（按「来源+交易单号/订单编码」判重，不覆盖人工修改）
- 导出 JSON 备份（含 format/format_version/includes_api_keys/ledgers/txns 等；API Key 默认不写入）与 **恢复实测**：在全新安装的 release 包上恢复 debug 端备份，账单、分类、账户、规则合并成功且不覆盖本机数据
- AI 聊天记账（无 API 时走本地规则）：待确认账单卡片、分类依据、确认入账/修改/忽略，确认后写库成功才显示「已入账」并给查看/撤销；聊天记录按账本持久化
- 我的页全部管理页渲染与交互入口：统计、预算（按净支出=支出−退款）、周期记账与提醒（说明「仅提醒」不生成账单）、导入、导出与备份恢复、分类管理、账户管理、账本管理、回收站、云同步、AI 配置/权限、个人规则、学习记录、主题字体语言、动画与液态玻璃、应用锁与帮助、年度账单、关于、分享（系统分享面板）、给项目 Star
- 桌面小组件 4x2「今日概览」与 2x2「预算」：RemoteViews 只读 `xz_ledger.db`，显示今日支出/收入与本月预算环形比例条（`widget/BudgetRing.kt` 用 Canvas 画整圈轨道 + 从 12 点顺时针的金色弧 + 圆心百分比，超支时整圈变红并显示实际百分比）；回桌面（`MainActivity.onStop`）时 `WidgetRefresh.updateAll` 统一刷新
- 全新视觉与交互：桑多涅金棕配色（默认 seed `sandrone`，主色 `#A8791F`，深色底 `#16130F`）、极简线条分类图标（Material Outlined + 13 种强调色）、底部全圆角胶囊导航（选中项滑动高亮块 + 页面横向滑入淡入）、年月滚轮中间行高亮、我的页重做（头像 + 三项统计 + 5 个分组卡片，每行左侧圆形图标 + 右箭头）
- 记账页一屏完成（类型/金额/分类/键盘/保存/AI 输入条均无需滚动），AI 聊天输入框随键盘上移（`imePadding`）
- 云同步队列每笔记录只保留最后一次修改（同一记录再次写入前先删旧 pending 项），条目显示中文实体名与操作名
- 应用锁（应用内密码 + 系统生物识别/密码回退）
- 第三轮 8 项整改（真机逐项确认）：底部胶囊导航去掉长方形点击水波纹（`clickable(interactionSource, indication = null)`，只保留选中项的滑动高亮块）；日历页重写（右上角改为「关联日程」`TextButton`，不再被 `IconButton` 的 48dp 固定尺寸裁切；月视图固定 6 行 42 格、`CELL_HEIGHT = 46.dp`，日期下方金额用 `shortAmount`（≥1 万元显示 `Nk`）避免 `1,234.56` 在小格里被裁；上下拖动切换月份的手势只挂在月视图 `Box` 上，不再与下方账单列表抢手势）；明细页以「天」为单位，把当天全部账单用一个半透明液态玻璃圆角框（`GlassPanel(corner = 22.dp)`）包成整体；AI 聊天气泡改为扁圆角（`RoundedCornerShape(18.dp)`、宽度上限 260/300dp），高度最多 3 行，超出后气泡内部上下滚动；记账提醒时间改为上下滚动的 `WheelPicker`（时/分两列，中间行高亮）；子页面打开/关闭从点击位置缩放呈现（`AnimatedContent` + `scaleIn(initialScale = 0.62f, transformOrigin = 点击归一化坐标)`，点击坐标由根 `Box` 的 `pointerInput` 在 `PointerEventPass.Initial` 捕获）；关于页新增开发者与联系方式；明细左上角「庄」改为应用图标，点击进入关于页
- 第四轮 6 项整改（真机逐项确认）：
  - 日历不再「两个月重叠」：月视图 `Box` 加 `.clipToBounds()`，切换动画改为整屏高度滑动（`val spec = tween<IntOffset>(260)`，前进从下滑入 / 后退从上滑入），过渡期间只可能看到一个月滑入，数字不再交错；旧实现位移只有 1/4 屏且未裁剪，所以两个月份的数字会同时出现在格子里。
  - 明细单条左滑删除：轻滑过半露出红色「删除」按钮（点击即移入回收站，并提示「已移入回收站」）；从最右一直滑到最左并保持 1 秒会直接删除。实现为 `SwipeToDeleteRow`（`Animatable` 位移 + `detectHorizontalDragGestures`，`reveal = 92.dp`，最左阈值 `-next >= max - reveal * 0.4f` 时启动 1 秒计时器）。日卡片内容加 `clip(RoundedCornerShape(22.dp))` 防止滑动时溢出卡片；每行用 `key(txn.id)` 绑定滑动状态，删除一笔后其它行不会继承它的滑动位置。
  - 记账页商户历史快捷选择 + 自动分类：新增 `Db.merchantHints(ledgerId, limit)`（把最近 500 条有商户的账单按 `merchant_norm` 聚合，取该商户历史最常用的分类与性质），记账页在「商户/备注」卡片下方给出一行历史商户胶囊（History 图标 + 商户名 + 浅色历史分类）；点选后回填商户、自动选中该商户的历史分类与性质，并在「分类」右侧显示「按历史记录自动选择（分类）」。
  - 输入框初始只有一行高：`AiScreens.kt` 新增 `ChatInputField`（BasicTextField 自绘：`RoundedCornerShape(percent = animateFloatAsState(if (multi) 30f else 50f))`，用 `onSizeChanged` 与一行行高比较来判断是否多行），记账页与 AI 聊天页共用；初始是单行胶囊，随行数增长平滑变成长方形大圆角并最多 4 行。
  - 子页面返回动画：关闭压栈页面时 `scaleOut(tween(280), targetScale = 0.62f, transformOrigin = 点击位置) + fadeOut(tween(220, delayMillis = 60))`，与进入的放大动画对称（实测可抓到页面缩小淡出、底层页面露出的中间帧）。
  - 版本号改为 1.0.1 rc2：`app/build.gradle.kts` 的 `versionCode = 2` / `versionName = "1.0.1-rc2"`，关于页显示「版本 1.0.1 rc2（versionCode 2）」。
- 第五轮 6 项整改（真机逐项确认，release 包 `com.xzledger.app`，1.0.2 rc1 / versionCode 3）：
  - 明细左滑删除键贴合账单右缘：`ui/DetailScreen.kt` 的 `SwipeToDeleteRow` 外层 `Box` 加 `.clipToBounds()`，红色删除块宽度实时等于 `val revealed = (-offset.value).coerceAtLeast(0f)`（`fillMaxHeight().width(revealed.toDp())`），左缘保持直角、只留右侧 16dp 圆角与日卡片圆角对齐，并随滑动一起变宽；块内只放「删除」/「删除中」文本并严格居中（去掉原来的垃圾桶图标）。实测中途约 57dp、滑到底约 91dp，左缘始终与账单行右缘贴合。
  - 导入按订单编号判重（跨文件格式）：根因是 Excel 与 CSV 的平台标识不一致（`.xlsx` 曾硬编码为 `Excel`、微信 CSV 记为 `微信支付`），同一笔用两种格式导入会生成不同的去重键。现 `data/ImportFiles.kt` 新增 `platformOf(kind)` 与 `xlsxPlatform(name, table)`（用表格前 60 行样本文本识别微信/支付宝），CSV 与 xlsx 走同一平台；`data/TxnDao.kt` 新增 `Db.findByAnyNo(ledgerId, tradeNo, orderNo, excludeId)` 跨平台按单号判重；`data/ImportService.kt` 的 `buildRow` 增加跨平台判重（退款不参与，避免退款被当成原付款的重复），`ImportSession.start` 调 `dedupBatch` 做批内判重。`ui/ImportScreens.kt` 预览页新增说明「单号判重：本批 N 笔带交易单号/订单编码……没有单号的记录走「疑似重复」人工核对」。实测：同批选中微信 CSV + 微信 XLSX → 新增 0 / 重复 2（xlsx 两笔提示「本次导入中重复：与「榆中爱钰糕点店」使用同一订单编号（WXTEST20261005001），已自动跳过」）；确认保存 2 笔后再单独导入同一个 xlsx → 新增 0 / 重复 2，提示「已存在相同交易单号/订单编码的记录（2026-10-05 12.00 元）」。
  - 明细左上角图标去黄底并放大：由 32dp 淡黄圆底改为 40dp 圆形无底（`.clip(CircleShape)`），头像 `ContentScale.Crop` 并放大 1.5 倍裁掉原图留白；「我的」页默认头像在二次元元素开启时同步改为桑多涅（未设头像时才不再垫黄圈）。
  - 桑多涅（二次元 / 原神风格）元素与个性化开关：`ui/theme/Theme.kt` 的 `Appearance` 新增 `anime: Boolean = true`，`data/Settings.kt` 新增 `appearance.anime` 键；新建 `ui/Anime.kt`（`animeEnabled()`、四角星 `Sparkle`、`SandroneFace`、带外发光与星芒的 `SandroneBadge`、点击换一句台词的 `MascotBubble` 与 `mascotLines`）；明细页月度汇总下方显示看板娘台词气泡，我的页显示默认桑多涅头像与看板娘卡片；「个性化设置」新增「二次元元素（桑多涅）」开关（关掉后回到纯记账界面：头像变回文字、气泡与卡片消失）。同时修复 `data/Settings.kt` 的 `Store.bool(key, def)`：`db.getSetting` 对缺失键返回 `""` 而非 null，原 `?:` 分支永不生效，导致 4 个默认开启项（二次元元素 / 动画效果 / 平滑出现动画 / 液态玻璃）在全新安装时被当成 false——改为 `val v = db.getSetting(key); if (v.isEmpty()) def else v == "1"`。
  - 桌面小组件预算改环形比例条：新增 `widget/BudgetRing.kt`（`render(sizePx, usedPct, over)` 用 Canvas 画整圈轨道 + 从 12 点顺时针的金色弧 + 圆心百分比，超支整圈变红并显示实际百分比，经 `setImageViewBitmap` 交给 RemoteViews）；4x2 与 2x2 布局重排（`img_budget_ring` 60dp / 58dp，并删除旧的 `txt_budget_pct`、`bar_budget`、`bar_budget_over` 引用，否则 RemoteViews 抛错会被外层 `try/catch` 吞掉导致整帧不刷新）。实测：设 100 元预算、已用 12.34 元后，桌面 4x2 显示「本月预算（环形）」金环 12% +「剩余¥87.66」，2x2 显示大金环 12% + 剩余金额。
  - 版本号改为 1.0.2 rc1：`app/build.gradle.kts` 的 `versionCode = 3` / `versionName = "1.0.2-rc1"`，关于页显示「版本 1.0.2 rc1（versionCode 3）」。
- 第六轮 2 项整改（真机确认，1.0.2 rc1 / versionCode 3）：
  - 2x2 桌面小组件底部「剩余 / 超支」被裁掉：`app/src/main/res/layout/widget_square.xml` 由竖排（顶部日期 → 支出/收入两行 → 58dp 环形条 → 剩余文字）改为左右排布（左侧支出/收入，右侧 52dp 环形条 + 下方剩余金额），内边距 10dp→9dp、环形条 58dp→52dp、日期 10sp→9sp、预算文字 11sp→10sp；同时把 `widget/CatWidgetProvider.kt` 的 `ringSizeDp` 与布局里的 ImageView 对齐（Square 76→52、Wide 80→60），环形位图不再被缩放，圆心百分比更清晰。
  - AI 聊天输入框超过两行后继续长高、最高 3 行：`ui/AiScreens.kt` 的 `ChatInputField(..., maxLines = 3)`（记账页与全屏 AI 聊天页共用同一组件；上一轮遗留的 `if (slim) 1` 把记账页锁死在单行，本轮一并放开），超过 3 行在框内滚动；记账页整页可滚动（`EntryScreen.kt` 的 `Column(...verticalScroll(rememberScrollState()))`），输入框长高不会把键盘顶出屏幕。
- 第七轮 4 项整改（真机逐项确认，debug + release 包，1.0.2 rc1 / versionCode 3）：
  - 预算可修改：`ui/StatsScreens.kt` 的 `BudgetScreen` 新增本地函数 `editBudget(exist, categoryId)`（无既有预算时新建 `Budget(id = UUID.randomUUID().toString(), month = if (categoryId == null) month else "ALL")`），「本月总预算」卡片加 `Modifier.clip(RoundedCornerShape(28.dp)).clickable { editBudget(list.firstOrNull { it.categoryId == null }, null) }`，未设置时提示改为「尚未设置总预算（点按这里设置）」，说明改为「点按可修改或删除；预算按净支出（支出-退款）计算，转账不计入。」；点按复用既有「设置/修改预算」对话框并预填原金额（`Money.centsToEdit`）与「删除该预算」；分类预算的 `ChoiceSheet.onPick` 改为先查已有项再 `editBudget`，避免重复插入总额/分类预算；保存与删除都调用 `state.touch()`。实测 debug：预算 2,500.00 → 点卡片 → 预填 2500.00 → 改 3000 保存 → 立即显示「已用 500.00 / 预算 3,000.00 16%」；release：100.00 → 点卡片弹出「修改预算 / 金额（元）100.00 / 删除该预算」。
  - 增删改全局即时同步：根因是多处写库只改屏幕本地 `version` 而没调 `AppState.touch()`（`XzApp.kt:64-71`，`dataVersion++` + `WidgetRefresh.updateAll`），`dataVersion` 不变 → 其他页面以及被 SubPage 压住、仍在组合中的页面都拿到旧数据，用户感知为「要重启才生效」。本轮在 21 处补齐：`ui/ManageScreens.kt` 分类上移/下移、删除分类、分类保存、账户删除、账户保存、账本新建/重命名/删除；`ui/Learning.kt` 规则开关/删除/保存与学习记录「建立规则」；`ui/RecurringScreen.kt` 启用开关/自动入账/立即执行/删除/保存；`ui/StatsScreens.kt` 预算保存/删除；`ui/AiScreens.kt` AI 配置删除/保存与 `db.setAiPermission`。实测：在记账页点分类行末尾「＋」进分类管理新增分类，保存后返回该页（未重启、未切 tab）新分类立即出现在分类行；把「交通」改名为「交通T」后切到记账 tab，分类胶囊立即显示「交通T」（明细行标题不变属正常：`ui/DetailScreen.kt:292` 用账单自带的 `txn.title`）。
  - 记账页底部输入栏贴底并随键盘升降：`ui/EntryScreen.kt` 去掉原来的 `Box(Modifier.fillMaxSize().imePadding())` 以及内容列末尾的 `ChatCardCompact` + `Spacer(150.dp)`，改为先算 `val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()`、`val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()`、`val barBottom = if (imeBottom > navBottom) imeBottom else navBottom + 78.dp`（键盘高度与胶囊条高度取较大值），内容列底部留 `barBottom + 62.dp`，输入栏改为 `Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = barBottom)) { ChatCardCompact(...) }` 浮在保存键等内容之上，toast 抬到 `barBottom + 72.dp`。键盘弹出时整条输入栏（含左侧 ✨/🎤 按键）同步上移、紧贴键盘上沿且不被覆盖；收起时停在底部胶囊条正上方。debug 与 release 包均实测。

- 第八轮 2 项整改（真机确认，debug 包 `com.xzledger.app.debug`，1.0.2 rc1 / versionCode 3）：
  - 所有桌面小组件都显示「结余」，预算剩余改按结余算：`widget/WidgetData.kt` 的 `SQL_MONTH` 增加 `income`，`Snapshot` 新增 `monthIncome` / `monthNetExpense` / `balance`（= 本月收入 − 本月净支出，与 `data/TxnDao.kt` 的 `monthSummary.balance`、「明细」页结余同口径）与派生属性 `budgetRemaining = budgetTotal + balance`；预算的「已用」改为 `净支出 − 收入`（收入多于支出时按 0 处理），因此本月有收入时剩余会大于「预算 − 支出」。`widget/CatWidgetProvider.kt` 新增 `money(cents)` 统一输出（负数显示 `-¥385.28` 而不是 `¥-385.28`）并写入新控件 `txt_balance`；4x2 布局 `res/layout/widget_wide.xml` 把原「本月预算（环形）」文字位换成 14sp 加粗「结余」行（环形条保持 60dp，右列约 94dp < 左列约 102dp，整体不增高），2x2 布局 `res/layout/widget_square.xml` 在「剩余」下方增加 10sp「结余」行。实测 4x2：今日支出 ¥50.44 / 今日收入 ¥0.00 / 结余 -¥385.28（与明细页 2026年10月 结余 -385.28 一致）/ 环形 15% / 剩余 ¥2,114.72 = 预算 2,500 + 结余（−385.28）——按旧算法只会显示 2,500 − 547.78 = 1,952.22。
  - 小组件背景透明度可调：`data/Settings.kt` 新增 `SettingsKeys.WIDGET_BG_ALPHA = "widget.bg_alpha"` 与 `Store.int/setInt`；`res/drawable/widget_bg.xml` 改为纯圆角底色（不透明基准 `#FF1A1714`），金色描边拆到新建的 `res/drawable/widget_bg_stroke.xml` 并作为两个根布局的 `android:foreground`（API 23+，绘制在内容之上、不拦截点击），这样调透明度不会把描边一起淡掉；`widget/CatWidgetProvider.kt` 的 `applyBackground()` 用 `RemoteViews.setColorStateList(R.id.widget_root, "setBackgroundTintList", ...)` 按 0–100 上色（95% 即原来的 `#F21A1714`，缺省值也是 95）。「个性化设置 → 桌面小组件」新增「背景透明度」滑杆对话框（0–100%、步进 5%，保存即 `WidgetRefresh.updateAll`）。实测：同一采样网格上，不透明度 95% → 15% 后桌面小组件背景平均亮度 49.5 → 129.5、明显透出壁纸，且金色描边与全部文字仍清晰；0% 时底色完全透明、只剩金色描边（按实现）。
  - 同节新增两条「添加 今日概览（4x2） / 添加 预算（2x2）」入口（`AppWidgetManager.requestPinAppWidget`）。如实说明：本机 ColorOS 桌面虽然对 `isRequestPinAppWidgetSupported` 返回 true，但点按后不弹确认框、`dumpsys appwidget` 也不新增实例（日志 `pin supported=true`），因此该入口在 ColorOS 上等同于无响应，条目副标题已写明「若桌面无反应，请长按桌面空白处手动添加」；在原生桌面（Pixel 等）上该入口可正常弹窗添加。

- 第九轮 4 项整改（ColorOS 17 液态玻璃改版，1.0.3 rc1 / versionCode 4；按用户要求本轮未做实机验证，仅编译打包）：
  - 新的液态玻璃设计系统：新增 `ui/theme/Glass.kt`，提供 `@Composable fun Modifier.liquidGlass(corner: Dp = 28.dp, strong: Boolean = false, enabled: Boolean = LocalAppearance.current.glass, elevation: Dp = 14.dp, tint: Color = Color.Transparent): Modifier` 与 `GlassBackdrop()`。面板由四层叠加：半透明基底（深色 #1A1E26 / 浅色白，strong 时 0.95→0.88、普通 0.78→0.62）、左上斜向主高光（深色 0.11 / 浅色 0.55 起）、底部主题色环境反光（0.16 / 0.10）、1dp 上亮下暗的渐变厚边，外加 `shadow(elevation, shape, clip = false)` 投影。**如实说明**：Compose 的 `RenderEffect` 只作用于自身内容，无法采样兄弟节点做真实背景模糊，这里的「液态玻璃」是分层模拟，不是实时高斯模糊；关闭 `appearance.glass` 时退回不透明纯色 + 发丝描边。`GlassBackdrop` 在 `MainActivity.kt` 的 Surface 内铺三团 radial gradient（左上主色、右侧辅色、底部回光），关闭玻璃时只保留纯背景色——彩色氛围底是玻璃「通透感」的来源。
  - 开关：`ui/Common.kt` 新增 `GlassSwitch(checked, onCheckedChange, modifier, enabled)`：50×30dp 玻璃胶囊轨道（主题色渐变 / 关闭态 14% onSurface）+ 顶部高光 + 24dp 玻璃滑块（径向反光、2dp 投影），点按与左右拖动都能切换，滑块位置用 `Animatable` + `spring(dampingRatio = 0.55f, stiffness = 700f)` 回弹，按下时滑块放大到 1.10。Material3 `Switch` 的 14 处直接调用点全部替换（`ui/Learning.kt`、`ui/ManageScreens.kt`、`ui/MineScreen.kt`、`ui/RecurringScreen.kt`、`ui/SearchScreen.kt`），`SwitchRow` 内部也改用 `GlassSwitch`，全应用不再出现 Material 开关。
  - 底部胶囊与子页面转场：`MainActivity.kt` 的 `GlassNav` 改为 34dp 圆角 strong 玻璃胶囊，78dp×46dp 条目，高亮块用 `spring(dampingRatio = 0.62f, stiffness = 260f)` 滑动、到位时 0.82→1 回弹，选中图标 1.12 缩放。子页面不再用 `AnimatedContent` 的 `scaleIn/scaleOut(initialScale = 0.62f)` 平面缩放，改成 `AppRoot` 里一个 `Animatable progress` 驱动的三层结构：底层三主页 + 胶囊导航在打开时缩到 0.935、裁成 28dp 圆角；中间黑色压暗层 0.34×progress；最上层子页面以「按下的位置」`TransformOrigin(openX, openY)` 从 0.32→1 缩放、圆角 34dp→0、alpha 在 progress≈0.72 时到 1，进场 `spring(dampingRatio = 0.72f, stiffness = 360f)`、退场 `spring(dampingRatio = 0.88f, stiffness = 520f)`，打开瞬间锁定原点，页面内后续点击不会改掉退场原点；子页面空白处加了一层吞点击的 `Box`，避免隔着页面点到被压住的胶囊导航。关闭「动画效果」时用 140ms tween 直接到位。
  - 其它 UI 向 ColorOS 17 靠拢：`TopBar` 返回键改成 40dp 玻璃圆钮、标题用 22sp SemiBold；`ChoiceSheet` 改为透明容器 + 悬浮玻璃面板（30dp 圆角、拖拽提示条、38% 黑色 scrim）；`ConfirmDialog` 改 30dp 圆角与玻璃色容器；`ChipButton` 未选中态为玻璃胶囊（选中态主题色渐变）；`SettingRow` 按下时整行 0.985 内收（去掉方形水波纹）；`SectionTitle` 改为左 20dp 的段落标题间距。
  - 桌面小组件液态玻璃：`res/layout/widget_wide.xml` 与 `widget_square.xml` 根布局由 LinearLayout 改为 FrameLayout（id 仍为 `widget_root`，background = 可调透明度底色、foreground = 金色厚边），新增 `@+id/widget_gloss` 的 View 与 `res/drawable/widget_gloss.xml`（24dp 圆角、顶部 #2EFFFFFF → #0CFFFFFF → 透明的反光渐变）；`widget_bg.xml`/`widget_bg_stroke.xml` 圆角 20dp→24dp。`widget/WidgetData.kt` 的 `Snapshot` 新增 `glassEnabled`（读 `appearance.glass`，空值按开启），`CatWidgetProvider.applyBackground(context, views, alphaPct, glass)` 按开关 `setViewVisibility(R.id.widget_gloss, VISIBLE/GONE)`；`AppState.updateAppearance` 保存后立即 `WidgetRefresh.updateAll`，切换玻璃时桌面小组件同步换装。
  - 版本号同步：`app/build.gradle.kts` versionCode 4 / versionName `1.0.3-rc1`，`ui/SettingsScreens.kt` 的 `APP_VERSION = "1.0.3 rc1"` 与关于页「（versionCode 4）」。

- 第十轮 11 项整改（debug + release 包，1.0.4 rc1 / versionCode 5）：
  - 结余口径改为「月预算 + 收入 − 支出（净）」：`ui/DetailScreen.kt:96-99` 用 `db.monthSummary(ledgerId, month)` 与总额预算 `db.budgets(ledgerId, month).firstOrNull { it.categoryId == null }?.amount` 算出 `val balance = (budgetTotal ?: 0L) + summary.income - summary.netExpense`，汇总第三格「结余」直接显示该值，说明文案改为「结余 = 本月预算 ¥2,500.00 + 收入 - 支出（净）……与账户余额口径不同。」；`ui/MineScreen.kt` 第三格由「账本结余」改名「账户余额」（各账户余额之和），副标题写清两种口径不同，避免两处「结余」打架。
  - 桑多涅台词改为「本月已经花掉百分之多少的预算」：`ui/Anime.kt:142` 的 `fun mascotLines(netExpense: Long, income: Long, budgetTotal: Long?, count: Int)` 先算「真正动用预算的钱」`val used = (netExpense - income).coerceAtLeast(0L)`（支出净额减收入，最低 0、永不为负），再 `pct = Math.round(used * 100.0 / budgetTotal)`，台词「本月已经花掉 N% 的预算，桑多涅帮你盯着。」；未设置预算时改为「本月已经花掉 ¥X，还没设置预算，桑多涅先记着。」；调用点 `ui/DetailScreen.kt:195` 的 `remember` 键加入 `summary.income`。
  - 我的页新增顶层分组「周期管理」并排在第一位：预算设置 / 月度统计 / 周期记账与订阅提醒 三项合并进同一组（与「智能记账」「数据管理」同级），「记账提醒」由原位置移入「数据管理」分组。
  - AI 习惯学习（第 4 项）：`data/AiLearn.kt` 在「已配置 AI 服务 + 授权 AI 权限 → 新增账单」时，把「商户 + 本次分类/性质/备注」发给模型，要求只回一行 JSON `{"remember":true|false,"match_type":"merchant_exact|merchant_contains|keyword","pattern":"…"}`；`remember = false`、商户过于通用（超市/转账/支付宝等）或已有同样结论的规则时不建规则；建规则后写一条 `origin = "ai"` 的学习记录，规则名固定为「AI 学习 · 商户」。任何失败静默忽略、不联网。本机实测已生成该规则（记账页提示「依据你的规则：AI 学习 · 闲鱼」）。
  - AI 气泡不再限制 3 行：`ui/AiScreens.kt` 的 `MessageRow`（约 :636-662）去掉原有的 3 行上限与气泡内部滚动，改成 `Text(m.content, style = MaterialTheme.typography.bodyMedium)` 自然长高（宽度仍上限 260dp / 展开页 300dp），长回复一次显示完整内容。
  - AI 聊天页顶部收纳：`ui/AiScreens.kt` 顶部卡片只留「模型：xxx / 账本：xxx / 状态」与一个 `⋮`（`IconButton` + `DropdownMenu`，`contentDescription = "更多"`），菜单内是「AI 权限管理」（`nav.push(Screen.AI_PERM)`）与「清空聊天记录」（走 `ConfirmDialog`，只删本账本聊天记录，不动已入账账单）。
  - 记账页收入下不显示「长期消费 / 即时消费」：`ui/EntryScreen.kt` 性质胶囊改为只在 `type != TxnType.TRANSFER && type != TxnType.INCOME` 时渲染；转账与收入下也不写 `nature`（保存时 `nature = if (type == TxnType.TRANSFER || type == TxnType.INCOME) null else nature`）。
  - 收/支历史互不串用：`data/TxnDao.kt` 的 `fun Db.merchantHints(ledgerId: String, limit: Int = 12, direction: TxnType? = null)` 在传入方向时追加 `" AND type=?"`；记账页分别取 `expenseTips`（`TxnType.EXPENSE`）与 `incomeTips`（`TxnType.INCOME`），历史胶囊随支出/收入切换。实测支出历史为「兰州大学 餐饮 / 平账 误差平账 / 淘宝 网购」，收入历史为「闲鱼 / 京东 / 滴滴 红包 / 肯德基 红包」。
  - 退款（第 8 项后半）：记账页分类行最前面新增「退款」胶囊（只在支出 / 退款状态下显示，收入与转账下隐藏），点它进入新页面 `ui/RefundPickScreen.kt`「选择退款账单」（搜索框 + 只列支出账单 + 已退金额与「剩余可退 ¥y」）；选中后 `AppState.refundTarget` 带回记账页，`EntryScreen` 的 `LaunchedEffect(state.refundTarget)` 自动填入金额、商户、分类、性质、账户并写「退款对象：标题（日期）」，分类行说明改为「退款对象」。保存时生成一条**独立的退款账单**（`type = TxnType.REFUND`、`refundOf = 原账单 id`、`dedupKey = "refund|原账单id|时间"`、`accountId` 沿用原账单、标题「退款：原标题」），金额不得超过原账单（超出提示「退款金额不能超过原账单金额」）；原账单**一个字段都不改**，本月支出按净额（支出 − 退款）统计。保存成功后自动回到普通支出记账状态并提示「已记录退款，本月支出已相应抵减」。
  - 历史胶囊可反选：`ui/EntryScreen.kt` 的历史胶囊点击改为「同一个商户再点一次即取消」——`if (merchant.equals(h.name, ignoreCase = true)) { merchant = ""; historyNote = "" } else { 原逻辑 }`。
  - 我的页桑多涅面板（第 10 项）：新增 `ui/SandroneAdvice.kt` 的 `@Composable fun SandroneAdvicePanel(state: AppState, modifier: Modifier = Modifier)`，右侧一个 26dp「？」圆钮；`detectTapGestures(onPress = …)` 里用 `Animatable` 跑 3000ms 线性进度，同时 `Box(Modifier.matchParentSize().drawBehind { … })` 沿整个面板的圆角矩形轮廓绘制金色环形进度（`Path` + `PathMeasure.getSegment(0f, len * p, …)`，3dp 圆头）；不足 3 秒松手即取消。满 3 秒且「已配置 AI + 授权读取账本」时调用一次模型，让桑多涅给出本月消费评价与建议（system prompt 限定人设与 ≤2 句 / ≤80 字），结果存 `SettingsKeys.MINE_ADVICE_TEXT`、日期存 `SettingsKeys.MINE_ADVICE_DATE`，同一天再长按直接显示已存文本（一天最多一次）；未配置服务时提示「还没有可用的 AI 接口」。
  - 第 11 项「不改动已经记录的账单」：退款走独立账单 + `refundOf` 关联、原账单只读；AI 学到的规则只影响后续自动分类，不回写历史账单；分类改名只影响展示，账单内 `title` 不变。
  - 版本号同步：`app/build.gradle.kts` versionCode 5 / versionName `1.0.4-rc1`，`ui/SettingsScreens.kt` 的 `APP_VERSION = "1.0.4 rc1"` 与关于页「（versionCode 5）」。

- 第十一轮 10 项整改（1.0.5 rc1 / versionCode 6）：
  - 桌面小组件「载入窗口小部件时出现问题」修复：根因是 `RemoteViews` 的视图白名单不允许裸 `<View>`——`res/layout/widget_wide.xml` 与 `widget_square.xml` 里的玻璃高光层写成了 `<View android:id="@+id/widget_gloss">`，宿主 inflate 时抛 `InflateException: Class not allowed to be inflated android.view.View`，桌面把整块卡片替换成错误提示。现改为 `<ImageView android:id="@+id/widget_gloss" android:background="@drawable/widget_gloss" android:contentDescription="@null" android:importantForAccessibility="no" />`。`widget/CatWidgetProvider.kt` 同时在 `updateAppWidget` 前加 `selfCheck()`（仅 debug 包启用：`views.apply(context, FrameLayout(context))` 试渲染并打 `XZWidget` 日志），原本静默的 `catch` 改成 `Log.e(TAG, "updateOne id=… failed", t)`，同类问题下次会直接出现在 logcat。
  - 预算「已用」按 支出 − 收入 计算：`ui/StatsScreens.kt` 预算页的 `used` 由 `sum.netExpense` 改为 `(sum.netExpense - sum.income).coerceAtLeast(0L)`，与明细页「结余 = 月预算 + 收入 − 支出（净）」同口径；进度条、「已用 ¥x / 预算 ¥y」与说明文字同步改为「已用 = 支出（净）− 收入……桌面小组件的「预算剩余」同样按结余算」。
  - 月度预算「每月自动沿用」：预算页总预算卡片新增「每月自动沿用」开关。打开时写入模板预算（`id = SettingsKeys.BUDGET_AUTO_ID_PREFIX + ledgerId`、`month = "ALL"`、`categoryId = null`），此后每月自动套用，卡片标题旁显示「沿用每月默认」；某个月单独修改只写 `month = "yyyy-MM"` 的行，模板不受影响。`data/TxnDao.kt` 新增 `fun Db.monthBudgetTotal(ledgerId: String, month: String): Long?`（当月行优先、没有则回落到 ALL 模板），桌面小组件本来就是这个读取顺序，因此自动沿用对小组件同样生效。
  - 明细页左上角桑多涅头像 → 一键回到当月：`ui/Anime.kt` 的 `MascotBubble(text, modifier, onClick, onAvatarClick)` 新增 `onAvatarClick`（头像 38dp 的 `Box` 加 `clickable`），`ui/DetailScreen.kt` 传 `onAvatarClick = { state.month = Dates.monthKey(System.currentTimeMillis()) }`，仅在不在当月时切换。
  - 我的页「？」圆钮居中：原来 `Text("？")` 是全角字符、字面比容器宽而视觉偏左，改成 `Icon(Icons.Outlined.QuestionMark, tint = primary, modifier = Modifier.size(15.dp))`。
  - 我的页分组「周期管理」→「数据统计」，月度统计排最前：顺序为 月度统计（副标题「左右滑动切月；分类占比、商户排行、趋势」）→ 预算设置（「月度总预算（可设每月默认）与分类预算」）→ 周期记账与订阅提醒。
  - 我的页第三格「账户余额」改为随月份更新的「本月结余」：`data/TxnDao.kt` 新增 `fun Db.monthBalance(ledgerId: String, month: String): Long` = `(monthBudgetTotal ?: 0) + 收入 − 支出（净）`；`ui/MineScreen.kt` 的统计改为「<月份> 支出 / 本月笔数 / 本月结余」，副标题写明「本月结余 = 本月预算 + 收入 − 支出（净）……在明细页切换月份后，这里也随之更新」。此前这一格是各账户余额之和（终身口径），换月不变也不含预算。
  - 月度统计左右滑动平滑切月：`ui/StatsScreens.kt` 的 `StatsScreen` 拆成「顶栏 + 月份切换 + 内容」，内容外包 `pointerInput { detectHorizontalDragGestures(...) }`（累计位移阈值 64dp，松手按方向 `Dates.shiftMonth(±1)`），用 `AnimatedContent(targetState = month, transitionSpec = slideInHorizontally(tween(320)){ it / 3 } + fadeIn(tween(240)) togetherWith slideOutHorizontally(tween(320)){ -it / 3 } + fadeOut(tween(180)))` 做 1/3 屏滑入滑出；四张卡片抽成 `MonthStats(state, month)`。
  - 发布到 GitHub 并内置链接：仓库 `https://github.com/XiaozhuangXzsg/XZ-Ledger`（public，首次提交含全部源码；`.gitignore` 排除 `build/`、`local.properties`、`keystore.properties`、`keystore/`、`*.log` 与开发用截图目录，签名与 SDK 路径不进仓库）。`data/Settings.kt` 新增 `object AppLinks { const val GITHUB; const val RELEASE }`：`GITHUB` = 仓库地址，`RELEASE` = `https://github.com/XiaozhuangXzsg/XZ-Ledger/releases/latest`；我的页与关于页的「给项目 Star」「分享应用」都改为「用户填了就用用户的，留空回落到默认」，因此**分享应用现在分享的是 GitHub 最新 Release 链接**。
  - Apple Liquid Glass 风格（第 10 项）：`ui/theme/Theme.kt` 的 `Appearance` 新增 `liquidApple: Boolean = false`（键 `appearance.liquid_apple`）；`ui/theme/Glass.kt` 的 `liquidGlass(...)` 开启时圆角放大 1.18 倍、基底透明度降到 0.44 / 0.28（strong 0.66 / 0.50）、阴影放大 1.7 倍并改低对比环境色、斜向镜面高光加宽、底缘加内阴影与彩色折射、描边 1.4dp 且更亮；`GlassBackdrop()` 的 Apple 分支改为「自上而下的白/黑薄雾 + 两团极低饱和 radial」。`ui/SettingsScreens.kt` 在「二次元元素」上方新增开关「Apple 液态玻璃（WWDC 2025）」。**如实说明**：Compose 无法对兄弟节点做真实高斯模糊，这里仍是分层模拟；本轮只改视觉层，未改业务流程、数据表结构与交互流程。
  - 版本号同步：`app/build.gradle.kts` versionCode 6 / versionName `1.0.5-rc1`，`ui/SettingsScreens.kt` 的 `APP_VERSION = "1.0.5 rc1"` 与关于页「（versionCode 6）」。

- 第十二轮 A 通知自动记账（1.0.6 rc1 / versionCode 7；全部本机解析、默认待确认）：
  - 权限与入口：`AndroidManifest.xml` 注册 `autorecord/NotifyListenerService.kt`（`android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`，`exported="true"` + `android.service.notification.NotificationListenerService` 的 intent-filter）；「我的 → 智能记账 → 通知自动记账」新页面 `ui/NotifyScreens.kt`：首次使用前必须阅读的隐私与风险说明（只读勾选应用的通知、解析全在本机、不申请无障碍服务 / 不读短信数据库 / 不取银行账号密码、验证码与密码令牌类通知直接丢弃、可随时撤销授权或清除本地解析记录），一键跳系统「通知使用权」，页内实时显示「通知使用权：已授予 / 未授予」与「系统监听服务：已连接 / 未连接」并可「刷新状态」；总开关（`SettingsKeys.NOTIFY_ENABLED`）、模式三选（关闭 / 待确认（默认）/ 自动记账，`NOTIFY_MODE`）、待确认入口（带笔数）、自动记账记录（最近 100 条）、清除本地解析记录、监听应用多选。
  - 来源判定按**包名**而非通知标题：`autorecord/NotifyPrefs.kt` 的 `installedApps(ctx)` 用 `queryIntentActivities(MAIN/LAUNCHER)` 列出真实已安装应用，`packages(store)` 存勾选的包名集合；`AndroidManifest.xml` 的 `<queries>` 增加 MAIN/LAUNCHER 意图，否则 Android 11+ 的包可见性会把列表削到只剩几个应用（本轮修复：此前搜索「支付宝」返回「没有匹配的应用」，不是搜索逻辑问题而是包不可见）。内置 10 组来源规则（支付宝、微信支付、云闪付 + 工商 / 建设 / 农业 / 中国 / 招商 / 交通 / 邮储银行），未内置的应用走通用规则（金额、方向、商户、余额），置信度更低、通常只进待确认；「解析规则」卡片按「已安装 / 未安装」标注每家银行。
  - 解析：`autorecord/TxnParser.kt` 提取金额、方向、时间、机构、账户、卡尾号、商户、备注、币种、流水号、余额；**交易金额与账户余额严格分开**（「您尾号1234的储蓄卡于10月20日消费人民币35.80元，账户余额2580.36元。」→ 支出 35.80、余额 2580.36；「您的账户收到转账人民币500.00元。」→ 收入 500.00）；类型区分「消费 / 收入 / 退款 / 账户间转账 / 信用卡还款 / 提现充值 / 手续费利息 / 仅余额变动 / 非交易 / 无法确定」，银行内部转账与信用卡还款不会被记成新的收支；验证码、登录、广告、贷款、额度类通知直接过滤、不落库；置信度不足的识别结果只进待确认、绝不静默入账。
  - 去重（`autorecord/NoticeDedup.kt` + `data/AutoRecordDao.kt` + `data/AutoRecordSchema.kt`）：有流水号时用「来源应用 + 流水号」强键，否则用「来源 + 方向 + 金额 + 分钟级时间 + 规范化商户」指纹；待确认表带唯一约束、写入前查重，所以同一条通知被系统重复投递、应用更新同一条通知、重启后重放都会落到同一指纹；跨来源（银行 App 与支付宝 / 微信）金额相同、方向相同、10 分钟内且来源不同只标「疑似重复」，只提示、不删除、不自动合并；**绝不只用金额判重**（连续两笔同额消费都能正常入账）。
  - 三种模式与账户匹配：待确认（默认）下所有结果先进待确认，逐笔「确认入账 / 忽略」，确认后写入真实账本并 `state.touch()`，明细与统计即时刷新、无需重启；自动记账只在「格式完整、置信度高、账户已匹配、非重复」时才无人值守入账（每笔都带 `source = notify` 与去重键，可在账单详情删除、在「自动记账记录」里追溯）；账户按卡尾号 / 银行名匹配，多账户同时匹配时不自动挑、匹配不到就留空并在待确认里提示绑定，用户绑定后记住；从不读取或索取银行卡号、密码、短信验证码。
  - 隐私边界：全部本机解析，默认不联网、不上传通知文本，也不接第三方分析；只保留结构化摘要（金额 / 方向 / 时间 / 商户 / 卡尾号 / 置信度），不保存通知原文；验证码 / 密码 / 登录令牌类通知在解析前丢弃；日志只打条数与结果、不打印正文与账号；关闭总开关即停止处理，「清除本地解析记录」清空待确认与记录、已入账账单不受影响。
  - 测试：新增 `app/src/test/java/com/xzledger/app/autorecord/TxnParserTest.kt`（15 个虚构语料解析用例）与 `NoticeDedupTest.kt`（9 个指纹 / 跨来源用例），`gradle :app:testDebugUnitTest` 24 个用例全通过；debug 包另加「调试工具（仅调试版）→ 注入测试通知」按钮（`if (BuildConfig.DEBUG)` 门控），注入一条虚构银行通知走完整「解析 → 判断 → 待确认」链路，release 包不含该入口。
- 第十二轮 B GitHub 应用内更新（非破坏性更新优先）：
  - 仓库配置集中在 `update/UpdateConfig.kt`（`OWNER` / `REPO` / `RELEASES_API` / `METADATA_ASSET = "update.json"` / `CHECK_INTERVAL_MS = 24h` / `SNOOZE_INTERVAL_MS = 6h`），客户端不内置任何 GitHub Token、也不含签名私钥。
  - 检查：`update/UpdateChecker.kt` 读 GitHub Releases API（`per_page=20`），跳过 `draft` 与 `prerelease`，**只按 versionCode 比高低**（versionName 仅用于展示）；versionCode 与 SHA-256 取自 Release 资产 `update.json`，缺失时明确提示「最新 Release（…）缺少 update.json，无法核对 versionCode 与 SHA-256，已跳过本次更新」而不做猜测；启动后最多每 24 小时自动检查一次，另有「我的 → 更多 → 检查更新」手动入口（`ui/UpdateScreens.kt`）；「稍后再说」静默 6 小时，不反复打扰。
  - 安装前硬校验：`update/ApkVerifier.kt` 对**实际下载到的文件**计算 SHA-256 并与元数据比对，再校验包名 == `BuildConfig.APPLICATION_ID`、versionCode 不低于当前、签名证书 SHA-256 与当前应用一致（任何一项不符直接拒绝并给出中文原因，不做「先装再说」）；下载走应用私有目录 + `FileProvider`（`res/xml/file_paths.xml`）+ 系统安装器，不要求卸载、不清数据。
  - 数据安全：不换 applicationId、不换签名，每次发版只递增 versionCode；更新是覆盖安装，SQLite（`xz_ledger.db`）与 SharedPreferences 原样保留，数据库升级仍只有 `onUpgrade` 里的非破坏性迁移；「导出与备份恢复」保持可用，升级前也可以先手动导出一份。
  - 发布流水线：新增 `.github/workflows/release.yml` —— 推 `v*` 标签（或手动触发）时用 Gradle 8.13 + JDK 21 构建 release APK，用 secrets 还原签名库并签名，`apksigner verify` 确认不是 debug 签名，计算 SHA-256 与体积，生成 `update.json`（versionCode / versionName / apk / sha256 / size / notes），再 `gh release create` 建 Release 并上传 APK 与 update.json（versionName 含 rc / beta / alpha 时自动标 prerelease）。**需要自行配置的仓库 secrets**：`SIGNING_KEY_BASE64`（release keystore 的 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；本仓库目前尚未配置这些 secrets，因此流水线还没有跑过（如实标注）。
  - 版本号同步：`app/build.gradle.kts` versionCode 7 / versionName `1.0.6-rc1`，`ui/SettingsScreens.kt` 的 `APP_VERSION = "1.0.6 rc1"` 与关于页「（versionCode 7）」。

### 已实现但**未在真机验证**（如实标注）
- 真实外部 AI API 的连接、权限门控与工具调用：本机已配置服务（deepseek-chat）并实测了聊天查询与「AI 自学习」建规则；仍**未**在真机触发的是「我的页长按 3 秒让 AI 评本月账单」的最终一次调用（为避免产生真实付费调用），以及与外部 AI 相关的语音/图片/PDF 识别、云同步工具调用
- 云同步与真实服务端的推送/冲突/多端隔离（无可用服务端，仅验证适配层、请求体构造与待同步队列）
- 周期任务「到期自动生成账单」的到点触发、重启/进程被杀后的补偿与去重（引擎与幂等键已实现，未等到实际时间点）
- 语音识别、图片/截图识别、扫描型 PDF 的 OCR 路径（未接具备相应能力的模型）
- Windows 网页端（本期仅预留同一数据结构与云同步适配层，未实现）
- 我的页桑多涅「长按 3 秒 → AI 评价与建议」的**最终评价文本生成与「一天最多一次」限制**：进度环（长按约 2.4 秒环绕整个面板）已实测，但本机已配置真实 API，为避免产生真实付费调用未让长按走完 3 秒；已存文本回放、失败回退与同日拦截走同一段代码，未在屏幕上确认

- 通知自动记账只在**本机注入的虚构通知**（debug 包「注入测试通知」）上跑通了完整链路：真实银行 App / 支付宝 / 微信支付的通知格式与措辞差异、真实到账通知的延迟与批量投递、ColorOS 后台限制下监听服务被回收后的恢复、以及「自动记账」模式下的真正无人值守入账，均未在真实支付场景验证
- 通知里「账户间转账 / 信用卡还款 / 提现充值」在真实语料上的判定准确率未验证（规则与用例已写，语料为虚构）
- GitHub Actions 发布流水线未实际运行（仓库 secrets 未配置）；应用内「立即更新 → 下载 → SHA-256 与签名校验 → 系统安装」整条链路也未执行过——当前 Release v1.0.5-rc1 不含 `update.json`，「检查更新」只走到了「已跳过本次更新」这条失败安全分支

### 已知限制
- 导入的单行「已全额退款」记录会保留为支出并计入统计；要抵减需在详情页用「记录退款」生成退款事件（退款按净额展示）
- 退款以「独立退款账单」记录（`refundOf` 关联原账单），原账单内容不会被修改；单笔退款金额不得超过原账单金额，同一账单可分多次退款，明细里以净额（支出 − 退款）统计
- 年月滚轮以滑动选择；点击非当前项不会直接跳转（仅滑动 + 确定）
- 周期提醒受系统省电策略影响，极端后台限制下可能延迟

---

## 六、真机验收记录

设备：一加 PLK110，Android 16（SDK 36），ColorOS/OxygenOS 16（PLK110_16.0.3.505(CN01)），arm64-v8a。
调试包：`com.xzledger.app.debug`；发布包：`com.xzledger.app`（两者数据独立，可共存）。

已验证：三主页与胶囊导航、手动记账与增删改后一致性、日历与年月滚轮、搜索与三类筛选、CSV 与真实 xlsx 导入及重复导入判重、撤销导入、导出与恢复、AI 聊天回退路径与入账状态、学习对话框与学习记录、回收站、AI 权限/配置等管理页渲染、发布包安装启动与恢复数据、应用锁界面与帮助页。

第二轮「外观与管理页整改」12 项已逐项真机确认：底部胶囊与页面滑动切换、年月滚轮中间高亮、线条分类图标、AI 输入随键盘上移（输入行从 y2532 移到 y1466 贴住键盘）、改名与新图标、我的页重做、记账页一屏、云同步页显示、子页面重叠（根因 `GlassPanel` 内容槽改为 `ColumnScope` 并补 16dp 内边距）、4x2 小组件在桌面实时渲染（设 200 元预算后显示「剩余 ¥78.00 / 39%」金色比例条）。另修复点「记账提醒」必崩的 `StringIndexOutOfBoundsException`（空字符串顶掉 `REMINDER_TIME` 默认值）。

第三轮「体验与动画」8 项已逐项真机确认（debug 包）：按住底部「记账」不再出现长方形点击背景；日历页右上角显示「关联日程」、月视图 6 行不再错位、网格上向上滑动切到 2026年11月、当日汇总单独一行不再与「在该日记账」按钮挤在一起；明细页当日 4 笔被一个圆角玻璃框包成整体；AI 聊天超长消息气泡恰好 3 行且可内部滚动；记账提醒弹出「21 时 / 00 分」滚轮；明细左上角应用图标点击进入关于页，页面显示开发者与联系方式；子页面从点击位置缩放淡入（把系统 `animator_duration_scale` 临时调到 6 可捕捉到中间帧，不同点击位置的中间帧不同）。

第四轮「交互细节」6 项已逐项真机确认（debug 包）：日历在 `animator_duration_scale = 6` 下拖动的中间帧只有一个月在滑入、不再两月交错；明细轻滑露出删除按钮并成功删除（支出 117.00 → 99.00），从最右滑到最左保持 1 秒自动删除并弹出「已移入回收站」，删除后相邻行不再残留滑动状态、滑动内容被裁剪在日卡片内；记账页历史商户胶囊点选后自动选中该商户的历史分类并显示「按历史记录自动选择（网购）」；记账页与 AI 聊天页输入框初始单行胶囊，输入长文本后成长为多行圆角矩形；关于页显示「版本 1.0.1 rc2（versionCode 2）」；子页面返回时页面朝点击位置缩小淡出（中间帧已抓到）。测试中删除的 3 笔测试账单均已移入回收站，可随时恢复。

第五轮「二次元与小组件」6 项已逐项真机确认（release 包 `com.xzledger.app`，1.0.2 rc1 / versionCode 3）：明细左滑删除红块左缘为直角并与账单行右缘贴合、宽度随滑动增长（中途约 57dp → 滑到底约 91dp）、「删除」二字严格居中；同批选中微信 CSV + 微信 XLSX 导入得「新增 0 · 重复 2」（xlsx 两笔被批内同单号判重，逐笔提示「本次导入中重复：与「榆中爱钰糕点店」使用同一订单编号（WXTEST20261005001），已自动跳过」），确认保存 2 笔后再单独导入同一个 xlsx 得「新增 0 · 重复 2」（提示「已存在相同交易单号/订单编码的记录（2026-10-05 12.00 元）」），落库后明细页支出 47.84；明细左上角图标改为 40dp 无淡黄底、头像裁掉留白；明细页月度汇总下方出现桑多涅看板娘台词气泡（点击换一句）、我的页默认头像为桑多涅并有「桑多涅」卡片、「个性化设置 → 二次元元素（桑多涅）」开关双向生效（关闭后头像变回「猫」与淡黄圆底、气泡与卡片消失，重新打开恢复）；桌面 4x2「今日概览」与 2x2「预算」小组件显示环形比例条（设 100 元预算、已用 12.34 元 → 金色环 12% +「剩余¥87.66」，环从 12 点顺时针生长、圆心为百分比）；关于页显示「版本 1.0.2 rc1（versionCode 3）」。导入测试用的 2 笔账单已用「撤销本次导入」移入回收站，测试文件已从设备删除。

第六轮「2x2 小组件与聊天输入框」2 项已真机确认（release 包 `com.xzledger.app`，1.0.2 rc1）：2x2「预算」小组件改为左右排布后底部「剩余¥100.00」完整可见、不再被卡片下沿裁掉（此前竖排时环形条 + 剩余文字总高超出 2x2 行高）；AI 聊天记账页与记账页共用的输入框都会在文字超过两行后继续长高、第 3 行封顶（全屏聊天页实测屏幕像素高度：空 113、两行 181、三行 249、四行及以上仍 249，每行 68px；记账页收起键盘后实测 11 字符为单行胶囊、框高约 120px，64 字符为 3 行圆角矩形、框高约 253px），多余文字在框内滚动。

第七轮「预算可改与全局同步」4 项已逐项真机确认（debug 包 `com.xzledger.app.debug` + release 包 `com.xzledger.app`，1.0.2 rc1 / versionCode 3）：预算页总预算卡片可点按并预填原金额（2,500 → 改成 3,000 保存后立即显示「已用 500.00 / 预算 3,000.00 16%」；release 包上 100.00 弹出「修改预算 / 删除该预算」，说明文案为「点按可修改或删除」）；在记账页点分类行末尾「＋」进分类管理新增分类，保存后返回该页新分类立即出现在分类行（页面未重启、未切 tab），把「交通」改名为「交通T」后切到记账 tab 分类胶囊立即显示「交通T」（截图 `design/v5_24_entry_synced_end.png`、`design/v5_36_after_rename_save.png`、`design/v5_37_entry_after_rename.png`）；记账页底部输入栏收起时贴在底部胶囊条正上方（`design/v5_40_rel_entry.png`）、点开键盘后整条输入栏连同左侧按钮上移并紧贴键盘上沿、不被键盘覆盖（`design/v5_41_rel_entry_kb.png`，release 包同测）。测试用分类（TestCato / SyncTest / InPlace / 交通T）只存在于 debug 包数据库，release 包数据未受影响。

第八轮「小组件结余与透明度」2 项已真机确认（debug 包 `com.xzledger.app.debug`，1.0.2 rc1 / versionCode 3）：4x2「今日概览」显示「今日支出 ¥50.44 / 今日收入 ¥0.00 / 结余 -¥385.28 / 环形 15% / 剩余 ¥2,114.72」（预算 2,500，结余 -385.28，剩余 = 2,500 + (−385.28)，与「明细」页 2026年10月 结余一致；旧算法会显示 1,952.22），说明「预算剩余按结余算」已生效；「个性化设置 → 桌面小组件 → 背景透明度」滑杆把不透明度从 95% 调到 15% 后，桌面小组件背景平均亮度 49.5 → 129.5、清晰透出壁纸，金色描边与文字保持清晰（截图 `_probe/after.png`），调回 95% 后恢复原观感。未能在本机新增 2x2 实例（ColorOS 桌面忽略 `requestPinAppWidget`，见上），故用「临时让 4x2 provider 渲染 2x2 布局」的方式确认：`img_budget_ring` 52dp +「剩余 ¥2,114.72」+「结余 -¥385.28」完整显示、无裁切（实测 4x2 实例尺寸 335dp × 158dp，2x2 同为两行高，2x2 内容高约 79dp）。

第九轮「ColorOS 17 液态玻璃改版」按用户明确要求（m02038「无需实机测试」）**未做实机验证**，本轮只做 Gradle 编译与打包：debug、release 均 BUILD SUCCESSFUL；尝试安装截图时本机未连接设备（`adb.exe: no devices/emulators found`），故开关回弹手感、子页面从按键缩放的中间帧、小组件高光层的实际观感均未在屏幕上确认，如实标注为未验证。改造涉及 `ui/theme/Glass.kt`（新增）、`ui/Common.kt`、`MainActivity.kt`、`widget/WidgetData.kt`、`widget/CatWidgetProvider.kt`、两个小组件布局与 `widget_gloss.xml`；功能逻辑（结余、预算剩余、数据读写）未改动。

第十轮 11 项整改已逐项真机确认（debug 包 `com.xzledger.app.debug` + release 包 `com.xzledger.app`，1.0.4 rc1 / versionCode 5）：明细页 2026年10月 显示 收入 904.40 / 支出 2,689.86 / **结余 714.54**（= 本月预算 2,500.00 + 收入 904.40 − 支出净 2,689.86），说明文案同步改为「结余 = 本月预算 ¥2,500.00 + 收入 - 支出（净）……与账户余额口径不同」；桑多涅台词由旧算法的「108%」（只看支出）变为 **71%**（(2,689.86 − 904.40) / 2,500 = 71.4%），并随收入抵消支出、最低按 0 计；我的页第一组为「周期管理」（预算设置 / 月度统计 / 周期记账与订阅提醒），「记账提醒」已移入下方「数据管理」；AI 聊天页 AI 气泡整段多行完整显示（不再 3 行截断），右上 `⋮` 弹出「AI 权限管理 / 清空聊天记录」两项；记账页收入 tab 不再出现「长期消费 / 即时消费」与「退款」胶囊，历史胶囊换成收入商户「闲鱼 / 京东 / 滴滴 红包 / 肯德基 红包」，支出 tab 则是「兰州大学 餐饮 / 平账 误差平账 / 淘宝 网购」；转账 tab 只留金额、日期、账户与商户备注；支出 tab 分类行首为「退款」→ 进入「选择退款账单」（搜索 + 仅支出列表 + 已退/剩余可退提示）→ 选「兰州大学 ¥14.00」后自动回到记账页并预填 金额 14.00 / 商户 兰州大学 / 分类 餐饮 / 即时消费，同时显示「退款对象：兰州大学（2026-10-31）」。该笔退款**未保存**，明细页与已有账单内容没有任何改动（本轮「不改动已记录账单」的要求）；历史胶囊「闲鱼」点选后显示「依据你的规则：AI 学习 · 闲鱼」（AI 自学习规则实际生效），再点一次同一胶囊即取消选择、商户被清空；我的页桑多涅面板右侧出现「？」与「长按 3 秒，让在下看一次本月账单。」；长按约 2.4 秒的中间帧抓到了沿整个面板轮廓生长的金色环形进度，松手即取消。本机已配置真实 AI 接口，为避免产生真实付费调用，未让长按走完 3 秒触发最终评价，该项如实列在「未在真机验证」里。

第十一轮 10 项整改已逐项真机确认（debug 包 `com.xzledger.app.debug`，1.0.5 rc1 / versionCode 6；release 包同版本编译通过）：桌面 4x2「今日概览」不再显示「载入窗口小部件时出现问题」，logcat 打出 `I XZWidget: selfCheck ok id=43`，卡片正常渲染「猫猫记账 11月1日 星期日 / 今日支出 ¥0.00 / 今日收入 ¥0.00 / 结余 ¥0.00 / 未设置预算」（根因是 RemoteViews 白名单不允许裸 `<View>`，见上节第 1 条）；预算「已用」按 支出 − 收入 生效——用临时数据（11 月支出 300.00、收入 100.00、月预算 500.00）实测预算页显示「已用 200.00 / 预算 500.00 40%」，明细页同步显示 收入 100.00 / 支出 300.00 / 结余 300.00（= 预算 500 + 100 − 300），桑多涅台词为「本月已经花掉 40% 的预算」；「每月自动沿用」开关打开后副标题变为「其它月份未单独设置时沿用 ¥500.00」，翻到 2026年12月 显示「沿用每月默认 / 已用 0.00 / 预算 500.00」、明细页 12 月结余 500.00（= 模板预算 + 0 − 0），证明模板对所有月份生效；我的页第一组已改名「数据统计」，顺序为 月度统计 → 预算设置 → 周期记账与订阅提醒，统计三格随月份更新为「2026年11月 支出 ¥300.00 / 本月笔数 2 / 本月结余 ¥300.00」；明细页点左上角桑多涅头像从 2026年12月 一键回到 2026年11月；月度统计页可左右滑动平滑切月（11月 → 10月，实测 10 月显示 收入 904.40 / 支出（毛额）2,929.86 / 退款 0.00 / 净支出 2,929.86 / 月度结余 −2,025.46 / 共 124 笔，分类分布 网购 1,581.77、餐饮 930.48、交通T 179.21），切月有 1/3 屏横向滑入 + 淡入过渡；我的页桑多涅面板右侧「？」已改为线条图标并居中；个性化设置新增「Apple 液态玻璃（WWDC 2025）」开关，与「液态玻璃效果（ColorOS 17）」同时打开后卡片圆角更大、阴影更柔、描边更亮（如实说明：Compose 无法对兄弟节点做真实高斯模糊，仍是分层模拟，暗色下差异属细微）。测试用的 11 月两笔账单与模板预算已在验收后从 debug 包数据库删除（验收后复核：11 月回到 0.00、预算回到「未设置」），release 包与真实账单内容未被改动。GitHub 仓库 `https://github.com/XiaozhuangXzsg/XZ-Ledger` 已发布，应用内「给项目 Star / 分享应用 / 配置 GitHub 地址 / 配置下载地址」默认指向该仓库与 `releases/latest`。

第十二轮（通知自动记账 + 应用内更新）已真机确认（debug 包 `com.xzledger.app.debug`，1.0.6 rc1 / versionCode 7）：「我的 → 智能记账 → 通知自动记账」页面正常渲染，用 `adb shell cmd notification allow_listener` 授权后显示「通知使用权：已授予 / 系统监听服务：已连接」；监听应用列表在修好包可见性后正确列出本机已安装的 中国农业银行（com.android.bankabc）/ 中国银行（com.chinamworld.bocmbci）/ 微信（com.tencent.mm）/ 招商银行（cmb.pb）/ 支付宝（com.eg.android.AlipayGphone），此前只剩 Claude / Google / Play 商店、搜索「Alipay」返回「没有匹配的应用」；勾选支付宝后点 debug 专属「注入测试通知」，待确认里出现「消费支出 置信度 80% / 星巴克 / 支付宝 / −35.80 / 2026-11-01 12:30 · 尾号 1234 · 账户 支付宝」，点「确认入账」提示「已入账」、待确认清零，明细页立即多出「星巴克 −35.80（支付宝）」，支出 18.00 → 53.80、结余 2,482.00 → 2,446.20（未重启）；账单详情显示 账单来源 notify、来源平台 com.eg.android.AlipayGphone、去重标识 `notify|f|com.eg.android.AlipayGphone|expense|3580|29891790|星巴克` 与记录编号；同一条通知再注入一次时待确认仍只有 1 笔、「自动记账记录」显示「重复已跳过 / 已在待确认列表，跳过」，去重生效；带日期的卡片原先显示「2026-11-01 11-01 12:30」，改用 `HH:mm` 后显示「2026-11-01 12:30」。测试数据已全部清理（账单删除 → 回收站「彻底删除」、待确认「全部忽略」→「没有待确认账单」），复核明细页回到 收入 0.00 / 支出 18.00 / 结余 2,482.00（三笔兰州大学），回收站只剩用户原有的 3 条。`gradle :app:testDebugUnitTest` 24 个解析 / 去重用例全通过。「我的 → 更多 → 检查更新」弹出「已是最新版本」并正确说明「本地数据不会被清除；更新只是覆盖安装同一个应用。」「最新 Release（v1.0.5-rc1）缺少 update.json，无法核对 versionCode 与 SHA-256，已跳过本次更新」；关于页显示「版本 1.0.6 rc1（versionCode 7）」，GitHub 地址与分享链接仍指向 `XiaozhuangXzsg/XZ-Ledger` 与 `releases/latest`。真实银行 / 支付通知、CI 发布流水线与下载安装链路如实列在上节「未在真机验证」。

未验证项见上节「已实现但未在真机验证」。
