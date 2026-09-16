package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.james.database.StoredRecord
import uk.co.james.state.*

@Composable fun LifeBalanceScreen(vm:JamesViewModel,records:List<StoredRecord>) {
    val balance=remember(records){lifeBalanceV2(records)}
    val cards=mutableListOf<@Composable ()->Unit>()
    cards.add {PageTitle("Life Balance","OWNERSHIP, AUTONOMY & CONTINUITY · v2")}
    cards.add {JamesCard("Current Balance",balance.current.label) {
        Text("Life Balance uses confirmed Time Ownership and interruptions. It does not score health, mood, places, activities or Legacy RUT.",style=MaterialTheme.typography.bodySmall)
        Text("Primary: ${balance.current.days}-day window · ${balance.current.evidenceState}")
        Text("Trend: ${balance.trend}")
        TextButton(onClick={vm.navigate("Calibration:life_balance_v2")}){Text("HOW MUCH HAS MY TIME FELT LIKE MINE? →")}
        listOf(balance.days7,balance.days28,balance.days90).forEach {window->
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text(window.days.toString()+" DAYS",fontWeight=FontWeight.Black)
            if(window.score==null)Muted("LEARNING · ${window.coveragePercent}% observed ownership coverage. Unknown is not a penalty.")
            else {
                Text("${window.score}/100 · ${window.label}")
                Text("Autonomous: "+window.autonomousMinutes/60+"h "+window.autonomousMinutes%60+"m")
                Text("Work: "+window.workMinutes/60+"h "+window.workMinutes%60+"m")
                Text("Committed: "+window.committedMinutes/60+"h "+window.committedMinutes%60+"m · Constrained: "+window.constrainedMinutes/60+"h "+window.constrainedMinutes%60+"m")
                if(window.unknownMinutes>0)Muted("Unknown ownership: "+window.unknownMinutes/60+"h "+window.unknownMinutes%60+"m")
                if(window.interruptions>0)Muted("Interruptions: "+window.interruptions+" · "+window.interruptionMinutes+"m · longest personal block "+window.longestAutonomousBlockMinutes+"m")
                Muted("Coverage: ${window.coveragePercent}% · facts are separate from direct James feedback.")
            }
        }
    }}
    cards.add {JamesCard("Legacy RUT history","HISTORICAL, NOT CURRENT BALANCE") {Muted("RUT was the predecessor to Life Balance. Its event-led score is preserved, but is not numerically equivalent to Balance v2.");TextButton(onClick={vm.navigate("Life events")}){Text("OPEN LEGACY RUT HISTORY →")}}}
    AdaptiveCards(cards)
}
