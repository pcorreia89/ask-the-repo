package askrepo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class EmbedInputType { DOCUMENT, QUERY }

interface EmbeddingClient {
    fun embed(texts: List<String>, type: EmbedInputType, onBatchProgress: (batch: Int, total: Int) -> Unit = { _, _ -> }): List<FloatArray>
}

class VoyageEmbeddingsClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = Defaults.VOYAGE_ENDPOINT,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : EmbeddingClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun embed(texts: List<String>, type: EmbedInputType, onBatchProgress: (batch: Int, total: Int) -> Unit): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val batches = texts.chunked(Defaults.EMBED_BATCH_SIZE)
        val out = ArrayList<FloatArray>(texts.size)
        for ((i, batch) in batches.withIndex()) {
            onBatchProgress(i + 1, batches.size)
            out.addAll(embedBatch(batch, type))
        }
        return out
    }

    private fun embedBatch(batch: List<String>, type: EmbedInputType): List<FloatArray> {
        val request = VoyageRequest(
            input = batch,
            model = model,
            inputType = when (type) {
                EmbedInputType.DOCUMENT -> "document"
                EmbedInputType.QUERY -> "query"
            },
        )
        val body = json.encodeToString(VoyageRequest.serializer(), request)
        val response = postWithRetry(body)
        val parsed = json.decodeFromString(VoyageResponse.serializer(), response)
        val sorted = parsed.data.sortedBy { it.index }
        return sorted.map { item ->
            val arr = FloatArray(item.embedding.size)
            for (i in item.embedding.indices) arr[i] = item.embedding[i].toFloat()
            arr
        }
    }

    private fun postWithRetry(body: String): String {
        var lastError: String? = null
        for (attempt in 0 until Defaults.HTTP_RETRIES) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (t: Throwable) {
                lastError = "transport: ${t.message}"
                sleepBackoff(attempt)
                continue
            }
            val status = res.statusCode()
            if (status in 200..299) return res.body()
            val shouldRetry = status == 429 || status in 500..599
            lastError = "http $status: ${res.body().take(300)}"
            if (!shouldRetry) break
            sleepBackoff(attempt)
        }
        error("voyage embeddings failed after ${Defaults.HTTP_RETRIES} attempts: $lastError")
    }

    private fun sleepBackoff(attempt: Int) {
        val ms = 1000L * (1 shl attempt)
        Thread.sleep(ms)
    }

    @Serializable
    private data class VoyageRequest(
        val input: List<String>,
        val model: String,
        @SerialName("input_type") val inputType: String,
    )

    @Serializable
    private data class VoyageResponse(
        val data: List<Item>,
        val model: String? = null,
    ) {
        @Serializable
        data class Item(
            val index: Int,
            val embedding: List<Double>,
        )
    }
}

class OllamaEmbeddingsClient(
    private val model: String = Defaults.OLLAMA_MODEL,
    private val baseUrl: String = Defaults.OLLAMA_BASE_URL,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : EmbeddingClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun embed(texts: List<String>, type: EmbedInputType, onBatchProgress: (batch: Int, total: Int) -> Unit): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val batches = texts.chunked(Defaults.EMBED_BATCH_SIZE)
        val out = ArrayList<FloatArray>(texts.size)
        for ((i, batch) in batches.withIndex()) {
            onBatchProgress(i + 1, batches.size)
            out.addAll(embedBatch(batch, type))
        }
        return out
    }

    private fun embedBatch(batch: List<String>, type: EmbedInputType): List<FloatArray> {
        val prefix = when (type) {
            EmbedInputType.DOCUMENT -> "search_document: "
            EmbedInputType.QUERY -> "search_query: "
        }
        val prefixed = batch.map { prefix + it }
        val request = OllamaRequest(model = model, input = prefixed)
        val body = json.encodeToString(OllamaRequest.serializer(), request)
        val response = postWithRetry(body)
        val parsed = json.decodeFromString(OllamaResponse.serializer(), response)
        return parsed.embeddings.map { embedding ->
            val arr = FloatArray(embedding.size)
            for (i in embedding.indices) arr[i] = embedding[i].toFloat()
            arr
        }
    }

    private fun postWithRetry(body: String): String {
        var lastError: String? = null
        for (attempt in 0 until Defaults.HTTP_RETRIES) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/embed"))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (t: Throwable) {
                lastError = "transport: ${t.message}"
                sleepBackoff(attempt)
                continue
            }
            val status = res.statusCode()
            if (status in 200..299) return res.body()
            val shouldRetry = status == 429 || status in 500..599
            lastError = "http $status: ${res.body().take(300)}"
            if (!shouldRetry) break
            sleepBackoff(attempt)
        }
        error("ollama embeddings failed after ${Defaults.HTTP_RETRIES} attempts: $lastError")
    }

    private fun sleepBackoff(attempt: Int) {
        val ms = 1000L * (1 shl attempt)
        Thread.sleep(ms)
    }

    @Serializable
    private data class OllamaRequest(
        val model: String,
        val input: List<String>,
    )

    @Serializable
    private data class OllamaResponse(
        val model: String? = null,
        val embeddings: List<List<Double>>,
    )
}

