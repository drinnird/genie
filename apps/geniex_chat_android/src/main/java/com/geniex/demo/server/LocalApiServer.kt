package com.geniex.demo.server

import android.content.Context
import android.os.SystemClock
import com.geniex.demo.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object LocalApiServer {
    private const val MAX_BODY_BYTES = 512 * 1024
    private const val DEFAULT_MAX_TOKENS = 2048
    private const val API_WORKER_THREADS = 4
    private val running = AtomicBoolean(false)
    @Volatile private var executor: ExecutorService? = null
    private val apiInferenceBusy = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var cachedWebUi: ByteArray? = null

    @Volatile var port: Int = 18181
        private set
    @Volatile var lanEnabled: Boolean = false
        private set
    @Volatile var apiKey: String = ""
        private set
    @Volatile var lastError: String? = null
        private set

    fun isRunning(): Boolean = running.get()

    /** Release recreatable server-side caches when Android reports memory pressure. */
    fun trimMemory() {
        cachedWebUi = null
        DiagnosticsLogger.log("INFO", "ApiServer", "released web UI cache for memory pressure")
    }

    @Synchronized
    fun start(context: Context, port: Int, lanEnabled: Boolean, apiKey: String): Result<Unit> {
        if (running.get()) stop()
        return runCatching {
            val bindAddress = InetAddress.getByName(if (lanEnabled) "0.0.0.0" else "127.0.0.1")
            val socket = ServerSocket(port, 20, bindAddress)
            this.appContext = context.applicationContext
            this.cachedWebUi = null
            this.port = port
            this.lanEnabled = lanEnabled
            this.apiKey = apiKey
            serverSocket = socket
            lastError = null
            apiInferenceBusy.set(false)
            executor = Executors.newFixedThreadPool(API_WORKER_THREADS) { runnable ->
                Thread(runnable, "GenieX-ApiWorker").apply { isDaemon = true }
            }
            running.set(true)
            Thread({ acceptLoop(socket) }, "GenieX-ApiAccept").apply {
                isDaemon = true
                start()
            }
            DiagnosticsLogger.log("INFO", "ApiServer", "started ${if (lanEnabled) "LAN" else "localhost"}:$port")
        }.onFailure {
            lastError = it.message
            running.set(false)
            DiagnosticsLogger.log("ERROR", "ApiServer", "start failed", it)
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        if (apiInferenceBusy.get()) requestInferenceStop()
        apiInferenceBusy.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        cachedWebUi = null
        appContext = null
        DiagnosticsLogger.log("INFO", "ApiServer", "stopped and released worker pool")
    }

    fun localhostUrl(): String = "http://127.0.0.1:$port"

    fun lanUrl(): String? = if (lanEnabled) localIpv4()?.let { "http://$it:$port" } else null

    fun webUrl(): String = "${lanUrl() ?: localhostUrl()}/"

    fun apiUrl(): String = "${lanUrl() ?: localhostUrl()}/v1"

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                client.tcpNoDelay = true
                val pool = executor
                if (pool == null || pool.isShutdown) {
                    client.close()
                } else {
                    pool.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    lastError = e.message
                    DiagnosticsLogger.log("ERROR", "ApiServer", "accept failed", e)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            // Do not let speculative/abandoned browser sockets occupy a worker for a full minute.
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return writeJson(output, 400, errorJson(400, "invalid request", "invalid_request_error"))
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }

                // The browser shell and health check are intentionally public, like llama-server.
                // Model inference endpoints still require the configured API key in LAN mode.
                when {
                    method == "GET" && path == "/" -> return writeHtml(output, webUiBytes())
                    method == "GET" && (path == "/health" || path == "/v1/health") -> return writeHealth(output)
                    method == "GET" && path == "/favicon.ico" -> return writeEmpty(output, 204)
                }

                if (!authorized(headers)) {
                    return writeJson(output, 401, errorJson(401, "Invalid API Key", "authentication_error"))
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    return writeJson(output, 413, errorJson(413, "request body too large", "invalid_request_error"))
                }
                val body = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
                DiagnosticsLogger.log("INFO", "ApiServer", "$method $path bytes=$contentLength")

                when {
                    method == "GET" && (path == "/v1/models" || path == "/models") -> writeJson(output, 200, modelsJson())
                    method == "POST" && path == "/v1/chat/completions" -> handleChat(output, body)
                    method == "POST" && path == "/v1/completions" -> handleOpenAiCompletion(output, body)
                    method == "POST" && path == "/completion" -> handleLlamaCompletion(output, body)
                    method == "POST" && (path == "/v1/stop" || path == "/stop") -> handleStop(output)
                    else -> writeJson(output, 404, errorJson(404, "not found", "not_found_error"))
                }
            } catch (e: SocketException) {
                if (apiInferenceBusy.get()) requestInferenceStop()
                DiagnosticsLogger.log("INFO", "ApiServer", "client disconnected: ${e.message}")
            } catch (e: Exception) {
                DiagnosticsLogger.log("ERROR", "ApiServer", "request failed", e)
                runCatching { writeJson(output, 500, errorJson(500, e.message ?: "internal error", "server_error")) }
            }
        }
    }

    private fun writeHealth(output: BufferedOutputStream) {
        if (InferenceBridge.isLoaded()) {
            writeJson(
                output,
                200,
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("model", JsonPrimitive(InferenceBridge.activeModelId ?: "loaded-model"))
                    put("compute", JsonPrimitive(InferenceBridge.requestedComputeUnit ?: "unknown"))
                    put("busy", JsonPrimitive(apiInferenceBusy.get() || InferenceBridge.isBusy()))
                },
            )
        } else {
            writeJson(
                output,
                503,
                errorJson(503, "No model loaded", "unavailable_error"),
            )
        }
    }

    private fun handleChat(output: BufferedOutputStream, body: ByteArray) {
        if (!InferenceBridge.isLoaded()) {
            return writeJson(output, 503, errorJson(503, "no model loaded", "unavailable_error"))
        }
        val root = parseJsonObject(body) ?: return writeJson(
            output,
            400,
            errorJson(400, "invalid JSON", "invalid_request_error"),
        )
        val messages = parseMessages(root["messages"]) ?: return writeJson(
            output,
            400,
            errorJson(400, "messages must be an array of text messages", "invalid_request_error"),
        )
        if (messages.isEmpty()) {
            return writeJson(output, 400, errorJson(400, "messages cannot be empty", "invalid_request_error"))
        }

        val stream = root["stream"]?.jsonPrimitive?.booleanOrNull == true
        val maxTokens = requestedMaxTokens(root)
        val enableThinking = requestedThinking(root)
        if (!acquireInferenceSlot(output, "/v1/chat/completions")) return
        try {
            if (stream) {
                handleChatStream(output, messages, maxTokens, enableThinking)
            } else {
                val result = runBlocking { InferenceBridge.generateText(messages, enableThinking, maxTokens) }
                result.fold(
                    onSuccess = { response -> writeJson(output, 200, chatCompletionJson(response)) },
                    onFailure = {
                        DiagnosticsLogger.log("ERROR", "ApiServer", "inference failed", it)
                        writeJson(output, 500, errorJson(500, it.message ?: "inference failed", "server_error"))
                    },
                )
            }
        } finally {
            releaseInferenceSlot("/v1/chat/completions")
        }
    }

    private fun handleChatStream(
        output: BufferedOutputStream,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        enableThinking: Boolean,
    ) {
        val id = "chatcmpl-${UUID.randomUUID()}"
        val model = InferenceBridge.activeModelId ?: "loaded-model"
        val created = System.currentTimeMillis() / 1000L
        writeSseHeaders(output)
        writeSseData(output, chatChunkJson(id, model, created, role = "assistant"))

        val chunks = TokenChunker { text ->
            writeSseData(output, chatChunkJson(id, model, created, content = text))
        }
        val result = runBlocking {
            InferenceBridge.streamText(messages, enableThinking, maxTokens) { token ->
                chunks.append(token)
            }
        }
        result.fold(
            onSuccess = {
                chunks.flush()
                writeSseData(output, chatChunkJson(id, model, created, finishReason = "stop"))
                writeSseDone(output)
            },
            onFailure = {
                if (it is SocketException) {
                    DiagnosticsLogger.log("INFO", "ApiServer", "stream client disconnected: ${it.message}")
                } else {
                    DiagnosticsLogger.log("ERROR", "ApiServer", "streaming inference failed", it)
                }
                runCatching {
                    writeSseData(output, errorJson(500, it.message ?: "inference failed", "server_error"))
                    writeSseDone(output)
                }
            },
        )
    }

    private fun handleOpenAiCompletion(output: BufferedOutputStream, body: ByteArray) {
        if (!InferenceBridge.isLoaded()) {
            return writeJson(output, 503, errorJson(503, "no model loaded", "unavailable_error"))
        }
        val root = parseJsonObject(body) ?: return writeJson(
            output,
            400,
            errorJson(400, "invalid JSON", "invalid_request_error"),
        )
        val prompt = root["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return writeJson(output, 400, errorJson(400, "prompt must be a string", "invalid_request_error"))
        val stream = root["stream"]?.jsonPrimitive?.booleanOrNull == true
        val maxTokens = requestedMaxTokens(root)
        val id = "cmpl-${UUID.randomUUID()}"
        val model = InferenceBridge.activeModelId ?: "loaded-model"
        val created = System.currentTimeMillis() / 1000L

        if (!acquireInferenceSlot(output, "/v1/completions")) return
        try {
        if (stream) {
            writeSseHeaders(output)
            val chunks = TokenChunker { text ->
                writeSseData(output, completionChunkJson(id, model, created, text))
            }
            val result = runBlocking {
                InferenceBridge.streamPrompt(prompt, maxTokens) { token -> chunks.append(token) }
            }
            result.fold(
                onSuccess = {
                    chunks.flush()
                    writeSseData(output, completionChunkJson(id, model, created, "", finishReason = "stop"))
                    writeSseDone(output)
                },
                onFailure = {
                    runCatching {
                        writeSseData(output, errorJson(500, it.message ?: "inference failed", "server_error"))
                        writeSseDone(output)
                    }
                },
            )
        } else {
            val result = runBlocking { InferenceBridge.generatePrompt(prompt, maxTokens) }
            result.fold(
                onSuccess = { text ->
                    writeJson(
                        output,
                        200,
                        buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put("object", JsonPrimitive("text_completion"))
                            put("created", JsonPrimitive(created))
                            put("model", JsonPrimitive(model))
                            put(
                                "choices",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("text", JsonPrimitive(text))
                                            put("index", JsonPrimitive(0))
                                            put("finish_reason", JsonPrimitive("stop"))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
                onFailure = { writeJson(output, 500, errorJson(500, it.message ?: "inference failed", "server_error")) },
            )
        }
        } finally {
            releaseInferenceSlot("/v1/completions")
        }
    }

    private fun handleLlamaCompletion(output: BufferedOutputStream, body: ByteArray) {
        if (!InferenceBridge.isLoaded()) {
            return writeJson(output, 503, errorJson(503, "no model loaded", "unavailable_error"))
        }
        val root = parseJsonObject(body) ?: return writeJson(
            output,
            400,
            errorJson(400, "invalid JSON", "invalid_request_error"),
        )
        val prompt = root["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return writeJson(output, 400, errorJson(400, "prompt must be a string", "invalid_request_error"))
        val maxTokens = root["n_predict"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: requestedMaxTokens(root)
        if (!acquireInferenceSlot(output, "/completion")) return
        try {
        val result = runBlocking { InferenceBridge.generatePrompt(prompt, maxTokens) }
        result.fold(
            onSuccess = { text ->
                writeJson(
                    output,
                    200,
                    buildJsonObject {
                        put("content", JsonPrimitive(text))
                        put("stop", JsonPrimitive(true))
                        put("model", JsonPrimitive(InferenceBridge.activeModelId ?: "loaded-model"))
                    },
                )
            },
            onFailure = { writeJson(output, 500, errorJson(500, it.message ?: "inference failed", "server_error")) },
        )
        } finally {
            releaseInferenceSlot("/completion")
        }
    }

    private fun handleStop(output: BufferedOutputStream) {
        val wasBusy = apiInferenceBusy.get() || InferenceBridge.isBusy()
        if (wasBusy) requestInferenceStop()
        writeJson(
            output,
            200,
            buildJsonObject {
                put("stopping", JsonPrimitive(wasBusy))
                put("status", JsonPrimitive(if (wasBusy) "stop_requested" else "idle"))
            },
        )
    }

    private fun acquireInferenceSlot(output: BufferedOutputStream, endpoint: String): Boolean {
        if (!apiInferenceBusy.compareAndSet(false, true)) {
            DiagnosticsLogger.log("INFO", "ApiServer", "busy request rejected endpoint=$endpoint")
            writeJson(output, 429, errorJson(429, "Model is busy with another generation", "busy_error"))
            return false
        }
        if (InferenceBridge.isBusy()) {
            apiInferenceBusy.set(false)
            DiagnosticsLogger.log("INFO", "ApiServer", "native UI is using model; request rejected endpoint=$endpoint")
            writeJson(output, 429, errorJson(429, "Model is busy with another generation", "busy_error"))
            return false
        }
        DiagnosticsLogger.log("INFO", "ApiServer", "inference slot acquired endpoint=$endpoint")
        return true
    }

    private fun releaseInferenceSlot(endpoint: String) {
        apiInferenceBusy.set(false)
        DiagnosticsLogger.log("INFO", "ApiServer", "inference slot released endpoint=$endpoint")
    }

    private fun requestInferenceStop() {
        Thread(
            {
                runCatching { runBlocking { InferenceBridge.stopActiveStream() } }
                    .onFailure { DiagnosticsLogger.log("ERROR", "ApiServer", "stop request failed", it) }
            },
            "GenieX-ApiStop",
        ).apply { isDaemon = true }.start()
    }

    private fun parseJsonObject(body: ByteArray): JsonObject? = runCatching {
        json.parseToJsonElement(body.toString(StandardCharsets.UTF_8)).jsonObject
    }.getOrNull()

    private fun parseMessages(element: JsonElement?): List<Pair<String, String>>? = runCatching {
        element!!.jsonArray.map { entry ->
            val obj = entry.jsonObject
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "user"
            val content = extractTextContent(obj["content"])
            role to content
        }
    }.getOrNull()

    private fun extractTextContent(element: JsonElement?): String {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonArray -> element.mapNotNull { part ->
                runCatching {
                    val obj = part.jsonObject
                    if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                        obj["text"]?.jsonPrimitive?.contentOrNull
                    } else {
                        null
                    }
                }.getOrNull()
            }.joinToString("\n")
            else -> ""
        }
    }

    private fun requestedMaxTokens(root: JsonObject): Int =
        root["max_completion_tokens"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            ?: root["max_tokens"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            ?: DEFAULT_MAX_TOKENS

    private fun requestedThinking(root: JsonObject): Boolean {
        val direct = root["enable_thinking"]?.jsonPrimitive?.booleanOrNull
        if (direct != null) return direct
        return runCatching {
            root["chat_template_kwargs"]?.jsonObject
                ?.get("enable_thinking")?.jsonPrimitive?.booleanOrNull
        }.getOrNull() ?: false
    }

    private fun chatCompletionJson(response: String): JsonObject = buildJsonObject {
        put("id", JsonPrimitive("chatcmpl-${UUID.randomUUID()}"))
        put("object", JsonPrimitive("chat.completion"))
        put("created", JsonPrimitive(System.currentTimeMillis() / 1000L))
        put("model", JsonPrimitive(InferenceBridge.activeModelId ?: "loaded-model"))
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", JsonPrimitive(0))
                        put(
                            "message",
                            buildJsonObject {
                                put("role", JsonPrimitive("assistant"))
                                put("content", JsonPrimitive(response))
                            },
                        )
                        put("finish_reason", JsonPrimitive("stop"))
                    },
                )
            },
        )
    }

    private fun chatChunkJson(
        id: String,
        model: String,
        created: Long,
        role: String? = null,
        content: String? = null,
        finishReason: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("object", JsonPrimitive("chat.completion.chunk"))
        put("created", JsonPrimitive(created))
        put("model", JsonPrimitive(model))
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", JsonPrimitive(0))
                        put(
                            "delta",
                            buildJsonObject {
                                role?.let { put("role", JsonPrimitive(it)) }
                                content?.let { put("content", JsonPrimitive(it)) }
                            },
                        )
                        put("finish_reason", if (finishReason != null) JsonPrimitive(finishReason) else JsonNull)
                    },
                )
            },
        )
    }

    private fun completionChunkJson(
        id: String,
        model: String,
        created: Long,
        text: String,
        finishReason: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("object", JsonPrimitive("text_completion"))
        put("created", JsonPrimitive(created))
        put("model", JsonPrimitive(model))
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("text", JsonPrimitive(text))
                        put("index", JsonPrimitive(0))
                        put("finish_reason", if (finishReason != null) JsonPrimitive(finishReason) else JsonNull)
                    },
                )
            },
        )
    }

    private fun modelsJson(): JsonObject = buildJsonObject {
        put("object", JsonPrimitive("list"))
        put(
            "data",
            JsonArray(
                InferenceBridge.activeModelId?.let { id ->
                    listOf(
                        buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put("object", JsonPrimitive("model"))
                            put("owned_by", JsonPrimitive("geniex-local"))
                            put("name", JsonPrimitive(InferenceBridge.activeModelName ?: id))
                            put("compute", JsonPrimitive(InferenceBridge.requestedComputeUnit ?: "unknown"))
                            put("busy", JsonPrimitive(apiInferenceBusy.get() || InferenceBridge.isBusy()))
                        },
                    )
                } ?: emptyList(),
            ),
        )
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        if (apiKey.isBlank()) return !lanEnabled
        return headers["authorization"] == "Bearer $apiKey"
    }

    private fun errorJson(code: Int, message: String, type: String): JsonObject = buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
                put("type", JsonPrimitive(type))
            },
        )
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        writeResponse(output, code, "application/json; charset=utf-8", bytes)
    }

    private fun writeHtml(output: BufferedOutputStream, bytes: ByteArray) {
        writeResponse(
            output,
            200,
            "text/html; charset=utf-8",
            bytes,
            extraHeaders = listOf(
                "Cache-Control: no-store",
                "X-Content-Type-Options: nosniff",
                "X-Frame-Options: DENY",
                "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'",
                "Referrer-Policy: no-referrer",
            ),
        )
    }

    private fun writeEmpty(output: BufferedOutputStream, code: Int) {
        writeResponse(output, code, "text/plain", ByteArray(0))
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<String> = emptyList(),
    ) {
        output.write("HTTP/1.1 $code ${reasonPhrase(code)}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: $contentType\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Length: ${body.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
        extraHeaders.forEach { header ->
            output.write("$header\r\n".toByteArray(StandardCharsets.US_ASCII))
        }
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun writeSseHeaders(output: BufferedOutputStream) {
        output.write("HTTP/1.1 200 OK\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: text/event-stream; charset=utf-8\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Cache-Control: no-cache, no-transform\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("X-Accel-Buffering: no\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun writeSseData(output: BufferedOutputStream, body: JsonElement) {
        val payload = "data: ${body}\n\n".toByteArray(StandardCharsets.UTF_8)
        output.write(payload)
        output.flush()
    }

    private fun writeSseDone(output: BufferedOutputStream) {
        output.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun webUiBytes(): ByteArray {
        cachedWebUi?.let { return it }
        val context = appContext ?: return FALLBACK_WEB_UI.toByteArray(StandardCharsets.UTF_8)
        return runCatching {
            context.assets.open("web/index.html").use { it.readBytes() }
        }.getOrElse {
            DiagnosticsLogger.log("ERROR", "ApiServer", "web UI asset load failed", it)
            FALLBACK_WEB_UI.toByteArray(StandardCharsets.UTF_8)
        }.also { cachedWebUi = it }
    }

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
            if (out.length > 8192) throw IllegalArgumentException("HTTP header line too long")
        }
        return out.toString()
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw IllegalArgumentException("unexpected end of request body")
            offset += read
        }
        return bytes
    }

    private fun localIpv4(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return@runCatching address.hostAddress
                }
            }
        }
        null
    }.getOrNull()

    private class TokenChunker(private val emit: (String) -> Unit) {
        private val buffer = StringBuilder(SSE_FLUSH_CHARS * 2)
        private var lastFlushMs = SystemClock.elapsedRealtime()

        fun append(token: String) {
            buffer.append(token)
            val now = SystemClock.elapsedRealtime()
            if (buffer.length >= SSE_FLUSH_CHARS || now - lastFlushMs >= SSE_FLUSH_MS) flush(now)
        }

        fun flush() = flush(SystemClock.elapsedRealtime())

        private fun flush(now: Long) {
            if (buffer.isEmpty()) return
            val text = buffer.toString()
            buffer.setLength(0)
            lastFlushMs = now
            emit(text)
        }
    }

    private const val SSE_FLUSH_CHARS = 96
    private const val SSE_FLUSH_MS = 50L

    private const val FALLBACK_WEB_UI = """<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>GenieX Local</title></head><body><h1>GenieX Local</h1><p>The bundled web chat UI could not be loaded. The API remains available at <code>/v1</code>.</p></body></html>"""
}
