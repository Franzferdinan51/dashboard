#!/usr/bin/env python3
"""
Mac Studio Dashboard Bridge
===========================
Read-only JSON API that exposes live Mac Studio vitals + Darkbloom provider
state + LM Studio state to the Dashboard Android app.

Stdlib only. No pip installs. Run:

    python3 dashboard_bridge.py [--port 8901] [--host 0.0.0.0]

Endpoints:
    GET  /              -> detailed web dashboard (web_dashboard.html)
    GET  /api/health    -> {"ok": true, ...}
    GET  /api/overview  -> everything (system + darkbloom + lmstudio)
    GET  /api/system    -> Mac vitals only
    GET  /api/darkbloom -> Darkbloom provider only
    GET  /api/lmstudio  -> LM Studio only
    GET  /api/decisions -> recent state-change events (?limit=50)
    GET  /api/windows   -> Windows PC (Batman-3) vitals + miner state
    GET  /api/mining    -> Kryptex mining fleet + Batman-3 live miner
    POST /mcp           -> MCP server (read-only tools: health, overview,
                           system, darkbloom, lmstudio, decisions)

Data sources (all local, read-only):
  * system    : sysctl / vm_stat / top / df / netstat / ps / pmset
  * darkbloom : ~/.darkbloom/daemon-state.json + account earnings API
  * lmstudio  : http://127.0.0.1:1234/api/v0/models (API key from
                ~/.config/lmstudio/api_key when present)
"""

import argparse
import base64
import datetime
import decimal
import gzip
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
DARKBLOOM_STATE = os.path.join(HOME, ".darkbloom", "daemon-state.json")
DARKBLOOM_TOKEN = os.path.join(HOME, ".darkbloom", "auth_token")
LMSTUDIO_KEY = os.path.join(HOME, ".config", "lmstudio", "api_key")
LMSTUDIO_BASE = "http://127.0.0.1:1234"
BRIDGE_VERSION = "1.7.0"
_STARTED_AT = time.time()

# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------

_cache = {}
_cache_lock = threading.Lock()
_cache_locks = {}


def _rebuild_async(key, fn, lock):
    """Background refresh for stale-while-revalidate keys. A failed rebuild
    keeps the last good value rather than blanking it."""
    def work():
        if not lock.acquire(blocking=False):
            return
        try:
            val = fn()
            if val is not None:
                with _cache_lock:
                    _cache[key] = (time.time(), val)
        except Exception:
            pass
        finally:
            lock.release()
    threading.Thread(target=work, daemon=True).start()


def cached(key, ttl, fn, swr=False):
    """TTL cache with a per-key build lock (concurrent callers share one
    build instead of stampeding) and short negative caching: a None result
    (a failed fetch) is only held for 10s so one blip can't blank data for
    the full TTL.

    With swr=True, an expired-but-present value is served immediately and
    refreshed in the background — a poll never waits on a slow build (the
    macOS system sample takes ~1.5s and was landing inside poll latency,
    which made the app's live updates feel laggy)."""
    now = time.time()
    with _cache_lock:
        hit = _cache.get(key)
        if hit and now - hit[0] < ttl:
            return hit[1]
        lock = _cache_locks.setdefault(key, threading.Lock())
    if swr and hit is not None and hit[1] is not None:
        _rebuild_async(key, fn, lock)
        return hit[1]
    with lock:
        now = time.time()
        with _cache_lock:
            hit = _cache.get(key)
            if hit and now - hit[0] < ttl:
                return hit[1]
        val = fn()
        stamp = time.time()
        if val is None and ttl > 10:
            stamp -= ttl - 10  # negative entry expires in ~10s
        with _cache_lock:
            _cache[key] = (stamp, val)
        return val


def run(cmd, timeout=6):
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return out.stdout or ""
    except Exception:
        return ""


def sysctl(name):
    return run(["sysctl", "-n", name], timeout=3).strip()


def http_get_json(url, headers=None, timeout=4):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


# --------------------------------------------------------------------------
# system vitals
# --------------------------------------------------------------------------

def _cpu_percent():
    # top's second sample reflects current usage (first is since-boot avg)
    out = run(["top", "-l", "2", "-n", "0", "-s", "1"], timeout=8)
    matches = re.findall(r"CPU usage:\s*([\d.]+)% user,\s*([\d.]+)% sys,\s*([\d.]+)% idle", out)
    if matches:
        user, sysp, idle = (float(x) for x in matches[-1])
        return {"user_pct": user, "sys_pct": sysp, "idle_pct": idle,
                "used_pct": round(user + sysp, 1)}
    # Parse failure: nulls, not a plausible-looking 0% lie.
    return {"user_pct": None, "sys_pct": None, "idle_pct": None, "used_pct": None}


def _memory():
    total = int(sysctl("hw.memsize") or 0)
    out = run(["vm_stat"], timeout=3)
    page = 16384
    m = re.search(r"page size of (\d+) bytes", out)
    if m:
        page = int(m.group(1))
    pages = {}
    for key, label in [
        ("free", "Pages free"), ("active", "Pages active"),
        ("inactive", "Pages inactive"), ("speculative", "Pages speculative"),
        ("wired", "Pages wired down"), ("compressed", "Pages occupied by compressor"),
        ("file_backed", "File-backed pages"), ("anonymous", "Anonymous pages"),
    ]:
        mm = re.search(re.escape(label) + r":\s+(\d+)\.", out)
        pages[key] = int(mm.group(1)) * page if mm else 0
    swap_raw = sysctl("vm.swapusage")
    swap = {"total_mb": 0.0, "used_mb": 0.0, "free_mb": 0.0}
    sm = re.search(r"total = ([\d.]+)M.*?used = ([\d.]+)M.*?free = ([\d.]+)M", swap_raw)
    if sm:
        swap = {"total_mb": float(sm.group(1)), "used_mb": float(sm.group(2)),
                "free_mb": float(sm.group(3))}
    used = pages["active"] + pages["wired"] + pages["compressed"]
    return {
        "total_bytes": total,
        "used_bytes": used,
        "free_bytes": pages["free"],
        "active_bytes": pages["active"],
        "inactive_bytes": pages["inactive"],
        "wired_bytes": pages["wired"],
        "compressed_bytes": pages["compressed"],
        "speculative_bytes": pages["speculative"],
        "file_backed_bytes": pages["file_backed"],
        "anonymous_bytes": pages["anonymous"],
        "used_pct": round(100.0 * used / total, 1) if total else 0.0,
        "swap": swap,
    }


def _disks():
    disks = []
    for path, label in [("/", "Internal"), ("/Volumes/Sabrent", "Sabrent")]:
        if os.path.exists(path):
            try:
                u = shutil.disk_usage(path)
                disks.append({
                    "label": label, "path": path,
                    "total_bytes": u.total, "used_bytes": u.used,
                    "free_bytes": u.free,
                    "used_pct": round(100.0 * u.used / u.total, 1) if u.total else 0.0,
                })
            except Exception:
                pass
    return disks


_net_prev = {"t": 0.0, "in": 0, "out": 0}


