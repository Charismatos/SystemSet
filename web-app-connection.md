# Phonmorn / System Set WebRTC integration

This guide owns the **application-specific** WebRTC, device, TURN, and ICE
requirements for connecting System Set to the Phonmorn Laravel viewer through
API Central Hub. The Hub is a project-agnostic platform; its generic
tenant-scoped payload transport is described in
[api-central-hub-connection.md](./api-central-hub-connection.md). WebRTC
semantics and payload schemas must remain isolated to this integration rather
than being made mandatory for unrelated Hub tenants.

The future StreamDisplayer receiver can use the same application contract if it
is enrolled under the same tenant and its own receiver authorization rules.

The Laravel projects are not checked out alongside SystemSet in this workspace. The snippets and tests below have not been installed or run against Phonmorn or the hub.

## Security and assumptions

- Phonmorn reads the tenant key from server-side Laravel configuration and sends it to the hub in `X-Tenant-Key`. **Never pass `$tenantKey` to a Blade view or browser JavaScript.**
- Protect the viewer and its same-origin proxy endpoints with Phonmorn's user authentication. If the product requires a public viewer, replace `auth` with a short-lived, per-viewer scoped token; do not make the hub tenant key public.
- Use HTTPS for Phonmorn, the hub, and TURN/TLS. Store all real keys and secrets in each deployment's secret manager or untracked `.env`, not in source files or these docs.
- Values pasted into chat or tickets should be rotated. The examples below intentionally contain placeholders only.
- The generic Hub payload response shape is `payload.body`; mailbox requests use
  `key` and `payload_type`.

## Application-specific WebRTC contract

These are requirements for System Set and its authorized receiver, **not** a
global list of payload types or fields required by API Central Hub:

| Purpose | `payload_type` | Required payload fields |
|---|---|---|
| Android sender publishes its gathered offer | `webrtc-offer` | `type`, `device_user_slug`, `call_id`, `device_model`, `sdp`, `timestamp` |
| Phonmorn/receiver publishes the SDP answer | `webrtc-answer` | `type`, `device_user_slug`, matching `call_id`, `sdp`, `timestamp` |
| Sender reports device/stream health | `device-health` | `type`, `device_user_slug`, `call_id`, `system_status`, `failure_reason`, `device_model`, `timestamp` |

For these records, the mailbox `key` is the sender's device slug. The Hub's
generic storage identity remains tenant + payload type + key; it stores the
payload as an opaque JSON object. The receiver and sender validate the
application-specific fields. The offer and answer must use separate payload
types, and a receiver must only answer the active `call_id`.

System Set requires two application-level Hub capabilities:

1. Generic `POST/GET /api/v1/tenant/payload` support for the three payload types
   above, as specified by [api-central-hub-connection.md](./api-central-hub-connection.md).
2. A WebRTC-only `GET /api/v1/tenant/turn-credentials` extension that returns
   short-lived, credentialed TURN configuration. This route is described in
   the following optional Hub extension section; it is **not** required for
   tenants that do not use WebRTC.

System Set uses non-trickle ICE: it waits for local ICE gathering to complete
before publishing its offer. Phonmorn must likewise wait for its own ICE
gathering to complete before publishing the answer. Establishing signaling or
setting the remote SDP is not proof that ICE connected or that video is being
received.

## WebRTC-only API Central Hub extension: TURN credentials

This section is an optional application integration for deployments that serve
System Set WebRTC. Do not make TURN secrets, TURN routes, Coturn dependencies,
device slugs, or ICE rules required for unrelated Hub tenants. The Hub operator
may gate this route by tenant/product entitlement.

### Route registration

In the API Central Hub's `routes/api.php`, add this route under the tenant
middleware group that already resolves `X-Tenant-Key`:

```php
use App\Http\Controllers\Tenants\TenantTurnCredentialsController;

Route::get(
    '/v1/tenant/turn-credentials',
    [TenantTurnCredentialsController::class, 'show']
)->middleware(ResolveTenantConnection::class);
```

Laravel's API prefix makes its URL
`GET /api/v1/tenant/turn-credentials?device_user_slug=<slug>`.
If the Hub groups tenant routes in a different way, register it in that group
instead of applying `ResolveTenantConnection` twice.

### TURN controller in the Hub

