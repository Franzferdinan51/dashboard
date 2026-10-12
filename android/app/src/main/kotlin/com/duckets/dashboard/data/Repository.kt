package com.duckets.dashboard.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Talks to the Mac-side dashboard bridge (see bridge/dashboard_bridge.py). */
class DashboardRepository(
    private val client: OkHttpClient = SHARED_CLIENT,
) {
    data class Health(val ok: Boolean, val version: String?, val latencyMs: Long)

    companion object {
        // One client for the app's lifetime: per-instance clients leak
        // dispatcher threads and connection pools if the repo is recreated.
        // callTimeout hard-bounds a whole request — readTimeout alone only
        // bounds gaps between bytes, so a trickling bridge could wedge the
        // poll loop forever.
        private val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** Trim, drop trailing slashes, and add http:// when no scheme is present. */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        return url
    }

    private suspend fun getJson(baseUrl: String, path: String): JSONObject {
        val url = normalizeBaseUrl(baseUrl) + path
        val req = Request.Builder().url(url).get().build()
        val call = client.newCall(req)
        // Truly cancellable: cancelling the coroutine cancels the HTTP call,
        // so a stale request from an old bridge URL can never complete late
        // and overwrite newer state.
        val body = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                throw IOException("Bridge returned HTTP ${it.code}")
                            }
                            val text = it.body?.string()
                                ?: throw IOException("Empty response from bridge")
                            if (cont.isActive) cont.resume(text)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }
        return withContext(Dispatchers.IO) { JSONObject(body) }
    }

    suspend fun fetchHealth(baseUrl: String): Health {
        val started = System.currentTimeMillis()
        val json = getJson(baseUrl, "/api/health")
        return Health(
            ok = json.optBoolean("ok", false),
            version = json.optString("version").ifBlank { null },
            latencyMs = System.currentTimeMillis() - started,
        )
    }

    suspend fun fetchOverview(baseUrl: String): Overview = parseOverview(getJson(baseUrl, "/api/overview"))
}

// ---------------------------------------------------------------------------
// org.json parsing (null-tolerant: the bridge omits fields it cannot read)
// ---------------------------------------------------------------------------

private fun JSONObject.obj(key: String): JSONObject? =
    if (has(key) && !isNull(key)) optJSONObject(key) else null

private fun JSONObject.arr(key: String): JSONArray? =
    if (has(key) && !isNull(key)) optJSONArray(key) else null

private fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

private fun JSONObject.dbl(key: String): Double? =
    // optDouble yields NaN for non-numeric values; NaN must read as "absent",
    // never flow into gauges, formatting, or history lists.
    if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