def _network():
    global _net_prev
    out = run(["netstat", "-ibn"], timeout=3)
    total_in = total_out = 0
    seen = set()
    for line in out.splitlines():
        parts = line.split()
        # Name Mtu Network Address Ipkts Ierrs Ibytes Opkts Oerrs Obytes Coll
        # en* = physical NICs, utun* = VPN tunnels (incl. Tailscale, which is
        # the interface the dashboard's own traffic rides).
        if len(parts) >= 10 and parts[0].startswith(("en", "utun")) and parts[0] not in seen:
            try:
                total_in += int(parts[6])
                total_out += int(parts[9])
                seen.add(parts[0])
            except ValueError:
                pass
    now = time.time()
    rate_in = rate_out = 0.0
    if _net_prev["t"]:
        dt = now - _net_prev["t"]
        if dt > 0:
            rate_in = max(0.0, (total_in - _net_prev["in"]) / dt)
            rate_out = max(0.0, (total_out - _net_prev["out"]) / dt)
    _net_prev = {"t": now, "in": total_in, "out": total_out}
    return {"bytes_in": total_in, "bytes_out": total_out,
            "rate_in_bps": round(rate_in), "rate_out_bps": round(rate_out)}


def _processes(limit=12):
    out = run(["ps", "-eo", "pid,pcpu,pmem,etime,comm", "-r"], timeout=4)
    procs = []
    for line in out.splitlines()[1:limit + 1]:
        parts = line.split(None, 4)
        if len(parts) == 5:
            try:
                procs.append({
                    "pid": int(parts[0]), "cpu_pct": float(parts[1]),
                    "mem_pct": float(parts[2]), "elapsed": parts[3],
                    "name": os.path.basename(parts[4]),
                })
            except ValueError:
                pass
    return procs


def _thermal():
    out = run(["pmset", "-g", "therm"], timeout=3)
    level = "nominal"
    if "CPU_Speed_Limit" in out:
        m = re.search(r"CPU_Speed_Limit\s*=\s*(\d+)", out)
        speed_limit = int(m.group(1)) if m else 100
        if speed_limit < 100:
            level = "throttled"
        return {"state": level, "cpu_speed_limit_pct": speed_limit, "raw": out.strip()[:200]}
    return {"state": level, "cpu_speed_limit_pct": 100, "raw": out.strip()[:200]}


def _static_info():
    def build():
        brand = sysctl("machdep.cpu.brand_string")
        cores = sysctl("hw.ncpu")
        model = sysctl("hw.model")
        gpu_cores = None
        sp = run(["system_profiler", "SPDisplaysDataType"], timeout=15)
        gm = re.search(r"Total Number of Cores:\s*(\d+)", sp)
        if gm:
            gpu_cores = int(gm.group(1))
        ver = run(["sw_vers"], timeout=3)
        os_ver = re.search(r"ProductVersion:\s*(\S+)", ver)
        name = run(["scutil", "--get", "ComputerName"], timeout=3).strip()
        # Display name: explicit override wins (set DASHBOARD_MACHINE_NAME in
        # the LaunchAgent plist). Falls back to the macOS ComputerName, which
        # may not match the hardware (e.g. a stale name from initial setup).
        display = os.environ.get("DASHBOARD_MACHINE_NAME") or name or "Mac Studio"
        return {
            "computer_name": display,
            "chip": brand,
            "model": model,
            "cpu_cores": int(cores) if cores.isdigit() else None,
            "gpu_cores": gpu_cores,
            "macos_version": os_ver.group(1) if os_ver else None,
        }
    return cached("static", 3600, build)


def system_stats():
    def build():
        boot = sysctl("kern.boottime")
        boot_epoch = None
        bm = re.search(r"sec\s*=\s*(\d+)", boot)
        if bm:
            boot_epoch = int(bm.group(1))
        load = sysctl("vm.loadavg")
        lm = re.findall(r"[\d.]+", load)
        return {
            **_static_info(),
            "uptime_seconds": int(time.time() - boot_epoch) if boot_epoch else None,
            "load_avg": [float(x) for x in lm[:3]] if lm else [],
            "cpu": _cpu_percent(),
            "memory": _memory(),
            "disks": _disks(),
            "network": _network(),
            "thermal": _thermal(),
            "top_processes": _processes(),
        }
    return cached("system", 2, build, swr=True)


# --------------------------------------------------------------------------
# darkbloom
# --------------------------------------------------------------------------

def _parse_ts(value):
    """Ledger timestamps arrive as ISO-8601 UTC strings; accept epoch
    seconds too. Returns epoch seconds (float) or None."""
    if isinstance(value, (int, float)) and value > 0:
        return value / 1000.0 if value > 1e12 else float(value)
    if isinstance(value, str):
        try:
            return datetime.datetime.fromisoformat(
                value.replace("Z", "+00:00")).timestamp()
        except Exception:
            return None
    return None


# "Earning" must mean money actually landed recently — a lifetime job
# count made the flag permanently true (it stayed on through a 52-minute
# dead session on 2026-10-12). A provider counts as earning when a ledger
# credit (paid job or base reward) or session inference activity is newer
# than this window.
EARNING_WINDOW_SECONDS = 900

# Session activity tracker: stamped whenever the daemon's request/token
# counters increase between samples (the ledger check alone can lag —
# credits settle after the work).
_SESSION_ACTIVITY = {"requests": None, "tokens": None, "at": 0.0}


def _note_session_activity(requests, tokens):
    prev_r = _SESSION_ACTIVITY["requests"]
    prev_t = _SESSION_ACTIVITY["tokens"]
    if (isinstance(requests, (int, float)) and isinstance(prev_r, (int, float))
            and requests > prev_r):
        _SESSION_ACTIVITY["at"] = time.time()
    if (isinstance(tokens, (int, float)) and isinstance(prev_t, (int, float))
            and tokens > prev_t):
        _SESSION_ACTIVITY["at"] = time.time()
    _SESSION_ACTIVITY["requests"] = requests
    _SESSION_ACTIVITY["tokens"] = tokens


def _is_earning(running, last_credit_at, last_activity_at, now,
                window=EARNING_WINDOW_SECONDS):
    if not running:
        return False
    for ts in (last_credit_at, last_activity_at):
        if ts and -60 <= now - ts <= window:
            return True
    return False


# --- Earnings snapshots: the bridge's own ground-truth ledger -----------
# Every successful earnings fetch snapshots the API's lifetime total,
# split into base-reward vs paid-job cumulative micro-USD (attributed
# from the credit entries the fetch saw; any unattributed residual is
# apportioned by the observed mix). Realized earnings over any trailing
# window are then exact differences of this series — no extrapolation
# from a handful of recent credits, immune to credits the recent
# entries window skipped, and downtime-inclusive by construction.
EARNINGS_SNAPSHOTS = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "earnings-snapshots.jsonl")
_SNAP_KEEP_SECONDS = 8 * 86400
_snap_lock = threading.Lock()
_snap_cache = None
_split_seen = {}  # (created_at, amount, model) -> True, insertion-ordered
_split_state = {"base": None, "paid": None}  # cumulative micro, ledger-seeded


