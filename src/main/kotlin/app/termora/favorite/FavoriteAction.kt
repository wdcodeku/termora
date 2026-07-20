package app.termora.favorite

import app.termora.ApplicationScope
import app.termora.I18n
import app.termora.Icons
import app.termora.actions.AnAction
import app.termora.actions.AnActionEvent

class FavoriteAction private constructor() : AnAction(I18n.getString("termora.favorite.title"), Icons.bookmarks) {
    companion object {
        const val FAVORITE = "FavoriteAction"

        fun getInstance(): FavoriteAction {
            return ApplicationScope.forApplicationScope().getOrCreate(FavoriteAction::class) { FavoriteAction() }
        }
    }

    override fun actionPerformed(evt: AnActionEvent) {
        FavoriteDialog(evt.window).isVisible = true
    }
}
