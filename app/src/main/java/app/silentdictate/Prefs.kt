package app.silentdictate

import android.content.Context
import android.content.SharedPreferences

/** Settings store. Small BYO-everything app: one SharedPreferences file. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("silent_dictate", Context.MODE_PRIVATE)

    var useOpenRouter: Boolean
        get() = sp.getBoolean(KEY_USE_OPENROUTER, true)
        set(value) = sp.edit().putBoolean(KEY_USE_OPENROUTER, value).apply()

    var openRouterKey: String
        get() = sp.getString(KEY_OPENROUTER_KEY, "").orEmpty()
        set(value) = sp.edit().putString(KEY_OPENROUTER_KEY, value.trim()).apply()

    var openRouterModel: String
        get() = sp.getString(KEY_OPENROUTER_MODEL, DEFAULT_OPENROUTER_MODEL).orEmpty()
        set(value) = sp.edit().putString(KEY_OPENROUTER_MODEL, value.trim()).apply()

    var modelUrl: String
        get() = sp.getString(KEY_MODEL_URL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_MODEL_URL, value.trim()).apply()

    var swipeHintDone: Boolean
        get() = sp.getBoolean(KEY_SWIPE_HINT, false)
        set(value) = sp.edit().putBoolean(KEY_SWIPE_HINT, value).apply()

    /** Last committed text, used as chat context for the cleanup pass. */
    var recentText: String
        get() = sp.getString(KEY_RECENT_TEXT, "").orEmpty()
        set(value) = sp.edit().putString(KEY_RECENT_TEXT, value).apply()

    companion object {
        const val DEFAULT_OPENROUTER_MODEL = "meta-llama/llama-3.2-3b-instruct:free"
        private const val KEY_USE_OPENROUTER = "use_openrouter"
        private const val KEY_OPENROUTER_KEY = "openrouter_key"
        private const val KEY_OPENROUTER_MODEL = "openrouter_model"
        private const val KEY_MODEL_URL = "model_url"
        private const val KEY_SWIPE_HINT = "swipe_hint_done"
        private const val KEY_RECENT_TEXT = "recent_text"
    }
}
