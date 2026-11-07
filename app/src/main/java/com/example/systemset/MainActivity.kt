package com.example.systemset

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var logcatTextView: TextView
    private lateinit var btnToggleStream: Button
    private var isStreamingActive = false
    private var isActivityStarted = false

    @Volatile
    private var logcatProcess: Process? = null

    @Volatile
    private var logcatReaderThread: Thread? = null

    private val logcatGeneration = AtomicInteger(0)
    private val visibleLogLines = ArrayDeque<String>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) {
                startStreamingWithProvisionedCredential()
            } else {
                updateSystemStatus(
                    STATUS_STOPPED,
                    "Camera permission denied; streaming was not started."
                )
            }
        }

    private val streamingStatusListener: (String, String, Boolean) -> Unit =
        { status, reason, active ->
            runOnUiThread {
                if (!isActivityStarted) return@runOnUiThread
                isStreamingActive = active
                btnToggleStream.text = getString(
                    if (active) R.string.stop_streaming else R.string.start_streaming
                )
                updateSystemStatus(status, reason)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        logcatTextView = findViewById(R.id.logcatTextView)
        btnToggleStream = findViewById(R.id.btnToggleStream)

        updateSystemStatus(STATUS_STOPPED, "Awaiting manual start activation.")
        btnToggleStream.setOnClickListener {
            if (isStreamingActive) {
                stopStreaming()
            } else {
                checkAndRequestPermissions()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true

        // Project significance: status and the start/stop control follow the service's
        // actual state, including when this activity is recreated while streaming continues.
        DeviceStreamingService.setUiListener(streamingStatusListener)
        startLogcatStreamReader()
    }

    override fun onStop() {
        isActivityStarted = false

        // Project significance: release the activity callback and Logcat process when the
        // screen is no longer visible, preventing stale UI references and leaked processes.
        DeviceStreamingService.setUiListener(null)
        stopLogcatStreamReader()
        super.onStop()
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startStreamingWithProvisionedCredential()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startStreamingWithProvisionedCredential() {
        val tenantKey = try {
            TenantCredentialStore.read(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read provisioned tenant credential.", e)
            null
        }

        if (tenantKey.isNullOrBlank()) {
            // Project significance: fail closed instead of shipping a reusable tenant
            // credential in the APK or starting a stream with empty authentication.
            updateSystemStatus(
                STATUS_STOPPED,
                "No tenant credential is provisioned. Enroll this device securely, then retry."
            )
            return
        }

        val intent = Intent(this, DeviceStreamingService::class.java).apply {
            action = DeviceStreamingService.ACTION_START
            putExtra(DeviceStreamingService.EXTRA_TENANT_KEY, tenantKey)
            putExtra(
                DeviceStreamingService.EXTRA_DEVICE_USER_SLUG,
                BuildConfig.DEVICE_USER_SLUG
            )
            // Project significance: a new session ID prevents the receiver from mistaking
            // a cached answer from an earlier stream for this stream's negotiation response.
            putExtra(DeviceStreamingService.EXTRA_CALL_ID, "session_${UUID.randomUUID()}")
        }

        try {
            ContextCompat.startForegroundService(this, intent)
            isStreamingActive = true
            btnToggleStream.text = getString(R.string.stop_streaming)
            updateSystemStatus(STATUS_STARTING, "Starting camera stream.")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start streaming service.", e)
            isStreamingActive = false
            btnToggleStream.text = getString(R.string.start_streaming)
            updateSystemStatus(
                STATUS_STOPPED,
                e.message ?: "Android could not start the streaming service."
            )
        }
    }
    
    

    private fun stopStreaming() {
        val intent = Intent(this, DeviceStreamingService::class.java).apply {
            action = DeviceStreamingService.ACTION_STOP
        }

        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not send stop request to streaming service.", e)
            updateSystemStatus(
                STATUS_STOPPED,
                e.message ?: "Could not stop the streaming service."
            )
        }
    }

    /**
     * Formats status details and scales long text to fit the status view.
     */
    private fun updateSystemStatus(status: String, reason: String) {
        val displayText = if (reason.isNotBlank() && status == STATUS_STOPPED) {
            "$status\n($reason)"
        } else if (reason.isNotBlank() && status == STATUS_STARTING) {
            "$status\n($reason)"
        } else {
            status
        }
        statusTextView.text = displayText

        statusTextView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                displayText.length > 60 -> 12f
                displayText.length > 30 -> 15f
                else -> 18f
            }
        )
    }

    private fun startLogcatStreamReader() {
        stopLogcatStreamReader()
        val generation = logcatGeneration.incrementAndGet()

        val readerThread = Thread(
            {
                var process: Process? = null
                try {
                    // Project significance: -d is intentionally omitted so this reader follows
                    // new app-tagged events while the activity is visible.
                    process = ProcessBuilder(
                        "logcat",
                        "-v",
                        "time",
                        "-s",
                        "WebRTCClient:D",
                        "DeviceStreamingService:D",
                        "MainActivity:D",
                        "*:S"
                    ).redirectErrorStream(true).start()
                    logcatProcess = process

                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (!Thread.currentThread().isInterrupted &&
                            generation == logcatGeneration.get()
                        ) {
                            val line = reader.readLine() ?: break
                            runOnUiThread {
                                if (isActivityStarted && generation == logcatGeneration.get()) {
                                    appendLogLine(line)
                                }
                            }
                        }
                    }

                    if (generation == logcatGeneration.get()) {
                        val exitCode = process.waitFor()
                        runOnUiThread {
                            if (isActivityStarted && generation == logcatGeneration.get()) {
                                appendLogLine("Logcat process ended (exit $exitCode).")
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Could not start/read Logcat.", e)
                    runOnUiThread {
                        if (isActivityStarted && generation == logcatGeneration.get()) {
                            appendLogLine("Logcat read error: ${e.message}")
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    process?.destroy()
                    if (logcatProcess === process) logcatProcess = null
                    if (logcatReaderThread === Thread.currentThread()) {
                        logcatReaderThread = null
                    }
                }
            },
            "SystemSet-LogcatReader"
        ).apply {
            isDaemon = true
            start()
        }

        logcatReaderThread = readerThread
    }

    private fun appendLogLine(line: String) {
        visibleLogLines.addLast(line)
        while (visibleLogLines.size > MAX_VISIBLE_LOG_LINES) {
            visibleLogLines.removeFirst()
        }
        logcatTextView.text = visibleLogLines.joinToString(separator = "\n")
    }

    private fun stopLogcatStreamReader() {
        logcatGeneration.incrementAndGet()
        logcatProcess?.let { process ->
            try {
                process.inputStream.close()
            } catch (e: IOException) {
                Log.d(TAG, "Logcat stream was already closed.", e)
            }
            process.destroy()
        }
        logcatProcess = null
        logcatReaderThread?.interrupt()
        logcatReaderThread = null
    }

    private companion object {
        const val TAG = "MainActivity"
        const val MAX_VISIBLE_LOG_LINES = 500
        const val STATUS_STARTING = "System is starting"
        const val STATUS_STOPPED = "System is not set"
    }
}