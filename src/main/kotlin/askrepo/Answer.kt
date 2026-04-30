package askrepo

import java.nio.file.Path

data class AnswerResult(
    val text: String,
    val sources: List<String>,
)

object Answer {

    const val SYSTEM_PROMPT = """You answer questions about a codebase using ONLY the provided context chunks.
Your audience includes product managers, designers, and non-engineers — write
in plain language. Avoid jargon, raw code, and file paths in your answer body.

Rules:
- If the context does not contain enough information to answer, say
  "I couldn't find this in the indexed repository." and stop.
- Explain what the code does in terms of user-facing behavior and business
  logic, not implementation details — unless the question is explicitly
  technical.
- Do NOT cite file paths or line numbers inline. Sources are shown separately.
- Use short paragraphs and bullet points for readability.
- When code and documentation disagree, trust the code and note the
  discrepancy.
- Be concise but clear. Prefer "what it does" over "how it's coded"."""

    fun answer(config: Config, indexDir: Path, question: String): AnswerResult {
        if (!Store.exists(indexDir)) {
            return AnswerResult("No index found at $indexDir. Run `ingest` first.", emptyList())
        }

        val manifest = Store.readManifest(indexDir)
            ?: return AnswerResult("Corrupt index — missing manifest.", emptyList())
        val chunks = Store.readChunks(indexDir)
        val (dim, vectors) = Store.readVectors(indexDir)
        require(dim == manifest.dim) { "manifest dim ${manifest.dim} != vectors.bin dim $dim" }
        require(vectors.size == chunks.size) { "vectors/chunks length mismatch" }

        val embedClient = config.createEmbeddingClient()
        val queryVec = embedClient.embed(listOf(question), EmbedInputType.QUERY).first()
        require(queryVec.size == dim) { "query vector dim ${queryVec.size} != index dim $dim" }

        val hits = Retrieve.topKHybrid(question, queryVec, chunks, vectors, config.topK)
        if (hits.isEmpty()) {
            return AnswerResult("I couldn't find this in the indexed repository.", emptyList())
        }

        val userContent = buildUserContent(question, hits)
        val anthropic = config.createLlmClient()
        val text = anthropic.message(SYSTEM_PROMPT, userContent, config.maxTokens)
        val sources = hits.map { it.chunk.filePath }.distinct()
        return AnswerResult(text.trim(), sources)
    }

    fun answerStreaming(config: Config, indexDir: Path, question: String, onChunk: (text: String, sources: List<String>) -> Unit): AnswerResult {
        if (!Store.exists(indexDir)) {
            return AnswerResult("No index found at $indexDir. Run `ingest` first.", emptyList())
        }
        val manifest = Store.readManifest(indexDir)
            ?: return AnswerResult("Corrupt index — missing manifest.", emptyList())
        val chunks = Store.readChunks(indexDir)
        val (dim, vectors) = Store.readVectors(indexDir)
        require(dim == manifest.dim) { "manifest dim ${manifest.dim} != vectors.bin dim $dim" }
        require(vectors.size == chunks.size) { "vectors/chunks length mismatch" }

        val embedClient = config.createEmbeddingClient()
        val queryVec = embedClient.embed(listOf(question), EmbedInputType.QUERY).first()
        require(queryVec.size == dim) { "query vector dim ${queryVec.size} != index dim $dim" }

        val hits = Retrieve.topKHybrid(question, queryVec, chunks, vectors, config.topK)
        if (hits.isEmpty()) {
            return AnswerResult("I couldn't find this in the indexed repository.", emptyList())
        }

        val sources = hits.map { it.chunk.filePath }.distinct()
        val userContent = buildUserContent(question, hits)
        val anthropic = config.createLlmClient()
        val text = anthropic.messageStreaming(SYSTEM_PROMPT, userContent, config.maxTokens) { partial ->
            onChunk(partial, sources)
        }
        return AnswerResult(text.trim(), sources)
    }

    fun run(config: Config, indexDir: Path, question: String) {
        if (!Store.exists(indexDir)) {
            System.err.println(
                "error: no index found at $indexDir. " +
                    "Run `ask-the-repo ingest` first."
            )
            kotlin.system.exitProcess(3)
        }
        val result = answer(config, indexDir, question)
        println(result.text)
        if (result.sources.isNotEmpty()) {
            println()
            println("Sources:")
            result.sources.forEach { println("  $it") }
        }
    }

    private fun buildUserContent(question: String, hits: List<Retrieve.Hit>): String {
        val sb = StringBuilder()
        sb.append("Question: ").append(question).append("\n\n")
        sb.append("Context chunks:\n\n")
        for (hit in hits) {
            val c = hit.chunk
            sb.append("--- ${c.filePath}:${c.startLine}-${c.endLine} ---\n")
            sb.append(c.text)
            if (!c.text.endsWith("\n")) sb.append("\n")
            sb.append("\n")
        }
        return sb.toString()
    }
}
