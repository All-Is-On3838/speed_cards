package com.speedcards.data.backup

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * Which backups to keep: the newest per day for [daily] days, per ISO week for [weekly] weeks,
 * and per month for [monthly] months. Everything else can be deleted.
 */
object Retention {
    fun keep(
        backups: Map<String, Long>,
        daily: Int = 7,
        weekly: Int = 4,
        monthly: Int = 6,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Set<String> {
        val newestFirst = backups.entries.sortedByDescending { it.value }
        fun pick(limit: Int, bucket: (Long) -> Any): List<String> {
            val seen = LinkedHashMap<Any, String>()
            for ((name, ts) in newestFirst) {
                val key = bucket(ts)
                if (key !in seen) {
                    if (seen.size == limit) break
                    seen[key] = name
                }
            }
            return seen.values.toList()
        }
        fun date(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        return (
            pick(daily) { date(it) } +
                pick(weekly) { date(it).let { d -> d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) } } +
                pick(monthly) { date(it).let { d -> d.year * 100 + d.monthValue } }
            ).toSet()
    }
}
