package app.termora

import app.termora.actions.ActionManager
import app.termora.actions.DataProvider
import app.termora.actions.DataProviders
import app.termora.actions.OpenHostAction
import app.termora.database.DatabaseManager
import app.termora.plugin.ExtensionManager
import app.termora.plugin.PluginManager
import app.termora.protocol.ProtocolProvider
import app.termora.terminal.DataKey
import com.formdev.flatlaf.icons.FlatTreeClosedIcon
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatSystemProperties
import com.formdev.flatlaf.extras.FlatDesktop
import com.formdev.flatlaf.extras.FlatInspector
import com.formdev.flatlaf.ui.FlatTableCellBorder
import com.formdev.flatlaf.util.SystemInfo
import com.jthemedetecor.OsThemeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.LocaleUtils
import org.apache.commons.lang3.SystemUtils
import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.desktop.AppReopenedEvent
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.SystemEventListener
import java.awt.event.*
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.CountDownLatch
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.system.exitProcess

class ApplicationRunner {
    private val log by lazy { LoggerFactory.getLogger(ApplicationRunner::class.java) }

    fun run() {

        // 异步初始化
        val loadPluginThread = Thread.ofVirtual().start { PluginManager.getInstance() }

        // 打印系统信息
        printSystemInfo()

        // 打开数据库
        openDatabase()

        // 加载设置
        loadSettings()

        // 统计
        enableAnalytics()

        // 设置 LAF
        setupLaf()

        // clear temporary
        clearTemporary()

        // 等待插件加载完成
        loadPluginThread.join()

        // 准备就绪
        for (extension in ExtensionManager.getInstance().getExtensions(ApplicationRunnerExtension::class.java)) {
            extension.ready()
        }

        // 启动主窗口
        SwingUtilities.invokeLater { startMainFrame() }

    }

    private fun clearTemporary() {
        swingCoroutineScope.launch(Dispatchers.IO) {
            // 启动时清除
            FileUtils.cleanDirectory(Application.getTemporaryDir())
        }

    }

    private fun startMainFrame() {


        TermoraFrameManager.getInstance().createWindow().isVisible = true

        if (SystemInfo.isMacOS) {
            SwingUtilities.invokeLater {

                try {
                    // 设置 Dock
                    setupMacOSDock()
                } catch (e: Exception) {
                    if (log.isWarnEnabled) {
                        log.warn(e.message, e)
                    }
                }

                // Command + Q
                FlatDesktop.setQuitHandler { quitHandler() }
            }
        }

        // 设置托盘：macOS 在菜单栏右侧，Windows 在通知区域
        if (SystemInfo.isMacOS || SystemInfo.isWindows) {
            SwingUtilities.invokeLater {
                // 托盘不是核心功能，创建失败不应该影响启动
                runCatching { setupSystemTray() }
                    .onFailure { if (log.isWarnEnabled) log.warn(it.message, it) }
            }
        }

        // 初始化 Scheme
        OpenURIHandlers.getInstance()
    }

