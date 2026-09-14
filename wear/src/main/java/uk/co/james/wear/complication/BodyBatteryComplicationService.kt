package uk.co.james.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.flow.first
import uk.co.james.wear.JamesWearApplication
import uk.co.james.wear.data.WearRepository
import uk.co.james.wear.ui.WearMainActivity

class BodyBatteryComplicationService:SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type:ComplicationType)=short(64,false)
    override suspend fun onComplicationRequest(request:ComplicationRequest):ComplicationData {val row=(application as JamesWearApplication).repository.dao.currentSnapshot();val stale=row==null||System.currentTimeMillis()-row.receivedAt>2*60*60_000L;val value=(application as JamesWearApplication).repository.snapshot.first().battery.value;return short(value,stale)}
    private fun short(value:Int?,stale:Boolean):ComplicationData {val tap=PendingIntent.getActivity(this,0,Intent(this,WearMainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);return ShortTextComplicationData.Builder(PlainComplicationText.Builder(if(value==null)"—" else "$value%${if(stale)"·" else ""}").build(),PlainComplicationText.Builder(if(stale)"James reserve, stale" else "James body battery").build()).setTitle(PlainComplicationText.Builder("JAMES").build()).setTapAction(tap).build()}
}
