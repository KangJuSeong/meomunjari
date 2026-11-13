package com.example.meomunjari.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.meomunjari.ui.theme.MColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.meomunjari.di.AppContainer
import com.example.meomunjari.domain.DriveState
import com.example.meomunjari.ui.screens.ArrivalScreen
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.ui.screens.ChargingScreen
import com.example.meomunjari.ui.screens.CampingScreen
import com.example.meomunjari.ui.screens.DrivingScreen
import com.example.meomunjari.ui.screens.HomeScreen
import com.example.meomunjari.ui.screens.MyScreen
import com.example.meomunjari.ui.screens.RegisterScreen
import com.example.meomunjari.ui.screens.ReviewScreen
import com.example.meomunjari.ui.screens.SearchScreen
import com.example.meomunjari.ui.screens.SpotDetailScreen
import com.example.meomunjari.ui.screens.SunriseScreen
import com.example.meomunjari.ui.screens.VirtualConsolePanel

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

object Routes {
    const val HOME = "home"
    const val SEARCH = "search?tags={tags}"
    const val SPOT = "spot/{id}"
    const val ARRIVAL = "arrival"
    const val CAMPING = "camping"
    const val REVIEW = "review"
    const val REGISTER = "register"
    const val MY = "my"
    const val SUNRISE = "sunrise"

    fun search(tags: List<String> = emptyList()) =
        if (tags.isEmpty()) "search" else "search?tags=${Uri.encode(tags.joinToString(","))}"

    fun spot(id: String) = "spot/$id"
}

@Composable
fun MeomunjariRoot(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        val nav = rememberNavController()
        val trip = container.trip
        val driveState by trip.driveState.collectAsStateWithLifecycle()
        val station by trip.arrivedStop.collectAsStateWithLifecycle()
        val vehicle by trip.vehicle.collectAsStateWithLifecycle()
        val destination by trip.destination.collectAsStateWithLifecycle()
        val certified by trip.certifiedVisit.collectAsStateWithLifecycle()
        var consoleOpen by rememberSaveable { mutableStateOf(false) }

        // 도착 인증이 되면 차박 모드 화면으로 안내한다.
        LaunchedEffect(certified?.id) {
            // 리뷰를 쓰는 중이면 화면을 빼앗지 않는다.
            val onReview = nav.currentDestination?.route == Routes.REVIEW
            if (certified != null && trip.camping.value == null && !onReview) {
                nav.navigate(Routes.ARRIVAL) { launchSingleTop = true }
            }
        }
        // 주행 중 음성으로 고른 태그는 정차 후 검색 결과로 보여준다.
        LaunchedEffect(driveState) {
            if (driveState == DriveState.PARKED) {
                val tags = trip.consumeVoiceTags()
                if (tags.isNotEmpty()) nav.navigate(Routes.search(tags)) { launchSingleTop = true }
            }
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val stop = station
            val spot = destination
            if (stop != null && spot != null && driveState == DriveState.PARKED &&
                vehicle.connected && vehicle.gear == Gear.PARK) {
                ChargingScreen(stop, spot)
            } else Row(Modifier.fillMaxSize()) {
                AppRail(nav)
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 32.dp, top = 30.dp),
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onSpot = { nav.navigate(Routes.spot(it)) },
                            onTag = { nav.navigate(Routes.search(listOf(it))) },
                            onRegister = { nav.navigate(Routes.REGISTER) },
                            onCamping = { nav.navigate(Routes.CAMPING) },
                        )
                    }
                    composable(
                        Routes.SEARCH,
                        arguments = listOf(navArgument("tags") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { entry ->
                        val tags = entry.arguments?.getString("tags")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                        SearchScreen(initialTags = tags, onSpot = { nav.navigate(Routes.spot(it)) })
                    }
                    composable(Routes.SPOT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                        SpotDetailScreen(
                            spotId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.ARRIVAL) {
                        ArrivalScreen(
                            onStarted = {
                                nav.navigate(Routes.CAMPING) { popUpTo(Routes.ARRIVAL) { inclusive = true } }
                            },
                            onRegister = { nav.navigate(Routes.REGISTER) },
                            onSunrise = { nav.navigate(Routes.SUNRISE) },
                        )
                    }
                    composable(Routes.SUNRISE) {
                        SunriseScreen(onBack = { nav.popBackStack() })
                    }
                    composable(Routes.CAMPING) {
                        CampingScreen(
                            onFinished = {
                                nav.navigate(Routes.REVIEW) { popUpTo(Routes.CAMPING) { inclusive = true } }
                            },
                        )
                    }
                    composable(Routes.REVIEW) {
                        ReviewScreen(onDone = { nav.navigate(Routes.MY) { popUpTo(Routes.HOME) } })
                    }
                    composable(Routes.REGISTER) {
                        RegisterScreen(
                            onBack = { nav.popBackStack() },
                            onRegistered = { id ->
                                nav.navigate(Routes.spot(id)) { popUpTo(Routes.HOME) }
                            },
                        )
                    }
                    composable(Routes.MY) { MyScreen() }
                }
            }

            // 주행 중에는 검색·리뷰·사진을 막고 내비 정보만 보여준다.
            if (driveState == DriveState.DRIVING) {
                DrivingScreen()
            }

            container.gateways.console?.let { console ->
                if (consoleOpen) {
                    VirtualConsolePanel(
                        console = console,
                        onClose = { consoleOpen = false },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                } else {
                    ExtendedFloatingActionButton(
                        onClick = { consoleOpen = true },
                        icon = { Icon(Icons.Filled.Tune, null) },
                        text = { Text("가상 차량 콘솔", fontWeight = FontWeight.Bold) },
                        containerColor = MColors.Text,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRail(nav: NavHostController) {
    val trip = LocalAppContainer.current.trip
    val camping by trip.camping.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    data class Item(val route: String, val target: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

    val items = listOf(
        Item(Routes.HOME, Routes.HOME, "홈", Icons.Filled.Home),
        Item(Routes.SEARCH, Routes.search(), "찾기", Icons.Filled.Search),
        Item(
            if (camping != null) Routes.CAMPING else Routes.ARRIVAL,
            if (camping != null) Routes.CAMPING else Routes.ARRIVAL,
            "차박",
            Icons.Filled.Nightlight,
        ),
        Item(Routes.MY, Routes.MY, "마이", Icons.Filled.EmojiEvents),
    )

    NavigationRail(containerColor = MColors.Card, modifier = Modifier.fillMaxHeight().width(120.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MColors.Blue),
            contentAlignment = Alignment.Center,
        ) {
            Text("머", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.weight(1f)) {
            items.forEach { item ->
                NavigationRailItem(
                    selected = route == item.route,
                    onClick = {
                        if (item.target == Routes.HOME) {
                            // 홈은 시작 화면이라 saveState/restoreState를 쓰면 방금 떠난 화면(마이 등)이
                            // 홈의 저장 상태로 다시 복원된다. 홈 위에 쌓인 화면을 모두 걷어내기만 한다.
                            if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
                                nav.navigate(Routes.HOME) { launchSingleTop = true }
                            }
                        } else {
                            nav.navigate(item.target) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(28.dp)) },
                    label = { Text(item.label, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MColors.Text,
                        selectedTextColor = MColors.Text,
                        indicatorColor = MColors.Grey100,
                        unselectedIconColor = MColors.Grey400,
                        unselectedTextColor = MColors.Grey500,
                    ),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}