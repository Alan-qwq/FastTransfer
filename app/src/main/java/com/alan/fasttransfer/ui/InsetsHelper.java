package com.alan.fasttransfer.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 系统栏 inset 处理。
 *
 * <p>原先在根布局上写 {@code fitsSystemWindows="true"}，会让根布局整体内缩。
 * 对底部导航栏（{@code layout_gravity="bottom"}）和侧边 NavigationRail 来说，
 * 它们的父容器高度**没有**跟着缩，于是控件比可见区域更高 —— 表现为
 * 「底栏遮住内容」或「底栏自己被系统导航栏切掉」。</p>
 *
 * <p>正确做法：根布局不处理 inset，由本类按控件分别加 padding。</p>
 */
public final class InsetsHelper {

    private InsetsHelper() {
    }

    /**
     * 让某个 View 按系统栏给自身加 padding。
     *
     * @param left  左 inset 计入
     * @param top   上 inset 计入
     * @param right 右 inset 计入
     * @param bottom 下 inset 计入
     */
    public static void applyPadding(final View view, final boolean left, final boolean top,
                                    final boolean right, final boolean bottom) {
        if (view == null) {
            return;
        }
        final int baseLeft = view.getPaddingLeft();
        final int baseTop = view.getPaddingTop();
        final int baseRight = view.getPaddingRight();
        final int baseBottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                int bottomInset = Math.max(bars.bottom, ime.bottom);
                v.setPadding(
                        baseLeft + (left ? bars.left : 0),
                        baseTop + (top ? bars.top : 0),
                        baseRight + (right ? bars.right : 0),
                        baseBottom + (bottom ? bottomInset : 0));
                return insets;
            }
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 让某个 View 按系统栏给自身加 margin（适合 CoordinatorLayout 里
     * 用 layout_gravity 定位的子控件，改 padding 会影响其内容布局）。
     */
    public static void applyMargin(final View view, final boolean left, final boolean top,
                                   final boolean right, final boolean bottom) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final ViewGroup.MarginLayoutParams base =
                (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        final int baseLeft = base.leftMargin;
        final int baseTop = base.topMargin;
        final int baseRight = base.rightMargin;
        final int baseBottom = base.bottomMargin;

        ViewCompat.setOnApplyWindowInsetsListener(view, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                int bottomInset = Math.max(bars.bottom, ime.bottom);
                ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                params.leftMargin = baseLeft + (left ? bars.left : 0);
                params.topMargin = baseTop + (top ? bars.top : 0);
                params.rightMargin = baseRight + (right ? bars.right : 0);
                params.bottomMargin = baseBottom + (bottom ? bottomInset : 0);
                v.setLayoutParams(params);
                return insets;
            }
        });
        ViewCompat.requestApplyInsets(view);
    }
}