def _usd_str_to_micro(s):
    try:
        return int((decimal.Decimal(str(s)) * 1_000_000).to_integral_value(
            rounding=decimal.ROUND_DOWN))
    except Exception:
        return None


def _snaps_load():
    """Rows of (ts, total_micro, base_cum_micro|None, paid_cum_micro|None),
    oldest first. Two-field legacy rows load with split = None."""
    global _snap_cache
    if _snap_cache is not None:
        return _snap_cache
    rows = []
    try:
        with open(EARNINGS_SNAPSHOTS) as f:
            for line in f:
                try:
                    v = json.loads(line)
                    rows.append((float(v[0]), int(v[1]),
                                 int(v[2]) if len(v) > 2 else None,
                                 int(v[3]) if len(v) > 3 else None))
                except Exception:
                    continue
    except Exception:
        pass
    rows.sort()
    _snap_cache = rows
    return rows


def _snap_record(total_micro, base_cum, paid_cum):
    """Append a snapshot when the lifetime total moves (split rides
    along). Windows are computed against the live point, so unchanged
    totals need no rows."""
    if total_micro is None:
        return
    now = time.time()
    with _snap_lock:
        rows = _snaps_load()
        if rows and rows[-1][1] == total_micro:
            return
        rows.append((now, total_micro, base_cum, paid_cum))
        cutoff = now - _SNAP_KEEP_SECONDS
        kept = [r for r in rows if r[0] >= cutoff] or rows[-1:]
        _snap_cache = kept
        try:
            if len(kept) != len(rows):
                with open(EARNINGS_SNAPSHOTS, "w") as f:
                    for r in kept:
                        f.write(json.dumps(list(r)) + "\n")
            else:
                with open(EARNINGS_SNAPSHOTS, "a") as f:
                    f.write(json.dumps([now, total_micro, base_cum,
                                        paid_cum]) + "\n")
        except Exception:
            pass


def _window_series(rows, now, current, window, idx=1):
    """(rate_per_day, span, earned) for column idx of the snapshot
    series over the trailing window, against the live point; None when
    the observed span is under 5 minutes or the column is unknown."""
    pts = [(r[0], r[idx]) for r in rows if r[idx] is not None]
    if current is None:
        return None
    pts.append((now, current))
    if len(pts) < 2:
        return None
    target = now - window
    then = pts[0]
    for ts, v in pts:
        if ts <= target:
            then = (ts, v)
        else:
            break
    span = now - then[0]
    if span < 300:
        return None
    earned = (current - then[1]) / 1e6
    if earned < 0:
        return None
    return earned / span * 86400, span, earned


_SERVING_CACHE = {"key": None, "frac": None, "span": 0.0}


def _serving_fraction(since_ts):
    """Fraction of the time since `since_ts` the provider was running,
    reconstructed from the decisions log (darkbloom_started/stopped),
    which persists across bridge restarts. Returns (fraction|None,
    covered_span_seconds)."""
    try:
        st = os.stat(_DECISIONS_FILE)
        key = (st.st_mtime, st.st_size, since_ts)
    except Exception:
        return None, 0.0
    if _SERVING_CACHE["key"] == key:
        return _SERVING_CACHE["frac"], _SERVING_CACHE["span"]
    events = []
    try:
        with open(_DECISIONS_FILE) as f:
            for line in f:
                try:
                    ev = json.loads(line)
                except Exception:
                    continue
                if ev.get("type") in ("darkbloom_started", "darkbloom_stopped"):
                    events.append((float(ev["ts"]),
                                   ev["type"] == "darkbloom_started"))
    except Exception:
        return None, 0.0
    now = time.time()
    if not events:
        # No transitions in the log: the watcher has seen one steady
        # state — use the provider's current state for the whole span.
        cur = globals().get("_last_db_running")
        frac = (1.0 if cur else 0.0) if cur is not None else None
        _SERVING_CACHE.update(key=key, frac=frac, span=now - since_ts)
        return frac, now - since_ts
    # State before the first known event: the opposite of that event.
    running = not events[0][1]
    serving, cursor = 0.0, since_ts
    for ts, started in events:
        if ts <= since_ts:
            running = started
            continue
        if running:
            serving += ts - cursor
        cursor, running = ts, started
    if running:
        serving += now - cursor
    span = now - since_ts
    frac = (serving / span) if span > 0 and running is not None else None
    _SERVING_CACHE.update(key=key, frac=frac, span=span)
    return frac, span


