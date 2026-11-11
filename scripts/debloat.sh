#!/bin/sh
# OPTIONAL. Disables Amazon background apps the home-screen setup does not need (telemetry, ads,
# tutorials, extra Alexa features). Nothing is uninstalled: scripts/restore.sh turns everything back on,
# and so does a factory reset. Tested on a Fire TV Stick Lite (Fire OS 7); review the list before running.
# Usage: scripts/debloat.sh <fire-tv-ip>
set -e

IP="$1"
if [ -z "$IP" ]; then
    echo "Usage: $0 <fire-tv-ip>" >&2
    exit 1
fi
DIR=$(dirname "$0")

adb connect "$IP:5555"
adb -s "$IP:5555" wait-for-device

grep -v '^#' "$DIR/debloat-packages.txt" | grep -v '^$' | while read -r PKG; do
    if adb -s "$IP:5555" shell pm path "$PKG" 2>/dev/null | grep -q package:; then
        adb -s "$IP:5555" shell pm disable-user --user 0 "$PKG" </dev/null
    else
        echo "skip (not installed): $PKG"
    fi
done
