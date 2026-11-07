# Current SystemSet Issues

## Scope and verification

This audit covers the authored Android app source, manifest, Gradle configuration, resources, and Android instrumentation test source in the current checkout. Generated Gradle/build output is excluded. No backend implementation or backend contract document is present in this checkout, so server behavior is identified as unverified rather than assumed.

The initial Kotlin compile check was run with:

```text
gradle :app:compileDebugKotlin
```

It initially failed in `DeviceStreamingService.kt` with unresolved `Intent`/encrypted-preferences calls and a missing `iceServers` constructor argument. Those compile issues have since been fixed. The latest Android instrumentation sources compile, but running device tests requires a connected emulator/device.

> **Status update (2026-11-04):** The compile blockers and several repository configuration issues below have since been fixed. The Android instrumentation test compiles but cannot execute without a connected device/emulator. See each issue's current status and the latest task results.

## Issues, ordered by impact

### 1. The app did not compile — **Resolved**

`DeviceStreamingService.kt` has a set of compile failures:

- The service start-intent handling at lines 40–68 leads to unresolved `getStringExtra` calls.
- The `WebRTCClient` construction at lines 135–145 omits the required `iceServers` argument.
- The encrypted preferences call at lines 246–255 does not match either available `EncryptedSharedPreferences.create` overload. The subsequent unresolved `edit`, `getString`, `putExtra`, and `commit` diagnostics also occur in this section.

These failures were confirmed by the original Gradle compile. Service intent handling and encrypted-preferences usage have been corrected. The service now retrieves TURN configuration asynchronously before constructing the WebRTC client. `:app:assembleDebug` succeeds.

**Original evidence:** `app/src/main/java/com/example/systemset/DeviceStreamingService.kt:40-68,135-145,206-255`

### 2. A fresh install has no way to provision the tenant credential — **Blocker, confirmed in app flow**

`MainActivity` reads the tenant key from `TenantCredentialStore` and stops if the value is missing. The only visible save call is inside `WebRTCClient` initialization, which occurs only after the service has already received a tenant key and started streaming. There is no enrollment UI, provisioning API, or other in-app route to seed the store.

Therefore, unless another unprovided mechanism preloads the encrypted preference, the normal user flow cannot start streaming on a fresh install.

**Evidence:** `app/src/main/java/com/example/systemset/MainActivity.kt:122-139`; `app/src/main/java/com/example/systemset/WebRTCClient.kt:73-84,481-501`

### 3. TURN configuration requires the documented hub endpoint — **Deployment blocker**

`WebRTCClient` requires at least one TURN/TURNS server with non-empty username and password. SystemSet now asynchronously requests `GET /api/v1/tenant/turn-credentials?device_user_slug=...` using the `X-Tenant-Key` header and validates the returned `payload.iceServers`. The corresponding hub route, server-side HMAC configuration, and Coturn credentials/listeners must be deployed as described in `api-central-hub-connection.md`; until then the request fails closed and streaming cannot start.

**Evidence:** `app/src/main/java/com/example/systemset/DeviceStreamingService.kt`; `app/src/main/java/com/example/systemset/TurnCredentialProvider.kt`; `api-central-hub-connection.md`

### 4. ICE reconnection is bounded and requires working TURN/signaling — **Mitigated**

`DeviceStreamingService` now waits 10 seconds before acting on `DISCONNECTED`, reconnects immediately on `FAILED`, refreshes TURN credentials, creates a new peer connection and call ID, and caps retries with exponential backoff. It cannot restore service if the hub/TURN endpoint is unavailable or if the signaling/backend contracts are not deployed.

**Evidence:** `app/src/main/java/com/example/systemset/WebRTCClient.kt`; `app/src/main/java/com/example/systemset/DeviceStreamingService.kt`

### 5. Signaling API compatibility requires hub deployment — **Deployment blocker**

SystemSet now uses the routes provided in the user-supplied hub code: POST and GET `/api/v1/tenant/payload`, with the mailbox key and payload type in the JSON body or GET query. The original controller only keyed cache entries by tenant and payload type, so it cannot isolate multiple devices or retain distinct offer/answer records for one tenant. Deploy the keyed controller change and Phonmorn proxy described in the connection guides before relying on signaling.

