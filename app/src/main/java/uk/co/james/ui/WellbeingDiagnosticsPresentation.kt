package uk.co.james.ui

import uk.co.james.state.AnxietyInputStamp

/** Keeps Insights diagnostics useful on mature databases without constructing a
 * wall of historical timestamps during route entry. */
internal fun boundedDiagnosticInputs(inputs:List<AnxietyInputStamp>,limit:Int=12):List<AnxietyInputStamp> =
    inputs.sortedBy {it.timestamp}.takeLast(limit.coerceAtLeast(1))
