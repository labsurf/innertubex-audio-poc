package poc

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val MAX_AUDIO_BYTES = 8 * 1024
private const val MAX_REQUEST_BYTES = 2048
private const val TOTAL_TIMEOUT_MS = 120_000L
private val busy = AtomicBoolean(false)

fun main(args: Array<String>) {
    if (args.firstOrNull() == "serve") startServer() else runCli(args)
}

private fun startServer() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 10000
    require(port in 1..65535) { "PORT must be 1..65535" }
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    server.executor = Executors.newFixedThreadPool(4)
    server.createContext("/health") { exchange ->
        if (exchange.requestMethod == "GET") respond(exchange, 200, "{\"status\":\"ok\"}")
        else respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
    }
    server.createContext("/probe") { exchange -> handleProbe(exchange) }
    server.start()
    println("http_listening=0.0.0.0:$port")
}

private fun handleProbe(exchange: HttpExchange) {
    if (exchange.requestMethod != "POST") {
        respond(exchange, 405, "{\"error\":\"method_not_allowed\"}"); return
    }
    val configured = System.getenv("PROBE_TOKEN")
    val supplied = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
    if (configured.isNullOrBlank()) {
        respond(exchange, 503, "{\"error\":\"probe_not_configured\"}"); return
    }
    if (supplied == null || !MessageDigest.isEqual(configured.toByteArray(), supplied.toByteArray())) {
        respond(exchange, 401, "{\"error\":\"unauthorized\"}"); return
    }
    val body = exchange.requestBody.use { it.readNBytes(MAX_REQUEST_BYTES + 1) }
    if (body.size > MAX_REQUEST_BYTES) {
        respond(exchange, 413, "{\"error\":\"request_too_large\"}"); return
    }
    val videoId = parseVideoId(body.decodeToString().trim())
    if (videoId == null) {
        respond(exchange, 400, "{\"error\":\"invalid_content_id_or_url\"}"); return
    }
    if (!busy.compareAndSet(false, true)) {
        respond(exchange, 429, "{\"error\":\"probe_busy\"}"); return
    }
    val started = System.nanoTime()
    try {
        val result = runBlocking { probe(videoId) }
        val totalMs = elapsedMs(started)
        println("probe result=PASS client=${result.client} transport=${result.transport} durationSeconds=${result.duration} extractionMs=${result.extractionMs} httpStatus=${result.httpStatus} contentType=${safeContentType(result.contentType)} bytesReceived=${result.bytes} totalMs=$totalMs")
        respond(exchange, 200, "{\"result\":\"PASS\",\"client\":\"${jsonSafe(result.client)}\",\"transport\":\"${result.transport}\",\"durationSeconds\":${result.duration ?: "null"},\"extractionMs\":${result.extractionMs},\"httpStatus\":${result.httpStatus},\"contentType\":\"${jsonSafe(safeContentType(result.contentType))}\",\"bytesReceived\":${result.bytes},\"totalMs\":$totalMs}")
    } catch (e: Exception) {
        val label = if (e is StreamResolveException) "stream_resolve_${e.reason.name}" else safeLabel(e)
        val causes = generateSequence(e as Throwable?) { it.cause }
            .take(6)
            .map { it.javaClass.simpleName.filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(60) }
            .joinToString(">")
        val attempts = if (e is StreamResolveException) e.diagnostics?.attempts.orEmpty().take(8).joinToString(",") { attempt ->
            val profile = attempt.profileId?.filter { it.isLetterOrDigit() || it == '_' || it == '-' } ?: "unknown"
            val outcome = attempt.outcome.substringBefore(':').filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            "$profile:$outcome"
        } else ""
        val detail = safeDetail(e.cause?.message ?: e.message)
        println("probe result=FAIL error=$label causes=$causes attempts=$attempts detail=$detail totalMs=${elapsedMs(started)}")
        respond(exchange, 502, "{\"result\":\"FAIL\",\"error\":\"${jsonSafe(label)}\"}")
    } finally { busy.set(false) }
}

private data class ProbeResult(val client: String, val transport: String, val duration: Long?, val extractionMs: Long, val httpStatus: Int, val contentType: String, val bytes: Int)

