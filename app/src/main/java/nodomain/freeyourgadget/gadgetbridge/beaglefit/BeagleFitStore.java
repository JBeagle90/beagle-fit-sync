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
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;

/**
 * Everything Beagle Fit Sync keeps on the phone: the server address, the link (key, account email,
 * phone name), the queue of FIT files waiting to go, which files were already sent, and the last
 * result. All of it is in app-private storage.
 *
 * The queue is a folder of copies named "&lt;sha256&gt;-&lt;original name&gt;", so it survives restarts and
 * does not depend on Gadgetbridge keeping its own export files.
 */
public final class BeagleFitStore {
    private static final Logger LOG = LoggerFactory.getLogger(BeagleFitStore.class);

    private static final String PREFS = "beaglefit";
    private static final String SECRET_PREFS = "beaglefit_secret";
    private static final String K_BASE_URL = "base_url";
    private static final String K_KEY = "key";
    private static final String K_EMAIL = "email";
    private static final String K_PHONE_ID = "phone_id";
    private static final String K_PHONE_NAME = "phone_name";
    private static final String K_LAST_RESULT = "last_result";
    private static final String K_LAST_TIME = "last_time";
    private static final String K_SENT = "sent_sha256";
    private static final String K_UNLINKED_BY_SERVER = "unlinked_by_server";
    private static final String K_AUTO_LINK = "auto_link";

    /** A queue this long means uploads have not worked for a long time; drop the oldest. */
    static final int MAX_QUEUE = 300;

    public enum QueueResult { QUEUED, ALREADY_SENT, ALREADY_QUEUED, TOO_BIG, NOT_ACTIVITY, ERROR }

    private static SharedPreferences secretPrefs;

