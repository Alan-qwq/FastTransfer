package com.alan.fasttransfer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 扫码取景框：四周压暗 + 中间镂空方框 + 四角高亮 + 一条来回移动的扫描线。
 */
public class ScannerOverlayView extends View {

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF frame = new RectF();

    private float lineProgress;
    private boolean animating;
    private long lastFrameTime;

    public ScannerOverlayView(Context context) {
        this(context, null);
    }

    public ScannerOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScannerOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        dimPaint.setColor(0x99000000);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(3));
        borderPaint.setColor(0x66FFFFFF);

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(4));
        framePaint.setStrokeCap(Paint.Cap.ROUND);
        framePaint.setColor(Color.WHITE);

        linePaint.setStrokeWidth(dp(2));
        linePaint.setColor(0xFF7C88EE);

        setClickable(true);
        setFocusable(true);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        float side = Math.min(width, height) * 0.68f;
        float half = side / 2f;
        float cx = width / 2f;
        float cy = height / 2f;
        frame.set(cx - half, cy - half, cx + half, cy + half);

        // 四周压暗，中间镂空
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, width, height, Path.Direction.CW);
        path.addRoundRect(frame, dp(24), dp(24), Path.Direction.CW);
        canvas.drawPath(path, dimPaint);

        // 淡边框 + 四角亮角
        canvas.drawRoundRect(frame, dp(24), dp(24), borderPaint);
        float corner = side * 0.18f;
        drawCorner(canvas, frame.left, frame.top, corner, corner, 1, 1);
        drawCorner(canvas, frame.right, frame.top, -corner, corner, -1, 1);
        drawCorner(canvas, frame.left, frame.bottom, corner, -corner, 1, -1);
        drawCorner(canvas, frame.right, frame.bottom, -corner, -corner, -1, -1);

        // 扫描线
        if (animating) {
            long now = System.currentTimeMillis();
            if (lastFrameTime == 0) {
                lastFrameTime = now;
            }
            float delta = (now - lastFrameTime) / 1600f;
            lastFrameTime = now;
            lineProgress += delta;
            while (lineProgress > 2f) {
                lineProgress -= 2f;
            }
            float t = lineProgress <= 1f ? lineProgress : 2f - lineProgress;
            float y = frame.top + dp(8) + t * (frame.height() - dp(16));
            canvas.drawLine(frame.left + dp(12), y, frame.right - dp(12), y, linePaint);
            postInvalidateOnAnimation();
        }
    }

    private void drawCorner(Canvas canvas, float x, float y, float dx, float dy,
                            int sx, int sy) {
        float inset = dp(4);
        canvas.drawLine(x + sx * inset, y + sy * inset, x + sx * inset + dx, y + sy * inset,
                framePaint);
        canvas.drawLine(x + sx * inset, y + sy * inset, x + sx * inset, y + sy * inset + dy,
                framePaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animating = true;
        lastFrameTime = 0;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        animating = false;
        super.onDetachedFromWindow();
    }
}
