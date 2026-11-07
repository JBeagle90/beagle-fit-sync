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

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.health.connect.client.HealthConnectClient;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.ControlCenterv2;
import nodomain.freeyourgadget.gadgetbridge.activities.discovery.DiscoveryActivityV2;

/**
 * Beagle Fit Sync's home screen: beagle.fit itself in a WebView, so the app looks like the site and
 * every change to the site reaches the app with no update. Only the site's own pages load here, and
 * only they get the bridge to the watch (BeagleFitBridge); links to other sites open in the browser.
 * The WebView can't save a file or print by itself, so the bridge asks this screen to.
 */
public class BeagleFitWebActivity extends AppCompatActivity {
    private static final Logger LOG = LoggerFactory.getLogger(BeagleFitWebActivity.class);

    /** The page to open, as the part after "#" (the watch's notification opens "v3/watch"). */
    public static final String EXTRA_HASH = "hash";

    private WebView web;
    private View root;
    private View top;
    private View bottom;
    private View error;
    private BeagleFitBridge bridge;
    /** The server the page came from; the setting changing on the Beagle Fit screen reloads it. */
    private String baseUrl;

    @Nullable private ValueCallback<Uri[]> pendingFiles;
    @Nullable private Consumer<Uri> pendingSave;
    private ActivityResultLauncher<Intent> pickFiles;
    private ActivityResultLauncher<Intent> createFile;
    @Nullable private Consumer<Boolean> pendingWeights;
    private ActivityResultLauncher<Set<String>> askHealth;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The page colors the bars itself, so no grey scrim over the navigation bar.
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        setContentView(R.layout.activity_beaglefit_web);
        root = findViewById(R.id.beaglefit_web_root);
        top = findViewById(R.id.beaglefit_web_top);
        bottom = findViewById(R.id.beaglefit_web_bottom);
        error = findViewById(R.id.beaglefit_web_error);
        web = findViewById(R.id.beaglefit_web);

