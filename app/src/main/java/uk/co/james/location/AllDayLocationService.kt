package uk.co.james.location

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import uk.co.james.JamesApplication
import uk.co.james.MainActivity
import uk.co.james.R
import uk.co.james.core.*
import java.time.*

internal fun sameVisit(distanceMetres:Float,newAccuracy:Float,oldAccuracy:Float)=distanceMetres<=maxOf(200f,newAccuracy*2,oldAccuracy*2)
internal fun visitMinutes(start:Instant,end:Instant)=Duration.between(start,end).toMinutes().coerceAtLeast(0)
internal fun completedVisit(minutes:Long)=minutes>=5
internal fun isFreshPassiveFix(observedAt:Instant,now:Instant)=Duration.between(observedAt,now)<=Duration.ofMinutes(15)

class AllDayLocationService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var client:FusedLocationProviderClient
    private val callback=object:LocationCallback() {
        override fun onLocationResult(result:LocationResult) {
            val point=result.lastLocation?:return
            val app=application as JamesApplication
            scope.launch {runCatching {VisitRecorder.record(app,point)}.onFailure {android.util.Log.w("JamesLocation","Location sample could not be grouped")}}
        }
    }
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,AllDayLocationService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_james_notification).setContentTitle("James location timeline")
            .setContentText("Grouping nearby points into private visits").setContentIntent(open)
            .addAction(0,"Stop tracking",stop).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        startForeground(NOTIFICATION_ID,notification)
        client=LocationServices.getFusedLocationProviderClient(this)
    }
    @Suppress("MissingPermission")
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP) {
            scope.launch {VisitRecorder.finish(application as JamesApplication,Instant.now());(application as JamesApplication).preferences.allDay(false);stopSelf()}
            return START_NOT_STICKY
        }
        scope.launch {if(!(application as JamesApplication).preferences.allDay.first())stopSelf()}
        val allowed=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!allowed){stopSelf();return START_NOT_STICKY}
        // A low-power foreground request: enough to confirm a stop, not a route recorder.
        val request=LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,5*60*1000L)
            .setMinUpdateIntervalMillis(5*60*1000L).setMaxUpdateDelayMillis(5*60*1000L).build()
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(request,callback,Looper.getMainLooper())
        // A five-minute interval must not also mean "blank map for five minutes".
        // The last fix is only a current-position/confirming anchor; it never makes
        // a completed visit by itself.
        scope.launch { runCatching { client.lastLocation.await()?.let { VisitRecorder.record(application as JamesApplication,it) } } }
        return START_STICKY
    }
    override fun onDestroy(){if(::client.isInitialized)client.removeLocationUpdates(callback);scope.cancel();super.onDestroy()}
    override fun onBind(intent:Intent?)=null
    private fun createChannel(){if(Build.VERSION.SDK_INT>=26)(getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(CHANNEL,"Location timeline",NotificationManager.IMPORTANCE_LOW).apply {description="Shown while James builds your location timeline"})}
    companion object {
        private const val CHANNEL="james_location_timeline"
        private const val NOTIFICATION_ID=204
        private const val ACTION_STOP="uk.co.james.location.STOP"
        fun start(context:Context)=ContextCompat.startForegroundService(context,Intent(context,AllDayLocationService::class.java))
        fun stop(context:Context)=context.startService(Intent(context,AllDayLocationService::class.java).setAction(ACTION_STOP))
    }
}

