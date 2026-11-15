package com.eliosnotes.app.ui

import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eliosnotes.app.R
import com.eliosnotes.app.data.FolderEntity
import com.eliosnotes.app.data.NoteEntity
import com.eliosnotes.app.data.NotesRepository
import com.eliosnotes.app.data.SaveQueue
import com.eliosnotes.app.ui.editor.EditorScreen
import com.eliosnotes.app.ui.editor.EditorSession
import com.eliosnotes.app.ui.editor.LoadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private enum class LibraryTab { ALL, FOLDERS, FAVORITES }

private class CardAction(val label: String, val run: () -> Unit)

private const val SCREEN_LIBRARY = "library"
private const val SCREEN_ARCHIVE = "archive"
private const val SCREEN_TRASH = "trash"
private const val SORT_MODIFIED = "modified"
private const val SORT_CREATED = "created"
private const val SORT_TITLE = "title"
private const val TRASH_DAYS = 30

@Composable
fun NotesApp(repo: NotesRepository) {
    var openNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf(SCREEN_LIBRARY) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { repo.purgeOldTrash(TRASH_DAYS) } }

    val id = openNoteId
    if (id != null) {
        EditorHost(repo, id) { openNoteId = null }
    } else if (screen == SCREEN_ARCHIVE || screen == SCREEN_TRASH) {
        SecondaryList(repo, isTrash = screen == SCREEN_TRASH, onBack = { screen = SCREEN_LIBRARY }, onOpen = { openNoteId = it })
    } else {
        Library(repo, onOpen = { openNoteId = it }, onNavigate = { screen = it })
    }
}

