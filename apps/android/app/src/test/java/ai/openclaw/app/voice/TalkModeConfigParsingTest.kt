package ai.openclaw.app.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class TalkModeConfigParsingTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun readsMainSessionKeyAndInterruptFlag() {
    val config =
      json
        .parseToJsonElement(
          """
          {
            "talk": {
              "interruptOnSpeech": true,
              "silenceTimeoutMs": 1800,
              "remoteSpeechEnabled": true,
              "realtime": {
                "mode": "realtime",
                "transport": "gateway-relay"
              }
            },
            "session": {
              "mainKey": "voice-main"
            }
          }
          """.trimIndent(),
        ).jsonObject

    val parsed = TalkModeGatewayConfigParser.parse(config)

    assertEquals("voice-main", parsed.mainSessionKey)
    assertEquals(true, parsed.interruptOnSpeech)
    assertEquals(1800L, parsed.silenceTimeoutMs)
    assertEquals(true, parsed.realtimeModeEnabled)
    assertEquals(true, parsed.remoteSpeechEnabled)
  }

  @Test
  fun remoteSpeechDefaultsOff() {
    val parsed = TalkModeGatewayConfigParser.parse(buildJsonObject {})

    assertEquals(false, parsed.remoteSpeechEnabled)
  }

  @Test
  fun realtimeModeRequiresExplicitGatewayRelay() {
    assertEquals(false, TalkModeGatewayConfigParser.isRealtimeModeEnabled(null))
    assertEquals(
      false,
      TalkModeGatewayConfigParser.isRealtimeModeEnabled(
        buildJsonObject {
          put("mode", "realtime")
          put("transport", "webrtc")
        },
      ),
    )
    assertEquals(
      true,
      TalkModeGatewayConfigParser.isRealtimeModeEnabled(
        buildJsonObject {
          put("mode", "realtime")
          put("transport", "gateway-relay")
        },
      ),
    )
  }

  @Test
  fun defaultsSilenceTimeoutMsWhenMissing() {
    assertEquals(
      TalkDefaults.defaultSilenceTimeoutMs,
      TalkModeGatewayConfigParser.resolvedSilenceTimeoutMs(null),
    )
  }

  @Test
  fun defaultsSilenceTimeoutMsWhenInvalid() {
    val talk = buildJsonObject { put("silenceTimeoutMs", 0) }

    assertEquals(
      TalkDefaults.defaultSilenceTimeoutMs,
      TalkModeGatewayConfigParser.resolvedSilenceTimeoutMs(talk),
    )
  }

  @Test
  fun defaultsSilenceTimeoutMsWhenString() {
    val talk = buildJsonObject { put("silenceTimeoutMs", "1500") }

    assertEquals(
      TalkDefaults.defaultSilenceTimeoutMs,
      TalkModeGatewayConfigParser.resolvedSilenceTimeoutMs(talk),
    )
  }
}