Add `app/Http/Controllers/Tenants/TenantTurnCredentialsController.php` to the
Hub project:

```php
<?php

namespace App\Http\Controllers\Tenants;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use RuntimeException;

class TenantTurnCredentialsController extends Controller
{
    public function show(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'device_user_slug' => [
                'required',
                'string',
                'max:120',
                'regex:/^[A-Za-z0-9_-]+$/',
            ],
        ]);

        $secret = config('services.webrtc_turn.secret');
        $host = config('services.webrtc_turn.host');
        if (! is_string($secret) || $secret === '' ||
            ! is_string($host) || $host === '') {
            throw new RuntimeException('WebRTC TURN service configuration is incomplete.');
        }

        $ttl = max(60, min(
            (int) config('services.webrtc_turn.ttl_seconds', 3600),
            86400
        ));
        $expiresAt = time() + $ttl;
        $username = $expiresAt . '_' . $validated['device_user_slug'];
        $credential = base64_encode(hash_hmac('sha1', $username, $secret, true));
        $udpPort = (int) config('services.webrtc_turn.udp_port', 3478);
        $urls = [
            "turn:{$host}:{$udpPort}?transport=udp",
            "turn:{$host}:{$udpPort}?transport=tcp",
        ];

        $tlsPort = config('services.webrtc_turn.tls_port');
        if (is_int($tlsPort) && $tlsPort > 0) {
            $urls[] = "turns:{$host}:{$tlsPort}?transport=tcp";
        }

        return response()->json([
            'status' => 'success',
            'payload' => [
                'ttl' => $ttl,
                'expires_at' => now()->setTimestamp($expiresAt)->toIso8601String(),
                'iceServers' => [
                    ['urls' => ["stun:{$host}:{$udpPort}"]],
                    [
                        'urls' => $urls,
                        'username' => $username,
                        'credential' => $credential,
                    ],
                ],
            ],
        ]);
    }
}
```

Configure these values only in the Hub's deployment environment/secret manager,
and only for an environment that actually provides this WebRTC integration:

```php
'webrtc_turn' => [
    'secret' => env('WEBRTC_TURN_SHARED_SECRET'),
    'host' => env('WEBRTC_TURN_HOST'),
    'udp_port' => (int) env('WEBRTC_TURN_UDP_PORT', 3478),
    'tls_port' => env('WEBRTC_TURN_TLS_PORT') !== null
        ? (int) env('WEBRTC_TURN_TLS_PORT')
        : null,
    'ttl_seconds' => (int) env('WEBRTC_TURN_CREDENTIAL_TTL_SECONDS', 3600),
],
```

Example environment names (set actual values in deployment; do not commit
secrets):

```dotenv
WEBRTC_TURN_SHARED_SECRET=<same-rest-auth-secret-as-coturn>
WEBRTC_TURN_HOST=<public-turn-hostname>
WEBRTC_TURN_UDP_PORT=3478
WEBRTC_TURN_TLS_PORT=5349
WEBRTC_TURN_CREDENTIAL_TTL_SECONDS=3600
```

The secret, username format, and expiry must match Coturn's REST-auth
configuration. Provision a valid certificate and listener before returning a
`turns:` URL; otherwise leave `WEBRTC_TURN_TLS_PORT` unset. Keep `ttl_seconds`
within the Coturn deployment's policy. Do not use a sample fallback secret or
return TURN credentials if configuration is incomplete. TURN username/password
are temporary and necessarily visible to the WebRTC endpoint; the HMAC secret
and long-lived tenant key are not.

The endpoint should return HTTP errors for invalid tenant credentials or
misconfiguration. Deployments may use a tenant entitlement/feature check so only
authorized WebRTC tenants can request these credentials. Neither the generic
payload API nor this optional endpoint provisions the Android tenant key; that
key still needs a separate secure enrollment/MDM process.

## 1. Configure Phonmorn

### `config/services.php`

Under the existing `api_central_hub` configuration, ensure these keys exist:

```php
'api_central_hub' => [
    'base_url' => env('API_CENTRAL_HUB_BASE_URL', ''),
    'api_key' => env('API_CENTRAL_HUB_API_KEY', ''),
    'client_secret' => env('API_CENTRAL_HUB_CLIENT_SECRET', ''),
],
```

