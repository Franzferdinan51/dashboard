package com.duckets.dashboard.ui

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

fun fmtBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    // 1024-based so a 128 GB machine reads "128 GB", matching Activity Monitor.
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val v = bytes / 1024.0.pow(i)
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun fmtRate(bps: Long): String = when {
    bps <= 0 -> "0 B/s"
    bps < 1_000 -> "$bps B/s"
    bps < 1_000_000 -> String.format(Locale.US, "%.1f KB/s", bps / 1_000.0)
    bps < 1_000_000_000 -> String.format(Locale.US, "%.1f MB/s", bps / 1_000_000.0)
    else -> String.format(Locale.US, "%.2f GB/s", bps / 1_000_000_000.0)
}

fun fmtGb(gb: Double?, digits: Int = 1): String =
    if (gb == null) "—" else String.format(Locale.US, "%.${digits}f GB", gb)

fun fmtPct(v: Double, digits: Int = 0): String =
    // A non-finite value reads as "absent", same rule as the repository's
    // JSON parsing — never render "NaN%" on a gauge.
    if (!v.isFinite()) "—" else String.format(Locale.US, "%.${digits}f%%", v)

fun fmtCount(n: Long?): String =
    if (n == null) "—" else String.format(Locale.US, "%,d", n)

fun fmtUsd(usd: String?): String {
    if (usd == null) return "—"
    // toDoubleOrNull accepts "NaN"/"Infinity" as valid numbers — treat
    // them as unparseable and show the bridge's raw string instead of
    // rendering "$NaN".
    val v = usd.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return "$$usd"
    return when {
        v == 0.0 -> "$0.00"
        v < 0 -> String.format(Locale.US, "-$%,.2f", -v)
        v < 0.00005 -> "<$0.0001"
        v < 0.01 -> String.format(Locale.US, "$%.4f", v)
        v < 1 -> String.format(Locale.US, "$%.3f", v)
        else -> String.format(Locale.US, "$%,.2f", v)
    }
}

fun fmtMicroUsd(micro: Long): String = fmtUsd((micro / 1_000_000.0).toString())

fun fmtDuration(totalSeconds: Long?): String {
    if (totalSeconds == null) return "—"
    val secs = totalSeconds.coerceAtLeast(0)
    val d = secs / 86_400
    val h = (secs % 86_400) / 3_600
    val m = (secs % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${secs}s"
    }
}

/** Shorten a model id for display: strip publisher prefix, keep it readable. */
fun shortModel(id: String): String = id.substringAfterLast("/")

/** "2026-10-12T16:55:00.775918Z" -> "4:55 PM" (device-local display). */
fun fmtLedgerTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return try {
        val instant = java.time.Instant.parse(iso)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
            .withZone(java.time.ZoneId.systemDefault())
        fmt.format(instant)
    } catch (e: Exception) {
        iso.take(16).replace("T", " ")
    }
}

/** Relative freshness: "just now", "12s ago", "3m ago". */
fun fmtFreshness(lastUpdatedMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (lastUpdatedMillis <= 0L) return "waiting for first sample"
    val ageSec = ((nowMillis - lastUpdatedMillis) / 1000).coerceAtLeast(0)
    return when {
        ageSec < 3 -> "updated just now"
        ageSec < 60 -> "updated ${ageSec}s ago"
        ageSec < 3600 -> "updated ${ageSec / 60}m ago"
        else -> "updated ${ageSec / 3600}h ago"
    }
}

/** Validate a bridge URL draft. Returns null when valid, else a short reason. */
fun bridgeUrlError(raw: String): String? {
    val v = raw.trim()
    if (v.isEmpty()) return "Enter the bridge address"
    if (v.any { it.isWhitespace() }) return "No spaces in the address"
    if (!v.startsWith("http://") && !v.startsWith("https://")) return "Must start with http:// or https://"
    val uri = try {
        java.net.URI(v)
    } catch (e: Exception) {
        return "That address can't be parsed"
    }
    val host = uri.host ?: return "Missing host — e.g. http://192.168.1.50:8901"
    if (host.isBlank()) return "Missing host — e.g. http://192.168.1.50:8901"
    if (uri.port != -1 && uri.port !in 1..65535) return "Port must be between 1 and 65535"
    if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return "No path after the port — just http://host:8901"
    if (uri.rawQuery != null) return "No query string in the bridge address"
    return null
}
