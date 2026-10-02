package cc.khixang.axonhub.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cc.khixang.axonhub.R

/** Foundation-only controls. Material is used for text/icon drawing and tokens, never widgets/ripple. */
private fun Modifier.iosClick(onClick: () -> Unit, enabled: Boolean = true, source: MutableInteractionSource) =
    clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)

@Composable
fun IosCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        modifier.then(if (onClick != null) Modifier.heightIn(min = 48.dp) else Modifier).shadow(2.dp, shape).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), shape)
            .then(if (onClick != null) Modifier.iosClick(onClick, source = source) else Modifier)
            .alpha(if (pressed) 0.76f else 1f), content = content,
    )
}

@Composable
fun IosSectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun IosPageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun IosSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValueChange, modifier, singleLine = true,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onValueChange("") }) { Icon(Icons.Default.Cancel, stringResource(R.string.ios_clear), Modifier.size(18.dp)) } },
    )
}

@Composable
fun IosSegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (options.isEmpty()) return
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp).selectableGroup()) {
        options.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            val source = remember { MutableInteractionSource() }
            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(8.dp)) else Modifier)
                .clip(RoundedCornerShape(8.dp)).background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .selectable(selected, interactionSource = source, indication = null, role = Role.Tab, onClick = { onSelect(index) })
                .padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(title, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun IosAction(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, primary: Boolean,
    plain: Boolean, colors: ButtonColors?, contentPadding: PaddingValues, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = MaterialTheme.colorScheme.onSurface
    // Keep explicitly destructive colors; ordinary primary actions use adaptive neutral ink.
    val destructive = colors?.containerColor == MaterialTheme.colorScheme.error || colors?.contentColor == MaterialTheme.colorScheme.error
    val background = when { plain -> Color.Transparent; destructive && primary -> MaterialTheme.colorScheme.error; primary -> ink; else -> MaterialTheme.colorScheme.surfaceVariant }
    val foreground = when { destructive && (!primary || plain) -> MaterialTheme.colorScheme.error; primary && !plain -> MaterialTheme.colorScheme.surface; else -> ink }
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(background)
        .then(if (!primary && !plain) Modifier.border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)) else Modifier)
        .iosClick(onClick, enabled, source).alpha(if (!enabled) 0.42f else if (pressed) 0.65f else 1f)
        .padding(contentPadding), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides foreground) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors? = null, shape: Shape = RoundedCornerShape(10.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp), content: @Composable RowScope.() -> Unit) =
    IosAction(onClick, modifier, enabled, true, false, colors, contentPadding, content)

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors? = null, shape: Shape = RoundedCornerShape(10.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp), content: @Composable RowScope.() -> Unit) =
    IosAction(onClick, modifier, enabled, false, false, colors, contentPadding, content)

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp), content: @Composable RowScope.() -> Unit) =
    IosAction(onClick, modifier, enabled, false, true, colors, contentPadding, content)

@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors? = null, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(12.dp))
        .iosClick(onClick, enabled, source).alpha(if (!enabled) 0.42f else if (pressed) 0.5f else 1f), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides (colors?.contentColor ?: MaterialTheme.colorScheme.onSurface)) { content() }
    }
}

@Composable
fun FilledIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors? = null, content: @Composable () -> Unit) =
    IconButton(onClick, modifier.background(colors?.containerColor ?: MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)), enabled, colors, content)

@Composable
fun Card(modifier: Modifier = Modifier, colors: CardColors? = null, shape: Shape = RoundedCornerShape(24.dp),
    elevation: CardElevation? = null, content: @Composable ColumnScope.() -> Unit) = IosCard(modifier, content = content)
