#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/versions.env"
RUN_DIR="${MEOWO_RUN_DIR:-$ROOT/.run/server}"
JAVA_BIN="${MEOWO_JAVA:-java}"
echo "Using Java: $JAVA_BIN"
if [[ "$($JAVA_BIN -version 2>&1 | head -n 1)" != *'"25.'* ]]; then
  echo "Paper 26.2 requires Java 25. Set MEOWO_JAVA=/path/to/java." >&2; exit 1
fi
if [[ "${1:-}" != '--skip-build' ]]; then "$ROOT/gradlew" -p "$ROOT" :core:build; fi
mkdir -p "$RUN_DIR/plugins"
verify() { python3 - "$1" "$2" <<'CHECK'
import hashlib,sys
with open(sys.argv[1],'rb') as f:
    digest=hashlib.sha256()
    for chunk in iter(lambda: f.read(1024*1024), b''): digest.update(chunk)
    actual=digest.hexdigest()
if actual != sys.argv[2]: raise SystemExit('SHA-256 mismatch: '+sys.argv[1])
CHECK
}
download() {
  local url="$1" sha="$2" dest="$3"
  if [[ -f "$dest" ]]; then verify "$dest" "$sha"; return; fi
  curl --fail --location --retry 3 --connect-timeout 20 "$url" -o "$dest.part"
  verify "$dest.part" "$sha"
  mv "$dest.part" "$dest"
}
download "$PAPER_URL" "$PAPER_SHA256" "$RUN_DIR/paper.jar"
download "$PACKETEVENTS_URL" "$PACKETEVENTS_SHA256" "$RUN_DIR/plugins/packetevents-2.14.0.jar"
cp "$ROOT/core/build/libs/Meowo-1.0.0.jar" "$RUN_DIR/plugins/Meowo.jar"
if [[ "${ACCEPT_MINECRAFT_EULA:-false}" == true ]]; then printf 'eula=true
' > "$RUN_DIR/eula.txt"; fi
if [[ ! -f "$RUN_DIR/eula.txt" ]] || ! grep -Eq '^eula=true$' "$RUN_DIR/eula.txt"; then
  echo 'Read https://aka.ms/MinecraftEULA then set ACCEPT_MINECRAFT_EULA=true to accept.' >&2; exit 1
fi
if [[ ! -f "$RUN_DIR/server.properties" ]]; then cp "$ROOT/scripts/server.properties" "$RUN_DIR/server.properties"; fi
cd "$RUN_DIR"
exec "$JAVA_BIN" -Xms512M -Xmx2G -jar paper.jar --nogui
