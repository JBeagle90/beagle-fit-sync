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
package nodomain.freeyourgadget.gadgetbridge.beaglefit;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Beagle Fit Sync's entry points for the rest of the app: the hook called when Gadgetbridge has
 * saved an activity FIT file from the watch, scheduling the upload, and queueing recent files.
 */
public final class BeagleFit {
    private static final Logger LOG = LoggerFactory.getLogger(BeagleFit.class);

    static final String WORK_NAME = "beaglefit-upload";
    static final int NOTIFICATION_ID_UNLINKED = 4301;

    private BeagleFit() {
    }

    /**
     * What the launcher icon opens: beagle.fit's page, its task brought to the front as it was left
     * (the activity is singleTask, and an intent without a hash doesn't reload it). Gadgetbridge's
     * notifications, its widget and its restart open this in this build instead of its home screen,
     * which only the Watch tab opens.
     */
    @NonNull
    public static Intent homeIntent(@NonNull final Context context) {
        return new Intent(context, BeagleFitWebActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * Called by FitImporter once a FIT file whose file_id says "activity" is in Gadgetbridge's
     * export folder. Queues a copy and starts the upload. Never throws: a problem here must not
     * break the watch sync.
     */
    public static void onActivityFileSaved(@NonNull final Context context, @NonNull final File file) {
        try {
            final BeagleFitStore.QueueResult result = BeagleFitStore.enqueue(context, file);
            LOG.debug("Beagle Fit: {} for {}", result, file.getName());
            if (result == BeagleFitStore.QueueResult.QUEUED) {
                scheduleUpload(context, false);
            }
        } catch (final Throwable t) {
            LOG.error("Beagle Fit: failed to queue {}", file, t);
        }
    }

    /**
     * Starts the upload worker when the phone has a network. With {@code now}, a waiting retry is
     * replaced so it goes at once (after linking, or when the person asks); otherwise the new work
     * runs after any upload already running or waiting.
     */
    public static void scheduleUpload(@NonNull final Context context, final boolean now) {
        final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BeagleFitUploadWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                WORK_NAME,
                now ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.APPEND_OR_REPLACE,
                request);
    }

    /**
     * Queues the activity FIT files Gadgetbridge already has from the last {@code days} days: the
     * files behind its workouts list, and any activity files in its export folders. Files sent
     * before are left out. Runs on a background thread (it reads the database and the files).
     *
     * @return how many files were added to the queue
     */
    public static int queueRecent(@NonNull final Context context, final int days) {
        final long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        final Set<File> candidates = new LinkedHashSet<>();

        try (DBHandler db = GBApplication.acquireDbReadOnly()) {
            final List<BaseActivitySummary> summaries = db.getDaoSession().getBaseActivitySummaryDao().queryBuilder()
                    .where(BaseActivitySummaryDao.Properties.StartTime.ge(new Date(since)),
                            BaseActivitySummaryDao.Properties.RawDetailsPath.isNotNull())
                    .orderAsc(BaseActivitySummaryDao.Properties.StartTime)
                    .build()
                    .list();
            for (final BaseActivitySummary summary : summaries) {
                final String path = summary.getRawDetailsPath();
                if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".fit")) {
                    candidates.add(new File(path));
                }
            }
        } catch (final Exception e) {
            LOG.error("Beagle Fit: could not read workouts", e);
        }

        // Gadgetbridge saves activity files under <export>/<device address>/ACTIVITY/<year>/.
        try {
            final File[] deviceDirs = FileUtils.getExternalFilesDir().listFiles(File::isDirectory);
            if (deviceDirs != null) {
                for (final File deviceDir : deviceDirs) {
                    final File activityDir = new File(deviceDir, "ACTIVITY");
                    if (!activityDir.isDirectory()) continue;
                    for (final File f : FileUtils.listRecursive(activityDir, (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".fit"))) {
                        if (f.lastModified() >= since) {
                            candidates.add(f);
                        }
                    }
                }
            }
        } catch (final Exception e) {
            LOG.error("Beagle Fit: could not list export folders", e);
        }

        int queued = 0;
        for (final File f : candidates) {
            if (BeagleFitStore.enqueue(context, f) == BeagleFitStore.QueueResult.QUEUED) {
                queued++;
            }
        }
        LOG.info("Beagle Fit: {} candidate files from the last {} days, {} queued", candidates.size(), days, queued);
        if (BeagleFitStore.queueLength(context) > 0) {
            scheduleUpload(context, true);
        }
        return queued;
    }

    /** Tells the person the website unlinked this phone; tapping opens the site's Watch tab. */
    static void notifyUnlinked(@NonNull final Context context) {
        try {
            final Intent intent = new Intent(context, BeagleFitWebActivity.class)
                    .putExtra(BeagleFitWebActivity.EXTRA_HASH, "v3/watch")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final PendingIntent pi = PendingIntent.getActivity(context, NOTIFICATION_ID_UNLINKED, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            final String text = context.getString(R.string.beaglefit_unlinked_notification);
            GB.notify(NOTIFICATION_ID_UNLINKED, new NotificationCompat.Builder(context, GB.NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.beaglefit_title))
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build(), context);
        } catch (final Exception e) {
            LOG.warn("Beagle Fit: could not post the unlinked notification", e);
        }
    }
}
