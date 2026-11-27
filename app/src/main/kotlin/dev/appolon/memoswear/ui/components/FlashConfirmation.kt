package dev.appolon.memoswear.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.curvedText
import dev.appolon.memoswear.ui.LocalAppContainer

/**
 * Shows the next confirmation addressed to the screen that owns [memoName]: null for the list,
 * a memo's name for its detail screen.
 */
@Composable
fun FlashConfirmation(memoName: String?) {
    val container = LocalAppContainer.current
    val flash by container.flash.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(flash) {
        val pending = flash ?: return@LaunchedEffect
        if (pending.memoName == memoName) {
            showing = pending.message
            container.flash.value = null
        }
    }

    if (showing != null) {
        val message = showing!!
        SuccessConfirmationDialog(
            visible = true,
            onDismissRequest = { showing = null },
            curvedText = { curvedText(message) },
        )
    }
}
