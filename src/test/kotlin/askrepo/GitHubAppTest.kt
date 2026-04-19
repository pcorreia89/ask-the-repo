package askrepo

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubAppTest {

    private fun generateTestKeyPair(): Pair<String, String> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        val pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----"
        return pkcs8Pem to Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    @Test
    fun resolvePrivateKeyFromPemString() {
        val (pem, _) = generateTestKeyPair()
        val resolved = GitHubApp.resolvePrivateKey(pem)
        assertTrue(resolved.contains("BEGIN PRIVATE KEY"))
    }

    @Test
    fun resolvePrivateKeyFromFile() {
        val (pem, _) = generateTestKeyPair()
        val tmp = Files.createTempFile("test-key", ".pem")
        try {
            Files.writeString(tmp, pem)
            val resolved = GitHubApp.resolvePrivateKey(tmp.toString())
            assertTrue(resolved.contains("BEGIN PRIVATE KEY"))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun resolvePrivateKeyFromBase64() {
        val (pem, _) = generateTestKeyPair()
        val encoded = Base64.getEncoder().encodeToString(pem.toByteArray())
        val resolved = GitHubApp.resolvePrivateKey(encoded)
        assertTrue(resolved.contains("BEGIN PRIVATE KEY"))
    }

    @Test
    fun resolvePrivateKeyFromEscapedNewlines() {
        val (pem, _) = generateTestKeyPair()
        val escaped = pem.replace("\n", "\\n")
        val resolved = GitHubApp.resolvePrivateKey(escaped)
        assertTrue(resolved.contains("BEGIN PRIVATE KEY"))
    }

    @Test
    fun resolvePrivateKeyThrowsForInvalidInput() {
        assertFailsWith<IllegalStateException> {
            GitHubApp.resolvePrivateKey("not-a-key-or-path")
        }
    }

    @Test
    fun resolveGitHubTokenPrefersExplicitToken() {
        val config = Config(
            anthropicApiKey = "test",
            voyageApiKey = null,
            embeddingProvider = EmbeddingProvider.OLLAMA,
            anthropicModel = "m", voyageModel = "v",
            ollamaModel = "o", ollamaBaseUrl = "http://localhost:11434",
            topK = 5, maxTokens = 512,
            indexBase = java.nio.file.Path.of("/tmp"),
            adminUser = "a", adminPassword = "p", adminPort = 3000,
            slackBotToken = null, slackAppToken = null,
            bitbucketToken = null,
            githubToken = "explicit-token",
            syncIntervalMinutes = null, webhookSecret = null,
            githubAppId = "12345",
            githubAppInstallationId = "67890",
            githubAppPrivateKey = "some-key",
        )
        assertEquals("explicit-token", config.resolveGitHubToken())
    }

    @Test
    fun resolveGitHubTokenReturnsNullWithoutAnyConfig() {
        val config = Config(
            anthropicApiKey = "test",
            voyageApiKey = null,
            embeddingProvider = EmbeddingProvider.OLLAMA,
            anthropicModel = "m", voyageModel = "v",
            ollamaModel = "o", ollamaBaseUrl = "http://localhost:11434",
            topK = 5, maxTokens = 512,
            indexBase = java.nio.file.Path.of("/tmp"),
            adminUser = "a", adminPassword = "p", adminPort = 3000,
            slackBotToken = null, slackAppToken = null,
            bitbucketToken = null, githubToken = null,
            syncIntervalMinutes = null, webhookSecret = null,
        )
        assertEquals(null, config.resolveGitHubToken())
    }
}
