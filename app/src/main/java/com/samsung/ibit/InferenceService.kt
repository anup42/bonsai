package com.samsung.ibit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.prismml.bonsai.runtime.BonsaiEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class InferencePhase {
    IDLE,
    RUNNING,
    COMPLETED,
    STOPPED,
    FAILED,
}

data class InferenceSnapshot(
    val requestId: Long = 0L,
    val phase: InferencePhase = InferencePhase.IDLE,
    val prompt: String = "",
    val thinkingEnabled: Boolean = false,
    val isAutomaticDemo: Boolean = false,
    val answer: String = "",
    val reasoning: String = "",
    val meta: String? = null,
    val generatedTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val error: String? = null,
)

object InferenceSession {
    private val mutableState = MutableStateFlow(InferenceSnapshot())
    val state = mutableState.asStateFlow()

    internal fun update(snapshot: InferenceSnapshot) {
        mutableState.value = snapshot
    }

    fun clearFinished() {
        if (mutableState.value.phase != InferencePhase.RUNNING) {
            mutableState.value = InferenceSnapshot()
        }
    }
}

/**
 * Keeps a user-requested generation alive when the activity is backgrounded.
 *
 * The service is foreground only while llama.cpp is actively generating. Its
 * ongoing notification and the in-app Stop button both cancel the same
 * coroutine, which lets [BonsaiEngine] reset the native conversation safely.
 */
class InferenceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: BonsaiEngine
    private var generationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engine = BonsaiEngine.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE -> {
                val request = GenerationRequest.from(intent)
                if (request == null) {
                    Log.e(TAG, "Ignoring an invalid generation request")
                    stopSelf(startId)
                } else {
                    startAsForeground(request)
                    startGeneration(request)
                }
            }

            ACTION_STOP -> stopGeneration(startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        generationJob?.cancel(CancellationException("Inference service destroyed"))
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startGeneration(request: GenerationRequest) {
        if (generationJob?.isActive == true) {
            Log.w(TAG, "Ignoring request ${request.requestId}; generation is already active")
            return
        }

        val response = StringBuilder()
        InferenceSession.update(
            InferenceSnapshot(
                requestId = request.requestId,
                phase = InferencePhase.RUNNING,
                prompt = request.prompt,
                thinkingEnabled = request.thinkingEnabled,
                isAutomaticDemo = request.isAutomaticDemo,
            ),
        )

        generationJob = serviceScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            var emittedPieces = 0
            var parsed = ParsedModelResponse("", "", reasoningComplete = !request.thinkingEnabled)
            try {
                engine.generate(
                    request.prompt,
                    request.maxTokens,
                    enableThinking = request.thinkingEnabled,
                ).collect { piece ->
                    emittedPieces++
                    response.append(piece)
                    parsed = ThinkingResponseParser.parse(
                        response.toString(),
                        request.thinkingEnabled,
                    )
                    InferenceSession.update(
                        InferenceSession.state.value.copy(
                            answer = parsed.answer,
                            reasoning = parsed.reasoning,
                        ),
                    )
                    if (
                        emittedPieces == 1 ||
                        emittedPieces % NOTIFICATION_UPDATE_INTERVAL_TOKENS == 0
                    ) {
                        updateProgressNotification(
                            currentTokens = emittedPieces,
                            request = request,
                        )
                    }
                }

                val metrics = engine.performanceMetrics()
                val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(1L)
                val tokenCount = if (metrics.generatedTokens > 0) {
                    metrics.generatedTokens
                } else {
                    emittedPieces
                }
                val tokensPerSecond = if (metrics.generationTokensPerSecond > 0.0) {
                    metrics.generationTokensPerSecond
                } else {
                    tokenCount * 1000.0 / elapsedMs.toDouble()
                }
                parsed = ThinkingResponseParser.parse(
                    response.toString(),
                    request.thinkingEnabled,
                )
                val reachedTokenLimit = tokenCount >= request.maxTokens
                val finalText = parsed.answer.ifBlank {
                    check(request.thinkingEnabled && parsed.reasoning.isNotBlank()) {
                        "The model returned an empty response; please retry"
                    }
                    getString(
                        if (reachedTokenLimit) {
                            R.string.response_reasoning_incomplete
                        } else {
                            R.string.response_reasoning_ended_early
                        },
                    )
                }
                val meta = resources.getQuantityString(
                    R.plurals.response_meta,
                    tokenCount,
                    tokenCount,
                    tokensPerSecond,
                )
                InferenceSession.update(
                    InferenceSession.state.value.copy(
                        phase = InferencePhase.COMPLETED,
                        answer = finalText,
                        reasoning = parsed.reasoning,
                        meta = meta,
                        generatedTokens = tokenCount,
                        tokensPerSecond = tokensPerSecond,
                    ),
                )
                logCompleted(request, parsed, finalText, tokenCount, tokensPerSecond, emittedPieces)
            } catch (cancelled: CancellationException) {
                parsed = ThinkingResponseParser.parse(
                    response.toString(),
                    request.thinkingEnabled,
                )
                val stoppedText = parsed.answer.ifBlank {
                    getString(R.string.response_stopped)
                }
                InferenceSession.update(
                    InferenceSession.state.value.copy(
                        phase = InferencePhase.STOPPED,
                        answer = stoppedText,
                        reasoning = parsed.reasoning,
                        meta = getString(R.string.response_meta_stopped),
                    ),
                )
                Log.i(TAG, "BONSAI_INFERENCE_STOPPED request_id=${request.requestId}")
            } catch (error: Throwable) {
                InferenceSession.update(
                    InferenceSession.state.value.copy(
                        phase = InferencePhase.FAILED,
                        answer = getString(R.string.response_failed),
                        reasoning = parsed.reasoning,
                        error = error.userMessage(),
                    ),
                )
                Log.e(TAG, "BONSAI_INFERENCE_FAILED request_id=${request.requestId}", error)
            } finally {
                generationJob = null
                ServiceCompat.stopForeground(this@InferenceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                // A notification or UI Stop action arrives as a newer start
                // command, so stop unconditionally after the generation job
                // has finished its native cleanup.
                stopSelf()
            }
        }
    }

    private fun stopGeneration(startId: Int) {
        val activeJob = generationJob
        if (activeJob?.isActive == true) {
            activeJob.cancel(CancellationException("Stopped by user"))
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun startAsForeground(request: GenerationRequest) {
        val notification = buildProgressNotification(
            thinkingEnabled = request.thinkingEnabled,
            currentTokens = 0,
            maxTokens = request.maxTokens,
            indeterminate = true,
        )
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType,
        )
    }

    private fun updateProgressNotification(
        currentTokens: Int,
        request: GenerationRequest,
    ) {
        val notification = buildProgressNotification(
            thinkingEnabled = request.thinkingEnabled,
            currentTokens = currentTokens,
            maxTokens = request.maxTokens,
            indeterminate = false,
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        if (currentTokens == 1 || currentTokens % NOTIFICATION_LOG_INTERVAL_TOKENS == 0) {
            Log.i(
                TAG,
                "BONSAI_NOTIFICATION_PROGRESS current=$currentTokens max=${request.maxTokens}",
            )
        }
    }

    private fun buildProgressNotification(
        thinkingEnabled: Boolean,
        currentTokens: Int,
        maxTokens: Int,
        indeterminate: Boolean,
    ): android.app.Notification {
        val stopIntent = Intent(this, InferenceService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentText = if (indeterminate) {
            getString(
                if (thinkingEnabled) {
                    R.string.inference_notification_reasoning
                } else {
                    R.string.inference_notification_generating
                },
            )
        } else {
            getString(
                R.string.inference_notification_progress,
                currentTokens.coerceAtMost(maxTokens),
                maxTokens,
            )
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ibit)
            .setContentTitle(getString(R.string.inference_notification_title))
            .setContentText(contentText)
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(
                maxTokens,
                currentTokens.coerceIn(0, maxTokens),
                indeterminate,
            )
            .addAction(0, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.inference_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.inference_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun logCompleted(
        request: GenerationRequest,
        parsed: ParsedModelResponse,
        finalText: String,
        tokenCount: Int,
        tokensPerSecond: Double,
        emittedPieces: Int,
    ) {
        val compactResponse = finalText.replace('\n', ' ').take(160)
        val modelName = engine.loadedModelPath?.let(::File)?.name ?: "unknown"
        Log.i(
            TAG,
            "BONSAI_DEMO_INFERENCE_OK model=$modelName request_id=${request.requestId} " +
                "emitted_pieces=$emittedPieces " +
                "backend=${engine.activeBackend.replace(' ', '_')} " +
                "thinking_enabled=${request.thinkingEnabled} " +
                "reasoning_chars=${parsed.reasoning.length} generated_tokens=$tokenCount " +
                "tg_tps=${String.format(Locale.US, "%.2f", tokensPerSecond)} " +
                "response=$compactResponse",
        )
        if (request.thinkingEnabled && parsed.reasoningComplete && parsed.answer.isNotBlank()) {
            Log.i(
                TAG,
                "BONSAI_THINKING_INFERENCE_OK reasoning_chars=${parsed.reasoning.length} " +
                    "answer_chars=${parsed.answer.length}",
            )
        }
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() }
        ?: javaClass.simpleName

    private data class GenerationRequest(
        val requestId: Long,
        val prompt: String,
        val maxTokens: Int,
        val thinkingEnabled: Boolean,
        val isAutomaticDemo: Boolean,
    ) {
        companion object {
            fun from(intent: Intent): GenerationRequest? {
                val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
                val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
                val maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 0)
                if (requestId == 0L || prompt.isBlank() || maxTokens <= 0) return null
                return GenerationRequest(
                    requestId = requestId,
                    prompt = prompt,
                    maxTokens = maxTokens,
                    thinkingEnabled = intent.getBooleanExtra(EXTRA_THINKING_ENABLED, false),
                    isAutomaticDemo = intent.getBooleanExtra(EXTRA_AUTOMATIC_DEMO, false),
                )
            }
        }
    }

    companion object {
        private const val TAG = "IBitInference"
        private const val ACTION_GENERATE = "com.samsung.ibit.action.GENERATE"
        private const val ACTION_STOP = "com.samsung.ibit.action.STOP"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_MAX_TOKENS = "max_tokens"
        private const val EXTRA_THINKING_ENABLED = "thinking_enabled"
        private const val EXTRA_AUTOMATIC_DEMO = "automatic_demo"
        private const val NOTIFICATION_CHANNEL_ID = "ibit_inference"
        private const val NOTIFICATION_ID = 8101
        private const val NOTIFICATION_UPDATE_INTERVAL_TOKENS = 4
        private const val NOTIFICATION_LOG_INTERVAL_TOKENS = 8
        private const val STOP_REQUEST_CODE = 8102
        private const val OPEN_REQUEST_CODE = 8103

        fun startGeneration(
            context: Context,
            requestId: Long,
            prompt: String,
            maxTokens: Int,
            thinkingEnabled: Boolean,
            isAutomaticDemo: Boolean,
        ) {
            val intent = Intent(context, InferenceService::class.java).apply {
                action = ACTION_GENERATE
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_MAX_TOKENS, maxTokens)
                putExtra(EXTRA_THINKING_ENABLED, thinkingEnabled)
                putExtra(EXTRA_AUTOMATIC_DEMO, isAutomaticDemo)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopGeneration(context: Context) {
            context.startService(
                Intent(context, InferenceService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
