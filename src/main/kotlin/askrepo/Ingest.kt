package askrepo

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

object Ingest {

    fun runFromRemoteFiles(config: Config, files: List<RemoteFile>, indexDir: Path, repoLabel: String, onProgress: (String) -> Unit = {}) {
        val priorManifest = Store.readManifest(indexDir)
        val priorFiles = Store.readFilesIndex(indexDir).files
        val priorChunks = Store.readChunks(indexDir).associateBy { it.id }
        val priorVectors = run {
            val (dim, vecs) = Store.readVectors(indexDir)
            if (dim == 0 || priorChunks.isEmpty() || vecs.size != priorChunks.size) {
                emptyMap()
            } else {
                Store.readChunks(indexDir).zip(vecs) { c, v -> c.id to v }.toMap()
            }
        }

        onProgress("Processing ${files.size} files...")
        System.err.println("included ${files.size} file(s) from $repoLabel")
        var changedCount = 0
        var reusedCount = 0
        val newFilesIndex = LinkedHashMap<String, FileEntry>()
        val newChunks = ArrayList<StoredChunk>()
        val newVectors = ArrayList<FloatArray>()
        val toEmbed = ArrayList<StoredChunk>()

        for (file in files) {
            val rel = file.path
            if (!looksLikeUtf8(file.content)) {
                System.err.println("skip (non-utf8): $rel")
                continue
            }
            val hash = sha256(file.content)
            val prior = priorFiles[rel]
            if (prior != null && prior.contentHash == hash &&
                prior.chunkIds.all { priorChunks.containsKey(it) && priorVectors.containsKey(it) }
            ) {
                reusedCount++
                newFilesIndex[rel] = FileEntry(hash, prior.chunkIds)
                for (id in prior.chunkIds) {
                    newChunks.add(priorChunks.getValue(id))
                    newVectors.add(priorVectors.getValue(id))
                }
                continue
            }
            changedCount++
            val text = String(file.content, StandardCharsets.UTF_8)
            val produced = Chunking.chunk(rel, text)
            val ids = ArrayList<String>(produced.size)
            val pathHash = sha256(rel.toByteArray(StandardCharsets.UTF_8))
            for ((i, c) in produced.withIndex()) {
                val id = "${pathHash.substring(0, 6)}${hash.substring(0, 6)}:$i"
                ids.add(id)
                val stored = StoredChunk(id, c.filePath, c.startLine, c.endLine, c.language, c.text)
                newChunks.add(stored)
                toEmbed.add(stored)
                newVectors.add(FloatArray(0))
            }
            newFilesIndex[rel] = FileEntry(hash, ids)
        }

        onProgress("Changed $changedCount files, reused $reusedCount, ${toEmbed.size} chunks to embed")
        System.err.println("changed $changedCount, skipped-unchanged $reusedCount, chunks-to-embed ${toEmbed.size}")
        embedAndPersist(config, indexDir, repoLabel, priorManifest, newFilesIndex, newChunks, newVectors, toEmbed, onProgress)
    }

    fun run(config: Config, repoPath: Path, indexDir: Path? = null) {
        val repoRoot = repoPath.toAbsolutePath().normalize()
        require(Files.isDirectory(repoRoot)) { "repo path is not a directory: $repoRoot" }

        val outDir = indexDir?.toAbsolutePath()?.normalize() ?: Store.repoLocalDir(repoRoot)
        val gitignore = Gitignore.load(repoRoot)
        val priorManifest = Store.readManifest(outDir)
        val priorFiles = Store.readFilesIndex(outDir).files
        val priorChunks = Store.readChunks(outDir).associateBy { it.id }
        val priorVectors = run {
            val (dim, vecs) = Store.readVectors(outDir)
            if (dim == 0 || priorChunks.isEmpty() || vecs.size != priorChunks.size) {
                emptyMap()
            } else {
                Store.readChunks(outDir).zip(vecs) { c, v -> c.id to v }.toMap()
            }
        }

        val included = collectFiles(repoRoot, gitignore)
        System.err.println("scanned ${included.scanned}, included ${included.files.size}")

        var changedCount = 0
        var reusedCount = 0
        val newFilesIndex = LinkedHashMap<String, FileEntry>()
        val newChunks = ArrayList<StoredChunk>()
        val newVectors = ArrayList<FloatArray>()

        // Split changed vs reused without embedding yet, so we can batch the API.
        val toEmbed = ArrayList<StoredChunk>() // chunks whose vectors we still need

        for (file in included.files) {
            val rel = repoRoot.relativize(file).toString().replace('\\', '/')
            val bytes = try {
                Files.readAllBytes(file)
            } catch (t: Throwable) {
                System.err.println("skip (read error): $rel — ${t.message}")
                continue
            }
            if (!looksLikeUtf8(bytes)) {
                System.err.println("skip (non-utf8): $rel")
                continue
            }
            val hash = sha256(bytes)
            val prior = priorFiles[rel]
            if (prior != null && prior.contentHash == hash &&
                prior.chunkIds.all { priorChunks.containsKey(it) && priorVectors.containsKey(it) }
            ) {
                reusedCount++
                val entry = FileEntry(hash, prior.chunkIds)
                newFilesIndex[rel] = entry
                for (id in prior.chunkIds) {
                    val stored = priorChunks.getValue(id)
                    newChunks.add(stored)
                    newVectors.add(priorVectors.getValue(id))
                }
                continue
            }
            changedCount++
            val text = String(bytes, StandardCharsets.UTF_8)
            val produced = Chunking.chunk(rel, text)
            val ids = ArrayList<String>(produced.size)
            val pathHash = sha256(rel.toByteArray(StandardCharsets.UTF_8))
            for ((i, c) in produced.withIndex()) {
                val id = "${pathHash.substring(0, 6)}${hash.substring(0, 6)}:$i"
                ids.add(id)
                val stored = StoredChunk(
                    id = id,
                    filePath = c.filePath,
                    startLine = c.startLine,
                    endLine = c.endLine,
                    language = c.language,
                    text = c.text,
                )
                newChunks.add(stored)
                toEmbed.add(stored)
                newVectors.add(FloatArray(0)) // placeholder; filled in after embedding
            }
            newFilesIndex[rel] = FileEntry(hash, ids)
        }

        System.err.println(
            "changed $changedCount, skipped-unchanged $reusedCount, " +
                "chunks-to-embed ${toEmbed.size}"
        )

        embedAndPersist(config, outDir, repoRoot.toString(), priorManifest, newFilesIndex, newChunks, newVectors, toEmbed)
    }

