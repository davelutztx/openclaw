package ai.openclaw.app.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class TalkModeConsultArgsTest {
  @Test
  fun usesLatestUserTranscriptWhenProviderOmitsQuestion() {
    val args =
      realtimeConsultArgsWithFallbackQuestion(
        Json.parseToJsonElement("""{"context":"asked from voice"}"""),
        "Read slash status",
      ) as JsonObject

    assertEquals("Read slash status", args["question"].primitiveContent())
    assertEquals("asked from voice", args["context"].primitiveContent())
  }

  @Test
  fun keepsProviderQuestionAliases() {
    val args =
      realtimeConsultArgsWithFallbackQuestion(
        Json.parseToJsonElement("""{"query":"Use this one"}"""),
        "Do not use this fallback",
      ) as JsonObject

    assertEquals(null, args["question"].primitiveContent())
    assertEquals("Use this one", args["query"].primitiveContent())
  }

  @Test
  fun leavesMissingQuestionMissingWhenNoTranscriptFallbackExists() {
    val args =
      realtimeConsultArgsWithFallbackQuestion(
        Json.parseToJsonElement("""{"context":"asked from voice"}"""),
        "  ",
      ) as JsonObject

    assertEquals(null, args["question"].primitiveContent())
    assertEquals("asked from voice", args["context"].primitiveContent())
  }
}

private fun kotlinx.serialization.json.JsonElement?.primitiveContent(): String? =
  (this as? JsonPrimitive)?.content
