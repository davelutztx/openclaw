package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

private const val DEFAULT_HEALTH_LOOKBACK_DAYS = 7L
private const val DEFAULT_HEALTH_LIMIT = 20

internal data class HealthTimeRangeRequest(
  val startISO: String?,
  val endISO: String?,
  val limit: Int,
)

internal typealias HealthSleepRequest = HealthTimeRangeRequest

internal data class HealthSleepStageRecord(
  val startISO: String,
  val endISO: String,
  val stage: String,
)

internal data class HealthSleepSessionRecord(
  val startISO: String,
  val endISO: String,
  val durationMinutes: Long,
  val title: String?,
  val notes: String?,
  val dataOriginPackage: String?,
  val stages: List<HealthSleepStageRecord>,
)

internal data class HealthHeartRateSample(
  val timeISO: String,
  val beatsPerMinute: Long,
  val dataOriginPackage: String?,
)

internal data class HealthStepsRecord(
  val startISO: String,
  val endISO: String,
  val count: Long,
  val dataOriginPackage: String?,
)

internal data class HealthWeightRecord(
  val timeISO: String,
  val kilograms: Double,
  val dataOriginPackage: String?,
)

internal data class HealthOxygenSaturationRecord(
  val timeISO: String,
  val percentage: Double,
  val dataOriginPackage: String?,
)

internal interface HealthDataSource {
  fun isAvailable(context: Context): Boolean

  suspend fun hasSleepPermission(context: Context): Boolean

  suspend fun hasRequestedPermissions(context: Context): Boolean

  suspend fun sleep(
    context: Context,
    request: HealthSleepRequest,
  ): List<HealthSleepSessionRecord>

  suspend fun heartRate(
    context: Context,
    request: HealthTimeRangeRequest,
  ): List<HealthHeartRateSample>

  suspend fun steps(
    context: Context,
    request: HealthTimeRangeRequest,
  ): List<HealthStepsRecord>

  suspend fun weight(
    context: Context,
    request: HealthTimeRangeRequest,
  ): List<HealthWeightRecord>

  suspend fun oxygenSaturation(
    context: Context,
    request: HealthTimeRangeRequest,
  ): List<HealthOxygenSaturationRecord>
}

internal object HealthConnectSleepPermissions {
  val readSleep: Set<String> = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
}

internal object HealthConnectRequestedPermissions {
  val readHeartRate: Set<String> = setOf(HealthPermission.getReadPermission(HeartRateRecord::class))
  val readSteps: Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))
  val readWeight: Set<String> = setOf(HealthPermission.getReadPermission(WeightRecord::class))
  val readOxygenSaturation: Set<String> = setOf(HealthPermission.getReadPermission(OxygenSaturationRecord::class))
  val all: Set<String> =
    HealthConnectSleepPermissions.readSleep + readHeartRate + readSteps + readWeight + readOxygenSaturation
}

internal object SystemHealthDataSource : HealthDataSource {
  override fun isAvailable(context: Context): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

  override suspend fun hasSleepPermission(context: Context): Boolean = hasPermissions(context, HealthConnectSleepPermissions.readSleep)

  override suspend fun hasRequestedPermissions(context: Context): Boolean = hasPermissions(context, HealthConnectRequestedPermissions.all)

  override suspend fun sleep(
    context: Context,
    request: HealthSleepRequest,
  ): List<HealthSleepSessionRecord> {
    ensureAvailableAndPermitted(context, HealthConnectSleepPermissions.readSleep, "Sleep")
    val (start, end) = request.resolveRange()
    val response =
      HealthConnectClient.getOrCreate(context).readRecords(
        ReadRecordsRequest(
          recordType = SleepSessionRecord::class,
          timeRangeFilter = TimeRangeFilter.between(start, end),
        ),
      )
    return response.records
      .sortedByDescending { it.endTime }
      .take(request.limit)
      .map { record ->
        HealthSleepSessionRecord(
          startISO = record.startTime.toString(),
          endISO = record.endTime.toString(),
          durationMinutes = Duration.between(record.startTime, record.endTime).toMinutes(),
          title = record.title,
          notes = record.notes,
          dataOriginPackage = record.metadata.dataOrigin.packageName,
          stages =
            record.stages.map { stage ->
              HealthSleepStageRecord(
                startISO = stage.startTime.toString(),
                endISO = stage.endTime.toString(),
                stage = sleepStageLabel(stage.stage),
              )
            },
        )
      }
  }

