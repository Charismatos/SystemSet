package com.example.systemset

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.security.crypto.EncryptedSharedPreferences
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoSink
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class DeviceStreamingService : Service() {

    private var eglBase: EglBase? = null
    private var webRTCClient: WebRTCClient? = null
    private var streamingActive = false
    private var stopping = false
    private var currentStatus = STATUS_STOPPED
    private var currentReason = "Awaiting manual start activation."
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var startupFuture: Future<*>? = null
    private var startupGeneration = 0
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private var disconnectedRunnable: Runnable? = null
    private var activeDeviceUserSlug: String? = null
    private var activeTenantKey: String? = null
    private var activeDeviceModel: String? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        // Project significance: a recreated activity can immediately receive the current
        // service state instead of showing an unrelated, hard-coded stopped state.
        notifyUiListener()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming(clearSavedSession = true)
            return START_NOT_STICKY
        }

        if (streamingActive) return START_REDELIVER_INTENT

        val startIntent: Intent = when {
            intent?.action == ACTION_START -> intent
            intent == null -> restoreStartIntent()
            else -> {
                Log.w(TAG, "Ignoring unsupported service action: ${intent.action}")
                return START_NOT_STICKY
            }
        } ?: run {
            Log.e(TAG, "Cannot restore streaming: no saved start configuration is available.")
            updateStatus(STATUS_STOPPED, "Service restart had no saved stream configuration.", false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val tenantKey = startIntent.getStringExtra(EXTRA_TENANT_KEY).orEmpty()
        val deviceUserSlug = startIntent.getStringExtra(EXTRA_DEVICE_USER_SLUG).orEmpty()
        val requestedCallId = startIntent.getStringExtra(EXTRA_CALL_ID).orEmpty()

        if (tenantKey.isBlank() || deviceUserSlug.isBlank() || requestedCallId.isBlank()) {
            Log.e(TAG, "Start request is missing a required streaming parameter.")
            updateStatus(STATUS_STOPPED, "Stream configuration is incomplete.", false)
            clearSavedSession()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val callId = "session_${UUID.randomUUID()}"

        try {
            saveStartConfiguration(tenantKey, deviceUserSlug, callId)
        } catch (e: Exception) {
            Log.e(TAG, "Could not securely save stream restart configuration.", e)
            updateStatus(
                STATUS_STOPPED,
                "Could not save secure restart configuration: ${e.message}",
                false
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        if (!startStreaming(deviceUserSlug, callId, tenantKey, deviceModel)) {
            return START_NOT_STICKY
        }

        // Project significance: Android re-delivers the start command after process death.
        // Returning START_STICKY alone could restart with a null intent and no stream parameters.
        return START_REDELIVER_INTENT
    }

    private fun startStreaming(
        deviceUserSlug: String,
        callId: String,
        tenantKey: String,
        deviceModel: String
    ): Boolean {
        if (streamingActive) return true

        try {
            createNotificationChannel()
            val notification = buildNotification(
                "Starting stream for $deviceUserSlug ($deviceModel)"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            streamingActive = true
            stopping = false
            activeDeviceUserSlug = deviceUserSlug
            activeTenantKey = tenantKey
            activeDeviceModel = deviceModel
            reconnectAttempt = 0
            updateStatus(STATUS_STARTING, "Initializing camera and WebRTC.", true)

            loadTurnCredentialsAndStartClient(deviceUserSlug, callId, tenantKey, deviceModel)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming service.", e)
            stopStreaming(
                clearSavedSession = true,
                reason = "Streaming initialization failed: ${e.message ?: "unknown error"}"
            )
            return false
        }
    }

    private fun loadTurnCredentialsAndStartClient(
        deviceUserSlug: String,
        callId: String,
        tenantKey: String,
        deviceModel: String
    ) {
        val generation = ++startupGeneration
        startupFuture = startupExecutor.submit {
            try {
                val iceServers = IceServerProvider.fetch(tenantKey, deviceUserSlug)
                mainHandler.post {
                    if (generation != startupGeneration || stopping || !streamingActive) return@post
                    try {
                        val newEglBase = EglBase.create()
                        eglBase = newEglBase
                        webRTCClient = WebRTCClient(
                            context = this,
                            deviceUserSlug = deviceUserSlug,
                            callId = callId,
                            tenantKey = tenantKey,
                            deviceModel = deviceModel,
                            eglContext = newEglBase.eglBaseContext,
                            localVideoSink = VideoSink { },
                            iceServers = iceServers,
                            onStatusChanged = { status, isHealthy, reason ->
                                handleStreamingStatus(status, isHealthy, reason)
                            },
                            onIceFailure = ::requestIceReconnect,
                            onIceDisconnected = ::requestDisconnectGracePeriod
                        ).also(WebRTCClient::connectSignalingServer)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize WebRTC with configured ICE servers.", e)
                        stopStreaming(
                            clearSavedSession = true,
                            reason = "WebRTC initialization failed: ${e.message ?: "unknown error"}"
                        )
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (generation != startupGeneration || stopping || !streamingActive) return@post
                    Log.e(TAG, "Could not load configured ICE servers.", e)
                    if (reconnectAttempt > 0) {
                        scheduleIceReconnect("ICE server refresh failed.")
                    } else {
                        stopStreaming(
                            clearSavedSession = true,
                            reason = "Could not load ICE servers: ${e.message ?: "unknown error"}"
                        )
                    }
                }
            }
        }
    }

    private fun requestIceReconnect(reason: String) {
        mainHandler.post {
            if (!streamingActive || stopping) return@post
            disconnectedRunnable?.let(mainHandler::removeCallbacks)
            disconnectedRunnable = null
            updateStatus(STATUS_NOT_CONNECTED, reason, true)
            scheduleIceReconnect(reason)
        }
    }

    private fun requestDisconnectGracePeriod(reason: String) {
        mainHandler.post {
            if (!streamingActive || stopping || disconnectedRunnable != null) return@post
            updateStatus(STATUS_NOT_CONNECTED, reason, true)
            disconnectedRunnable = Runnable {
                disconnectedRunnable = null
                if (streamingActive && !stopping) {
                    scheduleIceReconnect("$reason Connection did not recover.")
                }
            }.also { mainHandler.postDelayed(it, DISCONNECTED_GRACE_PERIOD_MILLIS) }
        }
    }

    private fun scheduleIceReconnect(reason: String) {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "WebRTC reconnect limit reached: $reason")
            stopStreaming(
                clearSavedSession = false,
                reason = "Reconnect limit reached: $reason"
            )
            return
        }

        reconnectAttempt++
        val delayMillis = (RECONNECT_BASE_DELAY_MILLIS shl (reconnectAttempt - 1))
            .coerceAtMost(RECONNECT_MAX_DELAY_MILLIS)
        updateStatus(
            STATUS_NOT_CONNECTED,
            "$reason Retrying in ${delayMillis / 1000} seconds.",
            true
        )
        reconnectRunnable = Runnable {
            if (!streamingActive || stopping) return@Runnable
            val deviceUserSlug = activeDeviceUserSlug ?: return@Runnable
            val tenantKey = activeTenantKey ?: return@Runnable
            val deviceModel = activeDeviceModel ?: return@Runnable

            releaseWebRtcResources()
            val callId = "session_${UUID.randomUUID()}"
            try {
                saveStartConfiguration(tenantKey, deviceUserSlug, callId)
                updateStatus(STATUS_STARTING, "Creating a fresh WebRTC negotiation.", true)
                loadTurnCredentialsAndStartClient(deviceUserSlug, callId, tenantKey, deviceModel)
            } catch (e: Exception) {
                Log.e(TAG, "Could not prepare WebRTC reconnection.", e)
                scheduleIceReconnect(e.message ?: "Could not prepare reconnection.")
            }
        }.also { mainHandler.postDelayed(it, delayMillis) }
    }

    private fun releaseWebRtcResources() {
        val client = webRTCClient
        webRTCClient = null
        try {
            client?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error while closing WebRTC client.", e)
        }
        try {
            eglBase?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error while releasing EGL resources.", e)
        } finally {
            eglBase = null
        }
    }

    private fun handleStreamingStatus(status: String, isHealthy: Boolean, reason: String) {
        mainHandler.post {
            if (stopping) return@post
            val displayStatus = if (isHealthy) STATUS_CONNECTED else status
            if (isHealthy) {
                reconnectAttempt = 0
                disconnectedRunnable?.let(mainHandler::removeCallbacks)
                disconnectedRunnable = null
            }
            updateStatus(displayStatus, reason, streamingActive)

            // Health events remain the WebRTC client's responsibility. This call is only made
            // while it is alive; service teardown separately sends its stopped status.
            webRTCClient?.sendHealthPayload(displayStatus, reason)
        }
    }

    private fun updateStatus(status: String, reason: String, active: Boolean) {
        currentStatus = status
        currentReason = reason
        streamingActive = active
        notifyUiListener()
    }

    private fun notifyUiListener() {
        uiListener?.invoke(currentStatus, currentReason, streamingActive)
    }

    private fun stopStreaming(
        clearSavedSession: Boolean,
        reason: String = "Stream stopped."
    ) {
        if (stopping) return
        stopping = true
        startupGeneration++
        startupFuture?.cancel(true)
        startupFuture = null
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        disconnectedRunnable?.let(mainHandler::removeCallbacks)
        disconnectedRunnable = null

        val client = webRTCClient
        webRTCClient = null
        streamingActive = false

        try {
            client?.sendHealthPayload(STATUS_STOPPED, reason)
            client?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error while closing WebRTC client.", e)
        }

        try {
            eglBase?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error while releasing EGL resources.", e)
        } finally {
            eglBase = null
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (clearSavedSession) clearSavedSession()
        activeTenantKey = null
        activeDeviceUserSlug = null
        activeDeviceModel = null
        updateStatus(STATUS_STOPPED, reason, false)
        stopSelf()
        stopping = false
    }

    private fun saveStartConfiguration(
        tenantKey: String,
        deviceUserSlug: String,
        callId: String
    ) {
        val saved = sessionPreferences().edit()
            .putString(KEY_TENANT, tenantKey)
            .putString(KEY_DEVICE_USER_SLUG, deviceUserSlug)
            .putString(KEY_CALL_ID, callId)
            .commit()
        check(saved) { "Encrypted preferences did not save the stream configuration." }
    }

    private fun restoreStartIntent(): Intent? {
        val preferences = try {
            sessionPreferences()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open encrypted stream configuration.", e)
            return null
        }
        val tenantKey = preferences.getString(KEY_TENANT, null) ?: return null
        val deviceUserSlug = preferences.getString(KEY_DEVICE_USER_SLUG, null) ?: return null
        val callId = preferences.getString(KEY_CALL_ID, null) ?: return null

        return Intent(this, DeviceStreamingService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TENANT_KEY, tenantKey)
            .putExtra(EXTRA_DEVICE_USER_SLUG, deviceUserSlug)
            .putExtra(EXTRA_CALL_ID, callId)
    }

    private fun clearSavedSession() {
        try {
            val cleared = sessionPreferences().edit().clear().commit()
            if (!cleared) Log.e(TAG, "Could not clear saved stream configuration.")
        } catch (e: Exception) {
            Log.e(TAG, "Could not access encrypted stream configuration to clear it.", e)
        }
    }
    
    // Use the supported encrypted-preferences overload
    // Project significance: the stream configuration is sensitive and must be encrypted at rest. The Android Keystore is used to protect the encryption key.
    private fun sessionPreferences() =
        EncryptedSharedPreferences.create(
            SESSION_PREFERENCES,
            MASTER_KEY_ALIAS,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun buildNotification(message: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, DeviceStreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.systemset_logo)
            .setContentTitle("System Set Background Stream")
            .setContentText(message)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Camera Stream",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        currentInstance = null
        // Project significance: release native camera/WebRTC resources if Android destroys
        // the service, but preserve encrypted configuration so a system restart can restore it.
        releaseStreamingResources()
        startupExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun releaseStreamingResources() {
        streamingActive = false
        startupGeneration++
        startupFuture?.cancel(true)
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        disconnectedRunnable?.let(mainHandler::removeCallbacks)
        disconnectedRunnable = null
        val client = webRTCClient
        webRTCClient = null

        try {
            client?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error while closing WebRTC client during service destruction.", e)
        }

        try {
            eglBase?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error while releasing EGL during service destruction.", e)
        } finally {
            eglBase = null
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        @Volatile
        private var currentInstance: DeviceStreamingService? = null

        @Volatile
        private var uiListener: ((String, String, Boolean) -> Unit)? = null

        /**
         * Attaches a listener for the current foreground activity.
         *
         * Project significance: callbacks are delivered to the visible activity only;
         * detaching on activity stop avoids retaining an obsolete Activity instance.
         */
        fun setUiListener(listener: ((String, String, Boolean) -> Unit)?) {
            uiListener = listener
            currentInstance?.notifyUiListener()
        }

        const val ACTION_START = "com.example.systemset.action.START_STREAM"
        const val ACTION_STOP = "com.example.systemset.action.STOP_STREAM"
        const val EXTRA_DEVICE_USER_SLUG = "device_user_slug"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_TENANT_KEY = "tenant_key"
        private const val CHANNEL_ID = "system_set_stream_channel"
        private const val NOTIFICATION_ID = 9921
        private const val STOP_REQUEST_CODE = 9922
        private const val TAG = "DeviceStreamingService"
        private const val SESSION_PREFERENCES = "stream_session"
        private const val KEY_TENANT = "tenant_key"
        private const val KEY_DEVICE_USER_SLUG = "device_user_slug"
        private const val KEY_CALL_ID = "call_id"
        private const val STATUS_STARTING = "System is starting"
        private const val STATUS_CONNECTED = "System is set"
        private const val STATUS_STOPPED = "System is not set"
        private const val MASTER_KEY_ALIAS = "systemset_stream_session_key"
        private const val STATUS_NOT_CONNECTED = "System is not set"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MILLIS = 1_000L
        private const val RECONNECT_MAX_DELAY_MILLIS = 60_000L
        private const val DISCONNECTED_GRACE_PERIOD_MILLIS = 10_000L
    }
}