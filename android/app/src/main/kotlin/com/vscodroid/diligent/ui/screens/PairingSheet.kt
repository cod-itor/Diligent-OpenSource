package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.devices.DeviceLink
import com.vscodroid.diligent.devices.PairingUi
import com.vscodroid.diligent.ui.components.SheetButtons
import com.vscodroid.diligent.ui.theme.DiligentTheme
import kotlinx.coroutines.delay

private const val SECOND_MS = 1_000L

/**
 * The pairing flow from [com.vscodroid.diligent.devices.DevicesState.pairing],
 * on either side (Appendix A.6): waiting, typing the code, showing it, and
 * every way it ends. Drawn by Mobile view's native layer, so a request from
 * another device shows on any tab.
 *
 * Closing the sheet while pairing is under way cancels it (on the target
 * that is Decline); closing a result only dismisses it.
 */
@Composable
fun PairingSheet(pairing: PairingUi, link: DeviceLink) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (pairing is PairingUi.ShowCode) {
        LaunchedEffect(pairing.expiresAtMs) {
            while (true) {
                now = System.currentTimeMillis()
                delay(SECOND_MS)
            }
        }
    }
    DiligentSheet(onDismiss = { if (pairing.underWay) link.cancelPairing() else link.dismiss() }) { hide ->
        PairingContent(
            pairing = pairing,
            now = now,
            onSubmit = link::submitCode,
            onClose = { hide {} },
            autoFocus = true,
        )
    }
}

/** Waiting, typing or showing a code: closing cancels it. */
private val PairingUi.underWay: Boolean
    get() = this is PairingUi.Requesting || this is PairingUi.EnterCode || this is PairingUi.ShowCode

@Composable
internal fun PairingContent(
    pairing: PairingUi,
    now: Long,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DiligentTheme.spacing.l)
            .padding(bottom = DiligentTheme.spacing.l)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        when (pairing) {
            is PairingUi.Requesting -> {
                DevicesTitle(stringResource(R.string.diligent_pair_requesting_title, pairing.name))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                DevicesBody(stringResource(R.string.diligent_pair_requesting_body, pairing.name))
                CloseButton(R.string.diligent_devices_cancel, onClose)
            }
            is PairingUi.EnterCode -> EnterCode(pairing, onSubmit, onClose, autoFocus)
            is PairingUi.ShowCode -> {
                DevicesTitle(stringResource(R.string.diligent_pair_show_title, pairing.requesterName))
                DevicesBody(stringResource(R.string.diligent_pair_show_body, pairing.requesterName))
                val spoken = stringResource(R.string.diligent_pair_code_spoken, pairing.code.toList().joinToString(" "))
                Text(
                    groupCode(pairing.code),
                    modifier = Modifier.align(Alignment.CenterHorizontally).clearAndSetSemantics { contentDescription = spoken },
                    style = DiligentTheme.type.material.displayMedium.copy(fontFamily = FontFamily.Monospace),
                    color = DiligentTheme.colors.onSurface,
                )
                DevicesBody(
                    stringResource(R.string.diligent_pair_expires, countdownText(secondsLeft(pairing.expiresAtMs, now))),
                    Modifier.align(Alignment.CenterHorizontally),
                )
                CloseButton(R.string.diligent_pair_decline, onClose)
            }
            is PairingUi.Paired -> Result(
                stringResource(R.string.diligent_pair_paired_title, pairing.name),
                stringResource(R.string.diligent_pair_paired_body, pairing.name),
                R.string.diligent_pair_done,
                onClose,
            )
            is PairingUi.Expired -> Result(
                stringResource(R.string.diligent_pair_expired_title),
                stringResource(R.string.diligent_pair_expired_body, pairing.name),
                R.string.diligent_devices_close,
                onClose,
            )
            is PairingUi.Locked -> Result(
                stringResource(R.string.diligent_pair_locked_title),
                stringResource(R.string.diligent_pair_locked_body, pairing.name),
                R.string.diligent_devices_close,
                onClose,
            )
            is PairingUi.IdentityChanged -> Result(
                stringResource(R.string.diligent_pair_identity_title, pairing.name),
                stringResource(R.string.diligent_devices_identity_changed),
                R.string.diligent_devices_close,
                onClose,
            )
            is PairingUi.Failed -> Result(
                stringResource(R.string.diligent_pair_failed_title, pairing.name),
                pairing.reason,
                R.string.diligent_devices_close,
                onClose,
            )
        }
    }
}

