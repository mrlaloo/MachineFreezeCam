package com.mrlaloo.machinefreezecam;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
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
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Surface;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 10;
    private ImageView imageView;
    private TextView rateText;
    private Button recordButton;
    private int fpm = 750;
    private volatile long lastShownNs = 0;
    private volatile boolean holdFrame = false;
    private volatile boolean recording = false;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private MediaRecorder mediaRecorder;
    private Surface recorderSurface;
    private Uri recordingUri;
    private ParcelFileDescriptor recordingFd;

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

        recordButton = new Button(this);
        recordButton.setText("RECORD VIDEO");
        recordButton.setOnClickListener(v -> {
            if (recording) stopRecording(); else startRecording();
        });
        root.addView(recordButton, new LinearLayout.LayoutParams(-1, -2));

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
        String suffix = recording ? "   REC" : "";
        rateText.setText("Machine Freeze Cam   " + fpm + " FPM   " + String.format(Locale.US, "%.2f Hz", hz) + suffix);
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
                @Override public void onOpened(CameraDevice camera) { cameraDevice = camera; createSession(false); }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); }
            }, cameraHandler);
        } catch (Exception e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void createSession(boolean startRecorderWhenReady) {
        if (cameraDevice == null || imageReader == null) return;
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            Surface previewSurface = imageReader.getSurface();
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            if (recording && recorderSurface != null) surfaces.add(recorderSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                                recording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(previewSurface);
                        if (recording && recorderSurface != null) builder.addTarget(recorderSurface);
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        if (startRecorderWhenReady && recording && mediaRecorder != null) {
                            mediaRecorder.start();
                            runOnUiThread(() -> {
                                recordButton.setText("STOP & SAVE");
                                updateRateText();
                                Toast.makeText(MainActivity.this, "Recording started", Toast.LENGTH_SHORT).show();
                            });
                        }
                    } catch (Exception e) {
                        recordingFailed(e);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    recordingFailed(new Exception("Camera session configuration failed"));
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            recordingFailed(e);
        }
    }

    private void startRecording() {
        if (cameraDevice == null || recording) return;
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "MachineFreezeCam_" + stamp + ".mp4";

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MachineFreezeCam");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                recordingUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (recordingUri == null) throw new Exception("Could not create video file");
                recordingFd = getContentResolver().openFileDescriptor(recordingUri, "w");
                if (recordingFd == null) throw new Exception("Could not open video file");
                FileDescriptor fd = recordingFd.getFileDescriptor();
                mediaRecorder.setOutputFile(fd);
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MachineFreezeCam");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create video folder");
                mediaRecorder.setOutputFile(new File(dir, fileName).getAbsolutePath());
            }

            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(640, 480);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(5_000_000);
            mediaRecorder.prepare();
            recorderSurface = mediaRecorder.getSurface();
            recording = true;
            recordButton.setEnabled(false);
            createSession(true);
            recordButton.postDelayed(() -> recordButton.setEnabled(true), 1000);
        } catch (Exception e) {
            recordingFailed(e);
        }
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        try {
            if (captureSession != null) captureSession.stopRepeating();
        } catch (Exception ignored) {}
        try {
            if (mediaRecorder != null) mediaRecorder.stop();
        } catch (Exception e) {
            if (recordingUri != null) getContentResolver().delete(recordingUri, null, null);
        }
        releaseRecorder();
        finalizeMediaStoreVideo();
        recordButton.setText("RECORD VIDEO");
        updateRateText();
        Toast.makeText(this, "Video saved to Movies/MachineFreezeCam", Toast.LENGTH_LONG).show();
        createSession(false);
    }

    private void finalizeMediaStoreVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && recordingUri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(recordingUri, values, null, null);
        }
        recordingUri = null;
    }

    private void releaseRecorder() {
        try { if (recordingFd != null) recordingFd.close(); } catch (Exception ignored) {}
        recordingFd = null;
        recorderSurface = null;
        if (mediaRecorder != null) {
            try { mediaRecorder.reset(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
    }

    private void recordingFailed(Exception e) {
        recording = false;
        releaseRecorder();
        if (recordingUri != null) {
            try { getContentResolver().delete(recordingUri, null, null); } catch (Exception ignored) {}
            recordingUri = null;
        }
        runOnUiThread(() -> {
            if (recordButton != null) {
                recordButton.setEnabled(true);
                recordButton.setText("RECORD VIDEO");
            }
            updateRateText();
            Toast.makeText(MainActivity.this, "Recording error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
        if (cameraDevice != null && imageReader != null) createSession(false);
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
        if (recording) stopRecording();
        super.onDestroy();
        if (captureSession != null) captureSession.close();
        if (cameraDevice != null) cameraDevice.close();
        if (imageReader != null) imageReader.close();
        if (cameraThread != null) cameraThread.quitSafely();
    }
}