@Composable
fun Card(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: CardColors? = null, content: @Composable ColumnScope.() -> Unit) = IosCard(modifier, if (enabled) onClick else null, content)
@Composable
fun ElevatedCard(modifier: Modifier = Modifier, colors: CardColors? = null, content: @Composable ColumnScope.() -> Unit) = IosCard(modifier, content = content)
@Composable
fun ElevatedCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: CardColors? = null, content: @Composable ColumnScope.() -> Unit) = IosCard(modifier, if (enabled) onClick else null, content)
@Composable
fun OutlinedCard(modifier: Modifier = Modifier, colors: CardColors? = null, content: @Composable ColumnScope.() -> Unit) = IosCard(modifier, content = content)
@Composable
fun OutlinedCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: CardColors? = null, content: @Composable ColumnScope.() -> Unit) = IosCard(modifier, if (enabled) onClick else null, content)

@Composable
fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (label != null) CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            ProvideTextStyle(MaterialTheme.typography.bodySmall) { label() }
        }
        BasicTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (isError) Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp)) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .semantics { if (isError) error("Invalid input") },
            enabled = enabled, readOnly = readOnly, textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
            maxLines = maxLines, minLines = minLines, visualTransformation = visualTransformation,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    leadingIcon?.let { it(); Spacer(Modifier.width(8.dp)) }
                    Box(Modifier.weight(1f)) { if (value.isEmpty()) placeholder?.invoke(); inner() }
                    trailingIcon?.let { Spacer(Modifier.width(4.dp)); it() }
                }
            },
        )
        supportingText?.let { ProvideTextStyle(MaterialTheme.typography.bodySmall) { it() } }
    }
}

@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "switch")
    val on = Color(0xFF34C759)
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp).then(
        if (onCheckedChange != null) Modifier.toggleable(checked, source, null, enabled, Role.Switch) { onCheckedChange(it) }
        else Modifier.semantics { this.role = Role.Switch; this.toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }
    ).alpha(if (enabled) 1f else 0.4f)) {
        val width = 51.dp.toPx(); val height = 31.dp.toPx()
        val left = (size.width - width) / 2; val top = (size.height - height) / 2
        drawRoundRect(if (checked) on else off, Offset(left, top), androidx.compose.ui.geometry.Size(width, height), androidx.compose.ui.geometry.CornerRadius(height / 2))
        drawCircle(Color.Black.copy(alpha = 0.10f), 14.dp.toPx(), Offset(left + 16.dp.toPx() + 19.dp.toPx() * progress, top + height / 2 + 1.dp.toPx()))
        drawCircle(Color.White, 13.5.dp.toPx(), Offset(left + 16.dp.toPx() + 19.dp.toPx() * progress, top + height / 2))
    }
}

@Composable
fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).then(if (onCheckedChange != null)
        Modifier.toggleable(checked, source, null, enabled, Role.Checkbox) { onCheckedChange(it) } else Modifier)
        .alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun RadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).then(if (onClick != null)
        Modifier.selectable(selected, source, null, enabled, Role.RadioButton, onClick) else Modifier), contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun AssistChip(onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    Row(modifier.heightIn(min = 48.dp).iosClick(onClick, enabled, source).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ProvideTextStyle(MaterialTheme.typography.labelMedium) { leadingIcon?.invoke(); label(); trailingIcon?.invoke() }
        }
    }
}

/** Finite-height sheet, including landscape/IME. Caller controls busy/dismiss policy. */
@Composable
fun AlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false)) {
    Dialog(onDismissRequest, properties) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            Column(modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.background)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { dismissButton?.invoke() }
                    Column(Modifier.weight(2f), horizontalAlignment = Alignment.CenterHorizontally) {
                        icon?.invoke(); title?.let { ProvideTextStyle(MaterialTheme.typography.titleMedium) { it() } }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { confirmButton() }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) { text?.invoke() }
            }
        }
    }
}

