package app.termora.favorite

import app.termora.Application.ohMyJson
import app.termora.ApplicationScope
import app.termora.database.DatabaseManager
import org.apache.commons.lang3.StringUtils

/**
 * 收藏指令的管理器，数据以 JSON 形式存放在本地配置中。
 */
class FavoriteManager private constructor() {
    companion object {
        private const val KEY = "Termora.Favorites"

        fun getInstance(): FavoriteManager {
            return ApplicationScope.forApplicationScope().getOrCreate(FavoriteManager::class) { FavoriteManager() }
        }
    }

    private val properties get() = DatabaseManager.getInstance().properties

    fun getFavorites(): List<Favorite> {
        val text = properties.getString(KEY, StringUtils.EMPTY)
        if (text.isBlank()) return emptyList()
        return runCatching { ohMyJson.decodeFromString<List<Favorite>>(text) }.getOrDefault(emptyList())
    }

    fun setFavorites(favorites: List<Favorite>) {
        properties.putString(KEY, ohMyJson.encodeToString(favorites))
    }

    fun addFavorite(favorite: Favorite) {
        val list = getFavorites().toMutableList()
        val index = list.indexOfFirst { it.id == favorite.id }
        if (index >= 0) list[index] = favorite else list.add(favorite)
        setFavorites(list)
    }

    fun removeFavorite(id: String) {
        setFavorites(getFavorites().filter { it.id != id })
    }
}
