#!/bin/sh
# Installs the app on a Fire TV over ADB and turns its accessibility service on.
# Usage: scripts/install.sh <fire-tv-ip> [path/to/app.apk]
set -e

IP="$1"
APK="${2:-app/build/outputs/apk/release/app-release.apk}"
SERVICE="local.inicio/local.homeapps.HomeToAppsService"

if [ -z "$IP" ]; then
    echo "Usage: $0 <fire-tv-ip> [path/to/app.apk]" >&2
    exit 1
fi
if [ ! -f "$APK" ]; then
    echo "APK not found: $APK (build it with ./gradlew assembleRelease)" >&2
    exit 1
fi

adb connect "$IP:5555"
adb -s "$IP:5555" wait-for-device
adb -s "$IP:5555" install -r "$APK"

# Keep any accessibility service that is already on; only add ours.
CURRENT=$(adb -s "$IP:5555" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CURRENT" in
    *"$SERVICE"*) NEW="$CURRENT" ;;
    ""|null) NEW="$SERVICE" ;;
    *) NEW="$CURRENT:$SERVICE" ;;
esac
adb -s "$IP:5555" shell settings put secure enabled_accessibility_services "$NEW"
adb -s "$IP:5555" shell settings put secure accessibility_enabled 1

echo "Done. Press Home on the remote: the apps grid should open."
