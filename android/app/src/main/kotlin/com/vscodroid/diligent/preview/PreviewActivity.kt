package com.vscodroid.diligent.preview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.vscodroid.R
import com.vscodroid.diligent.ui.theme.DiligentTheme
import kotlinx.coroutines.delay

/**
 * The running app, full screen: a WebView on `http://localhost:<port>` with
 * Reload, Open in browser and Close.
 *
 * Its own activity rather than a screen over the editor, so the editor's
 * WebView and its state are untouched underneath and Back returns to it.
 * Rotation does not reload the page (the manifest takes the configuration
 * changes), so a form half filled in survives turning the phone. The window
 * is edge to edge by the theme and targetSdk; `safeDrawingPadding` keeps the
 * bar and page clear of the system bars.
 *
 * Only loopback pages load here ([PreviewUrls.isLocal]); any other link goes
 * to the browser. A dev server that is not answering yet (the usual case right
 * after Start) shows a page that says so and retries by itself for a while.
 */
class PreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        if (port !in 1..65535) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)
        setContent {
            DiligentTheme {
                PreviewScreen(
                    port = port,
                    projectName = title,
                    onClose = ::finish,
                    onOpenExternal = ::openInBrowser,
                    webContent = { modifier, page ->
                        PreviewWebView(modifier, page, onExternalLink = ::openInBrowser, onRendererGone = ::finish)
                    },
                )
            }
        }
    }

    /** Hands [url] to the browser; a phone without one gets a short message instead of a crash. */
    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.diligent_preview_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_PORT = "com.vscodroid.diligent.preview.PORT"
        const val EXTRA_TITLE = "com.vscodroid.diligent.preview.TITLE"

        fun intent(context: Context, port: Int, title: String?): Intent =
            Intent(context, PreviewActivity::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TITLE, title)
    }
}

/** What the page is doing, as the top bar and the error page need it. */
internal class PreviewPageState(val url: String) {
    var loading by mutableStateOf(true)

    /** The main page failed to load (usually: nothing listening yet); null while it is fine. */
    var failure by mutableStateOf<String?>(null)

    /** The page on screen, which links inside the app may have moved on from [url]. */
    var current by mutableStateOf(url)

    /** Whether Back steps back a page rather than closing the preview. */
    var canGoBack by mutableStateOf(false)

    /** Bumped to ask the WebView to load [current] again. */
    var reloads by mutableIntStateOf(0)

    fun reload() {
        failure = null
        loading = true
        reloads++
    }
}

/**
 * The top bar, and the page or the "not answering" page. [webContent] draws
 * the WebView; previews pass a stand-in, since a WebView does not render there.
 */
@Composable
internal fun PreviewScreen(
    port: Int,
    projectName: String?,
    onClose: () -> Unit,
    onOpenExternal: (String) -> Unit,
    webContent: @Composable (Modifier, PreviewPageState) -> Unit,
    page: PreviewPageState = remember { PreviewPageState(PreviewUrls.forPort(port)) },
) {
    Surface(color = DiligentTheme.colors.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            PreviewTopBar(
                label = PreviewUrls.label(port),
                projectName = projectName,
                onClose = onClose,
                onReload = page::reload,
                onOpenExternal = { onOpenExternal(page.current) },
            )
            if (page.loading && page.failure == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                webContent(Modifier.fillMaxSize(), page)
                page.failure?.let { NotAnswering(PreviewUrls.label(port), onRetry = page::reload) }
            }
        }
    }

    // A server that is still starting answers within seconds; retry quietly
    // for a while, then leave it to the Retry button.
    var autoRetries by remember { mutableIntStateOf(0) }
    LaunchedEffect(page.failure) {
        if (page.failure != null && autoRetries < AUTO_RETRIES) {
            delay(AUTO_RETRY_MS)
            autoRetries++
            page.reload()
        }
    }
}

private const val AUTO_RETRIES = 15
private const val AUTO_RETRY_MS = 2000L

