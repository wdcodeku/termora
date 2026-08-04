package app.termora.plugin.internal.rdp

import app.termora.*
import app.termora.actions.DataProvider
import app.termora.protocol.GenericProtocolProvider
import com.formdev.flatlaf.util.SystemInfo
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.Strings
import org.apache.commons.lang3.SystemUtils
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.JOptionPane
import kotlin.time.Duration.Companion.seconds

internal class RDPProtocolProvider private constructor() : GenericProtocolProvider {
    companion object {
        val instance by lazy { RDPProtocolProvider() }
        const val PROTOCOL = "RDP"
    }

    override fun getProtocol(): String {
        return PROTOCOL
    }

    override fun createTerminalTab(dataProvider: DataProvider, windowScope: WindowScope, host: Host): TerminalTab {
        TODO()
    }

    override fun getIcon(width: Int, height: Int): DynamicIcon {
        return Icons.microsoftWindows
    }

    override fun canCreateTerminalTab(dataProvider: DataProvider, windowScope: WindowScope, host: Host): Boolean {
        openRDP(windowScope, host)
        return false
    }

    private fun openRDP(windowScope: WindowScope, host: Host) {
        if (SystemInfo.isLinux) {
            OptionPane.showMessageDialog(
                windowScope.window,
                "Linux cannot connect to Windows Remote Server, Supported only for macOS and Windows",
                messageType = JOptionPane.WARNING_MESSAGE
            )
            return
        }

        if (SystemInfo.isMacOS) {
            if (!FileUtils.getFile("/Applications/Windows App.app").exists()) {
                val option = OptionPane.showConfirmDialog(
                    windowScope.window,
                    "If you want to connect to a Windows Remote Server, You have to install the Windows App",
                    optionType = JOptionPane.OK_CANCEL_OPTION
                )
                if (option == JOptionPane.OK_OPTION) {
                    Application.browse(URI.create("https://apps.apple.com/app/windows-app/id1295203466"))
                }
                return
            }
        }

        val sb = StringBuilder()
        sb.append("full address:s:")
        if (SystemUtils.IS_OS_WINDOWS && Strings.CI.contains(host.host, ":")) {
            var newHost = Strings.CI.removeStart(host.host, "[")
            newHost = Strings.CI.removeEnd(newHost, "]")
            sb.append('[').append(newHost).append(']')
        } else {
            sb.append(host.host)
        }
        sb.append(':').append(host.port).appendLine()
        sb.append("username:s:").append(host.username).appendLine()

        // 完善远程桌面连接配置
        // authentication level:i:0 表示即使无法验证服务器身份也直接连接，
        // 不再每次连接都弹出“无法验证此远程计算机的身份/证书不受信任”的提示
        sb.append("authentication level:i:0").appendLine()
        sb.append("enablecredsspsupport:i:1").appendLine()
        sb.append("prompt for credentials:i:0").appendLine()
        sb.append("negotiate security layer:i:1").appendLine()
        // 断线自动重连
        sb.append("autoreconnection enabled:i:1").appendLine()
        // 剪贴板重定向
        sb.append("redirectclipboard:i:1").appendLine()
        // 压缩与缓存，提升体验
        sb.append("compression:i:1").appendLine()
        sb.append("bitmapcachepersistenable:i:1").appendLine()

        // 显示模式：2=全屏，1=窗口（默认全屏）
        val fullscreen = host.options.extras["fullscreen"]?.toBooleanStrictOrNull() ?: true
        sb.append("screen mode id:i:").append(if (fullscreen) 2 else 1).appendLine()
        sb.append("use multimon:i:0").appendLine()

        // 分辨率：指定 WxH 则使用固定分辨率，否则跟随窗口/屏幕动态调整
        val desktop = host.options.extras["desktop"]?.lowercase()
        val resolution = desktop?.split("x")?.map { it.trim() } ?: emptyList()
        val hasResolution = resolution.size == 2 && resolution.all { it.toIntOrNull() != null }
        if (hasResolution) {
            val width = resolution.first()
            val height = resolution.last()
            sb.append("desktopwidth:i:").append(width).appendLine()
            sb.append("desktopheight:i:").append(height).appendLine()
            // 固定分辨率时关闭动态分辨率，否则分辨率可能不生效
            sb.append("dynamic resolution:i:0").appendLine()
            if (fullscreen) {
                sb.append("smart sizing:i:1").appendLine()
            } else {
                // 窗口模式：普通窗口（非最大化），客户区按分辨率 1:1 显示（不缩放）
                // winposstr 的矩形是窗口“外框”，需要在分辨率基础上加上标题栏与边框，
                // 否则客户区会小于分辨率，导致出现滚动条 / 显示不完整
                if (SystemInfo.isWindows) {
                    // winposstr:s:0,<showCmd>,left,top,right,bottom  showCmd=1 普通窗口，3 最大化
                    sb.append(buildWindowedWinPosStr(width.toInt(), height.toInt())).appendLine()
                }
                sb.append("smart sizing:i:0").appendLine()
            }
        } else {
            // 默认动态分辨率，自动适配全屏或窗口大小
            sb.append("dynamic resolution:i:1").appendLine()
            // 窗口模式下默认以普通窗口（非最大化）打开
            if (!fullscreen && SystemInfo.isWindows) {
                sb.append(buildWindowedWinPosStr(1280, 800)).appendLine()
            }
        }

        if (host.authentication.type == AuthenticationType.Password) {
            val password = host.authentication.password
            var ep = StringUtils.EMPTY

            if (SystemInfo.isWindows) {
                val cmd = "ConvertTo-SecureString '${password}' -AsPlainText -Force | ConvertFrom-SecureString"
                val process = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd).start()
                if (process.waitFor() == 0) {
                    ep = String(process.inputStream.readAllBytes())
                }
            }

            if (ep.isNotBlank()) {
                sb.append("password 51:b:").append(ep).appendLine()
            }

            // 如果获取加密密码失败，那么依然要走剪切板
            if (ep.isBlank() || SystemInfo.isMacOS) {
                val systemClipboard = windowScope.window.toolkit.systemClipboard
                systemClipboard.setContents(StringSelection(password), null)
                // clear password
                swingCoroutineScope.launch(Dispatchers.IO) {
                    delay(30.seconds)
                    if (systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                        if (systemClipboard.getData(DataFlavor.stringFlavor) == password) {
                            systemClipboard.setContents(StringSelection(StringUtils.EMPTY), null)
                        }
                    }
                }
            }
        }


