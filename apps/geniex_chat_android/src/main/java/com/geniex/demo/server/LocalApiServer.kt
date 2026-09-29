package com.geniex.demo.server

import com.geniex.demo.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object LocalApiServer {
    private const val MAX_BODY_BYTES = 1024 * 1024
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(4)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var port: Int = 18181
        private set
    @Volatile var lanEnabled: Boolean = false
        private set
    @Volatile var apiKey: String = ""
        private set
    @Volatile var lastError: String? = null
        private set

    fun isRunning(): Boolean = running.get()

    @Synchronized
    fun start(port: Int, lanEnabled: Boolean, apiKey: String): Result<Unit> {
        if (running.get()) stop()
        return runCatching {
            val bindAddress = InetAddress.getByName(if (lanEnabled) "0.0.0.0" else "127.0.0.1")
            val socket = ServerSocket(port, 20, bindAddress)
            this.port = port
            this.lanEnabled = lanEnabled
            this.apiKey = apiKey
            serverSocket = socket
            lastError = null
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
        runCatching { serverSocket?.close() }
        serverSocket = null
        DiagnosticsLogger.log("INFO", "ApiServer", "stopped")
    }

    fun localhostUrl(): String = "http://127.0.0.1:$port"

    fun lanUrl(): String? = if (lanEnabled) localIpv4()?.let { "http://$it:$port" } else null

    fun apiUrl(): String = "${lanUrl() ?: localhostUrl()}/v1"

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                executor.execute { handleClient(client) }
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
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return writeJson(output, 400, errorJson("invalid request"))
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }

                if (!authorized(headers)) return writeJson(output, 401, errorJson("unauthorized"))

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    return writeJson(output, 413, errorJson("request body too large"))
                }
                val body = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
                DiagnosticsLogger.log("INFO", "ApiServer", "$method $path bytes=$contentLength")

                when {
                    method == "GET" && path == "/health" -> writeJson(
                        output,
                        200,
                        buildJsonObject {
                            put("status", JsonPrimitive("ok"))
                            put("model_loaded", JsonPrimitive(InferenceBridge.isLoaded()))
                        },
                    )
                    method == "GET" && path == "/v1/models" -> writeJson(output, 200, modelsJson())
                    method == "POST" && path == "/v1/chat/completions" -> handleChat(output, body)
                    else -> writeJson(output, 404, errorJson("not found"))
                }
            } catch (e: Exception) {
                DiagnosticsLogger.log("ERROR", "ApiServer", "request failed", e)
                runCatching { writeJson(output, 500, errorJson(e.message ?: "internal error")) }
            }
        }
    }

    private fun handleChat(output: BufferedOutputStream, body: ByteArray) {
        if (!InferenceBridge.isLoaded()) return writeJson(output, 503, errorJson("no model loaded"))
        val root = runCatching { json.parseToJsonElement(body.toString(StandardCharsets.UTF_8)).jsonObject }
            .getOrElse { return writeJson(output, 400, errorJson("invalid JSON")) }
        if (root["stream"]?.jsonPrimitive?.booleanOrNull == true) {
            return writeJson(output, 400, errorJson("streaming is not enabled in this Android server build"))
        }
        val messages = runCatching {
            root["messages"]!!.jsonArray.map { entry ->
                val obj = entry.jsonObject
                val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "user"
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                role to content
            }
        }.getOrElse { return writeJson(output, 400, errorJson("messages must be an array of text messages")) }
        if (messages.isEmpty()) return writeJson(output, 400, errorJson("messages cannot be empty"))

        val result = runBlocking { InferenceBridge.generateText(messages) }
        result.fold(
            onSuccess = { response ->
                val id = "chatcmpl-${UUID.randomUUID()}"
                writeJson(
                    output,
                    200,
                    buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("object", JsonPrimitive("chat.completion"))
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
                    },
                )
            },
            onFailure = {
                DiagnosticsLogger.log("ERROR", "ApiServer", "inference failed", it)
                writeJson(output, 500, errorJson(it.message ?: "inference failed"))
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
                            put("owned_by", JsonPrimitive("local"))
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

    private fun errorJson(message: String): JsonObject = buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("message", JsonPrimitive(message))
                put("type", JsonPrimitive("server_error"))
            },
        )
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
        output.write("HTTP/1.1 $code $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
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
}
