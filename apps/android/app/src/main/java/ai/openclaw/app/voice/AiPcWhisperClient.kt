package ai.openclaw.app.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** LAN-only batch STT client for Dave's AI PC faster-whisper service. */
internal class AiPcWhisperClient(
  private val endpoint: String = "http://192.168.1.152:8765/transcribe",
  private val json: Json = Json { ignoreUnknownKeys = true },
  private val client: OkHttpClient =
    OkHttpClient
      .Builder()
      .connectTimeout(2, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .build(),
) {
  suspend fun transcribePcm16(
    pcm16: ByteArray,
    sampleRateHz: Int,
  ): String =
    withContext(Dispatchers.IO) {
      val wav = buildPcm16Wav(pcm16 = pcm16, sampleRateHz = sampleRateHz)
      val body =
        MultipartBody
          .Builder()
          .setType(MultipartBody.FORM)
          .addFormDataPart(
            "file",
            "talk.wav",
            wav.toRequestBody("audio/wav".toMediaType()),
          ).addFormDataPart("language", "en")
          .addFormDataPart("vad_filter", "true")
          .build()
      val request =
        Request
          .Builder()
          .url(endpoint)
          .post(body)
          .build()
      client.newCall(request).execute().use { response ->
        val raw = response.body.string()
        val payload =
          runCatching { json.decodeFromString<AiPcWhisperResponse>(raw) }.getOrNull()
        if (!response.isSuccessful || payload?.ok == false) {
          throw IllegalStateException(payload?.detail ?: "AI PC Whisper HTTP ${response.code}")
        }
        payload?.text?.trim().orEmpty()
      }
    }
}

@Serializable
private data class AiPcWhisperResponse(
  val ok: Boolean = true,
  val text: String? = null,
  val detail: String? = null,
)

private fun buildPcm16Wav(
  pcm16: ByteArray,
  sampleRateHz: Int,
): ByteArray {
  val dataSize = pcm16.size
  val wav = ByteArray(44 + dataSize)
  fun writeAscii(
    offset: Int,
    text: String,
  ) {
    text.encodeToByteArray().copyInto(wav, offset)
  }
  fun writeShortLe(
    offset: Int,
    value: Int,
  ) {
    wav[offset] = (value and 0xff).toByte()
    wav[offset + 1] = ((value shr 8) and 0xff).toByte()
  }
  fun writeIntLe(
    offset: Int,
    value: Int,
  ) {
    wav[offset] = (value and 0xff).toByte()
    wav[offset + 1] = ((value shr 8) and 0xff).toByte()
    wav[offset + 2] = ((value shr 16) and 0xff).toByte()
    wav[offset + 3] = ((value shr 24) and 0xff).toByte()
  }

  writeAscii(0, "RIFF")
  writeIntLe(4, 36 + dataSize)
  writeAscii(8, "WAVE")
  writeAscii(12, "fmt ")
  writeIntLe(16, 16)
  writeShortLe(20, 1)
  writeShortLe(22, 1)
  writeIntLe(24, sampleRateHz)
  writeIntLe(28, sampleRateHz * 2)
  writeShortLe(32, 2)
  writeShortLe(34, 16)
  writeAscii(36, "data")
  writeIntLe(40, dataSize)
  pcm16.copyInto(wav, 44)
  return wav
}
