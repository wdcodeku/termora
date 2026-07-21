package app.termora.favorite

import app.termora.*
import app.termora.actions.DataProviders
import app.termora.terminal.panel.TerminalWriter
import com.formdev.flatlaf.extras.components.FlatButton
import org.apache.commons.lang3.StringUtils
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel

class FavoriteDialog(owner: Window) : DialogWrapper(owner) {

    private val favoriteManager get() = FavoriteManager.getInstance()
    private val favorites = favoriteManager.getFavorites().toMutableList()
    private val tableModel = FavoriteTableModel()
    private val table = JTable(tableModel)

    private val addButton = FlatButton()
    private val editButton = FlatButton()
    private val copyButton = FlatButton()
    private val removeButton = FlatButton()

    init {
        size = Dimension(UIManager.getInt("Dialog.width"), UIManager.getInt("Dialog.height"))
        isModal = true
        isResizable = true
        title = I18n.getString("termora.favorite.title")
        setLocationRelativeTo(owner)

        initViews()
        initEvents()
        init()
    }

    private fun initViews() {
        table.tableHeader.reorderingAllowed = false
        table.rowHeight = table.rowHeight + 6
        table.fillsViewportHeight = true
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // 说明列窄一些
        table.columnModel.getColumn(0).preferredWidth = 160
        table.columnModel.getColumn(1).preferredWidth = 320

        addButton.icon = Icons.add
        addButton.toolTipText = I18n.getString("termora.favorite.add")
        editButton.icon = Icons.edit
        editButton.toolTipText = I18n.getString("termora.keymgr.edit")
        copyButton.icon = Icons.copy
        copyButton.toolTipText = I18n.getString("termora.welcome.contextmenu.copy")
        removeButton.icon = Icons.delete
        removeButton.toolTipText = I18n.getString("termora.remove")
        for (b in arrayOf(addButton, editButton, copyButton, removeButton)) {
            b.isFocusable = false
            b.buttonType = FlatButton.ButtonType.toolBarButton
        }
    }

    private fun initEvents() {
        addButton.addActionListener {
            val favorite = showEditDialog(null) ?: return@addActionListener
            favorites.add(favorite)
            favoriteManager.setFavorites(favorites)
            tableModel.fireTableDataChanged()
            val row = favorites.size - 1
            table.setRowSelectionInterval(row, row)
        }

        editButton.addActionListener { editSelected() }

        copyButton.addActionListener {
            val row = table.selectedRow
            if (row < 0) return@addActionListener
            val command = favorites[row].command
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(command), null)
        }

        removeButton.addActionListener {
            val row = table.selectedRow
            if (row < 0) return@addActionListener
            favorites.removeAt(row)
            favoriteManager.setFavorites(favorites)
            tableModel.fireTableDataChanged()
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                sendToTerminal(favorites[row])
            }
        })
    }

    private fun editSelected() {
        val row = table.selectedRow
        if (row < 0) return
        val favorite = showEditDialog(favorites[row]) ?: return
        favorites[row] = favorite
        favoriteManager.setFavorites(favorites)
        tableModel.fireTableDataChanged()
        table.setRowSelectionInterval(row, row)
    }

    /**
     * 弹出编辑框（说明 + 指令），确定后返回，取消返回 null
     */
    private fun showEditDialog(existing: Favorite?): Favorite? {
        val descriptionField = JTextField(existing?.description ?: StringUtils.EMPTY, 24)
        val commandArea = JTextArea(existing?.command ?: StringUtils.EMPTY, 6, 24)
        commandArea.lineWrap = true

        val panel = JPanel(BorderLayout(8, 8))
        val top = JPanel(BorderLayout(4, 4))
        top.add(JLabel("${I18n.getString("termora.favorite.description")}:"), BorderLayout.WEST)
        top.add(descriptionField, BorderLayout.CENTER)
        panel.add(top, BorderLayout.NORTH)

        val center = JPanel(BorderLayout(4, 4))
        center.add(JLabel("${I18n.getString("termora.favorite.command")}:"), BorderLayout.NORTH)
        center.add(JScrollPane(commandArea), BorderLayout.CENTER)
        panel.add(center, BorderLayout.CENTER)
        panel.preferredSize = Dimension(420, 220)

        val result = JOptionPane.showConfirmDialog(
            this, panel, I18n.getString("termora.favorite.title"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return null

        val command = commandArea.text.trim()
        if (command.isBlank()) return null

        return (existing ?: Favorite()).copy(
            command = command,
            description = descriptionField.text.trim()
        )
    }

    /**
     * 将指令发送到当前终端
     */
    private fun sendToTerminal(favorite: Favorite) {
        val writer = currentTerminalWriter()
        if (writer == null) {
            OptionPane.showMessageDialog(
                this, I18n.getString("termora.favorite.no-terminal"),
                messageType = JOptionPane.WARNING_MESSAGE
            )
            return
        }
        writer.write(TerminalWriter.WriteRequest.fromBytes(favorite.command.toByteArray(writer.getCharset())))
        dispose()
    }

    private fun currentTerminalWriter(): TerminalWriter? {
        val window = owner ?: return null
        val scope = runCatching { ApplicationScope.forWindowScope(window) }.getOrNull() ?: return null
        val manager = runCatching { scope.get(TerminalTabbedManager::class) }.getOrNull() ?: return null
        val tab = manager.getSelectedTerminalTab() ?: return null
        return tab.getData(DataProviders.TerminalWriter)
    }

    override fun createCenterPanel(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2))
        toolbar.add(addButton)
        toolbar.add(editButton)
        toolbar.add(copyButton)
        toolbar.add(removeButton)

        val tip = JLabel(I18n.getString("termora.favorite.double-click-tip"))
        tip.foreground = UIManager.getColor("textInactiveText")
        tip.border = BorderFactory.createEmptyBorder(2, 6, 4, 6)

        val panel = JPanel(BorderLayout())
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        panel.add(tip, BorderLayout.SOUTH)
        panel.border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        return panel
    }

    override fun createSouthPanel(): JComponent? {
        return null
    }

    private inner class FavoriteTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = favorites.size

        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String {
            return when (column) {
                0 -> I18n.getString("termora.favorite.description")
                else -> I18n.getString("termora.favorite.command")
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val favorite = favorites[rowIndex]
            return when (columnIndex) {
                0 -> favorite.description
                else -> favorite.command
            }
        }
    }
}
