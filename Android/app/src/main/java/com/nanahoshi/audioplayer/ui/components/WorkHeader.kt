package com.nanahoshi.audioplayer.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.ui.theme.LocalDarkTheme
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.LocalSourceColors

@Composable
internal fun HeroCoverBackdrop(
    coverUrl: String?,
    topBarInset: Dp,
    clearBandHeight: Dp = 112.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val model = imageModelOf(coverUrl)
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    val dark = LocalDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val request = remember(context, model, reduceMotion) {
        model?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(!reduceMotion)
                .build()
        }
    }
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(modifier.fillMaxWidth()) {
        // Layer 1: Semantic gradient fallback + clear background cover
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.primaryContainer.copy(alpha = if (dark) 0.48f else 0.58f),
                            scheme.surfaceContainer,
                            scheme.background,
                        ),
                    ),
                ),
        )
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.matchParentSize(),
            )
        }

        // Layer 2: Top bar readability gradient
        Box(
            Modifier
                .fillMaxWidth()
                .height(topBarInset + 28.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.surface.copy(alpha = if (dark) 0.56f else 0.62f),
                            scheme.surface.copy(alpha = if (dark) 0.24f else 0.28f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Layer 3: Feathered blurred cover starting from title band (API 31+)
        if (blurSupported && request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val blurStartPx = (topBarInset + clearBandHeight).toPx()
                        val maskStart = (blurStartPx - 28.dp.toPx()).coerceAtLeast(0f)
                        val maskEnd = (blurStartPx + 12.dp.toPx()).coerceAtLeast(maskStart + 1f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black,
                                startY = maskStart,
                                endY = maskEnd,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .blur(radius = 22.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle),
            )
        }

        // Layer 4: Frosted glass scrim + bottom blend into page background
        val background = scheme.background
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val blurStartPx = (topBarInset + clearBandHeight).toPx()
                    val scrimStart = (blurStartPx - 32.dp.toPx()).coerceAtLeast(0f)
                    val scrimEnd = size.height.coerceAtLeast(scrimStart + 1f)
                    val midAlpha = when {
                        blurSupported && dark -> 0.54f
                        blurSupported -> 0.64f
                        dark -> 0.74f
                        else -> 0.80f
                    }
                    val lowerMidAlpha = when {
                        blurSupported && dark -> 0.78f
                        blurSupported -> 0.84f
                        dark -> 0.88f
                        else -> 0.92f
                    }
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to background.copy(alpha = 0f),
                            0.28f to background.copy(alpha = midAlpha),
                            0.70f to background.copy(alpha = lowerMidAlpha),
                            1f to background,
                            startY = scrimStart,
                            endY = scrimEnd,
                        ),
                        topLeft = Offset(0f, scrimStart),
                        size = Size(size.width, (size.height - scrimStart).coerceAtLeast(0f)),
                    )
                },
        )

        // Layer 5: Foreground content
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(topBarInset + clearBandHeight))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun HeroInteractivePill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    accentColor: Color? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    onClick: (() -> Unit)? = null,
) {
    val dark = LocalDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val pillBackground = accentColor?.copy(alpha = 0.16f)
        ?: scheme.surfaceContainerHighest.copy(alpha = if (dark) 0.72f else 0.82f)
    val contentTint = accentColor ?: scheme.onSurface
    val pillShape = MaterialTheme.shapes.extraSmall
    Box(
        modifier = modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .clip(pillShape)
            .clickable(
                enabled = onClick != null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = { onClick?.invoke() },
            )
            .semantics {
                if (!contentDescription.isNullOrBlank()) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(pillShape)
                .background(pillBackground)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = contentTint,
                )
            }
            Text(
                text = text,
                style = textStyle,
                color = contentTint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MutedFactLabel(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun WorkHeader(
    facts: WorkFacts,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    purchased: Boolean = false,
    topBarInset: Dp = 0.dp,
    onSearchCircle: ((String) -> Unit)? = null,
    onSearchVa: ((String) -> Unit)? = null,
    onSearchTag: ((String) -> Unit)? = null,
) {
    val circle = facts.circle.trim()
    val vas = remember(facts.vas) { facts.vas.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
    val tags = remember(facts.tags) { facts.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }

    HeroCoverBackdrop(
        coverUrl = coverUrl,
        topBarInset = topBarInset,
        modifier = modifier,
    ) {
        Text(
            text = facts.title.ifBlank { facts.sourceId.ifBlank { stringResource(R.string.work_unnamed) } },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (facts.sourceId.isNotBlank() || purchased || facts.release.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (facts.sourceId.isNotBlank()) {
                    LabelBadge(facts.sourceId, MaterialTheme.colorScheme.primary)
                }
                if (purchased) {
                    LabelBadge(stringResource(R.string.work_purchased), MaterialTheme.colorScheme.tertiary)
                }
                if (facts.release.isNotBlank()) {
                    IconLabel(Icons.Outlined.CalendarMonth, facts.release)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (circle.isNotEmpty()) {
                HeroInteractivePill(
                    icon = Icons.Outlined.Groups,
                    text = circle,
                    contentDescription = stringResource(R.string.a11y_search_circle, circle),
                    onClick = onSearchCircle?.let { cb -> { cb(circle) } },
                )
            } else {
                MutedFactLabel(
                    icon = Icons.Outlined.Groups,
                    text = stringResource(R.string.work_circle_unknown),
                )
            }
            Text(
                text = "·",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vas.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                vas.forEach { va ->
                    HeroInteractivePill(
                        icon = null,
                        text = va,
                        contentDescription = stringResource(R.string.a11y_search_va, va),
                        onClick = onSearchVa?.let { cb -> { cb(va) } },
                    )
                }
            } else {
                MutedFactLabel(
                    icon = Icons.Outlined.Mic,
                    text = stringResource(R.string.work_va_unknown),
                )
            }
        }

        if (tags.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(tags, key = { it }) { tag ->
                    HeroInteractivePill(
                        icon = null,
                        text = tag,
                        contentDescription = stringResource(R.string.a11y_search_tag, tag),
                        onClick = onSearchTag?.let { cb -> { cb(tag) } },
                    )
                }
            }
        }
    }
}

@Composable
fun BiliVideoHeroHeader(
    title: String,
    bvid: String,
    uploader: String?,
    partCount: Int,
    totalDurationText: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    topBarInset: Dp = 0.dp,
    onSearchUploader: ((String) -> Unit)? = null,
) {
    val cleanUploader = uploader?.trim()?.takeIf { it.isNotEmpty() }

    HeroCoverBackdrop(
        coverUrl = coverUrl,
        topBarInset = topBarInset,
        modifier = modifier,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabelBadge(
                stringResource(R.string.library_badge_bilibili),
                LocalSourceColors.current.bilibili,
            )
            LabelBadge(
                stringResource(R.string.library_badge_multi_part),
                MaterialTheme.colorScheme.secondary,
            )
            if (bvid.isNotBlank()) {
                LabelBadge(bvid, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = stringResource(R.string.bilibili_parts_duration_summary, partCount, totalDurationText),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cleanUploader != null) {
                HeroInteractivePill(
                    icon = Icons.Outlined.Person,
                    text = cleanUploader,
                    contentDescription = stringResource(R.string.a11y_search_uploader, cleanUploader),
                    onClick = onSearchUploader?.let { cb -> { cb(cleanUploader) } },
                )
            } else {
                MutedFactLabel(
                    icon = Icons.Outlined.Person,
                    text = stringResource(R.string.library_uploader_unknown),
                )
            }
        }
    }
}
