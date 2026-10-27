package com.imhamiddev.mozvpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Corner radius shared by every Moz card. */
val MozCardShape = RoundedCornerShape(24.dp)

/**
 * The base surface of the Moz design system: a softly tinted container with a hairline border.
 * Pass [accent] to tint the border (used for the "protected" state), and [onClick] to make it tappable.
 */
@Composable
fun MozCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(
        width = 1.dp,
        color = (accent ?: MaterialTheme.colorScheme.outlineVariant).copy(alpha = if (accent != null) 0.6f else 0.7f),
    )
    val container = MaterialTheme.colorScheme.surfaceContainer
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = MozCardShape,
            color = container,
            border = border,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = MozCardShape,
            color = container,
            border = border,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}
