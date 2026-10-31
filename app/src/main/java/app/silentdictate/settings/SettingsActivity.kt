package app.silentdictate.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.silentdictate.Prefs
import app.silentdictate.R
import app.silentdictate.ai.OpenRouterClient
import app.silentdictate.lips.model.ModelManager
import app.silentdictate.util.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single settings + onboarding screen. Same palette as the keyboard:
 * graphite on graphite, no color beyond error/status hints.
 */
class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var modelManager: ModelManager

    private lateinit var stepsContainer: LinearLayout
    private lateinit var useOpenRouter: SwitchCompat
    private lateinit var orFields: LinearLayout
    private lateinit var apiKey: EditText
    private lateinit var modelName: EditText
    private lateinit var checkKey: Button
    private lateinit var checkStatus: TextView
    private lateinit var rawWarning: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelUrl: EditText
    private lateinit var modelDownload: Button
    private lateinit var modelSourceNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        modelManager = ModelManager(this)
        setContentView(R.layout.activity_settings)

        stepsContainer = findViewById(R.id.steps)
        useOpenRouter = findViewById(R.id.use_openrouter)
        orFields = findViewById(R.id.openrouter_fields)
        apiKey = findViewById(R.id.api_key)
        modelName = findViewById(R.id.model_name)
        checkKey = findViewById(R.id.check_key)
        checkStatus = findViewById(R.id.check_status)
        rawWarning = findViewById(R.id.raw_warning)
        modelStatus = findViewById(R.id.model_status)
        modelUrl = findViewById(R.id.model_url)
        modelDownload = findViewById(R.id.model_download)
        modelSourceNote = findViewById(R.id.model_source)

        useOpenRouter.setOnCheckedChangeListener { _, checked ->
            prefs.useOpenRouter = checked
            applyVisibility()
        }
        apiKey.setText(prefs.openRouterKey)
        modelName.setText(prefs.openRouterModel)
        modelUrl.setText(prefs.modelUrl)
        checkKey.setOnClickListener { verifyKey() }
        modelDownload.setOnClickListener { downloadModel() }

        apiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.openRouterKey = apiKey.text.toString()
        }
        modelName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.openRouterModel = modelName.text.toString()
        }
        modelUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.modelUrl = modelUrl.text.toString()
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildSteps()
        applyVisibility()
        val status = modelManager.status()
        modelStatus.text = status.message
        modelStatus.setTextColor(
            if (status.installed) Palette.textPrimary else Palette.textSecondary
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun applyVisibility() {
        orFields.visibility = if (prefs.useOpenRouter) View.VISIBLE else View.GONE
        rawWarning.visibility = if (prefs.useOpenRouter) View.GONE else View.VISIBLE
        val desc = findViewById<TextView>(R.id.use_or_desc)
        if (prefs.useOpenRouter) {
            desc.text =
                "Recommended. The raw lip-reading output alone is inaccurate and unreliable — " +
                    "an LLM turns it into natural sentences before it lands in your text field."
        } else {
            desc.text =
                "Off. You get the raw lip-reading transcription — inaccurate and unreliable on " +
                    "its own. Words will often be garbled or missing."
        }
    }

    // ----- onboarding steps -----

    private data class Step(
        val number: String,
        val title: String,
        val doneText: String,
        val isDone: () -> Boolean,
        val actionLabel: () -> String,
        val action: () -> Unit,
    )

    private fun steps(): List<Step> = listOf(
        Step(
            "1", "Enable the keyboard", "Enabled",
            {
                val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                im.enabledInputMethodList.any { it.packageName == packageName }
            },
            { "Open system settings" },
            {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        ),
        Step(
            "2", "Choose it while typing", "Done",
            { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .currentInputMethodInfo?.packageName == packageName },
            { "Pick this keyboard" },
            {
                val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                im.showInputMethodPicker()
            }
        ),
        Step(
            "3", "Camera access", "Granted",
            {
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            },
            { "Grant camera" },
            {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), 7
                )
            }
        ),
        Step(
            "4", "Install a lip model", "Installed",
            { modelManager.status().installed },
            { "Go to Lip model" },
            {
                // nothing to open: section is below; surface guidance inline
                modelStatus.setTextColor(Palette.textSecondary)
            }
        ),
    )

    private fun rebuildSteps() {
        stepsContainer.removeAllViews()
        val dp = { v: Float -> (v * resources.displayMetrics.density).toInt() }
        steps().forEach { s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
                gravity = Gravity.CENTER_VERTICAL
            }
            val index = TextView(this).apply {
                text = s.number
                setTextColor(Palette.textFaint)
                textSize = 14f
            }
            index.layoutParams = LinearLayout.LayoutParams(dp(24f), ViewGroup.LayoutParams.WRAP_CONTENT)
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleColor = if (s.isDone()) Palette.textSecondary else Palette.textPrimary
            val title = TextView(this).apply {
                text = s.title
                setTextColor(titleColor)
                textSize = 15f
            }
            val badge = TextView(this).apply {
                text = if (s.isDone()) s.doneText else "To do"
                textSize = 12f
                setTextColor(if (s.isDone()) Palette.ok else Palette.textFaint)
            }
            textCol.addView(title)
            textCol.addView(badge)
            val actionLabel = s.actionLabel()
            val btn = Button(this).apply {
                text = if (s.isDone()) "Done ✓" else s.actionLabel()
                isEnabled = !s.isDone()
                textSize = 13f
                isAllCaps = false
                setTextColor(if (s.isDone()) Palette.textFaint else Palette.textPrimary)
                setBackgroundResource(
                    if (s.isDone()) R.drawable.btn_outline else R.drawable.btn_solid
                )
                setTextColor(if (s.isDone()) Palette.textFaint else Palette.textPrimary)
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                minWidth = 0
                minimumWidth = 0
                stateListAnimator = null
                setOnClickListener { s.action() }
            }
            row.addView(index)
            row.addView(textCol)
            row.addView(btn)
            stepsContainer.addView(row)
        }
    }

    // ----- actions -----

    private fun verifyKey() {
        val key = apiKey.text.toString()
        val model = modelName.text.toString()
        if (key.isBlank()) {
            checkStatus.visibility = View.VISIBLE
            checkStatus.text = "Paste a key first"
            checkStatus.setTextColor(Palette.danger)
            return
        }
        checkKey.isEnabled = false
        checkStatus.visibility = View.VISIBLE
        checkStatus.text = "Checking…"
        checkStatus.setTextColor(Palette.textSecondary)
        scope.launch {
            val result = OpenRouterClient().cleanUp(
                key = key, model = model,
                rawLipRead = "i stl to, same conve",
                contextBefore = ""
            )
            checkKey.isEnabled = true
            checkStatus.text = when (result) {
                is OpenRouterClient.Result.Ok -> {
                    "Key works — the model responded"
                }
                is OpenRouterClient.Result.Error -> result.userMessage
            }
            checkStatus.setTextColor(
                if (result is OpenRouterClient.Result.Ok) Palette.ok else Palette.danger
            )
        }
    }

    private fun downloadModel() {
        val url = modelUrl.text.toString().trim()
        prefs.modelUrl = url
        if (url.isBlank()) {
            modelStatus.text = "Paste a model zip URL first"
            modelStatus.setTextColor(Palette.danger)
            return
        }
        modelDownload.isEnabled = false
        modelStatus.text = "Downloading…"
        modelStatus.setTextColor(Palette.textSecondary)
        scope.launch {
            val result = withContext(Dispatchers.IO) { modelManager.download(url) }
            modelDownload.isEnabled = true
            when (result) {
                is ModelManager.DownloadResult.Ok -> {
                    modelStatus.text = "Model installed (${result.status.sizeMb} MB)"
                    modelStatus.setTextColor(Palette.ok)
                    rebuildSteps()
                }
                is ModelManager.DownloadResult.Error -> {
                    modelStatus.text = result.message
                    modelStatus.setTextColor(Palette.danger)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            rebuildSteps()
        }
    }
}
