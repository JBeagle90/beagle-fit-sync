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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;

/**
 * The bridge between beagle.fit's page and the app: window.BFApp on the page (js/app.js on the
 * site). A WebMessageListener rather than a JavaScript interface, because it is given only to pages
 * from the site's own origin, and the page can have other sites' frames in it (exercise videos).
 * Messages are JSON both ways: {id, op, …} from the page, {id, …} back, and {status} whenever the
 * link, the queue or a watch changes.
 */
final class BeagleFitBridge implements WebViewCompat.WebMessageListener {
    private static final Logger LOG = LoggerFactory.getLogger(BeagleFitBridge.class);
    private static final Pattern CSS_RGB = Pattern.compile("rgba?\\(\\s*(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)(?:[,\\s/]+([\\d.]+))?");

    private final BeagleFitWebActivity activity;
    private final Context app;
    private final WebView web;
    private final String baseUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The page that wrote last, to send it the status; a reload brings a new one. */
    @Nullable private JavaScriptReplyProxy page;
    private boolean started;

    private final Runnable pushStatus = this::pushStatus;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (prefs, key) -> pushStatusSoon();
    private final BroadcastReceiver deviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            pushStatusSoon();
        }
    };

    BeagleFitBridge(@NonNull final BeagleFitWebActivity activity, @NonNull final WebView web, @NonNull final String baseUrl) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.web = web;
        this.baseUrl = baseUrl;
    }

    /** False on a WebView too old for message listeners: the page then works as on a browser, with no Watch tab. */
    boolean attach() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false;
        final Uri base = Uri.parse(baseUrl);
        final String origin = base.getScheme() + "://" + base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "");
        WebViewCompat.addWebMessageListener(web, "BFApp", Collections.singleton(origin), this);
        return true;
    }

    /** While the screen shows: the page hears about changes. */
    void start() {
        if (started) return;
        started = true;
        app.getSharedPreferences("beaglefit", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(GBDevice.ACTION_DEVICE_CHANGED);
        filter.addAction(DeviceManager.ACTION_DEVICES_CHANGED);
        LocalBroadcastManager.getInstance(app).registerReceiver(deviceReceiver, filter);
        pushStatusSoon(); // whatever changed while it was hidden
    }

    void stop() {
        if (!started) return;
        started = false;
        app.getSharedPreferences("beaglefit", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener);
        LocalBroadcastManager.getInstance(app).unregisterReceiver(deviceReceiver);
        main.removeCallbacks(pushStatus);
    }

    void close() {
        stop();
        executor.shutdown();
        page = null;
    }

    @Override
    public void onPostMessage(@NonNull final WebView view, @NonNull final WebMessageCompat message, @NonNull final Uri sourceOrigin,
                              final boolean isMainFrame, @NonNull final JavaScriptReplyProxy reply) {
        if (!isMainFrame || message.getData() == null) return;
        final JSONObject m;
        try {
            m = new JSONObject(message.getData());
        } catch (final JSONException e) {
            return;
        }
        page = reply;
        final int id = m.optInt("id", 0);
        try {
            handle(m.optString("op"), m, id, reply);
        } catch (final Exception e) {
            LOG.error("Beagle Fit: the page's {} failed", m.optString("op"), e);
            answer(reply, id, error(e.getMessage() != null ? e.getMessage() : "Something went wrong in the app."));
        }
    }

    private void handle(final String op, final JSONObject m, final int id, final JavaScriptReplyProxy reply) throws JSONException {
        switch (op) {
            case "hello":
                answer(reply, id, new JSONObject().put("status", status()));
                return;
            case "theme":
                activity.applyTheme(cssColor(m.optString("top")), cssColor(m.optString("bottom")), m.optBoolean("dark"));
                return;
            case "link": {
                // A one-time code the page made with its session: traded for this phone's own key.
                final String code = BeagleFitApi.normalizeCode(m.optString("code"));
                executor.execute(() -> {
                    final BeagleFitApi.LinkResult result = new BeagleFitApi().link(baseUrl, code, Build.MODEL);
                    if (result.ok) {
                        BeagleFitStore.saveLink(app, result);
                        NotificationManagerCompat.from(app).cancel(BeagleFit.NOTIFICATION_ID_UNLINKED);
                        BeagleFit.scheduleUpload(app, true);
                    }
                    main.post(() -> {
                        try {
                            answer(reply, id, result.ok
                                    ? ok().put("email", result.email).put("status", status())
                                    : new JSONObject().put("ok", false).put("error", result.error));
                        } catch (final JSONException e) {
                            answer(reply, id, error("Couldn't link this phone."));
                        }
                    });
                });
                return;
            }
            case "unlink":
                BeagleFitStore.unlinkByHand(app);
                answer(reply, id, ok().put("status", status()));
                return;
            case "sync": {
                boolean any = false;
                for (final GBDevice device : devices()) any |= device.isInitialized();
                if (!any) {
                    answer(reply, id, error("Your watch isn't connected. Bring it near the phone with Bluetooth on, then try again."));
                    return;
                }
                GBApplication.deviceService().onFetchRecordedData(RecordedDataTypes.TYPE_SYNC);
                answer(reply, id, ok());
                return;
            }
            case "send":
                BeagleFit.scheduleUpload(app, true);
                answer(reply, id, ok());
                return;
            case "recent":
                executor.execute(() -> {
                    final int queued = BeagleFit.queueRecent(app, 30);
                    main.post(() -> {
                        try {
                            answer(reply, id, ok().put("queued", queued));
                        } catch (final JSONException e) {
                            answer(reply, id, okQuietly());
                        }
                    });
                });
                return;
            case "pair":
                activity.openPairing();
                answer(reply, id, ok());
                return;
            case "watch":
                activity.openWatchSettings();
                answer(reply, id, ok());
                return;
            case "save": {
                final String name = m.optString("name", "beagle-fit.json");
                final String mime = m.optString("mime", "application/json");
                activity.saveFile(name, m.optString("text"), mime, why -> {
                    try {
                        if (why == null) answer(reply, id, okQuietly());
                        else if ("declined".equals(why)) answer(reply, id, new JSONObject().put("ok", false).put("declined", true));
                        else answer(reply, id, error(why));
                    } catch (final JSONException e) {
                        answer(reply, id, error("The file couldn't be saved."));
                    }
                });
                return;
            }
            case "weights": {
                // Weigh-ins from Health Connect (BeagleFitWeights): its permission screen, then the last 30 days.
                final String available = BeagleFitWeights.availability(app);
                if (!"ok".equals(available)) {
                    activity.openHealthConnect();
                    answer(reply, id, error("update".equals(available)
                            ? "Health Connect needs an update. Update it on Google Play, then try again."
                            : "This phone doesn't have Health Connect. Install it from Google Play, then try again."));
                    return;
                }
                activity.askWeights(on -> main.post(() -> {
                    try {
                        answer(reply, id, on
                                ? ok().put("status", status())
                                : new JSONObject().put("ok", false).put("declined", true).put("status", status()));
                    } catch (final JSONException e) {
                        answer(reply, id, okQuietly());
                    }
                }));
                return;
            }
            case "weightsNow":
                BeagleFitWeights.collectAsync(app, n -> main.post(() -> {
                    try {
                        answer(reply, id, n < 0
                                ? error("Couldn't read Health Connect. Open the app again and try once more.")
                                : ok().put("queued", n));
                    } catch (final JSONException e) {
                        answer(reply, id, okQuietly());
                    }
                }));
                return;
            case "weightsOff":
                BeagleFitWeights.turnOff(app, () -> main.post(() -> answer(reply, id, okQuietly())));
                return;
            case "health":
                activity.openHealthConnect();
                answer(reply, id, ok());
                return;
            case "print":
                activity.print(m.optString("title", "Beagle Fit"), () -> main.post(() -> answer(reply, id, okQuietly())));
                return;
            default:
                answer(reply, id, error("The app doesn't know \"" + op + "\". Update Beagle Fit Sync."));
        }
    }

    // ---------- status

    /** What the page's Watch tab shows: the link, the queue, each watch, and weigh-ins from Health Connect. */
    @NonNull
    JSONObject status() throws JSONException {
        final JSONObject s = new JSONObject()
                .put("app", BuildConfig.VERSION_NAME)
                .put("linked", BeagleFitStore.isLinked(app))
                .put("email", BeagleFitStore.email(app))
                .put("phone", BeagleFitStore.phoneName(app))
                .put("phoneId", BeagleFitStore.phoneId(app))
                .put("autoLink", BeagleFitStore.autoLink(app))
                .put("unlinkedByServer", BeagleFitStore.wasUnlinkedByServer(app))
                .put("queue", BeagleFitStore.queueLength(app))
                .put("sent", BeagleFitStore.sentCount(app))
                .put("last", BeagleFitStore.lastResult(app))
                .put("lastAt", BeagleFitStore.lastResultTime(app))
                .put("setUp", !GBApplication.getPrefs().getBoolean("first_run", true))
                .put("weights", BeagleFitWeights.status(app));
        final JSONArray watches = new JSONArray();
        for (final GBDevice device : devices()) {
            watches.put(new JSONObject()
                    .put("name", device.getAliasOrName())
                    .put("connected", device.isInitialized())
                    .put("state", device.getStateString(app))
                    .put("battery", device.getBatteryLevel(0))
                    .put("busy", device.isBusy() ? device.getBusyTask() : JSONObject.NULL));
        }
        return s.put("watches", watches);
    }

    @NonNull
    private static List<GBDevice> devices() {
        return GBApplication.app().getDeviceManager().getDevices();
    }

    /** Several changes come together (a sync, then an upload): one message for them. */
    private void pushStatusSoon() {
        main.removeCallbacks(pushStatus);
        main.postDelayed(pushStatus, 300);
    }

    private void pushStatus() {
        final JavaScriptReplyProxy target = page;
        if (target == null || !started) return;
        try {
            target.postMessage(new JSONObject().put("status", status()).toString());
        } catch (final Exception e) {
            LOG.debug("Beagle Fit: could not send the status", e);
        }
    }

    // ---------- answers

    private static JSONObject ok() throws JSONException {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject okQuietly() {
        try {
            return ok();
        } catch (final JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject error(final String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message);
        } catch (final JSONException e) {
            return new JSONObject();
        }
    }

    private void answer(@NonNull final JavaScriptReplyProxy reply, final int id, @NonNull final JSONObject body) {
        if (id <= 0) return;
        try {
            reply.postMessage(body.put("id", id).toString());
        } catch (final Exception e) {
            LOG.debug("Beagle Fit: the page is gone", e); // reloaded or closed while the app worked
        }
    }

    /**
     * "rgb(13, 19, 24)" from the page's getComputedStyle, as a color; the default background when
     * it isn't one, or is see-through.
     */
    static int cssColor(@Nullable final String css) {
        if (css != null) {
            final Matcher m = CSS_RGB.matcher(css.toLowerCase(Locale.ROOT));
            if (m.find()) {
                try {
                    if (m.group(4) != null && Float.parseFloat(m.group(4)) == 0f) return Color.parseColor("#F1F3F5");
                    return Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                } catch (final NumberFormatException e) {
                    // falls through
                }
            }
        }
        return Color.parseColor("#F1F3F5");
    }
}