def _compute_estimates(now, total_micro, split_cums, api_fallback):
    """Estimated returns from the snapshot ledger.

    Headline: realized $/day blended across trailing windows (longest
    weighted most) — exact ledger differences, downtime included.
    Also: base-vs-jobs split rates over the longest observed span, the
    serving-time fraction and the while-serving rate, a confidence
    grade, and the downsampled cumulative series for graphing."""
    rows = _snaps_load()
    if total_micro is None or not rows:
        return api_fallback
    windows = (("last_hour", 3600), ("last_6h", 6 * 3600),
               ("last_24h", 86400), ("last_7d", 7 * 86400))
    comps, spans = {}, {}
    for name, w in windows:
        r = _window_series(rows, now, total_micro, w)
        if r:
            comps[name] = round(r[0], 4)
            spans[name] = round(r[1])
    if not comps:
        return api_fallback
    weights = {"last_hour": 0.15, "last_6h": 0.25, "last_24h": 0.30,
               "last_7d": 0.30}
    tw = sum(weights[k] for k in comps)
    per_day = sum(comps[k] * weights[k] for k in comps) / tw
    tracked_since = rows[0][0]
    tracked_span = now - tracked_since
    # Split rates over the longest observed span (most stable window).
    split = None
    if split_cums is not None:
        longest = max(spans.values())
        b = _window_series(rows, now, split_cums[0], longest + 1, idx=2)
        p = _window_series(rows, now, split_cums[1], longest + 1, idx=3)
        if b or p:
            split = {"base_per_day_usd": round(b[0], 4) if b else 0.0,
                     "jobs_per_day_usd": round(p[0], 4) if p else 0.0}
    frac, _ = _serving_fraction(tracked_since)
    while_serving = (round(per_day / frac, 4)
                     if frac and frac > 0.05 else None)
    confidence = ("solid" if tracked_span >= 7 * 86400 else
                  "building" if tracked_span >= 86400 else "early")
    dominant = max(comps, key=lambda k: weights[k])
    dspan = spans[dominant]
    if dspan < 3600:
        span_txt = f"{max(1, round(dspan / 60))}m"
    elif dspan < 86400:
        span_txt = f"{dspan / 3600:.1f}".rstrip("0").rstrip(".") + "h"
    else:
        span_txt = f"{dspan / 86400:.1f}".rstrip("0").rstrip(".") + "d"
    stride = max(1, len(rows) // 240)
    series = [[round(r[0]), r[1]] for r in rows[::stride]]
    if rows and (not series or series[-1][1] != rows[-1][1]):
        series.append([round(rows[-1][0]), rows[-1][1]])
    return {
        "per_day_usd": round(per_day, 4),
        "per_week_usd": round(per_day * 7, 4),
        "per_month_usd": round(per_day * 30, 4),
        "method": "bridge-ledger",
        "tracked_since": tracked_since,
        "confidence": confidence,
        "components": comps,
        "split": split,
        "serving": ({"online_fraction": round(frac, 3),
                     "per_day_while_serving_usd": while_serving}
                    if frac is not None else None),
        "series": series,
        "basis": {"label": f"realized earnings over the last {span_txt}",
                  "spans": spans},
    }


def _darkbloom_earnings():
    def fetch():
        try:
            with open(DARKBLOOM_TOKEN) as f:
                token = f.read().strip()
            if not token:
                return None
            data = http_get_json(
                "https://api.darkbloom.dev/v1/provider/account-earnings",
                headers={"Authorization": f"Bearer {token}"}, timeout=5)
            entries = data.get("earnings") or []
            credit_times = [t for t in (_parse_ts(e.get("created_at"))
                                         for e in entries) if t]
            last_credit_at = max(credit_times) if credit_times else None
            per_model = {}
            base_total = 0
            for e in entries:
                model = e.get("model") or "unknown"
                amt = e.get("amount_micro_usd") or 0
                if model == "base_reward":
                    # Broke out separately below; don't also list it as a
                    # pseudo-model in the per-model breakdown.
                    base_total += amt
                    continue
                slot = per_model.setdefault(model, {"jobs": 0, "micro_usd": 0})
                slot["jobs"] += 1
                slot["micro_usd"] += amt
            def slim(e):
                return {
                    "model": e.get("model"),
                    "amount_usd": round((e.get("amount_micro_usd") or 0) / 1e6, 6),
                    "amount_micro_usd": e.get("amount_micro_usd") or 0,
                    "prompt_tokens": e.get("prompt_tokens"),
                    "completion_tokens": e.get("completion_tokens"),
                    "created_at": e.get("created_at"),
                }
            recent = [slim(e) for e in entries[:8]]
            # Full window (up to the API's history_limit) for the web
            # dashboard's analytics; the app keeps using `recent`.
            history = [slim(e) for e in entries]
            # Cold-start fallback estimate from the API's recent-entries
            # window (used only until the snapshot ledger has span).
            api_estimates = None
            if len(credit_times) >= 2:
                span = max(credit_times) - min(credit_times)
                window_usd = sum((e.get("amount_micro_usd") or 0)
                                   for e in entries) / 1e6
                if span >= 60 and window_usd > 0:
                    per_day = window_usd / span * 86400
                    api_estimates = {
                        "per_day_usd": round(per_day, 4),
                        "per_week_usd": round(per_day * 7, 4),
                        "per_month_usd": round(per_day * 30, 4),
                        "method": "api-window",
                        "tracked_since": None,
                        "confidence": "early",
                        "components": {},
                        "split": None,
                        "serving": None,
                        "series": [],
                        "basis": {
                            "label": "recent API window (ledger still warming up)",
                            "window_seconds": round(span),
                            "window_credits": len(entries),
                            "window_usd": round(window_usd, 6),
                        },
                    }
            # Ground truth: snapshot the lifetime total and compute
            # realized trailing-window rates from the bridge's ledger.
            # Split attribution: entries this bridge hasn't seen before
            # (deduped by key) split the fetch's total delta into base
            # vs jobs; any residual (credits the entries window skipped)
            # is apportioned by the observed mix.
            total_micro = _usd_str_to_micro(data.get("total_usd"))
            new_base = new_paid = 0
            for e in entries:
                key = (e.get("created_at"), e.get("amount_micro_usd"),
                       e.get("model"))
                if key in _split_seen:
                    continue
                _split_seen[key] = True
                if e.get("model") == "base_reward":
                    new_base += e.get("amount_micro_usd") or 0
                else:
                    new_paid += e.get("amount_micro_usd") or 0
            while len(_split_seen) > 400:
                _split_seen.pop(next(iter(_split_seen)))
            rows_now = _snaps_load()
            if _split_state["base"] is None:
                last = rows_now[-1] if rows_now else None
                _split_state["base"] = last[2] if last and last[2] is not None else 0
                _split_state["paid"] = last[3] if last and last[3] is not None else 0
            base_cum = _split_state["base"]
            paid_cum = _split_state["paid"]
            prev_total = rows_now[-1][1] if rows_now else None
            if prev_total is not None and total_micro is not None:
                delta = total_micro - prev_total
                obs = new_base + new_paid
                if delta <= 0:
                    add_b = add_p = 0
                elif obs == 0:
                    hist = base_cum + paid_cum
                    add_b = round(delta * (base_cum / hist)) if hist else 0
                    add_p = delta - add_b
                elif obs >= delta:
                    add_b = round(new_base * delta / obs)
                    add_p = delta - add_b
                else:
                    res = delta - obs
                    rb = round(res * (new_base / obs))
                    add_b, add_p = new_base + rb, new_paid + (res - rb)
                base_cum += add_b
                paid_cum += add_p
            else:
                base_cum += new_base
                paid_cum += new_paid
            _split_state["base"], _split_state["paid"] = base_cum, paid_cum
            _snap_record(total_micro, base_cum, paid_cum)
            estimates = _compute_estimates(time.time(), total_micro,
                                           (base_cum, paid_cum),
                                           api_estimates)
            return {
                "balance_usd": data.get("available_balance_usd"),
                "withdrawable_usd": data.get("withdrawable_balance_usd"),
                "total_usd": data.get("total_usd"),
                "jobs_in_history": data.get("count"),
                "history_limit": data.get("history_limit"),
                "last_credit_at": last_credit_at,
                "base_rewards_micro_usd_recent": base_total,
                "per_model_recent": per_model,
                "recent": recent,
                "history": history,
                "estimates": estimates,
            }
        except Exception:
            return None
    # 20s TTL + stale-while-revalidate: clients poll every few seconds
    # and should see fresh estimates without ever waiting on this API.
    return cached("db_earnings", 20, fetch, swr=True)


_last_good_darkbloom = None
_last_db_running = None


def _darkbloom_default(installed):
    """Full-schema placeholder used when the daemon state can't be read and
    no last-good snapshot exists — consumers always see the same key set."""
    return {
        "installed": installed, "running": False, "version": None, "pid": None,
        "uptime_seconds": None, "provider_state_written_at": None,
        "lifecycle": None, "current_model": None,
        "trust": {"level": None, "status": None, "reason": None, "path": None,
                  "app_attest_available": None, "session_expires_at": None},
        "earning": False, "last_credit_at": None,
        "verification_pending": True,
        "stats": {"requests_served": None, "tokens_generated": None,
                  "usage_gaps": None},
        "capacity": {"total_memory_gb": None, "gpu_active_gb": None,
                     "gpu_cache_gb": None, "free_for_load_gb": None,
                     "load_usable_gb": None, "load_headroom_gb": None},
        "warm_models": [], "advertised_models": [], "slots": [],
        "resident_models": [],
        "autopilot": {"phase": None, "enabled": None, "active": None,
                      "observe_only": None, "paused": None,
                      "max_model_slots": None},
        "app_attest": {"attested": None, "key_age_seconds": None,
                       "last_success_age_seconds": None,
                       "generations_last_24h": None,
                       "consecutive_failures": None},
        "earnings": None,
    }


def darkbloom_stats():
    global _last_good_darkbloom
    d = None
    for attempt in range(2):
        try:
            with open(DARKBLOOM_STATE) as f:
                d = json.load(f)
            break
        except Exception:
            # The daemon may be mid-rewrite of its state file; one short
            # retry, then fall back to the last good snapshot rather than
            # reporting a healthy provider as stopped.
            if attempt == 0:
                time.sleep(0.1)
    if d is None:
        if _last_good_darkbloom is not None:
            stale = dict(_last_good_darkbloom)
            stale["stale"] = True
            return stale
        out = _darkbloom_default(os.path.exists(os.path.join(HOME, ".darkbloom")))
        out["error"] = "daemon state unreadable"
        return out

    trust = d.get("trust") or {}
    auth = trust.get("authorization") or {}
    stats = d.get("stats") or {}
    cap = d.get("capacity") or {}
    attest = d.get("app_attest") or {}
    key_hist = attest.get("key_history") or {}
    autopilot = d.get("autopilot_state") or {}
    started = d.get("started_at")
    pid = d.get("pid")

    running = False
    if pid:
        try:
            os.kill(int(pid), 0)
            # kill(pid, 0) only proves the PID is taken — macOS recycles PIDs,
            # so confirm the process actually is the Darkbloom daemon.
            comm = run(["ps", "-p", str(int(pid)), "-o", "comm="],
                       timeout=3).strip().lower()
            running = "darkbloom" in comm
        except Exception:
            running = False

    residents = []
    for r in autopilot.get("resident_models") or []:
        residents.append({
            "model": r.get("model_id"),
            "resident_gb": round(r.get("resident_gb") or 0, 2),
            "weights_gb": round(r.get("weights_gb") or 0, 2),
            "idle_seconds": r.get("idle_seconds"),
            "resident_seconds": r.get("resident_seconds"),
        })

    global _last_db_running
    _last_db_running = running
    earnings = _darkbloom_earnings()
    last_credit_at = (earnings or {}).get("last_credit_at")
    if running:
        _note_session_activity(stats.get("requests_served"),
                                stats.get("tokens_generated"))

    result = {
        "installed": True,
        "running": running,
        "version": d.get("version"),
        "pid": pid,
        "uptime_seconds": int(time.time() - started) if isinstance(started, (int, float)) else None,
        "provider_state_written_at": d.get("written_at"),
        "lifecycle": (d.get("lifecycle") or {}).get("outcome"),
        "current_model": d.get("current_model"),
        "trust": {
            "level": trust.get("trust_level"),
            "status": trust.get("status"),
            "reason": trust.get("reason"),
            "path": auth.get("path"),
            "app_attest_available": auth.get("app_attest_available"),
            "session_expires_at": auth.get("expires_at"),
        },
        # "Earning" = a ledger credit or session activity inside the
        # recency window — NOT a lifetime job count (that made the flag
        # permanently true and it lied through a dead session). The app
        # shows SERVING/READY when this is false but the daemon runs.
        "earning": _is_earning(running, last_credit_at,
                               _SESSION_ACTIVITY["at"] or None, time.time()),
        "last_credit_at": last_credit_at,
        "verification_pending": trust.get("trust_level") != "hardware",
        "stats": {
            "requests_served": stats.get("requests_served"),
            "tokens_generated": stats.get("tokens_generated"),
            "usage_gaps": stats.get("usage_gaps"),
        },
        "capacity": {
            "total_memory_gb": cap.get("total_memory_gb"),
            "gpu_active_gb": cap.get("gpu_memory_active_gb"),
            "gpu_cache_gb": cap.get("gpu_memory_cache_gb"),
            "free_for_load_gb": cap.get("free_for_load_gb"),
            "load_usable_gb": cap.get("load_usable_gb"),
            "load_headroom_gb": cap.get("load_headroom_gb"),
        },
        "warm_models": d.get("warm_models") or [],
        "advertised_models": d.get("advertised_models") or [],
        "slots": [
            {"model": s.get("model"), "mtp_enabled": s.get("mtp_enabled"),
             "mtp_active": s.get("mtp_active"), "kv_backend": s.get("kv_backend")}
            for s in (d.get("slots") or [])
        ],
        "resident_models": residents,
        "autopilot": {
            "phase": d.get("autopilot_phase"),
            "enabled": autopilot.get("enabled"),
            "active": autopilot.get("active"),
            "observe_only": autopilot.get("observe_only"),
            "paused": autopilot.get("paused"),
            "max_model_slots": autopilot.get("max_model_slots"),
        },
        "app_attest": {
            "attested": (attest.get("key") or {}).get("attested"),
            "key_age_seconds": key_hist.get("key_age_seconds"),
            "last_success_age_seconds": key_hist.get("last_success_age_seconds"),
            "generations_last_24h": key_hist.get("generations_last_24h"),
            "consecutive_failures": key_hist.get("consecutive_assertion_failures"),
        },
        "earnings": earnings,
    }
    _last_good_darkbloom = result
    return result


# --------------------------------------------------------------------------
# lm studio
# --------------------------------------------------------------------------

def lmstudio_stats():
    def fetch():
        headers = {}
        try:
            with open(LMSTUDIO_KEY) as f:
                key = f.read().strip()
            if key:
                headers["Authorization"] = f"Bearer {key}"
        except Exception:
            pass
        try:
            data = http_get_json(f"{LMSTUDIO_BASE}/api/v0/models", headers=headers, timeout=4)
            models = []
            for m in data.get("data", []):
                models.append({
                    "id": m.get("id"), "type": m.get("type"),
                    "state": m.get("state"), "max_context_length": m.get("max_context_length"),
                    "loaded_context_length": m.get("loaded_context_length"),
                    "quantization": (m.get("quantization") or {}).get("name")
                    if isinstance(m.get("quantization"), dict) else m.get("quantization"),
                    "publisher": m.get("publisher"),
                    "size_bytes": m.get("size_bytes"),
                })
            return {"reachable": True, "models": models,
                    "loaded_count": sum(1 for m in models if m["state"] == "loaded")}
        except Exception as e:
            return {"reachable": False, "error": str(e)[:120], "models": [], "loaded_count": 0}
    return cached("lmstudio", 5, fetch, swr=True)


# --------------------------------------------------------------------------
# decisions — state-change event log
#
# A watcher thread samples the same (cached) stats the REST endpoints serve
# and records timestamped transitions: Darkbloom start/stop, earning
# start/stop, trust-level changes, LM Studio up/down, loaded-model changes,
# serving-model changes. Anything (app, agent, cron) can then ask "what
# changed, and when" via GET /api/decisions or the MCP `decisions` tool
# instead of diffing snapshots. Persisted as JSONL next to the bridge so the
# history survives restarts; capped at the most recent _MAX_DECISIONS events.
# --------------------------------------------------------------------------

_DECISIONS_FILE = os.path.join(HOME, "dashboard-bridge", "decisions.jsonl")
_MAX_DECISIONS = 1000
_decisions = []
_decisions_lock = threading.Lock()


def _record_decision(kind, detail=None):
    ev = {"ts": time.time(), "type": kind, "detail": detail or {}}
    with _decisions_lock:
        _decisions.append(ev)
        if len(_decisions) > _MAX_DECISIONS:
            del _decisions[:len(_decisions) - _MAX_DECISIONS]
    try:
        with open(_DECISIONS_FILE, "a") as f:
            f.write(json.dumps(ev) + "\n")
        if os.path.getsize(_DECISIONS_FILE) > 2_000_000:
            with _decisions_lock:
                keep = list(_decisions[-500:])
            with open(_DECISIONS_FILE, "w") as f:
                for e in keep:
                    f.write(json.dumps(e) + "\n")
    except Exception:
        pass


def _load_decisions():
    try:
        with open(_DECISIONS_FILE) as f:
            lines = f.readlines()[-_MAX_DECISIONS:]
        for ln in lines:
            try:
                _decisions.append(json.loads(ln))
            except Exception:
                pass
    except Exception:
        pass


def decisions_list(limit=50):
    limit = max(1, min(int(limit or 50), _MAX_DECISIONS))
    with _decisions_lock:
        return list(reversed(_decisions[-limit:]))


def _watcher():
    _record_decision("bridge_started", {"version": BRIDGE_VERSION})
    prev = None
    while True:
        try:
            db = darkbloom_stats()
            lm = lmstudio_stats()
            sig = {
                "db_running": bool(db.get("running")),
                "db_earning": bool(db.get("earning")),
                "trust": (db.get("trust") or {}).get("level"),
                "lm_reachable": bool(lm.get("reachable")),
                "lm_loaded": tuple(sorted(
                    m.get("id") for m in (lm.get("models") or [])
                    if m.get("state") == "loaded")),
                "db_model": db.get("current_model"),
            }
            if prev is not None:
                if sig["db_running"] != prev["db_running"]:
                    _record_decision(
                        "darkbloom_started" if sig["db_running"] else "darkbloom_stopped")
                if sig["db_running"] and sig["db_earning"] != prev["db_earning"]:
                    _record_decision(
                        "darkbloom_earning_started" if sig["db_earning"]
                        else "darkbloom_earning_stopped")
                if sig["trust"] != prev["trust"]:
                    _record_decision("darkbloom_trust_changed",
                                     {"from": prev["trust"], "to": sig["trust"]})
                if sig["lm_reachable"] != prev["lm_reachable"]:
                    _record_decision(
                        "lmstudio_up" if sig["lm_reachable"] else "lmstudio_down")
                if sig["lm_loaded"] != prev["lm_loaded"]:
                    _record_decision("lmstudio_models_changed",
                                     {"loaded": list(sig["lm_loaded"])})
                if sig["db_model"] != prev["db_model"] and sig["db_model"]:
                    _record_decision("darkbloom_model_changed",
                                     {"model": sig["db_model"]})
            prev = sig
        except Exception:
            pass
        time.sleep(15)


# --------------------------------------------------------------------------
# Windows PC (Batman-3) — system vitals + SRBMiner state, probed from the
# Mac over SSH (~/bin/batman-ssh) with an EncodedCommand PowerShell script
# (no quoting games through three shells). Cached SWR; last-good retained
# when the PC is asleep or off.

_WIN_PS = r"""
$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'SilentlyContinue'
$os = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
Write-Output ("OS|" + $os.Caption + "|" + $os.Version)
Write-Output ("BOOT|" + $os.LastBootUpTime.ToString("o"))
Write-Output ("MEM|" + $os.TotalVisibleMemorySize + "|" + $os.FreePhysicalMemory)
Write-Output ("CPU|" + $cpu.Name + "|" + $cpu.LoadPercentage + "|" + $cpu.NumberOfCores + "|" + $cpu.NumberOfLogicalProcessors)
Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object { Write-Output ("DISK|" + $_.DeviceID + "|" + $_.Size + "|" + $_.FreeSpace) }
$ad = Get-NetAdapterStatistics | Where-Object { $_.ReceivedBytes -gt 0 }
$rx = ($ad | Measure-Object ReceivedBytes -Sum).Sum
$tx = ($ad | Measure-Object SentBytes -Sum).Sum
Write-Output ("NET|" + $rx + "|" + $tx)
$miner = (Get-Process SRBMiner-MULTI -ErrorAction SilentlyContinue).Count
Write-Output ("MINER|" + $miner)
if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) { Write-Output ("NV|" + (& nvidia-smi --query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw --format=csv,noheader,nounits)) }
Get-Content "C:\Users\Duckets\Desktop\srbminer\miner.log" -Tail 40 | Select-String -Pattern "TH/s|GH/s|MH/s" | Select-Object -Last 3 | ForEach-Object { Write-Output ("LOG|" + $_.Line) }
"""

_win_prev_net = {"rx": None, "tx": None, "ts": 0.0}
_last_good_windows = None


def _fnum(s):
    try:
        return float(str(s).strip())
    except Exception:
        return None


def _inum(s):
    v = _fnum(s)
    return int(v) if v is not None else None


def _parse_windows(out):
    d = {"reachable": True, "fetched_at": time.time(), "disks": [],
         "miner": {"running": False}}
    logs = []
    for line in (out or "").splitlines():
        parts = line.strip().split("|")
        if len(parts) < 2:
            continue
        tag = parts[0]
        if tag == "OS" and len(parts) >= 3:
            d["os_name"] = parts[1].strip()
            d["os_version"] = parts[2].strip()
        elif tag == "BOOT":
            try:
                iso = re.sub(r"\.(\d{7,})",
                             lambda m: "." + m.group(1)[:6], parts[1])
                boot = datetime.datetime.fromisoformat(iso)
                d["uptime_seconds"] = int(time.time() - boot.timestamp())
            except Exception:
                pass
        elif tag == "MEM" and len(parts) >= 3:
            total = (_inum(parts[1]) or 0) * 1024
            free = (_inum(parts[2]) or 0) * 1024
            d["memory"] = {
                "total_bytes": total, "free_bytes": free,
                "used_bytes": total - free,
                "used_pct": round((total - free) / total * 100, 1) if total else None}
        elif tag == "CPU" and len(parts) >= 5:
            d["cpu"] = {"name": parts[1].strip(),
                        "load_pct": _inum(parts[2]),
                        "cores": _inum(parts[3]),
                        "logical": _inum(parts[4])}
        elif tag == "DISK" and len(parts) >= 4:
            size, free = _inum(parts[2]) or 0, _inum(parts[3]) or 0
            d["disks"].append({
                "label": parts[1], "total_bytes": size, "free_bytes": free,
                "used_bytes": size - free,
                "used_pct": round((size - free) / size * 100, 1) if size else None})
        elif tag == "NET" and len(parts) >= 3:
            rx, tx = _inum(parts[1]), _inum(parts[2])
            now = time.time()
            rate_in = rate_out = None
            prev = _win_prev_net
            if prev["rx"] is not None and rx is not None and now > prev["ts"]:
                dt = now - prev["ts"]
                rate_in = max(0, (rx - prev["rx"]) / dt)
                rate_out = max(0, (tx - prev["tx"]) / dt)
            _win_prev_net.update(rx=rx, tx=tx, ts=now)
            d["network"] = {"bytes_in": rx, "bytes_out": tx,
                            "rate_in_bps": rate_in, "rate_out_bps": rate_out}
        elif tag == "MINER":
            d["miner"]["running"] = (_inum(parts[1]) or 0) > 0
            d["miner"]["process_count"] = _inum(parts[1]) or 0
        elif tag == "NV":
            f = [x.strip() for x in parts[1].split(",")]
            if len(f) >= 6:
                d["gpu"] = {"name": f[0], "util_pct": _fnum(f[1]),
                            "mem_used_mb": _fnum(f[2]),
                            "mem_total_mb": _fnum(f[3]),
                            "temp_c": _fnum(f[4]), "power_w": _fnum(f[5])}
        elif tag == "LOG":
            logs.append("|".join(parts[1:]))
    m = d["miner"]
    for line in logs:
        t = re.search(r"Total:\s*([\d.]+)\s*TH/s", line)
        if t:
            m["hashrate_ths"] = float(t.group(1))
            pw = re.search(r"P:([\d.]+)W", line)
            if pw:
                m["power_w"] = float(pw.group(1))
            sh = re.search(r"A:(\d+)\s+R:(\d+)", line)
            if sh:
                m["accepted"] = int(sh.group(1))
                m["rejected"] = int(sh.group(2))
        a = re.search(r"Average hashrate: 1m ([\d.]+) TH/s \| 15m ([\d.]+)"
                      r" TH/s \| 1h ([\d.]+) TH/s \| 6h ([\d.]+) TH/s", line)
        if a:
            m["avg_1m_ths"], m["avg_15m_ths"] = float(a.group(1)), float(a.group(2))
            m["avg_1h_ths"], m["avg_6h_ths"] = float(a.group(3)), float(a.group(4))
    return d


def _windows_fetch():
    enc = base64.b64encode(_WIN_PS.encode("utf-16-le")).decode()
    ssh = os.path.expanduser("~/bin/batman-ssh")
    cmd = [ssh if os.path.exists(ssh) else "batman-ssh",
           f"powershell -NoProfile -EncodedCommand {enc}"]
    out = run(cmd, timeout=25)
    if "OS|" not in (out or ""):
        return None
    return _parse_windows(out)


def windows_stats():
    global _last_good_windows
    got = cached("windows", 30, _windows_fetch, swr=True)
    if got is not None:
        _last_good_windows = got
        return got
    if _last_good_windows is not None:
        stale = dict(_last_good_windows)
        stale["stale"] = True
        return stale
    return {"reachable": False,
            "error": "Batman-3 unreachable (asleep, off, or SSH blocked)"}


# --------------------------------------------------------------------------
# Crypto mining — Kryptex fleet. Fleet figures live in a data file next
# to the bridge (mining.json, manual snapshots from the Kryptex
# dashboard; NOT in the git repo — it's his financial data). Batman-3's
# row is backed by the live Windows probe above.

MINING_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "mining.json")


