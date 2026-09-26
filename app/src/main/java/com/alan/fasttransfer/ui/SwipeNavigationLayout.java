package com.alan.fasttransfer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 支持左右滑动切换页面的内容容器。
 *
 * <p>手势判定：只有「横向位移明显大于纵向」时才认定为切页手势，
 * 并且一旦判定为纵向就整段手势不再抢事件 —— 这样页面内的纵向列表滚动、
 * 输入框文本选择、二维码点击都不受影响。</p>
 *
 * <p>切页时只做淡入淡出过渡，不做跟手位移，因此不需要调整子 View 的绘制顺序。</p>
 */
public class SwipeNavigationLayout extends FrameLayout {

    /** 切页回调。 */
    public interface OnSwipeListener {
        /**
         * @param direction -1 表示向右滑（上一页），+1 表示向左滑（下一页）
         * @return 是否消费了这次滑动
         */
        boolean onSwipe(int direction);
    }

    /** 横向判定：横向位移要大于纵向的这么多倍。 */
    private static final float HORIZONTAL_SLOP_FACTOR = 4.5f;
    /** 触发切页需要的最小横向位移（相对容器宽度）。 */
    private static final float TRIGGER_RATIO = 0.08f;

    private OnSwipeListener listener;
    private float downX;
    private float downY;
    private boolean horizontalGesture;
    private boolean verticalGesture;
    private boolean gestureConsumed;
    private int touchSlop;
    private int containerWidth;

    public SwipeNavigationLayout(@NonNull Context context) {
        this(context, null);
    }

    public SwipeNavigationLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeNavigationLayout(@NonNull Context context, @Nullable AttributeSet attrs,
                                 int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        containerWidth = getResources().getDisplayMetrics().widthPixels;
    }

    public void setOnSwipeListener(OnSwipeListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (listener == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                horizontalGesture = false;
                verticalGesture = false;
                gestureConsumed = false;
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (gestureConsumed || verticalGesture) {
                    return false;
                }
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                if (dx > touchSlop && dy > touchSlop && dx < dy * HORIZONTAL_SLOP_FACTOR) {
                    // 纵向手势：整段手势都不再考虑切页
                    verticalGesture = true;
                    return false;
                }
                if (dx > touchSlop && dx > dy * HORIZONTAL_SLOP_FACTOR) {
                    horizontalGesture = true;
                    // 从这里开始接管，后续 MOVE/UP 都走 onTouchEvent
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                horizontalGesture = false;
                verticalGesture = false;
                return false;

            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!horizontalGesture || gestureConsumed) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - downX;
                gestureConsumed = true;
                int threshold = Math.max((int) (containerWidth * TRIGGER_RATIO), touchSlop * 6);
                if (Math.abs(dx) < threshold) {
                    return true;
                }
                return listener != null && listener.onSwipe(dx < 0 ? 1 : -1);
            }
            case MotionEvent.ACTION_CANCEL:
                horizontalGesture = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) {
            containerWidth = w;
        }
    }
}
