package com.imhamiddev.mozvpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imhamiddev.mozvpn.data.model.ConnectionState
import com.imhamiddev.mozvpn.ui.theme.LocalMozStatusColors

/**
 * The main connect control. A glowing orb surrounded by a progress ring:
 *  - disconnected: muted disc with an empty ring
 *  - connecting: a bright arc spins around the ring
 *  - connected: the full ring lights up in the "protected" color and the orb gently breathes
 */
@Composable
fun ConnectOrb(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 248.dp,
) {
    val statusColors = LocalMozStatusColors.current
    val target = when (state) {
        ConnectionState.CONNECTED -> statusColors.connected
        ConnectionState.CONNECTING -> statusColors.connecting
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    val accent by animateColorAsState(target, label = "orb-accent")
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val idleTop = MaterialTheme.colorScheme.surfaceContainerHighest
    val idleBottom = MaterialTheme.colorScheme.surfaceContainer
    val idleIcon = MaterialTheme.colorScheme.onSurfaceVariant
    val active = state != ConnectionState.DISCONNECTED

    val transition = rememberInfiniteTransition(label = "orb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "orb-rotation",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-breathe",
    )
    val discScale by animateFloatAsState(
        targetValue = if (state == ConnectionState.CONNECTED) 1f else 0.94f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "orb-disc-scale",
    )

    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val outer = this.size.minDimension / 2f

                if (active) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.32f), Color.Transparent),
                            center = center,
                            radius = outer,
                        ),
                        radius = outer,
                        center = center,
                    )
                }

                val strokeWidth = 7.dp.toPx()
                val ringRadius = outer * 0.80f
                val topLeft = Offset(center.x - ringRadius, center.y - ringRadius)
                val arcSize = Size(ringRadius * 2f, ringRadius * 2f)

                drawCircle(
                    color = trackColor.copy(alpha = 0.7f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = strokeWidth),
                )
                when (state) {
                    ConnectionState.CONNECTING -> drawArc(
                        color = accent,
                        startAngle = rotation,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                    ConnectionState.CONNECTED -> drawCircle(
                        color = accent,
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = strokeWidth),
                    )
                    ConnectionState.DISCONNECTED -> Unit
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.54f)
                .graphicsLayer {
                    val s = discScale * (if (state == ConnectionState.CONNECTED) breathe else 1f)
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(
                    if (active) {
                        Brush.linearGradient(listOf(accent.copy(alpha = 0.80f), accent))
                    } else {
                        Brush.verticalGradient(listOf(idleTop, idleBottom))
                    },
                )
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = if (state == ConnectionState.DISCONNECTED) "Connect" else "Disconnect",
                tint = if (active) Color.White else idleIcon,
                modifier = Modifier.size(size * 0.22f),
            )
        }
    }
}
