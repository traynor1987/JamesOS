package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.routines.*

@Composable fun Choice(label:String,value:String,values:List<String>,select:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true}) {Text("$label: $value")};DropdownMenu(expanded,onDismissRequest={expanded=false}) {values.forEach {v->DropdownMenuItem(text={Text(v)},onClick={select(v);expanded=false})}} }
}
@Composable fun RoutinesScreen(vm:JamesViewModel,records:List<StoredRecord>) {
    val date by vm.date.collectAsStateWithLifecycle();var archived by rememberSaveable {mutableStateOf(false)}
    val all=records.map {it.raw()};val routines=records.filter {it.kind=="Routine" && it.data().flag("archived")==archived}
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {PageTitle("Routines","SMALL THINGS, OVER TIME");DateControl(date,vm::date);Button(onClick={vm.open("Routine")}){Text("Add routine")};TextButton(onClick={archived=!archived}){Text(if(archived)"Show active"else "Show archived")}}
        items(routines,key={it.recordId}) {r->val stats=Habits.stats(r.raw(),all,date);JamesCard(r.data().text("title"),r.data().text("category")) {
            Text("${r.data().text("period")} · ${stats.first} day streak");Muted("${stats.second} completions · ${stats.third} missed scheduled days")
            if(Habits.due(r.raw(),date))Row {Checkbox(Habits.complete(all,r.recordId,date),onCheckedChange={vm.action {vm.repo.toggleHabit(r,date)}});Text("Complete on $date",Modifier.padding(top=12.dp))}else Muted("Not scheduled on this day")
            r.data().text("note").takeIf {it.isNotBlank()}?.let {Text(it)}
            Row {TextButton(onClick={vm.open("Routine",r)}){Text("Edit")};TextButton(onClick={vm.action {vm.repo.save(r.store,r.raw().changed("updatedAt" to p(now()),"data" to r.data().changed("archived" to p(!archived))),r.rawJson)}}){Text(if(archived)"Restore"else "Archive")}}
            records.filter {it.kind=="RoutineCompletion"&&it.data().text("routineId")==r.recordId}.sortedByDescending {it.localDate}.take(7).forEach {Muted("${it.localDate} · ${if(it.data().flag("completed"))"Complete"else "Unmarked"}")}
        }}
        if(routines.isEmpty())item {JamesCard("Room for your routines"){Muted("Add a routine or import your existing James history. Archiving preserves completions.")}}
        item {JamesCard("Legacy RUT history"){Button(onClick={vm.navigate("Life events")}){Text("Open preserved history")}}}
    }
}
@Composable fun RutScreen(vm:JamesViewModel,records:List<StoredRecord>,route:String) {
    val date by vm.date.collectAsStateWithLifecycle();var category by rememberSaveable {mutableStateOf("All")};var query by rememberSaveable {mutableStateOf("")}
    val events=records.filter {it.store=="loggedEvents"};val raw=events.map {it.raw()};val templates=records.filter {it.store=="eventTemplates"};val recovered=Ledger.recovered(raw)
    val closed=records.filter {it.store=="metadata"&&it.recordId.startsWith("closed-pull:")}.map {it.recordId.removePrefix("closed-pull:")}.toSet()
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {PageTitle("Legacy RUT history","PRESERVED EVENT-LED ERA · NOT CURRENT LIFE BALANCE");Choice("Explore",route,listOf("Life events","Journey","Event history","Recovery space","Daily notes","Calendar","RUT Insights")){vm.navigate(it)};DateControl(date,vm::date)}
        if(events.isEmpty())item {JamesCard("No Legacy RUT history") {Button(onClick={vm.navigate("Import Centre")}){Text("Import existing Legacy RUT data")};Muted("Legacy RUT remains import-compatible but is no longer a current point tracker.")}}
        item {JamesCard(Ledger.stage(Ledger.score(raw)),"${Ledger.score(raw)} historical points"){Text("${events.size} preserved entries · ${recovered.size} recoveries");Muted("Different model: this is not numerically equivalent to Life Balance v2.")}}
        when(route) {
            "Life events" -> {
                item {Choice("Category",category,listOf("All")+templates.map {it.raw().text("category")}.distinct().sorted()){category=it};OutlinedTextField(query,{query=it},label={Text("Find historical template")},modifier=Modifier.fillMaxWidth())}
                items(templates.filter {it.raw().flag("enabled")&&(category=="All"||it.raw().text("category")==category)&&it.raw().text("title").contains(query,true)}.sortedBy {it.raw().number("order")},key={it.recordId}) {t->JamesCard("${t.raw().text("emoji")} ${t.raw().text("title")}","${t.raw().number("points").toInt()} · ${t.raw().text("category")}") {Muted("Preserved legacy template; Life Balance v2 does not award point templates.")}}
            }
            "Recovery space" -> items(events.filter {it.raw().text("type")=="negative"&&it.recordId !in recovered},key={it.recordId}) {e->JamesCard(e.raw().text("title"),e.localDate){Text(e.raw().text("recoveryTitle","Historical recovery step"));Muted("Legacy recovery history is preserved; new Balance does not create RUT point recoveries.")}}
            "Daily notes" -> item {val note=records.firstOrNull {it.store=="dailyNotes"&&it.recordId==date};JamesCard("Notes for $date"){Text(note?.raw()?.text("text")?:"Nothing written yet.");Button(onClick={vm.open("Note",note)}){Text("Edit note")}}}
            "Calendar" -> {item {JamesCard("Review $date"){listOf("reviewed","quiet","unknown").forEach {status->TextButton(onClick={vm.markDay(status)}){Text("Mark $status")}}}};items(Ledger.missedDays(raw,records.filter {it.recordId.startsWith("day-review:")}.map {it.recordId.removePrefix("day-review:")}.toSet())) {day->TextButton(onClick={vm.date(day)}){Text("$day · needs review")}}}
            "RUT Insights" -> {item {JamesCard("Recorded patterns"){raw.filter {it.text("type")!="initial"}.groupBy {it.text("category")}.forEach {(name,rows)->Text("$name · ${rows.size} events · ${Ledger.score(rows)} points")};Muted("Average exact recovery time: ${Ledger.averageRecoveryMinutes(raw)?.toInt()?.let {"$it min"}?:"Not enough exact records"}")}}}
            else -> items(events.filter {route=="Journey"||it.localDate==date}.sortedByDescending {it.timestamp},key={it.recordId}) {e->JamesCard(e.raw().text("title"),"${e.localDate} · ${e.raw().number("points").toInt()}"){Text(e.raw().text("note"));TextButton(onClick={vm.open("RutEvent",e)}){Text("Edit / delete")}}}
        }
    }
}