**Evidence:** `app/src/main/java/com/example/systemset/WebRTCClient.kt`; `app/src/main/java/com/example/systemset/BackendApi.kt`; user-provided `TenantPayloadController` cache key and `retrieve()` logic.

### 6. The app is pinned to a development ngrok hostname — **High operational risk**

`BackendApi.BASE_URL` now comes from generated `BuildConfig` and can be overridden with the Gradle property `systemset.backendBaseUrl`. Its default remains the development ngrok-free hostname, however, so production builds must explicitly provide a stable backend URL. If the default tunnel expires or changes, requests fail.

**Evidence:** `app/src/main/java/com/example/systemset/BackendApi.kt:10-13`

### 7. The instrumentation test source set — **Partially resolved**

`BackendApiInstrumentedTest.kt` has been moved to `app/src/androidTest/java/com/example/systemset/`, and the module now declares an instrumentation runner and test dependencies. Gradle compiles the test source. The test itself has not executed because no Android device/emulator is connected.

**Evidence:** `app/src/androidTest/java/com/example/systemset/BackendApiInstrumentedTest.kt`; `app/build.gradle.kts` instrumentation runner and `androidTestImplementation` dependencies. Test execution still requires a connected device/emulator.

### 8. Service recovery reuses the previous call ID and may accept a stale answer — **Mitigated, backend-dependent**

The service now generates and saves a fresh call ID when processing a start command, including a redelivered command after process recreation. This prevents a prior answer from matching solely because the client reused the old call ID. The backend must still honor call IDs and avoid returning stale payloads.

Continue requiring the backend to expire/version answers so stale negotiations cannot match.

**Evidence:** `app/src/main/java/com/example/systemset/DeviceStreamingService.kt:206-234`; `app/src/main/java/com/example/systemset/WebRTCClient.kt:277-303`

### 9. Backup policy resources are not connected to the manifest — **Resolved**

The manifest now references both provided backup-rule files, which exclude app data from backup and device transfer. The app stores tenant credentials and stream configuration in encrypted preferences; restore behavior should still be verified on supported Android versions.

Restore behavior should still be verified on supported Android versions.

**Evidence:** `app/src/main/AndroidManifest.xml:10-14`; `app/src/main/res/xml/backup_rules.xml`; `app/src/main/res/xml/data_extraction_rules.xml`; `app/src/main/java/com/example/systemset/WebRTCClient.kt:481-501`; `app/src/main/java/com/example/systemset/DeviceStreamingService.kt:206-255`

### 10. The device routing slug is configurable — **Mitigated**

`MainActivity` now reads the slug from generated `BuildConfig`, which is configurable with the Gradle property `systemset.deviceUserSlug`. The default remains `kampala-home` for compatibility, so deployments must provide a unique slug to avoid key collisions.

**Evidence:** `app/src/main/java/com/example/systemset/MainActivity.kt` reads `BuildConfig.DEVICE_USER_SLUG`; `app/build.gradle.kts` configures the `systemset.deviceUserSlug` Gradle property with a backwards-compatible default.

### 11. There was no Gradle wrapper — **Resolved**

The Gradle wrapper has been generated for Gradle 9.8.2, allowing builds through `gradlew.bat` without a separately installed Gradle version. Confirm wrapper/Android Gradle Plugin compatibility when changing either version.

**Evidence:** repository root contains `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties`, but no wrapper files or `gradle/wrapper` directory.

## Remaining work, ordered by dependency

1. Deploy the keyed tenant mailbox controller/routes and TURN endpoint from `api-central-hub-connection.md`.
2. Deploy Phonmorn's server-side hub client, authenticated proxy routes, and offer/answer viewer from `web-app-connection.md`.
3. Configure and verify Coturn, including its HMAC secret, public listeners, firewall, and TLS certificate.
4. Provision the Android sender's tenant credential securely; no enrollment route or setup path is provided in this project.
5. Run the documented cURL, Laravel, Coturn allocation, and end-to-end ICE checks against the deployed services.
6. Run Android instrumentation tests on a connected device/emulator and test process recreation and network changes.
7. Set a production backend URL and unique device slug rather than relying on development defaults.
