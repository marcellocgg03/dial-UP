package dev.t2u.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Timing summary of one resolve pass. Cold is the first record in index order when it succeeded (it carries
 * any lazy initialisation); warm is every other successful record. Failed records are counted but never timed.
 */
data class PassSummary(
    val total: Int,
    val ok: Int,
    val fail: Int,
    val coldMs: Long?,
    val warmMedianMs: Long?,
    val warmP90Ms: Long?,
    val minMs: Long?,
    val maxMs: Long?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("total", total)
        put("ok", ok)
        put("fail", fail)
        put("coldMs", coldMs)
        put("warmMedianMs", warmMedianMs)
        put("warmP90Ms", warmP90Ms)
        put("minMs", minMs)
        put("maxMs", maxMs)
    }
}

fun summarize(records: List<ResolveRecord>): PassSummary {
    val ordered = records.sortedBy { it.index }
    val okRecords = ordered.filter { it.ok }
    val cold = ordered.firstOrNull()?.takeIf { it.ok }
    val warm = okRecords.filter { it !== cold }.map { it.wallMs }.sorted()
    val all = okRecords.map { it.wallMs }
    return PassSummary(
        total = ordered.size,
        ok = okRecords.size,
        fail = ordered.size - okRecords.size,
        coldMs = cold?.wallMs,
        warmMedianMs = median(warm),
        warmP90Ms = nearestRank(warm, 90),
        minMs = all.minOrNull(),
        maxMs = all.maxOrNull(),
    )
}

/** Median of a sorted list; an even count is the mean of the middle two, rounded down. */
private fun median(sorted: List<Long>): Long? {
    if (sorted.isEmpty()) return null
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** Nearest-rank percentile of a sorted list: the value at rank ceil(p/100 * n). */
private fun nearestRank(sorted: List<Long>, percent: Int): Long? {
    if (sorted.isEmpty()) return null
    val rank = (percent * sorted.size + 99) / 100
    return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
}