        // 使用配置中的名称作为 .rdp 文件名，mstsc 会以文件名作为窗口标题
        val baseName = sanitizeFileName(host.name).ifBlank {
            sanitizeFileName(host.host).ifBlank { "Remote" }
        }
        // 放入独立子目录，避免同名 .rdp 文件相互覆盖
        val dir = FileUtils.getFile(Application.getTemporaryDir(), randomUUID())
        FileUtils.forceMkdir(dir)
        val file = FileUtils.getFile(dir, "$baseName.rdp")
        file.outputStream().use { IOUtils.write(sb.toString(), it, Charsets.UTF_8) }

        if (SystemInfo.isMacOS) {
            ProcessBuilder("open", file.absolutePath).start()
        } else if (SystemInfo.isWindows) {
            val process = ProcessBuilder("mstsc", file.absolutePath).start()
            // mstsc 以 .rdp 文件名作为标题，且会在第一个 "." 处截断，
            // 因此连接后用配置名称覆盖标题前缀（保留 mstsc 自带的 " - 地址 - 远程桌面连接" 后缀）
            renameRemoteDesktopWindow(process.pid(), host.name.trim(), host.host.trim())
        }

    }

    /**
     * 移除 Windows 文件名非法字符，用于以配置名称作为 .rdp 文件名（连接后窗口标题会再被覆盖为完整名称）。
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(120)
    }

    /**
     * 连接后优化 mstsc 窗口标题：
     * - 名称与主机相同（同一个 IP）或名称为空时，直接显示主机，避免重复；
     * - 否则用配置名称作为前缀，保留 mstsc 自带的 " - 地址 - 远程桌面连接" 后缀。
     * 从标题右侧解析地址与后缀，保证多次覆盖时结果稳定（幂等）。
     * 全程使用 runCatching 包裹，任何失败都不会影响 RDP 连接本身。
     */
    private fun renameRemoteDesktopWindow(pid: Long, name: String, address: String) {
        // 名称与主机一致或为空时视为冗余，标题只显示主机
        val redundant = name.isBlank() || name.equals(address, ignoreCase = true)
        swingCoroutineScope.launch(Dispatchers.IO) {
            runCatching {
                // mstsc 启动并建立连接需要一点时间，期间它会多次设置自己的标题，
                // 因此在一段时间内多次覆盖，确保最终标题正确。
                repeat(20) {
                    delay(700)
                    val hwnd = findMainWindowByPid(pid) ?: return@repeat
                    val current = getWindowTitle(hwnd)
                    if (current.isBlank()) return@repeat

                    // mstsc 标题格式：<前缀> - <地址:端口> - <远程桌面连接>
                    val lastSep = current.lastIndexOf(" - ")
                    if (lastSep < 0) return@repeat
                    val suffix = current.substring(lastSep + 3)
                    val left = current.substring(0, lastSep)
                    val midSep = left.lastIndexOf(" - ")
                    val addr = if (midSep >= 0) left.substring(midSep + 3) else left

                    val expected = if (redundant) "$addr - $suffix" else "$name - $addr - $suffix"
                    if (current != expected) {
                        User32Ext.INSTANCE.SetWindowTextW(hwnd, WString(expected))
                    }
                }
            }
        }
    }

    private fun findMainWindowByPid(pid: Long): HWND? {
        var result: HWND? = null
        User32.INSTANCE.EnumWindows(WinUser.WNDENUMPROC { hWnd, _ ->
            val pidRef = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef)
            if (pidRef.value.toLong() == pid && User32.INSTANCE.IsWindowVisible(hWnd)) {
                if (getWindowTitle(hWnd).isNotBlank()) {
                    result = hWnd
                    return@WNDENUMPROC false // 找到后停止枚举
                }
            }
            true
        }, null)
        return result
    }

    private fun getWindowTitle(hWnd: HWND): String {
        val buffer = CharArray(1024)
        val len = User32.INSTANCE.GetWindowText(hWnd, buffer, buffer.size)
        return if (len > 0) String(buffer, 0, len) else ""
    }

    /**
     * jna-platform 的 User32 未声明 SetWindowText，这里补充 SetWindowTextW。
     */
    private interface User32Ext : StdCallLibrary {
        fun SetWindowTextW(hWnd: HWND, lpString: WString): Boolean

        companion object {
            val INSTANCE: User32Ext by lazy { Native.load("user32", User32Ext::class.java) }
        }
    }

    /**
     * 计算窗口模式下的 winposstr，使窗口客户区按分辨率 1:1 显示，并在主屏幕居中。
     */
    private fun buildWindowedWinPosStr(clientWidth: Int, clientHeight: Int): String {
        var scale = 1.0
        var screenW = 0
        var screenH = 0
        runCatching {
            val gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration
            scale = gc.defaultTransform.scaleX
            // 物理像素 = 逻辑像素 * DPI 缩放
            screenW = Math.round(gc.bounds.width * scale).toInt()
            screenH = Math.round(gc.bounds.height * scale).toInt()
        }

        // 标题栏 + 边框（100% DPI 约 16x39），按 DPI 缩放
        val chromeW = Math.round(16 * scale).toInt()
        val chromeH = Math.round(39 * scale).toInt()
        val outerW = clientWidth + chromeW
        val outerH = clientHeight + chromeH

        // 在主屏幕居中
        val left = if (screenW > outerW) (screenW - outerW) / 2 else 0
        val top = if (screenH > outerH) (screenH - outerH) / 2 else 0

        return "winposstr:s:0,1,$left,$top,${left + outerW},${top + outerH}"
    }
}