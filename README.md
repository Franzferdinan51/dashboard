# Mac Studio Dashboard

A native Android dashboard for an Apple Silicon Mac: live system vitals,
[Darkbloom](https://www.darkbloom.dev) provider status & earnings, and
LM Studio library state — in one glanceable app styled after the
Muse Companion App (deep-navy glass cards, blue accent, rounded "bubble"
surfaces).

Two parts:

| Part | What it is |
| --- | --- |
| `bridge/` | A zero-dependency Python 3 server that runs on the Mac and serves system + Darkbloom + LM Studio data as JSON on port 8901. |
| `android/` | A Kotlin / Jetpack Compose app (phone + tablet adaptive layouts) that polls the bridge. |

## What it shows

**Overview**
- Machine hero card: chip, GPU cores, macOS version, uptime, load, thermal state
- Live gauges: CPU, memory, GPU unified-memory usage, with CPU history sparkline
- Darkbloom strip: earning status, ledger balance, paid jobs, warm models
- Network rates/totals, per-volume disk usage, LM Studio loaded models

**System**
- CPU split (user / system / idle), load average, thermal state
- Memory breakdown (active / wired / compressed / inactive / free, swap)
- Storage volumes, top processes by CPU

**Darkbloom**
- Settled earnings from the account ledger: balance, withdrawable, lifetime
- Earnings by model and the recent job ledger (incl. base rewards)
- Trust & App Attest status, provider uptime, resident models, engine slots
- GPU capacity pool and Autopilot state

**Settings**
- Bridge address and refresh interval (2–30 s)

## Quick start

### 1. Run the bridge on the Mac

Requires stock macOS Python 3 (`/usr/bin/python3`) — no pip installs.

```sh
cd bridge
./install_bridge.sh   # installs to ~/dashboard-bridge + a LaunchAgent (auto-start at login)
curl http://127.0.0.1:8901/api/health
```

The bridge is read-only: it shells out to `sysctl`, `vm_stat`, `top`, `df`,
`netstat`, reads Darkbloom's local daemon state, and summarizes the
Darkbloom account-earnings API (using the provider's own local auth token,
which never leaves the Mac).

Endpoints: `/api/health`, `/api/overview`, `/api/system`, `/api/darkbloom`,
`/api/lmstudio`.

Environment knobs: `--port` (default 8901), `--lm-url` (LM Studio API base),
`DASHBOARD_MACHINE_NAME` (display name shown in the app).

### 2. Build the app

Android Studio or the Gradle wrapper; JDK 17+, Android SDK 36.

```sh
cd android
./gradlew assembleDebug
```

The app needs to know where your bridge lives:

- **In-app:** Settings tab → Bridge URL (e.g. `http://<your-mac>:8901`).
- **Build-time default:** put `dashboard.bridgeUrl=http://<your-mac>:8901`
  in `android/local.properties` (git-ignored, never committed).

Then install: `adb install app/build/outputs/apk/debug/app-debug.apk`

Layout adapts to window size: bottom navigation + single column on phones,
navigation rail + two-column cards on tablets and landscape.

## Privacy

Nothing in this repo contains personal data, host addresses, account IDs,
or tokens — defaults are placeholders and per-machine values live in
`local.properties` / the in-app setting. The bridge exposes read-only stats
on your LAN/tailnet only; keep it off the public internet.

## Stack

- Android: Kotlin 2, Jetpack Compose (Material 3 + WindowSizeClass), OkHttp,
  coroutines. Min SDK 26, target SDK 36.
- Bridge: Python 3 standard library only.
