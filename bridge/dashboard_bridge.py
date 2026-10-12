#!/usr/bin/env python3
"""
Mac Studio Dashboard Bridge
===========================
Read-only JSON API that exposes live Mac Studio vitals + Darkbloom provider
state + LM Studio state to the Dashboard Android app.

Stdlib only. No pip installs. Run:

    python3 dashboard_bridge.py [--port 8901] [--host 0.0.0.0]

Endpoints:
    GET /api/health    -> {"ok": true, ...}
    GET /api/overview  -> everything (system + darkbloom + lmstudio)
    GET /api/system    -> Mac vitals only
    GET /api/darkbloom -> Darkbloom provider only
    GET /api/lmstudio  -> LM Studio only

Data sources (all local, read-only):
  * system    : sysctl / vm_stat / top / df / netstat / ps / pmset
  * darkbloom : ~/.darkbloom/daemon-state.json + account earnings API
  * lmstudio  : http://127.0.0.1:1234/api/v0/models (API key from
                ~/.config/lmstudio/api_key when present)
"""

import argparse
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
DARKBLOOM_STATE = os.path.join(HOME, ".darkbloom", "daemon-state.json")
DARKBLOOM_TOKEN = os.path.join(HOME, ".darkbloom", "auth_token")
LMSTUDIO_KEY = os.path.join(HOME, ".config", "lmstudio", "api_key")
LMSTUDIO_BASE = "http://127.0.0.1:1234"
BRIDGE_VERSION = "1.1.0"
_STARTED_AT = time.time()

# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------

_cache = {}
_cache_lock = threading.Lock()


def cached(key, ttl, fn):
    now = time.time()
    with _cache_lock:
        hit = _cache.get(key)
        if hit and now - hit[0] < ttl:
            return hit[1]
    val = fn()
    with _cache_lock:
        _cache[key] = (now, val)
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
    return {"user_pct": 0.0, "sys_pct": 0.0, "idle_pct": 100.0, "used_pct": 0.0}


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
        if len(parts) >= 10 and parts[0].startswith("en") and parts[0] not in seen:
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
    return cached("system", 2, build)


# --------------------------------------------------------------------------
# darkbloom
# --------------------------------------------------------------------------

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
            per_model = {}
            base_total = 0
            for e in entries:
                model = e.get("model") or "unknown"
                amt = e.get("amount_micro_usd") or 0
                if model == "base_reward":
                    base_total += amt
                slot = per_model.setdefault(model, {"jobs": 0, "micro_usd": 0})
                slot["jobs"] += 1
                slot["micro_usd"] += amt
            recent = [{
                "model": e.get("model"),
                "amount_usd": round((e.get("amount_micro_usd") or 0) / 1e6, 6),
                "prompt_tokens": e.get("prompt_tokens"),
                "completion_tokens": e.get("completion_tokens"),
                "created_at": e.get("created_at"),
            } for e in entries[:8]]
            return {
                "balance_usd": data.get("available_balance_usd"),
                "withdrawable_usd": data.get("withdrawable_balance_usd"),
                "total_usd": data.get("total_usd"),
                "jobs_in_history": data.get("count"),
                "history_limit": data.get("history_limit"),
                "base_rewards_micro_usd_recent": base_total,
                "per_model_recent": per_model,
                "recent": recent,
            }
        except Exception:
            return None
    return cached("db_earnings", 60, fetch)


def darkbloom_stats():
    try:
        with open(DARKBLOOM_STATE) as f:
            d = json.load(f)
    except Exception:
        return {"installed": os.path.exists(os.path.join(HOME, ".darkbloom")),
                "running": False}

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
            running = True
        except Exception:
            running = False

    residents = []
    for r in autopilot.get("resident_models") or []:
        residents.append({
            "model": r.get("model_id"),
            "resident_gb": round(r.get("resident_gb", 0), 2),
            "weights_gb": round(r.get("weights_gb", 0), 2),
            "idle_seconds": r.get("idle_seconds"),
            "resident_seconds": r.get("resident_seconds"),
        })

    earnings = _darkbloom_earnings()
    paid_jobs = (earnings or {}).get("jobs_in_history") or 0

    return {
        "installed": True,
        "running": running,
        "version": d.get("version"),
        "pid": pid,
        "uptime_seconds": int(time.time() - started) if started else None,
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
        # Actual paid work beats the console's trust banner: the account
        # ledger is the ground truth for "is this machine earning".
        "earning": bool(running and (paid_jobs > 0 or (stats.get("requests_served") or 0) > 0)),
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
    return cached("lmstudio", 5, fetch)


# --------------------------------------------------------------------------
# http server
# --------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

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
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        if use_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        try:
            path = self.path.split("?")[0].rstrip("/") or "/"
            if path in ("/", "/api/health"):
                self._send({"ok": True, "service": "dashboard-bridge",
                            "time": time.time(), "version": BRIDGE_VERSION,
                            "uptime_seconds": int(time.time() - _STARTED_AT),
                            "machine": os.environ.get("DASHBOARD_MACHINE_NAME") or "Mac Studio"})
            elif path == "/api/system":
                self._send({"generated_at": time.time(), "system": system_stats()})
            elif path == "/api/darkbloom":
                self._send({"generated_at": time.time(), "darkbloom": darkbloom_stats()})
            elif path == "/api/lmstudio":
                self._send({"generated_at": time.time(), "lmstudio": lmstudio_stats()})
            elif path == "/api/overview":
                self._send({
                    "generated_at": time.time(),
                    "bridge": {"version": BRIDGE_VERSION,
                               "uptime_seconds": int(time.time() - _STARTED_AT)},
                    "system": system_stats(),
                    "darkbloom": darkbloom_stats(),
                    "lmstudio": lmstudio_stats(),
                })
            else:
                self._send({"error": "not found"}, 404)
        except BrokenPipeError:
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
    print(f"dashboard-bridge listening on {args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
