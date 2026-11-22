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
import androidx.core.app.NotificationManagerCompat
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
        setContent { ChatRewindTheme { Root() } }
    }
}

fun hasNotificationAccess(ctx: Context): Boolean {
    val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(ctx)
    if (ctx.packageName in enabledPackages) return true
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
    return flat.contains(ComponentName(ctx, WhatsAppListener::class.java).flattenToString())
}

fun folderReady(ctx: Context): Boolean = directRoots().isNotEmpty() || safRoot(ctx) != null

fun batteryReady(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

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
    val allReady by remember(tick) { derivedStateOf { allSetupReady(ctx) } }

    LaunchedEffect(Unit) {
        val latest = withContext(Dispatchers.IO) { Updater.latest() }
        if (latest != null && latest.versionCode > currentVersionCode(ctx)) {
            update = latest
        }
    }

    if (!allReady && !skipped) {
        SetupWizard(
            onSkip = {
                skipped = true
                Prefs.get(ctx).edit().putBoolean("setup_skipped", true).apply()
            }
        )
    } else {
        MainScaffold(messages = messages, tab = tab, onTab = { tab = it })
    }

    update?.let { u ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("New update available ✨") },
            text = {
                Text(
                    "Version ${u.name} is available." +
                        (if (u.notes.isNotBlank()) "\n\n" + u.notes.take(300) else "") +
                        "\n\nAfter downloading, tap the file to install it. Your data will stay safe."
                )
            },
            confirmButton = {
                Button(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)))
                    update = null
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) { Text("Later") }
            }
        )
    }
}

@Composable
fun MainScaffold(messages: List<SavedMessage>, tab: Int, onTab: (Int) -> Unit) {
    val tabs = listOf(
        "💬" to "Messages",
        "🖼" to "Status",
        "🎤" to "Voice",
        "👁" to "Once",
        "⚙" to "Settings"
    )
    val deleted = remember(messages) { messages.count { it.isDeleted } }
    val subtitle = when (tab) {
        0 -> "${messages.size} saved  •  $deleted deleted"
        1 -> "Keep the statuses you have viewed"
        2 -> "Listen to voice messages again"
        3 -> "View-once photos and videos"
        else -> "Setup and options"
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader("ChatRewind", subtitle) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (emoji, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = { Text(emoji, fontSize = 20.sp) },
                        label = { Text(label) }
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
                3 -> ViewOnceScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun SetupWizard(onSkip: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        AppHeader("ChatRewind", "Just 3 quick steps")
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Before you start", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tap each step’s button, turn the switch on and come back. The app will show the next step by itself.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SetupSteps()
            TextButton(
                onClick = onSkip,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("I’ll do it later")
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
    val delCount = remember(all) { all.count { it.isDeleted } }
    val shown = remember(all, onlyDeleted, query) {
        all.filter { msg ->
            (!onlyDeleted || msg.isDeleted) &&
                (query.isBlank() || msg.text.contains(query, true) || msg.sender.contains(query, true))
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
                        Text(
                            "Notification access is off",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "You need to allow this to save messages.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Allow") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search by message or name") },
                leadingIcon = { Text("🔍") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !onlyDeleted,
                    onClick = { onlyDeleted = false },
                    label = { Text("All (${all.size})") }
                )
                FilterChip(
                    selected = onlyDeleted,
                    onClick = { onlyDeleted = true },
                    label = { Text("🗑 Deleted ($delCount)") }
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    if (onlyDeleted) "🗑" else "💬",
                    if (onlyDeleted) "No deleted messages yet" else "No messages yet",
                    "When someone sends you a WhatsApp message, it is saved here. It stays here even if it gets deleted."
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
            colors = CardDefaults.cardColors(
                containerColor = if (m.isDeleted) cs.errorContainer else cs.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.sender,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(formatTime(m.timestamp), fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(m.text, color = if (m.isDeleted) cs.onErrorContainer else cs.onSurface)
                if (m.isDeleted) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "🗑 The sender deleted this message",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.error
                    )
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
    val loader = remember {
        ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build()
    }
    var ready by remember { mutableStateOf(folderReady(ctx)) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Scanner.run(ctx) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ready = folderReady(ctx)
        scope.launch(Dispatchers.IO) { Scanner.run(ctx) }
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
                            "First give access to the WhatsApp folder in Settings.",
                            Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${files.size} status saved", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "View a status in WhatsApp first, then it will appear here.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { Scanner.run(ctx) }
                            Toast.makeText(ctx, "$n new statuses saved", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("🔄 Refresh") }
                }
            }
        }
        if (files.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    "🖼",
                    "No statuses yet",
                    "Open someone’s status in WhatsApp, then tap Refresh."
                )
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
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { openFile(ctx, f.path) }
    ) {
        AsyncImage(
            model = File(f.path),
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SourceTag(f.isBiz)
            if (f.name.startsWith("notif_")) NotifTag()
        }
        Text(
            formatTime(f.savedAt),
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
        if (isVideo) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) { Text("▶", color = Color.White, fontSize = 20.sp) }
                Spacer(Modifier.height(4.dp))
                Text("VIDEO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Pill("⬇ Save") {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { saveToGallery(ctx, f) }
                    Toast.makeText(
                        ctx,
                        if (ok) "Saved to gallery" else "Couldn’t save",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            Pill("🗑") {
                scope.launch(Dispatchers.IO) {
                    File(f.path).delete()
                    AppDb.get(ctx).dao().deleteFile(f)
                }
            }
        }
    }
}

@Composable
fun SourceTag(isBiz: Boolean) {
    Text(
        if (isBiz) "Business" else "WhatsApp",
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isBiz) Color(0xFF1F7A5C) else Color(0xFF2E8B9E))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
fun NotifTag() {
    Text(
        "NOTIF",
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFB26A00))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/* ------------------------------ VIEW ONCE ------------------------------ */

@Composable
fun ViewOnceScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by AppDb.get(ctx).dao().files("viewonce").collectAsState(initial = emptyList())
    val loader = remember {
        ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build()
    }
    var ready by remember { mutableStateOf(folderReady(ctx)) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Scanner.run(ctx) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ready = folderReady(ctx)
        scope.launch(Dispatchers.IO) { Scanner.run(ctx) }
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
                            "First give access to the WhatsApp folder in Settings.",
                            Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${files.size} view-once items saved", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Saved the moment they arrive. They stay here even after WhatsApp deletes them.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { Scanner.run(ctx) }
                            Toast.makeText(ctx, "$n new items saved", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("🔄 Refresh") }
                }
            }
        }
        if (files.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    "👁",
                    "No view-once media yet",
                    "Ask someone to send a view-once photo or video. Auto-download must be on in WhatsApp."
                )
            }
        }
        items(files, key = { it.id }) { StatusItem(it, loader) }
    }
}