@Composable
private fun PreviewTopBar(
    label: String,
    projectName: String?,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DiligentTheme.sizes.topBar)
            .background(DiligentTheme.colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(DiligentTheme.sizes.touchTarget)) {
            Icon(Icons.Outlined.Close, stringResource(R.string.diligent_preview_close), tint = DiligentTheme.colors.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = DiligentTheme.type.material.titleMedium,
                color = DiligentTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (projectName != null) {
                Text(
                    projectName,
                    style = DiligentTheme.type.material.bodySmall,
                    color = DiligentTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onReload, modifier = Modifier.size(DiligentTheme.sizes.touchTarget)) {
            Icon(Icons.Outlined.Refresh, stringResource(R.string.diligent_preview_reload), tint = DiligentTheme.colors.onSurface)
        }
        IconButton(onClick = onOpenExternal, modifier = Modifier.size(DiligentTheme.sizes.touchTarget)) {
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                stringResource(R.string.diligent_preview_open_browser),
                tint = DiligentTheme.colors.onSurface,
            )
        }
    }
}

@Composable
private fun NotAnswering(label: String, onRetry: () -> Unit) {
    Surface(color = DiligentTheme.colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(DiligentTheme.spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m, Alignment.CenterVertically),
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = DiligentTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(DiligentTheme.sizes.iconLarge),
            )
            Text(
                stringResource(R.string.diligent_preview_not_up_title, label),
                style = DiligentTheme.type.material.titleMedium,
                color = DiligentTheme.colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.diligent_preview_not_up_body),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = DiligentTheme.sizes.sheetMaxWidth),
            )
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_preview_retry))
            }
        }
    }
}

/**
 * The WebView itself. JavaScript and DOM storage are on because the apps
 * being previewed (Next.js, Vite, anything with a client) do not run without
 * them; that is safe to allow because only loopback pages ever load here.
 * File and content access stay off: a page from a dev server has no business
 * reading the device's files.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWebView(
    modifier: Modifier,
    page: PreviewPageState,
    onExternalLink: (String) -> Unit,
    onRendererGone: () -> Unit,
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = PreviewClient(page, onExternalLink, onRendererGone)
            loadUrl(page.url)
        }
    }
    LaunchedEffect(page.reloads) {
        // Loads the address again rather than calling reload(): after a
        // failure the WebView may be showing its own error page.
        if (page.reloads > 0) webView.loadUrl(page.current)
    }
    BackHandler(enabled = page.canGoBack) { webView.goBack() }
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}

/** Keeps the preview on loopback pages and reports the page's progress to [page]. */
// MissingOnRenderProcessGone: this class does override it, below. The check
// does not see the override from Kotlin, as bootstrapClient in MainActivity
// and readSavedPageColor in VSCodroidWebView note for theirs.
@SuppressLint("MissingOnRenderProcessGone")
private class PreviewClient(
    private val page: PreviewPageState,
    private val onExternalLink: (String) -> Unit,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (PreviewUrls.isLocal(url)) return false
        if (request.isForMainFrame && request.hasGesture()) onExternalLink(url)
        return true
    }

    /**
     * The page's renderer crashed or was killed for memory. This
     * WebView cannot be used again, and the app must not go down
     * with it: close the preview, and the user opens it afresh.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone()
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        page.loading = true
        if (url != null && PreviewUrls.isLocal(url)) page.current = url
    }

    override fun onPageFinished(view: WebView, url: String?) {
        page.loading = false
        page.canGoBack = view.canGoBack()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            page.failure = error.description?.toString().orEmpty()
            page.loading = false
        }
    }
}

@Composable
private fun PreviewSample(failed: Boolean) {
    val page = remember {
        PreviewPageState(PreviewUrls.forPort(3000)).apply {
            loading = false
            if (failed) failure = "net::ERR_CONNECTION_REFUSED"
        }
    }
    PreviewScreen(
        port = 3000,
        projectName = "hello-next",
        onClose = {},
        onOpenExternal = {},
        webContent = { modifier, _ -> Box(modifier.background(DiligentTheme.colors.surfaceContainer)) },
        page = page,
    )
}

@Preview(name = "Preview, light", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewLightPreview() = DiligentTheme(darkTheme = false) { PreviewSample(failed = false) }

@Preview(name = "Preview not answering, dark", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewNotAnsweringDarkPreview() = DiligentTheme(darkTheme = true) { PreviewSample(failed = true) }

@Preview(name = "Preview not answering, landscape", widthDp = 640, heightDp = 360)
@Composable
private fun PreviewLandscapePreview() = DiligentTheme(darkTheme = false) { PreviewSample(failed = true) }