def mining_stats():
    def build():
        fleet = None
        try:
            with open(MINING_FILE) as f:
                fleet = json.load(f)
        except Exception:
            fleet = None
        batman_live = None
        win = windows_stats()
        if win and win.get("reachable") and win.get("miner"):
            m = win["miner"]
            batman_live = {
                "running": bool(m.get("running")),
                "hashrate_ths": m.get("hashrate_ths"),
                "avg_1h_ths": m.get("avg_1h_ths"),
                "power_w": m.get("power_w") or (win.get("gpu") or {}).get("power_w"),
                "gpu_temp_c": (win.get("gpu") or {}).get("temp_c"),
                "checked_at": win.get("fetched_at"),
                "stale": bool(win.get("stale")),
            }
        total_day = total_month = None
        coins = (fleet or {}).get("coins") or []
        if coins:
            total_day = round(sum(r.get("per_day_usd") or 0
                                  for c in coins for r in c.get("rigs") or []), 2)
            total_month = round(sum(r.get("per_month_usd") or 0
                                    for c in coins for r in c.get("rigs") or []), 2)
        return {
            "fleet": fleet,
            "batman_live": batman_live,
            "totals": ({"per_day_usd": total_day,
                        "per_week_usd": round(total_day * 7, 2),
                        "per_month_usd": total_month,
                        "per_year_usd": round(total_day * 365, 2)}
                       if total_day is not None else None),
        }
    return cached("mining", 30, build, swr=True)


