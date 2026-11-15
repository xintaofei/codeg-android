package app.codeg.android.core.designsystem.webrender

import kotlinx.serialization.json.JsonPrimitive

/**
 * The page Mermaid runs in: an empty document the renderer loads once, hands the bundled
 * `mermaid.min.js` (11.17.2, the version codeg's web client pins) and keeps for every later
 * diagram. Port of iOS `MermaidHost`.
 *
 * Mermaid is set up the way the web's engine (`@streamdown/mermaid`) sets it up —
 * `startOnLoad: false`, `securityLevel: "strict"`, error rendering suppressed — with the
 * theme `"dark"` / `"default"` the web picks per color scheme, in the generic `monospace`
 * family the web asks for (Android resolves it to its system monospace face).
 *
 * Every value a call carries goes in as a JSON literal ([js]) — never spliced into script
 * source as text.
 */
object MermaidHost {
    const val FONT_FAMILY = "monospace"

    /** The bundled library, in the APK's assets. */
    const val LIBRARY_ASSET = "mermaid/mermaid.min.js"

    /**
     * No network of any kind (fetch, XHR, WebSocket, images, fonts, frames, forms) — on top
     * of the WebView refusing every request. Scripts are the app's own, injected; Mermaid
     * itself needs inline styles.
     */
    private const val CSP = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; " +
        "style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; " +
        "frame-src 'none'; form-action 'none'; base-uri 'none'"

    /** The host document. The stage holds the diagram being drawn at its top-left corner. */
    val PAGE_HTML: String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="$CSP">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        html,body{margin:0;padding:0;background:transparent;-webkit-text-size-adjust:100%}
        #codeg-stage{position:absolute;left:0;top:0}
        #codeg-stage>svg{display:block}
        </style>
        </head>
        <body><div id="codeg-stage"></div></body>
        </html>
    """.trimIndent()

    /**
     * Installs `window.__codegMermaid` once the library has run:
     * - `start(job, source, theme, font)` begins a render; `take(job)` is then `null` until it
     *   settles, and `{ok, svg}` or `{ok: false, message}` once (it is handed out only once).
     *   Re-initializes Mermaid only when the configuration changes.
     * - `stage(svg, width, height, background)` puts the SVG on the stage at `width` CSS
     *   pixels (and `height`, or the viewBox's ratio when 0) and returns its laid-out box.
     * - `clear()` empties the stage.
     */
    val BOOTSTRAP_SCRIPT: String = """
        (() => {
          let current = "";
          let seq = 0;
          const results = Object.create(null);
          const stage = () => document.getElementById("codeg-stage");
          window.__codegMermaid = {
            start(job, source, theme, font) {
              results[job] = null;
              const config = {
                startOnLoad: false,
                theme,
                securityLevel: "strict",
                fontFamily: font,
                suppressErrorRendering: true,
              };
              const key = JSON.stringify(config);
              if (key !== current) {
                mermaid.initialize(config);
                current = key;
              }
              seq += 1;
              const id = "codeg-mermaid-" + seq;
              Promise.resolve()
                .then(() => mermaid.render(id, source))
                .then(
                  (result) => { results[job] = { ok: true, svg: String(result.svg) }; },
                  (error) => {
                    for (const stray of [document.getElementById(id), document.getElementById("d" + id)]) {
                      if (stray) stray.remove();
                    }
                    const message = error && typeof error.message === "string" ? error.message : String(error);
                    results[job] = { ok: false, message };
                  },
                );
              return true;
            },
            take(job) {
              const result = results[job];
              if (result === undefined || result === null) return null;
              delete results[job];
              return result;
            },
            stage(svg, width, height, background) {
              document.documentElement.style.background = background;
              const host = stage();
              host.innerHTML = svg;
              const el = host.querySelector("svg");
              if (!el) return null;
              el.style.display = "block";
              el.style.maxWidth = "none";
              el.style.width = width + "px";
              el.style.height = height > 0 ? height + "px" : "auto";
              const box = el.getBoundingClientRect();
              return { width: box.width, height: box.height };
            },
            clear() {
              stage().innerHTML = "";
              return true;
            },
          };
          return true;
        })();
    """.trimIndent()

    /** A value as a JS literal: JSON-encoded, so it is data, never script. */
    fun js(value: String): String = JsonPrimitive(value).toString()

    fun js(value: Number): String = JsonPrimitive(value).toString()

    fun startCall(job: String, source: String, theme: MermaidTheme): String =
        "window.__codegMermaid.start(${js(job)}, ${js(source)}, ${js(theme.name)}, ${js(FONT_FAMILY)})"

    fun takeCall(job: String): String = "window.__codegMermaid.take(${js(job)})"

    fun stageCall(svg: String, width: Float, height: Float, background: String): String =
        "(() => { try { return window.__codegMermaid.stage(${js(svg)}, ${js(width)}, ${js(height)}, ${js(background)}); } " +
            "catch (e) { return { error: String(e && e.message || e) }; } })()"

    const val CLEAR_CALL = "window.__codegMermaid.clear()"
}
