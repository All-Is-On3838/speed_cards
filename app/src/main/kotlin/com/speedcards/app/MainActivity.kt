package com.speedcards.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.speedcards.app.ui.backup.BackupScreen
import com.speedcards.app.ui.common.LocalMediaDir
import com.speedcards.app.ui.importfile.ImportScreen
import com.speedcards.app.ui.settings.SettingsScreen
import com.speedcards.app.ui.stats.StatsScreen
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.speedcards.app.ui.browser.BrowserScreen
import com.speedcards.app.ui.editor.EditNoteScreen
import com.speedcards.app.ui.home.FolderScreen
import com.speedcards.app.ui.home.HomeScreen
import com.speedcards.app.ui.home.HomeViewModel
import com.speedcards.app.ui.importcsv.CsvImportScreen
import com.speedcards.app.ui.links.ClusterPracticeScreen
import com.speedcards.app.ui.links.GraphScreen
import com.speedcards.app.ui.links.LinkTypesScreen
import com.speedcards.app.ui.media.MediaScreen
import com.speedcards.app.ui.presets.PresetEditScreen
import com.speedcards.app.ui.presets.PresetListScreen
import com.speedcards.app.ui.study.StudyScreen
import com.speedcards.app.ui.theme.SpeedCardsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** A screen to jump to, from a notification or a file opened with the app. */
    private val pending: MutableState<String?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pending.value = routeFor(intent)
        val mediaDir = File(filesDir, "media")
        setContent {
            SpeedCardsTheme {
                CompositionLocalProvider(LocalMediaDir provides mediaDir) { AppNav(pending) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { pending.value = it }
    }

    private fun routeFor(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.getStringExtra(EXTRA_OPEN) == OPEN_BACKUP) return Routes.BACKUP_ROUTE
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        return uri?.let { Routes.import(it) }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_BACKUP = "backup"
    }
}

object Routes {
    const val HOME = "home"
    const val DECKS = "decks/{folderId}"
    const val STUDY = "study/{deckId}?ahead={ahead}"
    const val ADD = "add?deckId={deckId}"
    const val EDIT = "edit/{noteId}"
    const val BROWSE = "browse?deckId={deckId}"
    const val PRESETS = "presets"
    const val PRESET_EDIT = "preset/{presetId}"
    const val IMPORT = "import?uri={uri}"
    const val IMPORT_CSV = "import/csv?uri={uri}&nas={nas}"
    const val BACKUP_ROUTE = "backup"
    const val SETTINGS = "settings"
    const val MEDIA = "media"
    const val GRAPH = "graph/{noteId}"
    const val PRACTICE = "practice?noteId={noteId}&deckId={deckId}&tag={tag}&forgotten={forgotten}"
    const val STATS = "stats"
    const val LINK_TYPES = "linktypes"

    /** The decks in a folder; null for decks not in any folder. */
    fun decks(folderId: Long?) = "decks/${folderId ?: HomeViewModel.UNFILED_ARG}"
    fun study(deckId: Long, aheadDays: Int = 0) = "study/$deckId?ahead=$aheadDays"
    fun add(deckId: Long? = null) = if (deckId == null) "add" else "add?deckId=$deckId"
    fun edit(noteId: Long) = "edit/$noteId"
    fun browse(deckId: Long? = null) = if (deckId == null) "browse" else "browse?deckId=$deckId"
    fun presetEdit(presetId: Long) = "preset/$presetId"
    fun graph(noteId: Long) = "graph/$noteId"
    fun practice(noteId: Long) = "practice?noteId=$noteId"
    fun cram(deckId: Long, tag: String?) = "practice?deckId=$deckId" + (tag?.let { "&tag=${Uri.encode(it)}" } ?: "")
    fun forgotten(deckId: Long, days: Int) = "practice?deckId=$deckId&forgotten=$days"
    fun import(uri: Uri? = null) = if (uri == null) "import" else "import?uri=${Uri.encode(uri.toString())}"
    fun importCsv(uri: Uri? = null, nas: Boolean = false) =
        if (uri == null) "import/csv" else "import/csv?uri=${Uri.encode(uri.toString())}&nas=$nas"
}

private val optionalLong = { name: String ->
    navArgument(name) { type = NavType.LongType; defaultValue = -1L }
}

private val optionalString = { name: String ->
    navArgument(name) { type = NavType.StringType; defaultValue = "" }
}