`client_secret` is needed only for the separate OAuth/protected-resource methods shown in the existing service. Payload and TURN endpoints in the provided hub routes accept `X-Tenant-Key` without a Sanctum token.

### `.env` (untracked)

Set the endpoint and the **same tenant key used by the enrolled System Set
sender** in the deployment environment. Both apps must resolve to the intended
tenant in the Hub. Keep the key server-side in Phonmorn and encrypted on the
Android device; do not put it in browser code:

```dotenv
API_CENTRAL_HUB_BASE_URL=https://your-api-central-hub.example
API_CENTRAL_HUB_API_KEY=<same-tenant-key-provisioned-to-systemset>
API_CENTRAL_HUB_CLIENT_SECRET=<only-if-using-hub-oauth>
```

Do not use the literal placeholders. Do not copy secret values into a committed `.env.example`.

## 2. Replace `app/Services/ApiCentralHub/ApiCentralHubConnection.php`

This retains the existing OAuth methods and adds the actual shared mailbox and TURN methods. Replace the full class:

```php
<?php

namespace App\Services\ApiCentralHub;

use Illuminate\Http\Client\PendingRequest;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use RuntimeException;
use UnexpectedValueException;

class ApiCentralHubConnection
{
    public function checkConnection(): array
    {
        $apiKey = $this->requiredConfig('api_key');
        $cacheKey = $this->tokenCacheKey($apiKey);
        $response = $this->requestWithTenantCredentials($apiKey);

        if ($response->unauthorized()) {
            Cache::forget($cacheKey);
            $response = $this->requestWithTenantCredentials($apiKey);
        }

        return $response->throw()->json();
    }

    public function fetchWebRtcOffer(string $deviceUserSlug): ?array
    {
        return $this->fetchPayload($deviceUserSlug, 'webrtc-offer');
    }

    public function fetchPayload(string $key, string $payloadType): ?array
    {
        $response = $this->http()
            ->withHeader('X-Tenant-Key', $this->requiredConfig('api_key'))
            ->get('/api/v1/tenant/payload', [
                'key' => $key,
                'payload_type' => $payloadType,
            ]);

        if ($response->notFound()) {
            return null;
        }

        $json = $response->throw()->json();
        $body = data_get($json, 'payload.body');
        if (! is_array($body)) {
            throw new UnexpectedValueException('The hub payload response did not contain payload.body.');
        }

        return $body;
    }

    public function storePayload(string $key, string $payloadType, array $payload): array
    {
        $response = $this->http()
            ->withHeader('X-Tenant-Key', $this->requiredConfig('api_key'))
            ->post('/api/v1/tenant/payload', [
                'key' => $key,
                'payload_type' => $payloadType,
                'payload' => $payload,
            ]);

        return $response->throw()->json();
    }

    public function fetchTurnCredentials(string $deviceUserSlug): array
    {
        $response = $this->http()
            ->withHeader('X-Tenant-Key', $this->requiredConfig('api_key'))
            ->get('/api/v1/tenant/turn-credentials', [
                'device_user_slug' => $deviceUserSlug,
            ]);

        $json = $response->throw()->json();
        $payload = data_get($json, 'payload');
        if (! is_array($payload) || ! is_array($payload['iceServers'] ?? null)) {
            throw new UnexpectedValueException('The hub TURN response is missing payload.iceServers.');
        }
        $hasCredentialedTurn = collect($payload['iceServers'])->contains(function ($server): bool {
            $urls = is_array($server['urls'] ?? null)
                ? $server['urls']
                : [$server['urls'] ?? ''];

            return filled($server['username'] ?? null)
                && filled($server['credential'] ?? null)
                && collect($urls)->contains(
                    fn ($url) => is_string($url)
                        && (str_starts_with(strtolower($url), 'turn:')
                            || str_starts_with(strtolower($url), 'turns:'))
                );
        });
        if (! $hasCredentialedTurn) {
            throw new UnexpectedValueException('The hub did not return credentialed TURN/TURNS configuration.');
        }

        return $payload;
    }

    private function requestWithTenantCredentials(string $apiKey): \Illuminate\Http\Client\Response
    {
        return $this->http()
            ->withToken($this->token($apiKey))
            ->withHeader('X-Tenant-Key', $apiKey)
            ->get('/api/v1/tenant/protected-resource');
    }

    private function token(string $apiKey): string
    {
        return Cache::remember(
            $this->tokenCacheKey($apiKey),
            now()->addMinutes(55),
            function () use ($apiKey): string {
                $response = $this->http()->post('/api/v1/oauth/token', [
                    'api_key' => $apiKey,
                    'client_secret' => $this->requiredConfig('client_secret'),
                ])->throw();
                $token = $response->json('access_token');
                if (! is_string($token) || $token === '') {
                    throw new UnexpectedValueException('The hub token response did not contain an access token.');
                }

                return $token;
            }
        );
    }

    private function http(): PendingRequest
    {
        return Http::baseUrl($this->baseUrl())
            ->acceptJson()
            ->connectTimeout(3)
            ->timeout(10);
    }

    private function requiredConfig(string $key): string
    {
        $value = config("services.api_central_hub.{$key}");
        if (! is_string($value) || $value === '') {
            throw new RuntimeException("The API Central Hub configuration value [{$key}] is required.");
        }

        return $value;
    }

    private function baseUrl(): string
    {
        return rtrim($this->requiredConfig('base_url'), '/');
    }

    private function tokenCacheKey(string $apiKey): string
    {
        return 'api-central-hub:token:' . hash('sha256', $apiKey);
    }
}
```

