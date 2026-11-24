---
name: linux-env
description: Command 126/127, missing git/python/node, or env looks broken. Use env_status first.
category: environment
---

# Linux environment

The harness owns PATH. You do not.

## When to use
- Exit 126 / 127, "not found", "permission denied" on a basic tool
- Need git, python, node, or a compiler
- You are about to poke `/data/local/tmp` to "see if root works"

## When not to use
- The command failed with a normal non-zero and stderr from the program itself

## Procedure
1. Call `env_status` once. Check: you have the official state.
2. Tell the user what it reported (root, Linux prefix, storage, tier).
3. If the prefix is missing, they install from Settings → Terminal. Do not retry linker64 / toybox hacks.
4. If root is needed for the path, say so. Do not loop `pm` / system paths on the app tier.
5. After they install, one more `env_status`, then continue the original task.

## Permanent Rules for Root + Linux Execution

**This phone is rooted (KernelSU verified).** All local device operations use direct `/system/bin/su -c`; ADB is NOT required and should not be used for local shell execution.

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

## Pitfalls
- Never invoke `/system/bin/linker64` yourself.
- `/data/local/tmp` is not a status API.

## Verification
The next real command runs on the tier `env_status` described, or the user was asked to unlock it.
