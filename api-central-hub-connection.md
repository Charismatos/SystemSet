# API Central Hub: project-agnostic tenant payload API

## Purpose and scope

API Central Hub is a shared, project-agnostic service used by many independent
tenant applications. Its generic responsibility is to authenticate the tenant,
accept or retrieve tenant-scoped JSON payloads, and apply platform-wide
validation, isolation, expiry, rate limits, and operational policies. It must
not assume that payloads represent WebRTC, devices, ICE, TURN, payments, orders,
ERP records, or any other one product's domain.

This guide covers only the generic payload API. Application-specific workflows,
schemas, and services belong in the consuming application's integration guide.
For example, the System Set/Phonmorn WebRTC, device-health, TURN, and ICE contract
is documented in [web-app-connection.md](./web-app-connection.md); it is not a
global requirement for every Hub tenant.

The Laravel Hub project is not checked out alongside System Set in this
workspace. The examples below are implementation guidance and have not been
installed or tested against the live Hub.

## Generic payload contract

### Store a payload

`POST /api/v1/tenant/payload` with a tenant credential in the `X-Tenant-Key`
header and a JSON envelope:

```json
{
  "key": "opaque-tenant-defined-routing-key",
  "payload_type": "sample.event-created",
  "payload": {
    "event_id": "sample-1001",
    "attributes": {
      "source": "example-client"
    }
  }
}
```

The Hub treats `payload_type` as an opaque, tenant-selected namespace, not as a
closed list of product-specific types. `payload` is an arbitrary JSON object;
the Hub does not require fields such as `type`, `call_id`, `sdp`, device slug, or
payment identifiers. An application may define its own schema and validate it
before publishing or after retrieval.

The storage identity is the authenticated tenant + `payload_type` + `key`. A
new value replaces the current value for that exact identity. Other tenants,
types, and keys remain independent. Records expire according to a configurable
Hub-wide default TTL or an operator-approved per-tenant policy.

### Retrieve a payload

`GET /api/v1/tenant/payload?key=<key>&payload_type=<type>`

Example success response:

```json
{
  "status": "success",
  "payload": {
    "key": "opaque-tenant-defined-routing-key",
    "payload_type": "sample.event-created",
    "body": {
      "event_id": "sample-1001",
      "attributes": {
        "source": "example-client"
      }
    },
    "received_at": "2026-11-04T10:00:00+00:00"
  }
}
```

A missing or expired record returns HTTP `404` and a neutral response such as
`{"status":"not_found"}`. Do not expose records belonging to another tenant,
even when both tenants use the same key and payload type.

## 1. Tenant authentication and routes

Use the Hub's existing tenant-resolution middleware for both generic payload
routes. The middleware must resolve the tenant from `X-Tenant-Key`, activate
that tenant's context/connection, and restore the previous context after the
request. Keep unrelated OAuth, tenant administration, and protected-resource
routes unchanged.

In `routes/api.php`, register the shared API under its existing `v1/tenant`
prefix. Laravel's API route prefix supplies `/api`:

```php
use App\Http\Controllers\Tenants\TenantPayloadController;
use App\Http\Middleware\Tenants\ResolveTenantConnection;
use Illuminate\Support\Facades\Route;

Route::prefix('v1/tenant')
    ->middleware([ResolveTenantConnection::class])
    ->group(function () {
        Route::post('/payload', [TenantPayloadController::class, 'store']);
        Route::get('/payload', [TenantPayloadController::class, 'retrieve']);
    });
```

The resulting endpoints are `POST /api/v1/tenant/payload` and
`GET /api/v1/tenant/payload`. They require a valid tenant key. Do not add a
product-specific route or domain-specific middleware to this common group unless
that feature is intentionally offered as a reusable Hub service with its own
documented opt-in contract.

## 2. Generic payload controller

The following controller demonstrates a generic, replace-in-place mailbox using
Laravel's shared cache. It validates only the envelope and safe bounds; it does
not enforce one project's payload schema or a hard-coded list of payload types.
Adapt response/error conventions to the Hub's existing API standards.

```php
<?php

namespace App\Http\Controllers\Tenants;

use App\Http\Controllers\Controller;
use App\Services\Tenants\TenantContext;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;

class TenantPayloadController extends Controller
{
    public function store(Request $request, TenantContext $tenantContext): JsonResponse
    {
        $validated = $request->validate([
            'key' => ['required', 'string', 'max:160', 'regex:/^[A-Za-z0-9._:-]+$/'],
            'payload_type' => ['required', 'string', 'max:120', 'regex:/^[A-Za-z0-9._:-]+$/'],
            'payload' => ['required', 'array'],
        ]);

        $tenant = $tenantContext->tenant();
        $record = [
            'key' => $validated['key'],
            'payload_type' => $validated['payload_type'],
            'body' => $validated['payload'],
            'received_at' => now()->toIso8601String(),
        ];

        $ttlSeconds = max(1, (int) config(
            'services.api_central_hub.payload_ttl_seconds',
            600
        ));

        Cache::put(
            $this->cacheKey(
                (string) $tenant->getKey(),
                $validated['payload_type'],
                $validated['key']
            ),
            $record,
            now()->addSeconds($ttlSeconds)
        );

        return response()->json([
            'status' => 'success',
            'message' => 'Payload stored.',
            'payload_type' => $record['payload_type'],
            'key' => $record['key'],
            'received_at' => $record['received_at'],
        ], 201);
    }

    public function retrieve(Request $request, TenantContext $tenantContext): JsonResponse
    {
        $validated = $request->validate([
            'key' => ['required', 'string', 'max:160', 'regex:/^[A-Za-z0-9._:-]+$/'],
            'payload_type' => ['required', 'string', 'max:120', 'regex:/^[A-Za-z0-9._:-]+$/'],
        ]);

        $tenant = $tenantContext->tenant();
        $record = Cache::get(
            $this->cacheKey(
                (string) $tenant->getKey(),
                $validated['payload_type'],
                $validated['key']
            )
        );

        if (! is_array($record)) {
            return response()->json([
                'status' => 'not_found',
                'message' => 'No payload found for this key and type.',
            ], 404);
        }

        return response()->json([
            'status' => 'success',
            'payload' => $record,
        ]);
    }

    private function cacheKey(string $tenantId, string $payloadType, string $key): string
    {
        return 'tenant:' . $tenantId
            . ':payload:' . hash('sha256', $payloadType)
            . ':' . hash('sha256', $key);
    }
}
```

