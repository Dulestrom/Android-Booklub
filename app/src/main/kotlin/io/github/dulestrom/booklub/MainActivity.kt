package io.github.dulestrom.booklub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import uniffi.booklub_mobile.BooklubException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val state = try {
            AppState(applicationContext)
        } catch (e: BooklubException) {
            // Nothing can work without the library, so say why and leave the data alone.
            setContent { BooklubTheme(Appearance.System) { OpenFailed(e.text()) } }
            return
        }
        setContent { BooklubTheme(state.appearance) { App(state) } }
        // QA-26: sync runs only while Booklub is on screen, and stops when it leaves.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { state.sync.run() } }
    }
}

@Composable
fun App(state: AppState) {
    val screen = state.screen
    BackHandler(enabled = state.stack.size > 1 && screen !is Screen.Editor) { state.back() }
    // From a tab other than Home, the back gesture goes to Home before leaving the app.
    BackHandler(enabled = state.stack.size == 1 && state.tab != Tab.Home) { state.select(Tab.Home) }
    Scaffold(
        containerColor = ink.page,
        bottomBar = {
            if (screen !is Screen.Editor) {
                NavigationBar(containerColor = ink.card) {
                    for (tab in Tab.entries) {
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { state.select(tab) },
                            icon = { Glyph(tab.icon, null, if (state.tab == tab) ink.accent else ink.ink2) },
                            label = { Text(tab.label, style = Type.caption) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = ink.raised, selectedTextColor = ink.ink, unselectedTextColor = ink.ink2),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { message ->
                Text(message, style = Type.caption, color = ink.onDanger, modifier = Modifier.fillMaxWidth().background(ink.danger).padding(12.dp))
            }
            Box(Modifier.weight(1f)) {
                when (screen) {
                    Screen.Home -> HomeScreen(state)
                    Screen.Library -> LibraryScreen(state)
                    Screen.Analytics -> AnalyticsScreen(state)
                    Screen.Settings -> SettingsScreen(state)
                    Screen.Search -> SearchScreen(state)
                    is Screen.Category -> CategoryScreen(state, screen.id)
                    is Screen.Preview -> PreviewScreen(state, screen.bookId)
                    is Screen.Editor -> EditorScreen(state, screen.bookId)
                    Screen.Pair -> PairScreen(state)
                }
            }
        }
    }
}

@Composable
private fun OpenFailed(reason: String) {
    Column(Modifier.fillMaxSize().background(ink.page).padding(24.dp)) {
        Text("Booklub could not open your library", style = Type.title2, color = ink.ink, modifier = Modifier.padding(top = 48.dp))
        Text(reason, style = Type.body, color = ink.ink2, modifier = Modifier.padding(top = 12.dp))
    }
}
