# Fix Guide: Current SystemSet Issues

This guide maps each finding in `identified_systemset_issues_2.md` to the file to edit, the current insertion/replacement location, and the proposed code or implementation steps. Line numbers refer to the current checkout and can shift after edits; use the described code landmark as the stable location.

> This guide began as a proposal. The implementation status at the end records which changes have since been applied. The sender now targets the shared payload and TURN route contract described in [api-central-hub-connection.md](./api-central-hub-connection.md). The hub and Phonmorn changes remain documentation-only because those repositories are not present in this workspace.

## 1. Restore compilation in `DeviceStreamingService.kt`

### 1a. Make the `when` result explicitly an Android `Intent`

**File:** `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\DeviceStreamingService.kt`  
**Status:** Applied in `DeviceStreamingService.kt`. The `Intent` selection must also handle `restoreStartIntent()` returning `null`; do not copy an old nullable-only snippet without this guard.

```kotlin
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
```

The early `if (streamingActive) return START_REDELIVER_INTENT` guard must remain before this block. It prevents duplicate start commands from replacing an active negotiation.

### 1b. Use the supported encrypted-preferences overload

**File:** `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\DeviceStreamingService.kt`  
**Location:** Replace the entire `sessionPreferences()` function at current lines 246–255.

```kotlin
    private fun sessionPreferences() =
        EncryptedSharedPreferences.create(
            SESSION_PREFERENCES,
            MASTER_KEY_ALIAS,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
```

Add the alias alongside the existing preference constants in the companion object (currently after `SESSION_PREFERENCES` near line 342):

```kotlin
        private const val MASTER_KEY_ALIAS = "systemset_stream_session_key"
```

This uses the String-alias overload available in the configured Security Crypto dependency and avoids constructing the deprecated `MasterKey` API. Remove `import androidx.security.crypto.MasterKey` at current line 17 because it is no longer needed.

### 1c. Fetch credentialed `iceServers` before creating the client

`TurnCredentialProvider.kt` now requests `GET /api/v1/tenant/turn-credentials?device_user_slug=...` using the tenant-key header, validates that the response contains a credentialed TURN/TURNS server, and converts it to WebRTC ICE configuration. `DeviceStreamingService` performs this network request on its startup executor before creating `WebRTCClient`; do not block the Android main thread or embed TURN credentials in the APK.

The API Central Hub endpoint and server-side Coturn HMAC configuration are specified in [api-central-hub-connection.md](./api-central-hub-connection.md). Streaming remains unavailable until those backend changes and TURN listeners are deployed.

## 2. Provision the Android tenant credential

**Required files:**  
- `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\MainActivity.kt`  
- A backend-specific provisioning implementation, which cannot be specified completely until the enrollment route, authentication, and response contract are supplied.

**Do not replace** `startStreamingWithProvisionedCredential()`. Keep that method: it reads the already-provisioned value, fails closed if absent, and starts the foreground service. The missing feature is a trusted enrollment/bootstrap operation that obtains the per-device credential from a defined backend flow.

After an authenticated enrollment request succeeds, add an `onEnrollmentSucceeded(deviceCredential)` helper inside `MainActivity` (for example after `startStreamingWithProvisionedCredential()`) and call it from that request's success callback:

The app-side handoff after a successful enrollment response should follow this shape:

```kotlin
    private fun onEnrollmentSucceeded(deviceCredential: String) {
        require(deviceCredential.isNotBlank()) {
            "Enrollment returned an empty device credential."
        }
        TenantCredentialStore.save(applicationContext, deviceCredential)
        startStreamingWithProvisionedCredential()
    }
```

The helper is not a replacement for the stream-start method and does not implement enrollment by itself. `TenantCredentialStore.save` already exists in `WebRTCClient.kt`. The missing piece is the authenticated enrollment operation and its UI/trigger. Define its HTTP route and response from the actual backend; do not invent a URL, accept a universal tenant secret from an unauthenticated text field, or put a credential in `BuildConfig`, resources, source control, or an APK. If no enrollment operation exists, the operator must securely provision the tenant credential via MDM/another trusted channel or add an enrollment API server-side first.

**Integration requirement:** If the enrollment result is already available in memory, pass it directly into a start method rather than reading a stale value back from preferences. Continue using encrypted storage only where background workers/restarts need it.

## 3. TURN credential retrieval and `iceServers`

**Files:**  
- `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\DeviceStreamingService.kt`  
- `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\TurnCredentialProvider.kt`

