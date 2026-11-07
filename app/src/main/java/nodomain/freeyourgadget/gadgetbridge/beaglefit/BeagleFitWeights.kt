/*  Copyright (C) 2026 C.F.R. Inc.

    This file is part of Gadgetbridge (Beagle Fit Sync).

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.beaglefit

import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.IntConsumer
import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * Weigh-ins from Health Connect. A Garmin Index scale sends its weigh-ins to Garmin over Wi-Fi, so
 * they never reach the watch; Garmin Connect writes them to Health Connect, and this reads them
 * there (with body fat when the scale measured it), writes them as a scale's FIT file
 * (FitWeightFile) and queues it like a run. Other apps that write weight to Health Connect come in
 * the same way. Health Connect's changes token says what is new since the last look, including
 * weigh-ins Garmin Connect syncs late; beagle.fit keeps one reading per minute, so one sent twice
 * replaces itself.
 */
object BeagleFitWeights {
    private val LOG = LoggerFactory.getLogger(BeagleFitWeights::class.java)

    // The same prefs file as BeagleFitStore, so the page hears about changes (BeagleFitBridge).
    private const val PREFS = "beaglefit"
    private const val K_ON = "weights_on"
    private const val K_TOKEN = "weights_token"
    private const val K_BACKGROUND = "weights_background"
    private const val K_LAST_COUNT = "weights_last_count"
    private const val K_LAST_AT = "weights_last_at"
    private const val WORK_NAME = "beaglefit-weights"
    /** Health Connect lets an app read 30 days back from when it was first allowed. */
    private const val FIRST_DAYS = 30L
    /** Body fat goes with the weigh-in taken within two minutes of it. */
    private const val MATCH_MS = 2 * 60_000L
    private const val MAX_PER_FILE = 5000

