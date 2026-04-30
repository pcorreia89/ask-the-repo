package askrepo

import java.nio.file.Files
import java.nio.file.Path

object Defaults {
    const val ANTHROPIC_MODEL = "claude-haiku-4-5"
    const val VOYAGE_MODEL = "voyage-code-3"
    const val OLLAMA_MODEL = "nomic-embed-text"
    const val OLLAMA_BASE_URL = "http://localhost:11434"
    const val TOP_K = 12
    const val MAX_TOKENS = 1024

    const val ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages"
    const val VOYAGE_ENDPOINT = "https://api.voyageai.com/v1/embeddings"
    const val ANTHROPIC_VERSION = "2023-06-01"

    const val INDEX_DIR = ".ask-the-repo"
    const val MANIFEST_FILE = "manifest.json"
    const val FILES_FILE = "files.json"
    const val CHUNKS_FILE = "chunks.jsonl"
    const val VECTORS_FILE = "vectors.bin"

    const val MAX_FILE_BYTES = 1_000_000L
    const val EMBED_BATCH_SIZE = 64
    const val HTTP_RETRIES = 3

    val INCLUDED_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
        "go", "rs", "rb", "md", "mdx", "txt", "rst",
    )

    val ALWAYS_INCLUDE_NAMES_PREFIX = listOf("README", "CHANGELOG")

    val BUILTIN_IGNORES = listOf(
        ".git", "node_modules", ".venv", "venv", "dist", "build",
        "target", ".gradle", ".idea", ".ask-the-repo", "out",
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
        "Cargo.lock", "Gemfile.lock", "poetry.lock", "go.sum",
    )
}

enum class EmbeddingProvider { VOYAGE, OLLAMA }

enum class LlmProvider { ANTHROPIC, BEDROCK }

