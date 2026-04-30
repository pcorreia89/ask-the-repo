package askrepo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler
import software.amazon.awssdk.services.bedrockruntime.model.PayloadPart

class BedrockClaudeClient(
    private val modelId: String,
) : LlmClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun message(systemPrompt: String, userContent: String, maxTokens: Int): String {
        val body = json.encodeToString(
            BedrockRequest.serializer(),
            BedrockRequest(
                anthropicVersion = BEDROCK_ANTHROPIC_VERSION,
                maxTokens = maxTokens,
                system = systemPrompt,
                messages = listOf(BedrockMessage("user", userContent)),
            ),
        )
        val req = InvokeModelRequest.builder()
            .modelId(modelId)
            .contentType("application/json")
            .accept("application/json")
            .body(SdkBytes.fromUtf8String(body))
            .build()
        val resp = sharedClient.invokeModel(req).body().asUtf8String()
        val parsed = json.decodeFromString(BedrockResponse.serializer(), resp)
        return parsed.content.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }
    }

    override fun messageStreaming(
        systemPrompt: String,
        userContent: String,
        maxTokens: Int,
        onChunk: (String) -> Unit,
    ): String {
        val body = json.encodeToString(
            BedrockRequest.serializer(),
            BedrockRequest(
                anthropicVersion = BEDROCK_ANTHROPIC_VERSION,
                maxTokens = maxTokens,
                system = systemPrompt,
                messages = listOf(BedrockMessage("user", userContent)),
            ),
        )
        val req = InvokeModelWithResponseStreamRequest.builder()
            .modelId(modelId)
            .contentType("application/json")
            .accept("application/json")
            .body(SdkBytes.fromUtf8String(body))
            .build()

        val full = StringBuilder()
        val visitor = InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
            .onChunk { part: PayloadPart ->
                val data = part.bytes().asUtf8String()
                try {
                    val event = json.decodeFromString(StreamEvent.serializer(), data)
                    if (event.type == "content_block_delta" && event.delta?.text != null) {
                        full.append(event.delta.text)
                        onChunk(full.toString())
                    }
                } catch (_: Exception) { }
            }
            .build()

        val handler = InvokeModelWithResponseStreamResponseHandler.builder()
            .subscriber(visitor)
            .build()

        sharedAsyncClient.invokeModelWithResponseStream(req, handler).join()
        return full.toString()
    }

    @Serializable
    private data class BedrockRequest(
        @SerialName("anthropic_version") val anthropicVersion: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<BedrockMessage>,
    )

    @Serializable
    private data class BedrockMessage(val role: String, val content: String)

    @Serializable
    private data class BedrockResponse(val content: List<Block> = emptyList())

    @Serializable
    private data class Block(val type: String, val text: String? = null)

    @Serializable
    private data class StreamDelta(val text: String? = null)

    @Serializable
    private data class StreamEvent(
        val type: String,
        val delta: StreamDelta? = null,
    )

    companion object {
        private const val BEDROCK_ANTHROPIC_VERSION = "bedrock-2023-05-31"

        private val sharedClient: BedrockRuntimeClient by lazy {
            BedrockRuntimeClient.builder().build()
        }

        private val sharedAsyncClient: BedrockRuntimeAsyncClient by lazy {
            BedrockRuntimeAsyncClient.builder().build()
        }
    }
}
