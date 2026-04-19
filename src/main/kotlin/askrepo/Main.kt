package askrepo

import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime

fun printUsage() {
    System.err.println(
        """
        |ask-the-repo — local RAG Q&A over git repositories.
        |
        |Usage:
        |  ask-the-repo ingest --path <repo-path> [--name <index-name>]
        |      Walk a local repo, chunk, embed, and persist an index.
        |      Without --name, index is stored inside the repo at .ask-the-repo/.
        |      With --name, index is stored centrally under ~/.ask-the-repo/indexes/<name>/.
        |
        |  ask-the-repo sync [--name <index-name>] [--interval <minutes>]
        |      Fetch files via Bitbucket/GitHub API and re-ingest. Without --name,
        |      syncs all repos defined in ~/.ask-the-repo/repos.json.
        |      With --interval, runs continuously on the given schedule.
        |
        |  ask-the-repo ask "<question>" [--path <repo-path> | --name <index-name>]
        |      Answer a natural-language question. Use --path for a repo-local index
        |      or --name for a centrally stored index. Defaults to current directory.
        |
        |  ask-the-repo list
        |      List all centrally stored named indexes.
        |
        |  ask-the-repo serve
        |      Start the Slack bot (requires SLACK_BOT_TOKEN and SLACK_APP_TOKEN).
        |
        |Environment:
        |  ANTHROPIC_API_KEY      required
        |  EMBEDDING_PROVIDER     ollama (default) or voyage
        |  OLLAMA_BASE_URL        Ollama server URL (default: http://localhost:11434)
        |  OLLAMA_MODEL           Ollama model name (default: nomic-embed-text)
        |  VOYAGE_API_KEY         required when EMBEDDING_PROVIDER=voyage
        |  See .env.example for optional overrides.
        """.trimMargin()
    )
}

private class ParsedArgs(
    val path: Path?,
    val name: String?,
    val url: String?,
    val branch: String?,
    val interval: Int?,
    val positional: List<String>,
)