/** The requester's code field, with what went wrong last time. A wrong code clears the field. */
@Composable
private fun EnterCode(pairing: PairingUi.EnterCode, onSubmit: (String) -> Unit, onCancel: () -> Unit, autoFocus: Boolean) {
    var code by rememberSaveable(pairing.triesLeft) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { if (code.length == PAIRING_CODE_LENGTH) onSubmit(code) }
    DevicesTitle(stringResource(R.string.diligent_pair_enter_title, pairing.name))
    DevicesBody(stringResource(R.string.diligent_pair_enter_body, pairing.name))
    if (pairing.wrongBefore) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            DevicesNotice(stringResource(R.string.diligent_pair_wrong, pairing.name))
            DevicesBody(pluralStringResource(R.plurals.diligent_pair_tries_left, pairing.triesLeft, pairing.triesLeft))
        }
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = cleanCode(it) },
        label = { Text(stringResource(R.string.diligent_pair_code_label)) },
        singleLine = true,
        isError = pairing.wrongBefore && code.isEmpty(),
        textStyle = DiligentTheme.type.material.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    SheetButtons(
        confirm = stringResource(R.string.diligent_pair_pair),
        confirmEnabled = code.length == PAIRING_CODE_LENGTH,
        onConfirm = submit,
        onCancel = onCancel,
        cancel = stringResource(R.string.diligent_devices_cancel),
    )
}

/** How pairing ended, and one button to close it. */
@Composable
private fun Result(title: String, body: String, button: Int, onClose: () -> Unit) {
    DevicesTitle(title)
    DevicesBody(body)
    CloseButton(button, onClose)
}

@Composable
private fun CloseButton(label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
        Text(stringResource(label))
    }
}

// -- Previews --

private val PairingSamples = listOf(
    PairingUi.Requesting("MacBook-Air"),
    PairingUi.EnterCode("MacBook-Air", triesLeft = 5, wrongBefore = false),
    PairingUi.EnterCode("MacBook-Air", triesLeft = 3, wrongBefore = true),
    PairingUi.ShowCode("482913", "Toto's Laptop", expiresAtMs = PREVIEW_DEVICES_NOW + 102_000L),
    PairingUi.Paired("MacBook-Air"),
    PairingUi.Expired("MacBook-Air"),
    PairingUi.Locked("MacBook-Air"),
    PairingUi.IdentityChanged("Work PC"),
    PairingUi.Failed("MacBook-Air", "MacBook-Air did not answer. Check that both devices are on the same Wi-Fi."),
)

@Composable
private fun PairingSamplesColumn() {
    Column(verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
        for (pairing in PairingSamples) {
            Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) {
                PairingContent(pairing, PREVIEW_DEVICES_NOW, onSubmit = {}, onClose = {}, modifier = Modifier.padding(top = DiligentTheme.spacing.l))
            }
        }
    }
}

@Preview(name = "Pairing, every state, light", widthDp = 360, heightDp = 2600)
@Composable
private fun PairingLightPreview() = DiligentTheme(darkTheme = false) { PairingSamplesColumn() }

@Preview(name = "Pairing, every state, dark", widthDp = 360, heightDp = 2600)
@Composable
private fun PairingDarkPreview() = DiligentTheme(darkTheme = true) { PairingSamplesColumn() }

@Preview(name = "Pairing, show code, large font", widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun PairingLargeFontPreview() = DiligentTheme(darkTheme = false) {
    Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) {
        PairingContent(PairingSamples[3], PREVIEW_DEVICES_NOW, onSubmit = {}, onClose = {})
    }
}
