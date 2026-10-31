package app.silentdictate

import android.content.Context
import android.content.SharedPreferences

/** Settings store. Small BYO-everything app: one SharedPreferences file. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("silent_dictate", Context.MODE_PRIVATE).also {
            // earlier builds logged typed text here; erase any leftovers
            if (it.contains(KEY_RECENT_TEXT)) it.edit().remove(KEY_RECENT_TEXT).apply()
        }

    var useOpenRouter: Boolean
        get() = sp.getBoolean(KEY_USE_OPENROUTER, true)
        set(value) = sp.edit().putBoolean(KEY_USE_OPENROUTER, value).apply()

    var openRouterKey: String
        get() = sp.getString(KEY_OPENROUTER_KEY, "").orEmpty()
        set(value) = sp.edit().putString(KEY_OPENROUTER_KEY, value.trim()).apply()

    var openRouterModel: String
        get() {
            val v = sp.getString(KEY_OPENROUTER_MODEL, null).orEmpty().trim()
            // blank, or the pre-0.2 default that OpenRouter has since retired
            return if (v.isEmpty() || v == LEGACY_DEFAULT_MODEL) DEFAULT_OPENROUTER_MODEL else v
        }
        set(value) = sp.edit().putString(KEY_OPENROUTER_MODEL, value.trim()).apply()

    var swipeHintDone: Boolean
        get() = sp.getBoolean(KEY_SWIPE_HINT, false)
        set(value) = sp.edit().putBoolean(KEY_SWIPE_HINT, value).apply()

    companion object {
        /** OpenRouter's free-model router: always points at a currently available free model. */
        const val DEFAULT_OPENROUTER_MODEL = "openrouter/free"
        private const val LEGACY_DEFAULT_MODEL = "meta-llama/llama-3.2-3b-instruct:free"
        private const val KEY_USE_OPENROUTER = "use_openrouter"
        private const val KEY_OPENROUTER_KEY = "openrouter_key"
        private const val KEY_OPENROUTER_MODEL = "openrouter_model"
        private const val KEY_SWIPE_HINT = "swipe_hint_done"
        private const val KEY_RECENT_TEXT = "recent_text"  // removed: never persist what the user types
    }
}