    private fun setupSystemTray() {
        if (SystemInfo.isLinux || !SystemTray.isSupported()) return

        val tray = SystemTray.getSystemTray()
        val trayIcon = TrayIcon(loadTrayImage(tray))
        val dialog = JDialog()
        val trayPopup = JPopupMenu()

        dialog.isUndecorated = true
        dialog.isModal = false
        dialog.size = Dimension(0, 0)

        trayIcon.isImageAutoSize = true
        trayIcon.toolTip = Application.getName()

        rebuildTrayMenu(trayPopup)
        trayPopup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                // 每次显示时根据最新的主机/文件夹重建菜单
                rebuildTrayMenu(trayPopup)
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {
                SwingUtilities.invokeLater {
                    if (dialog.isVisible) {
                        dialog.isVisible = false
                    }
                }
            }

            override fun popupMenuCanceled(e: PopupMenuEvent?) {
                popupMenuWillBecomeInvisible(e)
            }

        })

        val showPopup = {
            val mouseLocation = MouseInfo.getPointerInfo().location
            trayPopup.setLocation(mouseLocation.x, mouseLocation.y)
            trayPopup.setInvoker(dialog)
            dialog.isVisible = true
            trayPopup.isVisible = true
        }

        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // macOS 菜单栏图标的惯例是单击就弹出菜单，
                // 而左键单击时 isPopupTrigger 为 false，所以不能照搬 Windows 的判断，
                // 否则在 macOS 上点击图标会没有任何反应
                if (SystemInfo.isMacOS) showPopup.invoke() else maybeShowPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                // macOS 上按下时已经弹出，这里不再重复处理
                if (!SystemInfo.isMacOS) maybeShowPopup(e)
            }

            private fun maybeShowPopup(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup.invoke()
            }
        })

        dialog.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) {
                dialog.isVisible = false
            }
        })

        // double click
        trayIcon.addActionListener(object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                TermoraFrameManager.getInstance().tick()
            }
        })

        tray.add(trayIcon)

        Disposer.register(ApplicationScope.forApplicationScope(), object : Disposable {
            override fun dispose() {
                tray.remove(trayIcon)
            }
        })
    }

    /**
     * 按托盘实际尺寸挑选最接近的图标资源。
     *
     * macOS 菜单栏留给图标的高度只有 22pt 左右，直接丢一张 32x32 交给 isImageAutoSize 缩放会发虚，
     * 所以先选一张不小于目标尺寸的资源，再做一次高质量缩放（缩小比放大清晰）。
     */
    private fun loadTrayImage(tray: SystemTray): Image {
        val expected = minOf(tray.trayIconSize.width, tray.trayIconSize.height).coerceAtLeast(16)
        val sizes = intArrayOf(16, 20, 24, 28, 32, 44, 48, 64, 128, 256)
        val pick = sizes.firstOrNull { it >= expected } ?: sizes.last()
        val image = ImageIO.read(TermoraFrame::class.java.getResourceAsStream("/icons/termora_${pick}x${pick}.png"))

        if (pick == expected) return image

        val scaled = BufferedImage(expected, expected, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(image, 0, 0, expected, expected, null)
        g.dispose()
        return scaled
    }

    /**
     * 重建托盘菜单：顶层显示文件夹（子菜单展开主机），根级主机直接显示，最后是退出。
     * 文件夹可通过 extras["tray"]=="false" 关闭托盘显示（默认显示）。
     */
    private fun rebuildTrayMenu(popup: JPopupMenu) {
        popup.removeAll()

        runCatching {
            val all = HostManager.getInstance().hosts()
            val byParent = all.groupBy { it.parentId.ifBlank { "0" } }

            fun addChildren(add: (JMenuItem) -> Unit, parentId: String) {
                val children = byParent[parentId] ?: return
                for (child in children) {
                    if (child.id == "0") continue
                    if (child.isFolder) {
                        // 文件夹是否显示在托盘（默认显示）
                        if (child.options.extras["tray"] == "false") continue
                        val submenu = JMenu(child.name)
                        // 与主机树一致的文件夹图标
                        submenu.icon = FlatTreeClosedIcon()
                        addChildren({ submenu.add(it) }, child.id)
                        // 只显示非空文件夹
                        if (submenu.menuComponentCount > 0) add(submenu)
                    } else {
                        val item = JMenuItem(child.name)
                        // 与主机树一致的协议图标
                        item.icon = ProtocolProvider.valueOf(child.protocol)?.getIcon() ?: Icons.terminal
                        item.addActionListener { openHostFromTray(child) }
                        add(item)
                    }
                }
            }

            addChildren({ popup.add(it) }, "0")

            if (popup.componentCount > 0) popup.addSeparator()
        }.onFailure { if (log.isWarnEnabled) log.warn(it.message, it) }

        // macOS 上单击图标弹出的就是这个菜单，双击回调不会触发，
        // 而且开启后台运行后关闭窗口是直接销毁的，所以必须有一个显式的入口回到主界面
        popup.add(I18n.getString("termora.tray.show-main-window")).addActionListener {
            TermoraFrameManager.getInstance().tick()
        }

        popup.add(I18n.getString("termora.exit")).addActionListener { quitHandler() }
    }

    /**
     * 从托盘打开主机：确保窗口可见后，走标准的 OpenHostAction 打开。
     */
    private fun openHostFromTray(host: Host) {
        SwingUtilities.invokeLater {
            runCatching {
                // RDP 等通过外部程序（mstsc）打开，不需要显示主界面
                val external = host.protocol.equals("RDP", ignoreCase = true)

                val manager = TermoraFrameManager.getInstance()
                val frame = manager.getWindows().firstOrNull()
                    ?: manager.createWindow().apply { if (!external) isVisible = true }

                // 非外部协议才还原并前置主界面
                if (!external) {
                    if (frame.extendedState and Frame.ICONIFIED == Frame.ICONIFIED) {
                        frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                    }
                    frame.isVisible = true
                    frame.toFront()
                }

                val windowScope = ApplicationScope.forWindowScope(frame)
                val tabbedManager = windowScope.get(TerminalTabbedManager::class)

                // 托盘不在组件树中，用一个 DataProvider 作为事件源，提供所需数据
                val provider = object : DataProvider {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : Any> getData(dataKey: DataKey<T>): T? {
                        return when (dataKey) {
                            DataProviders.WindowScope -> windowScope as T
                            DataProviders.TerminalTabbedManager -> tabbedManager as T
                            DataProviders.TermoraFrame -> frame as T
                            else -> null
                        }
                    }
                }

                ActionManager.getInstance().getAction(OpenHostAction.OPEN_HOST)
                    ?.actionPerformed(OpenHostActionEvent(provider, host, EventObject(provider)))

                // 非外部协议：在左侧主机树选中对应主机
                if (!external) {
                    frame.getData(DataProviders.Welcome.HostTree)?.selectHostById(host.id)
                }
            }.onFailure { if (log.isWarnEnabled) log.warn(it.message, it) }
        }
    }

    private fun quitHandler() {
        val windows = TermoraFrameManager.getInstance().getWindows()

        for (frame in windows) {
            frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSED))
        }

        Disposer.dispose(TermoraFrameManager.getInstance())
    }

    private fun loadSettings() {
        val language = DatabaseManager.getInstance().appearance.language
        val locale = runCatching { LocaleUtils.toLocale(language) }.getOrElse { Locale.getDefault() }
        if (log.isInfoEnabled) {
            log.info("Language: {} , Locale: {}", language, locale)
        }
        Locale.setDefault(locale)
    }


    private fun setupLaf() {

        System.setProperty(FlatSystemProperties.USE_WINDOW_DECORATIONS, "${SystemInfo.isLinux || SystemInfo.isWindows}")

        if (SystemInfo.isLinux) {
            JFrame.setDefaultLookAndFeelDecorated(true)
            JDialog.setDefaultLookAndFeelDecorated(true)
        }

        val themeManager = ThemeManager.getInstance()
        val appearance = DatabaseManager.getInstance().appearance
        var theme = appearance.theme
        // 如果是跟随系统
        if (appearance.followSystem) {
            theme = if (OsThemeDetector.getDetector().isDark) {
                appearance.darkTheme
            } else {
                appearance.lightTheme
            }
        }

        // init native icon
        NativeIcons.folderIcon

        themeManager.change(theme, true)

        if (Application.isBetaVersion()) {
            FlatInspector.install("ctrl shift X")
        }

        UIManager.put(FlatClientProperties.FULL_WINDOW_CONTENT, true)
        UIManager.put(FlatClientProperties.USE_WINDOW_DECORATIONS, false)
        UIManager.put(FlatClientProperties.POPUP_FORCE_HEAVY_WEIGHT, true)

        UIManager.put("Component.arc", 5)
        UIManager.put("TextComponent.arc", UIManager.getInt("Component.arc"))
        UIManager.put("Component.hideMnemonics", true)

        UIManager.put("TitleBar.height", 36)

        UIManager.put("Dialog.width", 650)
        UIManager.put("Dialog.height", 550)

        if (SystemInfo.isMacOS) {
            UIManager.put("TabbedPane.tabHeight", UIManager.getInt("TitleBar.height"))
        } else if (SystemInfo.isLinux) {
            UIManager.put("TabbedPane.tabHeight", UIManager.getInt("TitleBar.height") - 4)
        } else {
            UIManager.put("TabbedPane.tabHeight", UIManager.getInt("TitleBar.height") - 6)
        }

        if (SystemInfo.isLinux || SystemInfo.isWindows) {
            UIManager.put("TitlePane.centerTitle", true)
            UIManager.put("TitlePane.showIcon", false)
            UIManager.put("TitlePane.showIconInDialogs", false)
        }

        UIManager.put("Table.rowHeight", 24)
        UIManager.put("Table.focusCellHighlightBorder", FlatTableCellBorder.Default())
        UIManager.put("Table.focusSelectedCellHighlightBorder", FlatTableCellBorder.Default())

        UIManager.put("Tree.rowHeight", 24)
        UIManager.put("Tree.background", DynamicColor("window"))
        UIManager.put("Tree.showCellFocusIndicator", false)
        UIManager.put("Tree.repaintWholeRow", true)

        // Linux 更多的是尖锐风格
        if (SystemInfo.isMacOS || SystemInfo.isWindows) {
            val selectionInsets = Insets(0, 2, 0, 2)
            UIManager.put("Tree.selectionArc", UIManager.getInt("Component.arc"))
            UIManager.put("Tree.selectionInsets", selectionInsets)

            UIManager.put("List.selectionArc", UIManager.getInt("Component.arc"))
            UIManager.put("List.selectionInsets", selectionInsets)

            UIManager.put("ComboBox.selectionArc", UIManager.getInt("Component.arc"))
            UIManager.put("ComboBox.selectionInsets", selectionInsets)

            UIManager.put("Table.selectionArc", UIManager.getInt("Component.arc"))
            UIManager.put("Table.selectionInsets", selectionInsets)

            UIManager.put("MenuBar.selectionArc", UIManager.getInt("Component.arc"))
            UIManager.put("MenuBar.selectionInsets", selectionInsets)

            UIManager.put("MenuItem.selectionArc", UIManager.getInt("Component.arc"))
            UIManager.put("MenuItem.selectionInsets", selectionInsets)
        }

    }

    private fun setupMacOSDock() {
        val countDownLatch = CountDownLatch(1)
        val cls = Class.forName("com.apple.eawt.Application")
        val app = cls.getMethod("getApplication").invoke(null)
        val addAppEventListener = cls.getMethod("addAppEventListener", SystemEventListener::class.java)

        addAppEventListener.invoke(app, object : AppReopenedListener {
            override fun appReopened(e: AppReopenedEvent) {
                val manager = TermoraFrameManager.getInstance()
                if (manager.getWindows().isEmpty()) {
                    manager.createWindow().isVisible = true
                }
            }
        })

        // 当应用程序销毁时，驻守线程也可以退出了
        Disposer.register(ApplicationScope.forApplicationScope(), object : Disposable {
            override fun dispose() {
                countDownLatch.countDown()
            }
        })

        // 驻守线程，不然当所有窗口都关闭时，程序会自动退出
        // wait application exit
        Thread.ofPlatform().daemon(false)
            .priority(Thread.MIN_PRIORITY)
            .start { countDownLatch.await() }
    }

    private fun printSystemInfo() {
        if (log.isDebugEnabled) {
            log.debug("Welcome to ${Application.getName()} ${Application.getVersion()}!")
            log.debug(
                "JVM name: {} , vendor: {} , version: {}",
                SystemUtils.JAVA_VM_NAME,
                SystemUtils.JAVA_VM_VENDOR,
                SystemUtils.JAVA_VM_VERSION,
            )
            log.debug(
                "OS name: {} , version: {} , arch: {}",
                SystemUtils.OS_NAME,
                SystemUtils.OS_VERSION,
                SystemUtils.OS_ARCH
            )
            log.debug("Base config dir: ${Application.getBaseDataDir().absolutePath}")
        }
    }


    private fun openDatabase() {
        try {
            // 初始化数据库
            DatabaseManager.getInstance()
        } catch (e: Exception) {
            if (log.isErrorEnabled) {
                log.error(e.message, e)
            }
            JOptionPane.showMessageDialog(
                null, "Unable to open database",
                I18n.getString("termora.title"), JOptionPane.ERROR_MESSAGE
            )
            exitProcess(1)
        }
    }

    /**
     * 已本地化：移除启动统计上报。
     */
    private fun enableAnalytics() {
        // no-op
    }


}