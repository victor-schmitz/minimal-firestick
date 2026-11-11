#!/bin/sh
# Turns back on every app scripts/debloat.sh disabled.
# Usage: scripts/restore.sh <fire-tv-ip>
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
    adb -s "$IP:5555" shell pm enable "$PKG" </dev/null || true
done
