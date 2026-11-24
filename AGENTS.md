# AGENTS.md

Guidance for AI coding agents working in this repository.

## What this is

AndroidHarness is a native Android coding agent that runs on the phone. Kotlin +
Jetpack Compose, single Gradle module (`:app`), package `com.androidharness.app`.
It reads and edits files, executes shell commands, drives git, and hosts a chat
transcript where the agent's tool calls are rendered as cards.

The project also ships a small optional Node.js backend (`backend/github-oauth/`)
for GitHub OAuth login, but it is not part of the Android build or the agent
itself — see [Backend](#backend-github-oauth-login) below.

### Package layout (the architecture)

The app is a single Gradle module, so the package layout *is* the architecture.
All production code lives under
`app/src/main/java/com/androidharness/app/`:

| Package | Role |
|---|---|
| `agent/` | Agent loop: `AgentEngine`, run control (`RunManager`), retry (`StreamRetrier`), subagents, memory (`MemoryNotes`/`MemoryTopics`), todos (`TodoStore`), thinking budgets, permissions |
| `tools/` | Tool implementations + the `Tool` interface, `ToolRegistry`, and `Schema` helper. Subpackage `tools/mcp/` holds the MCP client manager and transport |
| `llm/` | Model providers — one file per wire protocol behind `ProviderType` (`OPENAI_COMPAT`, `OPENAI_RESPONSES`, `ANTHROPIC`, `GEMINI`, `GEMINI_CLI`). `ModelCatalog`, `ModelsDev`, `ModelPrices`, `ProviderFactory`/`HarnessProvider` |
| `data/` | Persistence & device data: `SessionRepository`, `ChatBackupManager`, `ProviderRepository`, `SettingsRepository`, `SnippetRepository`, Room DB (`db/`), SSH keys, terminal I/O (`TerminalManager`, `BgProcessStore`) |
| `data/env/` | Linux environment staging/deployment (`LinuxEnvironment`), root probing (`RootManager`), shell tier routing (`ShellTierRouter`), CodeGraph, GitHub provisioning, Termux SSH |
| `data/audio/` | Groq Whisper cloud transcription for voice input |
| `data/update/` | In-app update flow (`UpdateManager`, `UpdateIntents`) |
| `workspace/` | Filesystem abstraction: `WorkspaceFs`, `WorkspaceManager`, SAF (`SafPathResolver`), SSH (`SshFs`) |
| `browser/` | In-app WebView browser controller used by browser tools |
| `core/` | Shared domain primitives: `ChatMessage`/`Role`/`ToolCallData` (`Models.kt`), `Diff`, `TextLines`, `ImageRef`, `LocalPortProbe`, `WebResourceExtractor` |
| `repomap/` | Code-indexing/repo-map generation (`RepoMapCache`, `RepoMapGenerator`, `RepoSymbolExtractor`) |
| `skills/` | The skill extension system (`SkillStore` three-layer tree, `SkillParser`, `SkillAssets`) |
| `caveman/` | Caveman reply-style policy appended to the system prompt |
| `automation/` | Background automation engine (`AutomationManager`/`AutomationWorker`/`AutomationRepository`/`AutomationAiPlanner`) |
| `ui/` | Compose screens: `chat/`, `files/`, `terminal/`, `settings/`, `browser/`, `automation/`, `buildtest/`, `stats/`, `subagent/`, `setup/`, `common/`, `theme/` |

### The agent loop

`RunManager` owns the run, in an application-wide scope, so a run survives
minimizing the app, rotation, and navigating away — only process death ends it,
and a foreground service plus wakelock keep that at bay. `ChatViewModel`
*renders* that state; it deliberately does **not** own the loop. All message
persistence for a run goes through `SessionRepository` in `RunManager`, so the
database is the single source of truth and the chat UI only observes flows.
Do not move persistence back into a ViewModel.

`AgentEngine.run()` is a `Flow<AgentEvent>` and the loop is `while (true)`.
Events are a sealed hierarchy: `Text`, `Thinking`, `ToolStarted`,
`ToolFinished`, `ApprovalNeeded`, `QuestionNeeded`, `EnvironmentNeeded`,
`AssistantCommitted`, `ToolMessageCommitted`, `UserMessageInjected`, `Usage`,
`Retrying`, `EstimatedContext`, `Compacting`, `Compacted`, `Error`,
`SubagentStep`, `SubagentMessageCommitted`, `FileEdited`, `Finished`.

Things to preserve when editing the engine:

- **`maxIterations` is a safety limit, and `0` means unlimited.** Keep the
  `0` sentinel; callers depend on it.
- **Parallelism is read-only-only.** A batch fans out
  (`parallelSafeReadOnly(...)`) and falls back to sequential once a mutating
  call appears. Never widen the fan-out to mutating tools.
- **Retry only before anything has streamed.** Once text is on screen the turn
  is committed and re-requesting would duplicate visible output.
- **User input typed mid-run steers the *next* turn**, not the current one.
- Context hygiene is budget-based: it trims tool messages from the tail, never
  below two messages, so an assistant/tool pair is never orphaned.

### Providers

`llm/` has one file per wire protocol behind a `ProviderType` enum —
`OPENAI_COMPAT`, `OPENAI_RESPONSES`, `ANTHROPIC`, `GEMINI`, and `GEMINI_CLI`.
That last one is a real Gemini CLI **subprocess** authenticated by the CLI's own
Google OAuth state: it has no base URL and no API key, and its config shape is
only kept uniform. `endpointPath` names what the API actually speaks
(`/chat/completions`, `/v1/messages`, `:streamGenerateContent`, …), so add a
provider by adding the enum value, the `endpointPath` branch, and the provider
file.

### Workspace

`WorkspaceFs` is the abstraction that lets every tool work identically against
app-private storage, a user-picked SAF document tree, and a remote SSH host
(`SshFs` over pure-Java JSch). `WorkspaceFs.resolve()` **throws on path escape**
— that containment check is the security boundary, not a convenience. Note
`shellRoot` is nullable: SAF workspaces have no real directory, so any feature
that shells out must handle `null` rather than assume a cwd.

### Skills

Skills are instruction bundles (a `SKILL.md` plus optional supporting files)
that get attached to a message or invoked with a slash command. The system uses
a **three-layer tree** (`SkillStore`):

1. **Project skills** — from a `skills/` directory in the active workspace (gitignored, so they're local-only).
2. **User skills** — from a user-managed directory on shared storage.
3. **Bundled skills** — shipped in `app/src/main/assets/skills/`, one directory per skill.

The bundled catalog covers: `android-build`, `android-compose`, `android-crash`,
`doctor`, `git`, `linux-env`, `plan`, `review`, `spike`, `test-driven-development`,
`systematic-debugging`, `node-api`, `react-frontend`, `web-docs`, `browser`,
`design-tokens`, `sketch`, `popular-web-designs`, `web-design`, `simplify-code`,
`architecture-diagram`, `grill-me`, and more. Each ships a `SKILL.md` with a
front-matter header (`name`, `description`, `category`) and a procedure.

Loading the right skill at the right time is how an agent should approach
build failures, crashes, root-environment problems, git work, and deep
self-testing — follow the procedure in the skill rather than guessing.

## Root + Linux Execution (Permanent Rules)

**This phone is rooted (KernelSU verified).** All local device operations use
direct `/system/bin/su -c`; ADB is NOT required and should not be used for
local shell execution.

### Two Separate Root Execution Environments (MUST NEVER BE MERGED)

| Environment | Entry Point | PATH | LD_LIBRARY_PATH | Use For |
|---|---|---|---|---|
| **SYSTEM_ROOT** | `/system/bin/su -c` | `/system/bin:/system/xbin:/vendor/bin:/vendor/xbin` | **UNSET** | Android system binaries: `id`, `pm`, `am`, `cmd`, `ls`, `cat`, system `sh` |
| **LINUX_ROOT** | `/system/bin/su -c` | `<deployed-prefix>/bin:<deployed-prefix>/bin/applets:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin` | `<deployed-prefix>/lib` | Bundled Linux toolchain: `bash`, `git`, `python3`, `node`, `npm`, `codegraph` |

**NEVER** run Android system binaries with the Linux prefix `LD_LIBRARY_PATH`.
**NEVER** run Linux toolchain binaries without the Linux prefix `LD_LIBRARY_PATH`.

### Paths

- **App-private source prefix**: `/data/user/0/com.androidharness.app.debug/files/linux`
  - Installed by app, readable only by app uid
  - Used for `APP_LINUX` tier (app uid + linker workaround)

- **Root execution prefix**: `/data/local/tmp/androidharness/com.androidharness.app.debug/linux`
  - Deployed by root (`uid 0`), exec-capable
  - Used for `LINUX_ROOT` tier (root uid + native libraries)

### Staging & Deploy (Atomic & Self-Healing)

1. **Staging** (`LinuxEnvironmentManager.stageForShell()`):
   - Creates `prefix.tar.gz.tmp` on shared storage
   - **FULL fsync/flush** before rename
   - **Atomic rename** with FUSE fallback (copy + fsync + delete temp)
   - **ONLY THEN** writes `.harness-staged` marker with: line 1 = packageSetHash, line 2 = SHA-256 of final tar
   - On ANY failure: delete invalid marker + temp file

2. **Deploy** (`RootManager.ensureTmpPrefix()`):
   - Verifies deployed tag matches staging content hash
   - Untars as root into `/data/local/tmp/androidharness/<package>/`
   - Writes `.harness-hash` marker in deployed prefix
   - Post-deploy: syncs GitHub auth (token, gh hosts, gitconfig)

3. **Self-Healing** (automatic):
   - `verifyDeployedCopyThrottled()` called periodically and before every `LINUX_ROOT` command
   - If deployed prefix missing/stale → re-stages and re-deploys automatically
   - Agent tools + Terminal both trigger this

### Terminal Root Activation

- `TerminalManager.setPrivileged(true)` calls `root.refresh()` to actively probe KernelSU
- Root state observed via `root.state` flow; UI enables shield when `GRANTED`
- No manual `su` needed from user

### Agent Shell Tool Routing

`ShellTool` → `ShellTierRouter.run()` decides tier:
- `SHARED_STORAGE` / `SYSTEM` paths + root granted → `LINUX_ROOT` (preferred) or `SYSTEM_ROOT`
- `APP_DATA` paths → `APP_LINUX` (app uid, linker workaround)
- No root + no "All files access" → degraded notes, never silent failures

### Required Runtime Files (Validated on Deploy)

```
bin/bash
bin/git
bin/python3
bin/node
bin/npm
bin/codegraph
lib/libandroid-support.so
etc/tls/cacert.pem
etc/gitconfig
```

### DO NOT

- Edit KernelSU databases directly
- Uninstall or clear AndroidHarness app data as part of repairs
- Use ADB for local device operations when root is available
- Leak `LD_LIBRARY_PATH` from Linux prefix into Android system binaries

## Build and test

Requires JDK 17+ (CI uses 21) and the Android SDK.

```bash
./gradlew :app:assembleDebug        # build debug APK
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:assembleRelease      # release APK
```

### Running Gradle on the device

The project ships `run-gradle.sh` at the repo root. It runs Gradle in the
app-uid tier (`APP_LINUX`) via the linker-rerouted JVM wrapper, which is needed
because the toolchain's ELF binaries live under app-private storage where
SELinux denies direct exec. Set `GRADLE_USER_HOME` and `TMPDIR` as it does if
you invoke Gradle by hand:

- `JAVA_HOME` → `/data/user/0/com.androidharness.app.debug/files/jdkwrap`
- `TMPDIR` → `/data/local/tmp/androidharness-scratch`
- `org.gradle.java.home` → same `jdkwrap` dir

### Install and instrumentation

- `applicationId` `com.androidharness.app`; debug builds get the `.debug`
  suffix, so debug and release can coexist on one device.
- `compileSdk` 37, `targetSdk` 36, `minSdk` 26.
- Unit tests: `app/src/test` (JUnit 4). Instrumented tests: `app/src/androidTest`
  (`AndroidJUnitRunner`) — the browser tests need a real WebView, which no JVM
  test can exercise.

`.github/workflows/nightly.yml` builds the debug APK on pushes to `main` that
touch `app/**`, and nightly at 02:00 UTC.

## Toolchain versions

Pinned in `gradle/libs.versions.toml` — use the version catalog, never a literal:

- AGP 9.3.1, Kotlin 2.4.10, KSP 2.3.11
- Compose BOM 2026.08.00, Material3 1.5.0-alpha26
- OkHttp 4.12.0, coroutines 1.10.2, kotlinx-serialization 1.9.0

The root `build.gradle.kts` carries a `buildscript` block of dependency
`constraints` that pin several libraries (BouncyCastle, jdom2, jose4j,
httpclient, commons-lang3) to versions that fix named CVEs. **Do not downgrade
these.** They exist for AGP's own tooling and never reach shipped code. When
bumping anything, check whether a constraint references it.

`gradle.properties` pins a few Android-specific settings (parallel, config
cache, R8), enables the stable debug keystore path, and overrides the AAPT2
binary from the app's Linux prefix — these are intentional and must be
preserved. `signing-keys/` is gitignored on purpose.

## Coding conventions

### Kotlin and Compose

- `kotlin.code.style=official`; 4-space indent, no wildcard imports.
- Compose only — there is no `res/layout/` directory at all, so do not add XML
  layouts. `android.newDsl=false`.
- Screen state flows as `ViewModel` + `StateFlow`, collected with
  `collectAsStateWithLifecycle()`.
- Screens are stateless where practical: hoist state, pass callbacks down.

### Tools

The `Tool` interface (`tools/Tool.kt`) is the contract every tool implements:

```kotlin
interface Tool {
    val name: String
    val description: String
    val parametersSchema: JsonObject
    val isReadOnly: Boolean

    fun isAvailable(ctx: ToolContext): Boolean = true

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
```

- **Every tool declares `isReadOnly`.** The engine uses it to fan out read-only
  calls in parallel and to gate mutation in `AgentMode.PLAN` and full-access
  mode. Set it honestly.
- **`ToolFailure(message)`** is the signal for a tool-level error that should be
  shown to the model as a tool result (not a crash). Use it, don't throw
  unchecked exceptions.
- **`shell`** (`ShellTool.kt`) is sandboxed: dangerous commands are denied on
  signature — the first two tokens of the command line. `FULL_ACCESS` mode
  lifts the sandbox layers, but tools only honor that flag, they never enable
  it themselves.

### HTTP

`http_request` is deliberately public-addresses-only: localhost, private and
link-local destinations are refused, DNS answers are validated and pinned for
the request, proxies are bypassed, and redirects are no longer followed. Keep
it that way.

### Comments

Comment the *why*, especially for non-obvious invariants and security-relevant
decisions. This codebase does this consistently and well — see the
`sandboxOff` and stable-debug-keystore comments in `app/build.gradle.kts` for the
house style. Do not narrate what the code obviously does.

### Testing

- Mirror the production package: a fix in `tools/` gets a test in
  `app/src/test/java/com/androidharness/app/tools/`.
- JUnit 4 with `org.junit.Assert.*` static imports.
- Test method names are backtick sentence-style:
  `fun \`dangerous commands are always denied\`()`. Match this.
- Behavioral boundaries get a test with the boundary spelled out (`rm -rf /`,
  CRLF handling, `isReadOnly` accuracy).
- The repo also keeps a Python self-test (`scripts/test_message_truncation.py`)
  that exercises Room's actual truncate query against SQLite — run it with
  `python3 scripts/test_message_truncation.py` when touching message retention.

## Backend (GitHub OAuth login)

`backend/github-oauth/` is a tiny, dependency-free Node.js 22 server (`server.mjs`)
that proxies GitHub's token exchange/refresh so the client secret never touches
the app. It is optional and not built by Gradle. To run locally:

```bash
cd backend/github-oauth
npm start    # PORT=8080 if unset
node --test server.test.mjs
```

Tests: `./gradlew :app:testDebugUnitTest --tests 'com.androidharness.app.data.github.GitHubOAuthProtocolTest'`
plus `node --test backend/github-oauth/server.test.mjs`.

Build with GitHub config:
```bash
./gradlew :app:assembleRelease -PGITHUB_CLIENT_ID=your-client-id -PGITHUB_AUTH_BACKEND=https://your-service
```

See `backend/github-oauth/README.md` for full OAuth App registration steps.

## Change hygiene

- Keep a change focused; the repo's own `CONTRIBUTING.md` asks for that and the
  maintainer reviews diffs.
- Update `CHANGELOG.md` for user-visible behavior changes. Entries are grouped
  as `Added` / `Changed` / `Fixed`, and each one is a bolded lead-in followed by
  a sentence explaining the *user-visible* effect, not the implementation.
- Add or update tests when you change behavior.
- Never commit API keys, signing keys, `local.properties`, or workspace data.
  `signing-keys/` is gitignored on purpose — the stable debug keystore keeps
  signature identity across installs; do not replace it.

## Repowiki

`repowiki/` (50 pages) is a generated architecture reference covering the agent
engine, tools, providers, MCP stack, workspace layer, environment diagnostics,
and shared data models. When in doubt about a subsystem's design, read the
relevant page first — it is the primary documentation and is more detailed than
this file. Index: `repowiki/README.md`.

## Before you finish

- `./gradlew :app:testDebugUnitTest` passes.
- No new warnings introduced.
- CHANGELOG updated if behavior changed.
- For UI changes, a screenshot helps reviewers.