If the Phonmorn runtime is PHP 8.0 or 8.1, replace `str_starts_with` with `Illuminate\Support\Str::startsWith` or upgrade to PHP 8.2+. The shown `TurnCredentialsHandlerService` no longer generates credentials locally; the hub is the single authority for the Coturn HMAC secret.

## 3. Replace `app/Services/TurnCredentialsHandlerService.php`

```php
<?php

namespace App\Services;

use App\Services\ApiCentralHub\ApiCentralHubConnection;

class TurnCredentialsHandlerService
{
    public function __construct(
        private readonly ApiCentralHubConnection $hub
    ) {}

    public function getCredentials(string $deviceUserSlug): array
    {
        return $this->hub->fetchTurnCredentials($deviceUserSlug);
    }
}
```

Remove the old fallback secret and host from the provided implementation. TURN HMAC secrets belong only in the hub and Coturn configuration, not browser JavaScript or a Phonmorn source file.

## 4. Replace `app/Http/Controllers/WebRTCConnectionController.php`

```php
<?php

namespace App\Http\Controllers;

use App\Services\ApiCentralHub\ApiCentralHubConnection;
use App\Services\TurnCredentialsHandlerService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

class WebRTCConnectionController extends Controller
{
    public function __construct(
        private readonly ApiCentralHubConnection $hub,
        private readonly TurnCredentialsHandlerService $turnService
    ) {}

    public function viewer(Request $request): View
    {
        $validated = $request->validate([
            'device_user_slug' => ['nullable', 'string', 'max:120', 'regex:/^[A-Za-z0-9_-]+$/'],
        ]);
        $deviceUserSlug = $validated['device_user_slug'] ?? 'kampala-home';

        return view('streams.phone-viewer', [
            'deviceUserSlug' => $deviceUserSlug,
            'turnConfig' => $this->turnService->getCredentials($deviceUserSlug),
            'initialOffer' => $this->hub->fetchWebRtcOffer($deviceUserSlug),
        ]);
    }

    public function offer(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'device_user_slug' => ['required', 'string', 'max:120', 'regex:/^[A-Za-z0-9_-]+$/'],
        ]);
        $offer = $this->hub->fetchWebRtcOffer($validated['device_user_slug']);

        if ($offer === null) {
            return response()->json(['status' => 'not_found'], 404);
        }

        return response()->json(['status' => 'success', 'payload' => $offer]);
    }

    public function answer(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'device_user_slug' => ['required', 'string', 'max:120', 'regex:/^[A-Za-z0-9_-]+$/'],
            'call_id' => ['required', 'string', 'max:160'],
            'sdp' => ['required', 'string', 'max:200000'],
        ]);

        $result = $this->hub->storePayload(
            $validated['device_user_slug'],
            'webrtc-answer',
            [
                'type' => 'webrtc-answer',
                'device_user_slug' => $validated['device_user_slug'],
                'call_id' => $validated['call_id'],
                'sdp' => $validated['sdp'],
                'timestamp' => now()->getTimestampMs(),
            ]
        );

        return response()->json(['status' => 'success', 'hub' => $result], 201);
    }
}
```

