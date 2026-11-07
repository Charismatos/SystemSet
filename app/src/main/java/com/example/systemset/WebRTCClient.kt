package com.example.systemset

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebRTCClient(
    context: Context,
    private val deviceUserSlug: String,
    private val callId: String,
    private val tenantKey: String,
    private val deviceModel: String,
    private val eglContext: EglBase.Context,
    private val localVideoSink: VideoSink,
    private val iceServers: List<PeerConnection.IceServer>,
    private val onStatusChanged: (String, Boolean, String) -> Unit,
    private val onIceFailure: (String) -> Unit = {},
    private val onIceDisconnected: (String) -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val resourceLock = Any()
    private val signalingExecutor = Executors.newSingleThreadExecutor()
    private val persistenceExecutor = Executors.newSingleThreadExecutor()
    private val iceGatheringComplete = CountDownLatch(1)
    private val activeHttpCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    init {
        require(deviceUserSlug.isNotBlank()) { "Device user slug must not be blank." }
        require(callId.isNotBlank()) { "Call ID must not be blank." }
        require(tenantKey.isNotBlank()) { "Tenant key must not be blank." }
        require(iceServers.isNotEmpty()) { "At least one ICE server is required." }

        TenantCredentialStore.save(appContext, tenantKey)
        initPeerConnectionFactory()

        // Project significance: recover payloads that were durably written before process
        // death but not yet handed to WorkManager, so transient scheduler errors do not lose SDP.
        PersistedSignalingQueue.recoverPending(appContext)
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
            ?: throw IllegalStateException("Could not create PeerConnectionFactory.")
    }

    fun connectSignalingServer() {
        if (closed.get()) return
        try {
            signalingExecutor.execute {
                try {
                    createPeerConnectionAndOffer()
                } catch (e: Exception) {
                    reportFailure(e.message ?: "Signaling initialization failed.")
                    Log.e(TAG, "WebRTC setup failed.", e)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            reportFailure("Client was closed before signaling could start.")
        }
    }

    private fun createPeerConnectionAndOffer() {
        synchronized(resourceLock) {
            if (closed.get()) return

            val factory = peerConnectionFactory
                ?: throw IllegalStateException("PeerConnectionFactory is unavailable.")
            val connection = factory.createPeerConnection(
                PeerConnection.RTCConfiguration(iceServers),
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        // Project significance: this implementation uses non-trickle ICE;
                        // candidates are embedded in the final SDP before the offer is uploaded.
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        if (closed.get()) return
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED ->
                                onStatusChanged(
                                    STATUS_CONNECTED,
                                    true,
                                    "WebRTC ICE connection established."
                                )
                            PeerConnection.IceConnectionState.DISCONNECTED ->
                                onIceDisconnected("WebRTC connection interrupted.")
                            PeerConnection.IceConnectionState.FAILED ->
                                onIceFailure("WebRTC ICE connection failed.")
                            else -> Unit
                        }
                    }

                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        if (state == PeerConnection.IceGatheringState.COMPLETE) {
                            iceGatheringComplete.countDown()
                        }
                    }

                    override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                    override fun onAddStream(stream: MediaStream?) = Unit
                    override fun onRemoveStream(stream: MediaStream?) = Unit
                    override fun onDataChannel(channel: DataChannel?) = Unit
                    override fun onRenegotiationNeeded() = Unit
                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        streams: Array<out MediaStream>?
                    ) = Unit
                }
            ) ?: throw IllegalStateException("Could not create PeerConnection.")

            peerConnection = connection
            setupVideoTrack(factory, connection)
            if (closed.get()) return

            connection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null || closed.get()) return
                    synchronized(resourceLock) {
                        if (closed.get()) return
                        connection.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                if (closed.get()) return
                                // Project significance: callbacks only schedule work; they never
                                // wait on disk, HTTP, ICE gathering, or receiver polling.
                                submitSignalingTask {
                                    publishOfferThenApplyAnswer(connection)
                                }
                            }

                            override fun onSetFailure(error: String?) {
                                reportFailure("Could not set local description: $error")
                            }

                            override fun onCreateSuccess(description: SessionDescription?) = Unit
                            override fun onCreateFailure(error: String?) = Unit
                        }, description)
                    }
                }

                override fun onCreateFailure(error: String?) {
                    reportFailure("Could not create SDP offer: $error")
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        }
    }

    private fun publishOfferThenApplyAnswer(connection: PeerConnection) {
        try {
            // Project significance: the receiver needs ICE candidates as well as media SDP;
            // waiting ensures the hub receives the final, candidate-bearing offer.
            if (!iceGatheringComplete.await(ICE_GATHERING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting for ICE candidate gathering.")
            }
            if (closed.get()) return

            val gatheredSdp = synchronized(resourceLock) {
                if (closed.get()) return
                connection.localDescription?.description
            } ?: throw IOException("Final local SDP is unavailable.")

            val offer = JSONObject()
                .put("device_user_slug", deviceUserSlug)
                .put("call_id", callId)
                .put("device_model", deviceModel)
                .put("type", "webrtc-offer")
                .put("sdp", gatheredSdp)
                .put("timestamp", System.currentTimeMillis())

            PersistedSignalingQueue.enqueue(
                appContext,
                key = deviceUserSlug,
                payloadType = OFFER_PAYLOAD_TYPE,
                payload = offer
            )

            // Project significance: a stored/queued offer is only signaling progress, not
            // proof of a media connection; keep unhealthy until ICE actually connects.
            onStatusChanged(
                STATUS_NOT_CONNECTED,
                false,
                "Offer persisted for delivery; waiting for receiver answer."
            )

            val answer = waitForMatchingAnswer()
            if (closed.get()) return

            val answerSdp = answer.optString("sdp")
            if (answerSdp.isBlank()) throw IOException("Receiver answer has no SDP.")
            val remoteAnswer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)

            synchronized(resourceLock) {
                if (closed.get()) return
                connection.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // Project significance: applying the receiver's answer completes SDP
                        // negotiation; only the ICE state observer can later mark it healthy.
                        Log.i(TAG, "Remote answer applied for call $callId.")
                    }

                    override fun onSetFailure(error: String?) {
                        reportFailure("Could not apply receiver SDP answer: $error")
                    }

                    override fun onCreateSuccess(description: SessionDescription?) = Unit
                    override fun onCreateFailure(error: String?) = Unit
                }, remoteAnswer)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!closed.get()) reportFailure("Signaling was interrupted.")
        } catch (e: Exception) {
            if (!closed.get()) {
                reportFailure(e.message ?: "Offer/answer signaling failed.")
                Log.e(TAG, "Offer/answer signaling failed.", e)
            }
        }
    }

    private fun waitForMatchingAnswer(): JSONObject {
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(ANSWER_TIMEOUT_SECONDS)

        while (!closed.get() && System.nanoTime() < deadline) {
            try {
                val answer = fetchAnswer(deviceUserSlug)
                if (answer != null &&
                    answer.optString("type") == "webrtc-answer" &&
                    answer.optString("call_id") == callId
                ) {
                    return answer
                }
            } catch (e: SignalingHttpException) {
                if (e.code != 408 && e.code != 429 && e.code < 500) throw e
                Log.w(TAG, "Transient answer lookup response: HTTP ${e.code}")
            } catch (e: IOException) {
                // Project significance: a brief signaling outage does not abort negotiation;
                // answer lookup retries until the bounded session deadline.
                Log.w(TAG, "Answer lookup will retry after an I/O failure.", e)
            }

            // Project significance: polling uses the project's tenant hub as the signaling
            // rendezvous, allowing the receiver to answer without a persistent socket.
            Thread.sleep(ANSWER_POLL_INTERVAL_MILLIS)
        }

        if (closed.get()) throw InterruptedException("Client closed while awaiting answer.")
        throw IOException("Timed out waiting for a matching receiver answer.")
    }

    private fun fetchAnswer(key: String): JSONObject? {
        val request = BackendApi.request(
            tenantKey = tenantKey,
            path = BackendApi.TENANT_PAYLOAD_ENDPOINT,
            queryParameters = mapOf(
                "key" to key,
                "payload_type" to ANSWER_PAYLOAD_TYPE
            )
        )
        val response = executeHttp(request)
        response.use {
            if (it.code == 404) return null
            val responseText = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw SignalingHttpException(
                    it.code,
                    "Answer lookup failed: HTTP ${it.code}: $responseText"
                )
            }
            if (responseText.isBlank()) return null

            val envelope = BackendApi.parseSuccessfulJson(responseText, request.url.encodedPath)
            return envelope.optJSONObject("payload")?.optJSONObject("body")
        }
    }

    private fun setupVideoTrack(factory: PeerConnectionFactory, connection: PeerConnection) {
        val capturer = createCameraCapturer()
        val textureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
            ?: throw IllegalStateException("Could not create camera texture helper.")
        val source = factory.createVideoSource(capturer.isScreencast)

        videoCapturer = capturer
        surfaceTextureHelper = textureHelper
        videoSource = source

        capturer.initialize(textureHelper, appContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val track = factory.createVideoTrack("stream_track", source)
        track.setEnabled(true)
        track.addSink(localVideoSink)
        localVideoTrack = track
        connection.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            )
        )
    }

    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(appContext)
        val cameraName = enumerator.deviceNames.firstOrNull(enumerator::isBackFacing)
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No available camera found.")
        return enumerator.createCapturer(cameraName, null)
            ?: throw IllegalStateException("Could not create camera capturer.")
    }

    fun sendHealthPayload(status: String, reason: String) {
        if (closed.get()) return
        val health = JSONObject()
            .put("type", HEALTH_PAYLOAD_TYPE)
            .put("device_user_slug", deviceUserSlug)
            .put("call_id", callId)
            .put("system_status", status)
            .put("failure_reason", reason)
            .put("device_model", deviceModel)
            .put("timestamp", System.currentTimeMillis())

        try {
            persistenceExecutor.execute {
                try {
                    PersistedSignalingQueue.enqueue(
                        appContext,
                        key = deviceUserSlug,
                        payloadType = HEALTH_PAYLOAD_TYPE,
                        payload = health
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not persist health payload.", e)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "Health payload was not queued because the client is closing.")
        }
    }

    private fun submitSignalingTask(task: () -> Unit) {
        try {
            signalingExecutor.execute {
                if (!closed.get()) task()
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            if (!closed.get()) reportFailure("Signaling executor is unavailable.")
        }
    }

    private fun executeHttp(request: Request): Response {
        if (closed.get()) throw InterruptedException("WebRTC client is closed.")
        val call = SignalingHttp.newCall(request)
        activeHttpCalls.add(call)
        if (closed.get()) {
            call.cancel()
            activeHttpCalls.remove(call)
            throw InterruptedException("WebRTC client is closed.")
        }
        try {
            return call.execute()
        } finally {
            activeHttpCalls.remove(call)
        }
    }

    private fun reportFailure(reason: String) {
        if (!closed.get()) onStatusChanged(STATUS_NOT_CONNECTED, false, reason)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return

        // Project significance: release an ICE wait immediately, reject new callback work,
        // stop executor threads, and serialize native-resource cleanup against setup callbacks.
        iceGatheringComplete.countDown()
        activeHttpCalls.forEach { it.cancel() }
        activeHttpCalls.clear()
        signalingExecutor.shutdownNow()
        persistenceExecutor.shutdown()

        synchronized(resourceLock) {
            try {
                videoCapturer?.stopCapture()
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop camera capture cleanly.", e)
            }

            peerConnection?.close()
            localVideoTrack?.removeSink(localVideoSink)
            localVideoTrack?.dispose()
            videoSource?.dispose()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnectionFactory?.dispose()

            peerConnection = null
            localVideoTrack = null
            videoSource = null
            videoCapturer = null
            surfaceTextureHelper = null
            peerConnectionFactory = null
        }
    }

    private companion object {
        const val TAG = "WebRTCClient"
        const val STATUS_CONNECTED = "System is set"
        const val STATUS_NOT_CONNECTED = "System is not set"
        const val OFFER_PAYLOAD_TYPE = "webrtc-offer"
        const val ANSWER_PAYLOAD_TYPE = "webrtc-answer"
        const val HEALTH_PAYLOAD_TYPE = "device-health"
        const val ICE_GATHERING_TIMEOUT_SECONDS = 30L
        const val ANSWER_TIMEOUT_SECONDS = 120L
        const val ANSWER_POLL_INTERVAL_MILLIS = 1_000L
    }
}

/**
 * Persists an encrypted tenant credential for background payload uploads.
 *
 * Project significance: WorkManager can run after the streaming service has stopped,
 * while the credential is not placed in worker input data or alongside plaintext payloads.
 */
internal object TenantCredentialStore {
    private const val PREFERENCES_NAME = "tenant_credentials"
    private const val TENANT_KEY = "tenant_key"

    private fun preferences(context: Context) =
        EncryptedSharedPreferences.create(
            PREFERENCES_NAME,
            MASTER_KEY_ALIAS,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun save(context: Context, tenantKey: String) {
        check(preferences(context).edit().putString(TENANT_KEY, tenantKey).commit()) {
            "Could not save tenant key in encrypted preferences."
        }
    }

    fun read(context: Context): String? =
        preferences(context).getString(TENANT_KEY, null)

    private const val MASTER_KEY_ALIAS = "systemset_tenant_master_key"
}

/**
 * Stores upload bodies in app-private storage and schedules unique WorkManager jobs.
 *
 * Project significance: the offer/health JSON survives temporary disconnection and
 * process death; a worker retries delivery without blocking WebRTC or the service.
 */
private object PersistedSignalingQueue {
    private const val PENDING_DIRECTORY = "pending_signaling"
    private const val FAILED_DIRECTORY = "failed_signaling"
    private const val INPUT_FILE = "payload_file"
    private const val WORK_NAME_PREFIX = "signaling-upload-"

    fun enqueue(context: Context, key: String, payloadType: String, payload: JSONObject) {
        val envelope = JSONObject()
            .put("key", key)
            .put("payload_type", payloadType)
            .put("payload", payload)
        val pendingDirectory = File(context.filesDir, PENDING_DIRECTORY)
        if (!pendingDirectory.exists() && !pendingDirectory.mkdirs()) {
            throw IOException("Could not create signaling queue directory.")
        }

        val fileName = "payload-${UUID.randomUUID()}.json"
        val temporaryFile = File(pendingDirectory, "$fileName.tmp")
        val payloadFile = File(pendingDirectory, fileName)
        temporaryFile.writeText(envelope.toString(), Charsets.UTF_8)
        if (!temporaryFile.renameTo(payloadFile)) {
            temporaryFile.delete()
            throw IOException("Could not persist signaling payload.")
        }
        schedule(context, payloadFile)
    }

    fun recoverPending(context: Context) {
        val directory = File(context.filesDir, PENDING_DIRECTORY)
        directory.listFiles()
            ?.filter { it.isFile && isValidPayloadFile(it) }
            ?.forEach { schedule(context, it) }
    }

    fun schedule(context: Context, payloadFile: File) {
        if (!isValidPayloadFile(payloadFile)) {
            throw IOException("Invalid signaling payload file.")
        }
        val request = OneTimeWorkRequestBuilder<SignalingPayloadUploadWorker>()
            .setInputData(Data.Builder().putString(INPUT_FILE, payloadFile.name).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_NAME_PREFIX${payloadFile.name}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun inputFileNameKey(): String = INPUT_FILE

    fun pendingFile(context: Context, fileName: String): File? {
        if (!FILE_NAME_PATTERN.matches(fileName)) return null
        return File(File(context.filesDir, PENDING_DIRECTORY), fileName)
    }

    fun moveToFailed(context: Context, file: File): Boolean {
        val failedDirectory = File(context.filesDir, FAILED_DIRECTORY)
        if (!failedDirectory.exists() && !failedDirectory.mkdirs()) return false
        return file.renameTo(File(failedDirectory, file.name))
    }

    private fun isValidPayloadFile(file: File): Boolean =
        file.isFile && FILE_NAME_PATTERN.matches(file.name)

    private const val RETRY_BACKOFF_SECONDS = 30L
    private val FILE_NAME_PATTERN = Regex("payload-[a-fA-F0-9-]+\\.json")
}

/**
 * Delivers one persisted payload, retrying I/O and transient server failures.
 *
 * Project significance: HTTP runs on WorkManager's IO dispatcher, not a WebRTC
 * callback; permanent failures are retained in a failed queue for diagnosis.
 */
class SignalingPayloadUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileName = inputData.getString(PersistedSignalingQueue.inputFileNameKey())
            ?: return@withContext Result.failure()
        val file = PersistedSignalingQueue.pendingFile(applicationContext, fileName)
            ?: return@withContext Result.failure()
        if (!file.isFile) {
            Log.w(TAG, "Queued signaling payload no longer exists: $fileName")
            return@withContext Result.success()
        }

        val tenantKey = try {
            TenantCredentialStore.read(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read encrypted tenant key.", e)
            null
        }
        if (tenantKey.isNullOrBlank()) {
            moveToFailed(file)
            return@withContext Result.failure()
        }

        try {
            val envelope = JSONObject(file.readText(Charsets.UTF_8))
            val request = BackendApi.request(
                tenantKey = tenantKey,
                path = BackendApi.TENANT_PAYLOAD_ENDPOINT,
                method = "POST",
                body = envelope
            )
            SignalingHttp.execute(request).use { response ->
                when {
                    response.isSuccessful -> {
                        if (!file.delete()) {
                            Log.w(TAG, "Uploaded payload could not be deleted: $fileName")
                        }
                        Result.success()
                    }
                    response.code == 408 ||
                        response.code == 429 ||
                        response.code >= 500 -> {
                        Log.w(TAG, "Transient payload upload response: HTTP ${response.code}")
                        Result.retry()
                    }
                    else -> {
                        Log.e(TAG, "Payload rejected permanently: HTTP ${response.code}")
                        moveToFailed(file)
                        Result.failure()
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Payload upload will retry after I/O failure.", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Payload could not be decoded or uploaded.", e)
            moveToFailed(file)
            Result.failure()
        }
    }

    private fun moveToFailed(file: File) {
        if (!PersistedSignalingQueue.moveToFailed(applicationContext, file)) {
            Log.e(TAG, "Could not move failed payload to the retained failure queue.")
        }
    }

    private companion object {
        const val TAG = "SignalingPayloadUploadWorker"
    }
}

private object SignalingHttp {
    private val client = OkHttpClient()

    fun newCall(request: Request): okhttp3.Call = client.newCall(request)
    fun execute(request: Request): Response = client.newCall(request).execute()
}

private class SignalingHttpException(
    val code: Int,
    message: String
) : IOException(message)