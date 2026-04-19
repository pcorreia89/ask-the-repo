package askrepo

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepoManagerTest {

    private fun testConfig(base: Path): Config = Config(
        anthropicApiKey = "test-key",
        voyageApiKey = "test-key",
        embeddingProvider = EmbeddingProvider.VOYAGE,
        anthropicModel = Defaults.ANTHROPIC_MODEL,
        voyageModel = Defaults.VOYAGE_MODEL,
        ollamaModel = Defaults.OLLAMA_MODEL,
        ollamaBaseUrl = Defaults.OLLAMA_BASE_URL,
        topK = Defaults.TOP_K,
        maxTokens = Defaults.MAX_TOKENS,
        indexBase = base.resolve("indexes"),
        adminUser = "admin",
        adminPassword = "admin",
        adminPort = 3000,
        slackBotToken = null,
        slackAppToken = null,
        bitbucketToken = "bb-token",
        githubToken = "gh-token",
        syncIntervalMinutes = null,
        webhookSecret = null,
    )

    @Test
    fun registryRoundTrip() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base)
        val registry = RepoRegistry(listOf(
            RepoEntry("my-repo", "github", "org", "repo", "main", listOf("C123")),
            RepoEntry("other", "bitbucket", "ws", "other-repo", "develop"),
        ))
        RepoManager.saveRegistry(config, registry)
        val loaded = RepoManager.loadRegistry(config)
        assertEquals(registry, loaded)
    }

    @Test
    fun loadRegistryReturnsEmptyWhenMissing() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base)
        val loaded = RepoManager.loadRegistry(config)
        assertEquals(RepoRegistry(), loaded)
    }

    @Test
    fun reposForChannelReturnsAllWhenNoChannelRestriction() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base)
        val registry = RepoRegistry(listOf(
            RepoEntry("repo-a", "github", "org", "a"),
            RepoEntry("repo-b", "github", "org", "b"),
        ))
        RepoManager.saveRegistry(config, registry)
        val repos = RepoManager.reposForChannel(config, "any-channel")
        assertEquals(listOf("repo-a", "repo-b"), repos)
    }

    @Test
    fun reposForChannelFiltersRestrictedRepos() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base)
        val registry = RepoRegistry(listOf(
            RepoEntry("public", "github", "org", "pub"),
            RepoEntry("restricted", "github", "org", "priv", channels = listOf("C100", "C200")),
        ))
        RepoManager.saveRegistry(config, registry)

        val fromAllowed = RepoManager.reposForChannel(config, "C100")
        assertEquals(listOf("public", "restricted"), fromAllowed)

        val fromOther = RepoManager.reposForChannel(config, "C999")
        assertEquals(listOf("public"), fromOther)
    }

    @Test
    fun providerForReturnsGitHubProvider() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base)
        val entry = RepoEntry("test", "github", "org", "repo")
        val provider = RepoManager.providerFor(entry, config)
        assertTrue(provider is GitHubProvider)
    }

    @Test
    fun providerForReturnsBitbucketProvider() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base)
        val entry = RepoEntry("test", "bitbucket", "ws", "repo")
        val provider = RepoManager.providerFor(entry, config)
        assertTrue(provider is BitbucketProvider)
    }

    @Test
    fun providerForThrowsOnUnknownProvider() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base)
        val entry = RepoEntry("test", "gitlab", "ns", "repo")
        val ex = try {
            RepoManager.providerFor(entry, config)
            null
        } catch (e: IllegalStateException) { e }
        assertTrue(ex != null)
        assertTrue(ex.message!!.contains("unknown provider"))
    }

    @Test
    fun providerForThrowsWhenTokenMissing() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base).copy(githubToken = null)
        val entry = RepoEntry("test", "github", "org", "repo")
        val ex = try {
            RepoManager.providerFor(entry, config)
            null
        } catch (e: IllegalStateException) { e }
        assertTrue(ex != null)
        assertTrue(ex.message!!.contains("GitHub auth required"))
    }

    @Test
    fun registryFileIsUnderIndexBaseParent() {
        val base = Files.createTempDirectory("repo-mgr-test")
        val config = testConfig(base)
        val file = RepoManager.registryFile(config)
        assertEquals(base.resolve("repos.json"), file)
    }
}