  override suspend fun heartRate(
    context: Context,
    request: HealthTimeRangeRequest,
  ): List<HealthHeartRateSample> {
    ensureAvailableAndPermitted(context, HealthConnectRequestedPermissions.readHeartRate, "Heart rate")
    val (start, end) = request.resolveRange()
    val response =
      HealthConnectClient.getOrCreate(context).readRecords(
        ReadRecordsRequest(
          recordType = HeartRateRecord::class,
          timeRangeFilter = TimeRangeFilter.between(start, end),
        ),
      )
    return response.records
      .flatMap { record ->
        record.samples.map { sample ->
          HealthHeartRateSample(
            timeISO = sample.time.toString(),
            beatsPerMinute = sample.beatsPerMinute,
            dataOriginPackage = record.metadata.dataOrigin.packageName,
          )
        }
      }.sortedByDescending { it.timeISO }
      .take(request.limit)
  }

  override suspend fun steps(
    context: Context,
    request: HealthTimeRangeRequest,
  ): List<HealthStepsRecord> {
    ensureAvailableAndPermitted(context, HealthConnectRequestedPermissions.readSteps, "Steps")
    val (start, end) = request.resolveRange()
    val response =
      HealthConnectClient.getOrCreate(context).readRecords(
        ReadRecordsRequest(
          recordType = StepsRecord::class,
          timeRangeFilter = TimeRangeFilter.between(start, end),
        ),
      )
    return response.records
      .sortedByDescending { it.endTime }
      .take(request.limit)
      .map { record ->
        HealthStepsRecord(
          startISO = record.startTime.toString(),
          endISO = record.endTime.toString(),
          count = record.count,
          dataOriginPackage = record.metadata.dataOrigin.packageName,
        )
      }
  }

  override suspend fun weight(
    context: Context,
    request: HealthTimeRangeRequest,
  ): List<HealthWeightRecord> {
    ensureAvailableAndPermitted(context, HealthConnectRequestedPermissions.readWeight, "Weight")
    val (start, end) = request.resolveRange()
    val response =
      HealthConnectClient.getOrCreate(context).readRecords(
        ReadRecordsRequest(
          recordType = WeightRecord::class,
          timeRangeFilter = TimeRangeFilter.between(start, end),
        ),
      )
    return response.records
      .sortedByDescending { it.time }
      .take(request.limit)
      .map { record ->
        HealthWeightRecord(
          timeISO = record.time.toString(),
          kilograms = record.weight.inKilograms,
          dataOriginPackage = record.metadata.dataOrigin.packageName,
        )
      }
  }

  override suspend fun oxygenSaturation(
    context: Context,
    request: HealthTimeRangeRequest,
  ): List<HealthOxygenSaturationRecord> {
    ensureAvailableAndPermitted(context, HealthConnectRequestedPermissions.readOxygenSaturation, "Oxygen saturation")
    val (start, end) = request.resolveRange()
    val response =
      HealthConnectClient.getOrCreate(context).readRecords(
        ReadRecordsRequest(
          recordType = OxygenSaturationRecord::class,
          timeRangeFilter = TimeRangeFilter.between(start, end),
        ),
      )
    return response.records
      .sortedByDescending { it.time }
      .take(request.limit)
      .map { record ->
        HealthOxygenSaturationRecord(
          timeISO = record.time.toString(),
          percentage = record.percentage.value,
          dataOriginPackage = record.metadata.dataOrigin.packageName,
        )
      }
  }

  private suspend fun hasPermissions(
    context: Context,
    permissions: Set<String>,
  ): Boolean {
    if (!isAvailable(context)) return false
    val client = HealthConnectClient.getOrCreate(context)
    return client.permissionController.getGrantedPermissions().containsAll(permissions)
  }

  private suspend fun ensureAvailableAndPermitted(
    context: Context,
    permissions: Set<String>,
    label: String,
  ) {
    if (!isAvailable(context)) {
      throw IllegalStateException("HEALTH_CONNECT_UNAVAILABLE: Health Connect is not available on this device")
    }
    if (!hasPermissions(context, permissions)) {
      throw SecurityException("HEALTH_PERMISSION_REQUIRED: grant Health Connect $label permission")
    }
  }
}

