package ai.openclaw.app.node

import ai.openclaw.app.LocationMode
import ai.openclaw.app.SecurePrefs
import ai.openclaw.app.gateway.DeviceAuthStore
import ai.openclaw.app.gateway.DeviceIdentityStore
import ai.openclaw.app.gateway.GatewayDiscovery
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayHelloSummary
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.GatewayTlsParams
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private const val HEALTH_SNAPSHOT_EVENT = "health.snapshot"
private const val HEALTH_SYNC_WORK_NAME = "openclaw-health-connect-sync"
private const val HEALTH_SYNC_INTERVAL_HOURS = 3L

class HealthSnapshotWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val context = applicationContext
    if (!HealthHandler.isCapabilityAvailable(context)) return Result.success()
    if (!HealthHandler.hasRequestedPermissions(context)) return Result.success()

    val prefs = SecurePrefs(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return try {
      val sync = HealthSnapshotSync(context = context, prefs = prefs, scope = scope)
      if (sync.runOnce()) Result.success() else Result.retry()
    } catch (_: Throwable) {
      Result.retry()
    } finally {
      scope.cancel()
    }
  }

  companion object {
    fun enqueue(context: Context) {
      val request =
        PeriodicWorkRequestBuilder<HealthSnapshotWorker>(
          HEALTH_SYNC_INTERVAL_HOURS,
          TimeUnit.HOURS,
          30,
          TimeUnit.MINUTES,
        )
          .setConstraints(
            Constraints
              .Builder()
              .setRequiredNetworkType(NetworkType.CONNECTED)
              .build(),
          )
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
          .build()

      WorkManager
        .getInstance(context.applicationContext)
        .enqueueUniquePeriodicWork(
          HEALTH_SYNC_WORK_NAME,
          ExistingPeriodicWorkPolicy.UPDATE,
          request,
        )
    }
  }
}