The tenant key is deliberately not returned by `viewer()`. The browser accesses same-origin Phonmorn proxy routes, and only the Laravel server calls the hub with the tenant key.

## 5. Replace the relevant `routes/web.php` routes

Keep the home route if needed. Replace the viewer route with authenticated viewer and proxy routes:

```php
use App\Http\Controllers\WebRTCConnectionController;
use Illuminate\Support\Facades\Route;

Route::get('/', fn () => view('home'));

Route::middleware(['auth'])->group(function () {
    Route::get('/stream/viewer', [WebRTCConnectionController::class, 'viewer'])
        ->name('stream.viewer');
    Route::get('/stream/api/offer', [WebRTCConnectionController::class, 'offer'])
        ->name('stream.api.offer');
    Route::post('/stream/api/answer', [WebRTCConnectionController::class, 'answer'])
        ->name('stream.api.answer');
});
```

If the existing viewer is intentionally public, do not simply remove `auth`. Implement an expiring random viewer token scoped to one device slug and use it to authorize only these two proxy routes.

## 6. Replace `resources/views/streams/phone-viewer.blade.php`

This is a minimal complete viewer that polls offers, creates an ICE-gathered answer, posts it to Phonmorn, and displays actual peer-connection/track state. Replace the current file:

```blade
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="csrf-token" content="{{ csrf_token() }}">
    <title>Phonmorn Live Stream Viewer</title>
    <style>
        body { margin: 0; padding: 1.5rem; background: #020617; color: #e2e8f0; font: 16px system-ui, sans-serif; }
        main { max-width: 1100px; margin: auto; }
        video { width: 100%; max-height: 72vh; background: #000; }
        #status { padding: .6rem 0; color: #fbbf24; }
        pre { white-space: pre-wrap; overflow-wrap: anywhere; }
    </style>
</head>
<body>
<main>
    <h1>Phonmorn Stream Viewer</h1>
    <p>Device: <strong id="slug"></strong></p>
    <p id="status" role="status">Waiting for offer…</p>
    <video id="remote-video" autoplay playsinline controls></video>
    <h2>Negotiation diagnostics</h2>
    <pre id="diagnostics"></pre>
</main>
<script>
    const deviceUserSlug = @json($deviceUserSlug);
    const iceServers = @json($turnConfig['iceServers']);
    let peer = null;
    let activeCallId = null;
    let polling = false;

    const statusNode = document.getElementById('status');
    const diagnosticsNode = document.getElementById('diagnostics');
    const videoNode = document.getElementById('remote-video');
    document.getElementById('slug').textContent = deviceUserSlug;

    function setStatus(message) {
        statusNode.textContent = message;
        diagnosticsNode.textContent =
            `signaling=${peer?.signalingState ?? 'none'}\n` +
            `ice=${peer?.iceConnectionState ?? 'none'}\n` +
            `gathering=${peer?.iceGatheringState ?? 'none'}\n` +
            `call_id=${activeCallId ?? 'none'}`;
    }

    function waitForIceGatheringComplete(connection) {
        if (connection.iceGatheringState === 'complete') return Promise.resolve();
        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                connection.removeEventListener('icegatheringstatechange', onChange);
                reject(new Error('Timed out waiting for receiver ICE gathering.'));
            }, 30000);
            function onChange() {
                if (connection.iceGatheringState === 'complete') {
                    clearTimeout(timeout);
                    connection.removeEventListener('icegatheringstatechange', onChange);
                    resolve();
                }
            }
            connection.addEventListener('icegatheringstatechange', onChange);
        });
    }

    async function publishAnswer(offer) {
        if (peer) peer.close();
        activeCallId = offer.call_id;
        peer = new RTCPeerConnection({ iceServers });
        peer.ontrack = event => {
            videoNode.srcObject = event.streams[0] ?? new MediaStream([event.track]);
            setStatus('Remote media track received.');
        };
        peer.oniceconnectionstatechange = () => {
            const state = peer.iceConnectionState;
            setStatus(`ICE ${state}`);
            if (state === 'failed' || state === 'disconnected') {
                console.error('WebRTC ICE connection is not healthy:', state);
            }
        };

        await peer.setRemoteDescription({
            type: 'offer',
            sdp: offer.sdp,
        });
        const answer = await peer.createAnswer();
        await peer.setLocalDescription(answer);
        await waitForIceGatheringComplete(peer);

        const csrf = document.querySelector('meta[name="csrf-token"]').content;
        const response = await fetch('/stream/api/answer', {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': csrf,
            },
            body: JSON.stringify({
                device_user_slug: deviceUserSlug,
                call_id: offer.call_id,
                sdp: peer.localDescription.sdp,
            }),
        });
        if (!response.ok) {
            throw new Error(`Answer upload failed with HTTP ${response.status}.`);
        }
        setStatus('Answer published; waiting for ICE connection.');
    }

    async function pollOffer() {
        if (polling) return;
        polling = true;
        try {
            const query = new URLSearchParams({ device_user_slug: deviceUserSlug });
            const response = await fetch(`/stream/api/offer?${query}`, {
                credentials: 'same-origin',
                headers: { 'Accept': 'application/json' },
            });
            if (response.status === 404) {
                if (!peer) setStatus('Waiting for offer…');
                return;
            }
            if (!response.ok) throw new Error(`Offer lookup failed with HTTP ${response.status}.`);
            const data = await response.json();
            const offer = data.payload;
            if (offer?.type !== 'webrtc-offer' || !offer.call_id || !offer.sdp) {
                throw new Error('Hub returned an incomplete WebRTC offer.');
            }
            if (offer.call_id !== activeCallId) {
                setStatus('Offer received; negotiating…');
                await publishAnswer(offer);
            }
        } catch (error) {
            console.error(error);
            setStatus(`Signaling error: ${error.message}`);
        } finally {
            polling = false;
        }
    }

    pollOffer();
    setInterval(pollOffer, 2000);
</script>
</body>
</html>
```

