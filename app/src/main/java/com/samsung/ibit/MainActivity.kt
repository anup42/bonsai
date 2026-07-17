package com.samsung.ibit

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.prismml.bonsai.runtime.BonsaiEngine
import io.noties.markwon.Markwon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private var modelOperationBusy = false
    private var inferenceRunning = false
    private var downloadRunning = false
    private val isBusy: Boolean
        get() = modelOperationBusy || inferenceRunning || downloadRunning
    private var activeRequestId: Long? = null
    private var activeAssistantMessageId: Long? = null
    private var modelLoadFailed = false
    private var downloadCompletionLoading = false
    private var verificationRequest: VerificationRequest? = null
    private var selectedModel = BonsaiModels.default
    private var importTargetModel = BonsaiModels.default

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var modelActions: View
    private lateinit var downloadButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private lateinit var modelsButton: MaterialButton
    private lateinit var newChatButton: MaterialButton
    private lateinit var thinkingSwitch: SwitchMaterial
    private lateinit var thinkingSummary: TextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var emptyState: TextView
    private lateinit var promptInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var messagesView: RecyclerView
    private var pendingNotificationAction: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingNotificationAction?.let { action ->
            pendingNotificationAction = null
            action()
        }
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val preferences = getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
        val targetModel = BonsaiModels.find(
            preferences.getString(PREF_IMPORT_TARGET_MODEL, null),
        ) ?: importTargetModel
        preferences.edit { remove(PREF_IMPORT_TARGET_MODEL) }
        if (uri != null) {
            lifecycleScope.launch {
                setBusy(true)
                progressBar.isIndeterminate = true
                statusText.text = getString(R.string.status_importing, targetModel.displayName)
                progressLabel.text = getString(R.string.progress_importing)
                try {
                    val model = try {
                        importModel(uri, targetModel)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        showError(getString(R.string.error_import, error.userMessage()))
                        return@launch
                    }
                    try {
                        loadAndRunDemo(targetModel, model, runDemo = true)
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
        selectedModel = restoreSelectedModel()
        importTargetModel = selectedModel
        bindViews()
        applySystemBarInsets()
        setupMessages()
        restoreMessages(savedInstanceState)
        setupPromptChips()
        setupActions()
        setupInferenceObserver()
        setupDownloadObserver()
        verificationRequest = debugVerificationRequest()
        refreshModelState()
        setBusy(false)
        renderInferenceState(InferenceSession.state.value)
        renderDownloadState(ModelDownloadSession.state.value)

        if (!downloadCompletionLoading) candidateModelFile(selectedModel)?.let { modelFile ->
            lifecycleScope.launch {
                try {
                    loadAndRunDemo(
                        selectedModel,
                        modelFile,
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
        activeRequestId?.let { outState.putLong(STATE_ACTIVE_REQUEST_ID, it) }
        activeAssistantMessageId?.let {
            outState.putLong(STATE_ACTIVE_ASSISTANT_MESSAGE_ID, it)
        }
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        progressBar = findViewById(R.id.progressBar)
        progressLabel = findViewById(R.id.progressLabel)
        modelActions = findViewById(R.id.modelActions)
        downloadButton = findViewById(R.id.downloadButton)
        importButton = findViewById(R.id.importButton)
        modelsButton = findViewById(R.id.modelsButton)
        newChatButton = findViewById(R.id.newChatButton)
        thinkingSwitch = findViewById(R.id.thinkingSwitch)
        thinkingSummary = findViewById(R.id.thinkingSummary)
        chipGroup = findViewById(R.id.chipGroup)
        emptyState = findViewById(R.id.emptyState)
        promptInput = findViewById(R.id.promptInput)
        sendButton = findViewById(R.id.sendButton)
        stopButton = findViewById(R.id.stopButton)
        messagesView = findViewById(R.id.messages)
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val systemInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = initialLeft + systemInsets.left,
                top = initialTop + systemInsets.top,
                right = initialRight + systemInsets.right,
                bottom = initialBottom + maxOf(systemInsets.bottom, imeInsets.bottom),
            )
            if (imeInsets.bottom > 0 && messages.isNotEmpty()) {
                messagesView.post { messagesView.scrollToPosition(messages.lastIndex) }
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupMessages() {
        adapter = MessageAdapter(Markwon.create(this))
        messagesView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messagesView.itemAnimator = null
        messagesView.adapter = adapter
    }

    private fun restoreMessages(state: Bundle?) {
        if (state == null) return
        activeRequestId = state.getLong(STATE_ACTIVE_REQUEST_ID, 0L).takeIf { it != 0L }
        activeAssistantMessageId = state.getLong(
            STATE_ACTIVE_ASSISTANT_MESSAGE_ID,
            0L,
        ).takeIf { it != 0L }
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
                isStreaming = ids[index] == activeAssistantMessageId &&
                    InferenceSession.state.value.phase == InferencePhase.RUNNING,
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
        thinkingSwitch.isChecked = getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
            .getBoolean(PREF_THINKING_ENABLED, false)
        updateThinkingSummary()
        thinkingSwitch.setOnCheckedChangeListener { _, enabled ->
            getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE).edit {
                putBoolean(PREF_THINKING_ENABLED, enabled)
            }
            updateThinkingSummary()
        }

        downloadButton.setOnClickListener {
            if (downloadRunning) {
                downloadButton.isEnabled = false
                statusText.text = getString(R.string.status_stopping_download)
                progressLabel.text = getString(R.string.progress_stopping_download)
                ModelDownloadService.stopDownload(this)
            } else {
                showDownloadConfirmation(selectedModel)
            }
        }

        importButton.setOnClickListener {
            launchModelPicker(selectedModel)
        }

        modelsButton.setOnClickListener { showModelCatalog() }

        sendButton.setOnClickListener { submitPrompt() }
        stopButton.setOnClickListener {
            stopButton.isEnabled = false
            statusText.text = getString(R.string.status_stopping)
            progressLabel.text = getString(R.string.progress_stopping)
            InferenceService.stopGeneration(this)
        }
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

    private fun showModelCatalog() {
        if (isBusy) return
        val labels = BonsaiModels.all.map { model ->
            val partialBytes = partialDownloadBytes(model)
            val state = when {
                model.id == selectedModel.id && engine.state == BonsaiEngine.State.MODEL_READY -> {
                    getString(R.string.model_state_active)
                }
                candidateModelFile(model) != null -> getString(R.string.model_state_downloaded)
                partialBytes > 0L -> getString(
                    R.string.model_state_resume,
                    ((partialBytes * 100L) / model.fileBytes).toInt(),
                )
                else -> getString(R.string.model_state_download)
            }
            "${model.displayName}  ·  ${formatBytes(model.fileBytes)}\n" +
                "${model.formatLabel}  ·  $state"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.models_dialog_title)
            .setItems(labels) { _, index -> handleModelSelection(BonsaiModels.all[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleModelSelection(model: BonsaiModel) {
        val modelFile = candidateModelFile(model)
        if (modelFile == null) {
            showDownloadConfirmation(model)
            return
        }

        val isActive = model.id == selectedModel.id &&
            engine.state == BonsaiEngine.State.MODEL_READY
        if (isActive) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.model_active_title, model.displayName))
                .setMessage(
                    getString(
                        R.string.model_active_message,
                        model.formatLabel,
                        engine.activeBackend,
                    ),
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.model_use_title, model.displayName))
            .setMessage(
                getString(R.string.model_details, model.formatLabel, formatBytes(model.fileBytes),
                    getString(R.string.model_use_message)),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_delete_model) { _, _ -> confirmDeleteModel(model) }
            .setPositiveButton(R.string.action_use_model) { _, _ -> switchToModel(model, modelFile) }
            .show()
    }

    private fun confirmDeleteModel(model: BonsaiModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.model_delete_title, model.displayName))
            .setMessage(R.string.model_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete_model) { _, _ ->
                try {
                    deleteModelFiles(model)
                    refreshModelState()
                } catch (error: Throwable) {
                    showError(error.userMessage())
                }
            }
            .show()
    }

    private fun deleteModelFiles(model: BonsaiModel) {
        listOfNotNull(
            internalModelFile(model),
            externalModelFile(model),
            partialModelFile(model),
            importModelFile(model),
        ).distinctBy { it.absolutePath }.forEach { file ->
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Could not delete ${model.displayName}")
            }
        }
        getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE).edit {
            remove(verificationPreference(model))
        }
    }

    private fun switchToModel(model: BonsaiModel, modelFile: File) {
        lifecycleScope.launch {
            selectedModel = model
            saveSelectedModel()
            modelLoadFailed = false
            messages.clear()
            adapter.submitList(emptyList())
            updateEmptyState()
            try {
                loadAndRunDemo(model, modelFile, runDemo = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showModelLoadError(error)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showDownloadConfirmation(model: BonsaiModel) {
        if (isBusy) return
        val partialBytes = partialDownloadBytes(model)
        val isResume = partialBytes > 0L
        val isCompletePartial = partialBytes == model.fileBytes
        val title = when {
            isCompletePartial -> getString(R.string.download_dialog_title_verify, model.displayName)
            isResume -> getString(R.string.download_dialog_title_resume, model.displayName)
            else -> getString(R.string.download_dialog_title, model.displayName)
        }
        var message = when {
            isCompletePartial -> getString(R.string.download_dialog_message_verify)
            isResume -> getString(
                R.string.download_dialog_message_resume,
                formatBytes(partialBytes),
                formatBytes(model.fileBytes),
                formatBytes(model.fileBytes - partialBytes),
            )
            else -> getString(R.string.download_dialog_message, formatBytes(model.fileBytes))
        }
        if (model.experimentalOnAndroid) {
            message += "\n\n${getString(R.string.model_android_warning)}"
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
            ) { _, _ -> startModelDownload(model) }
        if (isResume) {
            dialog.setNeutralButton(R.string.action_restart_download) { _, _ ->
                startModelDownload(model, restart = true)
            }
        } else {
            dialog.setNeutralButton(R.string.action_import) { _, _ ->
                launchModelPicker(model)
            }
        }
        dialog.show()
    }

    private fun startModelDownload(model: BonsaiModel, restart: Boolean = false) {
        runWithNotificationPermission {
            selectedModel = model
            saveSelectedModel()
            downloadRunning = true
            progressBar.isIndeterminate = true
            statusText.text = getString(R.string.status_connecting)
            progressLabel.text = getString(R.string.progress_connecting)
            renderBusyState()
            try {
                ModelDownloadService.startDownload(
                    context = this,
                    requestId = SystemClock.elapsedRealtimeNanos(),
                    model = model,
                    restart = restart,
                )
            } catch (error: Throwable) {
                downloadRunning = false
                showError(getString(R.string.error_download, error.userMessage()))
                updateDownloadAction(selectedModel)
            }
        }
    }

    private fun launchModelPicker(model: BonsaiModel) {
        importTargetModel = model
        getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE).edit {
            putString(PREF_IMPORT_TARGET_MODEL, model.id)
        }
        modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
    }

    private fun submitPrompt() {
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty() || isBusy || engine.state != BonsaiEngine.State.MODEL_READY) return
        promptInput.text.clear()
        val maxTokens = if (thinkingSwitch.isChecked) THINKING_MAX_TOKENS else DIRECT_MAX_TOKENS
        runPrompt(prompt, maxTokens = maxTokens, auto = false)
    }

    private fun refreshModelState() {
        val modelFile = candidateModelFile(selectedModel)
        modelActions.isVisible = modelFile == null || modelLoadFailed
        if (modelFile == null) {
            val partialBytes = partialDownloadBytes(selectedModel)
            if (partialBytes > 0L) {
                statusText.text = getString(
                    R.string.status_model_partial,
                    selectedModel.displayName,
                )
                detailText.text = getString(
                    R.string.detail_model_partial,
                    formatBytes(partialBytes),
                    formatBytes(selectedModel.fileBytes),
                )
                progressLabel.text = getString(
                    if (partialBytes == selectedModel.fileBytes) {
                        R.string.progress_partial_complete
                    } else {
                        R.string.progress_partial
                    },
                )
            } else {
                statusText.text = getString(
                    R.string.status_model_missing,
                    selectedModel.displayName,
                )
                detailText.text = getString(R.string.detail_model_missing)
                progressLabel.text = getString(
                    R.string.progress_idle,
                    formatBytes(selectedModel.fileBytes),
                )
            }
            updateDownloadAction(selectedModel)
        } else {
            downloadButton.text = getString(
                R.string.action_download,
                formatBytes(selectedModel.fileBytes),
            )
            statusText.text = getString(R.string.status_model_found, selectedModel.displayName)
            detailText.text = getString(
                R.string.detail_model_found,
                selectedModel.formatLabel,
                formatBytes(modelFile.length()),
                engine.activeBackend,
            )
            progressLabel.text = getString(R.string.progress_ready, engine.activeBackend)
        }
        updateEmptyState()
    }

    private suspend fun showVerifyingModel() = withContext(Dispatchers.Main) {
        progressBar.isIndeterminate = true
        statusText.text = getString(R.string.status_verifying)
        progressLabel.text = getString(R.string.progress_verifying)
    }

    private suspend fun importModel(uri: Uri, model: BonsaiModel): File =
        modelAcquisitionMutex.withLock { importModelLocked(uri, model) }

    private suspend fun importModelLocked(uri: Uri, model: BonsaiModel): File =
        withContext(Dispatchers.IO) {
        val target = internalModelFile(model)
        target.parentFile?.mkdirs()
        ensureFreeSpace(target, model.fileBytes)
        val partial = importModelFile(model)

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
                        if (read.toLong() > model.fileBytes - copied) {
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
                                    formatBytes(model.fileBytes),
                                )
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            showVerifyingModel()
            validateModelFile(model, partial)
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
        replaceModelFile(partial, target)
        rememberModelVerification(model, target)
        target
    }

    private suspend fun loadAndRunDemo(
        model: BonsaiModel,
        modelFile: File,
        runDemo: Boolean,
    ) {
        setBusy(true)
        progressBar.isIndeterminate = true
        statusText.text = getString(R.string.status_loading, model.displayName)
        detailText.text = getString(
            R.string.detail_model_found,
            model.formatLabel,
            formatBytes(modelFile.length()),
            engine.activeBackend,
        )
        ensureModelVerified(model, modelFile)
        engine.loadModel(modelFile)
        selectedModel = model
        saveSelectedModel()
        modelLoadFailed = false
        refreshModelState()
        statusText.text = getString(R.string.status_ready)
        progressLabel.text = getString(R.string.progress_ready, engine.activeBackend)
        setBusy(false)
        if (runDemo) runPrompt(AUTO_PROMPT, maxTokens = 160, auto = true)
    }

    private fun runPrompt(
        prompt: String,
        maxTokens: Int,
        auto: Boolean,
        thinkingOverride: Boolean? = null,
    ) {
        runWithNotificationPermission {
            runPromptWithNotificationPermission(
                prompt = prompt,
                maxTokens = maxTokens,
                auto = auto,
                thinkingOverride = thinkingOverride,
            )
        }
    }

    private fun runPromptWithNotificationPermission(
        prompt: String,
        maxTokens: Int,
        auto: Boolean,
        thinkingOverride: Boolean?,
    ) {
        if (isBusy || engine.state != BonsaiEngine.State.MODEL_READY) return
        hideKeyboard()
        val requestId = SystemClock.elapsedRealtimeNanos()
        progressBar.isIndeterminate = true
        val thinkingEnabled = !auto && (thinkingOverride ?: thinkingSwitch.isChecked)
        statusText.text = getString(
            if (thinkingEnabled) R.string.status_reasoning else R.string.status_generating,
        )
        addMessage("You", prompt)
        val assistantIndex = addMessage(
            "iBit",
            getString(R.string.response_waiting),
            isStreaming = true,
        )
        activeRequestId = requestId
        activeAssistantMessageId = messages[assistantIndex].id
        inferenceRunning = true
        renderBusyState()
        try {
            InferenceService.startGeneration(
                context = this,
                requestId = requestId,
                prompt = prompt,
                maxTokens = maxTokens,
                thinkingEnabled = thinkingEnabled,
                isAutomaticDemo = auto,
            )
        } catch (error: Throwable) {
            updateMessage(
                assistantIndex,
                getString(R.string.response_failed),
            )
            inferenceRunning = false
            renderBusyState()
            showError(getString(R.string.error_inference, error.userMessage()))
        }
    }

    private fun setupInferenceObserver() {
        lifecycleScope.launch {
            InferenceSession.state.collect { snapshot ->
                renderInferenceState(snapshot)
            }
        }
    }

    private fun setupDownloadObserver() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloadSession.state.collect { snapshot ->
                    renderDownloadState(snapshot)
                }
            }
        }
    }

    private fun renderDownloadState(snapshot: ModelDownloadSnapshot) {
        if (snapshot.phase == ModelDownloadPhase.IDLE) {
            downloadRunning = false
            renderBusyState()
            return
        }

        val model = BonsaiModels.find(snapshot.modelId)
        if (model == null) {
            downloadRunning = false
            ModelDownloadSession.clearFinished(snapshot.requestId)
            showError(getString(R.string.error_download, "Unknown model"))
            return
        }
        if (selectedModel.id != model.id) {
            selectedModel = model
            saveSelectedModel()
        }

        when (snapshot.phase) {
            ModelDownloadPhase.CONNECTING -> {
                downloadRunning = true
                modelActions.isVisible = true
                progressBar.isIndeterminate = true
                statusText.text = getString(R.string.status_connecting)
                progressLabel.text = getString(R.string.progress_connecting)
            }

            ModelDownloadPhase.DOWNLOADING -> {
                downloadRunning = true
                modelActions.isVisible = true
                progressBar.isIndeterminate = false
                progressBar.max = DOWNLOAD_PROGRESS_MAX
                val ratio = (
                    snapshot.downloadedBytes.toDouble() /
                        snapshot.totalBytes.coerceAtLeast(1L).toDouble()
                    ).coerceIn(0.0, 1.0)
                progressBar.progress = (ratio * DOWNLOAD_PROGRESS_MAX).toInt()
                statusText.text = getString(
                    R.string.status_downloading_background,
                    model.displayName,
                )
                progressLabel.text = getString(
                    R.string.progress_background_download,
                    formatBytes(snapshot.downloadedBytes),
                    formatBytes(snapshot.totalBytes),
                )
            }

            ModelDownloadPhase.VERIFYING -> {
                downloadRunning = true
                modelActions.isVisible = true
                progressBar.isIndeterminate = true
                statusText.text = getString(R.string.status_verifying)
                progressLabel.text = getString(R.string.progress_verifying)
            }

            ModelDownloadPhase.COMPLETED -> {
                if (downloadCompletionLoading) return
                downloadRunning = false
                downloadCompletionLoading = true
                ModelDownloadSession.clearFinished(snapshot.requestId)
                val downloadedFile = snapshot.filePath?.let(::File)
                if (downloadedFile == null || !downloadedFile.isFile) {
                    downloadCompletionLoading = false
                    showError(
                        getString(
                            R.string.error_download,
                            "The verified model file is unavailable",
                        ),
                    )
                    return
                }
                setBusy(true)
                lifecycleScope.launch {
                    try {
                        loadAndRunDemo(model, downloadedFile, runDemo = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        showModelLoadError(error)
                    } finally {
                        downloadCompletionLoading = false
                        setBusy(false)
                    }
                }
            }

            ModelDownloadPhase.STOPPED -> {
                downloadRunning = false
                ModelDownloadSession.clearFinished(snapshot.requestId)
                refreshModelState()
                statusText.text = getString(R.string.status_download_stopped, model.displayName)
                progressLabel.text = getString(R.string.progress_download_stopped)
            }

            ModelDownloadPhase.FAILED -> {
                downloadRunning = false
                ModelDownloadSession.clearFinished(snapshot.requestId)
                refreshModelState()
                showError(
                    getString(
                        R.string.error_download,
                        snapshot.error ?: getString(R.string.download_error_unknown),
                    ),
                )
            }

            ModelDownloadPhase.IDLE -> Unit
        }
        renderBusyState()
    }

    private fun renderInferenceState(snapshot: InferenceSnapshot) {
        if (snapshot.phase == InferencePhase.IDLE) {
            inferenceRunning = false
            renderBusyState()
            return
        }

        ensureInferenceMessage(snapshot)
        val assistantId = activeAssistantMessageId ?: return
        val waitingText = getString(
            if (snapshot.thinkingEnabled) {
                R.string.response_waiting_thinking
            } else {
                R.string.response_waiting
            },
        )
        inferenceRunning = snapshot.phase == InferencePhase.RUNNING
        when (snapshot.phase) {
            InferencePhase.RUNNING -> {
                updateMessageById(
                    assistantId,
                    snapshot.answer.ifBlank { waitingText },
                    reasoning = snapshot.reasoning.ifBlank { null },
                    isStreaming = true,
                )
                statusText.text = getString(
                    if (snapshot.thinkingEnabled) {
                        R.string.status_reasoning_background
                    } else {
                        R.string.status_generating_background
                    },
                )
                progressLabel.text = getString(R.string.progress_background_inference)
            }

            InferencePhase.COMPLETED -> {
                updateMessageById(
                    assistantId,
                    snapshot.answer,
                    snapshot.meta,
                    snapshot.reasoning.ifBlank { null },
                )
                statusText.text = getString(R.string.status_ready)
                progressLabel.text = snapshot.meta.orEmpty()
                if (snapshot.isAutomaticDemo) markDemoCompleted()
            }

            InferencePhase.STOPPED -> {
                updateMessageById(
                    assistantId,
                    snapshot.answer,
                    snapshot.meta,
                    snapshot.reasoning.ifBlank { null },
                )
                statusText.text = getString(R.string.status_ready)
                progressLabel.text = getString(R.string.progress_stopped)
            }

            InferencePhase.FAILED -> {
                updateMessageById(
                    assistantId,
                    snapshot.answer.ifBlank { getString(R.string.response_failed) },
                    reasoning = snapshot.reasoning.ifBlank { null },
                )
                statusText.text = getString(R.string.status_error)
                val error = snapshot.error.orEmpty()
                detailText.text = getString(R.string.error_inference, error)
                progressLabel.text = getString(R.string.error_inference, error)
                Log.e(TAG, "Background inference failed: $error")
            }

            InferencePhase.IDLE -> Unit
        }
        renderBusyState()
    }

    private fun ensureInferenceMessage(snapshot: InferenceSnapshot) {
        val currentAssistantId = activeAssistantMessageId
        val hasCurrentMessage = currentAssistantId != null &&
            messages.any { it.id == currentAssistantId }
        if (activeRequestId == snapshot.requestId && hasCurrentMessage) return

        activeRequestId = snapshot.requestId
        addMessage("You", snapshot.prompt)
        val assistantIndex = addMessage(
            "iBit",
            getString(R.string.response_waiting),
            isStreaming = snapshot.phase == InferencePhase.RUNNING,
        )
        activeAssistantMessageId = messages[assistantIndex].id
    }

    private fun updateMessageById(
        messageId: Long,
        text: String,
        meta: String? = null,
        reasoning: String? = null,
        isStreaming: Boolean = false,
    ) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            updateMessage(index, text, meta, reasoning, isStreaming)
        }
    }

    private suspend fun startNewChat() {
        if (isBusy || engine.state != BonsaiEngine.State.MODEL_READY) return
        setBusy(true)
        try {
            engine.resetConversation()
            InferenceSession.clearFinished()
            activeRequestId = null
            activeAssistantMessageId = null
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
        modelOperationBusy = busy
        renderBusyState()
    }

    private fun renderBusyState() {
        val busy = isBusy
        downloadButton.isEnabled = downloadRunning || !busy
        if (downloadRunning) {
            downloadButton.setText(R.string.action_stop_download)
            downloadButton.contentDescription =
                getString(R.string.action_stop_download_description)
        } else {
            downloadButton.contentDescription = null
        }
        importButton.isEnabled = !busy
        modelsButton.isEnabled = !busy
        val modelReady = engine.state == BonsaiEngine.State.MODEL_READY
        sendButton.isEnabled = !busy && modelReady
        sendButton.isVisible = !inferenceRunning
        stopButton.isVisible = inferenceRunning
        stopButton.isEnabled = inferenceRunning
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
        downloadButton.text = getString(
            R.string.action_download,
            formatBytes(selectedModel.fileBytes),
        )
        importButton.setText(R.string.action_replace_import)
        emptyState.setText(R.string.chat_empty_model_error)
    }

    private fun addMessage(
        role: String,
        text: String,
        meta: String? = null,
        reasoning: String? = null,
        isStreaming: Boolean = false,
    ): Int {
        messages += ChatMessage(nextMessageId++, role, text, meta, reasoning, isStreaming)
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
        isStreaming: Boolean = false,
    ) {
        if (index !in messages.indices) return
        val keepAtBottom = !messagesView.canScrollVertically(1)
        messages[index] = messages[index].copy(
            text = text,
            meta = meta,
            reasoning = reasoning,
            isStreaming = isStreaming,
        )
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

    private fun restoreSelectedModel(): BonsaiModel {
        val stored = BonsaiModels.find(
            getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
                .getString(PREF_SELECTED_MODEL, null),
        )
        if (stored != null) return stored
        return BonsaiModels.all.firstOrNull { candidateModelFile(it) != null }
            ?: BonsaiModels.default
    }

    private fun saveSelectedModel() {
        getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE).edit {
            putString(PREF_SELECTED_MODEL, selectedModel.id)
        }
    }

    private fun internalModelFile(model: BonsaiModel) =
        File(File(filesDir, "models"), model.fileName)

    private fun partialModelFile(model: BonsaiModel) =
        File(internalModelFile(model).parentFile, "${model.fileName}.part")

    private fun importModelFile(model: BonsaiModel) =
        File(internalModelFile(model).parentFile, "${model.fileName}.import")

    private fun partialDownloadBytes(model: BonsaiModel): Long {
        val partial = partialModelFile(model)
        return partial.length().takeIf { partial.isFile && it in 1L..model.fileBytes } ?: 0L
    }

    private fun updateDownloadAction(model: BonsaiModel) {
        downloadButton.text = when (partialDownloadBytes(model)) {
            model.fileBytes -> getString(R.string.action_verify_download)
            0L -> getString(R.string.action_download, formatBytes(model.fileBytes))
            else -> getString(R.string.action_resume)
        }
        importButton.setText(R.string.action_import)
    }

    private fun externalModelFile(model: BonsaiModel): File? {
        val root = getExternalFilesDir(null) ?: return null
        return File(File(root, "models"), model.fileName)
    }

    private fun candidateModelFile(model: BonsaiModel): File? {
        return listOfNotNull(internalModelFile(model), externalModelFile(model))
            .firstOrNull { isValidModelFile(model, it) }
    }

    private fun isValidModelFile(model: BonsaiModel, file: File): Boolean {
        if (!file.isFile || !file.canRead() || file.length() != model.fileBytes) return false
        return runCatching {
            FileInputStream(file).use { input ->
                val magic = ByteArray(GGUF_MAGIC.size)
                input.read(magic) == magic.size && magic.contentEquals(GGUF_MAGIC)
            }
        }.getOrDefault(false)
    }

    private fun validateModelFile(model: BonsaiModel, file: File) {
        val actualBytes = file.length()
        require(actualBytes == model.fileBytes) {
            "Model size is ${formatBytes(actualBytes)}; expected ${formatBytes(model.fileBytes)}"
        }
        require(isValidModelFile(model, file)) { "The selected file is not a valid GGUF model" }
        require(sha256(file).equals(model.sha256, ignoreCase = true)) {
            "Model checksum does not match the official Bonsai artifact"
        }
    }

    private suspend fun ensureModelVerified(model: BonsaiModel, file: File) {
        val fingerprint = modelFileFingerprint(file)
        val preferences = getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
        if (preferences.getString(verificationPreference(model), null) == fingerprint) return

        showVerifyingModel()
        withContext(Dispatchers.IO) { validateModelFile(model, file) }
        rememberModelVerification(model, file)
    }

    private fun rememberModelVerification(model: BonsaiModel, file: File) {
        getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE).edit {
            putString(verificationPreference(model), modelFileFingerprint(file))
        }
    }

    private fun modelFileFingerprint(file: File): String =
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    private fun verificationPreference(model: BonsaiModel): String =
        "verified_model_${model.id}"

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
        return getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
            .getBoolean(PREF_DEMO_DONE, false)
    }

    private fun markDemoCompleted() {
        getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE).edit {
            putBoolean(PREF_DEMO_DONE, true)
        }
    }

    private fun runWithNotificationPermission(action: () -> Unit) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingNotificationAction = action
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        private const val TAG = "IBitDemo"
        private const val HASH_BUFFER_BYTES = 1024 * 1024
        private const val DOWNLOAD_PROGRESS_MAX = 1000
        private const val MIB_BYTES = 1024.0 * 1024.0
        private const val GIB_BYTES = 1024.0 * 1024.0 * 1024.0
        private const val PROGRESS_UPDATE_BYTES = 4L * 1024L * 1024L
        private const val STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
        private const val AUTO_PROMPT =
            "In two short sentences, confirm you are running locally on this Android device and name one benefit of an efficient Bonsai language model."
        private const val PREF_DEMO_DONE = "demo_done"
        private const val PREF_THINKING_ENABLED = "thinking_enabled"
        private const val PREF_SELECTED_MODEL = "selected_model"
        private const val PREF_IMPORT_TARGET_MODEL = "import_target_model"
        private const val DIRECT_MAX_TOKENS = 160
        private const val THINKING_MAX_TOKENS = 512
        private const val STATE_IDS = "message_ids"
        private const val STATE_ROLES = "message_roles"
        private const val STATE_TEXTS = "message_texts"
        private const val STATE_METAS = "message_metas"
        private const val STATE_REASONING = "message_reasoning"
        private const val STATE_NEXT_ID = "next_message_id"
        private const val STATE_ACTIVE_REQUEST_ID = "active_request_id"
        private const val STATE_ACTIVE_ASSISTANT_MESSAGE_ID = "active_assistant_message_id"
        private const val EXTRA_VERIFY_PROMPT = "com.samsung.ibit.extra.VERIFY_PROMPT"
        private const val EXTRA_VERIFY_THINKING = "com.samsung.ibit.extra.VERIFY_THINKING"
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    }

    private data class VerificationRequest(
        val prompt: String,
        val enableThinking: Boolean,
    )
}
