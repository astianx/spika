package org.astianx.midori.one

import org.astianx.midori.one.chat.ChatAppearance
import org.json.JSONObject

/** Portable appearance only. No identifiers, credentials, local images or conversation overrides. */
internal data class ThemeBundle(val app: AppStyle, val chats: ChatAppearance) {
    fun encode(): String = JSONObject().put("format", "midori-theme").put("version", 2)
        .put("app", app.toJson()).put("chats", chats.toJson(includeLocalPhoto = false))
        .toString(2)
    companion object {
        const val MAX_BYTES = 32 * 1024
        fun decode(bytes: ByteArray): ThemeBundle {
            require(bytes.size <= MAX_BYTES)
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getString("format") == "midori-theme" && json.getInt("version") in 1..2)
            return ThemeBundle(AppStyle.fromJson(json.getJSONObject("app")),
                ChatAppearance.fromJson(json.getJSONObject("chats"), allowLocalPhoto = false))
        }
    }
}
