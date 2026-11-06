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
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;

/**
 * The Beagle Fit screen: link this phone with a code from beagle.fit, see what is waiting and what
 * was sent last, queue the last 30 days, and change the server (for testing against a local one).
 */
public class BeagleFitActivity extends AbstractGBActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView status;
    private View linkGroup;
    private EditText code;
    private Button linkButton;
    private Button unlinkButton;
    private TextView queue;
    private TextView last;
    private EditText server;
    private Button sendRecent;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_beaglefit);

        status = findViewById(R.id.beaglefit_status);
        linkGroup = findViewById(R.id.beaglefit_link_group);
        code = findViewById(R.id.beaglefit_code);
        linkButton = findViewById(R.id.beaglefit_link);
        unlinkButton = findViewById(R.id.beaglefit_unlink);
        queue = findViewById(R.id.beaglefit_queue);
        last = findViewById(R.id.beaglefit_last);
        server = findViewById(R.id.beaglefit_server);
        sendRecent = findViewById(R.id.beaglefit_send_recent);

        server.setText(BeagleFitStore.baseUrl(this));

        linkButton.setOnClickListener(v -> link());
        unlinkButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.beaglefit_unlink)
                .setMessage(R.string.beaglefit_unlink_confirm)
                .setPositiveButton(R.string.beaglefit_unlink, (d, w) -> {
                    BeagleFitStore.unlinkByHand(this);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        sendRecent.setOnClickListener(v -> sendRecent());
        findViewById(R.id.beaglefit_send_now).setOnClickListener(v -> {
            BeagleFit.scheduleUpload(this, true);
            refresh();
        });
        findViewById(R.id.beaglefit_server_save).setOnClickListener(v -> saveServer());
    }

    @Override
    protected void onResume() {
        super.onResume();
        getSharedPreferences("beaglefit", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener);
        refresh();
    }

    @Override
    protected void onPause() {
        getSharedPreferences("beaglefit", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void refresh() {
        final boolean linked = BeagleFitStore.isLinked(this);
        if (linked) {
            final String email = BeagleFitStore.email(this);
            final String phone = BeagleFitStore.phoneName(this);
            status.setText(getString(R.string.beaglefit_linked,
                    email != null ? email : "your account",
                    phone != null ? phone : Build.MODEL));
        } else if (BeagleFitStore.wasUnlinkedByServer(this)) {
            status.setText(R.string.beaglefit_unlinked_by_server);
        } else {
            status.setText(R.string.beaglefit_not_linked);
        }
        linkGroup.setVisibility(linked ? View.GONE : View.VISIBLE);
        unlinkButton.setVisibility(linked ? View.VISIBLE : View.GONE);

        queue.setText(getString(R.string.beaglefit_queue, BeagleFitStore.queueLength(this), BeagleFitStore.sentCount(this)));
        final String lastResult = BeagleFitStore.lastResult(this);
        if (lastResult == null) {
            last.setText(R.string.beaglefit_last_none);
        } else {
            final CharSequence when = DateUtils.getRelativeDateTimeString(this, BeagleFitStore.lastResultTime(this),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0);
            last.setText(getString(R.string.beaglefit_last, lastResult, when));
        }
    }

    private void link() {
        final String typed = BeagleFitApi.normalizeCode(code.getText().toString());
        if (typed.length() != 9) {
            Toast.makeText(this, R.string.beaglefit_code_missing, Toast.LENGTH_LONG).show();
            return;
        }
        saveServerQuietly();
        final String baseUrl = BeagleFitStore.baseUrl(this);
        final Context app = getApplicationContext();
        linkButton.setEnabled(false);
        linkButton.setText(R.string.beaglefit_linking);
        executor.execute(() -> {
            final BeagleFitApi.LinkResult result = new BeagleFitApi().link(baseUrl, typed, Build.MODEL);
            if (result.ok) {
                BeagleFitStore.saveLink(app, result);
                NotificationManagerCompat.from(app).cancel(BeagleFit.NOTIFICATION_ID_UNLINKED);
                BeagleFit.scheduleUpload(app, true);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                linkButton.setEnabled(true);
                linkButton.setText(R.string.beaglefit_link);
                if (result.ok) {
                    code.setText("");
                    Toast.makeText(this, getString(R.string.beaglefit_link_done,
                            result.email != null ? result.email : baseUrl), Toast.LENGTH_LONG).show();
                } else {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.beaglefit_title)
                            .setMessage(result.error)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                refresh();
            });
        });
    }

    private void sendRecent() {
        final Context app = getApplicationContext();
        sendRecent.setEnabled(false);
        executor.execute(() -> {
            final int queued = BeagleFit.queueRecent(app, 30);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                sendRecent.setEnabled(true);
                Toast.makeText(this, getString(R.string.beaglefit_send_recent_done, queued), Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    /** A key is only good on the server that made it, so a new server address forgets the link. */
    private void saveServer() {
        final String url = BeagleFitApi.normalizeBaseUrl(server.getText().toString());
        if (!url.equals(BeagleFitStore.baseUrl(this)) && BeagleFitStore.isLinked(this)) {
            BeagleFitStore.forgetLink(this, false);
        }
        BeagleFitStore.setBaseUrl(this, url);
        server.setText(url);
        Toast.makeText(this, getString(R.string.beaglefit_server_saved, url), Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void saveServerQuietly() {
        final String url = BeagleFitApi.normalizeBaseUrl(server.getText().toString());
        if (!url.equals(BeagleFitStore.baseUrl(this))) {
            BeagleFitStore.setBaseUrl(this, url);
            server.setText(url);
        }
    }
}
