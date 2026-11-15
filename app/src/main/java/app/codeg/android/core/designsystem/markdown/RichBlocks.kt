package app.codeg.android.core.designsystem.markdown

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether ```` ```html ```` fences in replies render as previews — the per-device "Render
 * HTML in chat" setting (the web's `workspace:chat-html-rendering`), off by default. Set
 * once at the app's root from the stored setting; false wherever it is not set.
 */
val LocalChatHtmlRendering = compositionLocalOf { false }

/**
 * Called by a rich block (a Mermaid diagram, an HTML preview) whose result just landed and
 * changed its height after the first layout, so a transcript whose reader was at the bottom
 * can keep them there (iOS `transcriptContentGrew`). Not called after the reader's own
 * toggles: what they asked for must not move the page under them. A no-op outside one.
 */
val LocalRichBlockGrew = staticCompositionLocalOf<() -> Unit> { {} }
