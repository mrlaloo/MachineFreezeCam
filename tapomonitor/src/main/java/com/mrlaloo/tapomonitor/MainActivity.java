package com.mrlaloo.tapomonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private SharedPreferences prefs;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView statusView;
    private Button setupButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retryCount = 0;
    private boolean reconnectScheduled = false;

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            reconnectScheduled = false;
            startStream();
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && hasCredentials()) {
                int state = player.getPlaybackState();
                if (!player.isPlaying() && state != Player.STATE_BUFFERING) {
                    scheduleReconnect(1000);
                }
            }
            handler.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        buildUi();
        buildPlayer();

        handler.post(watchdogRunnable);

        if (hasCredentials()) {
            startStream();
        } else {
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
        statusView.setBackgroundColor(0x99000000);
        statusView.setPadding(dp(12), dp(8), dp(12), dp(8));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.TOP | Gravity.START;
        statusParams.setMargins(dp(14), dp(14), 0, 0);
        root.addView(statusView, statusParams);

        setupButton = new Button(this);
        setupButton.setText("SETUP");
        setupButton.setTextSize(14f);
        setupButton.setAllCaps(true);
        setupButton.setOnClickListener(v -> showSetupDialog());
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dp(110), dp(52));
        buttonParams.gravity = Gravity.TOP | Gravity.END;
        buttonParams.setMargins(0, dp(14), dp(14), 0);
        root.addView(setupButton, buttonParams);

        setContentView(root);
    }

    private void buildPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    setStatus("Connecting to camera…");
                } else if (playbackState == Player.STATE_READY) {
                    retryCount = 0;
                    String host = prefs.getString("host", DEFAULT_HOST);
                    setStatus("LIVE  •  " + host);
                    handler.postDelayed(() -> {
                        if (player != null && player.isPlaying()) {
                            statusView.setVisibility(View.GONE);
                            setupButton.setVisibility(View.GONE);
                        }
                    }, 5000);
                } else if (playbackState == Player.STATE_ENDED) {
                    scheduleReconnect(1000);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                String message = error.getMessage() == null ? "" : error.getMessage();
                String lower = message.toLowerCase();
                statusView.setVisibility(View.VISIBLE);
                setupButton.setVisibility(View.VISIBLE);
                if (lower.contains("401") || lower.contains("unauthor") || lower.contains("forbidden")) {
                    setStatus("Camera login rejected — press SETUP");
                    return;
                }
                retryCount++;
                long delay = Math.min(15000, 2000L + (retryCount * 1000L));
                setStatus("Camera disconnected — reconnecting…");
                scheduleReconnect(delay);
            }
        });
    }

    private boolean hasCredentials() {
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");
        return user != null && !user.trim().isEmpty() && pass != null && !pass.isEmpty();
    }

    private void startStream() {
        if (!hasCredentials() || player == null) {
            return;
        }

        handler.removeCallbacks(reconnectRunnable);
        reconnectScheduled = false;
        statusView.setVisibility(View.VISIBLE);
        setupButton.setVisibility(View.VISIBLE);

        String host = prefs.getString("host", DEFAULT_HOST);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");
        int stream = prefs.getBoolean("low_quality", false) ? 2 : 1;

        String safeUser = Uri.encode(user);
        String safePass = Uri.encode(pass);
        Uri uri = Uri.parse("rtsp://" + safeUser + ":" + safePass + "@" + host + ":554/stream" + stream);

        MediaItem item = MediaItem.fromUri(uri);
        RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(item);

        setStatus("Connecting to " + host + "…");
        player.stop();
        player.clearMediaItems();
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
    }

    private void scheduleReconnect(long delayMs) {
        if (!hasCredentials() || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, delayMs);
    }

    private void showSetupDialog() {
        statusView.setVisibility(View.VISIBLE);
        setupButton.setVisibility(View.VISIBLE);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, pad, pad, pad);

        EditText hostInput = new EditText(this);
        hostInput.setHint("Camera IP");
        hostInput.setSingleLine(true);
        hostInput.setText(prefs.getString("host", DEFAULT_HOST));
        form.addView(hostInput, matchWrap());

        EditText userInput = new EditText(this);
        userInput.setHint("Camera username");
        userInput.setSingleLine(true);
        userInput.setText(prefs.getString("user", ""));
        form.addView(userInput, matchWrap());

        EditText passInput = new EditText(this);
        passInput.setHint("Camera password");
        passInput.setSingleLine(true);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setText(prefs.getString("pass", ""));
        form.addView(passInput, matchWrap());

        CheckBox lowQuality = new CheckBox(this);
        lowQuality.setText("Use lower-bandwidth stream");
        lowQuality.setChecked(prefs.getBoolean("low_quality", false));
        form.addView(lowQuality, matchWrap());

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
            if (user.length() < 6) {
                userInput.setError("Camera username must be at least 6 characters");
                userInput.requestFocus();
                return;
            }
            if (pass.length() < 8) {
                passInput.setError("Camera password must be at least 8 characters");
                passInput.requestFocus();
                return;
            }

            prefs.edit()
                    .putString("host", host)
                    .putString("user", user)
                    .putString("pass", pass)
                    .putBoolean("low_quality", lowQuality.isChecked())
                    .apply();

            retryCount = 0;
            dialog.dismiss();
            startStream();
        }));

        dialog.show();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void setStatus(String text) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(text);
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
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public void onBackPressed() {
        showSetupDialog();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
