package app.termora

import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JComponent

/**
 * 远程桌面等需要独占键盘的组件。
 * 窗口级快捷键会先于 [KeyListener] 触发，命中后要把按键转发给该组件，而不是执行本机动作。
 */
object CaptureKeyboard {
    const val KEY = "Termora.captureKeyboard"

    fun target(component: Component?): JComponent? {
        var current = component
        while (current != null) {
            if (current is JComponent && current.getClientProperty(KEY) == true) {
                return current
            }
            current = current.parent
        }
        return null
    }

    fun forward(target: Component, event: KeyEvent) {
        val copy = KeyEvent(
            target,
            event.id,
            event.`when`,
            event.modifiersEx,
            event.keyCode,
            event.keyChar,
            event.keyLocation
        )
        for (listener in target.keyListeners) {
            dispatch(listener, copy)
        }
    }

    private fun dispatch(listener: KeyListener, event: KeyEvent) {
        when (event.id) {
            KeyEvent.KEY_PRESSED -> listener.keyPressed(event)
            KeyEvent.KEY_RELEASED -> listener.keyReleased(event)
            KeyEvent.KEY_TYPED -> listener.keyTyped(event)
        }
    }
}