@Composable
fun AppNav(pending: MutableState<String?>) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    LaunchedEffect(pending.value) {
        pending.value?.let { route -> nav.navigate(route) { launchSingleTop = true }; pending.value = null }
    }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            FolderScreen(
                onOpenFolder = { nav.navigate(Routes.decks(it)) },
                onStudy = { nav.navigate(Routes.study(it)) },
                onAdd = { nav.navigate(Routes.add()) },
                onBrowse = { nav.navigate(Routes.browse(it)) },
                onPresets = { nav.navigate(Routes.PRESETS) },
                onImport = { nav.navigate(Routes.import()) },
                onBackup = { nav.navigate(Routes.BACKUP_ROUTE) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onStats = { nav.navigate(Routes.STATS) },
                onMedia = { nav.navigate(Routes.MEDIA) },
                onLinkTypes = { nav.navigate(Routes.LINK_TYPES) },
            )
        }
        composable(Routes.DECKS, listOf(navArgument("folderId") { type = NavType.LongType })) {
            HomeScreen(
                onBack = back,
                onStudy = { nav.navigate(Routes.study(it)) },
                onAdd = { nav.navigate(Routes.add(it)) },
                onBrowse = { nav.navigate(Routes.browse(it)) },
                onPresets = { nav.navigate(Routes.PRESETS) },
                onImport = { nav.navigate(Routes.import()) },
                onBackup = { nav.navigate(Routes.BACKUP_ROUTE) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onStats = { nav.navigate(Routes.STATS) },
                onCram = { deck, tag -> nav.navigate(Routes.cram(deck, tag)) },
                onForgotten = { deck, days -> nav.navigate(Routes.forgotten(deck, days)) },
                onStudyAhead = { deck, days -> nav.navigate(Routes.study(deck, days)) },
                onMedia = { nav.navigate(Routes.MEDIA) },
                onLinkTypes = { nav.navigate(Routes.LINK_TYPES) },
            )
        }
        composable(
            Routes.STUDY,
            listOf(navArgument("deckId") { type = NavType.LongType }, navArgument("ahead") { type = NavType.IntType; defaultValue = 0 }),
        ) {
            StudyScreen(
                onBack = back,
                onEditNote = { nav.navigate(Routes.edit(it)) },
                onGraph = { nav.navigate(Routes.graph(it)) },
                onPracticeLinked = { nav.navigate(Routes.practice(it)) },
            )
        }
        composable(Routes.ADD, listOf(optionalLong("deckId"))) { EditNoteScreen(onDone = back) }
        composable(Routes.EDIT, listOf(navArgument("noteId") { type = NavType.LongType })) {
            EditNoteScreen(
                onDone = back,
                onOpenNote = { nav.navigate(Routes.edit(it)) },
                onGraph = { nav.navigate(Routes.graph(it)) },
            )
        }
        composable(Routes.BROWSE, listOf(optionalLong("deckId"))) {
            BrowserScreen(onBack = back, onOpenNote = { nav.navigate(Routes.edit(it)) })
        }
        composable(Routes.PRESETS) {
            PresetListScreen(onBack = back, onEdit = { nav.navigate(Routes.presetEdit(it)) })
        }
        composable(Routes.PRESET_EDIT, listOf(navArgument("presetId") { type = NavType.LongType })) {
            PresetEditScreen(onBack = back)
        }
        composable(Routes.IMPORT, listOf(optionalString("uri"))) {
            ImportScreen(
                onBack = back,
                onCsvWizard = { uri, nas -> nav.navigate(Routes.importCsv(uri, nas)) { popUpTo(Routes.IMPORT) { inclusive = true } } },
                onOpenBackup = { nav.navigate(Routes.BACKUP_ROUTE) },
            )
        }
        composable(
            Routes.IMPORT_CSV,
            listOf(optionalString("uri"), navArgument("nas") { type = NavType.BoolType; defaultValue = false }),
        ) { CsvImportScreen(onBack = back) }
        composable(Routes.BACKUP_ROUTE) { BackupScreen(onBack = back) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = back) }
        composable(Routes.MEDIA) { MediaScreen(onBack = back) }
        composable(Routes.GRAPH, listOf(navArgument("noteId") { type = NavType.LongType })) {
            GraphScreen(onBack = back, onOpenNote = { nav.navigate(Routes.edit(it)) })
        }
        composable(
            Routes.PRACTICE,
            listOf(
                optionalLong("noteId"), optionalLong("deckId"), optionalString("tag"),
                navArgument("forgotten") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { ClusterPracticeScreen(onBack = back) }
        composable(Routes.STATS) { StatsScreen(onBack = back, onOpenNote = { nav.navigate(Routes.edit(it)) }) }
        composable(Routes.LINK_TYPES) { LinkTypesScreen(onBack = back) }
    }
}
