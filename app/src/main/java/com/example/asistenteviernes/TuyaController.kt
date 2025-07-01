package com.example.asistenteviernes
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8
import org.json.JSONObject

class TuyaController(
    private val accessId: String,
    private val accessSecret: String,
    private val tuyaRegion: String = "us", // us, eu, cn, in
    private val deviceId: String
) {
    var accessToken: String? = null

    private fun getTimestamp(): String =
        System.currentTimeMillis().toString()

    private fun sha256Hex(input: String): String {
        val bytes = input.toByteArray(UTF_8)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: String, data: String): ByteArray {
        val secretKey = SecretKeySpec(key.toByteArray(UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray(UTF_8))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    private fun generateSignature(
        method: String,
        urlPath: String,
        timestamp: String,
        nonce: String,
        accessToken: String? = null,
        body: String = ""
    ): String {
        val contentHash = sha256Hex(body)
        val signUrl = urlPath

        val stringToSign = buildString {
            append(method.uppercase())
            append("\n")
            append(contentHash)
            append("\n")
            append("\n") // Signature-Headers vacío
            append(signUrl)
        }

        val payload = buildString {
            append(accessId)
            if (accessToken != null) append(accessToken)
            append(timestamp)
            append(nonce)
            append(stringToSign)
        }

        val hmac = hmacSha256(accessSecret, payload)
        return bytesToHex(hmac)
    }

    private fun generateHeaders(
        method: String,
        urlPath: String,
        timestamp: String,
        nonce: String,
        body: String = ""
    ): Map<String, String> {
        return mapOf(
            "client_id" to accessId,
            "sign_method" to "HMAC-SHA256",
            "t" to timestamp,
            "nonce" to nonce,
            "sign" to generateSignature(method, urlPath, timestamp, nonce, accessToken, body),
            "Content-Type" to "application/json",
            "access_token" to (accessToken ?: "")
        )
    }

    suspend fun authenticate() = withContext(Dispatchers.IO) {
        val timestamp = getTimestamp()
        val nonce = UUID.randomUUID().toString()

        val method = "GET"
        val urlPath = "/v1.0/token"
        val query = "?grant_type=1"
        val urlString = "https://openapi.tuya$tuyaRegion.com$urlPath$query"
        val signature = generateSignature(method, urlPath + query, timestamp, nonce)

        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("client_id", accessId)
            setRequestProperty("sign", signature)
            setRequestProperty("t", timestamp)
            setRequestProperty("sign_method", "HMAC-SHA256")
            setRequestProperty("nonce", nonce)
            connectTimeout = 10000
            readTimeout = 10000
        }

        connection.connect()

        if (connection.responseCode == 200) {
            val responseText = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseText)
            if (json.getBoolean("success")) {
                accessToken = json.getJSONObject("result").getString("access_token")
                Log.d("TuyaController", "✅ Access token: $accessToken")
            } else {
                throw Exception("Error authenticating: ${json.getString("msg")}")
            }
        } else {
            throw Exception("HTTP error ${connection.responseCode}")
        }
    }

    suspend fun controlarFoco(encender: Boolean) {
        requireNotNull(accessToken) { "No autenticado" }
        val urlPath = "/v1.0/devices/$deviceId/commands"
        val urlString = "https://openapi.tuya$tuyaRegion.com$urlPath"

        val bodyJson = JSONObject().apply {
            put("commands", listOf(JSONObject().apply {
                put("code", "switch_led")
                put("value", encender)
            }))
        }.toString()

        httpPost(urlString, urlPath, bodyJson)
    }

    suspend fun cambiarBrillo(nivel: Int) {
        requireNotNull(accessToken) { "No autenticado" }
        val urlPath = "/v1.0/devices/$deviceId/commands"
        val urlString = "https://openapi.tuya$tuyaRegion.com$urlPath"

        val bodyJson = JSONObject().apply {
            put("commands", listOf(JSONObject().apply {
                put("code", "bright_value_v2")
                put("value", nivel)
            }))
        }.toString()

        httpPost(urlString, urlPath, bodyJson)
    }

    suspend fun cambiarColor(hue: Int, saturation: Int, value: Int) {
        requireNotNull(accessToken) { "No autenticado" }
        val urlPath = "/v1.0/devices/$deviceId/commands"
        val urlString = "https://openapi.tuya$tuyaRegion.com$urlPath"

        val colorData = JSONObject().apply {
            put("h", hue)
            put("s", saturation)
            put("v", value)
        }

        val bodyJson = JSONObject().apply {
            put("commands", listOf(JSONObject().apply {
                put("code", "colour_data_v2")
                put("value", colorData)
            }))
        }.toString()

        httpPost(urlString, urlPath, bodyJson)
    }

    suspend fun activarBlanco(brillo: Int) {
        requireNotNull(accessToken) { "No autenticado" }
        val urlPath = "/v1.0/devices/$deviceId/commands"
        val urlString = "https://openapi.tuya$tuyaRegion.com$urlPath"

        val colorDataApagado = JSONObject().apply {
            put("h", 0)
            put("s", 0)
            put("v", 0)  // Apaga el canal color
        }

        val bodyJson = JSONObject().apply {
            put("commands", listOf(
                JSONObject().apply {
                    put("code", "bright_value_v2")
                    put("value", brillo.coerceIn(10, 1000))
                },
                JSONObject().apply {
                    put("code", "colour_data_v2")
                    put("value", colorDataApagado)
                }
            ))
        }.toString()

        httpPost(urlString, urlPath, bodyJson)
    }

    private suspend fun httpPost(urlString: String, urlPath: String, body: String) =
        withContext(Dispatchers.IO) {
            val timestamp = getTimestamp()
            val nonce = UUID.randomUUID().toString()

            val headers = generateHeaders("POST", urlPath, timestamp, nonce, body)

            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000

                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }

            connection.outputStream.use { os ->
                os.write(body.toByteArray(UTF_8))
            }

            connection.connect()

            val responseCode = connection.responseCode
            val responseText = connection.inputStream.bufferedReader().readText()

            Log.d("TuyaController", "POST $urlPath status: $responseCode")
            Log.d("TuyaController", "Response: $responseText")

            if (responseCode != 200) {
                throw Exception("Error en POST $urlPath: $responseText")
            }
        }
}
