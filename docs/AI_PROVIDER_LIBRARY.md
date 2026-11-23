# Central AI Provider Library

Mobile Harness Code ships one shared AI provider library used by every AI tool — **DeepSeek
Harness**, **Claude Code**, the **Sidekick assistant**, and any future module. This document
explains how to extend it without changing the rest of the app.

## Architecture at a glance

```
Bundled catalog (model/AiProviderLibrary.kt)     ← ships in the APK, works offline
        │  AiCatalogMerge.merge(...)
        ▼
Remote catalog (data/AiCatalogStore.kt)          ← hostable JSON, fetched + SHA-256 verified
        │  AiProviderCatalog.applyRemote(...)
        ▼
AiProviderCatalog.current()  →  used by the UI + auth-type resolution
        │
        ▼
ProviderProfile (providerId / keyId)  →  ApiKeyVault (AES-256-GCM, per-provider key pool)
        │
        ├─ DeepSeek Harness  (DshRuntimeBridge, custom route)
        ├─ Claude Code       (RuntimeLaunchConfigBuilder + LocalFormatGateway)
        └─ Sidekick          (SidekickEngine + ProviderApiClient)
```

Nothing here holds a secret. API keys live only in `ApiKeyVault`, keyed by `providerId`, so one
provider can never use another provider's key.

## Adding a provider (bundled, offline)

Append an `AiProvider` entry to `AiProviderCatalog.all` in
`model/AiProviderLibrary.kt`. No other change is required — it immediately appears in the shared
picker for every AI tool.

```kotlin
AiProvider(
    id = "my-provider",
    name = "My Provider",
    category = AiProviderCategory.OPENAI_COMPATIBLE,
    baseUrl = "https://api.my-provider.com/v1",
    wireProtocol = AiWireProtocol.OPENAI_CHAT,
    defaultModel = "my-model",
    authType = AiAuthType.API_KEY,
    apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
    capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING),
    docsUrl = "https://docs.my-provider.com",
)
```

### Wire protocols

- `OPENAI_CHAT` — OpenAI chat-completions (`/chat/completions`); covers most compatible providers.
- `OPENAI_RESPONSES` — OpenAI Responses API (`/responses`).
- `ANTHROPIC_MESSAGES` — Anthropic Messages (`/v1/messages`).
- `NATIVE` — a provider with its own API (needs an adapter; marked `needsAdapter` and not usable
  directly).

### Auth types

- `API_KEY` / `BEARER_TOKEN` → `Authorization: Bearer <key>`.
- `API_KEY_HEADER` (Azure OpenAI) → `api-key: <key>`.
- `NONE` (local servers) → no auth.
- `OAUTH` / `GOOGLE_OAUTH` / `AWS_SIGV4` → requires an adapter; no bare header is sent.

## Adding a provider via the remote catalog (no APK release)

The remote catalog is a JSON document hosted at a single HTTPS URL (`data/AiCatalogStore.kt`), with
a companion `.sha256` file pinning its integrity. It is a **metadata-only overlay**: it can add new
providers or update an existing provider's URL/model, but it can never remove a bundled provider or
touch the user's saved keys or configurations.

### JSON format

```json
{
  "schemaVersion": 1,
  "version": 2,
  "updatedAt": "2026-01-01T00:00:00Z",
  "providers": [
    {
      "id": "my-provider",
      "name": "My Provider",
      "category": "OPENAI_COMPATIBLE",
      "baseUrl": "https://api.my-provider.com/v1",
      "wireProtocol": "OPENAI_CHAT",
      "defaultModel": "my-model",
      "authType": "API_KEY",
      "apiFormats": ["OPENAI_CHAT"],
      "capabilities": ["CHAT_COMPLETIONS", "STREAMING"],
      "docsUrl": "https://docs.my-provider.com",
      "notes": "",
      "official": true
    }
  ]
}
```

Enums (`category`, `wireProtocol`, `authType`, `apiFormats`, `capabilities`) are the `name()` values
of the corresponding Kotlin enums in `model/AiProviderLibrary.kt`.

### Publishing a new catalog version

1. Edit `catalog.json` (append/override providers).
2. Bump `version` and `updatedAt`.
3. Regenerate the checksum: `sha256sum catalog.json > catalog.json.sha256`.
4. Push both files to the catalog repo (default `main` branch).

The app verifies the SHA-256 before applying the document and keeps the last valid copy for offline
use, so a malformed or tampered document is simply ignored.

## Model discovery

`ProviderApiClient.discoverModels` lists models using the protocol-appropriate endpoint:

- OpenAI/OpenAI-compatible + local servers → `GET /models`.
- Anthropic family → `GET /v1/models` (with `/models` and `/anthropic`-suffix fallbacks).
- OpenRouter → `GET /v1/models` (anonymous public catalog).

Results are cached per provider by `ModelDiscoveryStore` (offline reuse + last-check timestamp).
Providers without a model-list endpoint report that clearly and fall back to manual model entry.

## API key rotation

`data/ApiKeyRotator.kt` rotates keys **within one provider's pool** (never across providers):

- 401/403 (invalid key) and 402 (no credit) → the key is **disabled** and the next enabled key is
  tried; if none remain, the chain stops (no infinite loop).
- 429 (rate limit) and 5xx (server error) → another key is tried but the failed key stays enabled.
- Timeout/network/bad-request → the key is not blamed; no rotation.

Keys can be individually enabled/disabled in the UI (`ApiKeyVault.setEnabled`); a disabled key is
never used, exported-to-backup but not offered for requests.

## Compatibility gate

`model/AiCompatibility.kt` is the single source of truth for which capabilities a provider/model
supports (chat, streaming, tool calling, vision, embeddings). A curated per-model override wins,
then the provider's advertised capabilities, then fail-closed to unsupported. All three AI tools
must consult it before sending a request that uses tools, images or streaming.

## Adding a new API adapter

Providers speaking the OpenAI or Anthropic wire formats need no adapter. For a provider with its own
API (e.g. Bedrock SigV4, Vertex OAuth, Replicate), add an adapter that produces a
`ProviderProtocol`-shaped request and register it where the runtime resolves providers. Keep the
`needsAdapter = true` flag on the catalog entry until a real adapter exists, so the UI never claims
the provider is directly usable.