`TurnCredentialProvider` now retrieves and validates short-lived credentials from `GET /api/v1/tenant/turn-credentials?device_user_slug=...` over the authenticated backend connection; the service invokes it off the main thread. The response schema and matching API Central Hub implementation are in [api-central-hub-connection.md](./api-central-hub-connection.md).

The provider translates the documented `payload.iceServers` array to WebRTC ICE servers. The client still fails closed if no credentialed TURN/TURNS entry is returned. `DeviceStreamingService` fetches this list before constructing `WebRTCClient` and passes it through the constructor. Do not call the hub synchronously from the service main thread or embed credentials in the APK.

## 4. ICE reconnect implementation

`WebRTCClient.kt` now calls the service on ICE `FAILED` and `DISCONNECTED` states. `DeviceStreamingService.kt` waits 10 seconds for a transient disconnect to recover, then closes the old client and EGL resources, creates and persists a new call ID, fetches fresh TURN credentials, and retries with capped exponential backoff. Stop/destruction invalidates pending startup and reconnect callbacks. The reconnect path is present in the Android source; it requires the hub TURN and keyed signaling changes in the connection guides to be deployed.

## 5. Align signaling routes with the actual backend contract

**Files:**  
- `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\WebRTCClient.kt`  
- `D:\Development\Projects\Android\SystemSet\app\src\androidTest\java\com\example\systemset\BackendApiInstrumentedTest.kt`

**Location:** `WebRTCClient.fetchAnswer()` and `SignalingPayloadUploadWorker` use `BackendApi.TENANT_PAYLOAD_ENDPOINT`. The sender now uses GET query fields `key` and `payload_type`, and POST JSON fields `key`, `payload_type`, and `payload`.

The server routes provided in this task use the same `/api/v1/tenant/payload` path for POST and GET:

```kotlin
    const val TENANT_PAYLOAD_ENDPOINT = "/api/v1/tenant/payload"
```

The test and sender are aligned with the route provided in the task. The Hub's current controller still needs the keyed cache implementation in [api-central-hub-connection.md](./api-central-hub-connection.md); the original controller does not distinguish records by device key.

```kotlin
            path = BackendApi.TENANT_PAYLOAD_ENDPOINT,
            queryParameters = mapOf("key" to key, "payload_type" to "webrtc-answer")
```

The Hub must return the documented response envelope with the stored payload at `payload.body`. Verify the response and call-ID behavior with the cURL checks in the Hub guide before using the connection in production.

**File containing constants:** `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\BackendApi.kt`; `TENANT_PAYLOAD_ENDPOINT` and the TURN route constant are already declared there.

## 6. Configure a stable backend endpoint per build

This is already configured in `app/build.gradle.kts` using Gradle properties with development defaults. Override for a deployment without editing source:

```powershell
.\gradlew.bat :app:assembleDebug `
  -Psystemset.backendBaseUrl=https://your-stable-backend.example `
  -Psystemset.deviceUserSlug=assigned-device-id
```

Use the real HTTPS hub URL and a unique deployment-assigned slug. The URL and slug are configuration, not secrets. Never pass the tenant key or TURN secret with `-P` or generate either into `BuildConfig`.

**File:** `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\BackendApi.kt`  
`BASE_URL` already reads the generated value:

```kotlin
    const val BASE_URL = BuildConfig.BACKEND_BASE_URL
```

## 7. Instrumentation test source set

The move and Gradle setup are already applied. The file is now located at:

`D:\Development\Projects\Android\SystemSet\app\src\androidTest\java\com\example\systemset\BackendApiInstrumentedTest.kt`

The package declaration at line 1 remains `package com.example.systemset`.

The module already configures:

```kotlin
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
```

Run on a connected Android device or emulator:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The test sources compile locally, but a successful Kotlin compile alone does not execute instrumentation tests.

## 8. Give every restored negotiation a fresh call ID

**File:** `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\DeviceStreamingService.kt`

Add this import beside the other `java.*` imports (or add it if no `java.*` imports exist):

```kotlin
import java.util.UUID
```

**Location:** In `onStartCommand()` at current lines 66–89, rename the value read from the incoming intent:

```kotlin
        val requestedCallId = startIntent.getStringExtra(EXTRA_CALL_ID).orEmpty()
