package uk.co.james.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun JamesRoot(vm: JamesViewModel,chooseImport:()->Unit,export:(String?)->Unit,requestHealth:(Set<String>)->Unit,requestLocation:()->Unit,requestActivity:()->Unit,openAppSettings:()->Unit,install:()->Unit) {
    val route by vm.route.collectAsStateWithLifecycle();val tab by vm.tab.collectAsStateWithLifecycle();val screen by vm.screenRecords.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle();val dialog by vm.dialog.collectAsStateWithLifecycle()
    val history by vm.historyReadiness.collectAsStateWithLifecycle()
    val data=screen.records
    val settingsDetail=JamesSettingsNavigation.isSettingsDetail(route)
    val backRoute=if(settingsDetail) "Settings" else tab
    val snack=remember {SnackbarHostState()}
    LaunchedEffect(vm){vm.message.filter {it.isNotBlank()}.collect {text->snack.showSnackbar(text);vm.message.value=""}}
    BackHandler(route!=tab || dialog.isNotEmpty()) {if(dialog.isNotEmpty())vm.close()else vm.navigate(backRoute)}
    val navColors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.onPrimaryContainer,selectedTextColor=MaterialTheme.colorScheme.primary,indicatorColor=MaterialTheme.colorScheme.primaryContainer,unselectedIconColor=MaterialTheme.colorScheme.onSurfaceVariant,unselectedTextColor=MaterialTheme.colorScheme.onSurfaceVariant)
    Scaffold(containerColor=MaterialTheme.colorScheme.background,contentColor=MaterialTheme.colorScheme.onBackground,topBar={TopAppBar(title={if(route==tab)JamesBrandLockup() else Text(route.substringAfter("Settings:",route),fontWeight=FontWeight.ExtraBold)},navigationIcon={if(route!=tab)IconButton(onClick={vm.navigate(backRoute)}){Icon(Icons.Outlined.ArrowBack,"Back")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background,titleContentColor=MaterialTheme.colorScheme.onBackground,navigationIconContentColor=MaterialTheme.colorScheme.onSurfaceVariant))},bottomBar={Column {HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant);NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {JamesPrimaryNavigation.destinations.forEach {destination->val icon=when(destination.route){"Today"->Icons.Outlined.WbSunny;"Timeline"->Icons.Outlined.Schedule;"Me"->Icons.Outlined.PersonOutline;"Insights"->Icons.Outlined.Insights;else->Icons.Outlined.Settings};NavigationBarItem(selected=tab==destination.route,onClick={vm.navigate(destination.route,true)},icon={Icon(icon,null)},label={Text(destination.label)},colors=navColors)}}}},snackbarHost={SnackbarHost(snack)}) {padding->
        Box(Modifier.fillMaxSize().padding(padding)) {when(route){
            "Today"->TodayScreen(vm,data,screen.loaded,history)
            "Timeline"->TimelineScreen(vm,data)
            "Me"->MeScreen(vm,data)
            "Routines"->RoutinesScreen(vm,data)
            "Life events","Journey","RUT Insights","Event history","Manage events","Daily notes","Calendar","Recovery space"->RutScreen(vm,data,route)
            "Life Balance"->LifeBalanceScreen(vm,data)
            "Insights","Weekly review"->InsightsScreen(vm,data)
            "Nova"->NovaScreen(vm,data,export)
            "Settings"->SettingsScreen(vm,export,install)
            "Settings:Display"->DisplaySettingsScreen(vm)
            "Settings:Watch"->WatchSettingsScreen(vm)
            "Settings:JamesOS"->JamesOsSettingsScreen(vm)
            "Settings:App"->AppSettingsScreen(vm,install)
            "Settings:Diagnostics"->DiagnosticsSettingsScreen(vm)
            "Calibration Lab"->CalibrationLabScreen(vm)
            "Calibrate James OS"->CalibrationLabScreen(vm)
            "Connections"->ConnectionsScreen(vm,requestHealth)
            "Location"->LocationScreen(vm,data,requestLocation,requestActivity,openAppSettings)
            "Location map"->LocationMapScreen(vm,data)
            "Import Centre"->ImportScreen(vm,chooseImport,export)
            else->when {route.startsWith("Algorithm:")->AlgorithmDetailScreen(vm,route.substringAfter("Algorithm:"));route.startsWith("Calibration:")->CalibrationLabScreen(vm,route.substringAfter("Calibration:"));else->TodayScreen(vm,data,screen.loaded,history)}
        };if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())}
    }
    if(dialog.isNotEmpty())RecordEditor(vm)
}
