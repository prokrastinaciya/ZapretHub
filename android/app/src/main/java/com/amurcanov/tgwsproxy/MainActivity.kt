package com.amurcanov.tgwsproxy

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.ui.AccessSetupDialog
import com.amurcanov.tgwsproxy.ui.AppUpdateDialog
import com.amurcanov.tgwsproxy.ui.hasCriticalGap
import com.amurcanov.tgwsproxy.ui.rememberAccessOpener
import com.amurcanov.tgwsproxy.ui.rememberAccessRows
import com.amurcanov.tgwsproxy.ui.Brand
import com.amurcanov.tgwsproxy.ui.BrandIcon
import com.amurcanov.tgwsproxy.ui.ThemeModes
import com.amurcanov.tgwsproxy.ui.ConnectionTab
import com.amurcanov.tgwsproxy.ui.InfoTab
import com.amurcanov.tgwsproxy.ui.LogsTab
import com.amurcanov.tgwsproxy.ui.SettingsTab
import com.amurcanov.tgwsproxy.ui.TgWsProxyTheme
import com.amurcanov.tgwsproxy.ui.openUrlInBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val localized = newBase.withAppLanguage()
        Locale.setDefault(localized.resources.configuration.locales[0])
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DpiSession.initialize(this)


        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            val themeMode by settingsStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeModes.DARK)

            LaunchedEffect(settingsStore) {
                settingsStore.migrateLegacyDefaults()
            }

            TgWsProxyTheme(themeMode = themeMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brand.bg)
                ) {
                    MainContent(settingsStore)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(settingsStore: SettingsStore) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updatePostponeUntil by settingsStore.updatePostponeUntil.collectAsStateWithLifecycle(initialValue = 0L)
    val updatePostponeVersion by settingsStore.updatePostponeVersion.collectAsStateWithLifecycle(initialValue = "")
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = UPDATE_CHECK_NEVER
    )
    val updateLastCheckAt by settingsStore.updateLastCheckAt.collectAsStateWithLifecycle(initialValue = 0L)
    val cachedRelease by settingsStore.updateLatestRelease.collectAsStateWithLifecycle(initialValue = null)
    var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    val currentUpdatePostponeUntil by rememberUpdatedState(updatePostponeUntil)
    val currentUpdatePostponeVersion by rememberUpdatedState(updatePostponeVersion)
    val accessPromptShown by settingsStore.accessPromptShown.collectAsStateWithLifecycle(initialValue = true)
    val accessRows = rememberAccessRows()
    val openAccess = rememberAccessOpener()
    var showAccessDialog by rememberSaveable { mutableStateOf(false) }
    val navItems = listOf(
        NavItem(stringResource(R.string.nav_proxy), R.drawable.ic_dt_network),
        NavItem(stringResource(R.string.settings), R.drawable.ic_dt_settings),
        NavItem(stringResource(R.string.nav_logs), R.drawable.ic_dt_terminal),
        NavItem(stringResource(R.string.nav_info), R.drawable.ic_dt_documentation)
    )

    DisposableEffect(Unit) {
        LogManager.startListening()
        onDispose { LogManager.stopListening() }
    }

    LaunchedEffect(updateCheckIntervalHours, updateLastCheckAt) {
        if (!BuildConfig.APP_UPDATES_ENABLED || updateCheckIntervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

        val intervalMillis = updateIntervalHoursToMillis(updateCheckIntervalHours)
            ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
            ?: 12L * 60L * 60L * 1000L

        if (updateLastCheckAt > 0L) {
            val nextCheckAt = updateLastCheckAt + intervalMillis
            val now = System.currentTimeMillis()
            if (nextCheckAt > now) {
                delay(nextCheckAt - now)
            }
        }

        if (!isActive) return@LaunchedEffect

        AppUpdateRepository.check(context)
    }

    // A background worker can discover a release while no Activity exists.
    // Show its persisted candidate when the UI next observes the result.
    LaunchedEffect(cachedRelease, updatePostponeVersion, updatePostponeUntil, selectedTab) {
        // InfoTab owns the explicit manual-check dialog; show just one popup.
        if (!BuildConfig.APP_UPDATES_ENABLED || selectedTab == 3) return@LaunchedEffect
        val release = cachedRelease ?: return@LaunchedEffect
        val isPostponed = currentUpdatePostponeVersion == release.versionTag &&
            System.currentTimeMillis() < currentUpdatePostponeUntil
        if (release.versionCode > BuildConfig.VERSION_CODE && !isPostponed) {
            settingsStore.saveUpdateDialogShown(release.versionTag, System.currentTimeMillis())
            pendingRelease = release
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        containerColor = Color.Transparent,
        bottomBar = {
            ProxyNavigationBar(
                navItems = navItems,
                selectedTab = selectedTab,
                dragTargetIndex = dragTargetIndex,
                dragProgress = dragProgress,
                onTabSelected = { index ->
                    selectedTab = index
                    dragTargetIndex = -1
                    dragProgress = 0f
                }
            )
        },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .pointerInput(selectedTab) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDrag = 0f
                        dragTargetIndex = -1
                        dragProgress = 0f
                    },
                    onDragCancel = {
                        dragTargetIndex = -1
                        dragProgress = 0f
                    },
                    onDragEnd = {
                        if (dragTargetIndex in navItems.indices && dragProgress >= 0.5f) {
                            selectedTab = dragTargetIndex
                        }
                        dragTargetIndex = -1
                        dragProgress = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    totalDrag += dragAmount
                    if (abs(totalDrag) < 12f) {
                        dragTargetIndex = -1
                        dragProgress = 0f
                        return@detectHorizontalDragGestures
                    }

                    val candidate = if (totalDrag < 0f) selectedTab + 1 else selectedTab - 1
                    if (candidate !in navItems.indices) {
                        dragTargetIndex = -1
                        dragProgress = 0f
                        return@detectHorizontalDragGestures
                    }

                    dragTargetIndex = candidate
                    dragProgress = (abs(totalDrag) / 180f).coerceIn(0f, 1f)
                }
            }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(225))
                },
                modifier = Modifier.fillMaxSize(),
                label = "tab_content"
            ) { page ->
                when (page) {
                    0 -> ConnectionTab(
                        settingsStore = settingsStore,
                        showAccessWarning = accessRows.hasCriticalGap(),
                        onShowAccess = { showAccessDialog = true }
                    )
                    1 -> SettingsTab(settingsStore)
                    2 -> LogsTab(settingsStore)
                    3 -> InfoTab(settingsStore)
                }
            }

        }
    }

    if (showAccessDialog || (!accessPromptShown && accessRows.hasCriticalGap())) {
        AccessSetupDialog(
            rows = accessRows,
            onOpen = openAccess,
            onDone = {
                showAccessDialog = false
                scope.launch { settingsStore.saveAccessPromptShown() }
            }
        )
    }

    pendingRelease?.let { release ->
        AppUpdateDialog(
            release = release,
            onPostpone = {
                pendingRelease = null
                Toast.makeText(context, context.getString(R.string.update_postponed_24h), Toast.LENGTH_SHORT).show()
                scope.launch {
                    val now = System.currentTimeMillis()
                    settingsStore.saveUpdatePostpone(
                        version = release.versionTag,
                        until = now + 24L * 60L * 60L * 1000L
                    )
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_POSTPONED,
                        actedAt = now
                    )
                }
            },
            onOpenRelease = {
                if (openUrlInBrowser(context, release.releaseUrl)) {
                    pendingRelease = null
                    scope.launch {
                        settingsStore.saveUpdateDialogAction(
                            version = release.versionTag,
                            action = UPDATE_DIALOG_ACTION_RELEASE_OPENED,
                            actedAt = System.currentTimeMillis()
                        )
                    }
                } else {
                    Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}