The hub credentials are short-lived and scoped to the TURN server. They are necessarily visible to the browser's WebRTC stack; never place the TURN HMAC secret or tenant key in this view. The example runs the signaling fetches through Laravel, so the browser does not need `X-Tenant-Key`.

## 7. Laravel tests for the hub client

Create `tests/Feature/ApiCentralHubConnectionTest.php`. These tests fake HTTP and verify server-to-server requests; they do not prove that the real hub or Coturn is deployed correctly.

```php
<?php

namespace Tests\Feature;

use App\Services\ApiCentralHub\ApiCentralHubConnection;
use Illuminate\Http\Client\Request as ClientRequest;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class ApiCentralHubConnectionTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();
        config([
            'services.api_central_hub.base_url' => 'https://hub.test',
            'services.api_central_hub.api_key' => 'test-tenant-key',
        ]);
    }

    public function test_it_fetches_an_offer_using_tenant_header_and_mailbox_query(): void
    {
        Http::fake([
            'hub.test/api/v1/tenant/payload*' => Http::response([
                'status' => 'success',
                'payload' => [
                    'key' => 'device-1',
                    'payload_type' => 'webrtc-offer',
                    'body' => ['type' => 'webrtc-offer', 'call_id' => 'call-1', 'sdp' => 'offer-sdp'],
                ],
            ]),
        ]);

        $offer = app(ApiCentralHubConnection::class)->fetchWebRtcOffer('device-1');

        $this->assertSame('call-1', $offer['call_id']);
        Http::assertSent(fn (ClientRequest $request) =>
            $request->method() === 'GET'
            && str_contains($request->url(), '/api/v1/tenant/payload?')
            && str_contains($request->url(), 'key=device-1')
            && str_contains($request->url(), 'payload_type=webrtc-offer')
            && $request->hasHeader('X-Tenant-Key', 'test-tenant-key')
        );
    }

    public function test_it_posts_an_answer_with_the_same_mailbox_key(): void
    {
        Http::fake([
            'hub.test/api/v1/tenant/payload' => Http::response(['status' => 'success'], 201),
        ]);

        app(ApiCentralHubConnection::class)->storePayload('device-1', 'webrtc-answer', [
            'type' => 'webrtc-answer',
            'device_user_slug' => 'device-1',
            'call_id' => 'call-1',
            'sdp' => 'answer-sdp',
        ]);

        Http::assertSent(fn (ClientRequest $request) =>
            $request->method() === 'POST'
            && $request->hasHeader('X-Tenant-Key', 'test-tenant-key')
            && $request['key'] === 'device-1'
            && $request['payload_type'] === 'webrtc-answer'
            && $request['payload']['call_id'] === 'call-1'
        );
    }

    public function test_it_rejects_a_turn_response_without_credentialed_turn(): void
    {
        Http::fake([
            'hub.test/api/v1/tenant/turn-credentials*' => Http::response([
                'status' => 'success',
                'payload' => [
                    'ttl' => 60,
                    'iceServers' => [['urls' => ['stun:stun.example.test:3478']]],
                ],
            ]),
        ]);

        $this->expectException(\UnexpectedValueException::class);
        app(ApiCentralHubConnection::class)->fetchTurnCredentials('device-1');
    }

    public function test_it_accepts_short_lived_credentialed_turn_configuration(): void
    {
        Http::fake([
            'hub.test/api/v1/tenant/turn-credentials*' => Http::response([
                'status' => 'success',
                'payload' => [
                    'ttl' => 300,
                    'iceServers' => [[
                        'urls' => ['turn:turn.example.test:3478?transport=udp'],
                        'username' => 'expiry_device-1',
                        'credential' => 'temporary-credential',
                    ]],
                ],
            ]),
        ]);

        $turn = app(ApiCentralHubConnection::class)->fetchTurnCredentials('device-1');

        $this->assertSame(300, $turn['ttl']);
        $this->assertSame('temporary-credential', $turn['iceServers'][0]['credential']);
    }
}
```

