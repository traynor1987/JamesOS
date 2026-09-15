package uk.co.james.location

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import uk.co.james.core.*
import uk.co.james.data.JamesRepository
import uk.co.james.settings.Preferences
import uk.co.james.JamesApplication
import uk.co.james.sync.BackgroundJobs
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import java.time.Instant

@Suppress("MissingPermission") // Each public operation checks its runtime permissions before calling Play services.
class LocationSource(private val context: Context,private val repo: JamesRepository,private val preferences: Preferences) {
    fun precise()=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    fun background()=Build.VERSION.SDK_INT<29 || ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun pending(receiver: Class<*>,code: Int): PendingIntent = PendingIntent.getBroadcast(context,code,Intent(context,receiver),PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0)
    suspend fun setEnabled(enabled: Boolean) {
        if(enabled){require(precise() && background()) { "Allow precise location and 'Allow all the time' in Android app settings first." };preferences.location(true);try{restore()}catch(e: Exception){preferences.location(false);throw e}}
        else {preferences.location(false);LocationServices.getGeofencingClient(context).removeGeofences(pending(PlaceReceiver::class.java,101)).await()}
    }
    suspend fun setAllDay(enabled:Boolean) {
        if(enabled) {
            require(precise()&&background()){"Allow precise location and 'Allow all the time' in Android app settings first."}
            preferences.allDay(true)
            try {AllDayLocationService.start(context)} catch(e:Exception) {preferences.allDay(false);throw e}
        } else {preferences.allDay(false);AllDayLocationService.stop(context)}
    }
    /** Explicit calibration deliberately does not reuse an arbitrary passive
     * sample.  It may reuse a demonstrably fresh/accurate anchor; otherwise it
     * asks Fused Location for one high-accuracy fix and keeps the low-power
     * visit tracker untouched. */
    suspend fun addCurrentPlace(name: String,category:String="Unclassified",onGettingPreciseFix:()->Unit = {}): CalibrationFix {
        require(name.isNotBlank() && precise()) { "Name the place and grant precise location first." }
        val now=Instant.now()
        val passive=passiveCalibrationFix()
        val result=if(requiresPrecisePlaceCalibrationFix(passive,now)) {
            onGettingPreciseFix()
            val attempt=requestPreciseCalibrationFix()
            selectPlaceCalibrationFix(passive,attempt.fix,attempt.failure,now)
        } else selectPlaceCalibrationFix(passive,null,null,now)
        val fix=(result as? PlaceCalibrationFixResult.Accepted)?.fix
            ?: throw IllegalArgumentException((result as PlaceCalibrationFixResult.Rejected).message)
        val placeId="place:${id()}"
        val place=personal("Place",fields("title" to p(name.trim()),"category" to p(category),"latitude" to p(fix.latitude),"longitude" to p(fix.longitude),"radius" to p(150)),placeId,"manual")
        repo.save("personalRecords",place)
        // A place may be saved just after leaving it. Give completed, unnamed visits
        // nearby the new label rather than making James wait for a future visit.
        relabelNearbyUnknownVisits(place,placeId)
        if(preferences.location.first())restore()
        return fix
    }
    private data class PreciseFixAttempt(val fix:CalibrationFix?,val failure:CalibrationFixFailure?)
    private suspend fun passiveCalibrationFix(): CalibrationFix? {
        val anchor=repo.dao.currentLocationAnchor("location:current-anchor")?:return null
        val data=anchor.data()
        val latitude=data.number("latitude")
        val longitude=data.number("longitude")
        val accuracy=data.number("accuracy").toFloat()
        val observedAt=runCatching { Instant.parse(data.text("lastSeen",data.text("start"))) }.getOrNull()?:return null
        return CalibrationFix(latitude,longitude,accuracy,observedAt,CalibrationFixSource.PASSIVE_ANCHOR)
            .takeIf { latitude!=0.0 || longitude!=0.0 }
    }
    private suspend fun requestPreciseCalibrationFix(): PreciseFixAttempt {
        val token=CancellationTokenSource()
        return try {
            val point=withTimeout(20_000) {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,token.token).await()
            }
            if(point==null) PreciseFixAttempt(null,CalibrationFixFailure.TIMEOUT)
            else PreciseFixAttempt(
                CalibrationFix(point.latitude,point.longitude,point.accuracy,
                    point.time.takeIf {it>0}?.let(Instant::ofEpochMilli)?:Instant.now(),
                    CalibrationFixSource.FUSED_CURRENT),null)
        } catch(_:TimeoutCancellationException) {PreciseFixAttempt(null,CalibrationFixFailure.TIMEOUT)}
        catch(_:SecurityException) {PreciseFixAttempt(null,CalibrationFixFailure.PERMISSION_MISSING)}
        catch(_:Exception) {PreciseFixAttempt(null,CalibrationFixFailure.PROVIDER_UNAVAILABLE)}
        finally {token.cancel()}
    }
    suspend fun labelVisit(visitId:String,placeId:String) {
        val visit=repo.dao.get("personalRecords",visitId)?:return
        val place=repo.dao.get("personalRecords",placeId)?.takeIf {it.kind=="Place"}?:return
        if(visit.kind!="PlaceVisit")return
        saveVisitWithPlace(visit,place.data(),place.recordId)
    }
    private suspend fun relabelNearbyUnknownVisits(place:JsonObject,placeId:String) {
        val placeData=place.obj("data")
        // A new saved Place only labels recent unknown visits.  Historic editing is
        // explicit; saving Home today must not rewrite years of private evidence.
        repo.dao.visitsBetween(java.time.Instant.now().minus(java.time.Duration.ofDays(14)).toString(),java.time.Instant.now().toString()).asSequence()
            .filter { it.data().text("title","Unknown place").let { title->title.isBlank()||title=="Unknown place" } }
            .filter { visit->
                val result=FloatArray(1)
                val data=visit.data()
                Location.distanceBetween(data.number("latitude"),data.number("longitude"),placeData.number("latitude"),placeData.number("longitude"),result)
                result[0]<=placeData.number("radius",150.0)+100
            }.forEach { visit->saveVisitWithPlace(visit,placeData,placeId) }
    }
    private suspend fun saveVisitWithPlace(visit:uk.co.james.database.StoredRecord,place:JsonObject,placeId:String) {
        val data=visit.data().changed(
            "title" to p(place.text("title")),
            "category" to p(place.text("category","Unclassified")),
            "placeId" to p(placeId)
        )
        val raw=personal("PlaceVisit",data,visit.recordId,visit.source,visit.timestamp)
            .changed("externalId" to p(visit.externalId?:visit.recordId),"confidence" to p(visit.raw().number("confidence",65.0)),"updatedAt" to p(now()))
        repo.save("personalRecords",raw,visit.rawJson)
    }
    @Suppress("MissingPermission")
    suspend fun restore() {
        if(!preferences.location.first())return
        if(!precise() || !background()){preferences.location(false);return}
        val places=repo.dao.places(50)
        val client=LocationServices.getGeofencingClient(context)
        client.removeGeofences(pending(PlaceReceiver::class.java,101)).await()
        if(places.isNotEmpty()) {
            val fences=places.map { p -> val d=p.data();Geofence.Builder().setRequestId(p.recordId).setCircularRegion(d.number("latitude"),d.number("longitude"),d.number("radius",150.0).toFloat()).setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).setNotificationResponsiveness(120000).build() }
            client.addGeofences(GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build(),pending(PlaceReceiver::class.java,101)).await()
        }
    }
    @Suppress("MissingPermission")
    suspend fun setActivity(enabled: Boolean) {
        if(enabled)require(Build.VERSION.SDK_INT<29 || ContextCompat.checkSelfPermission(context,Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED) { "Grant physical activity permission first." }
        val client=ActivityRecognition.getClient(context)
        if(!enabled){preferences.activity(false);client.removeActivityTransitionUpdates(pending(MotionReceiver::class.java,102)).await();return}
        val transitions=listOf(DetectedActivity.IN_VEHICLE,DetectedActivity.WALKING,DetectedActivity.RUNNING).flatMap { type -> listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER,ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { t -> ActivityTransition.Builder().setActivityType(type).setActivityTransition(t).build() } }
        client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions),pending(MotionReceiver::class.java,102)).await()
        preferences.activity(true)
    }
}
class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val event=GeofencingEvent.fromIntent(intent)?:return;if(event.hasError())return
        val pending=goAsync();val app=context.applicationContext as JamesApplication
        CoroutineScope(Dispatchers.IO).launch {try {if(!app.preferences.location.first())return@launch
            for(fence in event.triggeringGeofences.orEmpty()) {val place=app.repository.dao.get("personalRecords",fence.requestId)?:continue
                val transition=if(event.geofenceTransition==Geofence.GEOFENCE_TRANSITION_ENTER)"Arrived at" else "Left"
                val key="place:${place.recordId}:${event.geofenceTransition}:${System.currentTimeMillis()/60000}"
                app.repository.external(personal("LocationEvent",fields("title" to p("$transition ${place.data().text("title")}"),"placeId" to p(place.recordId),"date" to p(today()),"approximate" to p(true)),key,"gps").changed("confidence" to p(70),"externalId" to p(key)))
            }
        } catch(e:Exception) { android.util.Log.w("JamesLocation","Place event could not be persisted") } finally {pending.finish()} }
    }
}
class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val result=ActivityTransitionResult.extractResult(intent)?:return
        val pending=goAsync();val app=context.applicationContext as JamesApplication
        CoroutineScope(Dispatchers.IO).launch {try {
            if(!app.preferences.activity.first())return@launch
            for(e in result.transitionEvents) {
                val activity=when(e.activityType){DetectedActivity.IN_VEHICLE->"Driving";DetectedActivity.RUNNING->"Running";else->"Walking"}
                val instant=java.time.Instant.now()
                val openId="motion:open:${e.activityType}"
                if(e.transitionType==ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    app.repository.save("personalRecords",personal("MotionAnchor",fields("activity" to p(activity),"start" to p(instant.toString())),openId,"android",instant.toString()))
                } else {
                    app.repository.dao.get("personalRecords",openId)?.let {open->
                        val start=runCatching {java.time.Instant.parse(open.data().text("start"))}.getOrNull()
                        val minutes=start?.let {java.time.Duration.between(it,instant).toMinutes()}?:0
                        if(start!=null&&minutes>=2) {
                            val blockId="motion-block:${e.activityType}:${start.epochSecond}"
                            val category=if(activity=="Driving")"Driving" else "Exercise"
                            app.repository.save("personalRecords",personal("TimeBlock",fields("category" to p(category),"activity" to p(activity),"end" to p(instant.toString()),"durationMin" to p(minutes),"approximate" to p(true),"note" to p("Likely $activity detected by Android activity recognition.")),blockId,"android",start.toString()).changed("confidence" to p(60),"externalId" to p(blockId)))
                        }
                        app.repository.dao.delete("personalRecords",openId)
                    }
                }
                val state=if(e.transitionType==ActivityTransition.ACTIVITY_TRANSITION_ENTER)"started" else "ended"
                val key="motion:${e.activityType}:${e.transitionType}:${e.elapsedRealTimeNanos}"
                app.repository.external(personal("LocationEvent",fields("title" to p("$activity likely $state"),"date" to p(today()),"approximate" to p(true)),key,"android").changed("confidence" to p(60),"externalId" to p(key)))
            }
        }catch(e:Exception){android.util.Log.w("JamesLocation","Motion event could not be persisted")}finally{pending.finish()}}}
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent){BackgroundJobs.restorePlaces(context);val pending=goAsync();val app=context.applicationContext as JamesApplication;CoroutineScope(Dispatchers.IO).launch {try {if(app.preferences.allDay.first())AllDayLocationService.start(context)}catch(e:Exception){android.util.Log.w("JamesLocation","All-day tracking could not restart")}finally{pending.finish()}}}
}
