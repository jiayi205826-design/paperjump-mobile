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
import android.view.MotionEvent;
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
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.QRCodeDetector;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends ComponentActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService syncWorker = Executors.newSingleThreadExecutor();
    private List<BindingData.Anchor> anchors = Collections.emptyList();
    private LocalMatcher matcher;
    private QRCodeDetector pairingScanner;
    private WifiCameraServer wifiCamera;
    private boolean showingDesktopStream;
    private volatile boolean pairingNeeded = true;
    private PreviewView preview;
    private MatchOverlay overlay;
    private TextView status;
    private TextView pairingStatus;
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
        pairingScanner = new QRCodeDetector();
        matcher = new LocalMatcher();
        buildUi();
        String key = getPreferences(MODE_PRIVATE).getString("binding_key", "");
        if (!key.isEmpty()) {
            pairingNeeded = false;
            pairingStatus.setText("已配对 · 正在同步绑定");
            startWifiCamera(key);
            sync(key);
        } else {
            showStatus("请用此摄像头对准电脑上的安卓配对码");
        }
        receivePairing(getIntent());
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
        pairingStatus = new TextView(this);
        pairingStatus.setTextColor(Color.rgb(255, 190, 215));
        pairingStatus.setTextSize(13);
        pairingStatus.setPadding(24, 0, 24, 12);
        pairingStatus.setText("未配对");
        root.addView(pairingStatus, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout camera = new FrameLayout(this);
        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        overlay = new MatchOverlay(this, this::openNode);
        camera.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        camera.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(camera, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        openButton = new Button(this);
        openButton.setText("识别后点选绑定区域");
        openButton.setEnabled(false);
        openButton.setOnClickListener(v -> openVisible());
        buttons.addView(openButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button syncButton = new Button(this);
        syncButton.setText("同步绑定");
        syncButton.setOnClickListener(v -> {
            String key = getPreferences(MODE_PRIVATE).getString("binding_key", "");
            if (key.isEmpty()) showStatus("请用此摄像头对准电脑上的安卓配对码");
            else sync(key);
        });
        buttons.addView(syncButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void receivePairing(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        receivePairingUri(intent.getData().toString());
    }

    private void receivePairingUri(String value) {
        Uri uri = Uri.parse(value);
        if (!"paperjump".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return;
        try {
            byte[] raw = Base64.decode(uri.getQueryParameter("sync"), Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject settings = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String key = settings.getString("key");
            if (Base64.decode(key, Base64.DEFAULT).length != 32) throw new IllegalArgumentException("密钥长度错误");
            getPreferences(MODE_PRIVATE).edit().putString("binding_key", key).apply();
            pairingNeeded = false;
            pairingStatus.setText("配对码已扫描 · 正在同步绑定");
            startWifiCamera(key);
            showStatus("已配对，正在同步绑定数据…");
            sync(key);
        } catch (Exception error) {
            showStatus("配对码无效：" + error.getMessage());
        }
    }

    private void startWifiCamera(String key) {
        if (wifiCamera != null) return;
        try {
            wifiCamera = new WifiCameraServer(key);
        } catch (Exception error) {
            showStatus("Wi-Fi 摄像头启动失败：" + error.getMessage());
        }
    }

    private void sync(String key) {
        showStatus("正在读取加密绑定数据…");
        syncWorker.execute(() -> {
            try {
                List<BindingData.Anchor> loaded = BindingData.fetch(key);
                List<BindingData.Anchor> previous = anchors;
                anchors = loaded;
                for (BindingData.Anchor anchor : previous) {
                    anchor.descriptors.release();
                    anchor.fastDescriptors.release();
                }
                runOnUiThread(() -> {
                    pairingStatus.setText("已同步 " + loaded.size() + " 组特征 · 手机地址 "
                            + WifiCameraServer.localAddress());
                    showStatus("正在寻找已绑定字迹…");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    pairingStatus.setText("已扫描配对码 · 同步失败");
                    showStatus("同步失败：" + error.getMessage());
                });
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
            boolean streaming = wifiCamera != null && wifiCamera.isConnected();
            if (now - lastAnalysis < (streaming ? 120 : 450)
                    || (!streaming && !pairingNeeded && anchors.isEmpty())) return;
            lastAnalysis = now;
            Mat rgba = imageToMat(image);
            Mat oriented = new Mat(), gray = new Mat(), small = new Mat();
            try {
                int rotation = image.getImageInfo().getRotationDegrees();
                if (rotation == 90) Core.rotate(rgba, oriented, Core.ROTATE_90_CLOCKWISE);
                else if (rotation == 180) Core.rotate(rgba, oriented, Core.ROTATE_180);
                else if (rotation == 270) Core.rotate(rgba, oriented, Core.ROTATE_90_COUNTERCLOCKWISE);
                else rgba.copyTo(oriented);
                if (streaming) {
                    MatOfByte encoded = new MatOfByte();
                    MatOfInt quality = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 75);
                    Mat streamFrame = new Mat();
                    try {
                        double streamScale = Math.min(1.0, 960.0 / oriented.cols());
                        Imgproc.resize(oriented, streamFrame, new org.opencv.core.Size(
                                Math.round(oriented.cols() * streamScale),
                                Math.round(oriented.rows() * streamScale)));
                        Imgcodecs.imencode(".jpg", streamFrame, encoded, quality);
                        wifiCamera.offerFrame(encoded.toArray());
                    } finally { encoded.release(); quality.release(); streamFrame.release(); }
                    if (!showingDesktopStream) {
                        showingDesktopStream = true;
                        runOnUiThread(() -> {
                            pairingStatus.setText("已连接电脑 · 手机正在提供摄像头画面");
                            visibleMatch = null;
                            overlay.setMatch(null, 0, 0);
                            openButton.setEnabled(false);
                        });
                    }
                    return;
                }
                if (showingDesktopStream) {
                    showingDesktopStream = false;
                    runOnUiThread(() -> pairingStatus.setText("等待电脑连接 · 手机地址 "
                            + WifiCameraServer.localAddress()));
                }
                Imgproc.cvtColor(oriented, gray, Imgproc.COLOR_RGBA2GRAY);
                double scale = Math.min(1.0, 960.0 / gray.cols());
                Imgproc.resize(gray, small, new org.opencv.core.Size(
                        Math.round(gray.cols() * scale), Math.round(gray.rows() * scale)));
                if (pairingNeeded) {
                    String code = pairingScanner.detectAndDecode(small);
                    if (code.startsWith("paperjump://pair?"))
                        runOnUiThread(() -> receivePairingUri(code));
                    return;
                }
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
            boolean singleNode = found != null && found.nodes.size() == 1;
            openButton.setEnabled(singleNode);
            openButton.setText(singleNode ? "打开「" + found.nodes.get(0).node.name + "」"
                    : "识别后点选绑定区域");
            if (found != null) showStatus("已识别第 " + found.anchor.pageId + " 页 · "
                    + found.nodes.size() + " 处绑定区域");
            else showStatus("正在寻找已绑定字迹…");
        });
    }

    private void openVisible() {
        if (visibleMatch != null && visibleMatch.nodes.size() == 1)
            openNode(visibleMatch.nodes.get(0).node);
    }

    private void openNode(BindingData.Node node) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(node.url))); }
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
        syncWorker.shutdownNow();
        if (wifiCamera != null) wifiCamera.stop();
        for (BindingData.Anchor anchor : anchors) {
            anchor.descriptors.release();
            anchor.fastDescriptors.release();
        }
    }

    private static final class MatchOverlay extends View {
        private final Paint paint = new Paint(3);
        private final Consumer<BindingData.Node> openNode;
        private LocalMatcher.Match match;
        private int frameWidth, frameHeight;

        MatchOverlay(android.content.Context context, Consumer<BindingData.Node> openNode) {
            super(context);
            this.openNode = openNode;
        }

        void setMatch(LocalMatcher.Match found, int width, int height) {
            match = found; frameWidth = width; frameHeight = height; invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (match == null || frameWidth == 0 || frameHeight == 0) return;
            double scale = Math.min((double) getWidth() / frameWidth, (double) getHeight() / frameHeight);
            float left = (float) ((getWidth() - frameWidth * scale) / 2);
            float top = (float) ((getHeight() - frameHeight * scale) / 2);
            paint.setColor(Color.argb(170, 231, 104, 155));
            paint.setStrokeWidth(2);
            paint.setStyle(Paint.Style.STROKE);
            for (LocalMatcher.NodeMatch node : match.nodes) {
                android.graphics.Path path = new android.graphics.Path();
                Point[] points = node.corners;
                path.moveTo(left + (float) (points[0].x * scale), top + (float) (points[0].y * scale));
                for (int i = 1; i < points.length; i++)
                    path.lineTo(left + (float) (points[i].x * scale), top + (float) (points[i].y * scale));
                path.close();
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(24);
                canvas.drawText(node.node.name,
                        left + (float) (points[0].x * scale),
                        top + (float) (points[0].y * scale) - 5, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP || match == null
                    || frameWidth == 0 || frameHeight == 0) return true;
            double scale = Math.min((double) getWidth() / frameWidth, (double) getHeight() / frameHeight);
            double left = (getWidth() - frameWidth * scale) / 2;
            double top = (getHeight() - frameHeight * scale) / 2;
            Point touch = new Point((event.getX() - left) / scale, (event.getY() - top) / scale);
            for (LocalMatcher.NodeMatch node : match.nodes) {
                if (inside(node.corners, touch)) {
                    openNode.accept(node.node);
                    break;
                }
            }
            return true;
        }

        private static boolean inside(Point[] corners, Point touch) {
            double sign = 0;
            for (int i = 0; i < corners.length; i++) {
                Point a = corners[i], b = corners[(i + 1) % corners.length];
                double cross = (b.x - a.x) * (touch.y - a.y)
                        - (b.y - a.y) * (touch.x - a.x);
                if (Math.abs(cross) < 1) continue;
                if (sign == 0) sign = Math.signum(cross);
                else if (Math.signum(cross) != sign) return false;
            }
            return sign != 0;
        }
    }
}
