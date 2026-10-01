package com.github.codeworkscreativehub.mlauncher.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.style.SettingsTheme
import com.github.codeworkscreativehub.mlauncher.style.SettingsType
import kotlin.math.abs
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(28.dp)
private val ScreenPadding = 20.dp

/**
 * One settings screen: optional back button, large title, scrolling content.
 * Keeps clear of the status bar and the navigation bar.
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = SettingsTheme.palette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets(top = 24.dp)))
            .padding(horizontal = ScreenPadding)
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .offset(x = (-8).dp)
                    .size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = getLocalizedString(R.string.st_back),
                    tint = palette.text,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
        } else {
            Spacer(Modifier.height(36.dp))
        }
        Text(
            text = title,
            style = SettingsType.screenTitle,
            color = palette.text,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )
        content()
        Spacer(Modifier.height(32.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** Accent-coloured caption above a card. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = SettingsType.section,
        color = SettingsTheme.palette.accent,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp)
    )
}

/** Rounded group of rows. No border, no shadow. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(SettingsTheme.palette.surface)
            .padding(vertical = 6.dp),
        content = content
    )
}

@Composable
fun CardGap() = Spacer(Modifier.height(12.dp))

/** Big entry on the main screen that opens a category. */
@Composable
fun CategoryRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = SettingsTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.accentTile),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SettingsType.categoryTitle, color = palette.text)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = SettingsType.subtitle,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String?, modifier: Modifier, titleFont: FontFamily? = null) {
    val palette = SettingsTheme.palette
    Column(modifier) {
        Text(
            title,
            style = if (titleFont != null) SettingsType.rowTitle.copy(fontFamily = titleFont) else SettingsType.rowTitle,
            color = palette.text
        )
        if (!subtitle.isNullOrEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = SettingsType.subtitle, color = palette.textSecondary)
        }
    }
}

private fun Modifier.rowPadding() = this
    .fillMaxWidth()
    .heightIn(min = 72.dp)
    .padding(horizontal = 20.dp, vertical = 12.dp)

/** Row with a switch. The whole row toggles. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = SettingsTheme.palette
    Row(
        modifier = Modifier
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .rowPadding(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowTexts(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = palette.textSecondary,
                uncheckedTrackColor = palette.surfaceVariant,
                uncheckedBorderColor = Color.Transparent,
            )
        )
    }
}

/**
 * Row that opens something. [value] shows on the right in secondary text,
 * [subtitle] goes under the title (use it for long values).
 */
@Composable
fun SelectRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    showChevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val palette = SettingsTheme.palette
    Row(
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .rowPadding(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowTexts(title, subtitle, Modifier.weight(1f))
        if (!value.isNullOrEmpty()) {
            Spacer(Modifier.width(16.dp))
            Text(
                value,
                style = SettingsType.value,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp)
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(180f)
            )
        }
    }
}

/**
 * Title with a live value label and a slider underneath.
 * The label follows the thumb; [onCommit] runs when the finger lifts.
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    subtitle: String? = null,
    onCommit: (Float) -> Unit,
) {
    val palette = SettingsTheme.palette
    // A range with nothing to choose (for example 1..1) would crash the slider.
    val usable = range.endInclusive > range.start
    val safeRange = if (usable) range else range.start..(range.start + 1f)
    var current by remember(value, range) { mutableFloatStateOf(value.coerceIn(safeRange)) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowTexts(title, subtitle, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(format(current), style = SettingsType.value, color = palette.accent)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current) },
            valueRange = safeRange,
            enabled = usable,
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.surfaceVariant,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** True when [color] would disappear against [background]. */
private fun lowContrast(color: Color, background: Color) =
    abs(color.luminance() - background.luminance()) < 0.06f

@Composable
fun ColorSwatch(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val palette = SettingsTheme.palette
    val ring = if (lowContrast(color, palette.surface)) {
        Modifier.border(1.dp, palette.textSecondary.copy(alpha = 0.5f), CircleShape)
    } else Modifier
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(ring)
    )
}

fun colorHex(color: Int) = String.format("#%06X", 0xFFFFFF and color)