# --------------------------------------------------------------------------
# MCP — minimal read-only MCP server (streamable HTTP, stateless)
#
# POST /mcp with JSON-RPC 2.0: initialize / ping / tools/list / tools/call.
# Tools mirror the REST endpoints so any MCP client (Hermes, DuckBot, …)
# can query live Mac vitals, Darkbloom provider state, LM Studio state,
# and the decisions log as native tools. Read-only by design.
# --------------------------------------------------------------------------

MCP_PROTOCOL_VERSION = "2025-03-26"

_MCP_TOOLS = [
    {"name": "health",
     "description": "Dashboard bridge liveness: ok flag, version, uptime, machine name.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "overview",
     "description": "Everything at once: Mac system vitals, Darkbloom provider "
                    "state + earnings, and LM Studio state.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "system",
     "description": "Mac vitals: CPU, memory, swap, disks, network rates, "
                    "thermal state, top processes.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "darkbloom",
     "description": "Darkbloom provider: running/earning flags, trust level, "
                    "warm models, capacity, App Attest status, and the "
                    "earnings ledger summary (balance, paid jobs, per-model, "
                    "recent jobs). The ledger is the ground truth for earning.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "lmstudio",
     "description": "LM Studio: API reachability and model library with "
                    "per-model state (loaded / not-loaded).",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "windows",
     "description": "Windows PC (Batman-3): OS, CPU load, memory, GPU "
                    "(utilization, temperature, power), disks, network, "
                    "uptime, and SRBMiner state with live hashrate.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "mining",
     "description": "Kryptex mining fleet: per-rig earnings snapshot "
                    "(with as-of time), coin subtotals, projections, and "
                    "Batman-3's live miner state. Separate from Darkbloom.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "decisions",
     "description": "Recent state-change events (newest first): Darkbloom "
                    "start/stop, earning start/stop, trust changes, LM Studio "
                    "up/down, model changes, bridge starts.",
     "inputSchema": {"type": "object", "properties": {
         "limit": {"type": "integer", "default": 50, "minimum": 1, "maximum": 200}}}},
]