@Composable
private fun EditorHost(repo: NotesRepository, noteId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember(noteId) { EditorSession(repo, noteId, scope) }
    LaunchedEffect(session) { session.load() }
    // Closing the editor queues a final save that outlives this composition.
    DisposableEffect(session) { onDispose { session.close() } }

    when (session.status) {
        LoadStatus.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        LoadStatus.MISSING -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.note_missing))
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        LoadStatus.READY -> EditorScreen(session, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Library(repo: NotesRepository, onOpen: (String) -> Unit, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dao = repo.noteDao

    // Backup: write, then re-open the file and parse it again. A file that fails is deleted.
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            val message = try {
                val count = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    try {
                        val stats = SaveQueue.run {
                            val out = resolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("no stream")
                            out.use { repo.exportBackup(it) }
                        }
                        val verified = resolver.openInputStream(uri)?.use { repo.verifyBackup(it) }
                            ?: throw IllegalStateException("no stream")
                        check(verified == stats.notes) { "backup verification mismatch" }
                        stats.notes
                    } catch (e: Exception) {
                        try { DocumentsContract.deleteDocument(resolver, uri) } catch (ignored: Exception) {}
                        throw e
                    }
                }
                context.getString(R.string.backup_done, count)
            } catch (e: Exception) {
                context.getString(R.string.backup_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val message = try {
                val stats = withContext(Dispatchers.IO) {
                    SaveQueue.run {
                        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("no stream")
                        input.use { repo.restoreBackup(it) }
                    }
                }
                context.getString(R.string.restore_done, stats.notes, stats.skipped)
            } catch (e: Exception) {
                context.getString(R.string.restore_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val notes by dao.observeNotes().collectAsState(initial = emptyList())
    val favorites by dao.observeFavorites().collectAsState(initial = emptyList())
    val folders by repo.folderDao.observeFolders().collectAsState(initial = emptyList())

    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.ALL) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(SORT_MODIFIED) }
    var activeFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderBeingRenamed by remember { mutableStateOf<FolderEntity?>(null) }
    var folderName by remember { mutableStateOf("") }
    var noteToMove by remember { mutableStateOf<NoteEntity?>(null) }
    var topMenuOpen by remember { mutableStateOf(false) }

    val activeFolder = folders.firstOrNull { it.id == activeFolderId }
    val source = when {
        selectedTab == LibraryTab.FAVORITES -> favorites
        activeFolder != null -> notes.filter { it.folderId == activeFolder.id }
        else -> notes
    }
    val visibleNotes = source
        .filter {
            searchText.isBlank() ||
                it.title.contains(searchText, ignoreCase = true) ||
                it.body.contains(searchText, ignoreCase = true)
        }
        .sortedWith(
            compareByDescending<NoteEntity> { it.isPinned }.thenComparator { a, b ->
                when (sortMode) {
                    SORT_TITLE -> a.title.compareTo(b.title, ignoreCase = true)
                    SORT_CREATED -> b.createdAt.compareTo(a.createdAt)
                    else -> b.updatedAt.compareTo(a.updatedAt)
                }
            }
        )

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(activeFolder?.name ?: stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                activeFolder != null -> stringResource(R.string.subtitle_folder)
                                selectedTab == LibraryTab.ALL -> stringResource(R.string.subtitle_all)
                                selectedTab == LibraryTab.FOLDERS -> stringResource(R.string.tab_folders)
                                else -> stringResource(R.string.tab_favorites)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (activeFolder != null) IconButton(onClick = { activeFolderId = null; selectedTab = LibraryTab.FOLDERS }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                    Box {
                        IconButton(onClick = { topMenuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                        DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.sort_modified)) }, onClick = { sortMode = SORT_MODIFIED; topMenuOpen = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sort_created)) }, onClick = { sortMode = SORT_CREATED; topMenuOpen = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sort_title)) }, onClick = { sortMode = SORT_TITLE; topMenuOpen = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_archive)) }, onClick = { topMenuOpen = false; onNavigate(SCREEN_ARCHIVE) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_trash)) }, onClick = { topMenuOpen = false; onNavigate(SCREEN_TRASH) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_backup)) }, onClick = {
                                topMenuOpen = false
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                backupLauncher.launch("ELIOS-backup-$stamp.json")
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_restore)) }, onClick = {
                                topMenuOpen = false
                                restoreLauncher.launch(arrayOf("application/json", "*/*"))
                            })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        val note = withContext(Dispatchers.IO) { repo.createNote(activeFolder?.id) }
                        onOpen(note.id)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_note)) }
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.surface) {
                TabItem(Modifier.weight(1f), selectedTab == LibraryTab.ALL, { Icon(Icons.Default.Create, null) }, stringResource(R.string.tab_notes)) {
                    activeFolderId = null; selectedTab = LibraryTab.ALL
                }
                TabItem(Modifier.weight(1f), selectedTab == LibraryTab.FOLDERS, { Icon(Icons.Default.Folder, null) }, stringResource(R.string.tab_folders)) {
                    selectedTab = LibraryTab.FOLDERS
                }
                TabItem(Modifier.weight(1f), selectedTab == LibraryTab.FAVORITES, { Icon(Icons.Default.Favorite, null) }, stringResource(R.string.tab_favorites)) {
                    activeFolderId = null; selectedTab = LibraryTab.FAVORITES
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (showSearch) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_notes)) },
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(12.dp))
            }
            if (selectedTab == LibraryTab.FOLDERS && activeFolder == null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.your_folders), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { folderBeingRenamed = null; folderName = ""; showFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Text(" " + stringResource(R.string.new_folder))
                    }
                }
                if (folders.isEmpty()) {
                    EmptyState(stringResource(R.string.no_folders), stringResource(R.string.no_folders_sub))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(folders, key = { it.id }) { folder ->
                            FolderCard(
                                folder = folder,
                                count = notes.count { it.folderId == folder.id },
                                onOpen = { activeFolderId = folder.id; selectedTab = LibraryTab.ALL },
                                onRename = { folderBeingRenamed = folder; folderName = folder.name; showFolderDialog = true },
                                onDelete = { scope.launch(Dispatchers.IO) { repo.deleteFolder(folder.id) } }
                            )
                        }
                    }
                }
            } else if (visibleNotes.isEmpty()) {
                EmptyState(
                    if (searchText.isBlank()) stringResource(R.string.empty_first_title) else stringResource(R.string.no_results),
                    if (searchText.isBlank()) stringResource(R.string.empty_first_sub) else stringResource(R.string.try_other)
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visibleNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onOpen(note.id) },
                            actions = listOf(
                                CardAction(stringResource(if (note.isPinned) R.string.unpin else R.string.pin)) {
                                    scope.launch(Dispatchers.IO) { dao.setPinned(note.id, !note.isPinned, System.currentTimeMillis()) }
                                },
                                CardAction(stringResource(if (note.isFavorite) R.string.remove_favorite else R.string.add_favorite)) {
                                    scope.launch(Dispatchers.IO) { dao.setFavorite(note.id, !note.isFavorite) }
                                },
                                CardAction(stringResource(R.string.move_to_folder)) { noteToMove = note },
                                CardAction(stringResource(R.string.archive)) {
                                    scope.launch(Dispatchers.IO) { dao.archive(note.id) }
                                },
                                CardAction(stringResource(R.string.move_to_trash)) {
                                    scope.launch(Dispatchers.IO) { dao.moveToTrash(note.id, System.currentTimeMillis()) }
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    if (showFolderDialog) {
        val renaming = folderBeingRenamed
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(stringResource(if (renaming == null) R.string.create_folder else R.string.rename_folder)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) }
                )
            },
            confirmButton = {
                TextButton(enabled = folderName.isNotBlank(), onClick = {
                    val now = System.currentTimeMillis()
                    val name = folderName.trim()
                    scope.launch(Dispatchers.IO) {
                        if (renaming == null) repo.folderDao.insert(FolderEntity(UUID.randomUUID().toString(), name, now, now))
                        else repo.folderDao.rename(renaming.id, name, now)
                    }
                    showFolderDialog = false
                }) { Text(stringResource(if (renaming == null) R.string.create else R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showFolderDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    val moving = noteToMove
    if (moving != null) {
        AlertDialog(
            onDismissRequest = { noteToMove = null },
            title = { Text(stringResource(R.string.move_note)) },
            text = {
                Column {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) { dao.moveToFolder(moving.id, null, System.currentTimeMillis()) }
                        noteToMove = null
                    }) { Text(stringResource(R.string.no_folder)) }
                    folders.forEach { folder ->
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) { dao.moveToFolder(moving.id, folder.id, System.currentTimeMillis()) }
                            noteToMove = null
                        }) { Text(folder.name) }
                    }
                    if (folders.isEmpty()) Text(stringResource(R.string.create_folder_first))
                }
            },
            confirmButton = { TextButton(onClick = { noteToMove = null }) { Text(stringResource(R.string.close)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecondaryList(repo: NotesRepository, isTrash: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val dao = repo.noteDao
    val notes by (if (isTrash) dao.observeTrashed() else dao.observeArchived()).collectAsState(initial = emptyList())
    var menuOpen by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<NoteEntity?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isTrash) R.string.menu_trash else R.string.menu_archive)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    if (isTrash && notes.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.empty_trash)) },
                                    onClick = { menuOpen = false; confirmEmptyTrash = true }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (isTrash) {
                Text(stringResource(R.string.trash_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (notes.isEmpty()) {
                EmptyState(stringResource(if (isTrash) R.string.trash_empty_title else R.string.archive_empty_title), "")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(notes, key = { it.id }) { note ->
                        val actions = buildList {
                            add(CardAction(stringResource(R.string.restore)) {
                                scope.launch(Dispatchers.IO) { if (isTrash) dao.restoreFromTrash(note.id) else dao.unarchive(note.id) }
                            })
                            if (isTrash) add(CardAction(stringResource(R.string.delete_forever)) {
                                noteToDelete = note
                            })
                        }
                        NoteCard(note = note, onClick = { if (!isTrash) onOpen(note.id) }, actions = actions)
                    }
                }
            }
        }
    }

    val toDelete = noteToDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text(stringResource(R.string.delete_forever)) },
            text = { Text(stringResource(R.string.delete_forever_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    noteToDelete = null
                    scope.launch(Dispatchers.IO) { dao.deleteForever(toDelete.id) }
                }) { Text(stringResource(R.string.delete_forever)) }
            },
            dismissButton = { TextButton(onClick = { noteToDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text(stringResource(R.string.empty_trash)) },
            text = { Text(stringResource(R.string.empty_trash_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmptyTrash = false
                    scope.launch(Dispatchers.IO) { dao.emptyTrash() }
                }) { Text(stringResource(R.string.empty_trash)) }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun TabItem(modifier: Modifier = Modifier, selected: Boolean, icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) { icon() }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FolderCard(folder: FolderEntity, count: Int, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(folder.name, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename_folder)) }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_folder)) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteEntity, onClick: () -> Unit, actions: List<CardAction>) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.title.ifBlank { stringResource(R.string.untitled_note) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (note.isPinned) Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.note_options)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        actions.forEach { a ->
                            DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; a.run() })
                        }
                    }
                }
            }
            if (note.body.isNotBlank()) {
                Text(note.body, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
