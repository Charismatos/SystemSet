# How System Set Works

## 1. Purpose and system boundary

System Set is a native Android **WebRTC video sender**. It captures video from the
device camera, negotiates a peer connection with a receiver, and sends signaling
records and device-health updates through an API Central Hub.

The intended end-to-end system has three applications:

1. **System Set** — this Android project; captures and publishes camera video.
2. **API Central Hub** — the tenant-authenticated mailbox for WebRTC signaling and
   the issuer of short-lived TURN credentials.
3. **A receiver** — Phonmorn, a Laravel web viewer, or StreamDisplayer, a native
   Android viewer. A receiver fetches the offer, creates and publishes the answer,
   then renders received media.

The hub is a rendezvous and credential service. It is not a WebRTC media server:
the video flows over the negotiated peer connection, not through the hub. The
receiver and hub implementation are not included in this Android checkout.

## 2. Important distinction: implemented versus planned integration

The Android source contains the sender-side HTTP, TURN, WebRTC, persistence, and
reconnection code described below. The Laravel instructions in
[api-central-hub-connection.md](./api-central-hub-connection.md) and
[web-app-connection.md](./web-app-connection.md) are implementation guides; the
corresponding Laravel projects are not present here, and their routes and tests
have not been applied or verified by this repository.

In particular:

- The Android app requires a tenant key to be provisioned into encrypted storage
  before a user can start streaming. This project has no enrollment screen or
  enrollment endpoint to do that on a fresh install.
- The Android app requests a TURN endpoint at
  `GET /api/v1/tenant/turn-credentials`. It will not start the peer connection
  unless the response includes at least one credentialed TURN/TURNS server.
- Android signaling expects the hub mailbox contract documented below. Deploying
  only the originally supplied hub routes/controller is not sufficient if it
  does not support a mailbox key per device and payload type.
- The viewer and its Laravel server-side proxy are not implemented in this Android
  project. The browser must not receive the hub's long-lived tenant key.

Consequently, a successful Android build does not by itself prove that live
streaming works. The Hub, Coturn, enrollment, and receiver must also be configured
and tested together.

## 3. Startup-to-stream flow

### Step 1: Android launches the activity

The launcher activity is `MainActivity`, declared in
[AndroidManifest.xml](./app/src/main/AndroidManifest.xml). It loads
[activity_main.xml](./app/src/main/res/layout/activity_main.xml), binds the
status label, diagnostics text, and start/stop button, then reports an initial
stopped status.

When visible, the activity attaches a status listener to the streaming service
and starts a Logcat reader filtered to the app's WebRTC, service, and activity
tags. When it stops being visible, it detaches the listener, stops the Logcat
process/thread, and avoids retaining a stale activity reference.

### Step 2: The user requests a stream

The start/stop button starts the stream or sends the service a stop action,
depending on the current service state.

Before starting, `MainActivity` requests camera permission, and requests
notification permission on Android 13 and later. It then reads the tenant key
from `TenantCredentialStore` in encrypted shared preferences. If there is no key,
the app shows an explicit enrollment/provisioning message and does not start.

The app gets its backend URL and device slug from generated `BuildConfig` values.
These are configured in [app/build.gradle.kts](./app/build.gradle.kts), with
Gradle-property overrides:

```powershell
.\gradlew.bat :app:assembleDebug `
  -Psystemset.backendBaseUrl=https://your-hub.example `
  -Psystemset.deviceUserSlug=assigned-device-id