def _health_obj():
    return {"ok": True, "service": "dashboard-bridge",
            "time": time.time(), "version": BRIDGE_VERSION,
            "uptime_seconds": int(time.time() - _STARTED_AT),
            "machine": os.environ.get("DASHBOARD_MACHINE_NAME") or "Mac Studio"}


_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="bridge")


def _overview_obj():
    # Build the three sections concurrently: sequentially they can stack to
    # ~11s (top's 2s + a cold earnings call + a cold LM Studio call), which
    # exceeds the phone's read timeout and flares false "offline" states.
    f_sys = _pool.submit(system_stats)
    f_db = _pool.submit(darkbloom_stats)
    f_lm = _pool.submit(lmstudio_stats)
    return {"generated_at": time.time(),
            "bridge": {"version": BRIDGE_VERSION,
                       "uptime_seconds": int(time.time() - _STARTED_AT)},
            "system": f_sys.result(),
            "darkbloom": f_db.result(),
            "lmstudio": f_lm.result()}


def _mcp_call_tool(name, args):
    if name == "health":
        return _health_obj()
    if name == "overview":
        return _overview_obj()
    if name == "system":
        return {"generated_at": time.time(), "system": system_stats()}
    if name == "darkbloom":
        return {"generated_at": time.time(), "darkbloom": darkbloom_stats()}
    if name == "lmstudio":
        return {"generated_at": time.time(), "lmstudio": lmstudio_stats()}
    if name == "windows":
        return {"generated_at": time.time(), "windows": windows_stats()}
    if name == "mining":
        return {"generated_at": time.time(), "mining": mining_stats()}
    if name == "decisions":
        return {"decisions": decisions_list((args or {}).get("limit", 50))}
    raise ValueError(f"unknown tool: {name}")


