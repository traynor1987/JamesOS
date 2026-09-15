package uk.co.james.ui

import uk.co.james.database.StoredRecord

/**
 * A screen-level state machine for Today preparation. Refreshes never discard
 * the last coherent snapshot: a new snapshot is prepared off the presentation
 * path and swapped in only when complete.
 */
internal sealed interface TodayPreparation<out T> {
    data object InitialLoading:TodayPreparation<Nothing>
    data class Ready<T>(val value:T):TodayPreparation<T>
    data class Refreshing<T>(val value:T):TodayPreparation<T>
    data class Failed<T>(val previous:T?,val type:String,val firstJamesFrame:String?):TodayPreparation<T>
}

internal fun <T> TodayPreparation<T>.visibleSnapshot():T?=when(this) {
    TodayPreparation.InitialLoading -> null
    is TodayPreparation.Ready -> value
    is TodayPreparation.Refreshing -> value
    is TodayPreparation.Failed -> previous
}

internal fun <T> beginTodayPreparation(previous:TodayPreparation<T>):TodayPreparation<T> =
    previous.visibleSnapshot()?.let { TodayPreparation.Refreshing(it) } ?: TodayPreparation.InitialLoading

/** Derived display snapshots must not feed the observer that recomputes and
 * persists those same snapshots. This stops Room invalidation feedback loops
 * while retaining the original evidence rows and all display records. */
internal fun todayPreparationInputRecords(rows:List<StoredRecord>):List<StoredRecord> = rows.filterNot { row ->
    row.kind in setOf("EnergySnapshot","StateEstimate") ||
        (row.store=="metadata" && row.recordId.let { key ->
            key.startsWith("mental-wellbeing:") ||
                key.startsWith("mental-wellbeing-anxiety:") ||
                key.startsWith("right-now:") ||
                key.startsWith("body-battery:")
        })
}

/** Stable, private in-memory revision for a bounded source snapshot. */
internal fun todayPreparationInputSignature(rows:List<StoredRecord>):Int =
    rows.fold(17) { hash,row -> 31*hash + (row.recordId.hashCode()*31 + row.updatedAt.hashCode()) }

/** No values, coordinates or content: only the latest evidence type/source. */
internal fun todayRefreshTrigger(previousSignature:Int?,rows:List<StoredRecord>):String {
    if(previousSignature==todayPreparationInputSignature(rows)) return "clock/freshness"
    val latest=rows.maxWithOrNull(compareBy<StoredRecord> { it.updatedAt.ifBlank { it.timestamp } }.thenBy { it.recordId })
    return latest?.let { "${it.kind}:${it.source}" } ?: "initial evidence"
}