```

The backend URL and slug are configuration, not substitutes for credentials.
The tenant key must not be put into `BuildConfig`, source code, or a committed
Gradle property.

### Step 3: The foreground service takes ownership

`MainActivity` starts `DeviceStreamingService` with the tenant key, device slug,
and a session call ID. The service validates these values, creates a new UUID-based
call ID, and stores the restart configuration in encrypted preferences.

The service then creates a persistent camera foreground-service notification
with a Stop action. It notifies the activity that startup is in progress. The
manifest declares the camera and foreground-service permissions and registers the
service as a non-exported camera foreground service.

If Android recreates the service with a null start intent, it attempts to restore
the encrypted session configuration and generates a new call ID for the new
negotiation. Explicitly stopping the stream clears that saved session. Service
destruction releases resources while preserving configuration for an eligible
system restart.

### Step 4: The service requests fresh TURN credentials

`DeviceStreamingService` runs the network request on a background executor through
`TurnCredentialProvider`. It sends:

```text
GET /api/v1/tenant/turn-credentials?device_user_slug=<device slug>
X-Tenant-Key: <provisioned tenant key>
Accept: application/json
```

It expects a JSON envelope with `payload.iceServers`. At least one ICE server
must include a `turn:` or `turns:` URL plus a non-empty username and credential.
STUN entries alone do not satisfy the requirement. Invalid, missing, or
unsuccessful TURN responses fail startup visibly rather than allowing a
STUN-only configuration to appear successful.

The hub must generate short-lived credentials whose HMAC format, secret, host,
ports, and TLS configuration match the deployed Coturn server. The HMAC secret
stays server-side; the temporary TURN username and password are passed to
WebRTC, where they are needed to create relay candidates.

### Step 5: The WebRTC client captures camera video

After TURN configuration succeeds, the service creates an EGL context and a
`WebRTCClient`. The client validates its device slug, tenant key, call ID, and
credentialed TURN server, then initializes the WebRTC peer-connection factory.

The client uses Android's Camera2 enumerator, preferring the back-facing camera
and falling back to another available camera. It captures at 1280x720 and 30 fps,
creates a video track, and adds a send-only transceiver to the peer connection.
The EGL, camera capturer, texture helper, source, track, factory, and peer
connection are all released when the client closes.

### Step 6: System Set creates and publishes an SDP offer

The client creates a WebRTC offer and sets it as its local description. It uses
**non-trickle ICE**: candidate updates are not sent as separate mailbox records.
Instead, it waits up to 30 seconds for ICE gathering to complete, then reads the
final local SDP containing the gathered candidates.

The offer body contains:

- `type`: `webrtc-offer`
- `device_user_slug`
- `call_id`
- `device_model`
- `sdp`
- `timestamp`

The outer hub payload envelope is:

```json
{
  "key": "device-slug",
  "payload_type": "webrtc-offer",
  "payload": {
    "type": "webrtc-offer",
    "device_user_slug": "device-slug",
    "call_id": "session-uuid",
    "sdp": "v=0...",
    "timestamp": 0
  }
}
```

The offer is written first to an app-private pending queue, then a unique
WorkManager job is scheduled to upload it. This keeps disk and HTTP work out of
WebRTC callbacks and allows queued offers to survive process death or temporary
network loss.

### Step 7: WorkManager delivers queued signaling and health records

The pending queue stores JSON files under app-private files. Each job is
constrained to run when a network is available and uses exponential retry
backoff. `SignalingPayloadUploadWorker` reads the tenant key from encrypted
preferences rather than placing the key in WorkManager input data or alongside
the queued payload.

For each upload, the worker sends:

```text
POST /api/v1/tenant/payload
X-Tenant-Key: <provisioned tenant key>
Content-Type: application/json
```

Successful uploads delete their pending file. I/O failures and HTTP 408, 429,
or 5xx responses are retried. Other HTTP failures are moved to an app-private
failed queue for diagnosis. On WebRTC client initialization, any still-pending
valid files are rescheduled.

The same queue carries `device-health` records with the device slug, call ID,
system status, failure reason, device model, and timestamp. Health delivery is
asynchronous and best-effort; it is not proof that the receiver is displaying
video.

### Step 8: The receiver retrieves the offer and publishes an answer

The intended receiver polls the hub for a record using the same tenant identity,
device key, and payload type. It sets the offer SDP as its remote description,
creates an answer, gathers its own ICE candidates, and publishes the answer under
`webrtc-answer` for the same key.

The documented mailbox lookup is:

```text
GET /api/v1/tenant/payload?key=<device slug>&payload_type=webrtc-offer
GET /api/v1/tenant/payload?key=<device slug>&payload_type=webrtc-answer
```

The documented response places the record at `payload.body`. The hub mailbox
must isolate records by **tenant + payload type + mailbox key**, so devices do
not overwrite one another and an offer cannot replace its answer. The current
contract expects new records to replace prior records for that same combination
and to expire after a configured TTL.

For Phonmorn, the browser should call authenticated same-origin Laravel proxy
routes. Phonmorn's server sends `X-Tenant-Key` to the hub. Do not render that
long-lived tenant key into HTML or JavaScript. The browser does use the
short-lived TURN credentials when constructing its peer connection.

### Step 9: System Set polls for a matching answer

While its peer connection is open, System Set polls the hub once per second for
`webrtc-answer`, using the device slug as the mailbox key. A 404 means no answer
has arrived yet. It waits for at most 120 seconds, tolerates I/O failures and
transient HTTP 408, 429, and 5xx errors, and rejects answers unless both
`type=webrtc-answer` and `call_id` match the active negotiation.

After finding an answer, it validates that SDP is present and sets it as the
remote description. Setting the remote SDP completes signaling negotiation,
but does **not** by itself mean the WebRTC connection is healthy.

### Step 10: ICE establishes the media path

The peer connection uses the ICE servers returned by the hub to find a direct
or relayed network path. A TURN server is important where direct connectivity
fails because of NAT or firewalls. The peer connection observer reports
`CONNECTED` or `COMPLETED` as healthy only after ICE actually establishes a
connection.

At that point, the service updates the foreground activity's status to
“System is set” and queues a `device-health` record. The receiver should also
confirm it receives a video track and that its video element/view renders frames.
An offer in the mailbox, a successful answer, or a stable signaling state alone
does not prove media is flowing.

## 4. Failure, stop, and reconnection behavior

- **Missing camera permission:** startup is not attempted; the UI reports that
  permission was denied.
- **Missing tenant key:** the activity stops before starting the service and
  explains that secure device enrollment/provisioning is needed.
- **TURN endpoint failure or invalid configuration:** initial startup fails
  visibly. A failure during reconnection consumes another bounded retry.
- **ICE `DISCONNECTED`:** the service allows a 10-second grace period for
  recovery before creating a new negotiation.
- **ICE `FAILED`:** the service starts reconnection without the disconnect grace
  period.
- **Reconnection:** old WebRTC/EGL resources are released, a fresh call ID is
  persisted, TURN credentials are fetched again, and a new peer connection is
  created. Retries use exponential delays capped at one minute, with at most five
  scheduled attempts.
- **Stop:** the service cancels pending startup/reconnect callbacks, asks the
  client to queue a stopped health status, closes the client and camera resources,
  removes the foreground notification, clears encrypted restart configuration,
  and stops itself.
- **Service/process destruction:** native WebRTC and EGL resources are released.
  The service's encrypted restart configuration is retained on destruction so
  Android can attempt recovery; an explicit stop clears it.

Errors and state changes are written to Android Logcat. The activity's diagnostics
panel tails selected tags while visible and retains at most 500 displayed lines.
The panel is a convenience for diagnosis, not a server-side audit log.

## 5. Project files and their responsibilities

### Android runtime

- [MainActivity.kt](./app/src/main/java/com/example/systemset/MainActivity.kt) —
  permissions, tenant credential lookup, start/stop UI, service binding through a
  status listener, and visible Logcat tail.
- [DeviceStreamingService.kt](./app/src/main/java/com/example/systemset/DeviceStreamingService.kt) —
  foreground camera service, encrypted restart state, TURN-fetch lifecycle,
  service status, and bounded ICE reconnection.
- [WebRTCClient.kt](./app/src/main/java/com/example/systemset/WebRTCClient.kt) —
  WebRTC factory and peer connection, camera/video track, offer/answer handling,
  health events, persistent signaling queue, credential storage, upload worker,
  and HTTP calls.
- [TurnCredentialProvider.kt](./app/src/main/java/com/example/systemset/TurnCredentialProvider.kt) —
  authenticated TURN endpoint request and strict ICE server parsing/validation.
- [BackendApi.kt](./app/src/main/java/com/example/systemset/BackendApi.kt) —
  backend URL use, shared endpoint constants, tenant-key headers, query creation,
  JSON request bodies, and malformed-JSON reporting.

### Android resources and configuration

- [AndroidManifest.xml](./app/src/main/AndroidManifest.xml) —
  camera/network/foreground-service/notification permissions, launcher activity,
  and streaming-service declaration. It references both backup-rule resources.
- [activity_main.xml](./app/src/main/res/layout/activity_main.xml) —
  status, start/stop button, and diagnostics layout.
- [strings.xml](./app/src/main/res/values/strings.xml) — app name and start/stop
  labels.
- [backup_rules.xml](./app/src/main/res/xml/backup_rules.xml) and
  [data_extraction_rules.xml](./app/src/main/res/xml/data_extraction_rules.xml) —
  exclude app data from cloud backup and device transfer.
- [app/build.gradle.kts](./app/build.gradle.kts) — Android namespace and SDK
  levels (`minSdk 24`, `compileSdk 37`, `targetSdk 37`), generated BuildConfig
  values, release shrinking, libraries, and instrumentation-test dependencies.
- [build.gradle.kts](./build.gradle.kts), [settings.gradle.kts](./settings.gradle.kts),
  [gradle.properties](./gradle.properties), and
  [gradle/wrapper/gradle-wrapper.properties](./gradle/wrapper/gradle-wrapper.properties) —
  plugin version, module/repository setup, Gradle settings, and wrapper version.
- The app module uses AndroidX/AppCompat, Android Security Crypto, WorkManager,
  OkHttp, and the WebRTC Android SDK. Release builds enable code and resource
  shrinking; debug is the variant used by the commands below.

### Tests and integration guides

- [BackendApiInstrumentedTest.kt](./app/src/androidTest/java/com/example/systemset/BackendApiInstrumentedTest.kt) —
  AndroidJUnit4 tests for request URL/method/headers, JSON request bodies, JSON
  parsing failures, and TURN response validation. It is an instrumentation test,
  so it must run on an emulator or connected Android device.
- [api-central-hub-connection.md](./api-central-hub-connection.md) —
  proposed Hub payload/TURN contract, Laravel changes, cURL/Coturn checks, and
  end-to-end acceptance steps.
- [web-app-connection.md](./web-app-connection.md) —
  proposed Phonmorn server-side Hub client, authenticated browser proxy, viewer,
  and validation steps.
- [identified_systemset_issues_2.md](./identified_systemset_issues_2.md) and
  [fixed_systemset_issues_2.md](./fixed_systemset_issues_2.md) — issue/status
  history and implementation guidance. Treat status against the source and
  actual deployments; the guides cannot prove a backend feature is deployed.

Generated files under `app/build/` and `.gradle/` are build/cache output, not
hand-maintained runtime source.

## 6. Build and test

From the project root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

To compile the Android instrumentation-test source without executing it:

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

To install and run the instrumentation tests, start an emulator or connect a
device with USB debugging enabled, verify that `adb devices` lists it with
status `device`, then run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The test source validates local request construction and parsing behavior; it
does not call a live Hub, verify Coturn credentials, test Phonmorn, or prove that
ICE/media works across networks. Those checks require the deployed services and
the integration procedures in the connection guides.