@Composable
fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (expanded) Popup(onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        Column(modifier.widthIn(min = 180.dp, max = 360.dp).heightIn(max = 400.dp)
            .shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(6.dp), content = content)
    }
}
@Composable
fun DropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).iosClick(onClick, enabled, source).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leadingIcon?.invoke(); Box(Modifier.weight(1f)) { text() }; trailingIcon?.invoke()
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 0.75.dp, color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Box(modifier.fillMaxWidth().height(thickness).background(color))
}
@Composable
fun CircularProgressIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth: Dp = 2.dp) {
    val transition = rememberInfiniteTransition(label = "activity")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "rotation")
    val loading = stringResource(R.string.ios_loading)
    Canvas(modifier.size(24.dp).semantics { contentDescription = loading; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        repeat(12) { segment ->
            val angle = Math.toRadians((rotation + segment * 30).toDouble())
            val radius = size.minDimension * 0.35f
            val a = Offset(center.x + kotlin.math.cos(angle).toFloat() * radius, center.y + kotlin.math.sin(angle).toFloat() * radius)
            val b = Offset(center.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.47f, center.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.47f)
            drawLine(color.copy(alpha = (segment + 1) / 12f), a, b, strokeWidth.toPx(), StrokeCap.Round)
        }
    }
}
@Composable
fun LinearProgressIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "loading")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "progress")
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(3.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        drawLine(track, Offset(0f, center.y), Offset(size.width, center.y), size.height, StrokeCap.Round)
        drawLine(color, Offset(size.width * progress * 0.7f, center.y), Offset(size.width * (progress * 0.7f + 0.3f), center.y), size.height, StrokeCap.Round)
    }
}
@Composable
fun LinearProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val value = progress().coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(4.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }) {
        drawLine(track, Offset(0f, center.y), Offset(size.width, center.y), size.height, StrokeCap.Round)
        drawLine(color, Offset(0f, center.y), Offset(size.width * value, center.y), size.height, StrokeCap.Round)
    }
}

@Composable
fun Slider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0) {
    var width by remember { mutableStateOf(1f) }
    val currentChange by rememberUpdatedState(onValueChange)
    fun update(x: Float) {
        val fraction = (x / width).coerceIn(0f, 1f)
        val snapped = if (steps > 0) kotlin.math.round(fraction * (steps + 1)) / (steps + 1) else fraction
        currentChange(valueRange.start + snapped * (valueRange.endInclusive - valueRange.start))
    }
    val tint = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().heightIn(min = 48.dp).onSizeChanged { width = it.width.toFloat() }
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
            if (enabled) setProgress { currentChange(it.coerceIn(valueRange.start, valueRange.endInclusive)); true } else disabled()
        }.pointerInput(enabled, valueRange, steps) { if (enabled) detectTapGestures { update(it.x) } }
        .pointerInput(enabled, valueRange, steps) { if (enabled) detectHorizontalDragGestures { change, _ -> change.consume(); update(change.position.x) } }) {
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        drawLine(track, Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(tint, Offset(0f, center.y), Offset(size.width * fraction, center.y), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = 0.12f), 14.dp.toPx(), Offset(size.width * fraction, center.y + 1.dp.toPx()))
        drawCircle(Color.White, 13.dp.toPx(), Offset(size.width * fraction, center.y))
    }
}

/** Non-Material scaffold compatibility, preserving inset consumption for nested screens. */
@Composable
fun Scaffold(modifier: Modifier = Modifier, topBar: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        topBar()
        Box(Modifier.weight(1f)) {
            content(PaddingValues(0.dp))
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { floatingActionButton() }
        }
        bottomBar()
    }
}
@Composable
fun TopAppBar(title: @Composable () -> Unit, modifier: Modifier = Modifier, navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        navigationIcon(); Box(Modifier.weight(1f)) { ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() } }; actions()
    }
}
@Composable
fun FloatingActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    IconButton(onClick, modifier.shadow(3.dp, RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)), content = content)

// Compatibility for remaining callers; new screens should prefer IosSegmentedControl.
@Composable
fun SingleChoiceSegmentedButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp).selectableGroup(), content = content)
}
@Composable
fun RowScope.SegmentedButton(selected: Boolean, onClick: () -> Unit, shape: Shape,
    modifier: Modifier = Modifier, enabled: Boolean = true, label: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
        .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
        .selectable(selected, source, null, enabled, Role.Tab, onClick).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { label() }
}
