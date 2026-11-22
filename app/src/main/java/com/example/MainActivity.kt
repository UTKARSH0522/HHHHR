package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.core.BrandConfig
import com.example.core.UiState
import com.example.data.auth.authStateFlow
import com.example.data.auth.signOut
import com.example.data.repository.AdminRepository
import com.example.data.repository.BookingPaymentRepository
import com.example.data.repository.ExpertRepository
import com.example.data.repository.MarketplaceRepository
import com.example.data.repository.UserAccountRepository
import com.example.notifications.HRConnectMessagingService
import com.example.ui.HRConnectViewModel
import com.example.ui.components.HRConnectMonogramBadge
import com.example.ui.components.LegalPoliciesDialog
import com.example.ui.components.NotificationsDialog
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CustomerDashboardScreen
import com.example.ui.screens.ExpertPortalScreen
import com.example.ui.screens.ExpertProfileBookingScreen
import com.example.ui.screens.HomeMarketplaceScreen
import com.example.ui.theme.HRConnectTheme
import com.example.ui.theme.HRConnectThemeExtras
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject

const val ROUTE_EXPLORE = "explore"
const val ROUTE_HUB = "hub"
const val ROUTE_EXPERT_PORTAL = "expert_portal"
const val ROUTE_ADMIN = "admin"
const val ROUTE_EXPERT_DETAIL = "expert_detail/{expertId}"

private data class NavDestinationItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity(), PaymentResultWithDataListener {
    internal var activeRazorpayViewModel: HRConnectViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { Checkout.preload(applicationContext) }
        HRConnectMessagingService.ensureNotificationChannel(applicationContext)
        setContent {
            HRConnectTheme {
                HRConnectApp()
            }
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        activeRazorpayViewModel?.onRazorpaySdkSuccess(razorpayPaymentId, paymentData)
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        activeRazorpayViewModel?.onRazorpaySdkError(code, response)
    }
}

