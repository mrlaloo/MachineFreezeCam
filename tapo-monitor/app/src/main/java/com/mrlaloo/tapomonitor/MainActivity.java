package com.mrlaloo.tapomonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

public class MainActivity extends Activity {
    private static final String PREFS = "tapo_monitor";
    private static final String DEFAULT_HOST = "192.168.0.13";
    private static final long WATCHDOG_INTERVAL_MS = 2000L;
    private static final long STALL_TIMEOUT_MS = 7000L;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private PlayerView playerView;
    private TextView statusView;
    private Button setupButton;
    private ExoPlayer player;
    private WifiManager.WifiLock wifiLock;

    private boolean destroyed = false;
    private boolean reconnectScheduled = false;
    private int retryCount = 0;
    private int playerGeneration = 0;
    private long lastAttemptMs = 0L;
    private long lastHealthyMs = 0L;

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            reconnectScheduled = false;
            if (!destroyed && hasCredentials()) {
                buildPlayer();
            }
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;

            if (player != null && !reconnectScheduled) {
                long now = SystemClock.elapsedRealtime();
                int state = player.getPlaybackState();

                if (state == Player.STATE_READY && player.isPlaying()) {
                    lastHealthyMs = now;
                    if (retryCount != 0) retryCount = 0;
                    setStatus("LIVE");
                } else if (state == Player.STATE_BUFFERING) {
                    if (now - Math.max(lastHealthyMs, lastAttemptMs) >= STALL_TIMEOUT_MS) {
                        scheduleReconnect("Camera stalled — reconnecting");
                    }
                } else if (state == Player.STATE_IDLE) {
                    if (now - lastAttemptMs >= STALL_TIMEOUT_MS) {
                        scheduleReconnect("Camera idle — reconnecting");
                    }
                } else if (state == Player.STATE_ENDED) {
                    scheduleReconnect("Camera disconnected — reconnecting");
                } else if (state == Player.STATE_READY && !player.isPlaying()) {
                    if (now - Math.max(lastHealthyMs, lastAttemptMs) >= STALL_TIMEOUT_MS) {
                        scheduleReconnect("Video paused — reconnecting");
                    }
                }
            }

            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        acquireWifiLock();
        enterImmersiveMode();
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);

        if (hasCredentials()) {
            startStream();
        } else {
            setStatus("SETUP REQUIRED");
            showSetupDialog();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16f);
        statusView.setBackgroundColor(0x66000000);
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        statusParams.setMargins(dp(12), dp(12), 0, 0);
        root.addView(statusView, statusParams);

        setupButton = new Button(this);
        setupButton.setText("SETUP");
        setupButton.setAllCaps(false);
        setupButton.setFocusable(true);
        setupButton.setOnClickListener(v -> showSetupDialog());
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dp(110), dp(52), Gravity.TOP | Gravity.END);
        buttonParams.setMargins(0, dp(8), dp(10), 0);
        root.addView(setupButton, buttonParams);

        setContentView(root);
    }

    private void startStream() {
        handler.removeCallbacks(reconnectRunnable);
        reconnectScheduled = false;
        retryCount = 0;
        releasePlayer();
        buildPlayer();
    }

    private void buildPlayer() {
        if (destroyed || !hasCredentials()) return;

        releasePlayer();
        final int generation = ++playerGeneration;
        lastAttemptMs = SystemClock.elapsedRealtime();
        lastHealthyMs = lastAttemptMs;

        String host = prefs.getString("host", DEFAULT_HOST).trim();
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");
        boolean lowQuality = prefs.getBoolean("low_quality", true);
        String stream = lowQuality ? "stream2" : "stream1";

        String safeUser = Uri.encode(user);
        String safePass = Uri.encode(pass);
        String uri = "rtsp://" + safeUser + ":" + safePass + "@" + host + ":554/" + stream;

        setStatus("Connecting to " + host);

        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (generation != playerGeneration || destroyed) return;

                    if (playbackState == Player.STATE_READY) {
                        lastHealthyMs = SystemClock.elapsedRealtime();
                        retryCount = 0;
                        setStatus("LIVE");
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        setStatus("Connecting to camera");
                    } else if (playbackState == Player.STATE_ENDED) {
                        scheduleReconnect("Camera disconnected — reconnecting");
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (generation != playerGeneration || destroyed) return;
                    if (isPlaying) {
                        lastHealthyMs = SystemClock.elapsedRealtime();
                        retryCount = 0;
                        setStatus("LIVE");
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (generation != playerGeneration || destroyed) return;

                    String message = error.getMessage() == null ? "" : error.getMessage();
                    Throwable cause = error.getCause();
                    if (cause != null && cause.getMessage() != null) {
                        message += " " + cause.getMessage();
                    }
                    String lower = message.toLowerCase();

                    if (lower.contains("401") || lower.contains("unauthor") || lower.contains("forbidden")) {
                        handler.removeCallbacks(reconnectRunnable);
                        reconnectScheduled = false;
                        releasePlayer();
                        setStatus("Camera login rejected — press SETUP");
                    } else {
                        scheduleReconnect("Camera disconnected — reconnecting");
                    }
                }
            });

            MediaItem item = MediaItem.fromUri(uri);
            RtspMediaSource source = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(10000L)
                    .createMediaSource(item);

            player.setMediaSource(source);
            player.prepare();
            player.play();
        } catch (Exception e) {
            scheduleReconnect("Connection failed — reconnecting");
        }
    }

    private void scheduleReconnect(String status) {
        if (destroyed || reconnectScheduled || !hasCredentials()) return;

        reconnectScheduled = true;
        setStatus(status);
        ++playerGeneration;
        releasePlayer();

        long delayMs = Math.min(5000L, 1200L + (long) retryCount * 700L);
        retryCount = Math.min(retryCount + 1, 10);
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, delayMs);
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (playerView != null) playerView.setPlayer(null);
    }

    private boolean hasCredentials() {
        String user = prefs == null ? "" : prefs.getString("user", "");
        String pass = prefs == null ? "" : prefs.getString("pass", "");
        return user != null && user.trim().length() >= 1 && pass != null && pass.length() >= 1;
    }

    private void showSetupDialog() {
        final LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);

        final EditText hostInput = new EditText(this);
        hostInput.setHint("Camera IP");
        hostInput.setSingleLine(true);
        hostInput.setText(prefs.getString("host", DEFAULT_HOST));
        form.addView(hostInput);

        final EditText userInput = new EditText(this);
        userInput.setHint("Camera username");
        userInput.setSingleLine(true);
        userInput.setText(prefs.getString("user", ""));
        form.addView(userInput);

        final EditText passInput = new EditText(this);
        passInput.setHint("Camera password");
        passInput.setSingleLine(true);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setText(prefs.getString("pass", ""));
        form.addView(passInput);

        final CheckBox low = new CheckBox(this);
        low.setText("Use lower-bandwidth stream");
        low.setChecked(prefs.getBoolean("low_quality", true));
        form.addView(low);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tapo Monitor Setup")
                .setMessage("Camera IP is already filled in. Enter the Camera Account username and password you created in Tapo.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save & Start", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            String user = userInput.getText().toString().trim();
            String pass = passInput.getText().toString();

            if (host.isEmpty()) {
                hostInput.setError("Enter camera IP");
                hostInput.requestFocus();
                return;
            }
            if (user.length() < 1) {
                userInput.setError("Enter camera username");
                userInput.requestFocus();
                return;
            }
            if (pass.length() < 1) {
                passInput.setError("Enter camera password");
                passInput.requestFocus();
                return;
            }

            prefs.edit()
                    .putString("host", host)
                    .putString("user", user)
                    .putString("pass", pass)
                    .putBoolean("low_quality", low.isChecked())
                    .apply();

            dialog.dismiss();
            startStream();
        }));

        dialog.show();
    }

    private void acquireWifiLock() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager != null) {
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tapomonitor:rtsp");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
        wifiLock = null;
    }

    private void setStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    public void onBackPressed() {
        showSetupDialog();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        releaseWifiLock();
        super.onDestroy();
    }
}
