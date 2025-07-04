package com.example.asistenteviernes

import android.app.*
import android.content.Intent
import android.hardware.*
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class ShakeService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var lastShakeTime = 0L

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // Instancia tu controlador Tuya
    private lateinit var tuyaController: TuyaController

    val coloresHue = mapOf(
        "rojo" to 0,
        "naranja" to 30,
        "amarillo" to 60,
        "verde" to 120,
        "cian" to 180,
        "azul" to 240,
        "violeta" to 270,
        "rosado" to 320
    )

    override fun onCreate() {
        super.onCreate()

        tuyaController = TuyaController(
            accessId = "TUIDAQUI",
            accessSecret = "SECRETCLAVEAQUI",
            tuyaRegion = "us",
            deviceId = "TUIDDEVICEAQUI"
        )

        // Inicializar TTS
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "ES")
                ttsInitialized = true
            }
        }

        // Inicializar sensor
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)

        // Inicializar SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                stopListening()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val comando = matches[0].lowercase(Locale.getDefault())

                    CoroutineScope(Dispatchers.Main).launch {
                        // Autenticación Tuya si necesario
                        if (tuyaController.accessToken == null) {
                            try {
                                withContext(Dispatchers.IO) {
                                    tuyaController.authenticate()
                                }
                            } catch (e: Exception) {
                                tts?.speak("Error autenticando con Tuya", TextToSpeech.QUEUE_FLUSH, null, null)
                                return@launch
                            }
                        }

                        // Llamar a Dialogflow
                        val respuesta = enviarTextoADialogflow(applicationContext, comando)

                        // Añadir hora si la respuesta la menciona
                        val respuestaConHora = if (respuesta?.contains("hora actual es") == true) {
                            val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                            "$respuesta $hora"
                        } else {
                            respuesta ?: "No entendí la orden"
                        }

                        // Hablar respuesta
                        tts?.speak(respuestaConHora, TextToSpeech.QUEUE_FLUSH, null, null)

                        // Controlar foco según respuesta
                        respuesta?.let { textoRespuesta ->
                            withContext(Dispatchers.IO) {
                                try {
                                    when {
                                        textoRespuesta.contains("foco activado", ignoreCase = true) -> {
                                            tuyaController.controlarFoco(encender = true)
                                        }
                                        textoRespuesta.contains("foco desactivado", ignoreCase = true) -> {
                                            tuyaController.controlarFoco(encender = false)
                                        }
                                        textoRespuesta.contains("brillo", ignoreCase = true) || textoRespuesta.contains("intensidad", ignoreCase = true) -> {
                                            val porcentaje = extraerNumero(textoRespuesta)?.coerceIn(10, 100) ?: 100
                                            val nivel = porcentaje * 10
                                            tuyaController.cambiarBrillo(nivel)
                                        }
                                        textoRespuesta.contains("color", ignoreCase = true) -> {
                                            if (textoRespuesta.contains("blanco", ignoreCase = true)) {
                                                val porcentaje  = extraerNumero(textoRespuesta)?.coerceIn(10, 100) ?: 100
                                                val nivelBrillo = porcentaje * 10
                                                tuyaController.activarBlanco(nivelBrillo)
                                            } else {
                                                val color = coloresHue.entries.find { textoRespuesta.contains(it.key, ignoreCase = true) }
                                                color?.let {
                                                    tuyaController.cambiarColor(hue = it.value, saturation = 1000, value = 1000)
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    tts?.speak("Error controlando el foco", TextToSpeech.QUEUE_FLUSH, null, null)
                                }
                            }
                        }
                    }
                }
                stopListening()
            }
        })

        iniciarForeground()
    }

    private fun extraerNumero(texto: String): Int? {
        val regex = "\\d+".toRegex()
        return regex.find(texto)?.value?.toIntOrNull()
    }

    private fun iniciarForeground() {
        val channelId = "asistente_viernes"
        val channelName = "Asistente Viernes"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Asistente activo")
            .setContentText("Detectando movimiento...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce > 3.5) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > 1500 && !isListening) {
                lastShakeTime = now

                if (ttsInitialized) {
                    tts?.speak("Esperando su orden señor", TextToSpeech.QUEUE_FLUSH, null, null)
                    Handler(Looper.getMainLooper()).postDelayed({
                        startListening()
                    }, 1500)
                }
            }
        }
    }

    private fun startListening() {
        if (isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "ES"))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        speechRecognizer?.startListening(intent)
        isListening = true
    }

    private fun stopListening() {
        if (!isListening) return
        speechRecognizer?.stopListening()
        isListening = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
