package askrepo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class RemoteFile(val path: String, val content: ByteArray, val size: Long)

private const val BITBUCKET_PARALLEL_FETCHES = 2
private const val GITHUB_PARALLEL_FETCHES = 8
private const val MAX_RETRIES_429 = 5
private const val MAX_RETRY_WAIT_MS = 5_000L

private fun retryAfterMillis(res: HttpResponse<*>, attempt: Int): Long {
    val header = res.headers().firstValue("retry-after").orElse(null)
    val fromHeader = header?.toLongOrNull()?.let { it * 1000L }
    val backoff = 1000L shl attempt
    return (fromHeader ?: backoff).coerceIn(1000L, MAX_RETRY_WAIT_MS)
}

private fun fetchParallel(
    parallelism: Int,
    paths: List<String>,
    sizes: List<Long>,
    fetcher: (String) -> ByteArray?,
): List<RemoteFile> {
    val executor = Executors.newFixedThreadPool(parallelism)
    try {
        val futures = paths.mapIndexed { i, path ->
            executor.submit<RemoteFile?> {
                val content = fetcher(path) ?: return@submit null
                RemoteFile(path, content, sizes[i])
            }
        }
        return futures.mapNotNull { it.get() }
    } finally {
        executor.shutdown()
    }
}

interface GitProvider {
    fun listAndFetchFiles(workspace: String, repo: String, branch: String, onProgress: (String) -> Unit = {}): List<RemoteFile>
}

class BitbucketProvider(private val token: String) : GitProvider {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.bitbucket.org/2.0"
    private val authHeader: String = if (':' in token) {
        "Basic " + Base64.getEncoder().encodeToString(token.toByteArray())
    } else {
        "Bearer $token"
    }

    override fun listAndFetchFiles(workspace: String, repo: String, branch: String, onProgress: (String) -> Unit): List<RemoteFile> {
        onProgress("Listing files from Bitbucket...")
        val paths = listFiles(workspace, repo, branch)
        System.err.println("  listed ${paths.size} file(s) from Bitbucket")
        val included = paths.filter { Ingest.isIncludedFile(it.path) && it.size <= Defaults.MAX_FILE_BYTES }
        onProgress("Fetching ${included.size} files from Bitbucket ($BITBUCKET_PARALLEL_FETCHES parallel)...")
        val out = fetchParallel(BITBUCKET_PARALLEL_FETCHES, included.map { it.path }, included.map { it.size }) { path ->
            fetchFile(workspace, repo, branch, path)
        }
        onProgress("Fetched ${out.size} files from Bitbucket")
        System.err.println("  fetched ${out.size} included file(s)")
        return out
    }

    private data class FileInfo(val path: String, val size: Long)

    private fun listFiles(workspace: String, repo: String, branch: String): List<FileInfo> {
        val files = ArrayList<FileInfo>()
        var url: String? = "$baseUrl/repositories/$workspace/$repo/src/$branch/?pagelen=100&max_depth=20"
        while (url != null) {
            val body = get(url)
            val page = json.decodeFromString(SrcListResponse.serializer(), body)
            for (entry in page.values) {
                if (entry.type == "commit_file") {
                    files.add(FileInfo(entry.path, entry.size ?: 0))
                }
            }
            url = page.next
        }
        return files
    }

    private fun fetchFile(workspace: String, repo: String, branch: String, path: String): ByteArray? {
        val url = "$baseUrl/repositories/$workspace/$repo/src/$branch/$path"
        return try {
            getBytes(url)
        } catch (t: Throwable) {
            System.err.println("  skip (fetch error): $path — ${t.message}")
            null
        }
    }

