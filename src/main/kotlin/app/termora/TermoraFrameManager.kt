package app.termora

import app.termora.database.DatabaseManager
import app.termora.plugin.ExtensionManager
import com.formdev.flatlaf.ui.FlatNativeWindowsLibrary
import com.formdev.flatlaf.util.SystemInfo
import com.sun.jna.Pointer
import com.sun.jna.platform.WindowUtils
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser.*
import de.jangassen.jfa.ThreadUtils
import de.jangassen.jfa.foundation.Foundation
import de.jangassen.jfa.foundation.ID
import org.slf4j.LoggerFactory
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
import kotlin.math.max
import kotlin.system.exitProcess


class TermoraFrameManager : Disposable {

    companion object {
        private val log = LoggerFactory.getLogger(TermoraFrameManager::class.java)

        /**
         * 位置信息延迟落库的时间，避免拖动或缩放窗口时频繁写数据库
         */
        private const val SAVE_RECTANGLE_DELAY = 800

        fun getInstance(): TermoraFrameManager {
            return ApplicationScope.forApplicationScope()
                .getOrCreate(TermoraFrameManager::class) { TermoraFrameManager() }
        }
    }

    private val frames = mutableListOf<TermoraFrame>()
    private val properties get() = DatabaseManager.getInstance().properties
    private val isDisposed = AtomicBoolean(false)
    private val isBackgroundRunning get() = DatabaseManager.getInstance().appearance.backgroundRunning
    private val frameExtensions get() = ExtensionManager.getInstance().getExtensions(FrameExtension::class.java)

    /**
     * 窗口在「正常状态」（非最大化、非最小化）下的边界。
     * 最大化或最小化时不会被覆盖，这样还原窗口后可以回到最大化之前的位置和大小。
     */
    private val normalBounds = mutableMapOf<TermoraFrame, Rectangle>()

    /**
     * 等待落库的位置信息，为空表示没有变动
     */
    @Volatile
    private var pendingRectangle: FrameRectangle? = null

    /**
     * 最后一次落库的位置信息，用于跳过没有变化的写入
     */
    @Volatile
    private var lastSavedRectangle: FrameRectangle? = null

    private val saveRectangleTimer by lazy {
        Timer(SAVE_RECTANGLE_DELAY) { flushFrameRectangle() }.apply { isRepeats = false }
    }

