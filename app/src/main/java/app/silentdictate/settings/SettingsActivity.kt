package app.silentdictate.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import app.silentdictate.Prefs
import app.silentdictate.R
import app.silentdictate.ai.OpenRouterClient
import app.silentdictate.lips.model.ModelInstaller
import app.silentdictate.lips.model.ModelManager
import app.silentdictate.update.Updater
import app.silentdictate.util.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The single settings + onboarding screen. Same palette as the keyboard:
 * graphite on graphite, off-white for emphasis, red only for problems.
 */
class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var modelManager: ModelManager

    private lateinit var scroll: ScrollView
    private lateinit var stepsContainer: LinearLayout
    private lateinit var tryField: EditText
    private lateinit var tryDivider: View
    private lateinit var transcriptionCard: ViewGroup
    private lateinit var segOr: View
    private lateinit var segRaw: View
    private lateinit var orFields: View
    private lateinit var orDesc: TextView
    private lateinit var rawWarning: TextView
    private lateinit var apiKey: EditText
    private lateinit var keyToggle: TextView
    private lateinit var modelName: EditText
    private lateinit var checkKey: Button
    private lateinit var checkStatus: TextView
    private lateinit var modelCard: View
    private lateinit var modelStatus: TextView
    private lateinit var modelDetail: TextView
    private lateinit var modelProgress: android.widget.ProgressBar
    private lateinit var modelImport: TextView
    private lateinit var modelDownload: Button
    private lateinit var modelRemove: Button

    private lateinit var updateStatus: TextView
    private lateinit var updateDetail: TextView
    private lateinit var updateProgress: android.widget.ProgressBar
    private lateinit var updateAction: Button
    private lateinit var updateSkip: Button

    private var updater = Updater(this)
    private var updateOffer: Updater.Check.Available? = null
    private var downloadedUpdate: java.io.File? = null
    private var updateBusy = false

    private var busy = false

    private val pickZip =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        modelManager = ModelManager(this)
        setContentView(R.layout.activity_settings)

        scroll = findViewById(R.id.scroll)
        stepsContainer = findViewById(R.id.steps)
        tryField = findViewById(R.id.try_field)
        tryDivider = findViewById(R.id.try_divider)
        transcriptionCard = findViewById(R.id.transcription_card)
        segOr = findViewById(R.id.seg_or)
        segRaw = findViewById(R.id.seg_raw)
        orFields = findViewById(R.id.openrouter_fields)
        orDesc = findViewById(R.id.use_or_desc)
        rawWarning = findViewById(R.id.raw_warning)
        apiKey = findViewById(R.id.api_key)
        keyToggle = findViewById(R.id.key_toggle)
        modelName = findViewById(R.id.model_name)
        checkKey = findViewById(R.id.check_key)
        checkStatus = findViewById(R.id.check_status)
        modelCard = findViewById(R.id.model_card)
        modelStatus = findViewById(R.id.model_status)
        modelDetail = findViewById(R.id.model_detail)
        modelProgress = findViewById(R.id.model_progress)
        modelImport = findViewById(R.id.model_import)
        modelDownload = findViewById(R.id.model_download)
        modelRemove = findViewById(R.id.model_remove)

        segOr.setOnClickListener { setUseOpenRouter(true) }
        segRaw.setOnClickListener { setUseOpenRouter(false) }

        apiKey.setText(prefs.openRouterKey)
        modelName.setText(prefs.openRouterModel)
        apiKey.doAfterTextChanged {
            prefs.openRouterKey = it?.toString().orEmpty()
            checkStatus.visibility = View.GONE
        }
        modelName.doAfterTextChanged { prefs.openRouterModel = it?.toString().orEmpty() }

        keyToggle.setOnClickListener {
            val show = apiKey.transformationMethod != null
            val keepSelection = apiKey.selectionStart
            apiKey.inputType = InputType.TYPE_CLASS_TEXT or
                if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_PASSWORD
            apiKey.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
            keyToggle.text = if (show) "Hide" else "Show"
            apiKey.setSelection(keepSelection.coerceAtLeast(0))
        }

        checkKey.setOnClickListener { verifyKey() }
        modelImport.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        modelProgress.progressTintList = android.content.res.ColorStateList.valueOf(Palette.textPrimary)
        modelProgress.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2D32.toInt())
        modelDownload.setOnClickListener { downloadModel() }
        modelRemove.setOnClickListener {
            ModelInstaller.clearError()
            modelManager.wipe()
            refreshModel()
            rebuildSteps()
        }

        updateStatus = findViewById(R.id.update_status)
        updateDetail = findViewById(R.id.update_detail)
        updateProgress = findViewById(R.id.update_progress)
        updateAction = findViewById(R.id.update_action)
        updateSkip = findViewById(R.id.update_skip)
        updateProgress.progressTintList = android.content.res.ColorStateList.valueOf(Palette.textPrimary)
        updateProgress.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2D32.toInt())
        updateAction.setOnClickListener { onUpdateAction() }
        updateSkip.setOnClickListener {
            updateOffer = null
            downloadedUpdate = null
            renderUpdateIdle()
        }
        renderUpdateIdle()

        applyMode(animate = false)
    }

    private var installWatch: kotlinx.coroutines.Job? = null

    override fun onStart() {
        super.onStart()
        installWatch = scope.launch { ModelInstaller.state.collect { render(it) } }
    }

    override fun onStop() {
        installWatch?.cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        rebuildSteps()
        if (!ModelInstaller.busy) refreshModel()
        autoCheckUpdate()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ transcription

    private fun setUseOpenRouter(on: Boolean) {
        if (prefs.useOpenRouter == on) return
        prefs.useOpenRouter = on
        applyMode(animate = true)
    }

    private fun applyMode(animate: Boolean) {
        val on = prefs.useOpenRouter
        if (animate) {
            TransitionManager.beginDelayedTransition(
                transcriptionCard, AutoTransition().setDuration(200)
            )
        }
        styleSegment(segOr, on)
        styleSegment(segRaw, !on)
        orFields.visibility = if (on) View.VISIBLE else View.GONE
        rawWarning.visibility = if (on) View.GONE else View.VISIBLE
        orDesc.text = if (on) {
            "The lip reader’s raw output is rough. OpenRouter reads it along with the text before your cursor and works out what you meant to say."
        } else {
            "Only the lip reader’s raw output is inserted, and nothing leaves your phone."
        }
    }

    private fun styleSegment(seg: View, selected: Boolean) {
        seg.setBackgroundResource(if (selected) R.drawable.seg_selected else 0)
        val group = seg as ViewGroup
        (group.getChildAt(0) as TextView).setTextColor(
            if (selected) Palette.bg else Palette.textSecondary
        )
        (group.getChildAt(1) as TextView).setTextColor(
            if (selected) 0xFF5B5F66.toInt() else Palette.textFaint
        )
    }

    private fun verifyKey() {
        val key = OpenRouterClient.normalizeKey(apiKey.text.toString())
        val model = modelName.text.toString().trim()
        if (key.isBlank()) {
            showCheck("Paste a key first", error = true)
            return
        }
        checkKey.isEnabled = false
        showCheck("Checking…", error = false)
        scope.launch {
            val result = OpenRouterClient().cleanUp(
                key = key, model = model,
                rawLipRead = "i stl to, same conve",
                contextBefore = "",
                allowFallbacks = false,   // a check tests the user's own model, not the fallbacks
            )
            checkKey.isEnabled = true
            when (result) {
                is OpenRouterClient.Result.Ok -> showCheck("Works — “${result.text.take(40)}”", error = false)
                is OpenRouterClient.Result.Error -> showCheck(result.userMessage, error = true)
            }
        }
    }

    private fun showCheck(text: String, error: Boolean) {
        checkStatus.visibility = View.VISIBLE
        checkStatus.text = text
        checkStatus.setTextColor(if (error) Palette.danger else Palette.textSecondary)
    }

    // ------------------------------------------------------------ lip model

    private fun refreshModel() {
        val st = modelManager.status()
        if (!busy) {
            modelStatus.text = if (st.installed) "Installed · ${formatSize(st.sizeMb)}"
            else "Not installed"
            modelStatus.setTextColor(Palette.textPrimary)
            modelDetail.text = if (st.installed) {
                "Runs on your phone. Video never leaves it."
            } else {
                "${ModelManager.Recommended.NAME}, ${ModelManager.Recommended.SIZE_MB} MB. Best downloaded on Wi-Fi."
            }
            modelDetail.setTextColor(Palette.textSecondary)
            modelProgress.visibility = View.GONE
        }
        modelDownload.text = if (st.installed) "Re-download" else "Download"
        modelDownload.visibility = if (st.installed) View.GONE else View.VISIBLE
        modelRemove.visibility = if (st.installed && !busy) View.VISIBLE else View.GONE
    }

    private fun formatSize(mb: Float): String =
        if (mb >= 10f) "%.0f MB".format(mb)
        else if (mb >= 1f) "%.1f MB".format(mb)
        else "%.0f KB".format(mb * 1024f)

    private fun setBusy(on: Boolean, message: String? = null) {
        busy = on
        modelImport.isEnabled = !on
        modelDownload.isEnabled = !on
        modelRemove.isEnabled = !on
        if (on) {
            modelStatus.text = message ?: "Working…"
            modelStatus.setTextColor(Palette.textPrimary)
            modelDetail.text = "This takes a minute or two."
            modelDetail.setTextColor(Palette.textSecondary)
            modelProgress.visibility = View.VISIBLE
        }
    }

    /** Mirrors [ModelInstaller]'s state, which outlives this screen. */
    private fun render(state: ModelInstaller.State) {
        when (state) {
            is ModelInstaller.State.Working -> {
                setBusy(true, state.label)
                modelDownload.visibility = View.VISIBLE
                modelRemove.visibility = View.GONE
                val p = state.progress
                modelProgress.isIndeterminate = p == null
                if (p != null) modelProgress.progress = (p * 1000).toInt()
            }
            ModelInstaller.State.Idle -> {
                setBusy(false)
                refreshModel()
                rebuildSteps()
            }
            is ModelInstaller.State.Failed -> {
                setBusy(false)
                refreshModel()
                modelStatus.text = "Couldn’t install the model"
                modelDetail.text = state.message
                modelDetail.setTextColor(Palette.danger)
                modelDownload.visibility = View.VISIBLE
                modelDownload.text = "Try again"
            }
        }
    }

    private fun importModel(uri: Uri) = ModelInstaller.importZip(this, uri)

    private fun downloadModel() = ModelInstaller.downloadRecommended(this)

    // ------------------------------------------------------------ in-app updates

    /** One quiet GitHub check per process (refreshed by the manual button). */
    private var autoChecked = false
    private fun autoCheckUpdate() {
        if (autoChecked || updateBusy) return
        autoChecked = true
        scope.launch { checkUpdate(manual = false) }
    }

    private fun onUpdateAction() {
        when {
            updateBusy -> Unit
            downloadedUpdate != null -> installUpdate()
            updateOffer != null -> startDownload()
            else -> scope.launch { checkUpdate(manual = true) }
        }
    }

    private suspend fun checkUpdate(manual: Boolean) {
        if (updateBusy) return
        if (manual) {
            updateStatus.text = "Checking…"
            updateStatus.setTextColor(Palette.textPrimary)
            updateDetail.text = "Asking GitHub for the latest release…"
            updateDetail.setTextColor(Palette.textSecondary)
        }
        when (val result = updater.check()) {
            is Updater.Check.Available -> {
                if (!manual && sameOffer(result)) return
                updateOffer = result
                downloadedUpdate = null
                updateStatus.text = "Version ${result.version} available"
                updateStatus.setTextColor(Palette.textPrimary)
                updateDetail.text =
                    "Current version is ${updater.installedVersion()}. " +
                        "Installs over the existing one; your settings and model stay."
                updateDetail.setTextColor(Palette.textSecondary)
                updateProgress.visibility = View.GONE
                updateAction.text = "Download update"
                updateSkip.visibility = View.VISIBLE
            }
            is Updater.Check.UpToDate -> {
                if (!manual) return
                updateOffer = null
                downloadedUpdate = null
                updateStatus.text = "Up to date"
                updateStatus.setTextColor(Palette.textPrimary)
                updateDetail.text = "v${result.version} is the latest release."
                updateDetail.setTextColor(Palette.textSecondary)
                updateProgress.visibility = View.GONE
                updateAction.text = "Check again"
                updateSkip.visibility = View.GONE
            }
            is Updater.Check.Failed -> {
                if (!manual) return
                updateOffer = null
                downloadedUpdate = null
                updateStatus.text = "Couldn’t check for updates"
                updateStatus.setTextColor(Palette.danger)
                updateDetail.text = result.message
                updateDetail.setTextColor(Palette.danger)
                updateProgress.visibility = View.GONE
                updateAction.text = "Try again"
                updateSkip.visibility = View.GONE
            }
        }
    }

    private fun sameOffer(offer: Updater.Check.Available): Boolean =
        updateOffer?.version == offer.version && downloadedUpdate == null

    private fun startDownload() {
        val offer = updateOffer ?: return
        updateBusy = true
        updateAction.isEnabled = false
        updateSkip.isEnabled = false
        updateStatus.text = "Downloading update…"
        updateStatus.setTextColor(Palette.textPrimary)
        updateDetail.text = "From GitHub"
        updateDetail.setTextColor(Palette.textSecondary)
        updateProgress.isIndeterminate = true
        updateProgress.visibility = View.VISIBLE
        scope.launch {
            try {
                val file = updater.download(offer.assetUrl) { read, total ->
                    scope.launch(Dispatchers.Main) {
                        updateProgress.isIndeterminate = total <= 0
                        if (total > 0) updateProgress.progress = (read * 1000 / total).toInt()
                        val mb = { b: Long -> "%.1f MB".format(b / (1024f * 1024f)) }
                        updateDetail.text = if (total > 0)
                            "${mb(read)} / ${mb(total)}"
                        else "${mb(read)} downloaded…"
                    }
                }
                downloadedUpdate = file
                updateOffer = null
                updateStatus.text = "Update downloaded"
                updateDetail.text = "Ready to install — v${offer.version}"
                updateDetail.setTextColor(Palette.textSecondary)
                updateProgress.visibility = View.GONE
                updateAction.text = "Install now"
                updateSkip.visibility = View.VISIBLE
            } catch (e: Exception) {
                updateStatus.text = "Download failed"
                updateStatus.setTextColor(Palette.danger)
                updateDetail.text = e.message ?: "Network error"
                updateDetail.setTextColor(Palette.danger)
                updateProgress.visibility = View.GONE
                updateAction.text = "Try again"
            } finally {
                updateBusy = false
                updateAction.isEnabled = true
                updateSkip.isEnabled = true
            }
        }
    }

    private fun installUpdate() {
        val file = downloadedUpdate ?: return
        if (!updater.canRequestInstalls()) {
            updater.requestInstallApproval()
            return
        }
        updateBusy = true
        updateAction.isEnabled = false
        updateSkip.isEnabled = false
        updateStatus.text = "Starting install…"
        updateDetail.text = "Android’s installer takes it from here."
        updateDetail.setTextColor(Palette.textSecondary)
        if (!updater.install(file)) {
            updateStatus.text = "Couldn’t open the installer"
            updateStatus.setTextColor(Palette.danger)
            updateDetail.text = "Install manually: github.com/Ember2819/silent-dictate/releases"
            updateDetail.setTextColor(Palette.danger)
        }
        updateBusy = false
        updateAction.isEnabled = true
        updateSkip.isEnabled = true
    }

    private fun renderUpdateIdle() {
        val current = updater.installedVersion()
        updateStatus.text = "Version $current"
        updateStatus.setTextColor(Palette.textPrimary)
        updateDetail.text = "Tap to see if a newer release is on GitHub."
        updateDetail.setTextColor(Palette.textSecondary)
        updateProgress.visibility = View.GONE
        updateAction.isEnabled = true
        updateSkip.isEnabled = true
        updateAction.text = "Check for updates"
        updateSkip.visibility = View.GONE
    }

    // ------------------------------------------------------------ onboarding

    private class Step(
        val title: String,
        val hint: String,
        val doneText: String,
        val action: String,
        val isDone: () -> Boolean,
        val run: () -> Unit,
    )

    private fun steps(): List<Step> = listOf(
        Step(
            "Enable the keyboard", "Turn on Silent Dictate in system settings", "Enabled", "Enable",
            {
                val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                im.enabledInputMethodList.any { it.packageName == packageName }
            },
            { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        ),
        Step(
            "Switch to it", "Pick it as your active keyboard", "Active", "Switch",
            {
                val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                current?.startsWith("$packageName/") == true
            },
            {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        ),
        Step(
            "Allow the camera", "Used only while you hold the dictate button", "Allowed", "Allow",
            { checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
            { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA) }
        ),
        Step(
            "Add a lip model", "The on-device model that reads your lips", "Installed", "Add",
            { modelManager.status().installed },
            {
                scroll.smoothScrollTo(0, modelCard.top - dp(24f))
            }
        ),
    )

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun rebuildSteps() {
        stepsContainer.removeAllViews()
        val all = steps()
        val states = all.map { it.isDone() }
        all.forEachIndexed { i, s ->
            val done = states[i]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18f), dp(14f), dp(14f), dp(14f))
            }
            row.addView(
                StepDot(this, (i + 1).toString(), done),
                LinearLayout.LayoutParams(dp(26f), dp(26f))
            )
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = s.title
                textSize = 15.5f
                setTextColor(if (done) Palette.textSecondary else Palette.textPrimary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            col.addView(TextView(this).apply {
                text = if (done) s.doneText else s.hint
                textSize = 12.5f
                setTextColor(Palette.textFaint)
                setPadding(0, dp(2f), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14f)
                marginEnd = dp(10f)
            })
            if (!done) {
                val primary = states.indexOfFirst { !it } == i
                row.addView(
                    Button(
                        this, null, 0,
                        if (primary) R.style.Button_Primary else R.style.Button_Secondary
                    ).apply {
                        text = s.action
                        textSize = 13.5f
                        minHeight = dp(38f)
                        minimumHeight = dp(38f)
                        setPadding(dp(16f), 0, dp(16f), 0)
                        setOnClickListener { s.run() }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38f))
                )
            }
            stepsContainer.addView(row)
        }
        val keyboardOn = states[0]
        tryField.visibility = if (keyboardOn) View.VISIBLE else View.GONE
        tryDivider.visibility = if (keyboardOn) View.VISIBLE else View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // permanently denied: only app settings can change it now
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
        rebuildSteps()
    }

    /** Numbered ring while pending, filled off-white check once done. */
    private class StepDot(context: Context, private val number: String, private val done: Boolean) :
        View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - d
            if (done) {
                paint.style = Paint.Style.FILL
                paint.color = Palette.textPrimary
                canvas.drawCircle(cx, cy, r + d * 0.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.color = Palette.bg
                paint.strokeWidth = 2f * d
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                path.reset()
                path.moveTo(cx - r * 0.38f, cy + r * 0.02f)
                path.lineTo(cx - r * 0.08f, cy + r * 0.32f)
                path.lineTo(cx + r * 0.42f, cy - r * 0.28f)
                canvas.drawPath(path, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.color = 0xFF363A40.toInt()
                paint.strokeWidth = 1.5f * d
                canvas.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.FILL
                paint.color = Palette.textSecondary
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 12.5f * d
                canvas.drawText(number, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
            }
        }
    }

    companion object {
        private const val REQ_CAMERA = 7
    }
}
