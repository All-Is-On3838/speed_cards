package com.speedcards.app.ui.media

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.speedcards.data.repo.MediaRepository
import com.speedcards.data.repo.MediaUsage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MediaViewModel @Inject constructor(private val media: MediaRepository) : ViewModel() {
    val usage = MutableStateFlow<MediaUsage?>(null)
    val message = MutableStateFlow<String?>(null)

    init { refresh() }

    private fun refresh() = viewModelScope.launch { usage.value = media.usage() }

    fun deleteUnused() = viewModelScope.launch {
        val n = media.deleteUnused()
        message.value = "Deleted $n unused file${if (n == 1) "" else "s"}."
        refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(onBack: () -> Unit, vm: MediaViewModel = hiltViewModel()) {
    val usage by vm.usage.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    fun files(n: Int) = "$n file${if (n == 1) "" else "s"}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio & media") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val u = usage
            if (u == null) { CircularProgressIndicator(); return@Column }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${files(u.fileCount)} · ${size(u.totalBytes)}", style = MaterialTheme.typography.titleLarge)
                    Text("Unused: ${files(u.unusedCount)} · ${size(u.unusedBytes)}")
                    Text(
                        "Unused files are recordings no card refers to anymore, e.g. after deleting a card or removing its audio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(onClick = vm::deleteUnused, enabled = u.unusedCount > 0, modifier = Modifier.fillMaxWidth()) {
                Text("Delete unused files")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
