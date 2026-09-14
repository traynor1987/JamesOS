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
    val balance=remember(records){lifeBalance(records)}
    val cards=mutableListOf<@Composable ()->Unit>()
    cards.add {PageTitle("Life Balance","ROLLING PERSONAL TRENDS · EXPERIMENTAL")}
    cards.add {JamesCard("Life Balance",balance.trend+" · "+balance.autonomy) {
        Text("James OS records facts and context periods. It does not recalculate historical Rut point records.",style=MaterialTheme.typography.bodySmall)
        listOf(balance.days7,balance.days14,balance.days28).forEach {window->
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text(window.days.toString()+" DAYS",fontWeight=FontWeight.Black)
            if(window.score==null)Muted("No recorded context yet — unknown, not a penalty.")
            else {
                Text("Personal time: "+window.personalMinutes/60+"h "+window.personalMinutes%60+"m")
                Text("Obligation: "+window.obligationMinutes/60+"h "+window.obligationMinutes%60+"m")
                if(window.difficultMinutes>0)Text("Difficult context: "+window.difficultMinutes+"m")
                Text("Chosen activities: "+window.positiveActivities)
            }
        }
    }}
    cards.add {JamesCard("Quick facts","WHAT HAPPENED?") {
        listOf("Gym","Gaming","Cinema","Walk","Personal project","Went out").chunked(2).forEach {row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach {fact->OutlinedButton(onClick={vm.logLifeActivity(fact)},modifier=Modifier.weight(1f)){Text(fact,style=MaterialTheme.typography.labelSmall)}}}
        }
    }}
    cards.add {JamesCard("Difficult interaction","DISCREET FACT LOG") {
        listOf("RAISED_VOICE","DISMISSED","CONTROLLING","ARGUMENT","INSULT_HOSTILITY","OTHER").chunked(2).forEach {row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach {kind->OutlinedButton(onClick={vm.logDifficultInteraction(kind)},modifier=Modifier.weight(1f)){Text(kind.replace('_',' '),style=MaterialTheme.typography.labelSmall)}}}
        }
        Muted("Optional person/source is never required. This is personal context, not a claim about someone else.")
    }}
    AdaptiveCards(cards)
}
