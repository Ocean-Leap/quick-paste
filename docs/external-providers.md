# External sink providers

Outbound-only channels (`type=external`) forward text clips to a remote system.
See SPEC §5.14 / FR-61–65 and decision **D-15**.

This document is the **public contract**. The public repo does **not** ship a
branded third-party integration. Personal adapters stay on your machine (or a
private fork) and must not be pushed to the public tree.

## What the platform provides

Shared plumbing (when implemented):

| Piece | Role |
|---|---|
| `channels.type = external` | Owner-only channel; no invites |
| `channel_sinks` | `provider`, `base_url`, encrypted `token`, optional `default_tags` |
| `clip_deliveries` | Async ledger: `pending` → `sent` / `deduped` / `failed` |
| SSRF guard | `https` only; reject private/loopback/link-local targets; no redirects |
| Worker | Poll / claim / backoff via `QP_SINK_DELIVERY_INTERVAL_SEC`, `QP_SINK_MAX_ATTEMPTS` |
| Clip API | Text only (files → 422); create returns `delivery.status=pending` immediately |
| Idempotency | Clip ULID is the remote idempotency key |

Google remains **identity only** — do not add Google API refresh tokens for a
sink (NFR-6, D-3). Prefer a user-supplied bearer token to the remote system.

## Adding a provider (compile-time)

There is **no plugin loader** (NFR-11). A provider is a small set of classes
registered in the server build:

1. **Id** — stable string stored in `channel_sinks.provider` (e.g. `webhook`).
2. **Validate** — on create/PATCH: normalize `baseUrl`, check token shape,
   apply `HostSafety`-style SSRF rules.
3. **Map** — `clip text (+ optional sinkKind/tags) →` outbound body. Keep
   mapping pure and unit-tested.
4. **Deliver** — HTTP client (or equivalent): never log tokens/payloads; treat
   2xx as success; 429/`Retry-After` and 5xx as retryable; 4xx auth/validation
   as terminal `failed`.
5. **Wire** — register in the sink delivery switch/map and (if needed) a thin
   PWA form for create/edit. Do not add a runtime extension SPI.

Suggested layout:

```text
server/src/main/java/com/quickpaste/sink/
  SinkProvider.java          # shared interface
  SinkDeliveryService.java   # enqueue, claim, retry
  HostSafety.java            # SSRF
  webhook/                   # example public provider (when shipped)
    WebhookIngestClient.java
    WebhookMapper.java
```

Personal / private providers belong under **`local/`** (gitignored) or an
unpushed branch — copy or overlay into the build only on your deploy host.

## Suggested first public provider: `webhook`

A generic HTTPS webhook is the best public default when you are ready to
implement one:

- `POST {baseUrl}` with JSON such as
  `{ "text", "idempotencyKey", "kind?", "tags?" }`
- `Authorization: Bearer {token}`
- No product-specific kinds beyond optional free-form `kind`

That gives self-hosters something useful without coupling quick-paste to a
private knowledge base.

## Local personal providers

| Do | Don't |
|---|---|
| Keep adapters in `local/` or a private fork | Commit private API paths, token formats, or product names you want to keep personal |
| Deploy from an image that includes your overlay | Rely on Google Drive / OAuth refresh tokens for async delivery |
| Reuse the shared ledger + SSRF + encryption | Build a dynamic plugin system |

Create `local/README.md` on your machine (ignored by git) for notes about your
private provider and how you bake it into the Docker image.