private class HealthSnapshotSync(
  private val context: Context,
  private val prefs: SecurePrefs,
  private val scope: CoroutineScope,
  private val dataSource: HealthDataSource = SystemHealthDataSource,
) {
  private val identityStore = DeviceIdentityStore(context)
  private val deviceAuthStore = DeviceAuthStore(prefs)
  private val connectionManager =
    ConnectionManager(
      prefs = prefs,
      cameraEnabled = { false },
      locationMode = { LocationMode.Off },
      motionActivityAvailable = { false },
      motionPedometerAvailable = { false },
      healthSleepAvailable = { true },
      sendSmsAvailable = { false },
      readSmsAvailable = { false },
      smsSearchPossible = { false },
      callLogAvailable = { false },
      photosAvailable = { false },
      installedAppsSharingEnabled = { false },
      voiceWakeAvailable = { false },
      mobileUiAvailable = { false },
      inlineWidgetsAvailable = { false },
      permissionSnapshot = {
        readAndroidPermissionSnapshot(
          context = context,
          smsEnabled = false,
          callLogEnabled = false,
          photosEnabled = false,
          backgroundLocationEnabled = false,
        )
      },
      manualTls = { prefs.manualTls.value },
    )

  suspend fun runOnce(): Boolean {
    val endpoint = resolveEndpoint() ?: return false
    val payloadJson = buildSnapshotPayload().toString()
    val credentials = prefs.loadGatewayCredentials(endpoint.stableId)
    val connected = CompletableDeferred<Unit>()
    val failed = CompletableDeferred<Unit>()
    val session =
      GatewaySession(
        scope = scope,
        identityStore = identityStore,
        deviceAuthStore = deviceAuthStore,
        onConnected = { _: GatewayHelloSummary -> connected.complete(Unit) },
        onDisconnected = {},
        onConnectFailure = { _, _ -> failed.complete(Unit) },
        onEvent = { _, _ -> },
        onInvoke = null,
        onTlsFingerprint = { stableId, fingerprint ->
          prefs.saveGatewayTlsFingerprint(stableId, fingerprint)
        },
      )
    return try {
      session.connect(
        endpoint = endpoint,
        token = credentials.token,
        bootstrapToken = credentials.bootstrapToken,
        password = credentials.password,
        options = connectionManager.buildNodeConnectOptions(),
        tls = connectionManager.resolveTlsParams(endpoint),
      )
      waitForConnect(connected, failed)
      session.sendNodeEvent(event = HEALTH_SNAPSHOT_EVENT, payloadJson = payloadJson)
    } finally {
      session.disconnect()
    }
  }

  private suspend fun waitForConnect(
    connected: CompletableDeferred<Unit>,
    failed: CompletableDeferred<Unit>,
  ) {
    withTimeout(30_000) {
      while (!connected.isCompleted) {
        if (failed.isCompleted) error("Gateway connection failed")
        delay(250)
      }
    }
  }

  private suspend fun resolveEndpoint(): GatewayEndpoint? {
    if (prefs.manualEnabled.value) {
      val host = prefs.manualHost.value.trim()
      val port = prefs.manualPort.value
      if (host.isNotEmpty() && port in 1..65535) {
        return GatewayEndpoint.manual(host = host, port = port)
      }
    }
    val stableId = prefs.lastDiscoveredStableId.value.trim()
    if (stableId.isEmpty()) return null
    val discovery = GatewayDiscovery(context, scope)
    return withTimeout(20_000) {
      discovery
        .gateways
        .first { list -> list.any { it.stableId == stableId } }
        .firstOrNull { it.stableId == stableId }
    }
  }

  private suspend fun buildSnapshotPayload() =
    buildJsonObject {
      val now = Instant.now()
      put("schema", JsonPrimitive("health.snapshot.v1"))
      put("generatedAtISO", JsonPrimitive(iso(now)))
      put("sleep", buildSleep(now))
      put("heartRate", buildHeartRate(now))
      put("steps", buildSteps(now))
      put("weight", buildWeight(now))
      put("oxygenSaturation", buildOxygen(now))
    }

  private suspend fun buildSleep(now: Instant) =
    buildJsonArray {
      dataSource
        .sleep(context, request(start = now.minus(14, ChronoUnit.DAYS), end = now, limit = 20))
        .forEach { session ->
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
    }

  private suspend fun buildHeartRate(now: Instant) =
    buildJsonArray {
      val seen = mutableSetOf<String>()
      var chunkStart = now.minus(6, ChronoUnit.HOURS)
      while (chunkStart.isBefore(now)) {
        val chunkEnd = chunkStart.plus(15, ChronoUnit.MINUTES).let { if (it.isAfter(now)) now else it }
        dataSource
          .heartRate(context, request(start = chunkStart, end = chunkEnd, limit = 500))
          .sortedBy { it.timeISO }
          .forEach { sample ->
            val key = "${sample.timeISO}|${sample.beatsPerMinute}|${sample.dataOriginPackage.orEmpty()}"
            if (seen.add(key)) {
              add(
                buildJsonObject {
                  put("timeISO", JsonPrimitive(sample.timeISO))
                  put("beatsPerMinute", JsonPrimitive(sample.beatsPerMinute))
                  sample.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
                },
              )
            }
          }
        chunkStart = chunkEnd
      }
    }

  private suspend fun buildSteps(now: Instant) =
    buildJsonArray {
      dataSource
        .steps(context, request(start = now.minus(3, ChronoUnit.DAYS), end = now, limit = 500))
        .forEach { record ->
          add(
            buildJsonObject {
              put("startISO", JsonPrimitive(record.startISO))
              put("endISO", JsonPrimitive(record.endISO))
              put("count", JsonPrimitive(record.count))
              record.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
            },
          )
        }
    }

  private suspend fun buildWeight(now: Instant) =
    buildJsonArray {
      dataSource
        .weight(context, request(start = now.minus(60, ChronoUnit.DAYS), end = now, limit = 500))
        .forEach { record ->
          add(
            buildJsonObject {
              put("timeISO", JsonPrimitive(record.timeISO))
              put("kilograms", JsonPrimitive(record.kilograms))
              record.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
            },
          )
        }
    }

  private suspend fun buildOxygen(now: Instant) =
    buildJsonArray {
      dataSource
        .oxygenSaturation(context, request(start = now.minus(60, ChronoUnit.DAYS), end = now, limit = 500))
        .forEach { record ->
          add(
            buildJsonObject {
              put("timeISO", JsonPrimitive(record.timeISO))
              put("percentage", JsonPrimitive(record.percentage))
              record.dataOriginPackage?.let { put("dataOriginPackage", JsonPrimitive(it)) }
            },
          )
        }
    }

  private fun request(
    start: Instant,
    end: Instant,
    limit: Int,
  ) = HealthTimeRangeRequest(startISO = iso(start), endISO = iso(end), limit = limit)

  private fun iso(value: Instant): String = value.truncatedTo(ChronoUnit.SECONDS).toString()
}
