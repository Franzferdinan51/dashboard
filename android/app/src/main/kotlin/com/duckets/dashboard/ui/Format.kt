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
    String.format(Locale.US, "%.${digits}f%%", v)

fun fmtCount(n: Long?): String =
    if (n == null) "—" else String.format(Locale.US, "%,d", n)

fun fmtUsd(usd: String?): String {
    if (usd == null) return "—"
    val v = usd.toDoubleOrNull() ?: return "$$usd"
    return when {
        v == 0.0 -> "$0.00"
        v < 0.01 -> String.format(Locale.US, "$%.4f", v)
        v < 1 -> String.format(Locale.US, "$%.3f", v)
        else -> String.format(Locale.US, "$%,.2f", v)
    }
}

fun fmtMicroUsd(micro: Long): String = fmtUsd((micro / 1_000_000.0).toString())

fun fmtDuration(totalSeconds: Long?): String {
    if (totalSeconds == null) return "—"
    val d = totalSeconds / 86_400
    val h = (totalSeconds % 86_400) / 3_600
    val m = (totalSeconds % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${totalSeconds}s"
    }
}

fun fmtAgo(epochSeconds: Double?, nowMillis: Long): String {
    if (epochSeconds == null) return "—"
    val ageSec = (nowMillis / 1000.0 - epochSeconds).toLong()
    return when {
        ageSec < 5 -> "just now"
        ageSec < 60 -> "${ageSec}s ago"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        else -> "${ageSec / 3600}h ago"
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

fun isStale(lastUpdatedMillis: Long, refreshSeconds: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (lastUpdatedMillis <= 0L) return false
    val thresholdMs = refreshSeconds.coerceIn(2, 60) * 1000L * 3
    return (nowMillis - lastUpdatedMillis) > thresholdMs
}

/** Validate a bridge URL draft. Returns null when valid, else a short reason. */
fun bridgeUrlError(raw: String): String? {
    val v = raw.trim()
    if (v.isEmpty()) return "Enter the bridge address"
    if (!v.startsWith("http://") && !v.startsWith("https://")) return "Must start with http:// or https://"
    val host = v.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
    if (host.isBlank()) return "Missing host — e.g. http://192.168.1.50:8901"
    return null
}