class HealthHandler private constructor(
  private val appContext: Context,
  private val dataSource: HealthDataSource,
) {
  constructor(appContext: Context) : this(appContext = appContext, dataSource = SystemHealthDataSource)

  suspend fun handleHealthSleep(paramsJson: String?): GatewaySession.InvokeResult =
    handleHealthList(
      paramsJson = paramsJson,
      unavailableCode = "HEALTH_SLEEP_UNAVAILABLE",
      permissionMessage = "HEALTH_PERMISSION_REQUIRED: grant Health Connect Sleep permission",
    ) { request ->
      val sessions = dataSource.sleep(appContext, request)
      buildJsonObject {
        put(
          "sleep",
          buildJsonArray {
            sessions.forEach { session ->
              add(
                buildJsonObject {
                  put("startISO", JsonPrimitive(session.startISO))
                  put("endISO", JsonPrimitive(session.endISO))
                  put("durationMinutes", JsonPrimitive(session.durationMinutes))
                  session.title?.let { put("title", JsonPrimitive(it)) }
                  session.notes?.let { put("notes", JsonPrimitive(it)) }
                  session.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
                  put(
                    "stages",
                    buildJsonArray {
                      session.stages.forEach { stage ->
                        add(
                          buildJsonObject {
                            put("startISO", JsonPrimitive(stage.startISO))
                            put("endISO", JsonPrimitive(stage.endISO))
                            put("stage", JsonPrimitive(stage.stage))
                          },
                        )
                      }
                    },
                  )
                },
              )
            }
          },
        )
      }
    }

  suspend fun handleHealthHeartRate(paramsJson: String?): GatewaySession.InvokeResult =
    handleHealthList(
      paramsJson = paramsJson,
      unavailableCode = "HEALTH_HEART_RATE_UNAVAILABLE",
      permissionMessage = "HEALTH_PERMISSION_REQUIRED: grant Health Connect Heart rate permission",
    ) { request ->
      val samples = dataSource.heartRate(appContext, request)
      buildJsonObject {
        put(
          "heartRate",
          buildJsonArray {
            samples.forEach { sample ->
              add(
                buildJsonObject {
                  put("timeISO", JsonPrimitive(sample.timeISO))
                  put("beatsPerMinute", JsonPrimitive(sample.beatsPerMinute))
                  sample.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
                },
              )
            }
          },
        )
      }
    }

  suspend fun handleHealthSteps(paramsJson: String?): GatewaySession.InvokeResult =
    handleHealthList(
      paramsJson = paramsJson,
      unavailableCode = "HEALTH_STEPS_UNAVAILABLE",
      permissionMessage = "HEALTH_PERMISSION_REQUIRED: grant Health Connect Steps permission",
    ) { request ->
      val records = dataSource.steps(appContext, request)
      buildJsonObject {
        put("totalCount", JsonPrimitive(records.sumOf { it.count }))
        put(
          "steps",
          buildJsonArray {
            records.forEach { record ->
              add(
                buildJsonObject {
                  put("startISO", JsonPrimitive(record.startISO))
                  put("endISO", JsonPrimitive(record.endISO))
                  put("count", JsonPrimitive(record.count))
                  record.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
                },
              )
            }
          },
        )
      }
    }

  suspend fun handleHealthWeight(paramsJson: String?): GatewaySession.InvokeResult =
    handleHealthList(
      paramsJson = paramsJson,
      unavailableCode = "HEALTH_WEIGHT_UNAVAILABLE",
      permissionMessage = "HEALTH_PERMISSION_REQUIRED: grant Health Connect Weight permission",
    ) { request ->
      val records = dataSource.weight(appContext, request)
      buildJsonObject {
        put(
          "weight",
          buildJsonArray {
            records.forEach { record ->
              add(
                buildJsonObject {
                  put("timeISO", JsonPrimitive(record.timeISO))
                  put("kilograms", JsonPrimitive(record.kilograms))
                  record.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
                },
              )
            }
          },
        )
      }
    }

  suspend fun handleHealthOxygenSaturation(paramsJson: String?): GatewaySession.InvokeResult =
    handleHealthList(
      paramsJson = paramsJson,
      unavailableCode = "HEALTH_OXYGEN_SATURATION_UNAVAILABLE",
      permissionMessage = "HEALTH_PERMISSION_REQUIRED: grant Health Connect Oxygen saturation permission",
    ) { request ->
      val records = dataSource.oxygenSaturation(appContext, request)
      buildJsonObject {
        put(
          "oxygenSaturation",
          buildJsonArray {
            records.forEach { record ->
              add(
                buildJsonObject {
                  put("timeISO", JsonPrimitive(record.timeISO))
                  put("percentage", JsonPrimitive(record.percentage))
                  record.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
                },
              )
            }
          },
        )
      }
    }

  fun isSleepAvailable(): Boolean = dataSource.isAvailable(appContext)

  fun isAvailable(): Boolean = dataSource.isAvailable(appContext)

  suspend fun hasSleepPermission(): Boolean = dataSource.hasSleepPermission(appContext)

  suspend fun hasRequestedPermissions(): Boolean = dataSource.hasRequestedPermissions(appContext)

  private suspend fun handleHealthList(
    paramsJson: String?,
    unavailableCode: String,
    permissionMessage: String,
    buildPayload: suspend (HealthTimeRangeRequest) -> kotlinx.serialization.json.JsonObject,
  ): GatewaySession.InvokeResult {
    val request =
      parseHealthRequest(paramsJson)
        ?: return GatewaySession.InvokeResult.error(
          code = "INVALID_REQUEST",
          message = "INVALID_REQUEST: expected JSON object",
        )
    return try {
      GatewaySession.InvokeResult.ok(buildPayload(request).toString())
    } catch (err: SecurityException) {
      GatewaySession.InvokeResult.error(
        code = "HEALTH_PERMISSION_REQUIRED",
        message = err.message ?: permissionMessage,
      )
    } catch (err: IllegalArgumentException) {
      GatewaySession.InvokeResult.error(code = "INVALID_REQUEST", message = err.message ?: "INVALID_REQUEST")
    } catch (err: Throwable) {
      val message = err.message ?: "health query failed"
      val code = if (message.startsWith("HEALTH_CONNECT_UNAVAILABLE")) "HEALTH_CONNECT_UNAVAILABLE" else unavailableCode
      GatewaySession.InvokeResult.error(code = code, message = message)
    }
  }

  private fun parseHealthRequest(paramsJson: String?): HealthTimeRangeRequest? {
    if (paramsJson.isNullOrBlank()) {
      return HealthTimeRangeRequest(startISO = null, endISO = null, limit = DEFAULT_HEALTH_LIMIT)
    }
    val params =
      try {
        Json.parseToJsonElement(paramsJson).asObjectOrNull()
      } catch (_: Throwable) {
        null
      } ?: return null
    val limit = ((params["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_HEALTH_LIMIT).coerceIn(1, 500)
    return HealthTimeRangeRequest(
      startISO = (params["startISO"] as? JsonPrimitive)?.content?.trim()?.ifEmpty { null },
      endISO = (params["endISO"] as? JsonPrimitive)?.content?.trim()?.ifEmpty { null },
      limit = limit,
    )
  }

  companion object {
    fun isSleepCapabilityAvailable(context: Context): Boolean = SystemHealthDataSource.isAvailable(context)

    fun isCapabilityAvailable(context: Context): Boolean = SystemHealthDataSource.isAvailable(context)

    suspend fun hasSleepPermission(context: Context): Boolean = SystemHealthDataSource.hasSleepPermission(context)

    suspend fun hasRequestedPermissions(context: Context): Boolean = SystemHealthDataSource.hasRequestedPermissions(context)

    internal fun forTesting(
      appContext: Context,
      dataSource: HealthDataSource,
    ): HealthHandler = HealthHandler(appContext = appContext, dataSource = dataSource)
  }
}

private fun HealthTimeRangeRequest.resolveRange(): Pair<Instant, Instant> {
  val end = endISO?.let(::parseInstantStrict) ?: Instant.now()
  val start = startISO?.let(::parseInstantStrict) ?: end.minus(Duration.ofDays(DEFAULT_HEALTH_LOOKBACK_DAYS))
  if (!start.isBefore(end)) {
    throw IllegalArgumentException("INVALID_REQUEST: startISO must be before endISO")
  }
  return start to end
}

private fun parseInstantStrict(value: String): Instant =
  try {
    Instant.parse(value)
  } catch (_: Throwable) {
    throw IllegalArgumentException("INVALID_REQUEST: expected ISO-8601 instant")
  }

private fun sleepStageLabel(stage: Int): String =
  when (stage) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
    SleepSessionRecord.STAGE_TYPE_REM -> "rem"
    SleepSessionRecord.STAGE_TYPE_UNKNOWN -> "unknown"
    else -> "unknown"
  }
