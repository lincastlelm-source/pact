package com.pact.coach

import android.app.Application
import android.util.Log
import com.pact.coach.di.AppContainer
import com.pact.coach.services.work.MaintenanceWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PactApplication : Application() {

    /**
     * Manual dependency injection through a single container.
     *
     * A DI framework would add an annotation processor and a compile-time graph for a graph that
     * is, in practice, one database and about ten singletons. The container is explicit, trivial
     * to substitute in tests, and keeps the build fast.
     */
    lateinit var container: AppContainer
        private set

    /**
     * Scope for work that must outlive any screen: broadcast receivers finishing their database
     * writes, the boot-time reschedule, and start-up maintenance.
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, throwable ->
                    Log.e(TAG, "Unhandled error in application scope", throwable)
                },
        )
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        container.notificationChannels.ensureBaseChannels()

        applicationScope.launch {
            // Bring the schedule up to date on every cold start: the app may have been closed
            // for days, the horizon may have run out, and alarms may have been cleared by a
            // force-stop.
            runCatching { container.schedulingCoordinator.refresh() }
                .onFailure { Log.e(TAG, "Start-up schedule refresh failed", it) }

            runCatching { container.seedBuiltInTemplates() }
                .onFailure { Log.e(TAG, "Could not seed templates", it) }
        }

        MaintenanceWorker.enqueuePeriodic(this)
    }

    companion object {
        private const val TAG = "PactApplication"
    }
}

/**
 * Launches [block] on this scope with the error and cleanup handling that broadcast receivers
 * need: never crash the process, and always release the `goAsync` result.
 */
fun CoroutineScope.launchGuarded(
    onError: (Throwable) -> Unit,
    onFinally: () -> Unit,
    block: suspend () -> Unit,
): Job = launch {
    try {
        block()
    } catch (e: Throwable) {
        onError(e)
    } finally {
        onFinally()
    }
}
