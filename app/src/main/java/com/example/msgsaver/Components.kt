package com.example.msgsaver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogoMark(size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF12A887), Color(0xFF07594B)))),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.5f)
        )
    }
}

@Composable
fun AppHeader(title: String, subtitle: String) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0B7A66), Color(0xFF064E41))))
    ) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoMark(46.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }
    }
}

private val avatarColors = listOf(
    Color(0xFF0B7A66), Color(0xFF3F6FD1), Color(0xFFB5527A), Color(0xFFD38B1F),
    Color(0xFF7A57C7), Color(0xFF2E8B9E), Color(0xFFC25B3A)
)

@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    val color = avatarColors[Math.floorMod(name.hashCode(), avatarColors.size)]
    val t = name.trim()
    val first = if (t.isEmpty()) "?" else String(Character.toChars(t.codePointAt(0))).uppercase()
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(first, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = border,
        content = content
    )
}

@Composable
fun EmptyState(emoji: String, title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 52.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
        )
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.22f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
fun StatusChip(ok: Boolean) {
    val cs = MaterialTheme.colorScheme
    Text(
        if (ok) "✅ Ready" else "⚠ Baaki",
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = if (ok) cs.onPrimaryContainer else cs.onErrorContainer,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (ok) cs.primaryContainer else cs.errorContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
