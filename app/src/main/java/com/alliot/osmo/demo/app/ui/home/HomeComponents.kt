package com.alliot.osmo.demo.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alliot.osmo.demo.app.ui.theme.SignalDanger
import com.alliot.osmo.demo.app.ui.theme.SignalReady
import com.alliot.osmo.demo.session.model.HandshakeStage
import com.alliot.osmo.demo.session.model.SessionTransportMode

@Composable
fun HomeDestinationToggle(
    destination: HomeDestination,
    onDestinationSelected: (HomeDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations = HomeDestination.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        destinations.forEachIndexed { index, item ->
            SegmentedButton(
                selected = item == destination,
                onClick = rememberHapticClick(
                    kind = HomeHapticKind.NAVIGATION,
                    onClick = { onDestinationSelected(item) },
                ),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = destinations.size),
            ) {
                Text(
                    when (item) {
                        HomeDestination.WORKBENCH -> "工作台"
                        HomeDestination.MEDIA -> "媒体"
                        HomeDestination.DEBUG_CONSOLE -> "调试台"
                    }
                )
            }
        }
    }
} 

@Composable
fun ConnectionSummaryStrip(
    state: DebugHomeState,
    onOpenConnectionSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = state.workbenchConnectionCardUiModel
    val canNavigate = model.primaryAction != WorkbenchConnectionCardPrimaryAction.PROCESSING
    val cardModifier = modifier
        .fillMaxWidth()
        .then(
            if (canNavigate) {
                Modifier.clickable(
                    role = Role.Button,
                    onClick = rememberHapticClick(
                        kind = HomeHapticKind.NAVIGATION,
                        onClick = onOpenConnectionSheet,
                    ),
                )
            } else {
                Modifier
            },
        )
    val actionColor = if (model.phase == WorkbenchConnectionPhase.FAILURE) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val supportingColor = if (model.phase == WorkbenchConnectionPhase.FAILURE) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionDot(connected = model.phase == WorkbenchConnectionPhase.READY)
                    Spacer(modifier = Modifier.size(8.dp))
                    Column {
                        Text(
                            text = model.statusCopy,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = model.primaryActionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = actionColor,
                    textAlign = TextAlign.End,
                )
            }
            Text(
                text = model.supportingCopy,
                style = MaterialTheme.typography.bodySmall,
                color = supportingColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun HomeFilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: HomeHapticKind = HomeHapticKind.PRIMARY,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        modifier = modifier,
        onClick = rememberHapticClick(kind = kind, onClick = onClick),
        enabled = enabled,
        colors = colors,
    ) {
        content()
    }
}

@Composable
fun HomeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: HomeHapticKind = HomeHapticKind.SECONDARY,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        modifier = modifier,
        onClick = rememberHapticClick(kind = kind, onClick = onClick),
        enabled = enabled,
    ) {
        content()
    }
}

@Composable
fun HomeSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    contentSpacing: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
fun SummaryPill(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(tint)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConnectionDot(connected: Boolean) {
    val tint = if (connected) SignalReady else SignalDanger
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(tint, CircleShape),
    )
}