```

Keep `requestedCallId` in the required-parameter check. Immediately after that check, insert:

```kotlin
        if (streamingActive) return START_REDELIVER_INTENT

        // A newly created service may receive Android's redelivered old start Intent.
        // Rotate the ID before negotiating so an answer from the previous peer connection
        // cannot match this session.
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
```

Replace the existing conditional `if (intent?.action == ACTION_START) { ... }` save block with that unconditional `try` block. Then replace the following `if (!streamingActive) { ... }` wrapper with a direct `if (!startStreaming(deviceUserSlug, callId, tenantKey, deviceModel)) return START_NOT_STICKY`. The early return above prevents a duplicate start command from changing the ID for a stream already running.

The saved start configuration now contains the rotated ID. Android may still redeliver its older Intent after a later process death, but the new service instance rotates the ID again before creating a peer connection. Confirm the backend expires or isolates answers by call ID; the client already filters returned answers by the active ID.

## 9. Apply the existing backup exclusions

**File:** `D:\Development\Projects\Android\SystemSet\app\src\main\AndroidManifest.xml`  
**Location:** Replace the opening `<application ...>` attributes at current lines 12–16 with:

```xml
    <application
        android:allowBackup="true"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/systemset_logo"
        android:label="@string/app_name"
        android:theme="@style/Theme.MaterialComponents.DayNight.DarkActionBar">
```

The referenced rule files already exist. Review platform-specific backup behavior for all supported Android versions. If backup is not a product requirement, the safer simpler option is `android:allowBackup="false"` and removing the backup rule attributes; do not enable backup of tenant credentials.

## 10. Remove the hard-coded device slug

This uses the `BuildConfig.DEVICE_USER_SLUG` field and per-deployment property added in [Issue 6](#6-configure-a-stable-backend-endpoint-per-build).

**File:** `D:\Development\Projects\Android\SystemSet\app\src\main\java\com\example\systemset\MainActivity.kt`

1. At current line 145, replace:

```kotlin
            putExtra(DeviceStreamingService.EXTRA_DEVICE_USER_SLUG, DEVICE_USER_SLUG)
```

with:

```kotlin
            putExtra(
                DeviceStreamingService.EXTRA_DEVICE_USER_SLUG,
                BuildConfig.DEVICE_USER_SLUG
            )
```

2. In the companion object at current lines 298–303, remove:

```kotlin
        const val DEVICE_USER_SLUG = "kampala-home"
```

Do not allow multiple devices to share a slug unless the backend explicitly supports that identity model.

## 11. Gradle wrapper

The Gradle wrapper has been generated in the project. Verify it from the project root:

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:compileDebugKotlin
```

## Verification checklist

1. Set the backend URL and unique device slug as Gradle properties at build time.
2. Implement the actual enrollment and TURN-credential backend contracts; do not add sample secrets.
3. Confirm signaling routes, payload envelopes, answer key, and call-ID behavior with the server owner.
4. Build and compile the Android app and instrumentation sources with the wrapper.
5. Run `:app:connectedDebugAndroidTest` on a device/emulator.
6. Exercise fresh install, denied permissions, missing enrollment, missing TURN service, network loss/recovery, explicit stop, and Android process recreation.

## Implementation status (2026-11-04)

Applied in the Android project:

- Fixed service intent null handling, saved session preferences with the String master-key alias, and assigned a fresh call ID when processing a new service start.
- Added asynchronous retrieval of credentialed TURN configuration from `GET /api/v1/tenant/turn-credentials` and wired the parsed ICE servers into `WebRTCClient`; startup fails visibly when the hub does not provide TURN.
- Made backend URL and device slug Gradle-overridable (`-Psystemset.backendBaseUrl=...` and `-Psystemset.deviceUserSlug=...`) while retaining existing development defaults.
- Connected the existing backup exclusion files to the manifest.
- Moved `BackendApiInstrumentedTest.kt` into `app/src/androidTest/java/com/example/systemset/`, configured the instrumentation runner/dependencies, and generated the Gradle wrapper.
- Aligned Android signaling to the provided hub routes: GET and POST `/api/v1/tenant/payload`, with `key`, `payload_type`, and `payload` fields; requests authenticate with `X-Tenant-Key`.
- Added bounded reconnection after ICE failure or a 10-second disconnected grace period, with fresh call IDs and TURN retrieval.
- Preserved actionable startup, TURN, and reconnect-exhaustion reasons in the stopped service status rather than replacing them with a generic stopped message.

Not implemented because required contracts/services are absent:

- Authenticated enrollment and initial per-device credential provisioning. The app still needs an out-of-band secure mechanism to provision the tenant key before first use.
- Deployment of the documented hub keyed payload-cache change, TURN endpoint, Coturn listener/certificate, and matching server-side secrets.
- Execution of the Phonmorn and API Central Hub PHP/Laravel tests; those project checkouts are not available here.

The Android instrumentation test source compiles, but execution requires a connected Android device/emulator. Cross-app cURL and ICE acceptance procedures are in [api-central-hub-connection.md](./api-central-hub-connection.md) and [web-app-connection.md](./web-app-connection.md).
