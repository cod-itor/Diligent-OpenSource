package com.vscodroid.diligent.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vscodroid.R
import com.vscodroid.diligent.ActiveShare
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.ShareKeeper
import com.vscodroid.diligent.ShareKind
import com.vscodroid.diligent.ShareStart
import com.vscodroid.diligent.ShareStopReason
import com.vscodroid.diligent.TailscaleAddress
import com.vscodroid.diligent.ui.components.QrCode
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** Tailscale's Android app on Google Play. */
private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/**
 * More, then Share: opens the editor or the running app's preview on the
 * user's other devices over Tailscale (see [ShareKeeper]).
 *
 * Without a Tailscale address it says "Install Tailscale and sign in" with a
 * link to the store, and checks again whenever the screen comes back, so
 * returning from the Tailscale app shows the share buttons.
 */
@Composable
fun ShareScreen(shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var address by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val recheck = {
        address = TailscaleAddress.find()?.hostAddress
        checked = true
    }
    LifecycleResumeEffect(Unit) {
        recheck()
        onPauseOrDispose { }
    }
    if (!checked) return
    val start = { kind: ShareKind, port: Int ->
        problem = null
        ShareKeeper.start(context, kind, port) { result ->
            problem = when (result) {
                ShareStart.Started -> null
                ShareStart.NoTailscale -> R.string.diligent_share_no_tailscale_title
                ShareStart.NoToken -> R.string.diligent_share_no_token
                ShareStart.Failed -> R.string.diligent_share_failed
            }
            if (result == ShareStart.NoTailscale) recheck()
        }
    }
    ShareContent(
        address = address,
        shares = ShareKeeper.shares,
        stopped = ShareKeeper.lastStop,
        problem = problem,
        editorPort = actions.editorPort(),
        previewPort = shell.previewPort.takeIf { shell.running },
        onShare = start,
        onStop = { ShareKeeper.stop(context, it) },
        onCopy = { copy(context, it) },
        onGetTailscale = { openStore(context) },
        onCheckAgain = recheck,
        modifier = modifier,
    )
}

@Composable
private fun ShareContent(
    address: String?,
    shares: Map<ShareKind, ActiveShare>,
    stopped: ShareStopReason?,
    @StringRes problem: Int?,
    editorPort: Int?,
    previewPort: Int?,
    onShare: (ShareKind, Int) -> Unit,
    onStop: (ShareKind) -> Unit,
    onCopy: (String) -> Unit,
    onGetTailscale: () -> Unit,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            Body(stringResource(R.string.diligent_share_intro))
            stopped?.let { reason -> Notice(stringResource(reason.text)) }
            problem?.let { Notice(stringResource(it)) }
            if (address == null && shares.isEmpty()) {
                Card {
                    Title(stringResource(R.string.diligent_share_no_tailscale_title))
                    Body(stringResource(R.string.diligent_share_no_tailscale_body))
                    Button(onClick = onGetTailscale, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
                        Text(stringResource(R.string.diligent_share_get_tailscale))
                    }
                    TextButton(onClick = onCheckAgain, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.diligent_share_check_again))
                    }
                }
            } else {
                address?.let { Body(stringResource(R.string.diligent_share_address, it)) }
                ShareCard(
                    title = stringResource(R.string.diligent_share_editor_title),
                    body = stringResource(R.string.diligent_share_editor_body),
                    unavailable = stringResource(R.string.diligent_share_editor_not_ready),
                    action = stringResource(R.string.diligent_share_start_editor),
                    port = editorPort,
                    share = shares[ShareKind.Editor],
                    canStart = address != null,
                    onShare = { onShare(ShareKind.Editor, it) },
                    onStop = { onStop(ShareKind.Editor) },
                    onCopy = onCopy,
                )
                ShareCard(
                    title = stringResource(R.string.diligent_share_preview_title),
                    body = previewPort?.let { stringResource(R.string.diligent_share_preview_body, it) }
                        ?: stringResource(R.string.diligent_share_preview_body_none),
                    unavailable = stringResource(R.string.diligent_share_preview_none),
                    action = stringResource(R.string.diligent_share_start_preview),
                    port = previewPort,
                    share = shares[ShareKind.Preview],
                    canStart = address != null,
                    onShare = { onShare(ShareKind.Preview, it) },
                    onStop = { onStop(ShareKind.Preview) },
                    onCopy = onCopy,
                )
            }
            Body(stringResource(R.string.diligent_share_safety))
        }
    }
}

