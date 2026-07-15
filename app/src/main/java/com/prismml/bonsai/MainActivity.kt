package com.prismml.bonsai

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.prismml.bonsai.runtime.BonsaiEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var engine: BonsaiEngine
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var nextMessageId = 1L
    private var isBusy = false
    private var verificationRequest: VerificationRequest? = null

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var modelActions: View
    private lateinit var downloadButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private lateinit var newChatButton: MaterialButton
    private lateinit var thinkingSwitch: SwitchMaterial
    private lateinit var thinkingSummary: TextView
    private lateinit var emptyState: TextView
    private lateinit var promptInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var messagesView: RecyclerView

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                setBusy(true)
                statusText.text = getString(R.string.status_importing)
                progressLabel.text = getString(R.string.progress_importing)
                try {
                    loadAndRunDemo(importModel(uri), runDemo = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    showError(getString(R.string.error_import, error.userMessage()))
                } finally {
                    setBusy(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = BonsaiEngine.getInstance(this)
        bindViews()
        applySystemBarInsets()
        setupMessages()
        restoreMessages(savedInstanceState)
        setupPromptChips()
        setupActions()
        verificationRequest = debugVerificationRequest()
        refreshModelState()
        setBusy(false)

        candidateModelFile()?.let { model ->
            lifecycleScope.launch {
                try {
                    loadAndRunDemo(
                        model,
                        runDemo = verificationRequest == null &&
                            messages.isEmpty() &&
                            !demoWasCompleted(),
                    )
                    verificationRequest?.let { request ->
                        verificationRequest = null
                        thinkingSwitch.isChecked = request.enableThinking
                        runPrompt(
                            request.prompt,
                            maxTokens = if (request.enableThinking) {
                                THINKING_MAX_TOKENS
                            } else {
                                DIRECT_MAX_TOKENS
                            },
                            auto = false,
                            thinkingOverride = request.enableThinking,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    showError(getString(R.string.error_model_load, error.userMessage()))
                } finally {
                    setBusy(false)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(STATE_IDS, messages.map { it.id }.toLongArray())
        outState.putStringArrayList(STATE_ROLES, ArrayList(messages.map { it.role }))
        outState.putStringArrayList(STATE_TEXTS, ArrayList(messages.map { it.text }))
        outState.putStringArrayList(STATE_METAS, ArrayList(messages.map { it.meta.orEmpty() }))
        outState.putStringArrayList(
            STATE_REASONING,
            ArrayList(messages.map { it.reasoning.orEmpty() }),
        )
        outState.putLong(STATE_NEXT_ID, nextMessageId)
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        progressBar = findViewById(R.id.progressBar)
        progressLabel = findViewById(R.id.progressLabel)
        modelActions = findViewById(R.id.modelActions)
        downloadButton = findViewById(R.id.downloadButton)
        importButton = findViewById(R.id.importButton)
        newChatButton = findViewById(R.id.newChatButton)
        thinkingSwitch = findViewById(R.id.thinkingSwitch)
        thinkingSummary = findViewById(R.id.thinkingSummary)
        emptyState = findViewById(R.id.emptyState)
        promptInput = findViewById(R.id.promptInput)
        sendButton = findViewById(R.id.sendButton)
        messagesView = findViewById(R.id.messages)
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = initialLeft + insets.left,
                top = initialTop + insets.top,
                right = initialRight + insets.right,
                bottom = initialBottom + insets.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupMessages() {
        adapter = MessageAdapter()
        messagesView.layoutManager = LinearLayoutManager(this)
        messagesView.adapter = adapter
    }

    private fun restoreMessages(state: Bundle?) {
        if (state == null) return
        val ids = state.getLongArray(STATE_IDS) ?: return
        val roles = state.getStringArrayList(STATE_ROLES) ?: return
        val texts = state.getStringArrayList(STATE_TEXTS) ?: return
        val metas = state.getStringArrayList(STATE_METAS) ?: return
        val reasoning = state.getStringArrayList(STATE_REASONING)
            ?: ArrayList(List(ids.size) { "" })
        val count = minOf(ids.size, roles.size, texts.size, metas.size)
        repeat(count) { index ->
            messages += ChatMessage(
                id = ids[index],
                role = roles[index],
                text = texts[index],
                meta = metas[index].ifBlank { null },
                reasoning = reasoning.getOrNull(index)?.ifBlank { null },
            )
        }
        nextMessageId = state.getLong(STATE_NEXT_ID, (ids.maxOrNull() ?: 0L) + 1L)
        adapter.submitList(messages.toList())
        updateEmptyState()
    }

    private fun setupPromptChips() {
        val chipGroup = findViewById<ChipGroup>(R.id.chipGroup)
        listOf(
            getString(R.string.suggestion_one) to
                "Explain why 1-bit language-model weights are useful on phones.",
            getString(R.string.suggestion_two) to
                "Write a tiny Kotlin function that adds two numbers.",
            getString(R.string.suggestion_three) to
                "Give me three offline assistant demo ideas.",
        ).forEach { (label, prompt) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = false
                isClickable = true
                setChipBackgroundColorResource(R.color.chip_surface)
                setTextColor(getColor(R.color.on_surface))
                setOnClickListener {
                    promptInput.setText(prompt)
                    promptInput.setSelection(prompt.length)
                    promptInput.requestFocus()
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun setupActions() {
        thinkingSwitch.isChecked = getPreferences(MODE_PRIVATE)
            .getBoolean(PREF_THINKING_ENABLED, false)
        updateThinkingSummary()
        thinkingSwitch.setOnCheckedChangeListener { _, enabled ->
            getPreferences(MODE_PRIVATE).edit {
                putBoolean(PREF_THINKING_ENABLED, enabled)
            }
            updateThinkingSummary()
        }

        downloadButton.setOnClickListener {
            lifecycleScope.launch {
                setBusy(true)
                statusText.text = getString(R.string.status_connecting)
                progressLabel.text = getString(R.string.progress_connecting)
                try {
                    loadAndRunDemo(downloadModel(), runDemo = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    showError(getString(R.string.error_download, error.userMessage()))
                } finally {
                    setBusy(false)
                }
            }
        }

        importButton.setOnClickListener {
            modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }

        sendButton.setOnClickListener { submitPrompt() }
        promptInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitPrompt()
                true
            } else {
                false
            }
        }

        newChatButton.setOnClickListener {
            lifecycleScope.launch { startNewChat() }
        }
    }

    private fun submitPrompt() {
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty() || isBusy || engine.state != BonsaiEngine.State.MODEL_READY) return
        promptInput.text.clear()
        val maxTokens = if (thinkingSwitch.isChecked) THINKING_MAX_TOKENS else DIRECT_MAX_TOKENS
        lifecycleScope.launch { runPrompt(prompt, maxTokens = maxTokens, auto = false) }
    }

    private fun refreshModelState() {
        val model = candidateModelFile()
        modelActions.isVisible = model == null
        if (model == null) {
            statusText.text = getString(R.string.status_model_missing)
            detailText.text = getString(R.string.detail_model_missing)
            progressLabel.text = getString(R.string.progress_idle)
        } else {
            statusText.text = getString(R.string.status_model_found)
            detailText.text = getString(
                R.string.detail_model_found,
                formatBytes(model.length()),
                engine.activeBackend,
            )
            progressLabel.text = getString(R.string.progress_ready, engine.activeBackend)
        }
    }

    private suspend fun downloadModel(): File = withContext(Dispatchers.IO) {
        val target = internalModelFile()
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "$MODEL_NAME.part")
        if (partial.length() > MODEL_BYTES) partial.delete()
        var downloaded = if (partial.isFile) partial.length() else 0L
        ensureFreeSpace(target, MODEL_BYTES - downloaded)

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IllegalStateException("Server returned HTTP $responseCode")
            }
            if (responseCode == HttpURLConnection.HTTP_OK && downloaded > 0) {
                partial.delete()
                downloaded = 0L
                ensureFreeSpace(target, MODEL_BYTES)
            }

            withContext(Dispatchers.Main) {
                progressBar.isIndeterminate = false
                progressBar.max = 1000
                progressBar.progress = ((downloaded.toDouble() / MODEL_BYTES) * 1000).toInt()
                statusText.text = getString(R.string.status_downloading)
                updateDownloadProgress(downloaded)
            }

            connection.inputStream.use { input ->
                FileOutputStream(partial, downloaded > 0).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastUiUpdate = downloaded
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastUiUpdate >= PROGRESS_UPDATE_BYTES) {
                            lastUiUpdate = downloaded
                            updateDownloadProgress(downloaded)
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        validateModelFile(partial)
        replaceModelFile(partial, target)
        target
    }

    private suspend fun updateDownloadProgress(bytes: Long) = withContext(Dispatchers.Main) {
        val ratio = (bytes.toDouble() / MODEL_BYTES.toDouble()).coerceIn(0.0, 1.0)
        progressBar.progress = (ratio * progressBar.max).toInt()
        progressLabel.text = getString(
            R.string.progress_download,
            formatBytes(bytes),
            formatBytes(MODEL_BYTES),
        )
    }

    private suspend fun importModel(uri: Uri): File = withContext(Dispatchers.IO) {
        val target = internalModelFile()
        target.parentFile?.mkdirs()
        ensureFreeSpace(target, MODEL_BYTES)
        val partial = File(target.parentFile, "$MODEL_NAME.import")

        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected file could not be opened" }
            FileOutputStream(partial, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                var lastUiUpdate = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (copied - lastUiUpdate >= PROGRESS_UPDATE_BYTES) {
                        lastUiUpdate = copied
                        withContext(Dispatchers.Main) {
                            progressLabel.text = getString(
                                R.string.progress_import,
                                formatBytes(copied),
                                formatBytes(MODEL_BYTES),
                            )
                        }
                    }
                }
                output.fd.sync()
            }
        }

        try {
            validateModelFile(partial)
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
        replaceModelFile(partial, target)
        target
    }

    private suspend fun loadAndRunDemo(model: File, runDemo: Boolean) {
        setBusy(true)
        progressBar.isIndeterminate = true
        statusText.text = getString(R.string.status_loading)
        detailText.text = getString(
            R.string.detail_model_found,
            formatBytes(model.length()),
            engine.activeBackend,
        )
        engine.loadModel(model)
        refreshModelState()
        statusText.text = getString(R.string.status_ready)
        progressLabel.text = getString(R.string.progress_ready, engine.activeBackend)
        setBusy(false)
        if (runDemo) runPrompt(AUTO_PROMPT, maxTokens = 160, auto = true)
    }

    private suspend fun runPrompt(
        prompt: String,
        maxTokens: Int,
        auto: Boolean,
        thinkingOverride: Boolean? = null,
    ) {
        hideKeyboard()
        setBusy(true)
        progressBar.isIndeterminate = true
        val thinkingEnabled = !auto && (thinkingOverride ?: thinkingSwitch.isChecked)
        statusText.text = getString(
            if (thinkingEnabled) R.string.status_reasoning else R.string.status_generating,
        )
        addMessage("You", prompt)
        val assistantIndex = addMessage("Bonsai", getString(R.string.response_waiting))

        val response = StringBuilder()
        var parsedResponse = ParsedModelResponse("", "", reasoningComplete = !thinkingEnabled)
        var emittedPieces = 0
        val startMs = SystemClock.elapsedRealtime()
        try {
            engine.generate(prompt, maxTokens, enableThinking = thinkingEnabled).collect { piece ->
                emittedPieces++
                response.append(piece)
                parsedResponse = ThinkingResponseParser.parse(response.toString(), thinkingEnabled)
                updateMessage(
                    assistantIndex,
                    parsedResponse.answer.ifBlank {
                        getString(
                            if (thinkingEnabled) {
                                R.string.response_waiting_thinking
                            } else {
                                R.string.response_waiting
                            },
                        )
                    },
                    reasoning = parsedResponse.reasoning.ifBlank { null },
                )
            }
            val metrics = engine.performanceMetrics()
            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val tokenCount = if (metrics.generatedTokens > 0) metrics.generatedTokens else emittedPieces
            val tokensPerSecond = if (metrics.generationTokensPerSecond > 0.0) {
                metrics.generationTokensPerSecond
            } else {
                tokenCount * 1000.0 / elapsedMs.toDouble()
            }
            parsedResponse = ThinkingResponseParser.parse(response.toString(), thinkingEnabled)
            val finalText = parsedResponse.answer.ifBlank {
                check(thinkingEnabled && parsedResponse.reasoning.isNotBlank()) {
                    "The model returned an empty response; please retry"
                }
                getString(R.string.response_reasoning_incomplete)
            }
            val meta = resources.getQuantityString(
                R.plurals.response_meta,
                tokenCount,
                tokenCount,
                tokensPerSecond,
            )
            updateMessage(
                assistantIndex,
                finalText,
                meta,
                parsedResponse.reasoning.ifBlank { null },
            )
            statusText.text = getString(R.string.status_ready)
            progressLabel.text = meta
            if (auto) markDemoCompleted()

            val compactResponse = finalText.replace('\n', ' ').take(160)
            Log.i(
                TAG,
                "BONSAI_DEMO_INFERENCE_OK model=$MODEL_NAME emitted_pieces=$emittedPieces " +
                    "backend=${engine.activeBackend.replace(' ', '_')} " +
                    "thinking_enabled=$thinkingEnabled reasoning_chars=${parsedResponse.reasoning.length} " +
                    "generated_tokens=$tokenCount tg_tps=" +
                    String.format(Locale.US, "%.2f", tokensPerSecond) +
                    " response=$compactResponse",
            )
            if (thinkingEnabled) {
                if (parsedResponse.reasoningComplete && parsedResponse.answer.isNotBlank()) {
                    Log.i(
                        TAG,
                        "BONSAI_THINKING_INFERENCE_OK reasoning_chars=" +
                            "${parsedResponse.reasoning.length} answer_chars=" +
                            "${parsedResponse.answer.length}",
                    )
                } else {
                    Log.w(
                        TAG,
                        "BONSAI_THINKING_INFERENCE_INCOMPLETE reasoning_chars=" +
                            parsedResponse.reasoning.length,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateMessage(
                assistantIndex,
                getString(R.string.response_failed),
                reasoning = parsedResponse.reasoning.ifBlank { null },
            )
            showError(getString(R.string.error_inference, error.userMessage()))
            Log.e(TAG, "BONSAI_DEMO_INFERENCE_FAILED", error)
        } finally {
            setBusy(false)
        }
    }

    private suspend fun startNewChat() {
        if (isBusy || engine.state != BonsaiEngine.State.MODEL_READY) return
        setBusy(true)
        try {
            engine.resetConversation()
            messages.clear()
            adapter.submitList(emptyList())
            promptInput.text.clear()
            updateEmptyState()
            statusText.text = getString(R.string.status_ready)
            progressLabel.text = getString(R.string.progress_ready, engine.activeBackend)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showError(getString(R.string.error_reset, error.userMessage()))
        } finally {
            setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        downloadButton.isEnabled = !busy
        importButton.isEnabled = !busy
        val modelReady = engine.state == BonsaiEngine.State.MODEL_READY
        sendButton.isEnabled = !busy && modelReady
        promptInput.isEnabled = !busy && modelReady
        newChatButton.isEnabled = !busy && modelReady && messages.isNotEmpty()
        thinkingSwitch.isEnabled = !busy
        progressBar.isVisible = busy
        if (!busy) progressBar.isIndeterminate = false
    }

    private fun showError(message: String) {
        statusText.text = getString(R.string.status_error)
        detailText.text = message
        progressLabel.text = message
        progressBar.isVisible = false
        setBusy(false)
        Log.e(TAG, message)
    }

    private fun addMessage(
        role: String,
        text: String,
        meta: String? = null,
        reasoning: String? = null,
    ): Int {
        messages += ChatMessage(nextMessageId++, role, text, meta, reasoning)
        adapter.submitList(messages.toList()) {
            messagesView.scrollToPosition(messages.lastIndex)
        }
        updateEmptyState()
        return messages.lastIndex
    }

    private fun updateMessage(
        index: Int,
        text: String,
        meta: String? = null,
        reasoning: String? = null,
    ) {
        if (index !in messages.indices) return
        messages[index] = messages[index].copy(text = text, meta = meta, reasoning = reasoning)
        adapter.submitList(messages.toList()) {
            messagesView.scrollToPosition(index)
        }
    }

    private fun updateEmptyState() {
        emptyState.isVisible = messages.isEmpty()
        newChatButton.isVisible = messages.isNotEmpty()
        if (::engine.isInitialized) {
            newChatButton.isEnabled = !isBusy && engine.state == BonsaiEngine.State.MODEL_READY
        }
    }

    private fun internalModelFile() = File(File(filesDir, "models"), MODEL_NAME)

    private fun externalModelFile(): File? {
        val root = getExternalFilesDir(null) ?: return null
        return File(File(root, "models"), MODEL_NAME)
    }

    private fun candidateModelFile(): File? {
        return listOfNotNull(internalModelFile(), externalModelFile())
            .firstOrNull { isValidModelFile(it) }
    }

    private fun isValidModelFile(file: File): Boolean {
        if (!file.isFile || !file.canRead() || file.length() != MODEL_BYTES) return false
        return runCatching {
            FileInputStream(file).use { input ->
                val magic = ByteArray(GGUF_MAGIC.size)
                input.read(magic) == magic.size && magic.contentEquals(GGUF_MAGIC)
            }
        }.getOrDefault(false)
    }

    private fun validateModelFile(file: File) {
        val actualBytes = file.length()
        require(actualBytes == MODEL_BYTES) {
            "Model size is ${formatBytes(actualBytes)}; expected ${formatBytes(MODEL_BYTES)}"
        }
        require(isValidModelFile(file)) { "The selected file is not a valid GGUF model" }
    }

    private fun replaceModelFile(source: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("The previous model file could not be replaced")
        }
        check(source.renameTo(target)) { "The model file could not be finalized" }
    }

    @SuppressLint("UsableSpace")
    private fun ensureFreeSpace(target: File, requiredBytes: Long) {
        // Use immediately writable space; do not evict unrelated app caches to
        // make room for a model download.
        val usableBytes = target.parentFile?.usableSpace ?: filesDir.usableSpace
        require(usableBytes >= requiredBytes + STORAGE_HEADROOM_BYTES) {
            "Not enough free space. ${formatBytes(requiredBytes + STORAGE_HEADROOM_BYTES)} is required"
        }
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(promptInput.windowToken, 0)
        promptInput.clearFocus()
    }

    private fun updateThinkingSummary() {
        thinkingSummary.setText(
            if (thinkingSwitch.isChecked) {
                R.string.thinking_mode_summary_on
            } else {
                R.string.thinking_mode_summary_off
            },
        )
    }

    private fun debugVerificationRequest(): VerificationRequest? {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val prompt = intent.getStringExtra(EXTRA_VERIFY_PROMPT)?.trim().orEmpty()
        if (prompt.isEmpty()) return null
        return VerificationRequest(
            prompt = prompt,
            enableThinking = intent.getBooleanExtra(EXTRA_VERIFY_THINKING, false),
        )
    }

    private fun demoWasCompleted(): Boolean {
        return getPreferences(MODE_PRIVATE).getBoolean(PREF_DEMO_DONE, false)
    }

    private fun markDemoCompleted() {
        getPreferences(MODE_PRIVATE).edit {
            putBoolean(PREF_DEMO_DONE, true)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f GiB", gib)
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() }
        ?: javaClass.simpleName

    companion object {
        private const val TAG = "BonsaiDemo"
        private const val MODEL_NAME = "Bonsai-8B-Q1_0.gguf"
        private const val MODEL_BYTES = 1_158_654_496L
        private const val PROGRESS_UPDATE_BYTES = 4L * 1024L * 1024L
        private const val STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
        private const val MODEL_URL =
            "https://huggingface.co/prism-ml/Bonsai-8B-gguf/resolve/main/Bonsai-8B-Q1_0.gguf"
        private const val AUTO_PROMPT =
            "In two short sentences, confirm you are running locally on this Android device and name one benefit of a 1-bit 8B language model."
        private const val PREF_DEMO_DONE = "demo_done"
        private const val PREF_THINKING_ENABLED = "thinking_enabled"
        private const val DIRECT_MAX_TOKENS = 160
        private const val THINKING_MAX_TOKENS = 320
        private const val STATE_IDS = "message_ids"
        private const val STATE_ROLES = "message_roles"
        private const val STATE_TEXTS = "message_texts"
        private const val STATE_METAS = "message_metas"
        private const val STATE_REASONING = "message_reasoning"
        private const val STATE_NEXT_ID = "next_message_id"
        private const val EXTRA_VERIFY_PROMPT = "com.prismml.bonsai.extra.VERIFY_PROMPT"
        private const val EXTRA_VERIFY_THINKING = "com.prismml.bonsai.extra.VERIFY_THINKING"
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    }

    private data class VerificationRequest(
        val prompt: String,
        val enableThinking: Boolean,
    )
}