data class Config(
    val anthropicApiKey: String?,
    val voyageApiKey: String?,
    val embeddingProvider: EmbeddingProvider,
    val anthropicModel: String,
    val voyageModel: String,
    val ollamaModel: String,
    val ollamaBaseUrl: String,
    val topK: Int,
    val maxTokens: Int,
    val indexBase: Path,
    val adminUser: String,
    val adminPassword: String,
    val adminPort: Int,
    val slackBotToken: String?,
    val slackAppToken: String?,
    val bitbucketToken: String?,
    val githubToken: String?,
    val syncIntervalMinutes: Int?,
    val webhookSecret: String?,
    val databaseUrl: String? = null,
    val githubAppId: String? = null,
    val githubAppInstallationId: String? = null,
    val githubAppPrivateKey: String? = null,
    val llmProvider: LlmProvider = LlmProvider.ANTHROPIC,
    val bedrockModelId: String? = null,
) {
    fun createLlmClient(): LlmClient = when (llmProvider) {
        LlmProvider.ANTHROPIC -> {
            val key = anthropicApiKey
                ?: error("ANTHROPIC_API_KEY is required when LLM_PROVIDER=anthropic")
            AnthropicClient(key, anthropicModel)
        }
        LlmProvider.BEDROCK -> {
            val id = bedrockModelId
                ?: error("BEDROCK_MODEL_ID is required when LLM_PROVIDER=bedrock")
            BedrockClaudeClient(id)
        }
    }

    fun createEmbeddingClient(): EmbeddingClient = when (embeddingProvider) {
        EmbeddingProvider.VOYAGE -> {
            val key = voyageApiKey
                ?: error("VOYAGE_API_KEY is required when EMBEDDING_PROVIDER=voyage")
            VoyageEmbeddingsClient(key, voyageModel)
        }
        EmbeddingProvider.OLLAMA -> OllamaEmbeddingsClient(ollamaModel, ollamaBaseUrl)
    }

    val embeddingModelName: String
        get() = when (embeddingProvider) {
            EmbeddingProvider.VOYAGE -> voyageModel
            EmbeddingProvider.OLLAMA -> ollamaModel
        }

    fun resolveGitHubToken(): String? {
        if (!githubToken.isNullOrEmpty()) return githubToken
        val appId = githubAppId
        val installId = githubAppInstallationId
        val keyRaw = githubAppPrivateKey
        if (appId != null && installId != null && keyRaw != null) {
            val pem = GitHubApp.resolvePrivateKey(keyRaw)
            return GitHubApp.getInstallationToken(appId, installId, pem)
        }
        return null
    }

    companion object {
        fun load(workingDir: Path): Config {
            val env = HashMap<String, String>()
            env.putAll(System.getenv())
            val dotenv = workingDir.resolve(".env")
            if (Files.isRegularFile(dotenv)) {
                for ((k, v) in parseDotenv(Files.readString(dotenv))) {
                    env.putIfAbsent(k, v)
                }
            }

            val llmProviderStr = env["LLM_PROVIDER"]?.trim()?.lowercase() ?: ""
            val llmProvider = when (llmProviderStr) {
                "", "anthropic" -> LlmProvider.ANTHROPIC
                "bedrock" -> LlmProvider.BEDROCK
                else -> {
                    System.err.println("error: LLM_PROVIDER must be 'anthropic' or 'bedrock', got '$llmProviderStr'")
                    kotlin.system.exitProcess(2)
                }
            }
            val bedrockModelId = env["BEDROCK_MODEL_ID"]?.takeIf { it.isNotBlank() }

            val anthropic = env["ANTHROPIC_API_KEY"].orEmpty().trim().ifEmpty { null }
            if (llmProvider == LlmProvider.ANTHROPIC && anthropic == null) {
                System.err.println(
                    "error: ANTHROPIC_API_KEY must be set " +
                        "(in the environment or in ./.env). See .env.example."
                )
                kotlin.system.exitProcess(2)
            }
            if (llmProvider == LlmProvider.BEDROCK && bedrockModelId == null) {
                System.err.println(
                    "error: BEDROCK_MODEL_ID must be set when LLM_PROVIDER=bedrock " +
                        "(in the environment or in ./.env). See .env.example."
                )
                kotlin.system.exitProcess(2)
            }

            val providerStr = env["EMBEDDING_PROVIDER"]?.trim()?.lowercase() ?: ""
            val voyage = env["VOYAGE_API_KEY"].orEmpty().trim().ifEmpty { null }
            val provider = when {
                providerStr == "ollama" -> EmbeddingProvider.OLLAMA
                providerStr == "voyage" -> EmbeddingProvider.VOYAGE
                providerStr.isEmpty() && voyage != null -> EmbeddingProvider.VOYAGE
                providerStr.isEmpty() -> EmbeddingProvider.OLLAMA
                else -> {
                    System.err.println("error: EMBEDDING_PROVIDER must be 'voyage' or 'ollama', got '$providerStr'")
                    kotlin.system.exitProcess(2)
                }
            }

            val defaultBase = Path.of(System.getProperty("user.home"), ".ask-the-repo", "indexes")
            return Config(
                adminUser = env["ADMIN_USER"]?.takeIf { it.isNotBlank() } ?: "admin",
                adminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() } ?: "admin",
                adminPort = env["ADMIN_PORT"]?.toIntOrNull() ?: 3000,
                anthropicApiKey = anthropic,
                voyageApiKey = voyage,
                embeddingProvider = provider,
                anthropicModel = env["ANTHROPIC_MODEL"]?.takeIf { it.isNotBlank() }
                    ?: Defaults.ANTHROPIC_MODEL,
                voyageModel = env["VOYAGE_MODEL"]?.takeIf { it.isNotBlank() }
                    ?: Defaults.VOYAGE_MODEL,
                ollamaModel = env["OLLAMA_MODEL"]?.takeIf { it.isNotBlank() }
                    ?: Defaults.OLLAMA_MODEL,
                ollamaBaseUrl = env["OLLAMA_BASE_URL"]?.takeIf { it.isNotBlank() }
                    ?: Defaults.OLLAMA_BASE_URL,
                topK = env["ASK_THE_REPO_TOP_K"]?.toIntOrNull() ?: Defaults.TOP_K,
                maxTokens = env["ASK_THE_REPO_MAX_TOKENS"]?.toIntOrNull() ?: Defaults.MAX_TOKENS,
                indexBase = Path.of(env["ASK_THE_REPO_INDEX_BASE"] ?: defaultBase.toString()),
                slackBotToken = env["SLACK_BOT_TOKEN"]?.takeIf { it.isNotBlank() },
                slackAppToken = env["SLACK_APP_TOKEN"]?.takeIf { it.isNotBlank() },
                bitbucketToken = env["BITBUCKET_TOKEN"]?.takeIf { it.isNotBlank() },
                githubToken = env["GITHUB_TOKEN"]?.takeIf { it.isNotBlank() },
                syncIntervalMinutes = env["SYNC_INTERVAL_MINUTES"]?.toIntOrNull(),
                webhookSecret = env["WEBHOOK_SECRET"]?.takeIf { it.isNotBlank() },
                databaseUrl = env["DATABASE_URL"]?.takeIf { it.isNotBlank() },
                githubAppId = env["GITHUB_APP_ID"]?.takeIf { it.isNotBlank() },
                githubAppInstallationId = env["GITHUB_APP_INSTALLATION_ID"]?.takeIf { it.isNotBlank() },
                githubAppPrivateKey = env["GITHUB_APP_PRIVATE_KEY"]?.takeIf { it.isNotBlank() },
                llmProvider = llmProvider,
                bedrockModelId = bedrockModelId,
            )
        }
    }
}

internal fun parseDotenv(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim()
        var value = line.substring(eq + 1).trim()
        if (value.length >= 2 &&
            ((value.startsWith('"') && value.endsWith('"')) ||
                (value.startsWith('\'') && value.endsWith('\'')))
        ) {
            value = value.substring(1, value.length - 1)
        }
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}