private fun JSONObject.lng(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.bool(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

fun parseOverview(root: JSONObject): Overview {
    // A 200 with an empty or error-shaped body must land in the error path —
    // never render as a plausible fake machine that reads "connected".
    if (!root.has("system") && !root.has("generated_at")) {
        throw JSONException("Unexpected response from bridge")
    }
    val sys = root.obj("system") ?: JSONObject()
    val mem = sys.obj("memory") ?: JSONObject()
    val swap = mem.obj("swap") ?: JSONObject()
    val cpu = sys.obj("cpu") ?: JSONObject()
    val net = sys.obj("network") ?: JSONObject()
    val therm = sys.obj("thermal") ?: JSONObject()

    val loadArr = sys.arr("load_avg")
    val load = buildList {
        if (loadArr != null) for (i in 0 until loadArr.length()) {
            val v = loadArr.optDouble(i)
            if (v.isFinite()) add(v)
        }
    }

    val disks = buildList {
        sys.arr("disks")?.let { a ->
            for (i in 0 until a.length()) {
                val d = a.optJSONObject(i) ?: continue
                add(
                    DiskStats(
                        label = d.str("label") ?: "",
                        path = d.str("path") ?: "",
                        totalBytes = d.lng("total_bytes") ?: 0,
                        usedBytes = d.lng("used_bytes") ?: 0,
                        freeBytes = d.lng("free_bytes") ?: 0,
                        usedPct = d.dbl("used_pct") ?: 0.0,
                    )
                )
            }
        }
    }

    val procs = buildList {
        sys.arr("top_processes")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i) ?: continue
                add(
                    ProcessInfo(
                        pid = p.intOrNull("pid") ?: 0,
                        cpuPct = p.dbl("cpu_pct") ?: 0.0,
                        memPct = p.dbl("mem_pct") ?: 0.0,
                        elapsed = p.str("elapsed") ?: "",
                        name = p.str("name") ?: "",
                    )
                )
            }
        }
    }

    val system = SystemStats(
        computerName = sys.str("computer_name") ?: "Mac Studio",
        chip = sys.str("chip") ?: "",
        model = sys.str("model") ?: "",
        cpuCores = sys.intOrNull("cpu_cores"),
        gpuCores = sys.intOrNull("gpu_cores"),
        macosVersion = sys.str("macos_version") ?: "",
        uptimeSeconds = sys.lng("uptime_seconds"),
        loadAvg = load,
        cpu = CpuStats(
            userPct = cpu.dbl("user_pct") ?: 0.0,
            sysPct = cpu.dbl("sys_pct") ?: 0.0,
            idlePct = cpu.dbl("idle_pct") ?: 100.0,
            usedPct = cpu.dbl("used_pct") ?: 0.0,
        ),
        memory = MemoryStats(
            totalBytes = mem.lng("total_bytes") ?: 0,
            usedBytes = mem.lng("used_bytes") ?: 0,
            freeBytes = mem.lng("free_bytes") ?: 0,
            activeBytes = mem.lng("active_bytes") ?: 0,
            inactiveBytes = mem.lng("inactive_bytes") ?: 0,
            wiredBytes = mem.lng("wired_bytes") ?: 0,
            compressedBytes = mem.lng("compressed_bytes") ?: 0,
            fileBackedBytes = mem.lng("file_backed_bytes") ?: 0,
            anonymousBytes = mem.lng("anonymous_bytes") ?: 0,
            usedPct = mem.dbl("used_pct") ?: 0.0,
            swapTotalMb = swap.dbl("total_mb") ?: 0.0,
            swapUsedMb = swap.dbl("used_mb") ?: 0.0,
        ),
        disks = disks,
        network = NetworkStats(
            bytesIn = net.lng("bytes_in") ?: 0,
            bytesOut = net.lng("bytes_out") ?: 0,
            rateInBps = net.lng("rate_in_bps") ?: 0,
            rateOutBps = net.lng("rate_out_bps") ?: 0,
        ),
        thermal = ThermalStats(
            state = therm.str("state") ?: "nominal",
            cpuSpeedLimitPct = therm.intOrNull("cpu_speed_limit_pct") ?: 100,
        ),
        topProcesses = procs,
    )

    return Overview(
        generatedAt = root.dbl("generated_at") ?: 0.0,
        system = system,
        darkbloom = parseDarkbloom(root.obj("darkbloom") ?: JSONObject()),
        lmstudio = parseLmStudio(root.obj("lmstudio") ?: JSONObject()),
    )
}

