package ru.hiddi.messenger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val HiddiInk = Color(0xFF070A0F)
private val HiddiSurface = Color(0xFF0E151E)
private val HiddiRaised = Color(0xFF151E2A)
private val HiddiMint = Color(0xFF5BE3C3)
private val HiddiViolet = Color(0xFFA99BFF)

val hiddiColors = darkColorScheme(
    primary = HiddiMint,
    onPrimary = Color(0xFF032D26),
    primaryContainer = Color(0xFF123C35),
    onPrimaryContainer = Color(0xFFCCFFF2),
    secondary = HiddiViolet,
    onSecondary = Color(0xFF21165A),
    secondaryContainer = Color(0xFF302A4A),
    onSecondaryContainer = Color(0xFFE7E0FF),
    tertiary = Color(0xFF69B9FF),
    background = HiddiInk,
    surface = HiddiSurface,
    surfaceVariant = HiddiRaised,
    onSurface = Color(0xFFF1F5FA),
    onSurfaceVariant = Color(0xFFA7B2C1),
    onBackground = Color(0xFFF1F5FA),
    outline = Color(0xFF334152),
    error = Color(0xFFFF7284),
)

val hiddiTypography = Typography()

enum class HiddiHomeSection { CHATS, CONTACTS }

@Composable
fun ConnectionPill(connection: ServerConnection, onRefresh: () -> Unit) {
    val color = when (connection) {
        ServerConnection.ONLINE -> Color(0xFF55E39A)
        ServerConnection.OFFLINE -> MaterialTheme.colorScheme.error
        ServerConnection.CHECKING -> MaterialTheme.colorScheme.secondary
    }
    val label = when (connection) {
        ServerConnection.ONLINE -> "В сети"
        ServerConnection.OFFLINE -> "Нет связи"
        ServerConnection.CHECKING -> "Проверяем"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onRefresh),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun HiddiHomeNavigation(
    selected: HiddiHomeSection,
    onChats: () -> Unit,
    onContacts: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 10.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            HiddiNavItem(
                selected = selected == HiddiHomeSection.CHATS,
                onClick = onChats,
                label = "Чаты",
                icon = { Icon(Icons.Rounded.ChatBubble, contentDescription = null) },
            )
            HiddiNavItem(
                selected = selected == HiddiHomeSection.CONTACTS,
                onClick = onContacts,
                label = "Контакты",
                icon = { Icon(Icons.Rounded.Contacts, contentDescription = null) },
            )
            HiddiNavItem(
                selected = false,
                onClick = onSettings,
                label = "Настройки",
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun RowScope.HiddiNavItem(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .height(62.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                ) {
                    icon()
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
