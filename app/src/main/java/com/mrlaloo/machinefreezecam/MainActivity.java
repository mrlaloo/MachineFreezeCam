package com.mrlaloo.machinefreezecam;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 10;
    private ImageView imageView;
    private TextView rpmText;
    private TextView hzText;
    private Button holdButton;
    private int fpm = 750;
    private volatile long lastShownNs = 0;
    private volatile boolean holdFrame = false;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackgroundColor(Color.rgb(18, 18, 18));
        root.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("Machine Freeze Cam");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(20), 0, 0, 0);
        title.setBackground(round(0xB0000000, 0));
        root.addView(title, new FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP));

        TextView crosshair = new TextView(this);
        crosshair.setText("+");
        crosshair.setTextColor(0x88FFFFFF);
        crosshair.setTextSize(34);
        crosshair.setGravity(Gravity.CENTER);
        root.addView(crosshair, new FrameLayout.LayoutParams(dp(70), dp(70), Gravity.CENTER));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(10));
        panel.setBackground(round(0xE6000000, dp(24)));

        LinearLayout readout = new LinearLayout(this);
        readout.setGravity(Gravity.CENTER | Gravity.BOTTOM);
        rpmText = new TextView(this);
        rpmText.setTextColor(Color.WHITE);
        rpmText.setTextSize(44);
        rpmText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        rpmText.setGravity(Gravity.CENTER);
        readout.addView(rpmText, new LinearLayout.LayoutParams(-2, dp(58)));

        TextView unit = new TextView(this);
        unit.setText(" RPM");
        unit.setTextColor(Color.LTGRAY);
        unit.setTextSize(14);
        unit.setGravity(Gravity.BOTTOM);
        unit.setPadding(0, 0, 0, dp(9));
        readout.addView(unit, new LinearLayout.LayoutParams(-2, dp(58)));
        panel.addView(readout, new LinearLayout.LayoutParams(-1, dp(60)));

        hzText = new TextView(this);
        hzText.setTextColor(Color.LTGRAY);
        hzText.setTextSize(13);
        hzText.setGravity(Gravity.CENTER);
        panel.addView(hzText, new LinearLayout.LayoutParams(-1, dp(24)));

        LinearLayout adjust = new LinearLayout(this);
        adjust.setGravity(Gravity.CENTER);
        addColumn(adjust, "+100", -100, "+", 100);
        addColumn(adjust, "+10", -10, "+", 10);
        addColumn(adjust, "+1", -1, "+", 1);
        addColumn(adjust, "x2", 0, "x", 2);
        panel.addView(adjust, new LinearLayout.LayoutParams(-1, dp(120)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);

        Button half = action("/2");
        half.setOnClickListener(v -> setFpm(Math.max(30, fpm / 2)));
        actions.addView(half, actionParams());

        holdButton = action("II");
        holdButton.setTextSize(22);
        holdButton.setOnClickListener(v -> {
            holdFrame = !holdFrame;
            holdButton.setText(holdFrame ? ">" : "II");
        });
        actions.addView(holdButton, actionParams());

        Button reset = action("750");
        reset.setTextSize(13);
        reset.setOnClickListener(v -> setFpm(750));
        actions.addView(reset, actionParams());

        Button doubleRate = action("x2");
        doubleRate.setTextSize(14);
        doubleRate.setOnClickListener(v -> setFpm(fpm * 2));
        actions.addView(doubleRate, actionParams());

        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView hint = new TextView(this);
        hint.setText("Adjust until the moving part appears stationary");
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, dp(28)));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM);
        panelParams.setMargins(dp(10), 0, dp(10), dp(10));
        root.addView(panel, panelParams);

        setContentView(root);
        updateRateText();
    }

    private void addColumn(LinearLayout row, String topLabel, int minus, String plusLabel, int plus) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        row.addView(col, new LinearLayout.LayoutParams(0, -1, 1f));

        Button up = smallButton(topLabel);
        if (topLabel.equals("x2")) up.setOnClickListener(v -> setFpm(fpm * 2));
        else up.setOnClickListener(v -> setFpm(fpm + plus));
        col.addView(up, smallParams());

        Button down = smallButton(minus == 0 ? "/2" : String.valueOf(minus));
        if (minus == 0) down.setOnClickListener(v -> setFpm(Math.max(30, fpm / 2)));
        else down.setOnClickListener(v -> setFpm(fpm + minus));
        col.addView(down, smallParams());
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(round(0xAA333333, dp(18)));
        return b;
    }

    private LinearLayout.LayoutParams smallParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(68), dp(48));
        p.setMargins(dp(4), dp(3), dp(4), dp(3));
        return p;
    }

    private Button action(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(round(0xDD292929, dp(28)));
        return b;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(56));
        p.setMargins(dp(8), 0, dp(8), 0);
        return p;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setFpm(int newFpm) {
        fpm = Math.max(30, Math.min(14400, newFpm));
        updateRateText();
    }

    private void updateRateText() {
        double hz = fpm / 60.0;
        rpmText.setText(String.valueOf(fpm));
        hzText.setText(String.format(Locale.US, "%.2f Hz   -   %.2f ms/frame", hz, 1000.0 / hz));
    }

    private void startCamera() {
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String selected = null;
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { selected = id; break; }
            }
            if (selected == null && manager.getCameraIdList().length > 0) selected = manager.getCameraIdList()[0];
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image == null) return;
                try {
                    long now = System.nanoTime();
                    long period = (long)(60_000_000_000.0 / fpm);
                    if (!holdFrame && now - lastShownNs >= period) {
                        lastShownNs = now;
                        Bitmap bmp = imageToBitmap(image);
                        if (bmp != null) runOnUiThread(() -> imageView.setImageBitmap(bmp));
                    }
                } finally { image.close(); }
            }, cameraHandler);
            manager.openCamera(selected, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) { cameraDevice = camera; createSession(); }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); }
            }, cameraHandler);
        } catch (Exception e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void createSession() {
        try {
            Surface surface = imageReader.getSurface();
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(surface);
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                    } catch (CameraAccessException ignored) {}
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {}
            }, cameraHandler);
        } catch (CameraAccessException ignored) {}
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            byte[] nv21 = yuv420ToNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
            byte[] jpeg = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Exception e) { return null; }
    }

    private byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] out = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        int pos = 0;
        ByteBuffer y = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) out[pos++] = y.get(row * yRowStride + col * yPixelStride);
        }
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int index = row * uvRowStride + col * uvPixelStride;
                out[pos++] = v.get(index);
                out[pos++] = u.get(index);
            }
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (captureSession != null) captureSession.close();
        if (cameraDevice != null) cameraDevice.close();
        if (imageReader != null) imageReader.close();
        if (cameraThread != null) cameraThread.quitSafely();
    }
}
