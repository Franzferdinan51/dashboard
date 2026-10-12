#!/bin/bash
# Install the Mac Studio Dashboard bridge as a LaunchAgent so it survives
# reboots and restarts itself if it ever dies.
set -euo pipefail

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
DEST_DIR="$HOME/dashboard-bridge"
PLIST="$HOME/Library/LaunchAgents/com.duckets.dashboard-bridge.plist"

mkdir -p "$DEST_DIR" "$HOME/Library/LaunchAgents"
cp "$SRC_DIR/dashboard_bridge.py" "$DEST_DIR/dashboard_bridge.py"
# The plist is a template: fill in this user's home directory.
sed "s|__HOME__|$HOME|g" "$SRC_DIR/com.duckets.dashboard-bridge.plist" > "$PLIST"

# Stop any manually-started copy and (re)load the agent.
pkill -f dashboard_bridge.py 2>/dev/null || true
launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"

sleep 2
if curl -s http://127.0.0.1:8901/api/health > /dev/null; then
  echo "bridge up: http://127.0.0.1:8901/api/health"
else
  echo "bridge did not come up; check $DEST_DIR/bridge.err.log" >&2
  exit 1
fi
