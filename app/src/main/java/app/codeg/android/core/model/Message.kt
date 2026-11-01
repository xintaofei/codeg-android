package app.codeg.android.core.model

import app.codeg.android.core.model.wire.InstantSerializer
import app.codeg.android.core.model.wire.nonEmptyString
import app.codeg.android.core.model.wire.objectOrNull
import app.codeg.android.core.model.wire.stringOrNull
import app.codeg.android.core.model.wire.boolOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/** Role of a rendered turn (Rust `TurnRole`). Unknown → [SYSTEM]. */
@Serializable(with = TurnRole.TurnRoleSerializer::class)
enum class TurnRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromWire(raw: String): TurnRole =
            entries.firstOrNull { it.wire == raw } ?: SYSTEM
    }

    object TurnRoleSerializer : KSerializer<TurnRole> {
        override val descriptor =
            kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
                "com.codeg.TurnRole",
                kotlinx.serialization.descriptors.PrimitiveKind.STRING,
            )

        override fun serialize(encoder: Encoder, value: TurnRole) =
            encoder.encodeString(value.wire)

        override fun deserialize(decoder: Decoder): TurnRole =
            fromWire(decoder.decodeString())
    }
}

/** Token usage for a single turn (Rust `TurnUsage`). */
@Serializable
data class TurnUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
) {
    val total: Int
        get() = inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens
}

/** Inline image payload (Rust `ImageData`). */
@Serializable
data class ImageData(
    val data: String,
    val mimeType: String = "image/png",
    val uri: String? = null,
)

/**
 * A polymorphic block of message content (Rust `ContentBlock`, internally
 * tagged by `type`). Decode-only. Unknown future variants decode to [Unknown]
 * instead of throwing.
 *
 * Decoded by hand from the raw JSON object (snake_case wire keys), mirroring the
 * iOS `init(from:)`. The custom [ContentBlockSerializer] lets `MessageTurn`
 * stay a normal `@Serializable`.
 */
@Serializable(with = ContentBlock.ContentBlockSerializer::class)
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Thinking(val text: String) : ContentBlock
    data class Image(val image: ImageData) : ContentBlock
    data class ImageGeneration(val revisedPrompt: String?, val image: ImageData?) : ContentBlock
    /**
     * [meta] is the ACP extensibility metadata the agent stamped on the call — an
     * opaque pass-through (the convention is agent-defined). Read today by the
     * context-compaction detector and Grok's plan-mode tool resolution.
     */
    data class ToolUse(
        val id: String?,
        val name: String,
        val inputPreview: String?,
        val meta: JsonObject? = null,
        val status: String? = null,
    ) : ContentBlock
    data class ToolResult(val id: String?, val outputPreview: String?, val isError: Boolean) : ContentBlock
    data class Unknown(val type: String) : ContentBlock

    companion object {
        fun fromWire(obj: JsonObject): ContentBlock {
            return when (val type = obj.stringOrNull("type") ?: "") {
                "text" -> Text(obj.stringOrNull("text").orEmpty())
                "thinking" -> Thinking(obj.stringOrNull("text").orEmpty())
                "image" -> Image(
                    ImageData(
                        data = obj.stringOrNull("data").orEmpty(),
                        mimeType = obj.stringOrNull("mime_type") ?: "image/png",
                        uri = obj.stringOrNull("uri"),
                    ),
                )
                "image_generation" -> ImageGeneration(
                    revisedPrompt = obj.stringOrNull("revised_prompt"),
                    image = obj.objectOrNull("image")?.let {
                        ImageData(
                            data = it.stringOrNull("data").orEmpty(),
                            mimeType = it.stringOrNull("mime_type") ?: "image/png",
                            uri = it.stringOrNull("uri"),
                        )
                    },
                )
                "tool_use" -> ToolUse(
                    id = obj.stringOrNull("tool_use_id"),
                    name = obj.nonEmptyString("tool_name") ?: "tool",
                    inputPreview = obj.stringOrNull("input_preview"),
                    meta = obj.objectOrNull("meta"),
                    status = obj.stringOrNull("status"),
                )
                "tool_result" -> ToolResult(
                    id = obj.stringOrNull("tool_use_id"),
                    outputPreview = obj.stringOrNull("output_preview"),
                    isError = obj.boolOrNull("is_error") ?: false,
                )
                else -> Unknown(type)
            }
        }
    }

    object ContentBlockSerializer : KSerializer<ContentBlock> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("com.codeg.ContentBlock")

        override fun deserialize(decoder: Decoder): ContentBlock {
            val input = decoder as? JsonDecoder
                ?: error("ContentBlock can only be decoded from JSON")
            return fromWire(input.decodeJsonElement().jsonObject)
        }

        override fun serialize(encoder: Encoder, value: ContentBlock) {
            throw UnsupportedOperationException("ContentBlock is decode-only")
        }
    }
}

/** One turn in a conversation transcript (Rust `MessageTurn`). */
@Serializable
data class MessageTurn(
    val id: String,
    val role: TurnRole,
    val blocks: List<ContentBlock> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    val usage: TurnUsage? = null,
    val durationMs: Int? = null,
    val model: String? = null,
    @Serializable(with = InstantSerializer::class)
    val completedAt: Instant? = null,
)