    private fun get(url: String): String {
        var attempt = 0
        while (true) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("authorization", authHeader)
                .GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 429 && attempt < MAX_RETRIES_429) {
                val waitMs = retryAfterMillis(res, attempt)
                System.err.println("  429 rate-limited on $url, retrying in ${waitMs}ms (attempt ${attempt + 1}/$MAX_RETRIES_429)")
                Thread.sleep(waitMs)
                attempt++
                continue
            }
            if (res.statusCode() !in 200..299) error("bitbucket ${res.statusCode()}: ${res.body().take(300)}")
            return res.body()
        }
    }

    private fun getBytes(url: String): ByteArray {
        var attempt = 0
        while (true) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("authorization", authHeader)
                .GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (res.statusCode() == 429 && attempt < MAX_RETRIES_429) {
                val waitMs = retryAfterMillis(res, attempt)
                System.err.println("  429 rate-limited on $url, retrying in ${waitMs}ms (attempt ${attempt + 1}/$MAX_RETRIES_429)")
                Thread.sleep(waitMs)
                attempt++
                continue
            }
            if (res.statusCode() !in 200..299) error("bitbucket ${res.statusCode()}")
            return res.body()
        }
    }

    @Serializable
    private data class SrcListResponse(
        val values: List<SrcEntry> = emptyList(),
        val next: String? = null,
    )

    @Serializable
    private data class SrcEntry(
        val path: String,
        val type: String,
        val size: Long? = null,
    )
}

class GitHubProvider(private val token: String) : GitProvider {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.github.com"

    override fun listAndFetchFiles(workspace: String, repo: String, branch: String, onProgress: (String) -> Unit): List<RemoteFile> {
        onProgress("Listing files from GitHub...")
        val tree = listTree(workspace, repo, branch)
        System.err.println("  listed ${tree.size} file(s) from GitHub")
        val included = tree.filter { it.type == "blob" && Ingest.isIncludedFile(it.path) && (it.size ?: 0) <= Defaults.MAX_FILE_BYTES }
        onProgress("Fetching ${included.size} files from GitHub ($GITHUB_PARALLEL_FETCHES parallel)...")
        val out = fetchParallel(GITHUB_PARALLEL_FETCHES, included.map { it.path }, included.map { it.size ?: 0L }) { path ->
            fetchFile(workspace, repo, branch, path)
        }
        onProgress("Fetched ${out.size} files from GitHub")
        System.err.println("  fetched ${out.size} included file(s)")
        return out
    }

    private fun listTree(owner: String, repo: String, branch: String): List<TreeEntry> {
        val body = get("$baseUrl/repos/$owner/$repo/git/trees/$branch?recursive=1")
        val resp = json.decodeFromString(TreeResponse.serializer(), body)
        return resp.tree
    }

    private fun fetchFile(owner: String, repo: String, branch: String, path: String): ByteArray? {
        val url = "$baseUrl/repos/$owner/$repo/contents/$path?ref=$branch"
        return try {
            val body = get(url)
            val resp = json.decodeFromString(ContentsResponse.serializer(), body)
            if (resp.encoding == "base64" && resp.content != null) {
                Base64.getMimeDecoder().decode(resp.content)
            } else null
        } catch (t: Throwable) {
            System.err.println("  skip (fetch error): $path — ${t.message}")
            null
        }
    }

    private fun get(url: String): String {
        var attempt = 0
        while (true) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("authorization", "Bearer $token")
                .header("accept", "application/vnd.github+json")
                .GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 429 && attempt < MAX_RETRIES_429) {
                val waitMs = retryAfterMillis(res, attempt)
                System.err.println("  429 rate-limited on $url, retrying in ${waitMs}ms (attempt ${attempt + 1}/$MAX_RETRIES_429)")
                Thread.sleep(waitMs)
                attempt++
                continue
            }
            if (res.statusCode() !in 200..299) error("github ${res.statusCode()}: ${res.body().take(300)}")
            return res.body()
        }
    }

    @Serializable
    private data class TreeResponse(val tree: List<TreeEntry> = emptyList())

    @Serializable
    private data class TreeEntry(
        val path: String,
        val type: String,
        val size: Long? = null,
    )

    @Serializable
    private data class ContentsResponse(
        val content: String? = null,
        val encoding: String? = null,
    )
}
