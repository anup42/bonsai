package com.samsung.ibit

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

enum class ModelDownloadPhase {
    IDLE,
    CONNECTING,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    STOPPED,
    FAILED,
}

data class ModelDownloadSnapshot(
    val requestId: Long = 0L,
    val modelId: String = "",
    val phase: ModelDownloadPhase = ModelDownloadPhase.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val filePath: String? = null,
    val error: String? = null,
)

object ModelDownloadSession {
    private val mutableState = MutableStateFlow(ModelDownloadSnapshot())
    val state = mutableState.asStateFlow()

    internal fun update(snapshot: ModelDownloadSnapshot) {
        mutableState.value = snapshot
    }

    fun clearFinished(requestId: Long) {
        val current = mutableState.value
        if (
            current.requestId == requestId &&
            current.phase !in ACTIVE_DOWNLOAD_PHASES
        ) {
            mutableState.value = ModelDownloadSnapshot()
        }
    }

    private val ACTIVE_DOWNLOAD_PHASES = setOf(
        ModelDownloadPhase.CONNECTING,
        ModelDownloadPhase.DOWNLOADING,
        ModelDownloadPhase.VERIFYING,
    )
}

/**
 * Downloads one official model at a time while keeping the process eligible to
 * run after the activity is backgrounded. Partial files are intentionally kept
 * when stopped so the next request can resume with an HTTP range request.
 */
