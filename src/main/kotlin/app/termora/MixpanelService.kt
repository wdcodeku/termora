package app.termora

import org.slf4j.LoggerFactory

/**
 * 已本地化：移除所有数据统计 / 遥测（原 mixpanel 上报）。
 * 保留空实现以兼容现有调用点（例如插件面板），但不再进行任何网络上报。
 */
internal class MixpanelService private constructor() {
    companion object {
        private val log = LoggerFactory.getLogger(MixpanelService::class.java)

        fun getInstance(): MixpanelService {
            return ApplicationScope.forApplicationScope().getOrCreate(MixpanelService::class) { MixpanelService() }
        }
    }

    /**
     * 空操作：不再向任何服务器上报统计数据。
     */
    @Suppress("UNUSED_PARAMETER")
    fun push(event: String, extras: Map<String, String> = emptyMap()) {
        // no-op
    }

}
