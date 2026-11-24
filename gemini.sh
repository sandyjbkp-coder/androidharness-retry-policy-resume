#!/bin/bash
# gemini.sh — launcher for Gemini CLI v0.62 on this Android device.
#
# Three Android-specific workarounds are baked in:
#
#   1. GEMINI_CLI_NO_RELAUNCH=true
#      The CLI re-spawns itself with --max-old-space-size=... but
#      process.execPath here is /apex/com.android.runtime/bin/linker64,
#      which cannot be exec'd  ->  "error: expected absolute path".
#
#   2. TERMUX_VERSION=<x>
#      clipboardy throws "You need to install Termux to work on Android" at
#      import time when process.platform === 'android'. Faking the var makes it
#      pick a termux code path we never actually reach (headless use only).
#
#   3. --skip-trust
#      Headless runs are refused in untrusted directories otherwise.
#
# node-pty fails to build here (--ignore-scripts during npm install, and it
# needs node-gyp), so INTERACTIVE mode is unavailable. Use -p/--prompt.
#
# Auth: reads ~/.gemini/oauth_creds.json (created by oauth-login.mjs).
# Falls back to GEMINI_API_KEY if that file is missing.

set -euo pipefail
cd "$(dirname "$0")"

export GEMINI_CLI_NO_RELAUNCH=true
export TERMUX_VERSION="${TERMUX_VERSION:-0.118.0}"
export GEMINI_CLI_TRUST_WORKSPACE=true

# Optional local .env (only used if it exists).
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

# Prefer OAuth; use the API key only when no OAuth credentials are cached.
if [ -f "$HOME/.gemini/oauth_creds.json" ]; then
  unset GEMINI_API_KEY || true
fi

# `node` is a shell function here that re-execs via /system/bin/linker64, so it
# cannot be used with `exec`. Invoke the real binary directly instead.
NODE_BIN="${NODE_BIN:-$(command -v node)}"

# shellcheck disable=SC2086
"$NODE_BIN" node_modules/@google/gemini-cli/bundle/gemini.js --skip-trust "$@"