/** Row that shows a colour swatch and its hex value. */
@Composable
fun ColorRow(title: String, color: Int, onClick: () -> Unit) {
    val palette = SettingsTheme.palette
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .rowPadding(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowTexts(title, null, Modifier.weight(1f))
        Text(colorHex(color), style = SettingsType.value, color = palette.textSecondary)
        Spacer(Modifier.width(14.dp))
        ColorSwatch(Color(color or 0xFF000000.toInt()), 30.dp)
    }
}

/** Small explanatory text inside a card. */
@Composable
fun CardNote(text: String) {
    Text(
        text,
        style = SettingsType.subtitle,
        color = SettingsTheme.palette.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
    )
}

/** Pill-shaped search field. */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val palette = SettingsTheme.palette
    val focus = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(palette.surface)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(getLocalizedString(R.string.st_search_hint), style = SettingsType.rowTitle, color = palette.textSecondary)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = SettingsType.rowTitle.copy(color = palette.text),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = getLocalizedString(R.string.st_clear_search),
                    tint = palette.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------

/** Base dialog: rounded surface, title, content, buttons on the right. */
@Composable
fun SettingsDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = SettingsTheme.palette
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = CardShape,
            color = palette.surface,
            contentColor = palette.text,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(
                    title,
                    style = SettingsType.dialogTitle,
                    color = palette.text,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) { content() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) { buttons() }
            }
        }
    }
}

@Composable
fun DialogButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text, style = SettingsType.button, color = SettingsTheme.palette.accent)
    }
}

/** Single choice list. Picking an option applies it and closes the dialog. */
@Composable
fun OptionsDialog(
    title: String,
    options: List<String>,
    selected: Int,
    fonts: List<FontFamily?>? = null,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val palette = SettingsTheme.palette
    SettingsDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = { DialogButton(getLocalizedString(R.string.st_cancel), onDismiss) }
    ) {
        options.forEachIndexed { index, label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = index == selected, role = Role.RadioButton) {
                        onDismiss()
                        onSelect(index)
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = index == selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = palette.accent,
                        unselectedColor = palette.textSecondary
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                val font = fonts?.getOrNull(index)
                Text(
                    label,
                    style = if (font != null) SettingsType.option.copy(fontFamily = font) else SettingsType.option,
                    color = palette.text
                )
            }
        }
    }
}

/** Several on/off choices. Each tap is saved right away through [onToggle]. */
@Composable
fun FlagsDialog(
    title: String,
    labels: List<String>,
    checked: List<Boolean>,
    onDismiss: () -> Unit,
    onToggle: (index: Int, value: Boolean) -> Unit,
) {
    val palette = SettingsTheme.palette
    SettingsDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = { DialogButton(getLocalizedString(R.string.st_done), onDismiss) }
    ) {
        labels.forEachIndexed { index, label ->
            val isChecked = checked.getOrElse(index) { false }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = isChecked, role = Role.Checkbox) { onToggle(index, it) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.accent,
                        checkmarkColor = palette.onAccent,
                        uncheckedColor = palette.textSecondary
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(label, style = SettingsType.option, color = palette.text)
            }
        }
    }
}

class DialogAction(val label: String, val icon: ImageBitmap? = null, val onClick: () -> Unit)