private fun parseFlags(args: List<String>): ParsedArgs {
    var path: Path? = null
    var name: String? = null
    var url: String? = null
    var branch: String? = null
    var interval: Int? = null
    val positional = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--path" -> {
                require(i + 1 < args.size) { "--path requires a value" }
                path = Paths.get(args[i + 1])
                i += 2
            }
            a.startsWith("--path=") -> {
                path = Paths.get(a.removePrefix("--path="))
                i += 1
            }
            a == "--name" -> {
                require(i + 1 < args.size) { "--name requires a value" }
                name = args[i + 1]
                i += 2
            }
            a.startsWith("--name=") -> {
                name = a.removePrefix("--name=")
                i += 1
            }
            a == "--url" -> {
                require(i + 1 < args.size) { "--url requires a value" }
                url = args[i + 1]
                i += 2
            }
            a.startsWith("--url=") -> {
                url = a.removePrefix("--url=")
                i += 1
            }
            a == "--branch" -> {
                require(i + 1 < args.size) { "--branch requires a value" }
                branch = args[i + 1]
                i += 2
            }
            a.startsWith("--branch=") -> {
                branch = a.removePrefix("--branch=")
                i += 1
            }
            a == "--interval" -> {
                require(i + 1 < args.size) { "--interval requires a value" }
                interval = args[i + 1].toInt()
                i += 2
            }
            a.startsWith("--interval=") -> {
                interval = a.removePrefix("--interval=").toInt()
                i += 1
            }
            else -> {
                positional.add(a)
                i += 1
            }
        }
    }
    return ParsedArgs(path, name, url, branch, interval, positional)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        kotlin.system.exitProcess(1)
    }

    when (args[0]) {
        "-h", "--help", "help" -> {
            printUsage()
            return
        }
        "ingest" -> {
            val parsed = try {
                parseFlags(args.drop(1))
            } catch (t: IllegalArgumentException) {
                System.err.println("error: ${t.message}")
                kotlin.system.exitProcess(1)
            }
            val repoPath = parsed.path ?: run {
                System.err.println("error: ingest requires --path <repo-path>")
                kotlin.system.exitProcess(1)
            }
            val config = Config.load(Paths.get("").toAbsolutePath())
            Store.init(config.databaseUrl)
            val indexDir = if (parsed.name != null) {
                Store.namedDir(config.indexBase, parsed.name)
            } else null
            Ingest.run(config, repoPath, indexDir)
        }
        "ask" -> {
            val parsed = try {
                parseFlags(args.drop(1))
            } catch (t: IllegalArgumentException) {
                System.err.println("error: ${t.message}")
                kotlin.system.exitProcess(1)
            }
            if (parsed.positional.isEmpty()) {
                System.err.println("error: ask requires a question argument, e.g. ask-the-repo ask \"how does X work?\"")
                kotlin.system.exitProcess(1)
            }
            val question = parsed.positional.joinToString(" ")
            if (question.length > 10_000) {
                System.err.println("error: question too long (${question.length} chars). Maximum is 10,000 characters.")
                kotlin.system.exitProcess(1)
            }
            val config = Config.load(Paths.get("").toAbsolutePath())
            Store.init(config.databaseUrl)
            val indexDir = if (parsed.name != null) {
                Store.namedDir(config.indexBase, parsed.name)
            } else {
                val repoPath = parsed.path ?: Paths.get("").toAbsolutePath()
                Store.repoLocalDir(repoPath)
            }
            Answer.run(config, indexDir, question)
        }
        "sync" -> {
            val parsed = try {
                parseFlags(args.drop(1))
            } catch (t: IllegalArgumentException) {
                System.err.println("error: ${t.message}")
                kotlin.system.exitProcess(1)
            }
            val config = Config.load(Paths.get("").toAbsolutePath())
            Store.init(config.databaseUrl)
            if (parsed.interval != null) {
                while (true) {
                    println("${LocalDateTime.now()} syncing...")
                    RepoManager.sync(config, parsed.name)
                    Thread.sleep(parsed.interval * 60_000L)
                }
            } else {
                RepoManager.sync(config, parsed.name)
            }
        }
        "list" -> {
            val config = Config.load(Paths.get("").toAbsolutePath())
            Store.init(config.databaseUrl)
            val names = Store.listNamedIndexes(config.indexBase)
            val registry = RepoManager.loadRegistry(config)
            if (names.isEmpty() && registry.repos.isEmpty()) {
                println("No indexed repos found.")
                println("Use `ask-the-repo sync` (with repos.json) or")
                println("     `ask-the-repo ingest --path <repo> --name <name>` to get started.")
            } else {
                if (names.isNotEmpty()) {
                    println("Named indexes (${config.indexBase}):")
                    for (n in names) {
                        val manifest = Store.readManifest(Store.namedDir(config.indexBase, n))
                        val regEntry = registry.repos.find { it.name == n }
                        val source = if (regEntry != null) {
                            "${regEntry.provider}:${regEntry.workspace}/${regEntry.repo}"
                        } else "local"
                        val channels = if (regEntry != null && regEntry.channels.isNotEmpty()) {
                            " channels=${regEntry.channels.joinToString(",")}"
                        } else ""
                        val info = if (manifest != null) " updated ${manifest.updatedAt}" else ""
                        println("  $n [$source]$channels$info")
                    }
                }
                val unindexed = registry.repos.filter { it.name !in names }
                if (unindexed.isNotEmpty()) {
                    println("Registered but not yet indexed (run `sync`):")
                    for (r in unindexed) println("  ${r.name} [${r.provider}:${r.workspace}/${r.repo}]")
                }
            }
        }
        "serve" -> {
            val config = Config.load(Paths.get("").toAbsolutePath())
            Store.init(config.databaseUrl)
            AdminServer.start(config)
            if (config.syncIntervalMinutes != null && config.syncIntervalMinutes > 0) {
                Thread({
                    while (true) {
                        try {
                            System.err.println("${LocalDateTime.now()} auto-sync: syncing all repos...")
                            RepoManager.sync(config)
                            System.err.println("${LocalDateTime.now()} auto-sync: done")
                        } catch (t: Throwable) {
                            System.err.println("${LocalDateTime.now()} auto-sync error: ${t.message}")
                        }
                        Thread.sleep(config.syncIntervalMinutes * 60_000L)
                    }
                }, "auto-sync").apply { isDaemon = true }.start()
                System.err.println("auto-sync enabled: every ${config.syncIntervalMinutes} minutes")
            }
            if (!config.slackBotToken.isNullOrEmpty() && !config.slackAppToken.isNullOrEmpty()) {
                SlackBot.start(config)
            } else {
                System.err.println(
                    "SLACK_BOT_TOKEN / SLACK_APP_TOKEN not set — running admin UI only. " +
                        "Set them in .env to enable the Slack bot."
                )
                Thread.currentThread().join()
            }
        }
        else -> {
            System.err.println("unknown command: ${args[0]}")
            printUsage()
            kotlin.system.exitProcess(1)
        }
    }
}
