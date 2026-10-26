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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;

/**
 * Sends the queued FIT files to beagle.fit one at a time. WorkManager runs it when there is a
 * network and retries it with exponential backoff when it returns {@link Result#retry()}.
 */
public class BeagleFitUploadWorker extends Worker {
    private static final Logger LOG = LoggerFactory.getLogger(BeagleFitUploadWorker.class);

    private final BeagleFitApi api = new BeagleFitApi();

    public BeagleFitUploadWorker(@NonNull final Context context, @NonNull final WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context context = getApplicationContext();
        final String key = BeagleFitStore.key(context);
        if (key == null) {
            // Not linked: the files wait in the queue until the phone is linked again.
            return Result.success();
        }
        final String baseUrl = BeagleFitStore.baseUrl(context);

        // Files kept after a refusal this run, so the loop does not send them twice.
        final Set<String> keptThisRun = new HashSet<>();
        int sent = 0;
        boolean moreLeft = true;
        while (moreLeft) {
            moreLeft = false;
            final List<File> queue = BeagleFitStore.queuedFiles(context);
            for (final File file : queue) {
                if (isStopped()) {
                    return Result.retry();
                }
                final String sha = BeagleFitStore.shaOf(file);
                if (keptThisRun.contains(sha)) continue;
                final String name = BeagleFitStore.originalNameOf(file);

                final byte[] data;
                try {
                    data = FileUtils.readAll(file);
                } catch (final IOException e) {
                    LOG.error("Beagle Fit: cannot read queued {}, dropping it", file, e);
                    BeagleFitStore.remove(file);
                    continue;
                }
                if (data.length > BeagleFitApi.MAX_FILE_BYTES) {
                    finish(context, file, sha, "Skipped " + name + ": bigger than 1 MB");
                    continue;
                }

                final BeagleFitApi.UploadResult result = api.upload(baseUrl, key, name, data);
                LOG.info("Beagle Fit: upload {} -> {}", name, result);
                switch (result.outcome) {
                    case SENT:
                        sent++;
                        finish(context, file, sha, (result.dup ? "Already on beagle.fit: " : "Sent ") + name);
                        moreLeft = true; // files queued meanwhile go in this run too
                        break;
                    case SKIP:
                        finish(context, file, sha, "Skipped " + name + ": " + result.message);
                        break;
                    case UNLINKED:
                        BeagleFitStore.forgetLink(context, true);
                        BeagleFitStore.setLastResult(context, result.message);
                        BeagleFit.notifyUnlinked(context);
                        return Result.success();
                    case KEEP:
                        keptThisRun.add(sha);
                        BeagleFitStore.setLastResult(context, "Not sent " + name + " (" + result.message + "), will try again later");
                        break;
                    case RETRY:
                    default:
                        BeagleFitStore.setLastResult(context, "Waiting to retry: " + result.message
                                + (sent > 0 ? " (" + sent + " sent before that)" : ""));
                        return Result.retry();
                }
            }
        }
        return keptThisRun.isEmpty() ? Result.success() : Result.retry();
    }

    /** The file is done with: remember it so it is never queued again, and take it off the queue. */
    private static void finish(final Context context, final File file, final String sha, final String message) {
        BeagleFitStore.markSent(context, sha);
        BeagleFitStore.remove(file);
        BeagleFitStore.setLastResult(context, message);
    }
}
