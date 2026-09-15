package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import uk.co.james.database.StoredRecord
import uk.co.james.core.*

private data class VisitPin(val id:String,val title:String,val subtitle:String,val latitude:Double,val longitude:Double,val confirming:Boolean=false)

@Composable fun LocationMapScreen(vm:JamesViewModel,records:List<StoredRecord>) {
    val completedPins=remember(records) { records.filter {it.kind=="PlaceVisit"}.sortedByDescending {it.timestamp}.mapNotNull {visit->
        val data=visit.data();val lat=data.number("latitude");val lon=data.number("longitude")
        if(lat==0.0&&lon==0.0)null else VisitPin(visit.recordId,data.text("title","Unnamed stop"),"${data.number("durationMin").toLong()/60}h ${data.number("durationMin").toLong()%60}m · ${data.text("ownership","UNKNOWN")}",lat,lon)
    }}
    val currentPin=remember(records) {records.firstOrNull {it.kind=="LocationAnchor"}?.let {anchor->val data=anchor.data();val lat=data.number("latitude");val lon=data.number("longitude");if(lat==0.0&&lon==0.0)null else VisitPin(anchor.recordId,data.text("placeName","Current area"),"Here ${data.number("durationMin").toLong()}m · ${data.text("movement","Stationary")} · ownership ${data.text("ownership","UNKNOWN")}",lat,lon,true)}}
    val pins=listOfNotNull(currentPin)+completedPins
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        PageTitle("Your places","OPENSTREETMAP · COMPLETED STOPS")
        if(pins.isEmpty()) {
            JamesCard("Waiting for a first location","Map is ready") {Muted("The map is available now. James will add your current area after its next low-power location check, then confirm it as a visit once you have stayed for five minutes.")}
        } else {
            Card(Modifier.fillMaxWidth().weight(1f),shape=MaterialTheme.shapes.large,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxSize()) {
                    AndroidView(factory={context->
                        Configuration.getInstance().userAgentValue=context.packageName
                        MapView(context).apply {setTileSource(TileSourceFactory.MAPNIK);setMultiTouchControls(true);controller.setZoom(14.0)}
                    },update={map->
                        map.overlays.clear()
                        pins.forEachIndexed {index,pin->
                            Marker(map).apply {position=GeoPoint(pin.latitude,pin.longitude);title=pin.title;subDescription=pin.subtitle;setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);map.overlays.add(this)}
                            if(index==0) {map.controller.setCenter(GeoPoint(pin.latitude,pin.longitude));map.controller.setZoom(14.0)}
                        }
                        map.invalidate()
                    },modifier=Modifier.fillMaxWidth().weight(1f))
                    Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)) {Icon(Icons.Outlined.Place,null,tint=jamesLime);Text("${completedPins.size} completed stop${if(completedPins.size==1)"" else "s"}${if(currentPin!=null)" · checking current area" else ""} · © OpenStreetMap contributors",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
            }
            completedPins.firstOrNull()?.let {latest->JamesCard("Latest stop",latest.title) {Muted(latest.subtitle);TextButton(onClick={vm.open("PlaceVisit",records.firstOrNull {it.recordId==latest.id})}){Text("Add what I did / note")}}}
            if(currentPin!=null)JamesCard("Current area","Confirming") {Muted("This is not saved as a completed visit yet. James will only keep it in your places history after it has confirmed you stayed there for five minutes.")}
        }
    }
}