        // The page fills the space between the status bar and the navigation bar (or the keyboard).
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            final Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, 0);
            setHeight(top, bars.top);
            setHeight(bottom, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        pickFiles = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (pendingFiles == null) return;
            Uri[] uris = null;
            final Intent data = result.getData();
            if (result.getResultCode() == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    uris = new Uri[data.getClipData().getItemCount()];
                    for (int i = 0; i < uris.length; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    uris = new Uri[]{data.getData()};
                }
            }
            pendingFiles.onReceiveValue(uris);
            pendingFiles = null;
        });
        createFile = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            final Consumer<Uri> done = pendingSave;
            pendingSave = null;
            if (done != null) done.accept(result.getResultCode() == RESULT_OK && result.getData() != null ? result.getData().getData() : null);
        });
        // Health Connect's own screen; what was allowed is read back from Health Connect itself.
        askHealth = registerForActivityResult(BeagleFitWeights.permissionContract(), granted -> {
            final Consumer<Boolean> done = pendingWeights;
            pendingWeights = null;
            BeagleFitWeights.afterPermissions(this, on -> {
                if (done != null) done.accept(on);
            });
        });

        baseUrl = BeagleFitStore.baseUrl(this);
        setUpWebView();
        bridge = new BeagleFitBridge(this, web, baseUrl);
        if (!bridge.attach()) {
            LOG.warn("Beagle Fit: this WebView has no message listener, so the page has no Watch tab");
        }

        findViewById(R.id.beaglefit_web_retry).setOnClickListener(v -> {
            error.setVisibility(View.GONE);
            web.reload();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (error.getVisibility() != View.VISIBLE && canGoBack()) {
                    web.goBack();
                } else {
                    moveTaskToBack(true);
                }
            }
        });

        // Gadgetbridge's service keeps the watch connected. Its home screen starts it too, but that
        // screen is opened only for pairing and settings now. Before its welcome screens have asked
        // for the Bluetooth permissions it would have nothing to connect to.
        if (!GBApplication.getPrefs().getBoolean("first_run", true)) {
            try {
                GBApplication.deviceService().requestDeviceInfo();
            } catch (final Exception e) {
                LOG.warn("Beagle Fit: could not start the device service", e);
            }
        }

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(startUrl(getIntent()));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setUpWebView() {
        final WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // the site keeps its settings and drafts in localStorage
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false); // target="_blank" opens in place, then goes through the client below
        s.setMediaPlaybackRequiresUserGesture(true);
        // The site can tell it's in the app from this as well as from the bridge.
        s.setUserAgentString(s.getUserAgentString() + " BeagleFitSync/" + BuildConfig.VERSION_NAME);
        // The site has a dark theme of its own; Android must not darken the light one.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(final WebView view, final WebResourceRequest request) {
                final Uri uri = request.getUrl();
                if (isSite(uri) || "about".equals(uri.getScheme())) return false;
                openOutside(uri);
                return true;
            }

            @Override
            public void onReceivedError(final WebView view, final WebResourceRequest request, final WebResourceError err) {
                if (request.isForMainFrame()) {
                    LOG.info("Beagle Fit: the page didn't load: {} {}", err.getErrorCode(), err.getDescription());
                    error.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public boolean onRenderProcessGone(final WebView view, final RenderProcessGoneDetail detail) {
                // The page's process died (out of memory, say): start the screen again with a new WebView.
                LOG.warn("Beagle Fit: the WebView's renderer is gone");
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                view.destroy();
                recreate();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(final WebView view, final ValueCallback<Uri[]> callback, final FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                // Any file: Android has no type for .fit files, so the site's accept list can't be used.
                final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    pickFiles.launch(intent);
                } catch (final ActivityNotFoundException e) {
                    pendingFiles = null;
                    return false;
                }
                return true;
            }
        });
    }

    @NonNull
    private String startUrl(@Nullable final Intent intent) {
        final String hash = intent != null ? intent.getStringExtra(EXTRA_HASH) : null;
        return baseUrl + "/" + (hash != null && !hash.isEmpty() ? "#" + hash : "");
    }

    @Override
    protected void onNewIntent(@NonNull final Intent intent) {
        super.onNewIntent(intent);
        if (intent.getStringExtra(EXTRA_HASH) != null) web.loadUrl(startUrl(intent));
    }

    @Override
    protected void onStart() {
        super.onStart();
        bridge.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        // A new server address on the Beagle Fit screen: the page, its sign-in and the bridge's
        // origin all belong to the old one.
        if (!Objects.equals(baseUrl, BeagleFitStore.baseUrl(this))) recreate();
        // Health Connect always lets an app read while it's open: new weigh-ins come in now.
        else if (BeagleFitWeights.isOn(this)) BeagleFitWeights.collectAsync(this);
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush(); // the sign-in survives the app being closed
        super.onPause();
    }

    @Override
    protected void onStop() {
        bridge.stop();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        bridge.close();
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        web.destroy();
        super.onDestroy();
    }

    /**
     * Back goes back through the site's pages. Not to the bare address the app opened on, though:
     * the site sends that straight on to Today, so back would never leave.
     */
    private boolean canGoBack() {
        final WebBackForwardList list = web.copyBackForwardList();
        final int at = list.getCurrentIndex();
        if (at >= 2) return true;
        return at == 1 && Uri.parse(list.getItemAtIndex(0).getUrl()).getFragment() != null;
    }

    private boolean isSite(@NonNull final Uri uri) {
        final Uri base = Uri.parse(baseUrl);
        return Objects.equals(uri.getScheme(), base.getScheme())
                && Objects.equals(uri.getHost(), base.getHost())
                && uri.getPort() == base.getPort();
    }

    private void openOutside(@NonNull final Uri uri) {
        final String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"mailto".equals(scheme)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.beaglefit_web_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private static void setHeight(@NonNull final View view, final int height) {
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            view.setLayoutParams(lp);
        }
    }

    // ---------- asked for by the page, through the bridge

    /** The page's colors behind the status bar (top) and the navigation bar (bottom). */
    void applyTheme(final int topColor, final int bottomColor, final boolean dark) {
        top.setBackgroundColor(topColor);
        bottom.setBackgroundColor(bottomColor);
        root.setBackgroundColor(topColor);
        final WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!dark && isLight(topColor));
        bars.setAppearanceLightNavigationBars(!dark && isLight(bottomColor));
    }

    private static boolean isLight(final int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 150;
    }

    /** Adding a watch: Gadgetbridge's welcome and permissions the first time, then its search for watches. */
    void openPairing() {
        if (GBApplication.getPrefs().getBoolean("first_run", true)) {
            startActivity(new Intent(this, ControlCenterv2.class));
        } else {
            startActivity(new Intent(this, DiscoveryActivityV2.class));
        }
    }

    /** Gadgetbridge's home screen: the watch's card, its settings, and the Beagle Fit screen in the menu. */
    void openWatchSettings() {
        startActivity(new Intent(this, ControlCenterv2.class));
    }

    /** Health Connect's screen asking to read weight and body fat. done gets whether reading weight is allowed. */
    void askWeights(@NonNull final Consumer<Boolean> done) {
        if (pendingWeights != null) pendingWeights.accept(false);
        pendingWeights = done;
        try {
            askHealth.launch(BeagleFitWeights.permissionsToAsk(this));
        } catch (final ActivityNotFoundException e) {
            pendingWeights = null;
            done.accept(false);
        }
    }

    /** Health Connect's settings (where its permissions for each app are), or its Play listing to install or update it. */
    void openHealthConnect() {
        try {
            if ("ok".equals(BeagleFitWeights.availability(this))) {
                startActivity(new Intent(HealthConnectClient.getHealthConnectSettingsAction()));
                return;
            }
        } catch (final ActivityNotFoundException e) {
            // falls through to Google Play
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")));
        } catch (final ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")));
        }
    }

    /** Android's save screen, then the text written to the file. done gets null on success, else why not. */
    void saveFile(@NonNull final String name, @NonNull final String text, @NonNull final String mime,
                  @NonNull final Consumer<String> done) {
        if (pendingSave != null) pendingSave.accept(null);
        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, name);
        pendingSave = uri -> {
            if (uri == null) {
                done.accept("declined");
                return;
            }
            new Thread(() -> {
                String why = null;
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("no stream");
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                } catch (final Exception e) {
                    LOG.warn("Beagle Fit: could not save {}", name, e);
                    why = getString(R.string.beaglefit_web_save_failed);
                }
                final String result = why;
                runOnUiThread(() -> done.accept(result));
            }, "beaglefit-save").start();
        };
        try {
            createFile.launch(intent);
        } catch (final ActivityNotFoundException e) {
            pendingSave = null;
            done.accept(getString(R.string.beaglefit_web_save_failed));
        }
    }

    /** Prints the page as it is now (Save as PDF is one of Android's printers); done once the dialog is. */
    void print(@NonNull final String title, @NonNull final Runnable done) {
        final PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            done.run();
            return;
        }
        final PrintDocumentAdapter inner = web.createPrintDocumentAdapter(title);
        final PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
            @Override
            public void onStart() {
                inner.onStart();
            }

            @Override
            public void onLayout(final PrintAttributes oldAttributes, final PrintAttributes newAttributes,
                                 final CancellationSignal cancellationSignal, final LayoutResultCallback callback,
                                 final Bundle extras) {
                inner.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras);
            }

            @Override
            public void onWrite(final PageRange[] pages, final ParcelFileDescriptor destination,
                                final CancellationSignal cancellationSignal, final WriteResultCallback callback) {
                inner.onWrite(pages, destination, cancellationSignal, callback);
            }

            @Override
            public void onFinish() {
                inner.onFinish();
                done.run();
            }
        };
        printManager.print(title, adapter, new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
                .build());
    }
}