internal object VisitRecorder {
    private const val ANCHOR="location:current-anchor"
    private data class PlaceRecordResolution(val place:uk.co.james.database.StoredRecord?,val confidence:PlaceMatchConfidence)
    suspend fun record(app:JamesApplication,point:Location) {
        if(!app.preferences.allDay.first()||point.accuracy>200f)return
        val now=Instant.now()
        val at=Instant.ofEpochMilli(point.time).takeIf {it<=now.plusSeconds(60)}?:now
        // `lastLocation` can survive a phone restart for days. It is useful to
        // seed nothing; it is not current evidence and must not replace an
        // active geofence-confirmed visit.
        if(!isFreshPassiveFix(at,now))return
        val old=app.repository.dao.currentLocationAnchor(ANCHOR)
        if(old==null){anchor(app,point,at);return}
        val d=old.data()
        val distance=FloatArray(1).also {Location.distanceBetween(d.number("latitude"),d.number("longitude"),point.latitude,point.longitude,it)}
        if(sameVisit(distance[0],point.accuracy,d.number("accuracy",100.0).toFloat())) {
            val samples=d.number("samples",1.0).toInt().coerceAtLeast(1)
            val resolved=nearestPlace(app,point)
            val manualPlaceId=d.text("placeId").takeIf {d.text("placeSource")=="JAMES_CONFIRMED"}
            val place=manualPlaceId?.let {id->app.repository.dao.get("personalRecords",id)}?:resolved.place
            val minutes=visitMinutes(Instant.parse(d.text("start")),at)
            val data=d.changed(
                "latitude" to p((d.number("latitude")*samples+point.latitude)/(samples+1)),
                "longitude" to p((d.number("longitude")*samples+point.longitude)/(samples+1)),
                "accuracy" to p(point.accuracy),
                "provider" to p(point.provider?:"Fused low-power"),
                "lastSeen" to p(at.toString()),
                "passiveLastSeen" to p(at.toString()),
                "passiveProvider" to p(point.provider?:"Fused low-power"),
                "samples" to p(samples+1),
                "durationMin" to p(minutes),
                "visitState" to p(if(completedVisit(minutes)) "ACTIVE" else "CONFIRMING"),
                "placeId" to p(place?.recordId?:d.text("placeId")),
                "placeName" to p(place?.data()?.text("title")?:d.text("placeName","Unknown place")),
                "placeConfidence" to p(if(place==null)d.text("placeConfidence","UNKNOWN") else resolved.confidence.name),
                "placeSource" to p(if(manualPlaceId!=null)"JAMES_CONFIRMED" else if(place==null)d.text("placeSource","PASSIVE") else "PASSIVE_SPATIAL")
            )
            app.repository.save("personalRecords",personal("LocationAnchor",data,ANCHOR,"gps",d.text("start")))
        } else {
            // One outlying point is GPS noise, not a departure.  Keep the active
            // visit open until the next stable evidence arrives or the exit grace
            // period expires.
            val last=Instant.parse(d.text("lastSeen",d.text("start")))
            if (shouldCloseAnchor(last,at,false)) { close(app,old,last); anchor(app,point,at) }
        }
    }
    suspend fun finish(app:JamesApplication,at:Instant) {
        app.repository.dao.currentLocationAnchor(ANCHOR)?.let {close(app,it,minOf(at,Instant.parse(it.data().text("lastSeen",it.data().text("start")))));app.repository.dao.delete("personalRecords",ANCHOR)}
    }
    /** A geofence is first-class location evidence. It names the saved place
     * that Android says was entered/exited; it is not an ownership decision and
     * it does not pretend to be a fresh GPS point. */
    suspend fun geofence(app:JamesApplication,place:uk.co.james.database.StoredRecord,entering:Boolean,at:Instant=Instant.now()) {
        val old=app.repository.dao.currentLocationAnchor(ANCHOR)
        val activePlaceId=old?.data()?.text("placeId").orEmpty()
        if(shouldStartGeofenceAnchor(activePlaceId,place.recordId,entering)) {
            old?.let {close(app,it,at);app.repository.dao.delete("personalRecords",ANCHOR)}
            geofenceAnchor(app,place,at,old)
        } else if(shouldCloseGeofenceAnchor(activePlaceId,place.recordId,entering)) {
            old?.let {close(app,it,at);app.repository.dao.delete("personalRecords",ANCHOR)}
        } else if(entering&&activePlaceId==place.recordId&&old!=null) {
            val data=old.data().changed("lastSeen" to p(at.toString()),"placeObservedAt" to p(at.toString()),"placeConfidence" to p("CONFIRMED"),"placeSource" to p("GEOFENCE"))
            app.repository.save(old.store,old.raw().changed("data" to data,"updatedAt" to p(at.toString())),old.rawJson)
        }
    }
    private suspend fun anchor(app:JamesApplication,point:Location,at:Instant) {
        val resolved=nearestPlace(app,point)
        val place=resolved.place
        app.repository.save("personalRecords",personal("LocationAnchor",fields(
            "start" to p(at.toString()),"lastSeen" to p(at.toString()),"latitude" to p(point.latitude),"longitude" to p(point.longitude),
            "accuracy" to p(point.accuracy),"samples" to p(1),"durationMin" to p(0),
            "provider" to p(point.provider?:"Fused low-power"),
            "passiveLastSeen" to p(at.toString()),"passiveProvider" to p(point.provider?:"Fused low-power"),
            "ownership" to p(inferredOwnership().name),"ownershipSource" to p("INFERRED"),"movement" to p("Stationary"),
            "placeId" to p(place?.recordId?:""),"placeName" to p(place?.data()?.text("title")?:"Unknown place"),
            "placeConfidence" to p(resolved.confidence.name),"placeSource" to p(if(place==null)"PASSIVE" else "PASSIVE_SPATIAL"),"visitState" to p("CONFIRMING")
        ),ANCHOR,"gps",at.toString()))
    }
    private suspend fun geofenceAnchor(app:JamesApplication,place:uk.co.james.database.StoredRecord,at:Instant,previous:uk.co.james.database.StoredRecord?) {
        val d=place.data()
        val prior=previous?.data()
        app.repository.save("personalRecords",personal("LocationAnchor",fields(
            "start" to p(at.toString()),"lastSeen" to p(at.toString()),"placeObservedAt" to p(at.toString()),
            "latitude" to p(d.number("latitude")),"longitude" to p(d.number("longitude")),"accuracy" to p(d.number("radius",150.0)),"samples" to p(1),"durationMin" to p(0),
            "provider" to p("Geofence"),"passiveLastSeen" to p(prior?.let {it.text("passiveLastSeen",it.text("lastSeen"))}?:""),"passiveProvider" to p(prior?.let {it.text("passiveProvider",it.text("provider"))}?:""),"ownership" to p(inferredOwnership().name),"ownershipSource" to p("INFERRED"),"movement" to p("Stationary"),
            "placeId" to p(place.recordId),"placeName" to p(d.text("title")),"placeConfidence" to p("CONFIRMED"),"placeSource" to p("GEOFENCE"),"visitState" to p("CONFIRMING")
        ),ANCHOR,"geofence",at.toString()))
    }
    private suspend fun nearestPlace(app:JamesApplication,point:Location):PlaceRecordResolution {
        val matches=app.repository.dao.places().map { saved ->
            val d=saved.data();val distance=FloatArray(1)
            val latitude=if(d.number("learnedSamples").toInt()>=3)d.number("learnedLatitude",d.number("latitude"))else d.number("latitude")
            val longitude=if(d.number("learnedSamples").toInt()>=3)d.number("learnedLongitude",d.number("longitude"))else d.number("longitude")
            Location.distanceBetween(point.latitude,point.longitude,latitude,longitude,distance)
            PlaceMatch(saved.recordId,d.text("title"),d.text("category","Unclassified"),distance[0],d.number("radius",150.0).toFloat())
        }
        val resolution=resolvePlaceMatch(matches,point.accuracy)
        val record=resolution.place?.let {app.repository.dao.get("personalRecords",it.id)}
        return PlaceRecordResolution(record,resolution.confidence)
    }
    private suspend fun close(app:JamesApplication,anchor:uk.co.james.database.StoredRecord,end:Instant) {
        val d=anchor.data();val start=Instant.parse(d.text("start"));val minutes=visitMinutes(start,end)
        if(!completedVisit(minutes))return
        val anchored=d.text("placeId").takeIf {it.isNotBlank()}?.let {app.repository.dao.get("personalRecords",it)}
        val places=app.repository.dao.places()
        val spatialPlace=places.map {saved->
            val result=FloatArray(1);Location.distanceBetween(d.number("latitude"),d.number("longitude"),saved.data().number("latitude"),saved.data().number("longitude"),result)
            saved to result[0]
        }.filter {(saved,distance)->distance<=saved.data().number("radius",150.0)+100}.minByOrNull {it.second}?.first
        val place=anchored?:spatialPlace
        val title=place?.data()?.text("title")?.takeIf {it.isNotBlank()}?:"Unknown place"
        val category=place?.data()?.text("category")?.takeIf {it.isNotBlank()}?:"Unclassified"
        val id="visit:${start.epochSecond}"
        app.repository.save("personalRecords",personal("PlaceVisit",fields(
            "title" to p(title),"category" to p(category),"activity" to p("Unknown"),"start" to p(start.toString()),"end" to p(end.toString()),
            "durationMin" to p(minutes),"latitude" to p(d.number("latitude")),"longitude" to p(d.number("longitude")),
            "approximate" to p(true),"note" to p(""),"placeId" to p(place?.recordId?:""),
            "anchorId" to p(anchor.recordId),
            "ownership" to p(d.text("ownership",TimeOwnership.UNKNOWN.name)),"ownershipSource" to p(d.text("ownershipSource","INFERRED")),
            "context" to p("UNKNOWN"),"contextSource" to p("INFERRED")
        ),id,"gps",start.toString()).changed("externalId" to p(id),"confidence" to p(65)))
    }
}
