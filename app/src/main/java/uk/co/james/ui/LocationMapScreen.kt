package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import uk.co.james.core.*
import uk.co.james.database.StoredRecord

private enum class MapPinType { CURRENT, VISIT, UNKNOWN_VISIT, KNOWN_PLACE }
private data class VisitPin(val id:String,val title:String,val subtitle:String,val latitude:Double,val longitude:Double,val type:MapPinType)

@Composable fun LocationMapScreen(vm:JamesViewModel,records:List<StoredRecord>) {
    val range by vm.locationMapRange.collectAsState()
    val visits=remember(records) { records.filter {it.kind=="PlaceVisit"}.sortedByDescending {it.data().text("start",it.timestamp)} }
    val completedPins=remember(visits) { visits.mapNotNull { visit->
        val d=visit.data();val lat=d.number("latitude");val lon=d.number("longitude")
        if(lat==0.0&&lon==0.0)null else VisitPin(visit.recordId,d.text("title","Unknown place"),"${d.text("start").take(16).replace('T',' ')} · ${d.number("durationMin").toLong()}m · ${d.text("ownership","UNKNOWN")} · ${d.text("ownershipSource","INFERRED")}",lat,lon,if(d.text("title","Unknown place")=="Unknown place")MapPinType.UNKNOWN_VISIT else MapPinType.VISIT)
    }}
    // Current physical coordinates are deliberately independent of place matching.
    val currentPin=remember(records) {records.firstOrNull {it.kind=="LocationAnchor"}?.let {anchor->val d=anchor.data();val lat=d.number("latitude");val lon=d.number("longitude");if(lat==0.0&&lon==0.0)null else VisitPin(anchor.recordId,"You now · ${d.text("placeName","Unknown place")}","${d.text("visitState","CONFIRMING")} · here ${d.number("durationMin").toLong()}m · accuracy ${d.number("accuracy").toInt()}m",lat,lon,MapPinType.CURRENT)}}
    val knownPins=remember(records,completedPins,currentPin) { records.filter {it.kind=="Place"}.mapNotNull {place->
        val d=place.data();val lat=d.number("latitude");val lon=d.number("longitude")
        if(lat==0.0&&lon==0.0)null else VisitPin(place.recordId,d.text("title","Known place"),"Known place · ${d.text("category","Unclassified")}",lat,lon,MapPinType.KNOWN_PLACE)
    }.filter { candidate->(listOfNotNull(currentPin)+completedPins).any {pin->distanceApproxMetres(pin.latitude,pin.longitude,candidate.latitude,candidate.longitude)<5000} }.take(12)}
    val pins=listOfNotNull(currentPin)+completedPins+knownPins
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        PageTitle("Your places","VISITS, NOT ROUTE HISTORY")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Today" to 1,"Yesterday" to 2,"7 days" to 7).forEach {(label,days)->FilterChip(selected=range==days,onClick={vm.locationMapRange(days)},label={Text(label)})}}
        if(pins.isEmpty()) JamesCard("Waiting for a first location","Map is ready") {Muted("The next valid device fix will be shown even when its place is still Unknown. A completed visit needs five minutes of stable dwell evidence.")}
        else Card(Modifier.fillMaxWidth().weight(1f),shape=MaterialTheme.shapes.large,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxSize()) {
                AndroidView(factory={context-> Configuration.getInstance().userAgentValue=context.packageName;MapView(context).apply {setTileSource(TileSourceFactory.MAPNIK);setMultiTouchControls(true)} },update={map->
                    map.overlays.clear()
                    pins.forEach { pin->Marker(map).apply {
                        position=GeoPoint(pin.latitude,pin.longitude);title=when(pin.type){MapPinType.CURRENT->"You now";MapPinType.VISIT->"Visit";MapPinType.UNKNOWN_VISIT->"Unknown visit";MapPinType.KNOWN_PLACE->"Known place"}+" · ${pin.title}";subDescription=pin.subtitle;setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _,_-> if(pin.type==MapPinType.VISIT||pin.type==MapPinType.UNKNOWN_VISIT) vm.open("PlaceVisit",records.firstOrNull {it.recordId==pin.id}); true };map.overlays.add(this)
                    }}
                    val frame=(listOfNotNull(currentPin)+completedPins).map {GeoPoint(it.latitude,it.longitude)}
                    map.post {if(frame.size==1){map.controller.setZoom(15.5);map.controller.setCenter(frame.first())}else if(frame.size>1){map.zoomToBoundingBox(BoundingBox.fromGeoPoints(frame),true,80)}}
                    map.invalidate()
                },modifier=Modifier.fillMaxWidth().weight(1f))
                MapLegend(completedPins.size,currentPin!=null,knownPins.isNotEmpty())
            }
        }
        currentPin?.let {JamesCard("Current visit",it.title.removePrefix("You now · ")) {Muted(it.subtitle);Muted("Physical location is shown even when place matching is Unknown.")}}
        completedPins.firstOrNull()?.let {latest->JamesCard("Latest completed visit",latest.title) {Muted(latest.subtitle);TextButton(onClick={vm.open("PlaceVisit",records.firstOrNull {it.recordId==latest.id})}){Text("View visit details and corrections")}}}
        if(visits.isEmpty())Muted("No completed visits exist in this range yet. Saved known places are definitions; they are not counted as visits.")
    }
}

@Composable private fun MapLegend(visits:Int,current:Boolean,known:Boolean) {Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {if(current)Text("● You now",style=MaterialTheme.typography.bodySmall);if(visits>0)Text("📍 Visit",style=MaterialTheme.typography.bodySmall);if(known)Text("⌂ Known place",style=MaterialTheme.typography.bodySmall);Text("? Unknown",style=MaterialTheme.typography.bodySmall)}}
private fun distanceApproxMetres(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double {val dLat=(aLat-bLat)*111_000;val dLon=(aLon-bLon)*70_000;return kotlin.math.sqrt(dLat*dLat+dLon*dLon)}
