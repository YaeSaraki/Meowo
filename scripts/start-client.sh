#!/usr/bin/env bash
set -euo pipefail
# Use an existing authenticated Prism Launcher instance configured for Minecraft 26.2 / Java 25.
INSTANCE="${MEOWO_CLIENT_INSTANCE:-}"
LAUNCHER="${MEOWO_CLIENT_LAUNCHER:-prismlauncher}"
if [[ -z "$INSTANCE" ]]; then
  echo 'Set MEOWO_CLIENT_INSTANCE to your Prism Launcher Minecraft 26.2 instance ID.' >&2
  echo 'Install/authenticate Minecraft first: https://www.minecraft.net/download' >&2
  exit 1
fi
exec "$LAUNCHER" --launch "$INSTANCE" --server 127.0.0.1:25565
