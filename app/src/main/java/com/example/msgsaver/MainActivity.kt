package com.example.msgsaver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { MsgSaverTheme { Root() } }
    }
}

fun hasNotificationAccess(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
    return flat.contains(ComponentName(ctx, WhatsAppListener::class.java).flattenToString())
}

fun folderReady(ctx: Context): Boolean = directRoots().isNotEmpty() || safRoot(ctx) != null

fun batteryReady(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

fun allSetupReady(ctx: Context): Boolean =
    hasNotificationAccess(ctx) && folderReady(ctx) && batteryReady(ctx)

/* ------------------------------ ROOT ------------------------------ */

@Composable
fun Root() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val messages by dao.messages().collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableStateOf(Prefs.get(ctx).getBoolean("setup_skipped", false)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val allReady = remember(tick) { allSetupReady(ctx) }

    LaunchedEffect(Unit) {
        val u = Updater.latest()
        if (u != null && u.versionCode > currentVersionCode(ctx)) update = u
    }

    if (!allReady && !skipped) {
        SetupWizard(onSkip = {
            skipped = true
            Prefs.get(ctx).edit().putBoolean("setup_skipped", true).apply()
        })
    } else {
        MainScaffold(messages, tab) { tab = it }
    }

    update?.let { u ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("Naya update available ✨") },
            text = {
                Text(
                    "Version ${u.name} aa gaya hai." +
                        (if (u.notes.isNotBlank()) "\n\n" + u.notes.take(300) else "") +
                        "\n\nDownload ke baad file par tap karke install karo. Aapka data safe rahega."
                )
            },
            confirmButton = {
                Button(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    update = null
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { update = null }) { Text("Baad me") } }
        )
    }
}

@Composable
fun MainScaffold(messages: List<SavedMessage>, tab: Int, onTab: (Int) -> Unit) {
    val tabs = listOf("💬" to "Messages", "🖼" to "Status", "🎤" to "Voice", "⚙" to "Settings")
    val deleted = messages.count { it.isDeleted }
    val subtitle = when (tab) {
        0 -> "${messages.size} saved  •  $deleted deleted"
        1 -> "Dekhe hue status safe rakho"
        2 -> "Voice messages dobara suno"
        else -> "Setup aur options"
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader("Msg Saver", subtitle) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (emoji, label) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { onTab(i) },
                        icon = { Text(emoji, fontSize = 20.sp) }, label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> MessagesScreen(messages)
                1 -> StatusScreen()
                2 -> VoiceScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun SetupWizard(onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
    ) {
        AppHeader("Msg Saver", "Bas 3 chhote steps")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Shuru karne se pehle", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Har step ka button dabao, wahan switch ON karo aur wapas aao. App khud agla step dikha degi.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SetupSteps()
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Baad me karunga")
            }
        }
    }
}

/* ------------------------------ MESSAGES ------------------------------ */

@Composable
fun MessagesScreen(all: List<SavedMessage>) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasNotificationAccess(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasNotificationAccess(ctx) }

    var onlyDeleted by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val delCount = all.count { it.isDeleted }
    val shown = remember(all, onlyDeleted, query) {
        all.filter {
            (!onlyDeleted || it.isDeleted) &&
                (query.isBlank() || it.text.contains(query, true) || it.sender.contains(query, true))
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!granted) {
            item {
                AppCard(container = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Notification access band hai", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Messages save karne ke liye isse allow karna zaroori hai.",
                            color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Allow karo") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Message ya naam search karo") },
                leadingIcon = { Text("🔍") },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !onlyDeleted, onClick = { onlyDeleted = false },
                    label = { Text("Sab (${all.size})") })
                FilterChip(selected = onlyDeleted, onClick = { onlyDeleted = true },
                    label = { Text("🗑 Deleted ($delCount)") })
            }
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    if (onlyDeleted) "🗑" else "💬",
                    if (onlyDeleted) "Abhi koi deleted message nahi" else "Abhi koi message nahi",
                    "Jab koi WhatsApp message bhejega, wo yahan save ho jayega. Delete hone par bhi yahan dikhega."
                )
            }
        }
        items(shown, key = { it.id }) { MessageBubble(it) }
    }
}

@Composable
fun MessageBubble(m: SavedMessage) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Avatar(m.sender)
        Spacer(Modifier.width(10.dp))
        Card(
            Modifier.weight(1f),
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            colors = CardDefaults.cardColors(containerColor = if (m.isDeleted) cs.errorContainer else cs.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.sender, fontWeight = FontWeight.Bold, color = cs.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(formatTime(m.timestamp), fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(m.text, color = if (m.isDeleted) cs.onErrorContainer else cs.onSurface)
                if (m.isDeleted) {
                    Spacer(Modifier.height(6.dp))
                    Text("🗑 Sender ne ye message delete kiya", fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, color = cs.error)
                }
            }
        }
    }
}

