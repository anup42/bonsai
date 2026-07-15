package com.prismml.bonsai.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

class BonsaiEngine private constructor(
    nativeLibraryDirectory: String,
) {
    enum class State {
        INITIALIZED,
        LOADING,
        MODEL_READY,
        GENERATING,
        ERROR,
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    var state: State = State.INITIALIZED
        private set

    @Volatile
    var loadedModelPath: String? = null
        private set

    @Volatile
    private var loadedModelLastModified: Long = 0L

    @Volatile
    var activeBackend: String = "Detecting accelerator"
        private set

    init {
        System.loadLibrary("bonsai-runtime")
        nativeInitialize(nativeLibraryDirectory)
        Log.i(TAG, "Native runtime initialized: ${nativeSystemInfo()}")
    }

    suspend fun loadModel(model: File, contextSize: Int = DEFAULT_CONTEXT_SIZE) =
        withContext(inferenceDispatcher) {
            require(model.isFile && model.canRead()) { "Model file is not readable: ${model.path}" }
            val canonicalPath = model.canonicalPath
            if (
                loadedModelPath == canonicalPath &&
                loadedModelLastModified == model.lastModified() &&
                state == State.MODEL_READY
            ) {
                return@withContext
            }

            state = State.LOADING
            if (loadedModelPath != null) nativeUnload()
            loadedModelPath = null
            loadedModelLastModified = 0L
            try {
                check(nativeLoad(canonicalPath, contextSize) == 0) {
                    "llama.cpp could not load ${model.name}"
                }
                check(nativeSetSystemPrompt(SYSTEM_PROMPT) == 0) {
                    "The model rejected the system prompt"
                }
                loadedModelPath = canonicalPath
                loadedModelLastModified = model.lastModified()
                activeBackend = nativeBackendDescription()
                state = State.MODEL_READY
            } catch (error: Throwable) {
                state = State.ERROR
                throw error
            }
        }

    fun generate(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        enableThinking: Boolean = false,
    ): Flow<String> = flow {
        require(prompt.isNotBlank()) { "Prompt cannot be empty" }
        check(state == State.MODEL_READY) { "Model is not ready (state=$state)" }

        state = State.GENERATING
        try {
            check(
                nativePreparePrompt(
                    prompt,
                    maxTokens.coerceIn(1, MAX_GENERATION_TOKENS),
                    enableThinking,
                ) == 0,
            ) {
                "Prompt processing failed"
            }
            while (true) {
                val piece = nativeNextToken() ?: break
                if (piece.isNotEmpty()) emit(piece)
            }
            state = State.MODEL_READY
        } catch (cancelled: CancellationException) {
            val resetResult = runCatching { nativeResetConversation(SYSTEM_PROMPT) }.getOrDefault(-1)
            state = if (resetResult == 0) State.MODEL_READY else State.ERROR
            throw cancelled
        } catch (error: Throwable) {
            val resetResult = runCatching { nativeResetConversation(SYSTEM_PROMPT) }.getOrDefault(-1)
            state = if (resetResult == 0) State.MODEL_READY else State.ERROR
            throw error
        }
    }.flowOn(inferenceDispatcher)

    suspend fun performanceMetrics(): PerformanceMetrics = withContext(inferenceDispatcher) {
        val values = nativePerformance()
        require(values.size == 4) { "Unexpected native performance payload" }
        PerformanceMetrics(
            promptTokens = values[0].toInt(),
            promptMilliseconds = values[1],
            generatedTokens = values[2].toInt(),
            generationMilliseconds = values[3],
        )
    }

    suspend fun resetConversation() = withContext(inferenceDispatcher) {
        check(loadedModelPath != null) { "No model is loaded" }
        check(nativeResetConversation(SYSTEM_PROMPT) == 0) { "Could not reset conversation" }
        state = State.MODEL_READY
    }

    private external fun nativeInitialize(nativeLibraryDirectory: String)
    private external fun nativeSystemInfo(): String
    private external fun nativeLoad(modelPath: String, contextSize: Int): Int
    private external fun nativeSetSystemPrompt(prompt: String): Int
    private external fun nativePreparePrompt(
        prompt: String,
        maxTokens: Int,
        enableThinking: Boolean,
    ): Int
    private external fun nativeNextToken(): String?
    private external fun nativePerformance(): DoubleArray
    private external fun nativeBackendDescription(): String
    private external fun nativeResetConversation(systemPrompt: String): Int
    private external fun nativeUnload()

    companion object {
        private const val TAG = "BonsaiEngine"
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val DEFAULT_MAX_TOKENS = 160
        private const val MAX_GENERATION_TOKENS = 512
        private const val SYSTEM_PROMPT =
            "You are Bonsai, a helpful and concise assistant. Answer accurately and clearly. If a thinking section is opened, keep it brief, double-check factual and numerical claims, close it, and always provide a clear final answer."

        @Volatile
        private var instance: BonsaiEngine? = null

        fun getInstance(context: Context): BonsaiEngine = instance ?: synchronized(this) {
            instance ?: BonsaiEngine(context.applicationInfo.nativeLibraryDir).also { instance = it }
        }
    }
}
