#!/usr/bin/env bash
# Links the Scala.js DOM fixture, runs it in headless Chromium and WebKit, then
# proves that the runner fails on injected faults and cleans up after
# interruption. From the Avocet checkout:
#   nix develop .#browser --no-write-lock-file --command bash scripts/test-dom-fixture.sh
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
usage='Run: nix develop .#browser --no-write-lock-file --command bash scripts/test-dom-fixture.sh'
for tool in node sbt timeout; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'Missing %s. %s\n' "$tool" "$usage" >&2
    exit 2
  fi
done
if [[ -z "${PLAYWRIGHT_DRIVER_PATH:-}" || -z "${PLAYWRIGHT_BROWSERS_PATH:-}" ]]; then
  printf 'Missing the Nix Playwright environment. %s\n' "$usage" >&2
  exit 2
fi
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
cd "$repo_root"

# Record what is being tested; a dirty tree is reported, not rejected.
if command -v git >/dev/null 2>&1 && commit="$(git rev-parse HEAD 2>/dev/null)"; then
  dirty=''
  [[ -n "$(git status --porcelain 2>/dev/null)" ]] && dirty=' (working tree has uncommitted changes)'
  printf 'Source commit: %s%s\n' "$commit" "$dirty"
fi

active_pid=''
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$active_pid" ]]; then
    # Signal only this script's timeout process; it forwards to its own group,
    # and the runner closes its owned browser and server.
    kill -TERM "$active_pid" 2>/dev/null || true
    wait "$active_pid" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Runs one bounded step: run_step <limit> <description> <command...>
run_step() {
  local limit=$1 description=$2
  shift 2
  printf '%s\n' "$description"
  timeout --kill-after=10s "$limit" "$@" &
  active_pid=$!
  local status=0
  wait "$active_pid" || status=$?
  active_pid=''
  if [[ "$status" -ne 0 ]]; then
    printf '%s failed (exit %s).\n' "$description" "$status" >&2
    exit "$status"
  fi
}

# Linking first means the browsers never run a stale module.
run_step 1200s 'Linking the DOM fixture' \
  sbt --batch -Dsbt.server.autostart=false domFixture/fastLinkJS
run_step 180s 'Running the DOM fixture in Chromium and WebKit' \
  node misc/dom-fixture/run-browsers.mjs chromium webkit
run_step 300s 'Checking that the runner fails closed and cleans up' \
  node misc/dom-fixture/runner-self-test.mjs