/* ------------------------------ STATUS ------------------------------ */

@Composable
fun StatusScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by AppDb.get(ctx).dao().files("status").collectAsState(initial = emptyList())
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    var ready by remember { mutableStateOf(folderReady(ctx)) }
    LaunchedEffect(Unit) { Scanner.run(ctx) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ready = folderReady(ctx)
        scope.launch { Scanner.run(ctx) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (!ready) {
                    AppCard(container = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            "Pehle Settings me WhatsApp folder ka access do.",
                            Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${files.size} status saved", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Status pehle WhatsApp me dekho, phir yahan aayega.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val n = Scanner.run(ctx)
                            Toast.makeText(ctx, "$n naye status save hue", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("🔄 Refresh") }
                }
            }
        }
        if (files.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState("🖼", "Abhi koi status nahi",
                    "WhatsApp me kisi ka status kholkar dekho, phir Refresh dabao.")
            }
        }
        items(files, key = { it.id }) { StatusItem(it, loader) }
    }
}

@Composable
fun StatusItem(f: BackupFile, loader: ImageLoader) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVideo = f.name.endsWith(".mp4", true)
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.72f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { openFile(ctx, f.path) }
    ) {
        AsyncImage(
            model = File(f.path), imageLoader = loader, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
        if (isVideo) {
            Box(
                Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) { Text("▶", color = Color.White, fontSize = 20.sp) }
            Text(
                "VIDEO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Pill("⬇ Save") {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { saveToGallery(ctx, f) }
                    Toast.makeText(ctx, if (ok) "Gallery me save ho gaya" else "Save nahi hua", Toast.LENGTH_SHORT).show()
                }
            }
            Pill("🗑") {
                scope.launch(Dispatchers.IO) { File(f.path).delete(); AppDb.get(ctx).dao().deleteFile(f) }
            }
        }
    }
}

/* ------------------------------ VOICE ------------------------------ */

@Composable
fun VoiceScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by AppDb.get(ctx).dao().files("voice").collectAsState(initial = emptyList())
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { Scanner.run(ctx) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { Scanner.run(ctx) } }
    DisposableEffect(Unit) { onDispose { VoicePlayer.stop() } }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("${files.size} voice messages saved", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Sirf wo jo monitoring on hone ke baad aaye. WhatsApp me auto-download on hona chahiye.",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
        }
        if (files.isEmpty()) {
            item {
                EmptyState("🎤", "Abhi koi voice message nahi",
                    "Naye voice messages aate hi yahan save ho jayenge.")
            }
        }
        items(files, key = { it.id }) { f ->
            val isPlaying = VoicePlayer.playing == f.path
            AppCard(container = if (isPlaying) cs.primaryContainer else cs.surface) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(cs.primary)
                            .clickable { VoicePlayer.toggle(f.path) },
                        contentAlignment = Alignment.Center
                    ) { Text(if (isPlaying) "⏹" else "▶", color = cs.onPrimary, fontSize = 18.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Voice message", fontWeight = FontWeight.SemiBold)
                        Text(
                            formatTime(if (f.origModified > 0) f.origModified else f.savedAt) +
                                "  •  ${f.size / 1024} KB",
                            fontSize = 12.sp, color = cs.onSurfaceVariant
                        )
                    }
                    Text("🗑", fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable {
                        if (isPlaying) VoicePlayer.stop()
                        scope.launch(Dispatchers.IO) { File(f.path).delete(); AppDb.get(ctx).dao().deleteFile(f) }
                    }.padding(8.dp))
                }
            }
        }
    }
}

/* ------------------------------ SETTINGS ------------------------------ */

private const val HINT_WA =
    "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia"
private const val HINT_BIZ =
    "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp.w4b%2FWhatsApp%20Business%2FMedia"

/** Returns a function that opens the system folder picker. Argument: true = WhatsApp Business. */
@Composable
fun rememberFolderPicker(onDone: () -> Unit): (Boolean) -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val p = Prefs.get(ctx)
            p.edit().putString("tree", uri.toString()).apply()
            if (!p.contains("enabled_at")) p.edit().putLong("enabled_at", System.currentTimeMillis()).apply()
            val root = DocumentFile.fromTreeUri(ctx, uri)
            val okNames = setOf("Media", ".Statuses", "WhatsApp Voice Notes")
            val looksRight = root != null && (
                (root.name ?: "") in okNames ||
                    root.findFile(".Statuses") != null ||
                    root.findFile("WhatsApp Voice Notes") != null
                )
            Toast.makeText(
                ctx,
                if (looksRight) "Folder sahi hai ✅" else "Ye WhatsApp ka Media folder nahi lag raha. Dobara choose karo.",
                Toast.LENGTH_LONG
            ).show()
            onDone()
        }
    }
    return { biz -> launcher.launch(Uri.parse(if (biz) HINT_BIZ else HINT_WA)) }
}

