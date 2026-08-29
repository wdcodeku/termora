package app.termora.plugin.internal.rdp

import app.termora.*
import app.termora.actions.DataProvider
import app.termora.database.DatabaseManager
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
import org.apache.commons.text.StringEscapeUtils
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

internal class RDPProtocolProvider private constructor() : GenericProtocolProvider {
    companion object {
        private val log = LoggerFactory.getLogger(RDPProtocolProvider::class.java)
        val instance by lazy { RDPProtocolProvider() }
        const val PROTOCOL = "RDP"

        /**
         * 是否已经提示过「密码已复制到剪贴板」
         */
        private const val PROP_CLIPBOARD_TIPPED = "RDP.clipboard-password-tipped"
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
        // macOS / Linux 优先使用 FreeRDP：它支持非交互式登录，可以直接使用已保存的密码，
        // 不需要每次手动输入。
        // Windows 下 mstsc 通过 DPAPI 加密的 password 51 字段已经可以免密，所以继续用 mstsc。
        if (!SystemInfo.isWindows) {
            val freerdp = findFreeRDP()
            if (log.isInfoEnabled) {
                log.info("openRDP: FreeRDP client = {}", freerdp?.absolutePath ?: "<not found>")
            }
            if (freerdp != null) {
                if (openWithFreeRDP(windowScope, host, freerdp)) return
                // 启动失败，继续尝试系统自带的客户端
            } else if (SystemInfo.isLinux) {
                OptionPane.showMessageDialog(
                    windowScope.window,
                    "FreeRDP is required to connect to a Windows Remote Server on Linux.<br/><br/>"
                            + "Install it first, for example: <b>sudo apt install freerdp3-x11</b>",
                    messageType = JOptionPane.WARNING_MESSAGE
                )
                return
            }
        }

        if (SystemInfo.isMacOS) {
            if (!FileUtils.getFile("/Applications/Windows App.app").exists()) {
                val option = OptionPane.showConfirmDialog(
                    windowScope.window,
                    "If you want to connect to a Windows Remote Server, You have to install the Windows App.<br/><br/>"
                            + "The Windows App cannot read the password from a .rdp file, so it asks for the "
                            + "password on every connection. Install FreeRDP instead to sign in automatically "
                            + "with the saved password: <b>brew install freerdp</b>",
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

        // 密码是否退化成了剪贴板方式，启动客户端之后需要提示用户
        var passwordInClipboard = false

        if (host.authentication.type == AuthenticationType.Password) {
            val password = host.authentication.password
            var ep = StringUtils.EMPTY

            if (SystemInfo.isWindows) {
                // PowerShell 单引号字符串里的单引号必须写两遍来转义，
                // 否则密码只要包含 ' 就会让命令执行失败，从而退化成剪贴板方式
                val escaped = password.replace("'", "''")
                val cmd = "ConvertTo-SecureString '${escaped}' -AsPlainText -Force | ConvertFrom-SecureString"
                val process = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd).start()
                if (process.waitFor() == 0) {
                    ep = String(process.inputStream.readAllBytes())
                }
            }

            if (ep.isNotBlank()) {
                sb.append("password 51:b:").append(ep).appendLine()
            }

            // 如果获取加密密码失败，那么依然要走剪切板
            // macOS 的 Windows App 不会读取 .rdp 里的密码，所以这里必然走剪贴板
            if (ep.isBlank()) {
                val systemClipboard = windowScope.window.toolkit.systemClipboard
                systemClipboard.setContents(StringSelection(password), null)
                passwordInClipboard = true
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
            if (passwordInClipboard) tipClipboardPassword(windowScope)
        } else if (SystemInfo.isWindows) {
            val process = ProcessBuilder("mstsc", file.absolutePath).start()
            // mstsc 以 .rdp 文件名作为标题，且会在第一个 "." 处截断，
            // 因此连接后用配置名称覆盖标题前缀（保留 mstsc 自带的 " - 地址 - 远程桌面连接" 后缀）
            renameRemoteDesktopWindow(process.pid(), host.name.trim(), host.host.trim())
        }

    }

    /**
     * 查找可用的 FreeRDP 客户端。
     *
     * 优先 SDL 客户端：macOS 下 sdl-freerdp 无需额外安装 XQuartz，而 xfreerdp 依赖 X11。
     *
     * 注意：从 Dock / Finder 启动的 GUI 程序继承到的 PATH 通常只有 /usr/bin:/bin:/usr/sbin:/sbin，
     * 不包含 Homebrew 目录，所以这里显式补上常见的安装位置。
     */
    private fun findFreeRDP(): File? {
        if (SystemInfo.isWindows) return null

        val dirs = mutableListOf(
            "/opt/homebrew/bin", // Homebrew on Apple Silicon
            "/usr/local/bin",    // Homebrew on Intel / 手动安装
            "/usr/bin",
            "/bin",
            "/snap/bin",         // Linux snap
        )
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.let { dirs.addAll(it) }

        for (name in listOf("sdl-freerdp3", "sdl-freerdp", "xfreerdp3", "xfreerdp")) {
            for (dir in dirs) {
                if (dir.isBlank()) continue
                val file = File(dir, name)
                if (file.isFile && file.canExecute()) return file
            }
        }

        return null
    }

    /**
     * 使用 FreeRDP 连接，密码通过标准输入传递，实现免密登录。
     *
     * 不使用 /p:password 是因为命令行参数对同机器上的其他进程可见（ps 就能看到）。
     *
     * @return 是否成功启动，false 表示调用方需要回退到系统自带的客户端
     */
    private fun openWithFreeRDP(windowScope: WindowScope, host: Host, freerdp: File): Boolean {
        // FreeRDP 的实际参数，每个占一行，稍后通过 /args-from:stdin 从标准输入喂给它。
        // 这样密码不会出现在进程列表（ps）里，而且 /args-from 在解析阶段就读取，
        // 比 /from-stdin（要等服务器请求凭据时才读，管道下不一定触发）可靠。
        val args = mutableListOf<String>()

        // IPv6 地址需要用方括号包裹，才能和端口区分开
        var address = host.host.trim()
        if (address.contains(':') && !address.startsWith("[")) {
            address = "[$address]"
        }
        args.add("/v:$address:${host.port}")

        if (host.username.isNotBlank()) {
            args.add("/u:${host.username}")
        }

        val password = if (host.authentication.type == AuthenticationType.Password) {
            host.authentication.password
        } else {
            StringUtils.EMPTY
        }
        if (password.isNotEmpty()) {
            args.add("/p:${password}")
        } else {
            // 没有密码时用 /p 抑制交互式提示（例如服务器不需要凭据）
            args.add("/p")
        }

        // 忽略证书校验，等价于 .rdp 的 authentication level:i:0，
        // 否则自签名证书会导致连接中断并等待确认
        args.add("/cert:ignore")
        // 剪贴板重定向
        args.add("+clipboard")
        // 断线自动重连
        args.add("/auto-reconnect")

        val fullscreen = host.options.extras["fullscreen"]?.toBooleanStrictOrNull() ?: true
        val resolution = host.options.extras["desktop"]?.lowercase()?.split("x")?.map { it.trim() } ?: emptyList()
        if (resolution.size == 2 && resolution.all { it.toIntOrNull() != null }) {
            args.add("/size:${resolution.first()}x${resolution.last()}")
        } else {
            // 未指定分辨率时跟随窗口动态调整
            args.add("/dynamic-resolution")
        }
        if (fullscreen) {
            args.add("/f")
        }

        if (log.isInfoEnabled) {
            // 不打印密码
            val safe = args.map { if (it.startsWith("/p:")) "/p:***" else it }
            log.info("Launching {} /args-from:stdin with args: {}", freerdp.name, safe.joinToString(" "))
        }

        return runCatching {
            // 命令行上只有 /args-from:stdin，真正的参数（含密码）从 stdin 逐行传入。
            // 文档要求 /args-from 不能与其它参数组合。
            val process = ProcessBuilder(freerdp.absolutePath, "/args-from:stdin")
                .redirectErrorStream(true)
                .start()
            process.outputStream.use { out ->
                out.write(args.joinToString("\n").toByteArray(Charsets.UTF_8))
                out.write("\n".toByteArray(Charsets.UTF_8))
            }
            watchFreeRDP(windowScope, process, freerdp.name)
            true
        }.onFailure {
            if (log.isWarnEnabled) {
                log.warn("Failed to start ${freerdp.absolutePath}: ${it.message}", it)
            }
        }.getOrDefault(false)
    }

    /**
     * 持续读取 FreeRDP 的输出（避免管道写满导致其阻塞），退出码非 0 时把最后几行提示给用户，
     * 否则连接失败时界面上不会有任何反馈。
     */
    private fun watchFreeRDP(windowScope: WindowScope, process: Process, name: String) {
        swingCoroutineScope.launch(Dispatchers.IO) {
            val tail = ArrayDeque<String>()
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        tail.addLast(line)
                        // 只保留末尾若干行，避免长时间连接累积占用内存
                        if (tail.size > 15) tail.removeFirst()
                    }
                }
            }

            val exitCode = runCatching { process.waitFor() }.getOrDefault(0)
            if (exitCode == 0) return@launch

            if (log.isWarnEnabled) {
                log.warn("{} exited with code {}: {}", name, exitCode, tail.joinToString(" | "))
            }

            SwingUtilities.invokeLater {
                OptionPane.showMessageDialog(
                    windowScope.window,
                    "$name exited with code $exitCode<br/><br/>"
                            + tail.joinToString("<br/>") { StringEscapeUtils.escapeHtml4(it) },
                    messageType = JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    /**
     * macOS 的 Windows App 不支持从 .rdp 文件读取密码，只能把密码放到剪贴板让用户粘贴。
     * 这个提示只显示一次，避免每次连接都打扰用户。
     */
    private fun tipClipboardPassword(windowScope: WindowScope) {
        val properties = DatabaseManager.getInstance().properties
        if (properties.getString(PROP_CLIPBOARD_TIPPED).toBoolean()) return
        properties.putString(PROP_CLIPBOARD_TIPPED, true.toString())

        OptionPane.showMessageDialog(
            windowScope.window,
            "The Windows App cannot read the password from a .rdp file, "
                    + "so the saved password has been copied to the clipboard for 30 seconds. "
                    + "Press <b>Cmd + V</b> in the password field.<br/><br/>"
                    + "To sign in automatically without typing the password, install FreeRDP "
                    + "and Termora will use it next time: <b>brew install freerdp</b>",
            messageType = JOptionPane.INFORMATION_MESSAGE
        )
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