    private fun embedAndPersist(
        config: Config,
        outDir: Path,
        repoLabel: String,
        priorManifest: Manifest?,
        newFilesIndex: Map<String, FileEntry>,
        newChunks: List<StoredChunk>,
        newVectors: MutableList<FloatArray>,
        toEmbed: List<StoredChunk>,
        onProgress: (String) -> Unit = {},
    ) {
        val dim: Int
        if (toEmbed.isNotEmpty()) {
            val totalBatches = (toEmbed.size + Defaults.EMBED_BATCH_SIZE - 1) / Defaults.EMBED_BATCH_SIZE
            onProgress("Embedding ${toEmbed.size} chunks ($totalBatches batches)...")
            val client = config.createEmbeddingClient()
            val embedded = client.embed(toEmbed.map { it.text }, EmbedInputType.DOCUMENT) { batch, total ->
                onProgress("Embedding batch $batch/$total...")
            }
            require(embedded.size == toEmbed.size) { "embedding count mismatch" }
            dim = embedded.first().size
            val idToVec = HashMap<String, FloatArray>(embedded.size)
            for ((i, stored) in toEmbed.withIndex()) idToVec[stored.id] = embedded[i]
            for ((i, stored) in newChunks.withIndex()) {
                val vec = idToVec[stored.id]
                if (vec != null) newVectors[i] = vec
            }
        } else {
            dim = priorManifest?.dim ?: 0
        }

        if (dim == 0 || newChunks.isEmpty()) {
            System.err.println("nothing to index — no chunks produced")
            return
        }

        require(newVectors.all { it.size == dim }) { "vector dimension mismatch after embedding" }

        val now = Instant.now().toString()
        val manifest = Manifest(
            repoPath = repoLabel,
            model = config.anthropicModel,
            embeddingModel = config.embeddingModelName,
            dim = dim,
            createdAt = priorManifest?.createdAt ?: now,
            updatedAt = now,
        )
        onProgress("Writing index...")
        Store.writeManifest(outDir, manifest)
        Store.writeFilesIndex(outDir, FilesIndex(newFilesIndex))
        Store.writeChunks(outDir, newChunks)
        Store.writeVectors(outDir, dim, newVectors)

        onProgress("Done! ${toEmbed.size} chunks embedded, ${newChunks.size} total across ${newFilesIndex.size} files")
        println("embedded ${toEmbed.size} chunk(s); total ${newChunks.size} chunk(s) across ${newFilesIndex.size} file(s)")
        println("index written to $outDir")
    }

    private data class Scan(val scanned: Int, val files: List<Path>)

    private fun collectFiles(repoRoot: Path, gitignore: Gitignore): Scan {
        val out = ArrayList<Path>()
        var scanned = 0
        Files.walkFileTree(repoRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == repoRoot) return FileVisitResult.CONTINUE
                val name = dir.fileName.toString()
                if (name in Defaults.BUILTIN_IGNORES) return FileVisitResult.SKIP_SUBTREE
                val rel = repoRoot.relativize(dir).toString().replace('\\', '/')
                if (gitignore.isIgnored(rel, true)) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                scanned++
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                if (attrs.size() > Defaults.MAX_FILE_BYTES) return FileVisitResult.CONTINUE
                val name = file.fileName.toString()
                if (name in Defaults.BUILTIN_IGNORES) return FileVisitResult.CONTINUE
                val rel = repoRoot.relativize(file).toString().replace('\\', '/')
                if (gitignore.isIgnored(rel, false)) return FileVisitResult.CONTINUE
                if (!isIncluded(name)) return FileVisitResult.CONTINUE
                out.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                System.err.println("skip (walk error): ${repoRoot.relativize(file)} — ${exc.message}")
                return FileVisitResult.CONTINUE
            }
        })
        return Scan(scanned, out)
    }

    fun isIncludedFile(path: String): Boolean {
        val filename = path.substringAfterLast('/')
        return isIncluded(filename)
    }

    private fun isIncluded(filename: String): Boolean {
        val upper = filename.uppercase()
        for (prefix in Defaults.ALWAYS_INCLUDE_NAMES_PREFIX) {
            if (upper.startsWith(prefix)) return true
        }
        val ext = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in Defaults.INCLUDED_EXTENSIONS
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return HexFormat.of().formatHex(digest)
    }

    private fun looksLikeUtf8(bytes: ByteArray): Boolean {
        // Decode with strict error handling; any malformed byte means not utf-8.
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (_: Throwable) {
            false
        }
    }
}
