package app.termora.account

import app.termora.ApplicationRunnerExtension
import app.termora.SettingsOptionExtension
import app.termora.database.DatabaseChangedExtension
import app.termora.plugin.Extension
import app.termora.plugin.InternalPlugin

internal class AccountPlugin : InternalPlugin() {
    init {
        // 已本地化：移除账号登录、云同步以及与服务器的任何交互，
        // 仅保留本地账户（owner）模型，使所有数据始终归本地用户所有。
        support.addExtension(ApplicationRunnerExtension::class.java) { AccountManager.getInstance() }
    }

    override fun getName(): String {
        return "Account"
    }


    override fun <T : Extension> getExtensions(clazz: Class<T>): List<T> {
        return support.getExtensions(clazz)
    }
}