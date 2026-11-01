package com.xzledger.app.update

/**
 * 应用内更新用到的仓库配置集中在这里：换仓库、换分支、换资产名时只改这一处。
 * 客户端不内置任何 GitHub Token，也不包含签名私钥。
 */
object UpdateConfig {
    const val OWNER = "XiaozhuangXzsg"
    const val REPO = "XZ-Ledger"

    /** 只读取正式 Release（草稿与预发布一律忽略）。 */
    const val RELEASES_API = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=20"

    /** 发布流程生成并随 Release 上传的元数据文件名。 */
    const val METADATA_ASSET = "update.json"

    /** 元数据缺失时用于提示用户的说明页。 */
    const val RELEASES_PAGE = "https://github.com/" + OWNER + "/" + REPO + "/releases"

    const val USER_AGENT = "XZ-Ledger-Android"

    /** 自动检查的间隔：一天最多一次。 */
    const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    /** 点「稍后再说」后的静默时长。 */
    const val SNOOZE_INTERVAL_MS = 6L * 60L * 60L * 1000L
}
