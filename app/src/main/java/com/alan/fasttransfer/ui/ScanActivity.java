package com.alan.fasttransfer.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.util.Logs;
import com.alan.fasttransfer.core.util.QrCode;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫码连接：CameraX 取景 + ZXing 解码。
 *
 * <p>扫到内容后解析出 {@code host:port}，通过 {@link #EXTRA_ADDRESS} 回传给调用方，
 * 由主界面复用「手动连接」那条探测逻辑完成实际连接。</p>
 */
public class ScanActivity extends BaseActivity {

    /** 返回解析出的 {@code ip:port}。 */
    public static final String EXTRA_ADDRESS = "extra_address";
    /** 返回二维码里携带的 PIN（可能为空）。 */
    public static final String EXTRA_PIN = "extra_pin";

    private static final int REQUEST_CAMERA = 3001;

    private PreviewView previewView;
    private View messageBox;
    private TextView tvMessage;
    private MaterialButton btnAction;

    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private ExecutorService analysisExecutor;
    private final AtomicBoolean handled = new AtomicBoolean(false);
    private long lastScanAttempt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        previewView = findViewById(R.id.preview);
        messageBox = findViewById(R.id.ll_message);
        tvMessage = findViewById(R.id.tv_message);
        btnAction = findViewById(R.id.btn_action);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 拿不到相机时，退回到手动输入
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        if (!hasCamera()) {
            showMessage(getString(R.string.scan_no_camera), true);
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else {
            startCamera();
        }
    }

    private boolean hasCamera() {
        try {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            showMessage(getString(R.string.scan_no_permission), true);
        }
    }

    // ==================== 相机 ====================

    private void startCamera() {
        analysisExecutor = Executors.newSingleThreadExecutor();
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraProvider = future.get();
                    bindCamera();
                } catch (Throwable t) {
                    Logs.w("ScanActivity", "camera init failed: " + t.getMessage());
                    showMessage(getString(R.string.scan_camera_failed), true);
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null || isFinishing()) {
            return;
        }
        try {
            imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(new android.util.Size(1280, 720))
                    .build();
            imageAnalysis.setAnalyzer(analysisExecutor, new ImageAnalysis.Analyzer() {
                @Override
                public void analyze(@NonNull ImageProxy imageProxy) {
                    analyzeFrame(imageProxy);
                }
            });

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageAnalysis);
        } catch (Throwable t) {
            Logs.w("ScanActivity", "bind failed: " + t.getMessage());
            showMessage(getString(R.string.scan_camera_failed), true);
        }
    }

    // ==================== 逐帧解码 ====================

    private void analyzeFrame(ImageProxy imageProxy) {
        try {
            if (handled.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastScanAttempt < 120) {
                return;
            }
            lastScanAttempt = now;

            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            if (planes == null || planes.length == 0) {
                return;
            }
            ImageProxy.PlaneProxy plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            byte[] yPlane = new byte[buffer.remaining()];
            buffer.get(yPlane);

            int[] size = new int[2];
            byte[] luminance = QrCode.prepareLuminance(
                    yPlane,
                    imageProxy.getWidth(),
                    imageProxy.getHeight(),
                    plane.getRowStride(),
                    plane.getPixelStride(),
                    imageProxy.getImageInfo().getRotationDegrees(),
                    size);
            if (luminance == null) {
                return;
            }
            String text = QrCode.decodeLuminance(luminance, size[0], size[1]);
            if (text != null && !text.isEmpty()) {
                onQrFound(text);
            }
        } catch (Throwable t) {
            Logs.d("ScanActivity", "analyze error: " + t.getMessage());
        } finally {
            imageProxy.close();
        }
    }

    private void onQrFound(final String text) {
        if (!handled.compareAndSet(false, true)) {
            return;
        }
        Logs.d("ScanActivity", "qr = " + text);

        QrCode.ConnectInfo info = QrCode.parseConnectContent(text);
        if (info == null || info.host == null || info.host.isEmpty()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    UiUtils.toast(ScanActivity.this, R.string.scan_not_valid);
                    handled.set(false);
                }
            });
            return;
        }

        final String address = info.host + ":" + info.port;
        final String pin = info.pin;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                Intent result = new Intent();
                result.putExtra(EXTRA_ADDRESS, address);
                if (pin != null && !pin.isEmpty()) {
                    result.putExtra(EXTRA_PIN, pin);
                }
                setResult(Activity.RESULT_OK, result);
                finish();
            }
        });
    }

    // ==================== 收尾 ====================

    private void showMessage(String message, boolean showAction) {
        messageBox.setVisibility(View.VISIBLE);
        tvMessage.setText(message);
        btnAction.setVisibility(showAction ? View.VISIBLE : View.GONE);
        previewView.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
        } catch (Throwable ignored) {
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
            analysisExecutor = null;
        }
    }
}