/** A short list of actions, optionally with an icon each. Tapping one closes the dialog. */
@Composable
fun ActionsDialog(
    title: String,
    actions: List<DialogAction>,
    message: String? = null,
    onDismiss: () -> Unit,
) {
    val palette = SettingsTheme.palette
    SettingsDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = { DialogButton(getLocalizedString(R.string.st_cancel), onDismiss) }
    ) {
        if (message != null) {
            Text(
                message,
                style = SettingsType.subtitle,
                color = palette.textSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
        }
        actions.forEach { action ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable {
                        onDismiss()
                        action.onClick()
                    }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (action.icon != null) {
                    Image(bitmap = action.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(16.dp))
                }
                Text(action.label, style = SettingsType.option, color = palette.text)
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SettingsDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            DialogButton(getLocalizedString(R.string.st_cancel), onDismiss)
            DialogButton(confirmLabel) {
                onDismiss()
                onConfirm()
            }
        }
    ) {
        Text(
            message,
            style = SettingsType.subtitle.copy(fontSize = SettingsType.rowTitle.fontSize),
            color = SettingsTheme.palette.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
    }
}

private val PresetColors = listOf(
    0xFFE4B9E2, 0xFF3B1830, 0xFFFFFFFF, 0xFF000000,
    0xFFF3EAF3, 0xFFB8A9B8, 0xFFF2B8B5, 0xFFFFB59E,
    0xFFF6D186, 0xFFC5E1A5, 0xFF9ED9C5, 0xFF9CCAF0,
    0xFFB7B3F5, 0xFF7D5BA6, 0xFF1E1826, 0xFF5E5E66,
).map { it.toInt() }

/** Colour picker: presets, hue/saturation/brightness sliders and a hex field. */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val palette = SettingsTheme.palette
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var bri by remember { mutableFloatStateOf(start[2]) }
    // HSV does not always round-trip to the same RGB, so keep the exact value from a preset or the hex field.
    var exact by remember { mutableStateOf<Int?>(initial) }
    val color = exact ?: android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri))
    var hexText by remember { mutableStateOf(colorHex(initial).removePrefix("#")) }

    fun setColor(c: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        hue = hsv[0]; sat = hsv[1]; bri = hsv[2]
        exact = c
    }

    fun syncHex() {
        exact = null
        hexText = colorHex(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri))).removePrefix("#")
    }

    SettingsDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            DialogButton(getLocalizedString(R.string.st_cancel), onDismiss)
            DialogButton(getLocalizedString(R.string.st_done)) {
                onDismiss()
                onPick(color or 0xFF000000.toInt())
            }
        }
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            // Preview and hex value
            Row(verticalAlignment = Alignment.CenterVertically) {
                val previewColor = Color(color)
                val ring = if (lowContrast(previewColor, palette.surface)) {
                    Modifier.border(1.dp, palette.textSecondary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                } else Modifier
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(previewColor)
                        .then(ring)
                )
                Spacer(Modifier.width(16.dp))
                Row(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.surfaceVariant)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("#", style = SettingsType.rowTitle, color = palette.textSecondary)
                    Spacer(Modifier.width(4.dp))
                    BasicTextField(
                        value = hexText,
                        onValueChange = { input ->
                            val clean = input.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            hexText = clean
                            if (clean.length == 6) {
                                clean.toLongOrNull(16)?.let { setColor((it or 0xFF000000).toInt()) }
                            }
                        },
                        singleLine = true,
                        textStyle = SettingsType.rowTitle.copy(color = palette.text),
                        cursorBrush = SolidColor(palette.accent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Presets, two rows of eight
            PresetColors.chunked(8).forEach { rowColors ->
                Row(Modifier.fillMaxWidth()) {
                    rowColors.forEach { preset ->
                        val isSelected = (preset and 0xFFFFFF) == (color and 0xFFFFFF)
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, palette.accent, CircleShape) else Modifier
                                )
                                .clickable {
                                    setColor(preset)
                                    hexText = colorHex(preset).removePrefix("#")
                                }
                                .padding(if (isSelected) 5.dp else 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ColorSwatch(Color(preset), 40.dp, Modifier.fillMaxSize())
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            PickerSlider(getLocalizedString(R.string.st_hue), "${hue.roundToInt()}°", hue, 0f..360f) {
                hue = it; syncHex()
            }
            PickerSlider(getLocalizedString(R.string.st_saturation), "${(sat * 100).roundToInt()}%", sat, 0f..1f) {
                sat = it; syncHex()
            }
            PickerSlider(getLocalizedString(R.string.st_brightness), "${(bri * 100).roundToInt()}%", bri, 0f..1f) {
                bri = it; syncHex()
            }
        }
    }
}

@Composable
private fun PickerSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val palette = SettingsTheme.palette
    Column(Modifier.padding(top = 8.dp)) {
        Row {
            Text(label, style = SettingsType.subtitle, color = palette.text, modifier = Modifier.weight(1f))
            Text(valueText, style = SettingsType.subtitle, color = palette.textSecondary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.surfaceVariant,
            )
        )
    }
}
