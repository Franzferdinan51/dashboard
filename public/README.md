# Dashboard bridge — public edition

A sanitized copy of the bridge that powers the Mac Studio Dashboard
(Android app + web UI in the parent repo). This edition has all personal
identifiers removed so it can be shared publicly; the owner's live copy
lives in `../bridge/` and is identical except for the strings below.

## What was scrubbed

- The Windows account name in the miner paths
  (`C:\Users\YOURNAME\Desktop\srbminer\...`) — set it to your own.
- The launchd label `com.duckets.dashboard-bridge` →
  `com.example.dashboard-bridge` (bridge, plist, install script, and the
  web console's restart hint all use the new label consistently).

Nothing else personal was ever in the code: no IP addresses (the bridge
binds 0.0.0.0:8901; clients configure their own URL), no API keys or
tokens (the LM Studio key and Darkbloom token are read from files on the
host at runtime), no wallet addresses (the mining snapshot is a local
data file, not code).

## Before you run it, configure

- `bridge/dashboard_bridge.py`: the `YOURNAME` Windows paths if you use
  the Windows/mining probes; a `~/bin/batman-ssh` helper (SSH wrapper for
  the Windows PC — rename/replace with your own) if you use the miner
  actions; `~/.lmstudio/bin/lms` and `~/.darkbloom/` are expected for the
  LM Studio / Darkbloom features and degrade gracefully if absent.
- `bridge/install_bridge.sh` installs the LaunchAgent from the plist
  (label `com.example.dashboard-bridge`).

Internal names for the Windows rig ("batman") remain in code identifiers
and JSON field names — they're just labels, and renaming the JSON keys
would break the pairing with the web UI and app in this repo. Rename
freely in your fork, in both places together.
