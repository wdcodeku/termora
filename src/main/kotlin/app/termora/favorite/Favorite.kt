package app.termora.favorite

import app.termora.randomUUID
import kotlinx.serialization.Serializable
import org.apache.commons.lang3.StringUtils

/**
 * 收藏的指令
 */
@Serializable
data class Favorite(
    val id: String = randomUUID(),
    /**
     * 指令
     */
    val command: String = StringUtils.EMPTY,
    /**
     * 说明
     */
    val description: String = StringUtils.EMPTY,
)