    init {
        // 兜底：进程被强制结束（比如系统关机）时也尽量把位置信息保存下来
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread { flushFrameRectangle() })
        }.onFailure { if (log.isWarnEnabled) log.warn(it.message, it) }
    }

    fun createWindow(): TermoraFrame {
        val frame = TermoraFrame().apply { registerCloseCallback(this) }
        frame.title = Application.getName()
        frame.defaultCloseOperation = DO_NOTHING_ON_CLOSE

        val rectangle = getFrameRectangle()?.also { lastSavedRectangle = it }
            ?: FrameRectangle(-1, -1, 1280, 800, 0)

        // 先恢复「正常状态」下的大小和位置，这样即使是最大化启动，还原窗口后也能回到之前的大小
        // 控制最小
        frame.setSize(
            max(rectangle.w, UIManager.getInt("Dialog.width") - 150),
            max(rectangle.h, UIManager.getInt("Dialog.height") - 100)
        )
        if (rectangle.x == -1 && rectangle.y == -1) {
            frame.setLocationRelativeTo(null)
        } else {
            frame.setLocation(max(rectangle.x, 0), max(rectangle.y, 0))
            // 显示器可能已经拔掉了，如果窗口完全不在任何屏幕内，那么居中显示
            if (isOutOfScreen(frame.bounds)) {
                frame.setLocationRelativeTo(null)
            }
        }

        // 记录初始的正常边界，避免最大化启动时把最大化的边界当成正常边界保存
        normalBounds[frame] = Rectangle(frame.bounds)

        // 再恢复最大化状态
        if (rectangle.isMaximized) {
            frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
        }

        // 位置或大小变动时记录下来，延迟落库
        registerRectangleTracker(frame)

        frame.addNotifyListener(object : NotifyListener {
            private val opacity get() = DatabaseManager.getInstance().appearance.opacity
            override fun addNotify() {
                val opacity = this.opacity
                if (opacity >= 1.0) return
                setOpacity(frame, opacity)
            }
        })

        for (extension in frameExtensions) {
            extension.customize(frame)
        }

        return frame.apply { frames.add(this) }
    }

    fun getWindows(): Array<TermoraFrame> {
        return frames.toTypedArray()
    }


    private fun registerCloseCallback(window: TermoraFrame) {
        val manager = this
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {

                // 销毁子窗口
                TermoraRestarter.getInstance().disposeChildren(window)

                // 存储位置信息
                saveFrameRectangle(window)

                // 删除
                frames.remove(window)
                normalBounds.remove(window)

                // dispose windowScope
                val windowScope = ApplicationScope.forWindowScope(e.window)
                Disposer.disposeChildren(windowScope, null)
                Disposer.dispose(windowScope)

                val windowScopes = ApplicationScope.windowScopes()
                if (windowScopes.isNotEmpty()) {
                    return
                }

                // 如果已经没有 Window 域了，那么就可以退出程序了
                if (SystemInfo.isWindows || SystemInfo.isLinux) {
                    Disposer.dispose(manager)
                } else if (SystemInfo.isMacOS) {
                    // 如果 macOS 开启了后台运行，那么尽管所有窗口都没了，也不会退出
                    if (isBackgroundRunning) {
                        return
                    }
                    Disposer.dispose(manager)
                }
            }

            override fun windowClosing(e: WindowEvent) {
                if (ApplicationScope.windowScopes().size != 1) {
                    window.dispose()
                    return
                }

                // 如果 Windows 开启了后台运行，那么最小化
                if (SystemInfo.isWindows && isBackgroundRunning) {
                    // 隐藏到托盘不会触发 windowClosed，所以要先把位置信息存下来
                    saveFrameRectangle(window)
                    // 最小化
                    window.extendedState = window.extendedState or JFrame.ICONIFIED
                    // 隐藏
                    window.isVisible = false
                    return
                }

                // 如果 macOS 已经开启了后台运行，那么直接销毁，因为会有一个进程驻守
                if (SystemInfo.isMacOS && isBackgroundRunning) {
                    window.dispose()
                    return
                }

                val option = OptionPane.showConfirmDialog(
                    window,
                    I18n.getString("termora.quit-confirm", Application.getName()),
                    optionType = JOptionPane.YES_NO_OPTION,
                )
                if (option == JOptionPane.YES_OPTION) {
                    window.dispose()
                }
            }
        })
    }

    fun tick() {
        if (SwingUtilities.isEventDispatchThread()) {
            val windows = getWindows()
            // macOS 开启后台运行后，关闭窗口是直接销毁的，此时一个窗口都不剩，
            // 从托盘或 Dock 唤起时需要重新创建，否则点了没有任何反应
            if (windows.isEmpty()) {
                createWindow().isVisible = true
                return
            }
            for (window in windows) {
                if (window.extendedState and JFrame.ICONIFIED == JFrame.ICONIFIED) {
                    window.extendedState = window.extendedState and JFrame.ICONIFIED.inv()
                }
                window.isVisible = true
            }
            windows.last().toFront()
        } else {
            SwingUtilities.invokeLater { tick() }
        }
    }

    override fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            // 数据库销毁之前，把还没落库的位置信息写进去
            flushFrameRectangle()

            Disposer.dispose(ApplicationScope.forApplicationScope())

            try {
                Disposer.getTree().assertIsEmpty(true)
            } catch (e: Exception) {
                if (log.isErrorEnabled) {
                    log.error(e.message, e)
                }
            }
        }

        exitProcess(0)
    }

    /**
     * 监听窗口的位置和大小变动，随时记录，延迟落库。
     * 这样隐藏到托盘、进程被强杀等场景也不会丢失位置信息。
     */
    private fun registerRectangleTracker(window: TermoraFrame) {
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = trackFrameRectangle(window)
            override fun componentResized(e: ComponentEvent) = trackFrameRectangle(window)
        })
        // 最大化、还原
        window.addWindowStateListener { trackFrameRectangle(window) }
    }

    private fun trackFrameRectangle(window: TermoraFrame) {
        val state = window.extendedState

        // 最小化状态下的边界不可信，忽略
        if (state and Frame.ICONIFIED == Frame.ICONIFIED) return

        // 只有正常状态下才记录边界，最大化时保留最大化之前的边界
        if (state and Frame.MAXIMIZED_BOTH != Frame.MAXIMIZED_BOTH) {
            normalBounds[window] = Rectangle(window.bounds)
        }

        pendingRectangle = getFrameRectangle(window)
        saveRectangleTimer.restart()
    }

    /**
     * 立即保存窗口的位置信息
     */
    private fun saveFrameRectangle(frame: TermoraFrame) {
        saveRectangleTimer.stop()
        pendingRectangle = null
        runCatching { writeFrameRectangle(getFrameRectangle(frame)) }
            .onFailure { if (log.isWarnEnabled) log.warn(it.message, it) }
    }

    /**
     * 把等待落库的位置信息写入数据库
     */
    @Synchronized
    private fun flushFrameRectangle() {
        val rectangle = pendingRectangle ?: return
        pendingRectangle = null
        runCatching { writeFrameRectangle(rectangle) }
            .onFailure { if (log.isWarnEnabled) log.warn(it.message, it) }
    }

    @Synchronized
    private fun writeFrameRectangle(rectangle: FrameRectangle) {
        val last = lastSavedRectangle
        if (last == rectangle) return

        // 只写变动的字段，减少数据库写入
        if (last == null || last.x != rectangle.x) {
            properties.putString("TermoraFrame.x", rectangle.x.toString())
        }
        if (last == null || last.y != rectangle.y) {
            properties.putString("TermoraFrame.y", rectangle.y.toString())
        }
        if (last == null || last.w != rectangle.w) {
            properties.putString("TermoraFrame.width", rectangle.w.toString())
        }
        if (last == null || last.h != rectangle.h) {
            properties.putString("TermoraFrame.height", rectangle.h.toString())
        }
        if (last == null || last.s != rectangle.s) {
            properties.putString("TermoraFrame.extendedState", rectangle.s.toString())
        }

        lastSavedRectangle = rectangle
    }

    private fun getFrameRectangle(frame: TermoraFrame): FrameRectangle {
        val bounds = normalBounds[frame] ?: frame.bounds
        // 剔除最小化标记，否则隐藏到托盘后下次启动会直接最小化
        val state = frame.extendedState and Frame.MAXIMIZED_BOTH
        return FrameRectangle(bounds.x, bounds.y, bounds.width, bounds.height, state)
    }

    /**
     * 判断窗口是否完全不在任何一个屏幕内
     */
    private fun isOutOfScreen(bounds: Rectangle): Boolean {
        val devices = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        }.getOrNull() ?: return false
        for (device in devices) {
            if (device.defaultConfiguration.bounds.intersects(bounds)) {
                return false
            }
        }
        return true
    }

    private fun getFrameRectangle(): FrameRectangle? {
        val x = properties.getString("TermoraFrame.x")?.toIntOrNull() ?: return null
        val y = properties.getString("TermoraFrame.y")?.toIntOrNull() ?: return null
        val w = properties.getString("TermoraFrame.width")?.toIntOrNull() ?: return null
        val h = properties.getString("TermoraFrame.height")?.toIntOrNull() ?: return null
        val s = properties.getString("TermoraFrame.extendedState")?.toIntOrNull() ?: return null
        return FrameRectangle(x, y, w, h, s)
    }

    fun setOpacity(opacity: Double) {
        if (opacity < 0 || opacity > 1) return
        for (window in getWindows()) {
            setOpacity(window, opacity)
        }
    }

    private fun setOpacity(window: Window, opacity: Double) {
        if (SystemInfo.isMacOS) {
            val nsWindow = ID(NativeMacLibrary.getNSWindow(window) ?: return)
            ThreadUtils.dispatch_async {
                Foundation.invoke(nsWindow, "setOpaque:", false)
                Foundation.invoke(nsWindow, "setAlphaValue:", opacity)
            }
        } else if (SystemInfo.isWindows) {
            val alpha = ((opacity * 255).toInt() and 0xFF).toByte()
            val hwnd = WinDef.HWND(Pointer.createConstant(FlatNativeWindowsLibrary.getHWND(window)))
            val exStyle = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE)
            if (exStyle and WS_EX_LAYERED == 0) {
                User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, exStyle or WS_EX_LAYERED)
            }
            User32.INSTANCE.SetLayeredWindowAttributes(hwnd, 0, alpha, LWA_ALPHA)
        } else if (SystemInfo.isLinux && WindowUtils.isWindowAlphaSupported()) {
            WindowUtils.setWindowAlpha(window, opacity.toFloat())
        }
    }

    private data class FrameRectangle(
        val x: Int, val y: Int, val w: Int, val h: Int, val s: Int
    ) {
        val isMaximized get() = (s and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
    }
}