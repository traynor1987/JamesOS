package uk.co.james.ui

/** The stable top-level product destinations. Keep experimental routes out of this list. */
data class PrimaryNavigationDestination(
    val route:String,
    val label:String,
    val settingsHome:Boolean=false
)

object JamesPrimaryNavigation {
    val destinations=listOf(
        PrimaryNavigationDestination("Today", "Today"),
        PrimaryNavigationDestination("Timeline", "Timeline"),
        PrimaryNavigationDestination("Me", "Me"),
        PrimaryNavigationDestination("Insights", "Insights"),
        PrimaryNavigationDestination("Settings", "Settings", settingsHome=true)
    )
}
