package app.codeg.android.core.designsystem.webrender

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * A live, sandboxed web view of [html] for the full-screen viewers: scrolling, zooming and
 * `<details>` work, script never runs, and nothing loads from anywhere ([WebSandbox]).
 */
@Composable
internal fun SandboxedWebView(html: String, background: Color, modifier: Modifier = Modifier) {
    // A renderer that dies takes its view with it: a fresh one is built in its place.
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        SandboxedWebViewOnce(html, background, modifier, onGone = { generation += 1 })
    }
}

@Composable
private fun SandboxedWebViewOnce(html: String, background: Color, modifier: Modifier, onGone: () -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebSandbox.makeWebView(context, javaScript = false, client = SandboxClient(onGone = { onGone() }), zoomable = true).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.argb(
                    (background.alpha * 255).toInt(),
                    (background.red * 255).toInt(),
                    (background.green * 255).toInt(),
                    (background.blue * 255).toInt(),
                ))
                tag = html
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
    )
}

/** The frame of a full-screen viewer: a bar (close, title, actions) over [content]. */
@Composable
internal fun RichViewerFrame(
    title: String,
    background: Color,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = CodegTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The whole screen, under the status bar too, with its icons legible on the surface:
        // a dialog window otherwise stops at the bars and leaves the dim showing there.
        val dialogView = LocalView.current
        val dark = colors.isDark
        SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.attributes = window.attributes.apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.getInsetsController(window, dialogView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        Column(Modifier.fillMaxSize().background(background)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.rich_close), tint = colors.textPrimary)
                }
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                actions()
            }
            Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) { content() }
        }
    }
}

/**
 * A diagram full screen: its SVG in a live web view with pinch zoom, still sandboxed; copy
 * the source, or share it as PNG, SVG or `.mmd` under the web's file names.
 */
@Composable
internal fun MermaidViewer(source: String, diagram: MermaidDiagram, theme: MermaidTheme, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val surface = RichBlockPalette.surface(theme.dark)
    val configuration = LocalConfiguration.current
    val html = remember(diagram, configuration.screenWidthDp) {
        val column = (configuration.screenWidthDp - 32).coerceAtLeast(1).toFloat()
        val width = MermaidGeometry.inlineWidth(diagram.natural, column)
        viewerDocument(diagram.svg, width, surface)
    }
    var menu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    fun share(format: MermaidExport.Format) {
        if (sharing) return
        sharing = true
        scope.launch {
            try {
                when (format) {
                    MermaidExport.Format.PNG -> {
                        val bitmap = WebRenderHost.mermaidPng(diagram, view)
                        shareFile(context, MermaidExport.fileName(format), format.mime) { file ->
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                    MermaidExport.Format.SVG -> shareFile(context, MermaidExport.fileName(format), format.mime) { it.writeText(diagram.svg) }
                    MermaidExport.Format.MMD -> shareFile(context, MermaidExport.fileName(format), format.mime) { it.writeText(source) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.rich_share_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_SHORT).show()
            } finally {
                sharing = false
            }
        }
    }
    RichViewerFrame(
        title = stringResource(R.string.rich_diagram),
        background = Color(surface.argb),
        onDismiss = onDismiss,
        actions = {
            if (sharing) CircularProgressIndicator(Modifier.padding(12.dp).padding(2.dp), color = colors.accent, strokeWidth = 2.dp)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.rich_more_actions), tint = colors.textPrimary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rich_copy_source)) }, onClick = {
                        menu = false
                        copySource(context, source)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rich_share_png)) }, onClick = {
                        menu = false
                        share(MermaidExport.Format.PNG)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rich_share_svg)) }, onClick = {
                        menu = false
                        share(MermaidExport.Format.SVG)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rich_share_source)) }, onClick = {
                        menu = false
                        share(MermaidExport.Format.MMD)
                    })
                }
            }
        },
    ) {
        SandboxedWebView(html, Color(surface.argb), Modifier.fillMaxSize())
    }
}

/** An HTML preview full screen: the same sanitized document, live — still no script, no network. */
@Composable
internal fun HtmlPreviewViewer(source: String, theme: ChatHtmlTheme, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    var document by remember(source, theme) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, theme) {
        document = withContext(Dispatchers.Default) { ChatHtmlDocument.build(source, theme) }
    }
    val surface = Color(theme.surface.argb)
    RichViewerFrame(title = stringResource(R.string.rich_html_preview), background = surface, onDismiss = onDismiss) {
        val doc = document
        if (doc == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.accent, strokeWidth = 2.dp)
        } else {
            SandboxedWebView(doc, surface, Modifier.fillMaxSize())
        }
    }
}

/**
 * The viewer's document for a rendered SVG: drawn [width] dp wide on the surface, zoomable
 * from 0.2× to 8× (the web's zoom bounds), under a CSP with no script and no network.
 */
internal fun viewerDocument(svg: String, width: Float, surface: Rgb): String = buildString {
    append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
    append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data: blob:; ")
    append("style-src 'unsafe-inline'; font-src data:; script-src 'none'\">")
    append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,minimum-scale=")
    append(MermaidGeometry.MIN_ZOOM).append(",maximum-scale=").append(MermaidGeometry.MAX_ZOOM).append(",user-scalable=yes\">")
    append("<style>html,body{margin:0;background:").append(surface.css).append("}")
    append("body{padding:16px;display:flex;justify-content:center}")
    append("body>svg{display:block;flex:none;max-width:none;width:").append(width).append("px;height:auto}</style>")
    append("</head><body>").append(svg).append("</body></html>")
}

internal fun copySource(context: Context, source: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("mermaid", source))
    Toast.makeText(context, context.getString(R.string.rich_source_copied), Toast.LENGTH_SHORT).show()
}

/**
 * Write [name] into a folder of its own under the app's share folder with [write], and offer
 * it to other apps. Every share gets a new folder: an app handed an earlier file keeps its
 * grant on that file only, and never sees what is shared after it. Shares older than a day
 * are cleared out first.
 */
private suspend fun shareFile(context: Context, name: String, mime: String, write: (File) -> Unit) {
    val file = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - SHARE_TTL_MS
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        File(dir, name).also(write)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.renderfiles", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, name)
    context.findActivity()?.startActivity(chooser) ?: context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Under the cache dir, as declared in `res/xml/render_file_paths.xml`. */
private const val SHARE_DIR = "shared"

/** How long a shared file is kept for the app it went to. */
private const val SHARE_TTL_MS = 24L * 60 * 60 * 1000
