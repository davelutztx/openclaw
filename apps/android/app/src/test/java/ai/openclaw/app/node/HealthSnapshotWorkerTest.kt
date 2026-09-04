package ai.openclaw.app.node

import ai.openclaw.app.NodeApp
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthSnapshotWorkerTest {
  @Test
  fun enqueueRegistersOnePeriodicHealthSync() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)

    HealthSnapshotWorker.enqueue(context)
    HealthSnapshotWorker.enqueue(context)

    val work =
      WorkManager
        .getInstance(context)
        .getWorkInfosForUniqueWork(HEALTH_SYNC_WORK_NAME)
        .get(5, TimeUnit.SECONDS)

    assertEquals(1, work.size)
    assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
  }

  @Test
  fun nodeApplicationSchedulesHealthSyncOnCreate() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    val app = NodeApp()
    shadowOf(app).callAttach(context)

    app.onCreate()

    val work =
      WorkManager
        .getInstance(context)
        .getWorkInfosForUniqueWork(HEALTH_SYNC_WORK_NAME)
        .get(5, TimeUnit.SECONDS)
    assertEquals(1, work.size)
    assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
  }
}
