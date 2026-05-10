#!/usr/bin/env bash
set -euo pipefail
PACKAGE="com.rezvani.mesh"
ACTIVITY="${PACKAGE}/.MainActivity"
LOG_TAGS="DiagLogger:V RezvanMesh:V RezvanRadioService:V AndroidRuntime:E *:S"
BLUE='\033[0;34m'; GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
log() { echo -e "${BLUE}[dev.sh]${NC} $*"; }
ok()  { echo -e "${GREEN}[dev.sh]${NC} $*"; }
err() { echo -e "${RED}[dev.sh]${NC} $*"; }

if ! adb devices | grep -q "device$"; then
    err "No device connected. Run: adb connect <phone-ip>:5555"
    exit 1
fi
DEVICE=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
log "Target: $DEVICE"

if [[ "${1:-}" == "--rust" ]]; then
    log "Rebuilding Rust core..."
    (cd rezvan-core && cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release)
    ok "Rust rebuild complete"
fi

log "Building & installing debug APK..."
(cd android && ./gradlew installDebug -q)
ok "Install complete"

log "Launching app..."
adb shell am start -n "$ACTIVITY" >/dev/null
ok "App started"

log "Streaming logs (Ctrl+C to stop)..."
adb logcat -c
adb logcat $LOG_TAGS
