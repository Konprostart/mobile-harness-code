# Mobile Harness Code — Development Plan

Independent fork of **Mobile Harness**: a complete autonomous AI development environment for
Android (Jetpack Compose + a self-contained Ubuntu 20.04 runtime via PRoot). Work is delivered in
small, fail-closed phases; each phase keeps existing tools intact (providers + AI routing/fallback,
local model, MCP client, RAG, workspace tools, safe Skill system, SSH client/tunnel/TOFU/PTY
terminal + keyboard, Phone Control, in-app update, Telegram bot, multimodal Sidekick, Eco Mode,
Share & Invite) and only changes what its phase targets.

## Phases (shipped)

- **Faza 2 — SSH terminal**: PTY + VT100/ANSI emulation, extra-keys keyboard, hardware keys,
  clipboard, pinch-to-zoom, TOFU-gated `openShell`.
- **Faza 3 — MCP**: JSON-RPC client, server registry, tool catalog, policy/guard, logging.
- **Faza 4 — RAG**: document base for the Sidekick assistant.
- **Faza C — workspace tools & skills**: safe built-in tools and the audited Skill system.

## Phase D — Stable background work, SSH/agents and state preservation

Goal: the app stays stable and loses no state when the user switches apps, splits screen, uses
PiP/recents, locks the screen, or is hit by Doze — with a fail-closed posture and no leaked
resources (sockets, processes, streams, coroutine scopes, `SpeechRecognizer`/audio).

### Landed in v1.3.12 / v1.3.13

- `SessionResumePolicy`: deterministic foreground/background lifecycle (idempotent edges,
  clock-adjustment-safe duration).
- Foreground-resume refresh of SSH tunnel status + diagnostics (never auto-reconnects).
- `SshTunnel.isRunning()` requires `thread.isAlive && !socket.isClosed && ssh.isConnected &&
  ssh.isAuthenticated`.
- Clean teardown on `onCleared()`: `SshController.closeAll()` + PTY session close.
- `SpeechRecognizerState` releases the microphone on `Lifecycle.Event.ON_STOP`.

### Landed in v1.3.14 (this phase)

- **Interactive PTY liveness**: `SshPtyTransport.isAlive` (real transport also requires a live,
  authenticated SSH link) and `SshShellSession.isAlive()`. `MainViewModel.refreshSshShellStatus()`
  runs on foreground resume and fail-closes a silently-dead shell instead of leaving the UI stuck
  on "connected" (mirrors the tunnel rule; never reconnects).
- **Session/state preservation across process death**: `AppPreferences.lastProjectId` /
  `lastChatId` are written on project open / chat switch and cleared on explicit close;
  `MainViewModel.restoreLastSession()` reopens the same workspace on a cold start after Doze or a
  low-memory reclaim (fail-closed and silent when the project/chat no longer exists or something
  is already running).
- **Rotation-state polish**: SSH terminal Ctrl/Alt modifier toggles and the Sidekick + quick
  terminal input drafts survive configuration changes (`rememberSaveable`).

## Testing & CI

- Plain-JVM unit tests cover lifecycle and background-state logic (`SessionResumePolicyTest`,
  `SshTunnelTest`, `SshShellTest` incl. new `isAlive()` cases).
- GitHub Actions CI runs build, unit tests, lint and `licenseCheck`/SBOM on every push to `main`.

## Release note

Signing keys are intentionally never in the repo, CI, or the development container. A release is
finalized on the maintainer's signing machine: build the signed `app-online-release.apk`, verify
with `apksigner` (expected cert SHA-256 `f72043e45e10a2974925e38569d4b09db176dee4caf156eb98e90ff930a5750f`),
then sync `mobile-harness-update.json` (versionCode/versionName/sha256/sizeBytes/url/notes), tag
and publish the GitHub Release.
