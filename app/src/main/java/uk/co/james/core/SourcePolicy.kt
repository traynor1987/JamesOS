package uk.co.james.core

/** Authority is per metric, not an instruction to overwrite another source's raw record. */
object SourcePolicy {
    val priorities=mapOf(
        "Recovery" to listOf("whoop"),
        "HRV" to listOf("whoop"),
        "Sleep quality" to listOf("whoop"),
        "Sleep" to listOf("whoop","health_connect"),
        "Resting heart rate" to listOf("whoop","health_connect"),
        "Steps" to listOf("health_connect","samsung_health"),
        "Exercise" to listOf("health_connect","whoop"),
        "WorkShift" to listOf("shift_tracker"),
        "GigSession" to listOf("gig_tracker")
    )
    val readOnlyAuthorities=setOf("shift_tracker","gig_tracker","health_connect","samsung_health","whoop")
    /** Lower is better. Unknown sources remain usable fallback evidence but can
     * never silently outrank a declared provider for the same measurement. */
    fun rank(metric:String,source:String):Int = priorities[metric]?.indexOf(source)?.takeIf {it>=0} ?: 99
    fun externalKey(source:String,kind:String,externalId:String):String {
        require(source in readOnlyAuthorities && externalId.isNotBlank())
        return "$source:$kind:$externalId"
    }
}