typealias BedrockInvoker = (modelId: String, body: String) -> String

class BedrockEmbeddingsClient(
    private val modelId: String,
    private val dimensions: Int? = null,
    private val invoker: BedrockInvoker = ::defaultBedrockInvoke,
) : EmbeddingClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val family: Family = when {
        modelId.startsWith("cohere.embed-") -> Family.COHERE
        modelId.startsWith("amazon.titan-embed-text-v2") -> Family.TITAN_V2
        else -> error(
            "unsupported Bedrock embedding model '$modelId' " +
                "(expected 'cohere.embed-*' or 'amazon.titan-embed-text-v2:*')"
        )
    }

    init {
        if (family == Family.COHERE && dimensions != null) {
            error("BEDROCK_EMBEDDING_DIMENSIONS is not supported for Cohere models; remove it or switch to Titan")
        }
        if (family == Family.TITAN_V2 && dimensions != null && dimensions !in TITAN_V2_DIMS) {
            error("BEDROCK_EMBEDDING_DIMENSIONS must be one of $TITAN_V2_DIMS for Titan v2, got $dimensions")
        }
    }

    override fun embed(texts: List<String>, type: EmbedInputType, onBatchProgress: (batch: Int, total: Int) -> Unit): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return when (family) {
            Family.COHERE -> embedCohere(texts, type, onBatchProgress)
            Family.TITAN_V2 -> embedTitan(texts, onBatchProgress)
        }
    }

    private fun embedCohere(texts: List<String>, type: EmbedInputType, onBatchProgress: (Int, Int) -> Unit): List<FloatArray> {
        val batches = texts.chunked(COHERE_MAX_BATCH)
        val out = ArrayList<FloatArray>(texts.size)
        for ((i, batch) in batches.withIndex()) {
            onBatchProgress(i + 1, batches.size)
            val body = json.encodeToString(
                CohereRequest.serializer(),
                CohereRequest(
                    texts = batch,
                    inputType = when (type) {
                        EmbedInputType.DOCUMENT -> "search_document"
                        EmbedInputType.QUERY -> "search_query"
                    },
                ),
            )
            val response = invoker(modelId, body)
            val parsed = json.decodeFromString(CohereResponse.serializer(), response)
            out.addAll(parsed.embeddings.map { it.toFloatArray() })
        }
        return out
    }

    private fun embedTitan(texts: List<String>, onBatchProgress: (Int, Int) -> Unit): List<FloatArray> {
        val out = ArrayList<FloatArray>(texts.size)
        for ((i, text) in texts.withIndex()) {
            onBatchProgress(i + 1, texts.size)
            val body = json.encodeToString(
                TitanRequest.serializer(),
                TitanRequest(
                    inputText = text,
                    dimensions = dimensions,
                    normalize = true,
                ),
            )
            val response = invoker(modelId, body)
            val parsed = json.decodeFromString(TitanResponse.serializer(), response)
            out.add(parsed.embedding.toFloatArray())
        }
        return out
    }

    private fun List<Double>.toFloatArray(): FloatArray {
        val arr = FloatArray(size)
        for (i in indices) arr[i] = this[i].toFloat()
        return arr
    }

    private enum class Family { COHERE, TITAN_V2 }

    @Serializable
    private data class CohereRequest(
        val texts: List<String>,
        @SerialName("input_type") val inputType: String,
    )

    @Serializable
    private data class CohereResponse(
        val embeddings: List<List<Double>> = emptyList(),
    )

    @Serializable
    private data class TitanRequest(
        val inputText: String,
        val dimensions: Int? = null,
        val normalize: Boolean,
    )

    @Serializable
    private data class TitanResponse(
        val embedding: List<Double> = emptyList(),
    )

    companion object {
        private const val COHERE_MAX_BATCH = 96
        private val TITAN_V2_DIMS = setOf(256, 512, 1024)
        private val sharedClient: BedrockRuntimeClient by lazy {
            BedrockRuntimeClient.builder().build()
        }

        fun defaultBedrockInvoke(modelId: String, body: String): String {
            val req = InvokeModelRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(body))
                .build()
            return sharedClient.invokeModel(req).body().asUtf8String()
        }
    }
}
