package com.example.asistenteviernes
import android.content.Context
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

suspend fun enviarTextoADialogflow(context: Context, mensaje: String): String? = withContext(Dispatchers.IO) {
    try {
        val inputStream = context.assets.open("dialogflow-key.json").buffered()
        val keyJson = JSONObject(inputStream.reader().readText())
        val projectId = keyJson.getString("project_id")

        val inputStream2 = context.assets.open("dialogflow-key.json")
        val credentials = GoogleCredentials
            .fromStream(inputStream2)
            .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
        credentials.refreshIfExpired()
        val token = credentials.accessToken.tokenValue

        val sessionId = UUID.randomUUID().toString()

        val url = URL("https://dialogflow.googleapis.com/v2/projects/$projectId/agent/sessions/$sessionId:detectIntent")
        val json = """
            {
              "queryInput": {
                "text": {
                  "text": "$mensaje",
                  "languageCode": "es"
                }
              }
            }
        """.trimIndent()

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.doOutput = true
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

        val response = conn.inputStream.bufferedReader().use { it.readText() }

        val fulfillment = JSONObject(response)
            .getJSONObject("queryResult")
            .optString("fulfillmentText", "No entendí la orden")

        return@withContext fulfillment
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext "Error al contactar con Dialogflow"
    }
}
