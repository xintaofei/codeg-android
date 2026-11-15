package app.codeg.android.core.designsystem.webrender

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Why the renderer gave no picture. */
sealed class WebRenderError(message: String) : Exception(message) {
    /** Mermaid rejected the source; [detail] is its message (may be empty). */
    class Mermaid(val detail: String) : WebRenderError("mermaid: $detail")
    class TimedOut : WebRenderError("timed out")
    class Unavailable(why: String = "unavailable") : WebRenderError(why)
}

/** A Mermaid diagram drawn for the transcript: the theme's SVG and, for one column, the picture. */
class MermaidRendering(
    val diagram: MermaidDiagram,
    val theme: MermaidTheme,
    val bitmap: Bitmap,
    /** The picture's size in dp: the drawn width × the capped height. */
    val size: DiagramSize,
    /** The drawn height before the cap, in dp. */
    val drawnHeight: Float,
) {
    /** The diagram runs on below the picture. */
    val clipped: Boolean get() = MermaidGeometry.isClipped(drawnHeight, size.height)
}

/** A chat HTML preview drawn for one column width (all in dp). */
class HtmlRendering(
    val bitmap: Bitmap,
    /** The column it was laid out in. */
    val width: Float,
    /** The document's height, clamped 96–12,000. */
    val height: Float,
    /** How much of it, from the top, the picture covers. */
    val covered: Float,
    val theme: ChatHtmlTheme,
)

/**
 * The app's one off-screen web renderer. Diagrams (and, with the HTML preview on, chat HTML)
 * are drawn by a WebView into static pictures here, so the transcript itself holds no web
 * views. Port of iOS `WebRenderHost`.
 *
 * - **One job at a time.** Every job waits its turn on one serial gate; identical requests
 *   share one job, and results are cached by content + theme + size — never by turn id.
 * - **Lazy, kept warm.** The Mermaid page is built on the first diagram: an empty document
 *   handed the bundled library (~3.6 MB) once and kept for every later diagram. A crashed or
 *   stuck page is thrown away and rebuilt on the next job.
 * - **Bounded.** Each step has a timeout; bitmaps stay within a pixel budget; the caches
 *   are bounded and emptied when the system runs low on memory.
 * - **Never mid-stream.** Callers only ask once a fence has closed (or the reply stopped
 *   streaming) — see [app.codeg.android.core.designsystem.markdown.MarkdownFence].
 *
 * ## Hosting and capture
 *
 * The pages' web views sit in a container parked off-screen in the activity's window
 * ([Parking]), visible as far as the WebView knows: attached and `VISIBLE`, so its timers
 * and frames run at full speed (Mermaid's parsers yield with `setTimeout(0)`, which a hidden
 * page throttles) and its frames are produced. Nothing on screen covers or shows them.
 *
 * A picture is taken by laying the page's view out at exactly the area to capture, waiting
 * until the page's current state is ready to draw (`postVisualStateCallback`), and drawing
 * the view into a software [Canvas] over a [Bitmap] — the WebView's software draw path, which
 * paints the current layer tree for the canvas's clip whatever is on screen. The canvas is
 * scaled, so the raster scale is ours to choose within the pixel budget. Whole-document
 * drawing (`enableSlowWholeDocumentDraw`) is not needed: the view is as large as what is
 * drawn. Tiles are never rastered for it (it is never on screen, and off-screen pre-raster
 * stays off), so a tall view costs no GPU memory.
 */
object WebRenderHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** SVG per source + theme: 32 entries / 8 MB of text, as the web's cache. */
    private val diagrams = ByteBoundedLru<MermaidDiagram>(32, 8L * 1024 * 1024) { it.svg.length * 2L }

    /** Inline pictures per source + theme + size: bitmaps get their own, larger budget. */
    private val images = ByteBoundedLru<MermaidRendering>(32, 48L * 1024 * 1024) { it.bitmap.allocationByteCount.toLong() }

    /** The last picture key drawn per SVG key, so a re-created block shows its diagram at once. */
    private val latestImageKey = HashMap<String, String>()

    /** HTML previews per source + theme + width + covered height. */
    private val htmlImages = ByteBoundedLru<HtmlRendering>(32, 48L * 1024 * 1024) { it.bitmap.allocationByteCount.toLong() }

    private val inFlight = HashMap<String, Deferred<MermaidRendering>>()
    private val htmlInFlight = HashMap<String, Deferred<HtmlRendering>>()

    private var parking: Parking? = null
    private var mermaidPage: RenderPage? = null
    private var htmlPage: RenderPage? = null
    private var callbacksRegistered = false
    private var jobSeq = 0L

    /** Bitmap budget of an inline diagram. */
    private const val INLINE_MAX_PIXELS = 8_000_000f

    /** Bitmap budget of an HTML preview (up to 12,000 dp tall). */
    private const val HTML_MAX_PIXELS = 12_000_000f

    private const val WARM_UP_TIMEOUT_MS = 30_000L
    private const val STEP_TIMEOUT_MS = 15_000L

    /** How long a page may take to say its state is drawn before the capture goes ahead. */
    private const val VISUAL_STATE_TIMEOUT_MS = 2_000L

    // region Mermaid

    data class MermaidRequest(
        val source: String,
        val theme: MermaidTheme,
        /** The column the picture fills, in dp. */
        val width: Float,
        /** The picture's height cap, in dp. */
        val maxHeight: Float,
    ) {
        val diagramKey: String get() = WebRenderKey.mermaid(source, theme)
        val imageKey: String get() = WebRenderKey.mermaidImage(source, theme, width, maxHeight)
    }

    /** The finished picture for exactly this request, if it is cached. */
    fun cachedMermaid(request: MermaidRequest): MermaidRendering? = images[request.imageKey]

    /** The last picture drawn of this source and theme at any size, if cached. */
    fun latestMermaid(source: String, theme: MermaidTheme): MermaidRendering? {
        val imageKey = latestImageKey[WebRenderKey.mermaid(source, theme)] ?: return null
        return images[imageKey]
    }

    /**
     * Draw a diagram for the transcript. [anchor] is any view of the activity the page is
     * parked in. Throws [WebRenderError.Mermaid] for a source Mermaid rejects; failures are not
     * cached, so asking again retries. A caller that goes away cancels only its own wait.
     */
    suspend fun mermaid(request: MermaidRequest, anchor: View): MermaidRendering {
        cachedMermaid(request)?.let { return it }
        val key = request.imageKey
        val job = inFlight[key] ?: shared(inFlight, key) {
            gate.withLock { cachedMermaid(request) ?: drawMermaid(request, anchor) }
        }
        return job.await()
    }

    /**
     * The diagram as a PNG bitmap, at three times its own size within the export budget, on a
     * transparent background — what the web's "Download as PNG" writes.
     */
    suspend fun mermaidPng(diagram: MermaidDiagram, anchor: View): Bitmap = scope.async {
        gate.withLock {
            val page = step(WARM_UP_TIMEOUT_MS, ::discardMermaidPage) { readyMermaidPage(anchor) }
            step(STEP_TIMEOUT_MS, ::discardMermaidPage) {
                val width = diagram.natural?.width ?: 800f
                val box = page.stage(diagram.svg, width, diagram.natural?.height ?: 0f, "transparent")
                val scale = PixelBudget.scale(box, MermaidExport.PNG_SCALE, MermaidExport.MAX_PIXELS, MermaidExport.MAX_SIDE)
                val bitmap = page.capture(box, scale, background = null)
                page.clearStage()
                bitmap
            }
        }
    }.await()

    private suspend fun drawMermaid(request: MermaidRequest, anchor: View): MermaidRendering {
        if (request.width <= 0f || request.maxHeight <= 0f) throw WebRenderError.Unavailable()
        val page = step(WARM_UP_TIMEOUT_MS, ::discardMermaidPage) { readyMermaidPage(anchor) }
        val diagram = diagrams[request.diagramKey] ?: run {
            val svg = step(STEP_TIMEOUT_MS, ::discardMermaidPage) { page.renderMermaid(request.source, request.theme) }
            MermaidDiagram(MermaidGeometry.stripSvgMaxWidth(svg), MermaidGeometry.parseSvgSize(svg))
                .also { diagrams.put(request.diagramKey, it) }
        }
        val rendering = step(STEP_TIMEOUT_MS, ::discardMermaidPage) { picture(diagram, request, page) }
        images.put(request.imageKey, rendering)
        latestImageKey[request.diagramKey] = request.imageKey
        if (latestImageKey.size > 128) latestImageKey.clear()
        return rendering
    }

    private suspend fun picture(diagram: MermaidDiagram, request: MermaidRequest, page: RenderPage): MermaidRendering {
        val surface = RichBlockPalette.surface(request.theme.dark)
        val width = MermaidGeometry.inlineWidth(diagram.natural, request.width)
        val height = diagram.natural?.let { width * it.height / max(it.width, 1f) } ?: 0f
        val box = page.stage(diagram.svg, width, height, surface.css)
        val clip = MermaidGeometry.inlineClip(box.height, request.maxHeight)
        val size = DiagramSize(box.width, clip)
        val scale = PixelBudget.scale(size, page.density, INLINE_MAX_PIXELS)
        val bitmap = page.capture(size, scale, background = surface.argb)
        page.clearStage()
        return MermaidRendering(diagram, request.theme, bitmap, size, box.height)
    }

    // endregion

    // region HTML preview

    data class HtmlRequest(
        val source: String,
        val theme: ChatHtmlTheme,
        /** The column the document is laid out in, in dp. */
        val width: Float,
        /** How much of the document, from the top, the picture covers. */
        val clip: Float,
    ) {
        val key: String get() = WebRenderKey.html(source, theme, width, clip)
    }

    fun cachedHtml(request: HtmlRequest): HtmlRendering? = htmlImages[request.key]

    /** Lay the fence's document out at the column's width and draw (the top [HtmlRequest.clip] of) it. */
    suspend fun html(request: HtmlRequest, anchor: View): HtmlRendering {
        cachedHtml(request)?.let { return it }
        val key = request.key
        val job = htmlInFlight[key] ?: shared(htmlInFlight, key) {
            gate.withLock {
                cachedHtml(request) ?: run {
                    if (request.width <= 0f || request.clip <= 0f) throw WebRenderError.Unavailable()
                    val document = withContext(Dispatchers.Default) { ChatHtmlDocument.build(request.source, request.theme) }
                    val rendering = step(STEP_TIMEOUT_MS, ::discardHtmlPage) { drawHtml(document, request, anchor) }
                    htmlImages.put(key, rendering)
                    rendering
                }
            }
        }
        return job.await()
    }

    private suspend fun drawHtml(document: String, request: HtmlRequest, anchor: View): HtmlRendering {
        val page = readyHtmlPage(anchor)
        // The layout viewport: the column's width, the default preview height (so `100vh`
        // content measures as it would in the web's first frame).
        page.resize(request.width, HtmlPreviewMetrics.PLACEHOLDER_HEIGHT)
        page.load(document, baseUrl = null)
        page.awaitVisualState()
        var measured = page.contentHeight()
        if (measured <= HtmlPreviewMetrics.PLACEHOLDER_HEIGHT) {
            // The content height is at least the viewport's: a shorter document only shows
            // as such in a shorter one.
            page.resize(request.width, HtmlPreviewMetrics.MIN_HEIGHT)
            page.awaitVisualState()
            val short = page.contentHeight()
            if (short < HtmlPreviewMetrics.PLACEHOLDER_HEIGHT) measured = short
        }
        val height = HtmlPreviewMetrics.clamp(measured.toDouble())
        val covered = minOf(height, request.clip)
        val size = DiagramSize(request.width, covered)
        page.resize(size.width, size.height)
        page.awaitVisualState()
        val scale = PixelBudget.scale(size, page.density, HTML_MAX_PIXELS)
        val surface = RichBlockPalette.surface(request.theme.dark)
        val bitmap = page.capture(size, scale, background = surface.argb)
        return HtmlRendering(bitmap, request.width, height, covered, request.theme)
    }

    // endregion

    // region Pages

    /** The Mermaid page, built (and handed the library) when there is none or the last one died. */
    private suspend fun readyMermaidPage(anchor: View): RenderPage {
        val parking = park(anchor)
        mermaidPage?.takeIf { it.usable }?.let { return it }
        mermaidPage?.discard()
        mermaidPage = null
        val page = RenderPage(parking, javaScript = true)
        // Owned before the first suspension: a timeout that fires mid-warm-up discards this
        // page rather than leaving it parked and orphaned.
        mermaidPage = page
        page.resize(800f, 600f)
        page.load(MermaidHost.PAGE_HTML, baseUrl = null)
        val context = anchor.context.applicationContext
        val library = withContext(Dispatchers.IO) {
            context.assets.open(MermaidHost.LIBRARY_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        page.evaluate("$library\n;true")
        if (page.evaluate(MermaidHost.BOOTSTRAP_SCRIPT) != "true") throw WebRenderError.Unavailable("bootstrap")
        page.isWarm = true
        return page
    }

    /** The HTML page: no warm-up, each job loads its own document. */
    private fun readyHtmlPage(anchor: View): RenderPage {
        val parking = park(anchor)
        htmlPage?.takeIf { it.usable }?.let { return it }
        htmlPage?.discard()
        htmlPage = null
        return RenderPage(parking, javaScript = false).also {
            it.isWarm = true
            htmlPage = it
        }
    }

    /**
     * The parking container in [anchor]'s activity window — moved there if it was parked in
     * another (the activity was recreated), with the pages in it.
     */
    private fun park(anchor: View): Parking {
        // A window's decor view carries a decor context, not the activity: its content does.
        val activity = anchor.context.findActivity()
            ?: anchor.rootView.findViewById<ViewGroup>(android.R.id.content)?.context?.findActivity()
            ?: throw WebRenderError.Unavailable("no activity")
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: throw WebRenderError.Unavailable("no window")
        registerCallbacks(activity.applicationContext)
        val current = parking ?: Parking(activity.applicationContext).also { parking = it }
        current.attach(content, activity)
        return current
    }

    /** Run [operation], giving up after [millis]; a step that times out throws its page away. */
    private suspend fun <T> step(millis: Long, onTimeout: () -> Unit, operation: suspend () -> T): T =
        try {
            withTimeout(millis) { operation() }
        } catch (e: TimeoutCancellationException) {
            onTimeout()
            throw WebRenderError.TimedOut()
        } catch (e: WebRenderError) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The page is in an unknown state.
            onTimeout()
            throw WebRenderError.Unavailable(e.message ?: e.javaClass.simpleName)
        }

    private fun discardMermaidPage() {
        mermaidPage?.discard()
        mermaidPage = null
    }

    private fun discardHtmlPage() {
        htmlPage?.discard()
        htmlPage = null
    }

    private fun registerCallbacks(context: Context) {
        if (callbacksRegistered) return
        callbacksRegistered = true
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                @Suppress("DEPRECATION")
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) scope.launch { trim() }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                scope.launch { trim() }
            }
        })
    }

    private fun trim() {
        diagrams.clear()
        images.clear()
        htmlImages.clear()
        latestImageKey.clear()
        // A page in use is left alone; an idle one is rebuilt on demand.
        if (!gate.isLocked) {
            discardMermaidPage()
            discardHtmlPage()
        }
    }

    private fun nextJob(): String = "j${++jobSeq}"

    /**
     * A job every caller asking for [key] shares, run in the host's own scope so that one
     * caller going away cancels only its wait. Registered before it starts, and unregistered
     * when it ends — success or failure: a failure is never handed to a later caller.
     */
    private fun <T> shared(registry: HashMap<String, Deferred<T>>, key: String, work: suspend () -> T): Deferred<T> {
        lateinit var job: Deferred<T>
        job = scope.async(start = CoroutineStart.LAZY) {
            try {
                work()
            } finally {
                if (registry[key] === job) registry.remove(key)
            }
        }
        registry[key] = job
        job.start()
        return job
    }

    // endregion

    // region Page

    /** One sandboxed web view of the renderer, parked off-screen. */
    private class RenderPage(private val parking: Parking, javaScript: Boolean) {
        private var pendingLoad: CompletableDeferred<Unit>? = null
        var isWarm = false
        var isDead = false
            private set

        val usable: Boolean get() = isWarm && !isDead

        val webView: WebView = WebSandbox.makeWebView(
            parking.webContext,
            javaScript,
            SandboxClient(
                onFinished = { pendingLoad?.complete(Unit) },
                onGone = { gone ->
                    isDead = true
                    pendingLoad?.completeExceptionally(WebRenderError.Unavailable("renderer gone"))
                    parking.remove(gone)
                    gone.destroy()
                },
            ),
        ).also {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            it.isFocusable = false
            it.isFocusableInTouchMode = false
            it.isClickable = false
            it.isLongClickable = false
            parking.add(it)
        }

        val density: Float get() = webView.resources.displayMetrics.density

        fun requireAlive() {
            if (isDead) throw WebRenderError.Unavailable("page gone")
        }

        /** Lay the view out at [widthDp] × [heightDp] now (the page's layout viewport follows). */
        fun resize(widthDp: Float, heightDp: Float) {
            requireAlive()
            val w = max(1, ceil(widthDp * density).toInt())
            val h = max(1, ceil(heightDp * density).toInt())
            parking.size(webView, w, h)
        }

        suspend fun load(html: String, baseUrl: String?) {
            requireAlive()
            val done = CompletableDeferred<Unit>()
            pendingLoad = done
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            done.await()
        }

        /** The script's value as JSON text (`"null"` for none). */
        suspend fun evaluate(script: String): String? {
            requireAlive()
            return suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
            }
        }

        /** Mermaid's SVG for [source], or [WebRenderError.Mermaid]. */
        suspend fun renderMermaid(source: String, theme: MermaidTheme): String {
            val job = nextJob()
            if (evaluate(MermaidHost.startCall(job, source, theme)) != "true") throw WebRenderError.Unavailable("start")
            while (true) {
                val raw = evaluate(MermaidHost.takeCall(job))
                if (raw != null && raw != "null") {
                    val reply = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                        ?: throw WebRenderError.Unavailable("reply")
                    val ok = (reply["ok"] as? JsonPrimitive)?.booleanOrNull == true
                    val svg = (reply["svg"] as? JsonPrimitive)?.content
                    if (ok && !svg.isNullOrEmpty()) return svg
                    throw WebRenderError.Mermaid((reply["message"] as? JsonPrimitive)?.content?.trim().orEmpty())
                }
                delay(25)
            }
        }

        /**
         * Put [svg] on the stage [width] CSS px wide (and [height] tall, or by its viewBox's
         * ratio when 0) and return the box it takes.
         */
        suspend fun stage(svg: String, width: Float, height: Float, background: String): DiagramSize {
            // The view is at least as wide as the diagram, so the page lays nothing out narrower.
            resize(max(width, 1f), 600f)
            val raw = evaluate(MermaidHost.stageCall(svg, width, height, background))
            val box = raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val w = (box?.get("width") as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
            val h = (box?.get("height") as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
            if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) {
                val error = (box?.get("error") as? JsonPrimitive)?.content
                throw WebRenderError.Unavailable(if (error != null) "stage: $error" else "stage")
            }
            return DiagramSize(w.toFloat(), h.toFloat())
        }

        suspend fun clearStage() {
            if (isDead) return
            evaluate(MermaidHost.CLEAR_CALL)
        }

        /** The loaded document's height in CSS px (at least the view's own). */
        fun contentHeight(): Float {
            requireAlive()
            return webView.contentHeight.toFloat()
        }

        /** Wait until the page's current state is what its next draw shows. */
        suspend fun awaitVisualState() {
            requireAlive()
            try {
                withTimeout(VISUAL_STATE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        webView.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        })
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // A page that never says so is drawn as it stands. (On the emulator it always
                // says so, within a frame or two.)
            }
        }

        /**
         * The top-left [size] (CSS px) of the page as a bitmap of [scale] pixels per CSS px.
         * With a [background] (ARGB), the bitmap is opaque on it; without, the page's own
         * transparency is kept. The view is laid out at exactly that area first.
         */
        suspend fun capture(size: DiagramSize, scale: Float, background: Int?): Bitmap {
            requireAlive()
            if (size.width <= 0f || size.height <= 0f || scale <= 0f) throw WebRenderError.Unavailable("capture")
            resize(size.width, size.height)
            awaitVisualState()
            requireAlive()
            val w = max(1, (size.width * scale).roundToInt())
            val h = max(1, (size.height * scale).roundToInt())
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            background?.let(canvas::drawColor)
            val factor = scale / density
            canvas.scale(factor, factor)
            webView.draw(canvas)
            return bitmap
        }

        fun discard() {
            if (isDead) return
            isDead = true
            pendingLoad?.cancel()
            webView.stopLoading()
            parking.remove(webView)
            webView.destroy()
        }
    }

    // endregion

    // region Parking

    /**
     * The container the pages' web views live in: a child of the activity's content view, moved
     * far off-screen, not touchable, hidden from accessibility. It measures itself 1×1 and lays
     * each web view out at the size it was given ([size]), whatever the window's size.
     */
    private class Parking(appContext: Context) {
        /** The web views' context: the activity while parked in one, else the application. */
        val webContext = android.content.MutableContextWrapper(appContext)
        private val application = appContext
        private val layout = ParkingLayout(webContext)

        fun attach(content: ViewGroup, activity: Activity) {
            if (layout.parent === content) return
            (layout.parent as? ViewGroup)?.removeView(layout)
            webContext.baseContext = activity
            content.addView(layout, FrameLayout.LayoutParams(1, 1))
        }

        fun add(view: WebView) {
            layout.addView(view, FrameLayout.LayoutParams(1, 1))
        }

        fun remove(view: View) {
            layout.removeView(view)
        }

        fun size(view: View, width: Int, height: Int) {
            val params = view.layoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                view.layoutParams = params
            }
            if (view.width != width || view.height != height) {
                // Now, not on the next layout pass: the capture that follows reads this size.
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)
            }
        }

        /**
         * Back on the application, and out of the window that went away, so a destroyed
         * activity's views aren't held by the parked pages. (Left in place if, by the time this
         * runs, it was parked in another window.)
         */
        fun onDetached() {
            webContext.baseContext = application
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!layout.isAttachedToWindow) (layout.parent as? ViewGroup)?.removeView(layout)
            }
        }

        private inner class ParkingLayout(context: Context) : FrameLayout(context) {
            init {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                isFocusable = false
                isClickable = false
                clipChildren = false
                // Far outside any window: drawn as far as the views know, seen nowhere.
                translationX = -100_000f
                translationY = -100_000f
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    val params = child.layoutParams
                    child.measure(
                        MeasureSpec.makeMeasureSpec(max(1, params.width), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(max(1, params.height), MeasureSpec.EXACTLY),
                    )
                }
                setMeasuredDimension(1, 1)
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    child.layout(0, 0, child.measuredWidth, child.measuredHeight)
                }
            }

            override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = true

            override fun onDetachedFromWindow() {
                super.onDetachedFromWindow()
                onDetached()
            }
        }
    }

    // endregion
}

/** ARGB of an opaque sRGB color. */
internal val Rgb.argb: Int get() = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
