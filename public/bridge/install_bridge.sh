#!/bin/bash
# Install the Mac Studio Dashboard bridge as a LaunchAgent so it survives
# reboots and restarts itself if it ever dies.
set -euo pipefail

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
DEST_DIR="$HOME/dashboard-bridge"
PLIST="$HOME/Library/LaunchAgents/com.example.dashboard-bridge.plist"
LABEL="com.example.dashboard-bridge"
GUI="gui/$(id -u)"

# /usr/bin/python3 is a stub on a stock Mac until the Xcode Command Line
# Tools are installed; under KeepAlive that failure crash-loops silently.
# Verify python3 can actually run the bridge before installing anything.
if ! /usr/bin/python3 -c 'import http.server, json' 2>/dev/null; then
  echo "error: /usr/bin/python3 can't import http.server — install the Xcode Command Line Tools first (xcode-select --install)" >&2
  exit 1
fi

mkdir -p "$DEST_DIR" "$HOME/Library/LaunchAgents"
cp "$SRC_DIR/dashboard_bridge.py" "$DEST_DIR/dashboard_bridge.py"
cp "$SRC_DIR/web_dashboard.html" "$DEST_DIR/web_dashboard.html"
# The plist is a template: fill in this user's home directory.
sed "s|__HOME__|$HOME|g" "$SRC_DIR/com.example.dashboard-bridge.plist" > "$PLIST"

# Stop any manually-started copy and (re)load the agent using the modern
# launchctl verbs (unload/load are deprecated and can't revive a disabled
# agent).
pkill -f dashboard_bridge.py 2>/dev/null || true
launchctl bootout "$GUI/$LABEL" 2>/dev/null || true
launchctl enable "$GUI/$LABEL" 2>/dev/null || true
launchctl bootstrap "$GUI" "$PLIST"

# Verify OUR agent came up — a bare health 200 could come from a stale
# listener on the port, so require a fresh uptime from the new process.
sleep 2
UPTIME="$(curl -s -m 3 --retry 3 --retry-delay 1 http://127.0.0.1:8901/api/health | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("uptime_seconds", 9999))' 2>/dev/null || echo fail)"
if [ "$UPTIME" != "fail" ] && [ "$UPTIME" -lt 60 ] 2>/dev/null; then
  echo "bridge up: http://127.0.0.1:8901/api/health (uptime ${UPTIME}s)"
else
  echo "bridge did not come up cleanly (health uptime: $UPTIME); check $DEST_DIR/bridge.err.log" >&2
  exit 1
fi
