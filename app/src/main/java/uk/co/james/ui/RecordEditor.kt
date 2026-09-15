package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.*
import uk.co.james.core.*

@Composable fun RecordEditor(vm:JamesViewModel) {
    val kind by vm.dialog.collectAsStateWithLifecycle();val text by vm.draft.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle()
    val raw=json.parseToJsonElement(text).jsonObject;val top=kind in listOf("Template","RutEvent","Note","WeekReflection");val d=if(top)raw else raw.obj("data")
    var deleting by rememberSaveable {mutableStateOf(false)}
    @Composable fun field(key:String,label:String=key,root:Boolean=top) {val value=if(kind=="WeekReflection")raw.obj("value").text(key) else if(root)raw.text(key)else d.text(key);OutlinedTextField(value,{vm.change(key,p(it),root)},label={Text(label)},modifier=Modifier.fillMaxWidth())}
    AlertDialog(onDismissRequest={if(!busy)vm.close()},title={Text(if(kind=="RutEvent")"Edit life event"else kind)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        when(kind) {
            "Template" -> {field("title","Name");field("emoji","Symbol");field("category","Category");Choice("Direction",d.text("type"),listOf("positive","negative")){vm.change("type",p(it),true)};field("points","Signed points");field("recoveryTitle","Recovery title (optional)");field("recoveryPoints","Recovery points")}
            "RutEvent" -> {field("title","Name");field("points","Signed points");field("category","Category");field("note","Note");field("localDate","Local date (YYYY-MM-DD)");field("timestamp","Timestamp (ISO 8601)");Muted("Changing a linked event is validated against its recovery history.");if(raw.text("type")!="initial")TextButton(onClick={deleting=true}){Text("Delete event…")}}
            "Routine" -> {field("title","Routine name");field("category","Category");Choice("Period",d.text("period"),listOf("Morning","Afternoon","Evening")){vm.change("period",p(it))};field("startDate","First scheduled date (YYYY-MM-DD)");val days=d.array("days").mapNotNull {(it as? JsonPrimitive)?.intOrNull}.toSet();listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat").forEachIndexed {index,label->Row {Checkbox(index in days,onCheckedChange={enabled->vm.change("days",JsonArray((if(enabled)days+index else days-index).sorted().map {p(it)}))});Text(label,Modifier.padding(top=12.dp))}};field("note","Notes")}
            "Note" -> field("text","Daily note")
            "WeekReflection" -> field("text","Weekly reflection")
            "MoodEntry","DailyReview" -> {Muted("Fill in only what you want. Blank signals keep their automatic estimate or your previous recent report.");uk.co.james.state.stateFields.forEach {(key,label)->Choice(label,d.text(key).ifBlank {"Not reported"},listOf("Not reported")+uk.co.james.state.stateChoices.getValue(key)){vm.change(key,p(if(it=="Not reported")""else it))}};if(kind=="DailyReview"){field("good","Good thing today");field("bad","Bad thing today");field("important","Anything important")};field("note","Notes")}

            "TimeBlock" -> {Choice("Category",d.text("category"),listOf("Sleep","Work","Driving","Gig work","Coding","Exercise","Errands","Home","Relaxation","Routine / chores","Free time","Unclassified")){vm.change("category",p(it))};field("timestamp","Start (ISO 8601)",true);field("end","End (ISO 8601)");field("note","Notes");Muted("Overlapping manual corrections take priority. Times are your reports.")}
            "PlaceVisit" -> {field("title","Place");Choice("Category",d.text("category","Unclassified"),listOf("Home","Work","Errands","Exercise","Relaxation","Unclassified")){vm.change("category",p(it))};field("activity","What did you do?");field("note","Notes");Muted("Arrival, departure and duration were estimated from battery-aware location samples. Your labels take priority.")}
            else -> {field("title","What happened?");field("timestamp","Timestamp (ISO 8601)",true);field("note","Notes")}
        }
    }},confirmButton={Button(enabled=!busy,onClick={vm.saveDraft()}){Text("Save")}},dismissButton={TextButton(enabled=!busy,onClick={vm.close()}){Text("Cancel")}})
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("Delete this event?")},text={Text("Its linked recovery will also be removed. Export a backup first if you need an independent copy.")},confirmButton={TextButton(onClick={vm.action {val old=vm.repo.dao.get("loggedEvents",raw.text("id"))?:error("Event missing");require(old.rawJson==canonical(raw)){"Close and reopen before deleting an edited event."};vm.repo.deleteEvent(old);vm.close()};deleting=false}){Text("Delete")}},dismissButton={TextButton(onClick={deleting=false}){Text("Cancel")}})
}
