package ai.openclaw.app.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** LAN-only KittenTTS client for Dave's AI PC fast speech service. */
internal class KittenFastTtsClient(
  private val endpoint: String = "http://192.168.1.152:8020/tts",
  private val json: Json = Json { ignoreUnknownKeys = true },
  private val client: OkHttpClient =
    OkHttpClient
      .Builder()
      .connectTimeout(2, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .writeTimeout(4, TimeUnit.SECONDS)
      .build(),
) : TalkSpeechSynthesizing {
  override suspend fun synthesize(
    text: String,
    directive: TalkDirective?,
  ): TalkSpeakResult =
    withContext(Dispatchers.IO) {
      val requestPayload =
        KittenFastTtsRequest(
          text = text,
          voice = resolveVoice(directive),
          speed = resolveSpeed(directive),
        )
      val body = json.encodeToString(requestPayload).toRequestBody(jsonMediaType)
      val request =
        Request
          .Builder()
          .url(endpoint)
          .post(body)
          .build()
      try {
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            return@withContext TalkSpeakResult.FallbackToLocal("KittenTTS HTTP ${response.code}")
          }
          val bytes = response.body.bytes()
          if (bytes.isEmpty()) {
            return@withContext TalkSpeakResult.FallbackToLocal("KittenTTS returned empty audio")
          }
          TalkSpeakResult.Success(
            TalkSpeakAudio(
              bytes = bytes,
              provider = "kitten-fast-tts",
              outputFormat = "wav",
              voiceCompatible = true,
              mimeType = response.header("Content-Type") ?: "audio/wav",
              fileExtension = ".wav",
            ),
          )
        }
      } catch (err: Throwable) {
        TalkSpeakResult.FallbackToLocal(err.message ?: "KittenTTS unavailable")
      }
    }

  private fun resolveVoice(directive: TalkDirective?): String {
    val requested = directive?.voiceId?.trim()
    return if (!requested.isNullOrBlank() && requested in supportedVoices) requested else defaultVoice
  }

  private fun resolveSpeed(directive: TalkDirective?): Double {
    val direct = directive?.speed?.takeIf { it in 0.5..2.0 }
    if (direct != null) return direct
    val wpm = directive?.rateWpm?.takeIf { it in 80..260 } ?: return defaultSpeed
    return (wpm.toDouble() / 175.0).coerceIn(0.5, 2.0)
  }

  private companion object {
    private const val defaultVoice = "Luna"
    private const val defaultSpeed = 1.45
    private val supportedVoices = setOf("Bella", "Jasper", "Luna", "Bruno", "Rosie", "Hugo", "Kiki", "Leo")
    private val jsonMediaType = "application/json".toMediaType()
  }
}

@Serializable
private data class KittenFastTtsRequest(
  val text: String,
  val voice: String,
  val speed: Double,
  val clean_text: Boolean = true,
)
