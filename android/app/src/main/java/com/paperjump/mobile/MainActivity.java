package com.paperjump.mobile;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<BindingData.Anchor> anchors = Collections.emptyList();
    private LocalMatcher matcher;
    private PreviewView preview;
    private MatchOverlay overlay;
    private TextView status;
    private Button openButton;
    private volatile long lastAnalysis;
    private volatile long lastMatch;
    private LocalMatcher.Match visibleMatch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!OpenCVLoader.initLocal()) {
            TextView error = new TextView(this);
            error.setText("手机视觉组件无法启动");
            setContentView(error);
            return;
        }
        matcher = new LocalMatcher();
        buildUi();
        receivePairing(getIntent());
        String key = getPreferences(MODE_PRIVATE).getString("binding_key", "");
        if (!key.isEmpty()) sync(key);
        else showStatus("请用手机扫描电脑上的 PaperJump 安卓配对码");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        receivePairing(intent);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(25, 23, 25));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setPadding(24, 18, 24, 18);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout camera = new FrameLayout(this);
        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        overlay = new MatchOverlay(this);
        camera.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        camera.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(camera, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        openButton = new Button(this);
        openButton.setText("指向识别后打开");
        openButton.setEnabled(false);
        openButton.setOnClickListener(v -> openVisible());
        buttons.addView(openButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button syncButton = new Button(this);
        syncButton.setText("同步绑定");
        syncButton.setOnClickListener(v -> {
            String key = getPreferences(MODE_PRIVATE).getString("binding_key", "");
            if (key.isEmpty()) showStatus("请先扫描电脑上的安卓配对码");
            else sync(key);
        });
        buttons.addView(syncButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void receivePairing(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (!"paperjump".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return;
        try {
            byte[] raw = Base64.decode(uri.getQueryParameter("sync"), Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject settings = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String key = settings.getString("key");
            if (Base64.decode(key, Base64.DEFAULT).length != 32) throw new IllegalArgumentException("密钥长度错误");
            getPreferences(MODE_PRIVATE).edit().putString("binding_key", key).apply();
            showStatus("已配对，正在同步绑定数据…");
            sync(key);
        } catch (Exception error) {
            showStatus("配对码无效：" + error.getMessage());
        }
    }

    private void sync(String key) {
        showStatus("正在读取加密绑定数据…");
        worker.execute(() -> {
            try {
                List<BindingData.Anchor> loaded = BindingData.fetch(key);
                List<BindingData.Anchor> previous = anchors;
                anchors = loaded;
                for (BindingData.Anchor anchor : previous) anchor.descriptors.release();
                runOnUiThread(() -> showStatus("已同步 " + loaded.size() + " 处局部锚点，正在识别…"));
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("同步失败：" + error.getMessage()));
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview cameraPreview = new Preview.Builder().build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(worker, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis);
            } catch (Exception error) {
                showStatus("摄像头启动失败：" + error.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (now - lastAnalysis < 450 || anchors.isEmpty()) return;
            lastAnalysis = now;
            Mat rgba = imageToMat(image);
            Mat oriented = new Mat(), gray = new Mat(), small = new Mat();
            try {
                int rotation = image.getImageInfo().getRotationDegrees();
                if (rotation == 90) Core.rotate(rgba, oriented, Core.ROTATE_90_CLOCKWISE);
                else if (rotation == 180) Core.rotate(rgba, oriented, Core.ROTATE_180);
                else if (rotation == 270) Core.rotate(rgba, oriented, Core.ROTATE_90_COUNTERCLOCKWISE);
                else rgba.copyTo(oriented);
                Imgproc.cvtColor(oriented, gray, Imgproc.COLOR_RGBA2GRAY);
                double scale = Math.min(1.0, 960.0 / gray.cols());
                Imgproc.resize(gray, small, new org.opencv.core.Size(
                        Math.round(gray.cols() * scale), Math.round(gray.rows() * scale)));
                LocalMatcher.Match found = matcher.find(small, anchors);
                if (found != null) {
                    lastMatch = now;
                    showMatch(found, small.cols(), small.rows());
                } else if (now - lastMatch > 1000) showMatch(null, small.cols(), small.rows());
            } finally {
                rgba.release(); oriented.release(); gray.release(); small.release();
            }
        } catch (Exception error) {
            runOnUiThread(() -> showStatus("识别失败：" + error.getMessage()));
        } finally {
            image.close();
        }
    }

    private static Mat imageToMat(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer input = plane.getBuffer();
        int width = image.getWidth(), height = image.getHeight();
        int stride = plane.getRowStride();
        byte[] bytes = new byte[stride * height];
        input.rewind();
        input.get(bytes, 0, Math.min(bytes.length, input.remaining()));
        Mat mat = new Mat(height, width, CvType.CV_8UC4);
        if (stride == width * 4) mat.put(0, 0, bytes);
        else {
            for (int row = 0; row < height; row++) {
                byte[] line = new byte[width * 4];
                System.arraycopy(bytes, row * stride, line, 0, line.length);
                mat.put(row, 0, line);
            }
        }
        return mat;
    }

    private void showMatch(LocalMatcher.Match found, int width, int height) {
        runOnUiThread(() -> {
            visibleMatch = found;
            overlay.setMatch(found, width, height);
            openButton.setEnabled(found != null && !found.anchor.url.isEmpty());
            openButton.setText(found == null ? "指向识别后打开" : "打开「" + found.anchor.title + "」");
            if (found != null) showStatus("已识别第 " + found.anchor.pageId + " 页 · " + found.inliers + " 个一致特征点");
            else showStatus("正在寻找已绑定字迹…");
        });
    }

    private void openVisible() {
        if (visibleMatch == null || visibleMatch.anchor.url.isEmpty()) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(visibleMatch.anchor.url))); }
        catch (Exception error) { showStatus("无法打开绑定目标：" + error.getMessage()); }
    }

    private void showStatus(String message) {
        if (status != null) status.setText(message);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 7 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else showStatus("请允许使用摄像头");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        for (BindingData.Anchor anchor : anchors) anchor.descriptors.release();
    }

    private static final class MatchOverlay extends View {
        private final Paint paint = new Paint(3);
        private LocalMatcher.Match match;
        private int frameWidth, frameHeight;

        MatchOverlay(android.content.Context context) { super(context); }

        void setMatch(LocalMatcher.Match found, int width, int height) {
            match = found; frameWidth = width; frameHeight = height; invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (match == null || frameWidth == 0 || frameHeight == 0) return;
            double scale = Math.min((double) getWidth() / frameWidth, (double) getHeight() / frameHeight);
            float left = (float) ((getWidth() - frameWidth * scale) / 2);
            float top = (float) ((getHeight() - frameHeight * scale) / 2);
            paint.setColor(Color.rgb(231, 104, 155));
            paint.setStrokeWidth(3);
            paint.setStyle(Paint.Style.STROKE);
            android.graphics.Path path = new android.graphics.Path();
            Point[] points = match.corners;
            path.moveTo(left + (float) (points[0].x * scale), top + (float) (points[0].y * scale));
            for (int i = 1; i < points.length; i++)
                path.lineTo(left + (float) (points[i].x * scale), top + (float) (points[i].y * scale));
            path.close();
            canvas.drawPath(path, paint);
        }
    }
}
