package com.eliosnotes.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.eliosnotes.app.R
import com.eliosnotes.app.data.document.PageMode
import com.eliosnotes.app.data.document.PageOrientation
import com.eliosnotes.app.data.document.PageTemplate
import com.eliosnotes.app.pdf.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val INK_PALETTE = listOf(0xFF1B1F23, 0xFF1E4FA3, 0xFFD32F2F, 0xFF2E7D32, 0xFFEF6C00, 0xFF7B1FA2, 0xFF607D8B).map { Color(it) }
private val HIGHLIGHT_PALETTE = listOf(0xFFFFEB3B, 0xFF76FF03, 0xFFFF80AB, 0xFF18FFFF, 0xFFFFAB40).map { Color(it) }
private val PAGE_COLORS = listOf(0xFFFFFFFFL, 0xFFFFF8E1L, 0xFFF2F2F2L, 0xFFE8F1FBL, 0xFF1F2328L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(session: EditorSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            val pages = session.snapshot()
            scope.launch {
                val result = withContext(Dispatchers.IO) { PdfExporter.export(context, uri, pages) }
                snackbar.showSnackbar(
                    if (result.ok) context.getString(R.string.export_done, result.pageCount)
                    else context.getString(R.string.export_failed)
                )
            }
        }
    }

    BackHandler {
        session.flush()
        onBack()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.flush() }

    val paged = session.pageMode == PageMode.INDIVIDUAL_PAGES
    val untitled = stringResource(R.string.untitled_note)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { session.flush(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    BasicTextField(
                        value = session.title,
                        onValueChange = session::onTitleChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box {
                                if (session.title.isEmpty()) {
                                    Text(
                                        stringResource(R.string.title_hint),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                actions = {
                    IconButton(onClick = session::undo, enabled = session.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                    }
                    IconButton(onClick = session::redo, enabled = session.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.redo))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (session.isFavorite) R.string.remove_favorite else R.string.add_favorite)) },
                                onClick = { menuOpen = false; session.toggleFavorite() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_page)) },
                                onClick = {
                                    menuOpen = false
                                    session.addPage()
                                    scope.launch {
                                        delay(60)
                                        listState.animateScrollToItem(session.pages.lastIndex)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_page)) },
                                onClick = { menuOpen = false; confirmDelete = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.page_settings)) },
                                onClick = { menuOpen = false; showSettings = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_pdf)) },
                                onClick = {
                                    menuOpen = false
                                    val safe = session.title.ifBlank { untitled }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                    exportLauncher.launch("$safe.pdf")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move_to_trash)) },
                                onClick = {
                                    menuOpen = false
                                    session.moveToTrash()
                                    onBack()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = { EditorToolbar(session) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(if (paged) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(if (paged) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (paged) 14.dp else 0.dp)
            ) {
                itemsIndexed(session.pages, key = { _, p -> p.id }) { index, page ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PageView(
                            page = page,
                            session = session,
                            modifier = Modifier
                                .widthIn(max = 900.dp)
                                .then(if (paged) Modifier.shadow(3.dp) else Modifier)
                        )
                        if (paged) {
                            Text(
                                stringResource(R.string.page_counter, index + 1, session.pages.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
            if (session.saveFailed) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        stringResource(R.string.save_failed),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_page)) },
            text = { Text(stringResource(R.string.delete_page_confirm_n, currentPage + 1, session.pages.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    session.pages.getOrNull(currentPage)?.let { session.deletePage(it) }
                }) { Text(stringResource(R.string.delete_page)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (showSettings) PageSettingsDialog(session) { showSettings = false }
}

// ---------------------------------------------------------------------------------------
// Toolbar
// ---------------------------------------------------------------------------------------

private fun toolIcon(t: EditorTool): ImageVector = when (t) {
    EditorTool.PEN -> Icons.Default.Edit
    EditorTool.PENCIL -> Icons.Default.Create
    EditorTool.MARKER -> Icons.Default.Brush
    EditorTool.HIGHLIGHTER -> Icons.Default.Highlight
    EditorTool.ERASER -> Icons.AutoMirrored.Filled.Backspace
    EditorTool.TEXT -> Icons.Default.TextFields
}

private fun toolLabel(t: EditorTool): Int = when (t) {
    EditorTool.PEN -> R.string.tool_pen
    EditorTool.PENCIL -> R.string.tool_pencil
    EditorTool.MARKER -> R.string.tool_marker
    EditorTool.HIGHLIGHTER -> R.string.tool_highlighter
    EditorTool.ERASER -> R.string.tool_eraser
    EditorTool.TEXT -> R.string.tool_text
}

@Composable
private fun EditorToolbar(session: EditorSession) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            if (session.tool != EditorTool.TEXT) ToolOptions(session)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                EditorTool.values().forEach { t ->
                    val selected = session.tool == t
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { session.tool = t }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        ) { Icon(toolIcon(t), contentDescription = null) }
                        Text(
                            stringResource(toolLabel(t)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolOptions(session: EditorSession) {
    val tool = session.tool
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (tool == EditorTool.ERASER) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !session.eraseWholeStrokes,
                    onClick = { session.eraseWholeStrokes = false },
                    label = { Text(stringResource(R.string.erase_partial)) }
                )
                FilterChip(
                    selected = session.eraseWholeStrokes,
                    onClick = { session.eraseWholeStrokes = true },
                    label = { Text(stringResource(R.string.erase_stroke)) }
                )
            }
        } else {
            val palette = if (tool == EditorTool.HIGHLIGHTER) HIGHLIGHT_PALETTE else INK_PALETTE
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                palette.forEach { c ->
                    val selected = session.colorOf(tool) == c.toArgb()
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                CircleShape
                            )
                            .clickable { session.setColor(tool, c.toArgb()) }
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.thickness), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = session.widthOf(tool),
                onValueChange = { session.setWidth(tool, it) },
                valueRange = when (tool) {
                    EditorTool.PEN -> 1f..12f
                    EditorTool.PENCIL -> 1f..10f
                    EditorTool.MARKER -> 3f..30f
                    EditorTool.HIGHLIGHTER -> 8f..40f
                    else -> 6f..40f
                },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            Text(stringResource(R.string.finger_draws), style = MaterialTheme.typography.labelMedium)
            Switch(
                checked = session.fingerDraws,
                onCheckedChange = { session.fingerDraws = it },
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Page settings
// ---------------------------------------------------------------------------------------

@Composable
private fun PageSettingsDialog(session: EditorSession, onDismiss: () -> Unit) {
    val first = session.pages.firstOrNull()?.entity
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_settings)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsSection(R.string.template) {
                    listOf(
                        PageTemplate.BLANK to R.string.tpl_blank,
                        PageTemplate.RULED to R.string.tpl_ruled,
                        PageTemplate.GRID to R.string.tpl_grid,
                        PageTemplate.DOTS to R.string.tpl_dots
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = first?.template == key,
                            onClick = { session.setTemplate(key) },
                            label = { Text(stringResource(label)) }
                        )
                    }
                }
                SettingsSection(R.string.page_color) {
                    PAGE_COLORS.forEach { argb ->
                        val selected = first?.backgroundColorArgb == argb
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb.toInt()))
                                .border(
                                    BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                    CircleShape
                                )
                                .clickable { session.setBackground(argb) }
                        )
                    }
                }
                SettingsSection(R.string.orientation) {
                    FilterChip(
                        selected = first?.orientation == PageOrientation.PORTRAIT,
                        onClick = { session.setOrientation(PageOrientation.PORTRAIT) },
                        label = { Text(stringResource(R.string.portrait)) }
                    )
                    FilterChip(
                        selected = first?.orientation == PageOrientation.LANDSCAPE,
                        onClick = { session.setOrientation(PageOrientation.LANDSCAPE) },
                        label = { Text(stringResource(R.string.landscape)) }
                    )
                }
                Text(
                    stringResource(R.string.orientation_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsSection(R.string.page_mode) {
                    FilterChip(
                        selected = session.pageMode == PageMode.INDIVIDUAL_PAGES,
                        onClick = { session.setPageMode(PageMode.INDIVIDUAL_PAGES) },
                        label = { Text(stringResource(R.string.mode_pages)) }
                    )
                    FilterChip(
                        selected = session.pageMode == PageMode.INFINITE_SCROLLING,
                        onClick = { session.setPageMode(PageMode.INFINITE_SCROLLING) },
                        label = { Text(stringResource(R.string.mode_infinite)) }
                    )
                }
            }
        }
    )
}

@Composable
private fun SettingsSection(titleRes: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}
