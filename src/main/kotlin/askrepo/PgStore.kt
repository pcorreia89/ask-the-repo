package askrepo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

class PgStore(databaseUrl: String) {

    private val jsonCompact = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jdbcUrl: String
    private val props: Properties

    init {
        val (u, p) = parseDatabaseUrl(databaseUrl)
        jdbcUrl = u
        props = p
        initSchema()
    }

    private fun conn(): Connection = DriverManager.getConnection(jdbcUrl, props)

    private fun parseDatabaseUrl(url: String): Pair<String, Properties> {
        val props = Properties()
        if (url.startsWith("jdbc:")) return url to props
        val normalized = url.replaceFirst("postgres://", "postgresql://")
        val uri = java.net.URI(normalized)
        uri.userInfo?.split(":", limit = 2)?.let { parts ->
            props["user"] = parts[0]
            if (parts.size > 1) props["password"] = parts[1]
        }
        val port = if (uri.port > 0) uri.port else 5432
        return "jdbc:postgresql://${uri.host}:$port${uri.path}" to props
    }

    private fun initSchema() {
        conn().use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE EXTENSION IF NOT EXISTS vector")
                s.execute("""
                    CREATE TABLE IF NOT EXISTS manifests (
                        index_name TEXT PRIMARY KEY,
                        repo_path TEXT NOT NULL,
                        model TEXT NOT NULL,
                        embedding_model TEXT NOT NULL,
                        dim INT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                """.trimIndent())
                s.execute("""
                    CREATE TABLE IF NOT EXISTS files_index (
                        index_name TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        chunk_ids JSONB NOT NULL DEFAULT '[]',
                        PRIMARY KEY (index_name, file_path)
                    )
                """.trimIndent())
                s.execute("""
                    CREATE TABLE IF NOT EXISTS chunks (
                        index_name TEXT NOT NULL,
                        id TEXT NOT NULL,
                        seq INT NOT NULL,
                        file_path TEXT NOT NULL,
                        start_line INT NOT NULL,
                        end_line INT NOT NULL,
                        language TEXT NOT NULL,
                        text TEXT NOT NULL,
                        embedding vector,
                        PRIMARY KEY (index_name, id)
                    )
                """.trimIndent())
                s.execute("""
                    CREATE TABLE IF NOT EXISTS repos (
                        name TEXT PRIMARY KEY,
                        provider TEXT NOT NULL DEFAULT '',
                        workspace TEXT NOT NULL DEFAULT '',
                        repo TEXT NOT NULL DEFAULT '',
                        branch TEXT NOT NULL DEFAULT 'main',
                        channels JSONB NOT NULL DEFAULT '[]'
                    )
                """.trimIndent())
            }
        }
    }

    fun exists(name: String): Boolean {
        conn().use { c ->
            c.prepareStatement("SELECT 1 FROM manifests WHERE index_name = ?").use { ps ->
                ps.setString(1, name)
                return ps.executeQuery().next()
            }
        }
    }

    fun listIndexes(): List<String> {
        conn().use { c ->
            c.prepareStatement("SELECT index_name FROM manifests ORDER BY index_name").use { ps ->
                val rs = ps.executeQuery()
                val names = mutableListOf<String>()
                while (rs.next()) names.add(rs.getString(1))
                return names
            }
        }
    }

    fun readManifest(name: String): Manifest? {
        conn().use { c ->
            c.prepareStatement(
                "SELECT repo_path, model, embedding_model, dim, created_at, updated_at FROM manifests WHERE index_name = ?"
            ).use { ps ->
                ps.setString(1, name)
                val rs = ps.executeQuery()
                if (!rs.next()) return null
                return Manifest(
                    repoPath = rs.getString("repo_path"),
                    model = rs.getString("model"),
                    embeddingModel = rs.getString("embedding_model"),
                    dim = rs.getInt("dim"),
                    createdAt = rs.getString("created_at"),
                    updatedAt = rs.getString("updated_at"),
                )
            }
        }
    }

    fun writeManifest(name: String, manifest: Manifest) {
        conn().use { c ->
            c.prepareStatement("""
                INSERT INTO manifests (index_name, repo_path, model, embedding_model, dim, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (index_name) DO UPDATE SET
                    repo_path = EXCLUDED.repo_path, model = EXCLUDED.model,
                    embedding_model = EXCLUDED.embedding_model, dim = EXCLUDED.dim,
                    created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at
            """.trimIndent()).use { ps ->
                ps.setString(1, name)
                ps.setString(2, manifest.repoPath)
                ps.setString(3, manifest.model)
                ps.setString(4, manifest.embeddingModel)
                ps.setInt(5, manifest.dim)
                ps.setString(6, manifest.createdAt)
                ps.setString(7, manifest.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    fun readFilesIndex(name: String): FilesIndex {
        conn().use { c ->
            c.prepareStatement("SELECT file_path, content_hash, chunk_ids FROM files_index WHERE index_name = ?").use { ps ->
                ps.setString(1, name)
                val rs = ps.executeQuery()
                val files = LinkedHashMap<String, FileEntry>()
                while (rs.next()) {
                    val ids = jsonCompact.decodeFromString(
                        ListSerializer(String.serializer()), rs.getString("chunk_ids")
                    )
                    files[rs.getString("file_path")] = FileEntry(rs.getString("content_hash"), ids)
                }
                return FilesIndex(files)
            }
        }
    }

    fun writeFilesIndex(name: String, index: FilesIndex) {
        conn().use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("DELETE FROM files_index WHERE index_name = ?").use { ps ->
                    ps.setString(1, name); ps.executeUpdate()
                }
                c.prepareStatement(
                    "INSERT INTO files_index (index_name, file_path, content_hash, chunk_ids) VALUES (?, ?, ?, ?::jsonb)"
                ).use { ps ->
                    for ((path, entry) in index.files) {
                        ps.setString(1, name)
                        ps.setString(2, path)
                        ps.setString(3, entry.contentHash)
                        ps.setString(4, jsonCompact.encodeToString(
                            ListSerializer(String.serializer()), entry.chunkIds
                        ))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            }
        }
    }

    fun readChunks(name: String): List<StoredChunk> {
        conn().use { c ->
            c.prepareStatement(
                "SELECT id, file_path, start_line, end_line, language, text FROM chunks WHERE index_name = ? ORDER BY seq"
            ).use { ps ->
                ps.setString(1, name)
                val rs = ps.executeQuery()
                val chunks = mutableListOf<StoredChunk>()
                while (rs.next()) {
                    chunks.add(StoredChunk(
                        id = rs.getString("id"),
                        filePath = rs.getString("file_path"),
                        startLine = rs.getInt("start_line"),
                        endLine = rs.getInt("end_line"),
                        language = rs.getString("language"),
                        text = rs.getString("text"),
                    ))
                }
                return chunks
            }
        }
    }

    fun writeChunks(name: String, chunks: List<StoredChunk>) {
        conn().use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("DELETE FROM chunks WHERE index_name = ?").use { ps ->
                    ps.setString(1, name); ps.executeUpdate()
                }
                c.prepareStatement(
                    "INSERT INTO chunks (index_name, id, seq, file_path, start_line, end_line, language, text) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    for ((i, chunk) in chunks.withIndex()) {
                        ps.setString(1, name)
                        ps.setString(2, chunk.id)
                        ps.setInt(3, i)
                        ps.setString(4, chunk.filePath)
                        ps.setInt(5, chunk.startLine)
                        ps.setInt(6, chunk.endLine)
                        ps.setString(7, chunk.language)
                        ps.setString(8, chunk.text)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            }
        }
    }

    fun readVectors(name: String): Pair<Int, List<FloatArray>> {
        conn().use { c ->
            c.prepareStatement("SELECT embedding FROM chunks WHERE index_name = ? ORDER BY seq").use { ps ->
                ps.setString(1, name)
                val rs = ps.executeQuery()
                val vectors = mutableListOf<FloatArray>()
                var dim = 0
                while (rs.next()) {
                    val vecStr = rs.getString("embedding")
                    if (vecStr == null) {
                        vectors.add(FloatArray(0))
                        continue
                    }
                    val arr = parseVector(vecStr)
                    if (dim == 0) dim = arr.size
                    vectors.add(arr)
                }
                return dim to vectors
            }
        }
    }

    fun writeVectors(name: String, dim: Int, vectors: List<FloatArray>) {
        conn().use { c ->
            c.autoCommit = false
            try {
                val ids = mutableListOf<String>()
                c.prepareStatement("SELECT id FROM chunks WHERE index_name = ? ORDER BY seq").use { ps ->
                    ps.setString(1, name)
                    val rs = ps.executeQuery()
                    while (rs.next()) ids.add(rs.getString("id"))
                }
                require(ids.size == vectors.size) { "chunks/vectors count mismatch: ${ids.size} vs ${vectors.size}" }

                c.prepareStatement(
                    "UPDATE chunks SET embedding = ?::vector WHERE index_name = ? AND id = ?"
                ).use { ps ->
                    for ((i, id) in ids.withIndex()) {
                        val v = vectors[i]
                        if (v.isEmpty()) continue
                        ps.setString(1, formatVector(v))
                        ps.setString(2, name)
                        ps.setString(3, id)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            }
        }
    }

    private fun formatVector(v: FloatArray): String =
        v.joinToString(",", "[", "]")

    private fun parseVector(s: String): FloatArray {
        val trimmed = s.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isEmpty()) return FloatArray(0)
        return trimmed.split(",").map { it.trim().toFloat() }.toFloatArray()
    }

    fun loadRegistry(): RepoRegistry {
        conn().use { c ->
            c.prepareStatement(
                "SELECT name, provider, workspace, repo, branch, channels FROM repos ORDER BY name"
            ).use { ps ->
                val rs = ps.executeQuery()
                val repos = mutableListOf<RepoEntry>()
                while (rs.next()) {
                    val channels = jsonCompact.decodeFromString(
                        ListSerializer(String.serializer()), rs.getString("channels")
                    )
                    repos.add(RepoEntry(
                        name = rs.getString("name"),
                        provider = rs.getString("provider"),
                        workspace = rs.getString("workspace"),
                        repo = rs.getString("repo"),
                        branch = rs.getString("branch"),
                        channels = channels,
                    ))
                }
                return RepoRegistry(repos)
            }
        }
    }

    fun saveRegistry(registry: RepoRegistry) {
        conn().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().executeUpdate("DELETE FROM repos")
                c.prepareStatement(
                    "INSERT INTO repos (name, provider, workspace, repo, branch, channels) VALUES (?, ?, ?, ?, ?, ?::jsonb)"
                ).use { ps ->
                    for (r in registry.repos) {
                        ps.setString(1, r.name)
                        ps.setString(2, r.provider)
                        ps.setString(3, r.workspace)
                        ps.setString(4, r.repo)
                        ps.setString(5, r.branch)
                        ps.setString(6, jsonCompact.encodeToString(
                            ListSerializer(String.serializer()), r.channels
                        ))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            }
        }
    }
}
