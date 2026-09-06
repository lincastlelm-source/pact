package com.pact.coach.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.di.AppContainer
import com.pact.coach.presentation.screens.BehaviorDetailScreen
import com.pact.coach.presentation.screens.BehaviorEditScreen
import com.pact.coach.presentation.screens.GoalEditScreen
import com.pact.coach.presentation.screens.GoalsScreen
import com.pact.coach.presentation.screens.HomeScreen
import com.pact.coach.presentation.screens.InsightsScreen
import com.pact.coach.presentation.screens.InterventionScreen
import com.pact.coach.presentation.screens.OnboardingScreen
import com.pact.coach.presentation.screens.SettingsScreen
import com.pact.coach.presentation.screens.TodayScreen
import com.pact.coach.presentation.viewmodels.BehaviorDetailViewModel
import com.pact.coach.presentation.viewmodels.BehaviorEditViewModel
import com.pact.coach.presentation.viewmodels.GoalEditViewModel
import com.pact.coach.presentation.viewmodels.GoalsViewModel
import com.pact.coach.presentation.viewmodels.HomeViewModel
import com.pact.coach.presentation.viewmodels.InsightsViewModel
import com.pact.coach.presentation.viewmodels.InterventionViewModel
import com.pact.coach.presentation.viewmodels.OnboardingViewModel
import com.pact.coach.presentation.viewmodels.SettingsViewModel

