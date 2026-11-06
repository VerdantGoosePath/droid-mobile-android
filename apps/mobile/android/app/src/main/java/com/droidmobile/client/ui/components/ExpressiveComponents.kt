package com.droidmobile.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidmobile.client.ui.theme.DroidTokens

/**
 * Material 3 Expressive visual grammar:
 * - tonal roles convey hierarchy, never arbitrary hex values per screen;
 * - connected rows are one surface (not a forest of elevated cards);
 * - one strong action per context, not multiple competing fills;
 * - type roles communicate importance consistently;
 * - responsive sizes come from density-independent tokens.
 */
@Composable
fun SectionHeading(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DroidTokens.gapXS)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (supportingText != null) Text(
            supportingText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun GroupedItems(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(content = content)
    }
}

@Composable
fun GroupedActionRow(
    title: String,
    supportingText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true
) {
    ListItem(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        leadingContent = if (leadingIcon != null) {
            { Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else null,
        headlineContent = {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (supportingText != null) {
            {
                Text(supportingText, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        } else null,
        trailingContent = if (showChevron) {
            { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null
    )
}

@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val positive = status.lowercase() in setOf("ready", "active", "connected", "online")
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (positive) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (positive) MaterialTheme.colorScheme.onTertiaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(status.replaceFirstChar { it.uppercase() },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun DroidEmptyState(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier.fillMaxSize().padding(DroidTokens.pagePadding),
        contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(icon, null,
                    modifier = Modifier.padding(24.dp).size(40.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(supportingText, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DroidLoadingState(
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        LoadingIndicator()
        Spacer(Modifier.height(DroidTokens.gapL))
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InlineError(
    message: String,
    onRetry: (() -> Unit)? = null
) {
    Surface(shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