/** One thing to share: its button while off; its address, QR code, Copy and Stop while on. */
@Composable
private fun ShareCard(
    title: String,
    body: String,
    unavailable: String,
    action: String,
    port: Int?,
    share: ActiveShare?,
    canStart: Boolean,
    onShare: (Int) -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Card {
        Title(title)
        Body(body)
        when {
            share != null -> {
                SelectionContainer {
                    Text(share.url, style = DiligentTheme.type.code, color = DiligentTheme.colors.onSurface)
                }
                QrCode(
                    share.url,
                    stringResource(R.string.diligent_share_qr, share.url),
                    DiligentTheme.sizes.qrCode,
                    Modifier.align(Alignment.CenterHorizontally),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
                    OutlinedButton(
                        onClick = { onCopy(share.url) },
                        modifier = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget),
                    ) { Text(stringResource(R.string.diligent_share_copy)) }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget),
                    ) { Text(stringResource(R.string.diligent_share_stop)) }
                }
            }
            port == null -> Body(unavailable)
            else -> Button(
                onClick = { onShare(port) },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
            ) { Text(action) }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = DiligentTheme.colors.surfaceContainer, shape = DiligentTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
            content = content,
        )
    }
}

@Composable
private fun Title(text: String) = Text(
    text,
    modifier = Modifier.semantics { heading() },
    style = DiligentTheme.type.material.titleSmall,
    color = DiligentTheme.colors.onSurface,
)

@Composable
private fun Body(text: String) = Text(
    text,
    style = DiligentTheme.type.material.bodyMedium,
    color = DiligentTheme.colors.onSurfaceVariant,
)

@Composable
private fun Notice(text: String) = Text(
    text,
    style = DiligentTheme.type.material.bodyMedium,
    color = DiligentTheme.colors.warning,
)

@get:StringRes
private val ShareStopReason.text: Int
    get() = when (this) {
        ShareStopReason.Idle -> R.string.diligent_share_stopped_idle
        ShareStopReason.TailscaleGone -> R.string.diligent_share_stopped_tailscale
        ShareStopReason.Failed, ShareStopReason.User -> R.string.diligent_share_stopped_failed
    }

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("url", text))
    // Android 13+ confirms a copy itself; earlier versions say nothing.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.diligent_share_copied, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Tailscale's store page: the Play app when there is one, else the web page,
 * else a note, since a phone can have neither (a work profile, a stripped ROM).
 */
private fun openStore(context: Context) {
    val links = listOf(
        "market://details?id=$TAILSCALE_PACKAGE",
        "https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE",
    )
    for (link in links) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
    Toast.makeText(context, R.string.diligent_share_no_store, Toast.LENGTH_LONG).show()
}

private val PreviewShare = ActiveShare(
    ShareKind.Editor,
    "http://100.101.102.103:41234/?tkn=6f1c2e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6",
    "100.101.102.103",
    41234,
    41234,
)

@Composable
private fun SharePreviewContent(address: String?, shares: Map<ShareKind, ActiveShare>) = ShareContent(
    address = address,
    shares = shares,
    stopped = null,
    problem = null,
    editorPort = 41234,
    previewPort = null,
    onShare = { _, _ -> },
    onStop = {},
    onCopy = {},
    onGetTailscale = {},
    onCheckAgain = {},
)

@Preview(name = "Share, no Tailscale, light", widthDp = 360, heightDp = 640)
@Composable
private fun ShareNoTailscalePreview() = DiligentTheme(darkTheme = false) { SharePreviewContent(null, emptyMap()) }

@Preview(name = "Share, off, dark", widthDp = 360, heightDp = 640)
@Composable
private fun ShareOffPreview() = DiligentTheme(darkTheme = true) { SharePreviewContent("100.101.102.103", emptyMap()) }

@Preview(name = "Share, editor on, light", widthDp = 360, heightDp = 1000)
@Composable
private fun ShareOnPreview() = DiligentTheme(darkTheme = false) {
    SharePreviewContent("100.101.102.103", mapOf(ShareKind.Editor to PreviewShare))
}
