package com.example.notes

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ---------- מודל נתונים ----------
data class Dir(val id: String, val name: String, val parentId: String?)
data class CheckItem(val text: String, val checked: Boolean)
data class Note(
    val id: String, val folderId: String?, val title: String, val isList: Boolean,
    val text: String, val items: List<CheckItem>, val pinned: Boolean
)

fun Note.matches(q: String) =
    title.contains(q, true) || text.contains(q, true) || items.any { it.text.contains(q, true) }

fun Note.isBlank() = title.isBlank() && text.isBlank() && items.all { it.text.isBlank() }

// ---------- שמירה וניהול ----------
class Repo(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("notes", Context.MODE_PRIVATE)
    var folders by mutableStateOf(listOf<Dir>()); private set
    var notes by mutableStateOf(listOf<Note>()); private set

    init {
        prefs.getString("data", null)?.let { s ->
            val o = JSONObject(s)
            val fa = o.getJSONArray("folders")
            folders = (0 until fa.length()).map {
                val f = fa.getJSONObject(it)
                Dir(f.getString("id"), f.getString("name"), f.getString("parentId").ifEmpty { null })
            }
            val na = o.getJSONArray("notes")
            notes = (0 until na.length()).map {
                val n = na.getJSONObject(it)
                val ia = n.getJSONArray("items")
                Note(
                    n.getString("id"), n.getString("folderId").ifEmpty { null }, n.getString("title"),
                    n.getBoolean("isList"), n.getString("text"),
                    (0 until ia.length()).map { i ->
                        val x = ia.getJSONObject(i); CheckItem(x.getString("t"), x.getBoolean("c"))
                    },
                    n.getBoolean("pinned")
                )
            }
        }
    }

    private fun save() {
        val o = JSONObject()
        o.put("folders", JSONArray(folders.map {
            JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: "")
        }))
        o.put("notes", JSONArray(notes.map { n ->
            JSONObject().put("id", n.id).put("folderId", n.folderId ?: "").put("title", n.title)
                .put("isList", n.isList).put("text", n.text).put("pinned", n.pinned)
                .put("items", JSONArray(n.items.map { JSONObject().put("t", it.text).put("c", it.checked) }))
        }))
        prefs.edit().putString("data", o.toString()).apply()
    }

    private fun <T> swapped(l: List<T>, a: T, b: T): List<T> {
        val m = l.toMutableList(); val i = m.indexOf(a); val j = m.indexOf(b)
        m[i] = b; m[j] = a; return m
    }

    fun path(id: String?): String {
        val d = folders.find { it.id == id } ?: return "ראשי"
        return if (d.parentId == null) d.name else path(d.parentId) + " / " + d.name
    }

    fun addFolder(name: String, parent: String?) {
        folders = folders + Dir(UUID.randomUUID().toString(), name, parent); save()
    }

    private fun descendants(id: String): List<String> =
        folders.filter { it.parentId == id }.flatMap { listOf(it.id) + descendants(it.id) }

    fun deleteFolder(id: String) {
        val ids = descendants(id) + id
        folders = folders.filter { it.id !in ids }
        notes = notes.filter { val f = it.folderId; f == null || f !in ids }
        save()
    }

    fun moveFolder(d: Dir, delta: Int) {
        val sib = folders.filter { it.parentId == d.parentId }
        val t = sib.getOrNull(sib.indexOf(d) + delta) ?: return
        folders = swapped(folders, d, t); save()
    }

    fun newNote(folderId: String?, isList: Boolean): Note {
        val n = Note(UUID.randomUUID().toString(), folderId, "", isList, "",
            if (isList) listOf(CheckItem("", false)) else emptyList(), false)
        notes = notes + n; save(); return n
    }

    fun updateNote(n: Note) { notes = notes.map { if (it.id == n.id) n else it }; save() }
    fun deleteNote(id: String) { notes = notes.filter { it.id != id }; save() }

    fun moveNote(n: Note, delta: Int) {
        val sib = notes.filter { it.folderId == n.folderId && it.pinned == n.pinned }
        val t = sib.getOrNull(sib.indexOf(n) + delta) ?: return
        notes = swapped(notes, n, t); save()
    }
}

// ---------- מסכים ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo(applicationContext)
        setContent { MaterialTheme { App(repo) } }
    }
}

