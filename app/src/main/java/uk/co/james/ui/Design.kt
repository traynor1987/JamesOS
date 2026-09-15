package uk.co.james.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.*

internal val jamesLime=Color(0xFFB7F663)
internal val jamesBlue=Color(0xFF63C7F2)
internal val jamesAmber=Color(0xFFF4CF5D)
internal val jamesRed=Color(0xFFFF6F75)
internal val jamesInk=Color(0xFF0C1214)
internal val jamesPanel=Color(0xFF182124)

private val dark=darkColorScheme(
    primary=jamesLime,onPrimary=Color(0xFF10170D),primaryContainer=Color(0xFF20321D),onPrimaryContainer=Color(0xFFDDFFC5),
    secondary=jamesBlue,onSecondary=Color(0xFF06171D),secondaryContainer=Color(0xFF15313B),onSecondaryContainer=Color(0xFFC8F1FF),
    tertiary=jamesAmber,onTertiary=Color(0xFF201B05),error=jamesRed,onError=Color(0xFF220508),
    background=Color(0xFF080C0E),onBackground=Color(0xFFF3F6F5),surface=Color(0xFF111719),onSurface=Color(0xFFF3F6F5),
    surfaceVariant=jamesPanel,onSurfaceVariant=Color(0xFFA8B4B7),outline=Color(0xFF2A3538),outlineVariant=Color(0xFF202A2D),scrim=Color.Black
)
private val light=lightColorScheme(
    primary=Color(0xFF3E7012),onPrimary=Color.White,primaryContainer=Color(0xFFDDF5C6),onPrimaryContainer=Color(0xFF142905),
    secondary=Color(0xFF007C98),onSecondary=Color.White,secondaryContainer=Color(0xFFD2F1FA),onSecondaryContainer=Color(0xFF052A34),
    tertiary=Color(0xFF7A6100),onTertiary=Color.White,error=Color(0xFFB32632),onError=Color.White,
    background=Color(0xFFF2F5F3),onBackground=Color(0xFF111716),surface=Color(0xFFFCFEFC),onSurface=Color(0xFF111716),
    surfaceVariant=Color(0xFFE8EEEA),onSurfaceVariant=Color(0xFF596563),outline=Color(0xFFCCD5D0),outlineVariant=Color(0xFFDDE4E0)
)
private val jamesTypography=Typography(
    displaySmall=TextStyle(fontWeight=FontWeight.Black,fontSize=38.sp,lineHeight=42.sp,letterSpacing=(-.5).sp),
    headlineLarge=TextStyle(fontWeight=FontWeight.Black,fontSize=36.sp,lineHeight=40.sp,letterSpacing=(-.4).sp),
    headlineMedium=TextStyle(fontWeight=FontWeight.ExtraBold,fontSize=28.sp,lineHeight=33.sp),
    headlineSmall=TextStyle(fontWeight=FontWeight.ExtraBold,fontSize=24.sp,lineHeight=29.sp),
    titleLarge=TextStyle(fontWeight=FontWeight.ExtraBold,fontSize=21.sp,lineHeight=26.sp),
    titleMedium=TextStyle(fontWeight=FontWeight.Bold,fontSize=17.sp,lineHeight=22.sp),
    bodyLarge=TextStyle(fontWeight=FontWeight.Normal,fontSize=17.sp,lineHeight=25.sp),
    bodyMedium=TextStyle(fontWeight=FontWeight.Normal,fontSize=15.sp,lineHeight=22.sp),
    bodySmall=TextStyle(fontWeight=FontWeight.Medium,fontSize=13.sp,lineHeight=19.sp),
    labelLarge=TextStyle(fontWeight=FontWeight.Bold,fontSize=14.sp,lineHeight=18.sp),
    labelMedium=TextStyle(fontWeight=FontWeight.Bold,fontSize=12.sp,lineHeight=16.sp),
    labelSmall=TextStyle(fontWeight=FontWeight.Bold,fontSize=11.sp,lineHeight=15.sp,letterSpacing=.7.sp)
)
@Composable fun JamesTheme(theme: String,content: @Composable ()->Unit) {MaterialTheme(colorScheme=if(theme=="dark"||(theme=="system"&&isSystemInDarkTheme()))dark else light,typography=jamesTypography,shapes=Shapes(extraSmall=RoundedCornerShape(10.dp),small=RoundedCornerShape(14.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(30.dp)),content=content)}
@Composable fun JamesCard(title: String,tag: String?=null,onClick:(()->Unit)?=null,content: @Composable ColumnScope.()->Unit) {
    Card(
        Modifier.fillMaxWidth().then(if(onClick==null) Modifier else Modifier.clickable(onClick=onClick)),
        shape=RoundedCornerShape(22.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal=20.dp,vertical=19.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(4.dp).height(23.dp).background(MaterialTheme.colorScheme.primary,RoundedCornerShape(8.dp)))
                Text(title,style=MaterialTheme.typography.titleLarge)
            }
            if(tag!=null)Text(tag.uppercase(),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,letterSpacing=1.sp)
            content()
        }
    }
}
@Composable fun Muted(text: String){Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)}
@Composable fun PageTitle(title: String,eyebrow: String=""){Column(verticalArrangement=Arrangement.spacedBy(7.dp)){if(eyebrow.isNotBlank())Text(eyebrow.uppercase(),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,letterSpacing=1.5.sp);Text(title,style=MaterialTheme.typography.headlineLarge)}}
/**
 * Dashboard cards share a reusable slot type. Callers with conditional content can
 * supply semantic keys so remembered state never migrates when a card appears or
 * disappears. Giving every index a unique content type disabled lazy reuse and
 * caused a visible hitch at the Health Monitor / Right Now boundary.
 */
internal fun adaptiveCardContentType(index:Int)="dashboard-card"
@Composable fun AdaptiveCards(cards:List<@Composable ()->Unit>,keys:List<String>?=null) {
    require(keys==null||keys.size==cards.size)
    LazyVerticalGrid(columns=GridCells.Adaptive(320.dp),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.fillMaxSize()) {
        items(count=cards.size,key={index->keys?.get(index)?:index},contentType={adaptiveCardContentType(it)}) {index->
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){cards[index]()}
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DateControl(date: String,onDate: (String)->Unit) {var showing by remember {mutableStateOf(false)};OutlinedButton(onClick={showing=true}){Text(uk.co.james.core.displayDate(date))};if(showing){val state=rememberDatePickerState(initialSelectedDateMillis=LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),selectableDates=object:SelectableDates {
 override fun isSelectableDate(utcTimeMillis:Long)=utcTimeMillis<=LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
});DatePickerDialog(onDismissRequest={showing=false},confirmButton={TextButton(onClick={state.selectedDateMillis?.let {onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())};showing=false}){Text("Choose")}},dismissButton={TextButton(onClick={showing=false}){Text("Cancel")}}){DatePicker(state)}}}