/**
 * Routes.
 *
 * Five destinations in the bottom bar, everything else pushed on top. The structure is
 * deliberately shallow: the app's job is to be answerable in seconds, and deep navigation is the
 * enemy of that.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val TODAY = "today"
    const val GOALS = "goals"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"

    const val GOAL_EDIT = "goal_edit"
    const val BEHAVIOR_EDIT = "behavior_edit"
    const val BEHAVIOR_DETAIL = "behavior_detail"
    const val INTERVENTION = "intervention"

    fun goalEdit(goalId: String? = null) =
        if (goalId == null) "$GOAL_EDIT?goalId=" else "$GOAL_EDIT?goalId=$goalId"

    fun behaviorEdit(behaviorId: String? = null, goalId: String? = null) =
        "$BEHAVIOR_EDIT?behaviorId=${behaviorId.orEmpty()}&goalId=${goalId.orEmpty()}"

    fun behaviorDetail(behaviorId: String) = "$BEHAVIOR_DETAIL/$behaviorId"

    fun intervention(instanceId: String) = "$INTERVENTION/$instanceId"
}

private data class TabDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabDestination(Routes.HOME, "Home", Icons.Outlined.Home),
    TabDestination(Routes.TODAY, "Today", Icons.Outlined.CalendarToday),
    TabDestination(Routes.GOALS, "Goals", Icons.Outlined.Flag),
    TabDestination(Routes.INSIGHTS, "Insights", Icons.Outlined.BarChart),
    TabDestination(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
fun PactApp(
    container: AppContainer,
    settings: AppSettings,
    startInterventionId: String?,
    onInterventionConsumed: () -> Unit,
) {
    val navController = rememberNavController()

    // A notification tap must land on the intervention, whatever was on screen before.
    LaunchedEffect(startInterventionId) {
        if (startInterventionId != null) {
            navController.navigate(Routes.intervention(startInterventionId))
            onInterventionConsumed()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val showBottomBar = tabs.any { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (settings.onboardingComplete) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                val vm: OnboardingViewModel = viewModel(factory = container.factory())
                OnboardingScreen(
                    viewModel = vm,
                    onCreateBehavior = { goalId ->
                        navController.navigate(Routes.behaviorEdit(goalId = goalId))
                    },
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(factory = container.factory())
                HomeScreen(
                    viewModel = vm,
                    settings = settings,
                    onOpenIntervention = { navController.navigate(Routes.intervention(it)) },
                    onOpenBehavior = { navController.navigate(Routes.behaviorDetail(it)) },
                    onAddBehavior = { navController.navigate(Routes.behaviorEdit()) },
                    onSeeToday = { navController.navigate(Routes.TODAY) },
                )
            }

            composable(Routes.TODAY) {
                val vm: HomeViewModel = viewModel(factory = container.factory())
                TodayScreen(
                    viewModel = vm,
                    settings = settings,
                    onOpenIntervention = { navController.navigate(Routes.intervention(it)) },
                    onOpenBehavior = { navController.navigate(Routes.behaviorDetail(it)) },
                )
            }

            composable(Routes.GOALS) {
                val vm: GoalsViewModel = viewModel(factory = container.factory())
                GoalsScreen(
                    viewModel = vm,
                    onAddGoal = { navController.navigate(Routes.goalEdit()) },
                    onEditGoal = { navController.navigate(Routes.goalEdit(it)) },
                    onAddBehavior = { goalId ->
                        navController.navigate(Routes.behaviorEdit(goalId = goalId))
                    },
                    onOpenBehavior = { navController.navigate(Routes.behaviorDetail(it)) },
                )
            }

            composable(Routes.INSIGHTS) {
                val vm: InsightsViewModel = viewModel(factory = container.factory())
                InsightsScreen(
                    viewModel = vm,
                    settings = settings,
                    onOpenBehavior = { navController.navigate(Routes.behaviorDetail(it)) },
                )
            }

            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = container.factory())
                SettingsScreen(viewModel = vm)
            }

            composable(
                route = "${Routes.GOAL_EDIT}?goalId={goalId}",
                arguments = listOf(
                    navArgument("goalId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val goalId = entry.arguments?.getString("goalId").orEmpty().ifBlank { null }
                val vm: GoalEditViewModel = viewModel(factory = container.factory(goalId = goalId))
                GoalEditScreen(
                    viewModel = vm,
                    onDone = { navController.popBackStack() },
                    onAddBehavior = { id ->
                        navController.navigate(Routes.behaviorEdit(goalId = id))
                    },
                )
            }

            composable(
                route = "${Routes.BEHAVIOR_EDIT}?behaviorId={behaviorId}&goalId={goalId}",
                arguments = listOf(
                    navArgument("behaviorId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("goalId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val behaviorId = entry.arguments?.getString("behaviorId").orEmpty().ifBlank { null }
                val goalId = entry.arguments?.getString("goalId").orEmpty().ifBlank { null }
                val vm: BehaviorEditViewModel = viewModel(
                    factory = container.factory(behaviorId = behaviorId, goalId = goalId),
                )
                BehaviorEditScreen(
                    viewModel = vm,
                    settings = settings,
                    onDone = { navController.popBackStack() },
                )
            }

            composable(
                route = "${Routes.BEHAVIOR_DETAIL}/{behaviorId}",
                arguments = listOf(navArgument("behaviorId") { type = NavType.StringType }),
            ) { entry ->
                val behaviorId = entry.arguments?.getString("behaviorId").orEmpty()
                val vm: BehaviorDetailViewModel = viewModel(
                    factory = container.factory(behaviorId = behaviorId),
                )
                BehaviorDetailScreen(
                    viewModel = vm,
                    settings = settings,
                    onEdit = { navController.navigate(Routes.behaviorEdit(behaviorId = it)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "${Routes.INTERVENTION}/{instanceId}",
                arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
            ) { entry ->
                val instanceId = entry.arguments?.getString("instanceId").orEmpty()
                val vm: InterventionViewModel = viewModel(
                    factory = container.factory(instanceId = instanceId),
                )
                InterventionScreen(
                    viewModel = vm,
                    onClose = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.HOME)
                        }
                    },
                )
            }
        }
    }
}

/**
 * One factory for every view model in the app.
 *
 * With manual DI there is no generated graph, so construction is explicit. Screen arguments are
 * passed straight in rather than pulled from SavedStateHandle: it keeps the view models plain
 * classes that a unit test can instantiate directly.
 */
fun AppContainer.factory(
    goalId: String? = null,
    behaviorId: String? = null,
    instanceId: String? = null,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val container = this@factory
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(container)

            modelClass.isAssignableFrom(GoalsViewModel::class.java) ->
                GoalsViewModel(container)

            modelClass.isAssignableFrom(GoalEditViewModel::class.java) ->
                GoalEditViewModel(container, goalId)

            modelClass.isAssignableFrom(BehaviorEditViewModel::class.java) ->
                BehaviorEditViewModel(container, behaviorId, goalId)

            modelClass.isAssignableFrom(BehaviorDetailViewModel::class.java) ->
                BehaviorDetailViewModel(container, behaviorId.orEmpty())

            modelClass.isAssignableFrom(InterventionViewModel::class.java) ->
                InterventionViewModel(container, instanceId.orEmpty())

            modelClass.isAssignableFrom(InsightsViewModel::class.java) ->
                InsightsViewModel(container)

            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(container)

            modelClass.isAssignableFrom(OnboardingViewModel::class.java) ->
                OnboardingViewModel(container)

            else -> throw IllegalArgumentException("Unknown view model: ${modelClass.name}")
        } as T
    }
}

/** Kept so the nav host can be previewed without a real container. */
@Composable
internal fun rememberPactNavController(): NavHostController = rememberNavController()