def _mcp_handle(payload):
    """Returns (http_status, response_obj_or_None_for_notifications)."""
    method = payload.get("method")
    mid = payload.get("id")
    params = payload.get("params") or {}
    if method == "initialize":
        return 200, {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": params.get("protocolVersion") or MCP_PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "dashboard-bridge", "version": BRIDGE_VERSION}}}
    if method == "ping":
        return 200, {"jsonrpc": "2.0", "id": mid, "result": {}}
    if method == "tools/list":
        return 200, {"jsonrpc": "2.0", "id": mid, "result": {"tools": _MCP_TOOLS}}
    if method == "tools/call":
        try:
            data = _mcp_call_tool(params.get("name"), params.get("arguments") or {})
            return 200, {"jsonrpc": "2.0", "id": mid, "result": {
                "content": [{"type": "text", "text": json.dumps(data)}],
                "structuredContent": data, "isError": False}}
        except Exception as e:
            return 200, {"jsonrpc": "2.0", "id": mid, "result": {
                "content": [{"type": "text", "text": str(e)[:200]}], "isError": True}}
    if mid is None:
        return 202, None  # notification (e.g. notifications/initialized)
    return 200, {"jsonrpc": "2.0", "id": mid,
                 "error": {"code": -32601, "message": f"method not found: {method}"}}


# --------------------------------------------------------------------------
# http server
# --------------------------------------------------------------------------

_web_cache = {"mtime": 0.0, "body": None}


def _web_page():
    """The detailed web dashboard, served from web_dashboard.html next to
    this script (installed alongside it). Re-read when the file changes;
    a placeholder page if the file is missing."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "web_dashboard.html")
    try:
        mtime = os.path.getmtime(path)
        if _web_cache["body"] is None or mtime != _web_cache["mtime"]:
            with open(path, "rb") as f:
                _web_cache["body"] = f.read()
            _web_cache["mtime"] = mtime
        return _web_cache["body"]
    except Exception:
        return (b"<!DOCTYPE html><title>Dashboard</title>"
                b"<p>web_dashboard.html is missing next to the bridge. "
                b"The JSON API is at /api/overview.</p>")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "dashboard-bridge"
    sys_version = ""

    def log_message(self, fmt, *args):  # quiet
        pass

    def _send(self, obj, code=200):
        body = json.dumps(obj).encode("utf-8")
        use_gzip = "gzip" in (self.headers.get("Accept-Encoding") or "") and len(body) > 1024
        if use_gzip:
            body = gzip.compress(body, compresslevel=6)
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        if use_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type, Mcp-Session-Id, MCP-Protocol-Version")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _send_raw(self, body, code=200, content_type="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        try:
            path = self.path.split("?")[0].rstrip("/") or "/"
            if path == "/mcp":
                self._send({"error": "MCP uses POST (streamable HTTP)"}, 405)
            elif path in ("/", "/dashboard"):
                self._send_raw(_web_page(), 200, "text/html; charset=utf-8")
            elif path == "/favicon.ico":
                self._send_raw(b"", 204, "image/x-icon")
            elif path == "/api/health":
                self._send(_health_obj())
            elif path == "/api/system":
                self._send({"generated_at": time.time(), "system": system_stats()})
            elif path == "/api/darkbloom":
                self._send({"generated_at": time.time(), "darkbloom": darkbloom_stats()})
            elif path == "/api/lmstudio":
                self._send({"generated_at": time.time(), "lmstudio": lmstudio_stats()})
            elif path == "/api/windows":
                self._send({"generated_at": time.time(), "windows": windows_stats()})
            elif path == "/api/mining":
                self._send({"generated_at": time.time(), "mining": mining_stats()})
            elif path == "/api/decisions":
                qs = urllib.parse.urlparse(self.path).query
                limit = urllib.parse.parse_qs(qs).get("limit", ["50"])[0]
                self._send({"generated_at": time.time(),
                            "decisions": decisions_list(int(limit) if str(limit).isdigit() else 50)})
            elif path == "/api/overview":
                self._send(_overview_obj())
            else:
                self._send({"error": "not found"}, 404)
        except ConnectionError:
            pass
        except Exception as e:
            try:
                self._send({"error": str(e)[:200]}, 500)
            except Exception:
                pass

    def do_POST(self):
        try:
            path = self.path.split("?")[0].rstrip("/") or "/"
            if path != "/mcp":
                self._send({"error": "not found"}, 404)
                return
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length > 0 else b""
            try:
                payload = json.loads(raw.decode("utf-8")) if raw else {}
            except Exception:
                self._send_raw(json.dumps({"jsonrpc": "2.0", "id": None, "error": {
                    "code": -32700, "message": "parse error"}}).encode("utf-8"), 400)
                return
            if isinstance(payload, list):  # JSON-RPC batch
                responses = []
                status = 200
                for item in payload:
                    st, resp = _mcp_handle(item if isinstance(item, dict) else {})
                    if resp is not None:
                        responses.append(resp)
                        status = st
                if responses:
                    self._send(responses, status)
                else:
                    self._send_raw(b"", 202)
                return
            status, resp = _mcp_handle(payload if isinstance(payload, dict) else {})
            if resp is None:
                self._send_raw(b"", 202)
            else:
                self._send(resp, status)
        except ConnectionError:
            pass
        except Exception as e:
            try:
                self._send({"error": str(e)[:200]}, 500)
            except Exception:
                pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8901)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    _load_decisions()
    threading.Thread(target=_watcher, daemon=True, name="decisions-watcher").start()
    # Pre-warm the caches off-thread so the first requests don't pay the
    # ~15s system_profiler / cold-API costs inside a request.
    for fn in (_static_info, system_stats, darkbloom_stats, lmstudio_stats):
        _pool.submit(fn)
    print(f"dashboard-bridge listening on {args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
