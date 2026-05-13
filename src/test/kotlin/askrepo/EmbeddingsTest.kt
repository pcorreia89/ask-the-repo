package askrepo

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbeddingsTest {

    @Test
    fun ollamaClientSendsCorrectRequestAndParsesResponse() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var capturedBody = ""
        server.createContext("/api/embed") { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().readText()
            val response = """{"model":"nomic-embed-text","embeddings":[[0.1,0.2,0.3],[0.4,0.5,0.6]]}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val client = OllamaEmbeddingsClient(
                model = "nomic-embed-text",
                baseUrl = "http://localhost:${server.address.port}",
            )
            val result = client.embed(listOf("hello", "world"), EmbedInputType.DOCUMENT)
            assertEquals(2, result.size)
            assertEquals(3, result[0].size)
            assertEquals(0.1f, result[0][0], 0.001f)
            assertEquals(0.6f, result[1][2], 0.001f)

            assertTrue(capturedBody.contains("\"model\":\"nomic-embed-text\""))
            assertTrue(capturedBody.contains("search_document: hello"))
            assertTrue(capturedBody.contains("search_document: world"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun ollamaClientUsesQueryPrefixForQueryType() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var capturedBody = ""
        server.createContext("/api/embed") { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().readText()
            val response = """{"model":"nomic-embed-text","embeddings":[[0.1,0.2,0.3]]}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val client = OllamaEmbeddingsClient(
                model = "nomic-embed-text",
                baseUrl = "http://localhost:${server.address.port}",
            )
            client.embed(listOf("what does this do?"), EmbedInputType.QUERY)
            assertTrue(capturedBody.contains("search_query: what does this do?"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun ollamaClientReturnsEmptyForEmptyInput() {
        val client = OllamaEmbeddingsClient()
        val result = client.embed(emptyList(), EmbedInputType.DOCUMENT)
        assertTrue(result.isEmpty())
    }

    @Test
    fun voyageClientReturnsEmptyForEmptyInput() {
        val client = VoyageEmbeddingsClient("fake-key", "fake-model")
        val result = client.embed(emptyList(), EmbedInputType.DOCUMENT)
        assertTrue(result.isEmpty())
    }

    @Test
    fun configCreatesVoyageClientByDefault() {
        val config = Config(
            anthropicApiKey = "test",
            voyageApiKey = "vk",
            embeddingProvider = EmbeddingProvider.VOYAGE,
            anthropicModel = "m",
            voyageModel = "voyage-code-3",
            ollamaModel = Defaults.OLLAMA_MODEL,
            ollamaBaseUrl = Defaults.OLLAMA_BASE_URL,
            topK = 5, maxTokens = 512,
            indexBase = java.nio.file.Path.of("/tmp"),
            adminUser = "a", adminPassword = "p", adminPort = 3000,
            slackBotToken = null, slackAppToken = null,
            bitbucketToken = null, githubToken = null,
            syncIntervalMinutes = null, webhookSecret = null,
        )
        val client = config.createEmbeddingClient()
        assertTrue(client is VoyageEmbeddingsClient)
    }

    @Test
    fun configCreatesOllamaClient() {
        val config = Config(
            anthropicApiKey = "test",
            voyageApiKey = null,
            embeddingProvider = EmbeddingProvider.OLLAMA,
            anthropicModel = "m",
            voyageModel = "voyage-code-3",
            ollamaModel = "nomic-embed-text",
            ollamaBaseUrl = "http://localhost:11434",
            topK = 5, maxTokens = 512,
            indexBase = java.nio.file.Path.of("/tmp"),
            adminUser = "a", adminPassword = "p", adminPort = 3000,
            slackBotToken = null, slackAppToken = null,
            bitbucketToken = null, githubToken = null,
            syncIntervalMinutes = null, webhookSecret = null,
        )
        val client = config.createEmbeddingClient()
        assertTrue(client is OllamaEmbeddingsClient)
    }

    @Test
    fun configVoyageWithoutKeyThrows() {
        val config = Config(
            anthropicApiKey = "test",
            voyageApiKey = null,
            embeddingProvider = EmbeddingProvider.VOYAGE,
            anthropicModel = "m",
            voyageModel = "voyage-code-3",
            ollamaModel = Defaults.OLLAMA_MODEL,
            ollamaBaseUrl = Defaults.OLLAMA_BASE_URL,
            topK = 5, maxTokens = 512,
            indexBase = java.nio.file.Path.of("/tmp"),
            adminUser = "a", adminPassword = "p", adminPort = 3000,
            slackBotToken = null, slackAppToken = null,
            bitbucketToken = null, githubToken = null,
            syncIntervalMinutes = null, webhookSecret = null,
        )
        assertFailsWith<IllegalStateException> {
            config.createEmbeddingClient()
        }
    }

    @Test
    fun embeddingModelNameReflectsProvider() {
        val voyageConfig = Config(
            anthropicApiKey = "t", voyageApiKey = "vk",
            embeddingProvider = EmbeddingProvider.VOYAGE,
            anthropicModel = "m", voyageModel = "voyage-code-3",
            ollamaModel = "nomic-embed-text",
            ollamaBaseUrl = Defaults.OLLAMA_BASE_URL,
            topK = 5, maxTokens = 512,
            indexBase = java.nio.file.Path.of("/tmp"),
            adminUser = "a", adminPassword = "p", adminPort = 3000,
            slackBotToken = null, slackAppToken = null,
            bitbucketToken = null, githubToken = null,
            syncIntervalMinutes = null, webhookSecret = null,
        )
        assertEquals("voyage-code-3", voyageConfig.embeddingModelName)

        val ollamaConfig = voyageConfig.copy(embeddingProvider = EmbeddingProvider.OLLAMA)
        assertEquals("nomic-embed-text", ollamaConfig.embeddingModelName)

        val bedrockConfig = voyageConfig.copy(
            embeddingProvider = EmbeddingProvider.BEDROCK,
            bedrockEmbeddingModelId = "amazon.titan-embed-text-v2:0",
        )
        assertEquals("amazon.titan-embed-text-v2:0", bedrockConfig.embeddingModelName)

        val bedrockWithDims = bedrockConfig.copy(bedrockEmbeddingDimensions = 512)
        assertEquals("amazon.titan-embed-text-v2:0@512", bedrockWithDims.embeddingModelName)
    }

    @Test
    fun bedrockCohereBatchesAndSendsInputType() {
        val captured = mutableListOf<Pair<String, String>>()
        val client = BedrockEmbeddingsClient(
            modelId = "cohere.embed-english-v3",
            invoker = { modelId, body ->
                captured += modelId to body
                """{"embeddings":[[0.1,0.2,0.3],[0.4,0.5,0.6]]}"""
            },
        )
        val result = client.embed(listOf("hello", "world"), EmbedInputType.DOCUMENT)
        assertEquals(2, result.size)
        assertEquals(3, result[0].size)
        assertEquals(0.1f, result[0][0], 0.001f)
        assertEquals(0.6f, result[1][2], 0.001f)
        assertEquals(1, captured.size)
        assertEquals("cohere.embed-english-v3", captured[0].first)
        assertTrue(captured[0].second.contains("\"input_type\":\"search_document\""))
        assertTrue(captured[0].second.contains("\"hello\""))
        assertTrue(captured[0].second.contains("\"world\""))
    }

    @Test
    fun bedrockCohereUsesQueryInputType() {
        var capturedBody = ""
        val client = BedrockEmbeddingsClient(
            modelId = "cohere.embed-multilingual-v3",
            invoker = { _, body ->
                capturedBody = body
                """{"embeddings":[[0.1]]}"""
            },
        )
        client.embed(listOf("what does this do?"), EmbedInputType.QUERY)
        assertTrue(capturedBody.contains("\"input_type\":\"search_query\""))
    }

    @Test
    fun bedrockTitanSendsOneCallPerText() {
        val captured = mutableListOf<String>()
        val responses = listOf(
            """{"embedding":[1.0,2.0,3.0]}""",
            """{"embedding":[4.0,5.0,6.0]}""",
        )
        var idx = 0
        val client = BedrockEmbeddingsClient(
            modelId = "amazon.titan-embed-text-v2:0",
            invoker = { _, body ->
                captured += body
                responses[idx++]
            },
        )
        val result = client.embed(listOf("a", "b"), EmbedInputType.DOCUMENT)
        assertEquals(2, captured.size)
        assertTrue(captured[0].contains("\"inputText\":\"a\""))
        assertTrue(captured[1].contains("\"inputText\":\"b\""))
        assertTrue(captured[0].contains("\"normalize\":true"))
        assertEquals(2, result.size)
        assertEquals(1.0f, result[0][0], 0.001f)
        assertEquals(6.0f, result[1][2], 0.001f)
    }

    @Test
    fun bedrockTitanIncludesDimensionsWhenSpecified() {
        var capturedBody = ""
        val client = BedrockEmbeddingsClient(
            modelId = "amazon.titan-embed-text-v2:0",
            dimensions = 512,
            invoker = { _, body ->
                capturedBody = body
                """{"embedding":[0.1,0.2]}"""
            },
        )
        client.embed(listOf("hello"), EmbedInputType.DOCUMENT)
        assertTrue(capturedBody.contains("\"dimensions\":512"), "expected dimensions in body, got: $capturedBody")
    }

    @Test
    fun bedrockTitanOmitsDimensionsWhenNull() {
        var capturedBody = ""
        val client = BedrockEmbeddingsClient(
            modelId = "amazon.titan-embed-text-v2:0",
            invoker = { _, body ->
                capturedBody = body
                """{"embedding":[0.1]}"""
            },
        )
        client.embed(listOf("hello"), EmbedInputType.DOCUMENT)
        assertTrue(!capturedBody.contains("dimensions"), "did not expect dimensions field, got: $capturedBody")
    }

    @Test
    fun bedrockReturnsEmptyForEmptyInput() {
        val client = BedrockEmbeddingsClient(
            modelId = "cohere.embed-english-v3",
            invoker = { _, _ -> error("should not be called") },
        )
        assertTrue(client.embed(emptyList(), EmbedInputType.DOCUMENT).isEmpty())
    }

    @Test
    fun bedrockUnsupportedModelThrows() {
        assertFailsWith<IllegalStateException> {
            BedrockEmbeddingsClient(modelId = "anthropic.claude-3-haiku-20240307-v1:0")
        }
    }

    @Test
    fun bedrockCohereRejectsDimensions() {
        assertFailsWith<IllegalStateException> {
            BedrockEmbeddingsClient(modelId = "cohere.embed-english-v3", dimensions = 512)
        }
    }

    @Test
    fun bedrockTitanRejectsInvalidDimensions() {
        assertFailsWith<IllegalStateException> {
            BedrockEmbeddingsClient(modelId = "amazon.titan-embed-text-v2:0", dimensions = 777)
        }
    }

    @Test
    fun configCreatesBedrockClient() {
        val config = Config(
            anthropicApiKey = "test",
            voyageApiKey = null,
            embeddingProvider = EmbeddingProvider.BEDROCK,
            anthropicModel = "m",
            voyageModel = Defaults.VOYAGE_MODEL,
            ollamaModel = Defaults.OLLAMA_MODEL,
            ollamaBaseUrl = Defaults.OLLAMA_BASE_URL,
            topK = 5, maxTokens = 512,
            indexBase = java.nio.file.Path.of("/tmp"),
            adminUser = "a", adminPassword = "p", adminPort = 3000,
            slackBotToken = null, slackAppToken = null,
            bitbucketToken = null, githubToken = null,
            syncIntervalMinutes = null, webhookSecret = null,
            bedrockEmbeddingModelId = "cohere.embed-english-v3",
        )
        val client = config.createEmbeddingClient()
        assertTrue(client is BedrockEmbeddingsClient)
    }

    @Test
    fun configBedrockWithoutModelIdThrows() {
        val config = Config(
            anthropicApiKey = "test",
            voyageApiKey = null,
            embeddingProvider = EmbeddingProvider.BEDROCK,
            anthropicModel = "m",
            voyageModel = Defaults.VOYAGE_MODEL,
            ollamaModel = Defaults.OLLAMA_MODEL,
            ollamaBaseUrl = Defaults.OLLAMA_BASE_URL,
            topK = 5, maxTokens = 512,
            indexBase = java.nio.file.Path.of("/tmp"),
            adminUser = "a", adminPassword = "p", adminPort = 3000,
            slackBotToken = null, slackAppToken = null,
            bitbucketToken = null, githubToken = null,
            syncIntervalMinutes = null, webhookSecret = null,
        )
        assertFailsWith<IllegalStateException> {
            config.createEmbeddingClient()
        }
    }
}