class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val request = DownloadRequest.from(intent)
                if (request == null) {
                    Log.e(TAG, "Ignoring an invalid model download request")
                    stopSelf(startId)
                } else if (downloadJob?.isActive == true) {
                    Log.w(TAG, "Ignoring request ${request.requestId}; a download is already active")
                } else {
                    startAsForeground(request.model)
                    startDownload(request)
                }
            }

            ACTION_STOP_DOWNLOAD -> stopDownload(startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRequested = true
        activeConnection?.disconnect()
        downloadJob?.cancel(CancellationException("Download service destroyed"))
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDownload(request: DownloadRequest) {
        stopRequested = false
        val model = request.model
        ModelDownloadSession.update(
            ModelDownloadSnapshot(
                requestId = request.requestId,
                modelId = model.id,
                phase = ModelDownloadPhase.CONNECTING,
                downloadedBytes = partialModelFile(model).length(),
                totalBytes = model.fileBytes,
            ),
        )
        Log.i(
            TAG,
            "IBIT_MODEL_DOWNLOAD_STARTED request_id=${request.requestId} " +
                "model=${model.id} restart=${request.restart}",
        )

        downloadJob = serviceScope.launch {
            try {
                val target = downloadModel(request)
                val finalSnapshot = ModelDownloadSession.state.value.copy(
                    phase = ModelDownloadPhase.COMPLETED,
                    downloadedBytes = model.fileBytes,
                    filePath = target.absolutePath,
                )
                ModelDownloadSession.update(finalSnapshot)
                showCompletedNotification(model)
                Log.i(
                    TAG,
                    "IBIT_MODEL_DOWNLOAD_COMPLETED request_id=${request.requestId} " +
                        "model=${model.id} bytes=${model.fileBytes}",
                )
            } catch (cancelled: CancellationException) {
                publishStopped(request)
            } catch (error: Throwable) {
                if (stopRequested) {
                    publishStopped(request)
                } else {
                    val message = error.userMessage()
                    ModelDownloadSession.update(
                        ModelDownloadSession.state.value.copy(
                            phase = ModelDownloadPhase.FAILED,
                            downloadedBytes = partialModelFile(model).length(),
                            error = message,
                        ),
                    )
                    showFailedNotification(model, message)
                    Log.e(
                        TAG,
                        "IBIT_MODEL_DOWNLOAD_FAILED request_id=${request.requestId} " +
                            "model=${model.id}",
                        error,
                    )
                }
            } finally {
                activeConnection = null
                downloadJob = null
                ServiceCompat.stopForeground(
                    this@ModelDownloadService,
                    ServiceCompat.STOP_FOREGROUND_DETACH,
                )
                stopSelf()
            }
        }
    }

    private fun stopDownload(startId: Int) {
        val activeJob = downloadJob
        if (activeJob?.isActive == true) {
            stopRequested = true
            activeConnection?.disconnect()
            activeJob.cancel(CancellationException("Stopped by user"))
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun publishStopped(request: DownloadRequest) {
        val downloadedBytes = partialModelFile(request.model).length()
        ModelDownloadSession.update(
            ModelDownloadSession.state.value.copy(
                phase = ModelDownloadPhase.STOPPED,
                downloadedBytes = downloadedBytes,
            ),
        )
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        Log.i(
            TAG,
            "IBIT_MODEL_DOWNLOAD_STOPPED request_id=${request.requestId} " +
                "model=${request.model.id} bytes=$downloadedBytes",
        )
    }

    private suspend fun downloadModel(request: DownloadRequest): File {
        val model = request.model
        val target = internalModelFile(model)
        target.parentFile?.mkdirs()
        val partial = partialModelFile(model)
        if (request.restart) deletePartialFile(partial)
        if (partial.length() > model.fileBytes) deletePartialFile(partial)

        if (partial.isFile && partial.length() == model.fileBytes) {
            try {
                publishVerifying(request)
                validateModelFile(model, partial)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                deletePartialFile(partial)
            }
            if (partial.isFile) {
                replaceModelFile(partial, target)
                rememberModelVerification(model, target)
                return target
            }
        }

        var downloaded = if (partial.isFile) partial.length() else 0L
        ensureFreeSpace(target, model.fileBytes - downloaded)

        var connection = openModelDownloadConnection(model, downloaded)
        activeConnection = connection
        try {
            var responseCode: Int
            while (true) {
                currentCoroutineContext().ensureActive()
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
                            model.fileBytes,
                        )
                    )
                if (!invalidResume) break

                connection.disconnect()
                deletePartialFile(partial)
                downloaded = 0L
                ensureFreeSpace(target, model.fileBytes)
                connection = openModelDownloadConnection(model, downloaded)
                activeConnection = connection
            }

            if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IllegalStateException("Server returned HTTP $responseCode")
            }
            if (responseCode == HttpURLConnection.HTTP_OK && downloaded > 0L) {
                deletePartialFile(partial)
                downloaded = 0L
                ensureFreeSpace(target, model.fileBytes)
            }
            if (
                responseCode == HttpURLConnection.HTTP_PARTIAL &&
                !ModelDownloadProtocol.isExpectedContentRange(
                    connection.getHeaderField("Content-Range"),
                    downloaded,
                    model.fileBytes,
                )
            ) {
                throw IllegalStateException("Server returned an unexpected download range")
            }

            val expectedResponseBytes = model.fileBytes - downloaded
            val declaredResponseBytes = connection.contentLengthLong
            if (declaredResponseBytes >= 0L && declaredResponseBytes != expectedResponseBytes) {
                throw IllegalStateException(
                    "Server returned ${formatBytes(declaredResponseBytes)}; " +
                        "expected ${formatBytes(expectedResponseBytes)}",
                )
            }

            publishProgress(request, downloaded, forceNotification = true)
            connection.inputStream.use { input ->
                FileOutputStream(partial, downloaded > 0L).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgressUpdate = downloaded
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read.toLong() > model.fileBytes - downloaded) {
                            throw IllegalStateException(
                                "Server sent more data than the expected model size",
                            )
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastProgressUpdate >= PROGRESS_UPDATE_BYTES) {
                            lastProgressUpdate = downloaded
                            publishProgress(request, downloaded)
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            activeConnection = null
            connection.disconnect()
        }

        currentCoroutineContext().ensureActive()
        publishProgress(request, downloaded, forceNotification = true)
        if (downloaded != model.fileBytes) {
            throw IllegalStateException(
                "Download stopped at ${formatBytes(downloaded)}; tap Resume download to continue",
            )
        }
        publishVerifying(request)
        try {
            validateModelFile(model, partial)
        } catch (error: IllegalArgumentException) {
            deletePartialFile(partial)
            throw error
        }
        replaceModelFile(partial, target)
        rememberModelVerification(model, target)
        return target
    }

    private fun publishProgress(
        request: DownloadRequest,
        bytes: Long,
        forceNotification: Boolean = false,
    ) {
        val model = request.model
        ModelDownloadSession.update(
            ModelDownloadSession.state.value.copy(
                phase = ModelDownloadPhase.DOWNLOADING,
                downloadedBytes = bytes,
                totalBytes = model.fileBytes,
            ),
        )
        val normalizedProgress = (
            bytes.toDouble() / model.fileBytes.toDouble() * NOTIFICATION_PROGRESS_MAX
            ).toInt().coerceIn(0, NOTIFICATION_PROGRESS_MAX)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildProgressNotification(
                model = model,
                downloadedBytes = bytes,
                progress = normalizedProgress,
                indeterminate = false,
            ),
        )
        if (
            forceNotification ||
            bytes == 0L ||
            bytes % PROGRESS_LOG_BYTES < PROGRESS_UPDATE_BYTES
        ) {
            Log.i(
                TAG,
                "IBIT_MODEL_DOWNLOAD_PROGRESS request_id=${request.requestId} " +
                    "model=${model.id} bytes=$bytes total=${model.fileBytes}",
            )
        }
    }

    private fun publishVerifying(request: DownloadRequest) {
        ModelDownloadSession.update(
            ModelDownloadSession.state.value.copy(
                phase = ModelDownloadPhase.VERIFYING,
                downloadedBytes = request.model.fileBytes,
            ),
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildProgressNotification(
                model = request.model,
                downloadedBytes = request.model.fileBytes,
                progress = NOTIFICATION_PROGRESS_MAX,
                indeterminate = true,
                verifying = true,
            ),
        )
        Log.i(
            TAG,
            "IBIT_MODEL_DOWNLOAD_VERIFYING request_id=${request.requestId} " +
                "model=${request.model.id}",
        )
    }

    private fun startAsForeground(model: BonsaiModel) {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(
                model = model,
                downloadedBytes = partialModelFile(model).length(),
                progress = 0,
                indeterminate = true,
            ),
            foregroundType,
        )
    }

    private fun buildProgressNotification(
        model: BonsaiModel,
        downloadedBytes: Long,
        progress: Int,
        indeterminate: Boolean,
        verifying: Boolean = false,
    ): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_STOP_DOWNLOAD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentText = when {
            verifying -> getString(R.string.download_notification_verifying)
            indeterminate -> getString(R.string.download_notification_connecting)
            else -> getString(
                R.string.download_notification_progress,
                formatBytes(downloadedBytes),
                formatBytes(model.fileBytes),
            )
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ibit)
            .setContentTitle(getString(R.string.download_notification_title, model.displayName))
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(NOTIFICATION_PROGRESS_MAX, progress, indeterminate)
            .addAction(0, getString(R.string.action_stop_download), stopPendingIntent)
            .build()
    }

    private fun showCompletedNotification(model: BonsaiModel) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ibit)
                .setContentTitle(getString(R.string.download_notification_complete_title))
                .setContentText(
                    getString(R.string.download_notification_complete, model.displayName),
                )
                .setContentIntent(openAppPendingIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun showFailedNotification(model: BonsaiModel, error: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ibit)
                .setContentTitle(getString(R.string.download_notification_failed, model.displayName))
                .setContentText(error)
                .setStyle(NotificationCompat.BigTextStyle().bigText(error))
                .setContentIntent(openAppPendingIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_REQUEST_CODE,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.download_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun internalModelFile(model: BonsaiModel) =
        File(File(filesDir, "models"), model.fileName)

    private fun partialModelFile(model: BonsaiModel) =
        File(internalModelFile(model).parentFile, "${model.fileName}.part")

    private fun openModelDownloadConnection(
        model: BonsaiModel,
        offset: Long,
    ): HttpURLConnection {
        return (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECTION_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
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

    private fun validateModelFile(model: BonsaiModel, file: File) {
        require(file.length() == model.fileBytes) {
            "Model size is ${formatBytes(file.length())}; expected ${formatBytes(model.fileBytes)}"
        }
        require(hasGgufMagic(file)) { "The downloaded file is not a valid GGUF model" }
        require(sha256(file).equals(model.sha256, ignoreCase = true)) {
            "Model checksum does not match the official Bonsai artifact"
        }
    }

    private fun hasGgufMagic(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val magic = ByteArray(GGUF_MAGIC.size)
            input.read(magic) == magic.size && magic.contentEquals(GGUF_MAGIC)
        }
    }.getOrDefault(false)

    private fun rememberModelVerification(model: BonsaiModel, file: File) {
        getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE).edit {
            putString(
                "verified_model_${model.id}",
                "${file.absolutePath}:${file.length()}:${file.lastModified()}",
            )
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
        val usableBytes = target.parentFile?.usableSpace ?: filesDir.usableSpace
        require(usableBytes >= requiredBytes + STORAGE_HEADROOM_BYTES) {
            "Not enough free space. " +
                "${formatBytes(requiredBytes + STORAGE_HEADROOM_BYTES)} is required"
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

    private data class DownloadRequest(
        val requestId: Long,
        val model: BonsaiModel,
        val restart: Boolean,
    ) {
        companion object {
            fun from(intent: Intent): DownloadRequest? {
                val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
                val model = BonsaiModels.find(intent.getStringExtra(EXTRA_MODEL_ID))
                if (requestId == 0L || model == null) return null
                return DownloadRequest(
                    requestId = requestId,
                    model = model,
                    restart = intent.getBooleanExtra(EXTRA_RESTART, false),
                )
            }
        }
    }

    companion object {
        private const val TAG = "IBitModelDownload"
        private const val ACTION_DOWNLOAD = "com.samsung.ibit.action.DOWNLOAD_MODEL"
        private const val ACTION_STOP_DOWNLOAD = "com.samsung.ibit.action.STOP_MODEL_DOWNLOAD"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_RESTART = "restart"
        private const val NOTIFICATION_CHANNEL_ID = "ibit_model_download"
        private const val NOTIFICATION_ID = 8201
        private const val NOTIFICATION_PROGRESS_MAX = 1000
        private const val STOP_REQUEST_CODE = 8202
        private const val OPEN_REQUEST_CODE = 8203
        private const val CONNECTION_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val PROGRESS_UPDATE_BYTES = 2L * 1024L * 1024L
        private const val PROGRESS_LOG_BYTES = 32L * 1024L * 1024L
        private const val HASH_BUFFER_BYTES = 1024 * 1024
        private const val STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
        private const val MIB_BYTES = 1024.0 * 1024.0
        private const val GIB_BYTES = 1024.0 * 1024.0 * 1024.0
        private val GGUF_MAGIC = byteArrayOf(
            'G'.code.toByte(),
            'G'.code.toByte(),
            'U'.code.toByte(),
            'F'.code.toByte(),
        )

        fun startDownload(
            context: Context,
            requestId: Long,
            model: BonsaiModel,
            restart: Boolean,
        ) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_MODEL_ID, model.id)
                putExtra(EXTRA_RESTART, restart)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopDownload(context: Context) {
            context.startService(
                Intent(context, ModelDownloadService::class.java)
                    .setAction(ACTION_STOP_DOWNLOAD),
            )
        }
    }
}

internal const val APP_PREFERENCES = "ibit_app_state"