@Composable
fun HRConnectApp() {
    val authState by Firebase.auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    val user = authState
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
        return
    }

    key(user.uid) {
        val uid = user.uid
        val email = user.email ?: ""
        val displayName = user.displayName ?: "HRConnect Member"

        val viewModel: HRConnectViewModel = viewModel(
            key = uid,
            factory = viewModelFactory {
                initializer {
                    val app = this[APPLICATION_KEY]
                        ?: throw IllegalStateException("Application context missing")
                    val databaseId = app.getString(R.string.firestore_database_id)
                    val db = FirebaseFirestore.getInstance(databaseId)
                    HRConnectViewModel(
                        currentUserId = uid,
                        currentUserEmail = email,
                        currentUserDisplayName = displayName,
                        marketplaceRepo = MarketplaceRepository(db),
                        bookingRepo = BookingPaymentRepository(db),
                        expertRepo = ExpertRepository(db),
                        userRepo = UserAccountRepository(db),
                        adminRepo = AdminRepository(db)
                    )
                }
            }
        )

        AuthenticatedShell(viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedShell(viewModel: HRConnectViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val pendingRazorpayLaunch by viewModel.pendingRazorpayLaunch.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val notificationsState by viewModel.notificationsState.collectAsStateWithLifecycle()

    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showLegalDialog by remember { mutableStateOf(false) }

    val notifications = (notificationsState as? UiState.Success)?.data ?: emptyList()
    val unreadCount = notifications.count { !it.read }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(viewModel, context) {
        val mainActivity = context as? MainActivity
        mainActivity?.activeRazorpayViewModel = viewModel
        onDispose {
            if (mainActivity?.activeRazorpayViewModel == viewModel) {
                mainActivity.activeRazorpayViewModel = null
            }
        }
    }

    LaunchedEffect(viewModel.currentUserId) {
        HRConnectMessagingService.ensureNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    viewModel.syncFcmToken(
                        token = token,
                        deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".take(64)
                    )
                }
            }
            .addOnFailureListener { err ->
                Log.w("HRConnectFCM", "Unable to obtain FCM token", err)
            }
    }

    LaunchedEffect(pendingRazorpayLaunch) {
        val launch = pendingRazorpayLaunch ?: return@LaunchedEffect
        val activity = context as? Activity
        if (activity == null) {
            viewModel.onRazorpaySdkError(-1, "Activity context unavailable for Razorpay Checkout.")
            return@LaunchedEffect
        }
        runCatching {
            val checkout = Checkout()
            checkout.setKeyID(launch.razorpayKeyId)
            val options = JSONObject()
                .put("name", BrandConfig.APP_NAME)
                .put("description", launch.description)
                .put("order_id", launch.orderId)
                .put("currency", launch.currency)
                .put("amount", launch.amountPaise)
                .put("theme", JSONObject().put("color", "#0F2140"))
                .put(
                    "prefill",
                    JSONObject()
                        .put("email", launch.prefillEmail)
                        .put("name", launch.prefillName)
                )
            checkout.open(activity, options)
        }.onFailure { err ->
            viewModel.onRazorpaySdkError(-1, err.message ?: "Unable to launch Razorpay Checkout")
        }
    }

    LaunchedEffect(actionMessage) {
        val msg = actionMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    val destinations = remember(viewModel.isAdminUser) {
        buildList {
            add(
                NavDestinationItem(
                    route = ROUTE_EXPLORE,
                    label = "Explore",
                    selectedIcon = Icons.Filled.Explore,
                    unselectedIcon = Icons.Outlined.Explore,
                    testTag = "nav_explore"
                )
            )
            add(
                NavDestinationItem(
                    route = ROUTE_HUB,
                    label = "My Hub",
                    selectedIcon = Icons.Filled.Dashboard,
                    unselectedIcon = Icons.Outlined.Dashboard,
                    testTag = "nav_hub"
                )
            )
            add(
                NavDestinationItem(
                    route = ROUTE_EXPERT_PORTAL,
                    label = "Expert",
                    selectedIcon = Icons.Filled.Workspaces,
                    unselectedIcon = Icons.Outlined.Workspaces,
                    testTag = "nav_expert_portal"
                )
            )
            if (viewModel.isAdminUser) {
                add(
                    NavDestinationItem(
                        route = ROUTE_ADMIN,
                        label = "Admin",
                        selectedIcon = Icons.Filled.AdminPanelSettings,
                        unselectedIcon = Icons.Outlined.AdminPanelSettings,
                        testTag = "nav_admin"
                    )
                )
            }
        }
    }

    val isDetailRoute = currentRoute?.startsWith("expert_detail") == true

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = if (isDetailRoute) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
            topBar = {
                if (!isDetailRoute) {
                    Column {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HRConnectMonogramBadge(size = 34.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = BrandConfig.APP_NAME,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (viewModel.isAdminUser) "Admin & Marketplace" else "HR Advisory Marketplace",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = HRConnectThemeExtras.colors.mutedText
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = { showLegalDialog = true },
                                    modifier = Modifier.testTag("open_policies_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Gavel,
                                        contentDescription = "Legal and platform policies"
                                    )
                                }
                                IconButton(
                                    onClick = { showNotificationsDialog = true },
                                    modifier = Modifier.testTag("open_notifications_button")
                                ) {
                                    BadgedBox(
                                        badge = {
                                            if (unreadCount > 0) {
                                                Badge(
                                                    containerColor = HRConnectThemeExtras.colors.gold,
                                                    contentColor = HRConnectThemeExtras.colors.navy
                                                ) { Text(unreadCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Notifications,
                                            contentDescription = "Notifications"
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                        if (isBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .imePadding()
                        .then(if (isDetailRoute) Modifier.navigationBarsPadding() else Modifier)
                )
            },
            bottomBar = {
                if (!isDetailRoute && !useRail) {
                    val ext = HRConnectThemeExtras.colors
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        destinations.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ext.navy,
                                    selectedTextColor = ext.navy,
                                    indicatorColor = ext.goldContainer,
                                    unselectedIconColor = ext.mutedText,
                                    unselectedTextColor = ext.mutedText
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (!isDetailRoute && useRail) {
                    val ext = HRConnectThemeExtras.colors
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            destinations.forEach { item ->
                                val selected = currentRoute == item.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(item.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = { Text(item.label) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = ext.navy,
                                        selectedTextColor = ext.navy,
                                        indicatorColor = ext.goldContainer,
                                        unselectedIconColor = ext.mutedText,
                                        unselectedTextColor = ext.mutedText
                                    ),
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_EXPLORE,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    composable(ROUTE_EXPLORE) {
                        HomeMarketplaceScreen(
                            viewModel = viewModel,
                            onOpenExpert = { expertId ->
                                navController.navigate("expert_detail/$expertId")
                            },
                            onNavigateBecomeExpert = { navigateTopLevel(ROUTE_EXPERT_PORTAL) },
                            onOpenPolicies = { showLegalDialog = true }
                        )
                    }
                    composable(ROUTE_HUB) {
                        CustomerDashboardScreen(
                            viewModel = viewModel,
                            onOpenExpert = { expertId ->
                                navController.navigate("expert_detail/$expertId")
                            },
                            onOpenPolicies = { showLegalDialog = true },
                            onSignOut = {
                                signOut(credentialManager = credentialManager, scope = scope)
                            }
                        )
                    }
                    composable(ROUTE_EXPERT_PORTAL) {
                        ExpertPortalScreen(viewModel = viewModel)
                    }
                    composable(ROUTE_ADMIN) {
                        AdminDashboardScreen(viewModel = viewModel)
                    }
                    composable(
                        route = ROUTE_EXPERT_DETAIL,
                        arguments = listOf(navArgument("expertId") { type = NavType.StringType })
                    ) { entry ->
                        val expertId = entry.arguments?.getString("expertId").orEmpty()
                        ExpertProfileBookingScreen(
                            expertId = expertId,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onBookingConfirmedGoToBookings = {
                                navController.popBackStack(ROUTE_EXPLORE, inclusive = false)
                                navigateTopLevel(ROUTE_HUB)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNotificationsDialog) {
        NotificationsDialog(
            notifications = notifications,
            onMarkRead = { viewModel.markNotificationRead(it) },
            onDismiss = { showNotificationsDialog = false }
        )
    }

    if (showLegalDialog) {
        LegalPoliciesDialog(onDismiss = { showLegalDialog = false })
    }
}