private fun parseDarkbloom(db: JSONObject): DarkbloomStats {
    val trust = db.obj("trust") ?: JSONObject()
    val stats = db.obj("stats") ?: JSONObject()
    val cap = db.obj("capacity") ?: JSONObject()
    val autopilot = db.obj("autopilot") ?: JSONObject()
    val attest = db.obj("app_attest") ?: JSONObject()

    // Skip non-string elements rather than rendering their raw JSON text.
    val warm = buildList {
        db.arr("warm_models")?.let { a ->
            for (i in 0 until a.length()) (a.opt(i) as? String)?.let { add(it) }
        }
    }
    val advertised = buildList {
        db.arr("advertised_models")?.let { a ->
            for (i in 0 until a.length()) (a.opt(i) as? String)?.let { add(it) }
        }
    }
    val slots = buildList {
        db.arr("slots")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                add(
                    SlotInfo(
                        model = s.str("model") ?: "",
                        mtpEnabled = s.bool("mtp_enabled") ?: false,
                        mtpActive = s.bool("mtp_active") ?: false,
                        kvBackend = s.str("kv_backend") ?: "",
                    )
                )
            }
        }
    }
    val residents = buildList {
        db.arr("resident_models")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                add(
                    ResidentModel(
                        model = r.str("model") ?: "",
                        residentGb = r.dbl("resident_gb") ?: 0.0,
                        weightsGb = r.dbl("weights_gb") ?: 0.0,
                        idleSeconds = r.lng("idle_seconds"),
                        residentSeconds = r.lng("resident_seconds"),
                    )
                )
            }
        }
    }

    return DarkbloomStats(
        installed = db.bool("installed") ?: false,
        running = db.bool("running") ?: false,
        version = db.str("version"),
        pid = db.intOrNull("pid"),
        uptimeSeconds = db.lng("uptime_seconds"),
        lifecycle = db.str("lifecycle"),
        currentModel = db.str("current_model"),
        trustLevel = trust.str("level"),
        trustStatus = trust.str("status"),
        trustReason = trust.str("reason"),
        trustPath = trust.str("path"),
        earning = db.bool("earning") ?: false,
        verificationPending = db.bool("verification_pending") ?: true,
        requestsServed = stats.lng("requests_served"),
        tokensGenerated = stats.lng("tokens_generated"),
        capacityTotalGb = cap.dbl("total_memory_gb"),
        gpuActiveGb = cap.dbl("gpu_active_gb"),
        gpuCacheGb = cap.dbl("gpu_cache_gb"),
        freeForLoadGb = cap.dbl("free_for_load_gb"),
        warmModels = warm,
        advertisedModels = advertised,
        slots = slots,
        residentModels = residents,
        autopilotPhase = autopilot.str("phase"),
        autopilotEnabled = autopilot.bool("enabled"),
        autopilotObserveOnly = autopilot.bool("observe_only"),
        attestAttested = attest.bool("attested"),
        attestKeyAgeSeconds = attest.lng("key_age_seconds"),
        attestLastSuccessAgeSeconds = attest.lng("last_success_age_seconds"),
        earnings = db.obj("earnings")?.let { parseEarnings(it) },
    )
}

private fun parseEarnings(e: JSONObject): Earnings {
    val perModel = buildList {
        e.obj("per_model_recent")?.let { pm ->
            val keys = pm.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = pm.optJSONObject(k) ?: continue
                add(ModelEarnings(model = k, jobs = v.intOrNull("jobs") ?: 0, microUsd = v.lng("micro_usd") ?: 0))
            }
        }
    }.sortedByDescending { it.microUsd }

    val recent = buildList {
        e.arr("recent")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                add(
                    EarningEntry(
                        model = r.str("model") ?: "",
                        // Money stays in integer micro-USD end to end; no
                        // floating-point currency anywhere in the pipeline.
                        amountMicroUsd = r.lng("amount_micro_usd") ?: 0,
                        promptTokens = r.lng("prompt_tokens"),
                        completionTokens = r.lng("completion_tokens"),
                        createdAt = r.str("created_at"),
                    )
                )
            }
        }
    }

    return Earnings(
        balanceUsd = e.str("balance_usd"),
        withdrawableUsd = e.str("withdrawable_usd"),
        totalUsd = e.str("total_usd"),
        jobsInHistory = e.intOrNull("jobs_in_history"),
        baseRewardsMicroRecent = e.lng("base_rewards_micro_usd_recent") ?: 0,
        perModel = perModel,
        recent = recent,
        estimates = e.obj("estimates")?.let { s ->
            EarningsEstimates(
                perDayUsd = s.dbl("per_day_usd") ?: 0.0,
                perWeekUsd = s.dbl("per_week_usd") ?: 0.0,
                perMonthUsd = s.dbl("per_month_usd") ?: 0.0,
                method = s.str("method"),
                trackedSinceEpochSec = s.dbl("tracked_since"),
                basisLabel = s.obj("basis")?.str("label"),
            )
        },
    )
}

private fun parseLmStudio(lm: JSONObject): LmStudioStats {
    val models = buildList {
        lm.arr("models")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                add(
                    LmModel(
                        id = m.str("id") ?: "",
                        type = m.str("type"),
                        state = m.str("state"),
                        publisher = m.str("publisher"),
                        quantization = m.str("quantization"),
                        sizeBytes = m.lng("size_bytes"),
                        maxContext = m.lng("max_context_length"),
                        loadedContext = m.lng("loaded_context_length"),
                    )
                )
            }
        }
    }
    return LmStudioStats(
        reachable = lm.bool("reachable") ?: false,
        loadedCount = lm.intOrNull("loaded_count") ?: 0,
        models = models,
    )
}