The regular expression is an example of a shared safe-character policy, not a
domain schema. If existing tenants require other key/type characters, define and
document a compatible platform-wide rule rather than introducing a WebRTC-only
restriction. Apply request-body size limits at the web server and Laravel
validation layer to protect a multi-tenant service from oversized payloads.

Use a shared cache backend such as Redis when the Hub runs multiple processes or
containers. Confirm the cache's expiry semantics and tenant context are correct
for the deployed topology. If durable audit history is required, implement a
dedicated database schema and explicit failure handling; do not silently swallow
audit-write errors or treat ephemeral cache as a durable record.

## 3. Generic TTL configuration

Under `api_central_hub` in `config/services.php`, configure the default
short-lived payload TTL:

```php
'payload_ttl_seconds' => (int) env('API_CENTRAL_HUB_PAYLOAD_TTL_SECONDS', 600),
```

Set an operator-approved value in the Hub's untracked `.env` or secret manager:

```dotenv
API_CENTRAL_HUB_PAYLOAD_TTL_SECONDS=600
```

This setting applies to generic payload records. If the platform later offers
multiple TTL classes or per-tenant overrides, define them as reusable Hub policy
and document their effect independently of any one consuming application's
protocol. Clear/rebuild Laravel's config cache using the Hub's standard
deployment procedure after changing configuration.

## 4. Generic API tests

Add Hub tests for the platform behavior rather than one product's business
payload. At minimum verify:

- valid tenant authentication is required;
- arbitrary valid `payload_type` values and different JSON object shapes
  can be stored and read without product-specific fields;
- the same tenant + type + key retrieves the current record;
- a different type or key does not retrieve that record;
- a different tenant cannot retrieve it, even with the same type and key;
- invalid envelope fields and oversized requests are rejected;
- expired records return `404`;
- transient or unavailable cache behavior follows the Hub's explicit error
  policy.

The tests should set a low TTL or use a controllable clock/cache fake to check
expiration deterministically. Keep project-specific schema and end-to-end
behavior tests in the relevant consumer project.

## 5. cURL smoke tests

Run these against a non-production environment with a test tenant. Do not put
tenant keys in URLs, source files, command history, or logs.

```bash
export HUB_URL="https://your-api-central-hub.example"
read -rsp "Tenant key: " TENANT_KEY; echo
export TENANT_KEY
PAYLOAD_KEY="hub-smoke-$(date +%s)"
PAYLOAD_TYPE="sample.event-created"
```

Publish a generic JSON object:

```bash
curl -fsS -X POST "$HUB_URL/api/v1/tenant/payload" \
  -H "X-Tenant-Key: $TENANT_KEY" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  --data-binary "{\"key\":\"$PAYLOAD_KEY\",\"payload_type\":\"$PAYLOAD_TYPE\",\"payload\":{\"event_id\":\"smoke-1\",\"attributes\":{\"source\":\"curl\"}}}"
```

Retrieve and assert the stored values:

```bash
curl -fsS -G "$HUB_URL/api/v1/tenant/payload" \
  -H "X-Tenant-Key: $TENANT_KEY" \
  --data-urlencode "key=$PAYLOAD_KEY" \
  --data-urlencode "payload_type=$PAYLOAD_TYPE" |
  jq -e --arg key "$PAYLOAD_KEY" --arg type "$PAYLOAD_TYPE" \
    '.status == "success" and .payload.key == $key and .payload.payload_type == $type and .payload.body.attributes.source == "curl"'
```

Also verify that an unknown/invalid tenant credential is rejected and that a
missing key/type returns `404`. In a controlled test, use another valid tenant
with the same key and type to verify tenant isolation.

## 6. Application-specific integrations

The generic API above is only transport and tenant isolation. It intentionally
does not provide or require WebRTC signaling, TURN credentials, ICE policy,
device enrollment, payment state, or any other product contract. A consuming
application that needs such behavior must define its schema, routes, server
configuration, credential handling, lifecycle, and acceptance tests in that
application's integration guide.

For System Set and Phonmorn, use
[web-app-connection.md](./web-app-connection.md) for the application-specific
offer/answer/device-health payloads, TURN credential provisioning, receiver
proxy, and ICE/media acceptance procedure. Those features must be deployed and
verified for that application; they are not assumed to be active for every Hub
tenant.
