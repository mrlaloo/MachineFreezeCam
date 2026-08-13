package com.mrlaloo.machinefreezecam;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 10;
    private ImageView imageView;
    private TextView rateText;
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
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        rateText = new TextView(this);
        rateText.setTextSize(24);
        rateText.setGravity(Gravity.CENTER);
        root.addView(rateText, new LinearLayout.LayoutParams(-1, -2));

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(imageView, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout presets = new LinearLayout(this);
        presets.setGravity(Gravity.CENTER);
        for (int p : new int[]{650, 700, 750, 800}) {
            Button b = new Button(this);
            b.setText(String.valueOf(p));
            b.setOnClickListener(v -> setFpm(Integer.parseInt(((Button)v).getText().toString())));
            presets.addView(b);
        }
        root.addView(presets);

        LinearLayout fine = new LinearLayout(this);
        fine.setGravity(Gravity.CENTER);
        addAdjust(fine, "-10", -10);
        addAdjust(fine, "-1", -1);
        Button hold = new Button(this);
        hold.setText("HOLD");
        hold.setOnClickListener(v -> { holdFrame = !holdFrame; hold.setText(holdFrame ? "RESUME" : "HOLD"); });
        fine.addView(hold);
        addAdjust(fine, "+1", 1);
        addAdjust(fine, "+10", 10);
        root.addView(fine);

        setContentView(root);
        updateRateText();
    }

    private void addAdjust(LinearLayout parent, String label, int amount) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> setFpm(fpm + amount));
        parent.addView(b);
    }

    private void setFpm(int newFpm) {
        fpm = Math.max(30, Math.min(14400, newFpm));
        updateRateText();
    }

    private void updateRateText() {
        double hz = fpm / 60.0;
        rateText.setText("Machine Freeze Cam   " + fpm + " FPM   " + String.format("%.2f Hz", hz));
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