    private val READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)
    private val READ_FAT = HealthPermission.getReadPermission(BodyFatRecord::class)
    private const val READ_BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "ok", "update" (Health Connect needs an update from Google Play) or "none" (not on this phone). */
    @JvmStatic
    fun availability(context: Context): String {
        // Health Connect runs on Android 9 and later.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "none"
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> "ok"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
            else -> "none"
        }
    }

    private fun client(context: Context): HealthConnectClient? =
        if (availability(context) == "ok") HealthConnectClient.getOrCreate(context.applicationContext) else null

    @JvmStatic
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** Weight and body fat, and reading in the background where this phone's Health Connect has it. */
    @JvmStatic
    fun permissionsToAsk(context: Context): Set<String> {
        val asks = mutableSetOf(READ_WEIGHT, READ_FAT)
        val c = client(context) ?: return asks
        if (c.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            asks.add(READ_BACKGROUND)
        }
        return asks
    }

    @JvmStatic
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(K_ON, false)

    /** What the page's Watch tab shows about weigh-ins. */
    @JvmStatic
    fun status(context: Context): JSONObject {
        val p = prefs(context)
        return JSONObject()
            .put("available", availability(context))
            .put("on", p.getBoolean(K_ON, false))
            .put("background", p.getBoolean(K_BACKGROUND, false))
            .put("lastCount", p.getInt(K_LAST_COUNT, 0))
            .put("lastAt", p.getLong(K_LAST_AT, 0L))
    }

    /**
     * After Health Connect's permission screen: on when reading weight was allowed, and the last 30
     * days come in. [done] gets whether it's on, on the worker thread.
     */
    @JvmStatic
    fun afterPermissions(context: Context, done: Consumer<Boolean>) {
        val app = context.applicationContext
        scope.launch {
            val granted = try {
                client(app)?.permissionController?.getGrantedPermissions() ?: emptySet()
            } catch (e: Exception) {
                LOG.warn("Beagle Fit: could not read Health Connect's permissions", e)
                emptySet()
            }
            val on = READ_WEIGHT in granted
            prefs(app).edit {
                putBoolean(K_ON, on)
                putBoolean(K_BACKGROUND, READ_BACKGROUND in granted)
            }
            if (on) {
                schedule(app)
                collect(app, false)
            }
            done.accept(on)
        }
    }

    /** Stops reading: Health Connect forgets this app's permissions, and the next start reads 30 days again. */
    @JvmStatic
    fun turnOff(context: Context, done: Runnable) {
        val app = context.applicationContext
        scope.launch {
            prefs(app).edit {
                putBoolean(K_ON, false)
                putBoolean(K_BACKGROUND, false)
                remove(K_TOKEN)
            }
            WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            try {
                client(app)?.permissionController?.revokeAllPermissions()
            } catch (e: Exception) {
                LOG.warn("Beagle Fit: could not revoke Health Connect's permissions", e)
            }
            done.run()
        }
    }

    /** Looks for new weigh-ins now (the app was opened, or the page asked). [done] gets how many were queued, -1 on a failure. */
    @JvmStatic
    @JvmOverloads
    fun collectAsync(context: Context, done: IntConsumer? = null) {
        val app = context.applicationContext
        scope.launch {
            val n = collect(app, false)
            done?.accept(n)
        }
    }

    /** Every few hours while it's on; the worker reads only when Health Connect allows reading in the background. */
    @JvmStatic
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BeagleFitWeightWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** The new weigh-ins, queued as one FIT file. Returns how many, 0 for none, -1 on a failure. */
    suspend fun collect(context: Context, background: Boolean): Int = lock.withLock {
        if (!isOn(context)) return@withLock 0
        val c = client(context) ?: return@withLock 0
        val p = prefs(context)
        try {
            val granted = c.permissionController.getGrantedPermissions()
            val canBackground = READ_BACKGROUND in granted
            if (READ_WEIGHT !in granted) {
                // Taken away in Health Connect's own settings.
                p.edit { putBoolean(K_ON, false); putBoolean(K_BACKGROUND, false); remove(K_TOKEN) }
                return@withLock 0
            }
            if (canBackground != p.getBoolean(K_BACKGROUND, false)) p.edit { putBoolean(K_BACKGROUND, canBackground) }
            if (background && !canBackground) return@withLock 0

            val weights = mutableListOf<WeightRecord>()
            var token = p.getString(K_TOKEN, null)
            if (token != null) {
                while (true) {
                    val r = c.getChanges(token!!)
                    if (r.changesTokenExpired) {
                        // Unused for 30 days: start again from a fresh token and the last 30 days.
                        token = null
                        weights.clear()
                        break
                    }
                    r.changes.filterIsInstance<UpsertionChange>().mapNotNullTo(weights) { it.record as? WeightRecord }
                    token = r.nextChangesToken
                    if (!r.hasMore) break
                }
            }
            if (token == null) {
                // The token first, so a weigh-in written while the 30 days are read comes next time.
                token = c.getChangesToken(ChangesTokenRequest(setOf(WeightRecord::class)))
                val now = Instant.now()
                weights.addAll(readAll(c, WeightRecord::class, now.minus(FIRST_DAYS, ChronoUnit.DAYS), now))
            }

            if (weights.isEmpty()) {
                p.edit { putString(K_TOKEN, token) }
                return@withLock 0
            }
            weights.sortBy { it.time }
            val fats = readAll(c, BodyFatRecord::class,
                weights.first().time.minusMillis(MATCH_MS), weights.last().time.plusMillis(MATCH_MS + 1))
            val readings = weights.map { w ->
                val fat = fats.filter { abs(it.time.toEpochMilli() - w.time.toEpochMilli()) <= MATCH_MS }
                    .minByOrNull { abs(it.time.toEpochMilli() - w.time.toEpochMilli()) }
                FitWeightFile.Reading(w.time.toEpochMilli(), w.weight.inKilograms, fat?.percentage?.value)
            }

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            var queued = 0
            for ((i, chunk) in readings.chunked(MAX_PER_FILE).withIndex()) {
                val name = "weigh-ins-$stamp" + (if (i > 0) "-$i" else "") + ".fit"
                when (BeagleFitStore.enqueueData(context, name, FitWeightFile.build(chunk, System.currentTimeMillis()))) {
                    BeagleFitStore.QueueResult.QUEUED, BeagleFitStore.QueueResult.ALREADY_QUEUED,
                    BeagleFitStore.QueueResult.ALREADY_SENT -> queued += chunk.size
                    // Not saved: keep the old token, so the same weigh-ins come again next time.
                    else -> return@withLock -1
                }
            }
            p.edit {
                putString(K_TOKEN, token)
                putInt(K_LAST_COUNT, queued)
                putLong(K_LAST_AT, System.currentTimeMillis())
            }
            LOG.info("Beagle Fit: {} weigh-ins from Health Connect queued", queued)
            BeagleFit.scheduleUpload(context, false)
            queued
        } catch (e: Exception) {
            // A SecurityException when Health Connect refuses a read (in the background, say): next time.
            LOG.warn("Beagle Fit: reading weigh-ins from Health Connect failed", e)
            -1
        }
    }

    private suspend fun <T : Record> readAll(c: HealthConnectClient, type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var page: String? = null
        do {
            val r = c.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageToken = page))
            out.addAll(r.records)
            page = r.pageToken
        } while (!page.isNullOrEmpty())
        return out
    }
}

/** WorkManager's periodic look for new weigh-ins (BeagleFitWeights.schedule). */
class BeagleFitWeightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BeagleFitWeights.collect(applicationContext, true)
        return Result.success()
    }
}
