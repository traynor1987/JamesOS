package uk.co.james.ui

/**
 * Directory contract for Settings. Keep provider management in Connections and
 * import/restore in Import Centre; Settings is the discoverable entry point.
 */
data class SettingsDestination(val id:String,val title:String,val route:String,val keywords:Set<String>)

object JamesSettingsNavigation {
    val destinations=listOf(
        SettingsDestination("display","Display & Today","Settings:Display",setOf("theme","appearance","compact","classic","today layout")),
        SettingsDestination("connections","Connections & Data","Connections",setOf("health connect","whoop","provider","nutrition","permissions","shift tracker")),
        SettingsDestination("watch","Watch & Sensors","Settings:Watch",setOf("watch","wear","sensor","sync")),
        SettingsDestination("places","Places & Context","Location",setOf("location","place","movement","context")),
        SettingsDestination("james-os","James OS & Calibration","Settings:JamesOS",setOf("algorithm","calibration","energy","wellbeing")),
        SettingsDestination("backup","Data, Backup & Import","Import Centre",setOf("backup","export","import","restore")),
        SettingsDestination("updates","App & Updates","Settings:App",setOf("update","version","release","about")),
        SettingsDestination("diagnostics","Advanced & Diagnostics","Settings:Diagnostics",setOf("diagnostics","status","maintenance","troubleshooting"))
    )

    val detailRoutes=destinations.map {it.route}.toSet()+setOf("Location map","Calibration Lab","Calibrate James OS")
    fun isSettingsDetail(route:String)=route in detailRoutes||route.startsWith("Algorithm:")||route.startsWith("Calibration:")
}