Run in Phonmorn:

```bash
php artisan test --filter=ApiCentralHubConnectionTest
```

## 8. cURL smoke tests

Set these in a shell without writing the tenant key to shell history:

```bash
export PHONMORN_URL="https://your-phonmorn.example"
read -rsp "Phonmorn user session is required; sign in in your browser first. Hub tenant key (server-only): " TENANT_KEY; echo
```

The browser must not call the hub directly. For local server-to-server hub testing, run the hub cURL commands from [api-central-hub-connection.md](./api-central-hub-connection.md).

For the same-origin Phonmorn viewer endpoints, use an authenticated browser session/cookie and CSRF token. Verify that:

- `GET /stream/api/offer?device_user_slug=...` returns `404` before an offer exists and `200` with `payload.type=webrtc-offer` after SystemSet posts one.
- `POST /stream/api/answer` returns `201` for a valid answer and `422` for a missing `call_id` or SDP.
- Unauthenticated requests to the viewer/proxy routes redirect to login or return `401/403`.
- The HTML response does not contain `X-Tenant-Key`, the configured API key, or the TURN HMAC secret.

## 9. TURN and ICE tests

1. Call the WebRTC-only Hub TURN endpoint described above and validate a
   non-empty `ttl`, a future `expires_at`, and at least one credentialed
   TURN/TURNS URL. This route is an optional System Set integration, not part of
   the generic Hub API.
2. Open `/stream/viewer?device_user_slug=<the-sender-slug>` while authenticated.
3. Start the SystemSet sender. The Phonmorn diagnostics must report `ice=connected` or `ice=completed`; a successful HTTP answer upload or `signalingState=stable` alone does not count.
4. Confirm the `<video>` element receives a track and has non-zero `videoWidth`/`videoHeight`.
5. Test a network where direct UDP is unavailable; verify Coturn relay via TCP/TLS and media still reaches the viewer.
6. Wait beyond credential expiry or temporarily deny Coturn access; the viewer must report ICE failure, not "Stream Connected".
7. Restart the sender so a new `call_id` is generated; ensure the old answer is not applied to the new offer.
8. Use a second tenant with the same slug and verify the hub does not cross-deliver records.

These are real network acceptance tests and need a deployed Hub, Phonmorn, Coturn, and Android sender. Laravel `Http::fake()` tests validate request contracts only; they do not simulate SDP, ICE connectivity, NAT traversal, or media.

## Future StreamDisplayer receiver

Do not start its implementation until this web receiver passes the checks above. It should use the same hub routes and mailbox schema: poll `webrtc-offer` with `key=<device slug>`, publish `webrtc-answer` with the same key and `call_id`, and fetch TURN credentials with its tenant key in the request header. Its tenant key must be provisioned securely and stored encrypted; it must never be embedded as a constant in the APK.