    private BeagleFitStore() {
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The key lives in EncryptedSharedPreferences; if the keystore fails on this phone, plain private prefs. */
    private static synchronized SharedPreferences secret(final Context context) {
        if (secretPrefs == null) {
            final Context app = context.getApplicationContext();
            try {
                final MasterKey masterKey = new MasterKey.Builder(app)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                secretPrefs = EncryptedSharedPreferences.create(app, SECRET_PREFS, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (final Exception e) {
                LOG.warn("EncryptedSharedPreferences unavailable, keeping the key in private prefs", e);
                secretPrefs = app.getSharedPreferences(SECRET_PREFS + "_plain", Context.MODE_PRIVATE);
            }
        }
        return secretPrefs;
    }

    // ---------- settings and link

    @NonNull
    public static String baseUrl(final Context context) {
        return BeagleFitApi.normalizeBaseUrl(prefs(context).getString(K_BASE_URL, BeagleFitApi.DEFAULT_BASE_URL));
    }

    public static void setBaseUrl(final Context context, final String url) {
        prefs(context).edit().putString(K_BASE_URL, BeagleFitApi.normalizeBaseUrl(url)).apply();
    }

    @Nullable
    public static String key(final Context context) {
        final String key = secret(context).getString(K_KEY, null);
        return key == null || key.isEmpty() ? null : key;
    }

    public static boolean isLinked(final Context context) {
        return key(context) != null;
    }

    public static void saveLink(final Context context, final BeagleFitApi.LinkResult link) {
        secret(context).edit().putString(K_KEY, link.key).commit();
        prefs(context).edit()
                .putString(K_EMAIL, link.email)
                .putString(K_PHONE_ID, link.phoneId)
                .putString(K_PHONE_NAME, link.phoneName)
                .putBoolean(K_UNLINKED_BY_SERVER, false)
                .putBoolean(K_AUTO_LINK, true)
                .apply();
    }

    /**
     * Whether beagle.fit's page in the app may link this phone by itself once someone is signed in
     * (js/app.js on the site). Not after the person unlinked it, or the account did, until they
     * link it again.
     */
    public static boolean autoLink(final Context context) {
        return prefs(context).getBoolean(K_AUTO_LINK, true) && !wasUnlinkedByServer(context);
    }

    /** The person unlinked this phone: forget the key, and don't link it again by itself. */
    public static void unlinkByHand(final Context context) {
        forgetLink(context, false);
        prefs(context).edit().putBoolean(K_AUTO_LINK, false).apply();
    }

    @Nullable
    public static String phoneId(final Context context) {
        return prefs(context).getString(K_PHONE_ID, null);
    }

    /** Forgets the key. The queue stays, so linking again sends what was waiting. */
    public static void forgetLink(final Context context, final boolean byServer) {
        secret(context).edit().remove(K_KEY).commit();
        prefs(context).edit()
                .remove(K_EMAIL)
                .remove(K_PHONE_ID)
                .remove(K_PHONE_NAME)
                .putBoolean(K_UNLINKED_BY_SERVER, byServer)
                .apply();
    }

    public static boolean wasUnlinkedByServer(final Context context) {
        return prefs(context).getBoolean(K_UNLINKED_BY_SERVER, false);
    }

    @Nullable
    public static String email(final Context context) {
        return prefs(context).getString(K_EMAIL, null);
    }

    @Nullable
    public static String phoneName(final Context context) {
        return prefs(context).getString(K_PHONE_NAME, null);
    }

    public static void setLastResult(final Context context, final String result) {
        prefs(context).edit()
                .putString(K_LAST_RESULT, result)
                .putLong(K_LAST_TIME, System.currentTimeMillis())
                .apply();
    }

    @Nullable
    public static String lastResult(final Context context) {
        return prefs(context).getString(K_LAST_RESULT, null);
    }

    public static long lastResultTime(final Context context) {
        return prefs(context).getLong(K_LAST_TIME, 0);
    }

    // ---------- sent files

    public static synchronized boolean wasSent(final Context context, final String sha256) {
        return prefs(context).getStringSet(K_SENT, new HashSet<>()).contains(sha256);
    }

    /** Remembers a file as done (sent, or refused for good) so it is never queued again. */
    public static synchronized void markSent(final Context context, final String sha256) {
        final Set<String> sent = new HashSet<>(prefs(context).getStringSet(K_SENT, new HashSet<>()));
        sent.add(sha256);
        prefs(context).edit().putStringSet(K_SENT, sent).commit();
    }

    public static int sentCount(final Context context) {
        return prefs(context).getStringSet(K_SENT, new HashSet<>()).size();
    }

    // ---------- queue

    private static File queueDir(final Context context) {
        final File dir = new File(new File(context.getApplicationContext().getFilesDir(), "beaglefit"), "queue");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Copies an activity FIT file into the queue, unless it was sent or queued before. */
    @NonNull
    public static synchronized QueueResult enqueue(final Context context, final File source) {
        try {
            if (!source.isFile()) {
                return QueueResult.ERROR;
            }
            if (source.length() > BeagleFitApi.MAX_FILE_BYTES) {
                LOG.info("Beagle Fit: not queueing {}, bigger than 1 MB", source);
                return QueueResult.TOO_BIG;
            }
            final byte[] data = FileUtils.readAll(source);
            if (!FitActivityCheck.isActivity(data)) {
                return QueueResult.NOT_ACTIVITY;
            }
            final String sha = sha256(data);
            if (wasSent(context, sha)) {
                return QueueResult.ALREADY_SENT;
            }
            final File dir = queueDir(context);
            final String[] existing = dir.list();
            if (existing != null) {
                for (final String name : existing) {
                    if (name.startsWith(sha)) {
                        return QueueResult.ALREADY_QUEUED;
                    }
                }
            }
            final File tmp = new File(dir, sha + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.getFD().sync();
            }
            final File target = new File(dir, sha + "-" + safeName(source.getName()));
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return QueueResult.ERROR;
            }
            //noinspection ResultOfMethodCallIgnored
            target.setLastModified(System.currentTimeMillis());
            trimQueue(dir);
            LOG.info("Beagle Fit: queued {}", target.getName());
            return QueueResult.QUEUED;
        } catch (final IOException | RuntimeException e) {
            LOG.error("Beagle Fit: failed to queue {}", source, e);
            return QueueResult.ERROR;
        }
    }

    private static void trimQueue(final File dir) {
        final List<File> files = queuedFiles(dir);
        for (int i = 0; i < files.size() - MAX_QUEUE; i++) {
            LOG.warn("Beagle Fit: queue full, dropping {}", files.get(i).getName());
            //noinspection ResultOfMethodCallIgnored
            files.get(i).delete();
        }
    }

    /** Queued files, oldest first. */
    @NonNull
    public static synchronized List<File> queuedFiles(final Context context) {
        return queuedFiles(queueDir(context));
    }

    private static List<File> queuedFiles(final File dir) {
        final File[] files = dir.listFiles((d, name) -> name.length() > 65 && name.charAt(64) == '-');
        if (files == null) {
            return new ArrayList<>();
        }
        final List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        return list;
    }

    public static int queueLength(final Context context) {
        return queuedFiles(context).size();
    }

    /** The sha256 a queued file was stored under. */
    @NonNull
    public static String shaOf(final File queued) {
        return queued.getName().substring(0, 64);
    }

    /** The name the file had in Gadgetbridge's export folder, sent to the server. */
    @NonNull
    public static String originalNameOf(final File queued) {
        return queued.getName().substring(65);
    }

    public static synchronized void remove(final File queued) {
        //noinspection ResultOfMethodCallIgnored
        queued.delete();
    }

    static String safeName(final String name) {
        final String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "activity.fit" : cleaned;
    }

    @NonNull
    static String sha256(final byte[] data) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            final StringBuilder sb = new StringBuilder(64);
            for (final byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
