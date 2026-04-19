package askrepo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class RepoEntry(
    val name: String,
    val provider: String = "",
    val workspace: String = "",
    val repo: String = "",
    val branch: String = "main",
    val channels: List<String> = emptyList(),
)

@Serializable
data class RepoRegistry(
    val repos: List<RepoEntry> = emptyList(),
)

object RepoManager {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun registryFile(config: Config): Path =
        config.indexBase.parent.resolve("repos.json")

    fun loadRegistry(config: Config): RepoRegistry {
        Store.pg?.let { return it.loadRegistry() }
        val f = registryFile(config)
        if (!Files.isRegularFile(f)) return RepoRegistry()
        return json.decodeFromString(RepoRegistry.serializer(), Files.readString(f))
    }

    fun saveRegistry(config: Config, registry: RepoRegistry) {
        Store.pg?.let { it.saveRegistry(registry); return }
        val f = registryFile(config)
        Files.createDirectories(f.parent)
        Files.writeString(f, json.encodeToString(RepoRegistry.serializer(), registry))
    }

    fun providerFor(entry: RepoEntry, config: Config): GitProvider {
        return when (entry.provider.lowercase()) {
            "bitbucket" -> {
                val token = config.bitbucketToken
                    ?: error("BITBUCKET_TOKEN is required for Bitbucket repos. See .env.example.")
                BitbucketProvider(token)
            }
            "github" -> {
                val token = config.resolveGitHubToken()
                    ?: error(
                        "GitHub auth required: set GITHUB_TOKEN, or set " +
                            "GITHUB_APP_ID + GITHUB_APP_INSTALLATION_ID + GITHUB_APP_PRIVATE_KEY. " +
                            "See .env.example."
                    )
                GitHubProvider(token)
            }
            else -> error("unknown provider: ${entry.provider}. Use 'bitbucket' or 'github'.")
        }
    }

    fun sync(config: Config, name: String? = null, onProgress: (String) -> Unit = {}) {
        val registry = loadRegistry(config)
        if (registry.repos.isEmpty()) {
            System.err.println("no repos in repos.json. Add entries and re-run.")
            System.err.println("repos.json location: ${registryFile(config)}")
            return
        }

        val targets = if (name != null) {
            val entry = registry.repos.find { it.name == name }
            if (entry == null) {
                System.err.println("repo '$name' not found in repos.json")
                return
            }
            listOf(entry)
        } else {
            registry.repos
        }

        for (entry in targets) {
            onProgress("Syncing ${entry.name} (${entry.provider}:${entry.workspace}/${entry.repo}@${entry.branch})...")
            System.err.println("syncing ${entry.name} (${entry.provider}:${entry.workspace}/${entry.repo}@${entry.branch})...")
            try {
                val provider = providerFor(entry, config)
                val files = provider.listAndFetchFiles(entry.workspace, entry.repo, entry.branch, onProgress)
                val indexDir = Store.namedDir(config.indexBase, entry.name)
                val label = "${entry.provider}:${entry.workspace}/${entry.repo}"
                Ingest.runFromRemoteFiles(config, files, indexDir, label, onProgress)
            } catch (t: Throwable) {
                System.err.println("error syncing ${entry.name}: ${t.message}")
            }
        }
    }

    fun reposForChannel(config: Config, channelId: String): List<String> {
        val registry = loadRegistry(config)
        val matching = registry.repos.filter { entry ->
            entry.channels.isEmpty() || entry.channels.any { it == channelId }
        }
        return matching.map { it.name }
    }
}
