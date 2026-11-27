package dev.appolon.memoswear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * A text field sized for a watch: a rounded surface that lights its border when focused, with
 * Wear typography. Wear Material 3 has no text field of its own, so this wraps the foundation one.
 * The caller owns the text and the selection through [value].
 */
@Composable
fun WatchTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    password: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val textStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    val shape = if (singleLine) RoundedCornerShape(percent = 50) else RoundedCornerShape(18.dp)
    val border = if (focused) Modifier.border(2.dp, colors.primary, shape) else Modifier

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .then(border)
            .onFocusChanged { focused = it.isFocused },
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(
            capitalization = if (password) KeyboardCapitalization.None else capitalization,
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = if (onDone != null || singleLine) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                if (value.text.isEmpty() && value.annotatedString.isEmpty()) {
                    Text(placeholder, style = textStyle, color = colors.onSurfaceVariant)
                }
                innerTextField()
            }
        },
    )
}

/** Overload for callers that only need the text. */
@Composable
fun WatchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    password: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    // The parent echoes the text back; keep our copy meanwhile so the selection survives typing.
    val shown = if (field.text == value) field else field.copy(text = value)
    WatchTextField(
        value = shown,
        onValueChange = { next ->
            field = next
            if (next.text != value) onValueChange(next.text)
        },
        modifier = modifier,
        placeholder = placeholder,
        singleLine = singleLine,
        keyboardType = keyboardType,
        capitalization = capitalization,
        password = password,
        onDone = onDone,
    )
}
