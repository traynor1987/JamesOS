package uk.co.james.wear.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders.StringProp
import androidx.wear.tiles.RequestBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import uk.co.james.wear.JamesWearApplication
import uk.co.james.wear.data.parseSnapshot
import uk.co.james.wear.data.isSnapshotStale

class JamesTileService:TileService() {
    override fun onTileRequest(request:RequestBuilders.TileRequest)=Futures.immediateFuture(buildTile())
    override fun onTileResourcesRequest(request:RequestBuilders.ResourcesRequest):ListenableFuture<ResourceBuilders.Resources> = Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
    private fun text(value:String,size:Float,color:Int)=LayoutElementBuilders.Text.Builder().setText(StringProp.Builder(value).build()).setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build()).build()
    private fun buildTile():TileBuilders.Tile {val repo=(application as JamesWearApplication).repository;val (row,stress)=runBlocking {repo.dao.currentSnapshot() to repo.dao.recent("james_stress",1).first().firstOrNull()};val snap=row?.let {runCatching {parseSnapshot(it.json)}.getOrNull()};val stale=isSnapshotStale(row?.receivedAt?:0L);val battery=snap?.battery?.value?.let{"$it%${if(stale)"·" else ""}"}?:"—";val launch=ActionBuilders.LaunchAction.Builder().setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName("uk.co.james.wear.ui.WearMainActivity").build()).build();val clickable=ModifiersBuilders.Clickable.Builder().setId("open_james").setOnClick(launch).build();val root=LayoutElementBuilders.Column.Builder().setWidth(androidx.wear.protolayout.DimensionBuilders.expand()).setHeight(androidx.wear.protolayout.DimensionBuilders.expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build())
        .addContent(text("JAMES BODY BATTERY",13f,0xFFAEF76E.toInt())).addContent(text(battery,44f,0xFFFFFFFF.toInt())).addContent(text(if(stale)"STALE · OPEN TO SYNC" else snap?.battery?.headline?.uppercase()?:"WAITING FOR PHONE",16f,if(stale)0xFFFFCF67.toInt() else 0xFFFFFFFF.toInt())).addContent(text("Recovery ${snap?.recovery?.value?.toInt()?.let{"$it%"}?:"—"}  ·  Strain ${snap?.strain?.value?.let{"%.1f".format(it)}?:"—"}",12f,0xFF9EAAAD.toInt())).addContent(text("Steps ${snap?.steps?.value?.toLong()?.let{"%,d".format(it)}?:"—"}",12f,0xFF9EAAAD.toInt())).addContent(text("James Stress ${stress?.value?.toInt()?.let{"$it / 100"}?:"—"} · experimental",12f,0xFF9EAAAD.toInt())).build()
        return TileBuilders.Tile.Builder().setResourcesVersion("1").setTileTimeline(TimelineBuilders.Timeline.Builder().addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build()).build()).build()).setFreshnessIntervalMillis(15*60_000L).build()}
}
