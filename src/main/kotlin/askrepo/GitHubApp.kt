package askrepo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

object GitHubApp {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Instant = Instant.MIN

    fun getInstallationToken(appId: String, installationId: String, privateKeyPem: String): String {
        val now = Instant.now()
        cachedToken?.let { token ->
            if (now.isBefore(tokenExpiresAt.minusSeconds(60))) return token
        }

        val jwt = createJwt(appId, privateKeyPem)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/app/installations/$installationId/access_tokens"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $jwt")
            .header("Accept", "application/vnd.github+json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) {
            error("GitHub App token request failed (${res.statusCode()}): ${res.body().take(300)}")
        }

        val resp = json.decodeFromString(TokenResponse.serializer(), res.body())
        cachedToken = resp.token
        tokenExpiresAt = Instant.parse(resp.expiresAt)
        return resp.token
    }

    private fun createJwt(appId: String, privateKeyPem: String): String {
        val now = Instant.now()
        val header = base64url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = base64url(
            """{"iss":"$appId","iat":${now.epochSecond - 60},"exp":${now.epochSecond + 600}}""".toByteArray()
        )
        val signingInput = "$header.$payload"

        val key = loadPrivateKey(privateKeyPem)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(key)
        sig.update(signingInput.toByteArray())

        return "$signingInput.${base64url(sig.sign())}"
    }

    private fun loadPrivateKey(pem: String): PrivateKey {
        val keyFactory = KeyFactory.getInstance("RSA")

        val isPkcs1 = pem.contains("BEGIN RSA PRIVATE KEY")
        val marker = if (isPkcs1) "RSA PRIVATE KEY" else "PRIVATE KEY"
        val base64 = pem
            .replace("-----BEGIN $marker-----", "")
            .replace("-----END $marker-----", "")
            .replace("\\s".toRegex(), "")
        val derBytes = Base64.getDecoder().decode(base64)

        val pkcs8Bytes = if (isPkcs1) wrapPkcs1InPkcs8(derBytes) else derBytes
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))
    }

    private fun wrapPkcs1InPkcs8(pkcs1: ByteArray): ByteArray {
        val algorithmId = byteArrayOf(
            0x30, 0x0d,
            0x06, 0x09,
            0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00,
        )
        val version = byteArrayOf(0x02, 0x01, 0x00)
        return derWrap(0x30, version + algorithmId + derWrap(0x04, pkcs1))
    }

    private fun derWrap(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val header = when {
            len < 128 -> byteArrayOf(tag.toByte(), len.toByte())
            len < 256 -> byteArrayOf(tag.toByte(), 0x81.toByte(), len.toByte())
            len < 65536 -> byteArrayOf(
                tag.toByte(), 0x82.toByte(), (len shr 8).toByte(), (len and 0xff).toByte()
            )
            else -> byteArrayOf(
                tag.toByte(), 0x83.toByte(),
                (len shr 16).toByte(), ((len shr 8) and 0xff).toByte(), (len and 0xff).toByte()
            )
        }
        return header + content
    }

    private fun base64url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun resolvePrivateKey(value: String): String {
        val resolved = value.replace("\\n", "\n")
        if (resolved.contains("BEGIN") && resolved.contains("PRIVATE KEY")) return resolved
        if (Files.isRegularFile(Path.of(value))) return Files.readString(Path.of(value))
        try {
            val decoded = String(Base64.getDecoder().decode(value.trim()))
            if (decoded.contains("BEGIN") && decoded.contains("PRIVATE KEY")) return decoded
        } catch (_: Exception) { }
        error("GITHUB_APP_PRIVATE_KEY must be a PEM string, base64-encoded PEM, or path to a PEM file")
    }

    @Serializable
    private data class TokenResponse(
        val token: String,
        @SerialName("expires_at") val expiresAt: String,
    )
}
