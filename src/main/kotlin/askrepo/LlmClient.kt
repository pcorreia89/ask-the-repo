package askrepo

interface LlmClient {
    fun message(systemPrompt: String, userContent: String, maxTokens: Int): String
    fun messageStreaming(systemPrompt: String, userContent: String, maxTokens: Int, onChunk: (String) -> Unit): String
}
