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
import android.util.Range;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;
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
    private static final int MIN_RPM = 30;
    private static final int MAX_RPM = 14400;

    private ImageView imageView;
    private TextView rpmText;
    private TextView statusText;
    private TextView cameraText;
    private Button holdButton;
    private Button torchButton;
    private Button sharpButton;

    private volatile int rpm = 750;
    private volatile boolean holdFrame = false;
    private volatile long lastShownNs = 0;
    private volatile long lastCameraFrameNs = 0;
    private volatile double measuredCameraFps = 0.0;
    private volatile int fpsSamples = 0;

    private boolean torchOn = false;
    private boolean sharpMode = false;
    private String cameraId;
    private CameraCharacteristics characteristics;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder requestBuilder;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackgroundColor(Color.rgb(12, 12, 12));
        root.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), 0, dp(12), 0);
        top.setBackgroundColor(0xA8000000);

        TextView title = new TextView(this);
        title.setText("Machine Freeze Tach");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1f));

        cameraText = new TextView(this);
        cameraText.setText("CAM --");
        cameraText.setTextColor(Color.LTGRAY);
        cameraText.setTextSize(12);
        cameraText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        top.addView(cameraText, new LinearLayout.LayoutParams(dp(120), dp(58)));
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP));

        TextView crosshair = new TextView(this);
        crosshair.setText("+");
        crosshair.setTextColor(0x77FFFFFF);
        crosshair.setTextSize(36);
        crosshair.setGravity(Gravity.CENTER);
        root.addView(crosshair, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackground(round(0xE8000000, dp(22)));

        LinearLayout readout = new LinearLayout(this);
        readout.setGravity(Gravity.CENTER | Gravity.BOTTOM);
        rpmText = new TextView(this);
        rpmText.setTextColor(Color.WHITE);
        rpmText.setTextSize(48);
        rpmText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        rpmText.setGravity(Gravity.CENTER);
        readout.addView(rpmText, new LinearLayout.LayoutParams(-2, dp(62)));

        TextView unit = new TextView(this);
        unit.setText(" RPM");
        unit.setTextColor(Color.LTGRAY);
        unit.setTextSize(14);
        unit.setGravity(Gravity.BOTTOM);
        unit.setPadding(0, 0, 0, dp(10));
        readout.addView(unit, new LinearLayout.LayoutParams(-2, dp(62)));
        panel.addView(readout, new LinearLayout.LayoutParams(-1, dp(64)));

        statusText = new TextView(this);
        statusText.setTextColor(Color.LTGRAY);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER);
        panel.addView(statusText, new LinearLayout.LayoutParams(-1, dp(26)));

        LinearLayout coarse = new LinearLayout(this);
        coarse.setGravity(Gravity.CENTER);
        addAdjust(coarse, "-100", -100);
        addAdjust(coarse, "-10", -10);
        addAdjust(coarse, "-1", -1);
        addAdjust(coarse, "+1", 1);
        addAdjust(coarse, "+10", 10);
        addAdjust(coarse, "+100", 100);
        panel.addView(coarse, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout harmonic = new LinearLayout(this);
        harmonic.setGravity(Gravity.CENTER);
        addAction(harmonic, "÷2", () -> setRpm(rpm / 2));
        addAction(harmonic, "×2", () -> setRpm(rpm * 2));
        addAction(harmonic, "650", () -> setRpm(650));
        addAction(harmonic, "700", () -> setRpm(700));
        addAction(harmonic, "750", () -> setRpm(750));
        addAction(harmonic, "800", () -> setRpm(800));
        panel.addView(harmonic, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);

        holdButton = action("HOLD");
        holdButton.setOnClickListener(v -> {
            holdFrame = !holdFrame;
            holdButton.setText(holdFrame ? "LIVE" : "HOLD");
        });
        actions.addView(holdButton, actionParams());

        torchButton = action("LIGHT");
        torchButton.setOnClickListener(v -> {
            torchOn = !torchOn;
            torchButton.setText(torchOn ? "LIGHT ON" : "LIGHT");
            applyCameraRequest();
        });
        actions.addView(torchButton, actionParams());

        sharpButton = action("SHARP");
        sharpButton.setOnClickListener(v -> {
            sharpMode = !sharpMode;
            sharpButton.setText(sharpMode ? "SHARP ON" : "SHARP");
            applyCameraRequest();
        });
        actions.addView(sharpButton, actionParams());

        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView hint = new TextView(this);
        hint.setText("Tune RPM until the repeating machine part appears stationary");
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, dp(24)));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1, dp(298), Gravity.BOTTOM);
        panelParams.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(panel, panelParams);

        setContentView(root);
        updateReadout();
    }

    private void addAdjust(LinearLayout row, String label, int delta) {
        Button b = smallButton(label);
        b.setOnClickListener(v -> setRpm(rpm + delta));
        row.addView(b, smallParams());
    }

    private void addAction(LinearLayout row, String label, Runnable action) {
        Button b = smallButton(label);
        b.setOnClickListener(v -> action.run());
        row.addView(b, smallParams());
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(round(0xB0333333, dp(18)));
        return b;
    }

    private LinearLayout.LayoutParams smallParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p.setMargins(dp(3), dp(4), dp(3), dp(4));
        return p;
    }

    private Button action(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(round(0xDD2B2B2B, dp(24)));
        return b;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1f);
        p.setMargins(dp(6), 0, dp(6), 0);
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

    private void setRpm(int value) {
        rpm = Math.max(MIN_RPM, Math.min(MAX_RPM, value));
        lastShownNs = 0;
        updateReadout();
    }

    private void updateReadout() {
        double hz = rpm / 60.0;
        rpmText.setText(String.valueOf(rpm));
        String warning = measuredCameraFps > 1.0 && hz > measuredCameraFps ? "  •  CAMERA LIMIT" : "";
        statusText.setText(String.format(Locale.US, "%.2f Hz  •  %.2f ms/rev%s", hz, 1000.0 / hz, warning));
        if (measuredCameraFps > 1.0) cameraText.setText(String.format(Locale.US, "CAM %.1f fps", measuredCameraFps));
    }

    private void startCamera() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("tach-camera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    characteristics = c;
                    break;
                }
            }
            if (cameraId == null && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
                characteristics = manager.getCameraCharacteristics(cameraId);
            }
            if (cameraId == null) throw new IllegalStateException("No camera available");

            imageReader = ImageReader.newInstance(960, 720, ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image == null) return;
                try {
                    long ts = image.getTimestamp();
                    if (lastCameraFrameNs != 0 && ts > lastCameraFrameNs) {
                        double instant = 1_000_000_000.0 / (ts - lastCameraFrameNs);
                        if (instant > 1 && instant < 240) {
                            measuredCameraFps = fpsSamples == 0 ? instant : measuredCameraFps * 0.9 + instant * 0.1;
                            fpsSamples++;
                        }
                    }
                    lastCameraFrameNs = ts;

                    long strobePeriod = (long) (60_000_000_000.0 / rpm);
                    if (!holdFrame && (lastShownNs == 0 || ts - lastShownNs >= strobePeriod)) {
                        lastShownNs = ts;
                        Bitmap bmp = imageToBitmap(image);
                        if (bmp != null) runOnUiThread(() -> {
                            imageView.setImageBitmap(bmp);
                            updateReadout();
                        });
                    }
                } finally {
                    image.close();
                }
            }, cameraHandler);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Camera error " + error, Toast.LENGTH_LONG).show());
                }
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
                        requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        requestBuilder.addTarget(surface);
                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        chooseHighestFpsRange();
                        applyCameraRequest();
                    } catch (CameraAccessException e) {
                        showCameraMessage(e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    showCameraMessage("Camera session configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            showCameraMessage(e.getMessage());
        }
    }

    private void chooseHighestFpsRange() {
        if (requestBuilder == null || characteristics == null) return;
        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) return;
        Range<Integer> best = ranges[0];
        for (Range<Integer> r : ranges) {
            if (r.getUpper() > best.getUpper() || (r.getUpper().equals(best.getUpper()) && r.getLower() > best.getLower())) best = r;
        }
        requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
    }

    private void applyCameraRequest() {
        if (requestBuilder == null || captureSession == null) return;
        try {
            requestBuilder.set(CaptureRequest.FLASH_MODE, torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            if (sharpMode && characteristics != null) {
                Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (exposureRange != null && isoRange != null) {
                    long targetExposure = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), 1_000_000L));
                    int targetIso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), 800));
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExposure);
                    requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso);
                }
            } else {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            }
            captureSession.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
        } catch (Exception e) {
            showCameraMessage("Setting not supported on this camera");
        }
    }

    private void showCameraMessage(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message == null ? "Camera error" : message, Toast.LENGTH_SHORT).show());
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            byte[] nv21 = yuv420ToNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 82, out);
            byte[] jpeg = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Exception e) {
            return null;
        }
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
