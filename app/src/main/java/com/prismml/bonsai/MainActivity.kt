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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.prismml.bonsai.runtime.BonsaiEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var engine: BonsaiEngine
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var nextMessageId = 1L
    private var isBusy = false
    private var modelLoadFailed = false
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
    private lateinit var chipGroup: ChipGroup
    private lateinit var emptyState: TextView
    private lateinit var promptInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var messagesView: RecyclerView

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                setBusy(true)
                progressBar.isIndeterminate = true
                statusText.text = getString(R.string.status_importing)
                progressLabel.text = getString(R.string.progress_importing)
                try {
                    val model = try {
                        importModel(uri)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        showError(getString(R.string.error_import, error.userMessage()))
                        return@launch
                    }
                    try {
                        loadAndRunDemo(model, runDemo = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        showModelLoadError(error)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
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
                    showModelLoadError(error)
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
        chipGroup = findViewById(R.id.chipGroup)
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
        messagesView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messagesView.itemAnimator = null
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

        downloadButton.setOnClickListener { showDownloadConfirmation() }

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

    private fun showDownloadConfirmation() {
        if (isBusy) return
        val partialBytes = partialDownloadBytes()
        val isResume = partialBytes > 0L
        val isCompletePartial = partialBytes == MODEL_BYTES
        val title = when {
            isCompletePartial -> R.string.download_dialog_title_verify
            isResume -> R.string.download_dialog_title_resume
            else -> R.string.download_dialog_title
        }
        val message = when {
            isCompletePartial -> getString(R.string.download_dialog_message_verify)
            isResume -> getString(
                R.string.download_dialog_message_resume,
                formatBytes(partialBytes),
                formatBytes(MODEL_BYTES),
                formatBytes(MODEL_BYTES - partialBytes),
            )
            else -> getString(R.string.download_dialog_message, formatBytes(MODEL_BYTES))
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                when {
                    isCompletePartial -> R.string.action_verify
                    isResume -> R.string.action_resume
                    else -> R.string.action_download_confirm
                },
            ) { _, _ -> startModelDownload() }
        if (isResume) {
            dialog.setNeutralButton(R.string.action_restart_download) { _, _ ->
                startModelDownload(restart = true)
            }
        }
        dialog.show()
    }

    private fun startModelDownload(restart: Boolean = false) {
        lifecycleScope.launch {
            setBusy(true)
            progressBar.isIndeterminate = true
            statusText.text = getString(R.string.status_connecting)
            progressLabel.text = getString(R.string.progress_connecting)
            try {
                val model = try {
                    downloadModel(restart)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    showError(getString(R.string.error_download, error.userMessage()))
                    updateDownloadAction()
                    return@launch
                }
                try {
                    loadAndRunDemo(model, runDemo = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    showModelLoadError(error)
                }
            } finally {
                setBusy(false)
            }
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
            val partialBytes = partialDownloadBytes()
            if (partialBytes > 0L) {
                statusText.text = getString(R.string.status_model_partial)
                detailText.text = getString(
                    R.string.detail_model_partial,
                    formatBytes(partialBytes),
                    formatBytes(MODEL_BYTES),
                )
                progressLabel.text = getString(
                    if (partialBytes == MODEL_BYTES) {
                        R.string.progress_partial_complete
                    } else {
                        R.string.progress_partial
                    },
                )
            } else {
                statusText.text = getString(R.string.status_model_missing)
                detailText.text = getString(R.string.detail_model_missing)
                progressLabel.text = getString(R.string.progress_idle)
            }
            updateDownloadAction()
        } else {
            downloadButton.setText(R.string.action_download)
            statusText.text = getString(R.string.status_model_found)
            detailText.text = getString(
                R.string.detail_model_found,
                formatBytes(model.length()),
                engine.activeBackend,
            )
            progressLabel.text = getString(R.string.progress_ready, engine.activeBackend)
        }
        updateEmptyState()
    }

    private suspend fun downloadModel(restart: Boolean): File =
        modelAcquisitionMutex.withLock { downloadModelLocked(restart) }

    private suspend fun downloadModelLocked(restart: Boolean): File = withContext(Dispatchers.IO) {
        val target = internalModelFile()
        target.parentFile?.mkdirs()
        val partial = partialModelFile()
        if (restart) deletePartialFile(partial)
        if (partial.length() > MODEL_BYTES) deletePartialFile(partial)

        if (partial.isFile && partial.length() == MODEL_BYTES) {
            try {
                showVerifyingModel()
                validateModelFile(partial)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                deletePartialFile(partial)
            }
            if (partial.isFile) {
                replaceModelFile(partial, target)
                return@withContext target
            }
        }

        var downloaded = if (partial.isFile) partial.length() else 0L
        ensureFreeSpace(target, MODEL_BYTES - downloaded)

        var connection = openModelDownloadConnection(downloaded)
        try {
            var responseCode: Int
            while (true) {
                responseCode = connection.responseCode
                check(connection.url.protocol.equals("https", ignoreCase = true)) {
                    "The model download was redirected to an insecure connection"
                }
                val invalidResume = downloaded > 0L && (
                    responseCode == HTTP_RANGE_NOT_SATISFIABLE ||
                        responseCode == HttpURLConnection.HTTP_PARTIAL &&
                        !ModelDownloadProtocol.isExpectedContentRange(
                            connection.getHeaderField("Content-Range"),
                            downloaded,
                            MODEL_BYTES,
                        )
                    )
                if (!invalidResume) break

                connection.disconnect()
                deletePartialFile(partial)
                downloaded = 0L
                ensureFreeSpace(target, MODEL_BYTES)
                connection = openModelDownloadConnection(downloaded)
            }

            if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IllegalStateException("Server returned HTTP $responseCode")
            }
            if (responseCode == HttpURLConnection.HTTP_OK && downloaded > 0) {
                deletePartialFile(partial)
                downloaded = 0L
                ensureFreeSpace(target, MODEL_BYTES)
            }
            if (
                responseCode == HttpURLConnection.HTTP_PARTIAL &&
                !ModelDownloadProtocol.isExpectedContentRange(
                    connection.getHeaderField("Content-Range"),
                    downloaded,
                    MODEL_BYTES,
                )
            ) {
                throw IllegalStateException("Server returned an unexpected download range")
            }

            val expectedResponseBytes = MODEL_BYTES - downloaded
            val declaredResponseBytes = connection.contentLengthLong
            if (declaredResponseBytes >= 0L && declaredResponseBytes != expectedResponseBytes) {
                throw IllegalStateException(
                    "Server returned ${formatBytes(declaredResponseBytes)}; " +
                        "expected ${formatBytes(expectedResponseBytes)}",
                )
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
                        if (read.toLong() > MODEL_BYTES - downloaded) {
                            throw IllegalStateException("Server sent more data than the expected model size")
                        }
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

        updateDownloadProgress(downloaded)
        if (downloaded != MODEL_BYTES) {
            throw IllegalStateException(
                "Download stopped at ${formatBytes(downloaded)}; tap Resume download to continue",
            )
        }
        showVerifyingModel()
        try {
            validateModelFile(partial)
        } catch (error: IllegalArgumentException) {
            deletePartialFile(partial)
            throw error
        }
        replaceModelFile(partial, target)
        target
    }

    private fun openModelDownloadConnection(offset: Long): HttpURLConnection {
        return (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
    }

    private fun deletePartialFile(file: File) {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("The incomplete model download could not be reset")
        }
    }

    private suspend fun showVerifyingModel() = withContext(Dispatchers.Main) {
        progressBar.isIndeterminate = true
        statusText.text = getString(R.string.status_verifying)
        progressLabel.text = getString(R.string.progress_verifying)
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

    private suspend fun importModel(uri: Uri): File =
        modelAcquisitionMutex.withLock { importModelLocked(uri) }

    private suspend fun importModelLocked(uri: Uri): File = withContext(Dispatchers.IO) {
        val target = internalModelFile()
        target.parentFile?.mkdirs()
        ensureFreeSpace(target, MODEL_BYTES)
        val partial = File(target.parentFile, "$MODEL_NAME.import")

        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected file could not be opened" }
                FileOutputStream(partial, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastUiUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read.toLong() > MODEL_BYTES - copied) {
                            throw IllegalArgumentException(
                                "The selected file is larger than the official Bonsai model",
                            )
                        }
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
            showVerifyingModel()
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
        modelLoadFailed = false
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
        thinkingSwitch.isEnabled = !busy && modelReady
        for (index in 0 until chipGroup.childCount) {
            chipGroup.getChildAt(index).isEnabled = !busy && modelReady
        }
        promptInput.setHint(
            if (modelReady) R.string.prompt_hint else R.string.prompt_hint_model_missing,
        )
        updateThinkingSummary()
        updateEmptyState()
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

    private fun showModelLoadError(error: Throwable) {
        modelLoadFailed = true
        showError(getString(R.string.error_model_load, error.userMessage()))
        modelActions.isVisible = true
        downloadButton.setText(R.string.action_replace_download)
        importButton.setText(R.string.action_replace_import)
        emptyState.setText(R.string.chat_empty_model_error)
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
        val keepAtBottom = !messagesView.canScrollVertically(1)
        messages[index] = messages[index].copy(text = text, meta = meta, reasoning = reasoning)
        adapter.submitList(messages.toList()) {
            if (keepAtBottom && messagesView.canScrollVertically(1)) {
                messagesView.scrollToPosition(index)
            }
        }
    }

    private fun updateEmptyState() {
        emptyState.isVisible = messages.isEmpty()
        newChatButton.isVisible = messages.isNotEmpty()
        if (::engine.isInitialized) {
            val modelReady = engine.state == BonsaiEngine.State.MODEL_READY
            newChatButton.isEnabled = !isBusy && modelReady
            if (messages.isEmpty()) {
                emptyState.setText(
                    when {
                        modelLoadFailed -> R.string.chat_empty_model_error
                        modelReady -> R.string.chat_empty
                        else -> R.string.chat_empty_model_missing
                    },
                )
            }
        }
    }

    private fun internalModelFile() = File(File(filesDir, "models"), MODEL_NAME)

    private fun partialModelFile() = File(internalModelFile().parentFile, "$MODEL_NAME.part")

    private fun partialDownloadBytes(): Long {
        val partial = partialModelFile()
        return partial.length().takeIf { partial.isFile && it in 1L..MODEL_BYTES } ?: 0L
    }

    private fun updateDownloadAction() {
        downloadButton.setText(
            when (partialDownloadBytes()) {
                MODEL_BYTES -> R.string.action_verify_download
                0L -> R.string.action_download
                else -> R.string.action_resume
            },
        )
        importButton.setText(R.string.action_import)
    }

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
        require(sha256(file).equals(MODEL_SHA256, ignoreCase = true)) {
            "Model checksum does not match the official Bonsai artifact"
        }
    }

    private fun replaceModelFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
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
            when {
                engine.state != BonsaiEngine.State.MODEL_READY -> {
                    R.string.thinking_mode_summary_unavailable
                }
                thinkingSwitch.isChecked -> R.string.thinking_mode_summary_on
                else -> R.string.thinking_mode_summary_off
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
        return if (bytes >= GIB_BYTES) {
            String.format(Locale.US, "%.2f GiB", bytes.toDouble() / GIB_BYTES)
        } else {
            String.format(Locale.US, "%.0f MiB", bytes.toDouble() / MIB_BYTES)
        }
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() }
        ?: javaClass.simpleName

    companion object {
        private val modelAcquisitionMutex = Mutex()
        private const val TAG = "BonsaiDemo"
        private const val MODEL_NAME = "Bonsai-8B-Q1_0.gguf"
        private const val MODEL_BYTES = 1_158_654_496L
        private const val MODEL_SHA256 =
            "284a335aa3fb2ced3b1b01fcb40b08aa783e3b70832767f0dd2e3fdfa134bd54"
        private const val HASH_BUFFER_BYTES = 1024 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val MIB_BYTES = 1024.0 * 1024.0
        private const val GIB_BYTES = 1024.0 * 1024.0 * 1024.0
        private const val PROGRESS_UPDATE_BYTES = 4L * 1024L * 1024L
        private const val STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
        private const val MODEL_URL =
            "https://huggingface.co/prism-ml/Bonsai-8B-gguf/resolve/" +
                "48516770dd04643643e9f9019a2a349cf26c5dbd/Bonsai-8B-Q1_0.gguf"
        private const val AUTO_PROMPT =
            "In two short sentences, confirm you are running locally on this Android device and name one benefit of a 1-bit 8B language model."
        private const val PREF_DEMO_DONE = "demo_done"
        private const val PREF_THINKING_ENABLED = "thinking_enabled"
        private const val DIRECT_MAX_TOKENS = 160
        private const val THINKING_MAX_TOKENS = 512
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