/* ------------------------------ VOICE ------------------------------ */

@Composable
fun VoiceScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by AppDb.get(ctx).dao().files("voice").collectAsState(initial = emptyList())
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Scanner.run(ctx) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch(Dispatchers.IO) { Scanner.run(ctx) }
    }
    DisposableEffect(Unit) { onDispose { VoicePlayer.stop() } }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("${files.size} voice messages saved", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Only voice messages received after monitoring started. Auto-download must be on in WhatsApp.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }
        if (files.isEmpty()) {
            item {
                EmptyState(
                    "🎤",
                    "No voice messages yet",
                    "New voice messages are saved here as soon as they arrive."
                )
            }
        }
        items(files, key = { it.id }) { f ->
            val isPlaying = VoicePlayer.playing == f.path
            AppCard(container = if (isPlaying) cs.primaryContainer else cs.surface) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(cs.primary)
                            .clickable { VoicePlayer.toggle(f.path) },
                        contentAlignment = Alignment.Center
                    ) { Text(if (isPlaying) "⏹" else "▶", color = cs.onPrimary, fontSize = 18.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Voice message", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            SourceTag(f.isBiz)
                        }
                        Text(
                            formatTime(if (f.origModified > 0) f.origModified else f.savedAt) +
                                "  •  ${f.size / 1024} KB",
                            fontSize = 12.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                    Text(
                        "🗑",
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                if (isPlaying) VoicePlayer.stop()
                                scope.launch(Dispatchers.IO) {
                                    File(f.path).delete()
                                    AppDb.get(ctx).dao().deleteFile(f)
                                }
                            }
                            .padding(8.dp)
                    )
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
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val p = Prefs.get(ctx)
            p.edit().putString("tree", uri.toString()).apply()
            if (!p.contains("enabled_at")) {
                p.edit().putLong("enabled_at", System.currentTimeMillis()).apply()
            }
            val root = DocumentFile.fromTreeUri(ctx, uri)
            val okNames = setOf("Media", ".Statuses", "WhatsApp Voice Notes")
            val looksRight = root != null && (
                (root.name ?: "") in okNames ||
                    root.findFile(".Statuses") != null ||
                    root.findFile("WhatsApp Voice Notes") != null
                )
            Toast.makeText(
                ctx,
                if (looksRight) "Folder looks right ✅"
                else "This doesn’t look like WhatsApp’s Media folder. Please choose again.",
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
                    if (readyCount == 3) "All set 🎉" else "Setup: $readyCount/3 ready",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = cs.onPrimaryContainer
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { readyCount / 3f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = cs.primary,
                    trackColor = cs.surface
                )
            }
        }

        SetupCard(
            n = 1,
            title = "Permission to read messages",
            ok = notifOk,
            help = "Needed to save deleted messages. If you see ‘App was denied access’: App info > ⋮ (top right) > ‘Allow restricted settings’, then come back.",
            highlight = current == 0
        ) {
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text("Allow")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            }) { Text("App info") }
        }

        val filesHelp = when {
            Build.VERSION.SDK_INT >= 30 && !allFiles ->
                "For statuses, voice messages and view-once media. Just turn on one switch (All files access)."
            Build.VERSION.SDK_INT >= 30 && !filesOk ->
                "Access granted, but WhatsApp’s folder wasn’t found. Tap ‘Choose folder’ and select the ‘Media’ folder."
            Build.VERSION.SDK_INT >= 30 -> "Access granted ✅ Both WhatsApp and Business will be scanned."
            else -> "Choose a folder: on the screen that opens, tap ‘Use this folder’ at the bottom. The folder must be named ‘Media’."
        }
        SetupCard(
            n = 2,
            title = "Status, voice and view-once files",
            ok = filesOk,
            help = filesHelp,
            highlight = current == 1
        ) {
            if (Build.VERSION.SDK_INT >= 30) {
                Button(onClick = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }) { Text("Allow") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { picker(false) }) { Text("Choose folder") }
            } else {
                Button(onClick = { picker(false) }) { Text("WhatsApp") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { picker(true) }) { Text("Business") }
            }
        }

        SetupCard(
            n = 3,
            title = "Allow background running",
            ok = batteryOk,
            help = "Otherwise your phone may stop the app and new messages will be missed.",
            highlight = current == 2
        ) {
            Button(onClick = {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            }) { Text("Allow") }
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var custom by remember { mutableStateOf(Prefs.get(ctx).getString("custom_deleted", "") ?: "") }
    var fb by remember { mutableStateOf(Prefs.get(ctx).getBoolean("notif_fallback", false)) }
    var logText by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SetupSteps()

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text("Deleted-message text", fontWeight = FontWeight.Bold)
                Text(
                    "If WhatsApp is set to another language, enter the text it shows for a deleted message here.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = {
                        custom = it
                        Prefs.get(ctx).edit().putString("custom_deleted", it).apply()
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text("App version", fontWeight = FontWeight.Bold)
                Text(
                    "v${currentVersionName(ctx)}  (build ${currentVersionCode(ctx)})",
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = {
                    scope.launch {
                        val u = withContext(Dispatchers.IO) { Updater.latest() }
                        val msg = when {
                            u == null -> "Couldn’t check for updates (no internet or no release found)"
                            u.versionCode > currentVersionCode(ctx) -> "New version ${u.name} is available"
                            else -> "You’re on the latest version ✅"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        if (u != null && u.versionCode > currentVersionCode(ctx)) {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)))
                        }
                    }
                }) { Text("🔄 Check for updates") }
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notification fallback", fontWeight = FontWeight.Bold)
                        Text(
                            "Agar folder se file na mile aur koi normal photo bhi na aayi ho, " +
                                "to notification wali image save ho jati hai (orange NOTIF tag).",
                            fontSize = 12.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = fb,
                        onCheckedChange = {
                            fb = it
                            Prefs.get(ctx).edit().putBoolean("notif_fallback", it).apply()
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        AppDb.get(ctx).dao().notifItems().forEach { File(it.path).delete() }
                        AppDb.get(ctx).dao().deleteNotifItems()
                    }
                }) { Text("Delete all NOTIF items") }
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text("Diagnostics", fontWeight = FontWeight.Bold)
                Text(
                    "View-once file kahan aati hai, uska log. Fallback OFF rakh kar test karo.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            logText = withContext(Dispatchers.IO) { Probe.read(ctx) }
                        }
                    }) { Text("Show log") }
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            Probe.logPath(ctx).delete()
                            logText = ""
                        }
                    }) { Text("Clear") }
                }
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text("Data", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { Scanner.run(ctx) }
                        Toast.makeText(ctx, "$n new files saved", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Scan now") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) { AppDb.get(ctx).dao().clearMessages() }
                }) { Text("Delete all messages") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        File(ctx.filesDir, "backup").deleteRecursively()
                        AppDb.get(ctx).dao().clearFiles()
                    }
                }) { Text("Delete all statuses, voice and view-once items") }
            }
        }

        Text(
            "Privacy: your messages, statuses, voice messages and view-once media stay on your phone. The internet is used only to check for a new version. This app is separate from WhatsApp and is not part of it.",
            fontSize = 12.sp,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }

    logText?.let { txt ->
        AlertDialog(
            onDismissRequest = { logText = null },
            title = { Text("Path log") },
            text = {
                Text(
                    txt.ifBlank { "(khaali — pehle view-once bhejwao)" },
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = { logText = null }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { Probe.logPath(ctx).delete() }
                    logText = null
                }) { Text("Clear") }
            }
        )
    }
}

@Composable
fun SetupCard(
    n: Int,
    title: String,
    ok: Boolean,
    help: String,
    highlight: Boolean,
    actions: @Composable RowScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val border = when {
        ok -> BorderStroke(1.dp, cs.primary.copy(alpha = 0.4f))
        highlight -> BorderStroke(1.5.dp, cs.primary)
        else -> BorderStroke(1.dp, cs.outlineVariant)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) cs.surfaceVariant.copy(alpha = 0.5f) else cs.surface
        ),
        border = border
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (ok) cs.primary else cs.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (ok) "✓" else n.toString(),
                        color = if (ok) cs.onPrimary else cs.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                if (ok) {
                    Text(
                        "Done",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.primary
                    )
                }
            }
            Text(
                help,
                fontSize = 13.sp,
                color = cs.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}