private suspend fun probe(videoId: String): ProbeResult {
    val http = newClient()
    val innerTube = InnerTube(httpClient = http)
    try {
        val store = RemotePlayerConfigStore(httpClient = http, repository = PlayerConfigRepository.disabled())
        val cipher = YouTubeCipherService(http, store)
        val extractor = InnerTubeExtractor(YtConfigParserImpl(http, innerTube, store, cipherService = cipher), cipher, innerTube)
        val start = System.nanoTime()
        val stream = withTimeout(TOTAL_TIMEOUT_MS) {
            extractor.extract(videoId = videoId, hints = ContentHints(wantVideo = false), audioQuality = AudioQuality.HIGH)
        } ?: error("no_stream")
        val extractionMs = elapsedMs(start)
        val transport = when { stream.sabrBootstrap != null -> "SABR"; stream.mimeType == "application/x-mpegURL" -> "HLS"; else -> "DIRECT" }
        check(transport != "SABR") { "sabr_transport_not_supported_by_probe" }
        val response = if (transport == "HLS") {
            var playlistUrl = stream.audioUrl
            var playlistHeaders = stream.headers
            var media: ReadResponse? = null
            repeat(3) {
                if (media != null) return@repeat
                val playlistResponse = fetchResponse(http, playlistUrl, playlistHeaders, 256 * 1024, false)
                val text = playlistResponse.bytes.decodeToString()
                val ref = text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?: error("hls_playlist_empty")
                val base = URI(playlistUrl)
                val target = base.resolve(ref)
                require(target.scheme.equals("https", true) && !target.host.isNullOrBlank()) { "invalid_hls_url" }
                val targetHeaders = if (target.host.equals(base.host, true)) playlistHeaders else emptyMap()
                if (text.contains("#EXTINF")) {
                    media = fetchResponse(http, target.toASCIIString(), targetHeaders, MAX_AUDIO_BYTES, true)
                    return@repeat
                }
                playlistUrl = target.toASCIIString()
                playlistHeaders = targetHeaders
            }
            media ?: error("hls_media_playlist_not_found")
        } else fetchResponse(http, stream.audioUrl, stream.headers, MAX_AUDIO_BYTES, true)
        check(response.bytes.isNotEmpty()) { "empty_audio_response" }
        return ProbeResult(stream.profileId, transport, stream.mediaMetadata?.durationSeconds, extractionMs, response.status, response.contentType, response.bytes.size)
    } finally { innerTube.close(); http.close() }
}

private data class ReadResponse(val status: Int, val contentType: String, val bytes: ByteArray)
private suspend fun fetchResponse(http: HttpClient, url: String, headers: Map<String, String>, limit: Int, range: Boolean): ReadResponse = http.prepareGet(url) {
    headers.forEach { (k, v) -> if (!k.equals(HttpHeaders.Range, true) && !k.equals(HttpHeaders.AcceptEncoding, true)) header(k, v) }
    header(HttpHeaders.AcceptEncoding, "identity")
    if (range) header(HttpHeaders.Range, "bytes=0-${limit - 1}")
}.execute { response ->
    val status = response.status.value
    check(if (range) status == 200 || status == 206 else status == 200) { "media_http_status_$status" }
    val channel: ByteReadChannel = response.bodyAsChannel()
    val output = ByteArrayOutputStream(minOf(limit, 4096)); val buffer = ByteArray(4096)
    while (output.size() < limit) {
        val n = channel.readAvailable(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (n < 0) break
        if (n > 0) output.write(buffer, 0, n)
    }
    ReadResponse(status, response.headers[HttpHeaders.ContentType].orEmpty(), output.toByteArray())
}

private fun runCli(args: Array<String>) = runBlocking {
    val id = args.singleOrNull()?.let(::parseVideoId) ?: run { println("Uso: innertubex-audio-poc <ID-o-URL>"); exitProcess(2) }
    try {
        val result = probe(id)
        println("client=${result.client}\ntransport=${result.transport}\ndurationSeconds=${result.duration ?: "unknown"}\nextractionMs=${result.extractionMs}\nhttpStatus=${result.httpStatus}\ncontentType=${safeContentType(result.contentType)}\naudioBytesReceived=${result.bytes}\nresult=PASS")
    } catch (e: Exception) {
        val label = if (e is StreamResolveException) "stream_resolve_${e.reason.name}" else safeLabel(e)
        println("result=FAIL\nerror=$label"); exitProcess(1)
    }
}

private fun newClient() = HttpClient(OkHttp) {
    engine {
        config {
            addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code in 300..399) {
                    val requestHost = chain.request().url.host
                    val targetHost = response.header("Location")
                        ?.let { chain.request().url.resolve(it)?.host }
                        ?.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
                        ?.take(100) ?: "unknown"
                    println("upstream_redirect status=${response.code} fromHost=$requestHost toHost=$targetHost")
                }
                response
            }
        }
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 15_000 }
}
private fun parseVideoId(input: String): String? {
    if (input.matches(Regex("[A-Za-z0-9_-]{11}"))) return input
    return Regex("(?:youtu\\.be/|youtube\\.com/(?:watch\\?[^#]*?v=|shorts/|embed/))([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE).find(input)?.groupValues?.get(1)
}
private fun respond(x: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(); x.responseHeaders.set("Content-Type", "application/json; charset=utf-8"); x.responseHeaders.set("Cache-Control", "no-store"); x.sendResponseHeaders(status, bytes.size.toLong()); x.responseBody.use { it.write(bytes) }
}
private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000
private fun safeContentType(v: String) = v.substringBefore(';').filter { it.isLetterOrDigit() || it in "/.+-_" }.take(100)
private fun jsonSafe(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"").filter { it >= ' ' }
private fun safeLabel(e: Exception) = (e.message ?: e::class.simpleName ?: "error").substringBefore(':').filter { it.isLetterOrDigit() || it in "_-" }.take(100)
private fun safeDetail(value: String?): String = value.orEmpty()
    .replace(Regex("https?://[^\\s\\\"']+", RegexOption.IGNORE_CASE), "<url>")
    .replace(Regex("(?i)(authorization|cookie|set-cookie|token|visitor[_-]?data|signature|sig|key)\\s*[:=]\\s*[^,\\s]+"), "\$1=<redacted>")
    .replace(Regex("[A-Za-z0-9_=-]{64,}"), "<redacted>")
    .replace(Regex("\\s+"), " ")
    .replace(Regex("[^A-Za-z0-9 _.,:;()/<>-]"), "")
    .take(180)
