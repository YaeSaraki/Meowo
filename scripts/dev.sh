#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Launch client only after the local server socket is ready; server stays attached to this terminal.
(
  for ((i=0;i<300;i++)); do
    if (echo > /dev/tcp/127.0.0.1/25565) >/dev/null 2>&1; then
      "$ROOT/scripts/start-client.sh"; exit
    fi
    sleep 1
  done
  echo 'Server did not become ready within 300 seconds; client was not launched.' >&2
) &
CLIENT_JOB=$!
trap 'kill "$CLIENT_JOB" 2>/dev/null || true' EXIT INT TERM
"$ROOT/scripts/start-server.sh"