@Composable
fun App(repo: Repo) {
    var folderId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val editing = repo.notes.find { it.id == editingId }

    fun closeEditor() {
        editing?.let { if (it.isBlank()) repo.deleteNote(it.id) }
        editingId = null
    }

    BackHandler(enabled = editing != null || folderId != null) {
        if (editing != null) closeEditor()
        else folderId = repo.folders.find { it.id == folderId }?.parentId
    }
    if (editing != null) EditScreen(repo, editing) { closeEditor() }
    else BrowseScreen(repo, folderId, { folderId = it }, { editingId = it })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(repo: Repo, folderId: String?, openFolder: (String?) -> Unit, openNote: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Dir?>(null) }

    val current = repo.folders.find { it.id == folderId }
    val subFolders = repo.folders.filter { it.parentId == folderId }
    val notes = repo.notes.filter { it.folderId == folderId }.sortedByDescending { it.pinned }
    val results = repo.notes.filter { it.matches(query) }.sortedByDescending { it.pinned }
    val showResults = searching && query.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) TextField(
                        query, { query = it }, placeholder = { Text("חיפוש...") }, singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    ) else Text(current?.name ?: "הפתקיות שלי")
                },
                navigationIcon = {
                    if (searching) IconButton({ searching = false; query = "" }) { Icon(Icons.Default.Close, "סגור חיפוש") }
                    else if (folderId != null) IconButton({ openFolder(current?.parentId) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה")
                    }
                },
                actions = {
                    if (!searching) IconButton({ searching = true }) { Icon(Icons.Default.Search, "חיפוש") }
                }
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton({ fabMenu = true }) { Icon(Icons.Default.Add, "הוסף") }
                DropdownMenu(fabMenu, { fabMenu = false }) {
                    DropdownMenuItem({ Text("תיקייה חדשה") }, { fabMenu = false; folderDialog = true })
                    DropdownMenuItem({ Text("פתקית טקסט") }, {
                        fabMenu = false; openNote(repo.newNote(folderId, false).id)
                    })
                    DropdownMenuItem({ Text("רשימה עם צ'קבוקסים") }, {
                        fabMenu = false; openNote(repo.newNote(folderId, true).id)
                    })
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showResults) {
                if (results.isEmpty()) item { Text("לא נמצאו תוצאות", Modifier.padding(16.dp)) }
                items(results, key = { it.id }) { n ->
                    NoteRow(repo, n, subtitle = repo.path(n.folderId), reorder = false) { openNote(n.id) }
                }
            } else {
                items(subFolders, key = { it.id }) { d ->
                    Card(Modifier.fillMaxWidth().clickable { openFolder(d.id) }) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null)
                            Text(d.name, Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleMedium)
                            IconButton({ repo.moveFolder(d, -1) }) { Icon(Icons.Default.ArrowUpward, "למעלה") }
                            IconButton({ repo.moveFolder(d, 1) }) { Icon(Icons.Default.ArrowDownward, "למטה") }
                            IconButton({ toDelete = d }) { Icon(Icons.Default.Delete, "מחק") }
                        }
                    }
                }
                items(notes, key = { it.id }) { n -> NoteRow(repo, n, null, true) { openNote(n.id) } }
                if (subFolders.isEmpty() && notes.isEmpty()) item {
                    Text("אין כאן כלום עדיין. לחץ על + כדי להוסיף.", Modifier.padding(16.dp))
                }
            }
        }
    }

    if (folderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { folderDialog = false },
            title = { Text("תיקייה חדשה") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("שם") }, singleLine = true) },
            confirmButton = {
                TextButton({ if (name.isNotBlank()) repo.addFolder(name.trim(), folderId); folderDialog = false }) { Text("צור") }
            },
            dismissButton = { TextButton({ folderDialog = false }) { Text("ביטול") } }
        )
    }
    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("למחוק את \"${d.name}\"?") },
            text = { Text("כל הפתקיות ותתי-התיקיות שבתוכה יימחקו.") },
            confirmButton = { TextButton({ repo.deleteFolder(d.id); toDelete = null }) { Text("מחק") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("ביטול") } }
        )
    }
}

@Composable
fun NoteRow(repo: Repo, n: Note, subtitle: String?, reorder: Boolean, onClick: () -> Unit) {
    val preview = if (n.isList) n.items.take(3).joinToString("\n") { (if (it.checked) "☑ " else "☐ ") + it.text }
    else n.text.take(120)
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(n.title.ifBlank { "ללא כותרת" }, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall)
                if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            IconButton({ repo.updateNote(n.copy(pinned = !n.pinned)) }) {
                Icon(
                    Icons.Default.PushPin, "נעץ",
                    tint = if (n.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
            if (reorder) {
                IconButton({ repo.moveNote(n, -1) }) { Icon(Icons.Default.ArrowUpward, "למעלה") }
                IconButton({ repo.moveNote(n, 1) }) { Icon(Icons.Default.ArrowDownward, "למטה") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(repo: Repo, note: Note, onBack: () -> Unit) {
    var moveMenu by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (note.isList) "רשימה" else "פתקית") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
            actions = {
                IconButton({ repo.updateNote(note.copy(pinned = !note.pinned)) }) {
                    Icon(
                        Icons.Default.PushPin, "נעץ",
                        tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
                Box {
                    IconButton({ moveMenu = true }) { Icon(Icons.Default.Folder, "העבר לתיקייה") }
                    DropdownMenu(moveMenu, { moveMenu = false }) {
                        DropdownMenuItem({ Text("ראשי") }, { repo.updateNote(note.copy(folderId = null)); moveMenu = false })
                        repo.folders.forEach { d ->
                            DropdownMenuItem({ Text(repo.path(d.id)) }, {
                                repo.updateNote(note.copy(folderId = d.id)); moveMenu = false
                            })
                        }
                    }
                }
                IconButton({ repo.deleteNote(note.id) }) { Icon(Icons.Default.Delete, "מחק") }
            }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(note.title, { repo.updateNote(note.copy(title = it)) },
                    label = { Text("כותרת") }, modifier = Modifier.fillMaxWidth())
            }
            if (!note.isList) {
                item {
                    OutlinedTextField(note.text, { repo.updateNote(note.copy(text = it)) },
                        label = { Text("טקסט") }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
                }
            } else {
                itemsIndexed(note.items) { i, it ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(it.checked, { c ->
                            repo.updateNote(note.copy(items = note.items.toMutableList().also { l -> l[i] = it.copy(checked = c) }))
                        })
                        OutlinedTextField(
                            it.text,
                            { t -> repo.updateNote(note.copy(items = note.items.toMutableList().also { l -> l[i] = it.copy(text = t) })) },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(
                                textDecoration = if (it.checked) TextDecoration.LineThrough else TextDecoration.None
                            )
                        )
                        IconButton({
                            repo.updateNote(note.copy(items = note.items.toMutableList().also { l -> l.removeAt(i) }))
                        }) { Icon(Icons.Default.Close, "הסר") }
                    }
                }
                item {
                    TextButton({ repo.updateNote(note.copy(items = note.items + CheckItem("", false))) }) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("הוסף פריט")
                    }
                }
            }
        }
    }
}