/** The three setup cards. Used both in the first-run wizard and in Settings. */
@Composable
fun SetupSteps() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val notifOk = remember(tick) { hasNotificationAccess(ctx) }
    val filesOk = remember(tick) { folderReady(ctx) }
    val batteryOk = remember(tick) { batteryReady(ctx) }
    val allFiles = remember(tick) { hasAllFiles() }
    val picker = rememberFolderPicker { tick++ }

    val states = listOf(notifOk, filesOk, batteryOk)
    val readyCount = states.count { it }
    val current = states.indexOfFirst { !it }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard(container = cs.primaryContainer) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (readyCount == 3) "Sab ready hai 🎉" else "Setup: $readyCount/3 ready",
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, color = cs.onPrimaryContainer
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { readyCount / 3f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = cs.primary, trackColor = cs.surface
                )
            }
        }

        SetupCard(
            1, "Messages padhne ki ijazat", notifOk,
            "Deleted messages save karne ke liye. Agar 'App was denied access' aaye: App info > upar right ⋮ > 'Allow restricted settings', phir wapas aao.",
            highlight = current == 0
        ) {
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text("Allow karo")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text("App info") }
        }

        val filesHelp = when {
            Build.VERSION.SDK_INT >= 30 && !allFiles ->
                "Status aur voice messages ke liye. Bas ek switch ON karo (All files access)."
            Build.VERSION.SDK_INT >= 30 && !filesOk ->
                "Access mil gaya, par WhatsApp ka folder nahi mila. 'Folder chuno' dabao aur 'Media' folder select karo."
            Build.VERSION.SDK_INT >= 30 -> "Access mil gaya ✅ WhatsApp aur Business dono scan honge."
            else -> "Folder chuno: khulne wali screen me neeche 'Use this folder' dabao. Folder ka naam 'Media' hona chahiye."
        }
        SetupCard(2, "Status aur voice files", filesOk, filesHelp, highlight = current == 1) {
            if (Build.VERSION.SDK_INT >= 30) {
                Button(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                }) { Text("Allow karo") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { picker(false) }) { Text("Folder chuno") }
            } else {
                Button(onClick = { picker(false) }) { Text("WhatsApp") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { picker(true) }) { Text("Business") }
            }
        }

        SetupCard(
            3, "Background me chalne do", batteryOk,
            "Warna phone app ko band kar dega aur naye messages miss ho jayenge.",
            highlight = current == 2
        ) {
            Button(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text("Allow karo") }
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var custom by remember { mutableStateOf(Prefs.get(ctx).getString("custom_deleted", "") ?: "") }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SetupSteps()

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text("Deleted message ka text", fontWeight = FontWeight.Bold)
                Text(
                    "Agar WhatsApp Hindi/Urdu ya kisi aur language me hai, to deleted message ka text yahan likho.",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it; Prefs.get(ctx).edit().putString("custom_deleted", it).apply() },
                    singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text("App version", fontWeight = FontWeight.Bold)
                Text("v${currentVersionName(ctx)}  (build ${currentVersionCode(ctx)})",
                    fontSize = 13.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = {
                    scope.launch {
                        val u = Updater.latest()
                        val msg = when {
                            u == null -> "Update check nahi ho saka (internet ya release nahi mila)"
                            u.versionCode > currentVersionCode(ctx) -> "Naya version ${u.name} available hai"
                            else -> "Aap latest version par ho ✅"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        if (u != null && u.versionCode > currentVersionCode(ctx)) {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }) { Text("🔄 Update check karo") }
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text("Data", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = {
                    scope.launch {
                        val n = Scanner.run(ctx)
                        Toast.makeText(ctx, "$n nayi files save hui", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Abhi scan karo") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) { AppDb.get(ctx).dao().clearMessages() }
                }) { Text("Saare messages delete karo") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        File(ctx.filesDir, "backup").deleteRecursively()
                        AppDb.get(ctx).dao().clearFiles()
                    }
                }) { Text("Saare status/voice delete karo") }
            }
        }

        Text(
            "Privacy: messages, status aur voice sirf aapke phone me rehte hain. Internet sirf naya version check karne ke liye use hota hai. Ye app WhatsApp se alag hai aur uska koi hissa nahi.",
            fontSize = 12.sp, color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SetupCard(
    n: Int, title: String, ok: Boolean, help: String, highlight: Boolean = false,
    actions: @Composable RowScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    AppCard(border = if (highlight) BorderStroke(2.dp, cs.primary) else null) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(cs.primary),
                    contentAlignment = Alignment.Center
                ) { Text("$n", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusChip(ok)
            }
            Spacer(Modifier.height(6.dp))
            Text(help, fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(content = actions)
        }
    }
}
