package com.alan.fasttransfer.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.alan.fasttransfer.core.AppSettings;
import com.alan.fasttransfer.core.TransferEngine;
import com.alan.fasttransfer.core.transfer.TransferHistory;

/**
 * 所有标签页 Fragment 的基类。
 *
 * <p><b>为什么会有这个类：</b> 最早把 {@code settings}/{@code engine} 缓存在
 * {@code onCreate()} 取到的字段里。结果在「改显示大小 → Activity 本地重建」
 * 这条路径上，Fragment 会由 FragmentManager 从已保存状态直接恢复到
 * {@code onViewCreated()}，缓存字段仍为 null，于是 {@code settings.getAlias()} NPE。</p>
 *
 * <p>现在两层防护：</p>
 * <ol>
 *   <li>访问器改为**每次现取**，不再依赖缓存时序；</li>
 *   <li>{@link #onViewCreated} 被声明为 {@code final}，统一先做就绪判断，
 *       子类只能实现 {@link #onBindViews} —— 这样「忘记判空」在结构上就不可能发生。</li>
 * </ol>
 */
public abstract class BaseTabFragment extends Fragment {

    private View contentView;
    /** 引擎是否已就绪并完成绑定。 */
    private boolean bound;
    /** 保存下来供 onResume 重试绑定用。 */
    @Nullable
    private Bundle lastSavedState;

    @Override
    public void onAttach(Context context) {
        // Fragment 的布局用自己的 Context inflate，这里也要带上「显示大小」
        super.onAttach(BaseActivity.applyUiScale(context));
    }

    @Override
    public final void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        contentView = view;
        lastSavedState = savedInstanceState;
        tryBind(view);
    }

    /**
     * 引擎若在 onViewCreated 时还没就绪，这里再补一次绑定。
     *
     * <p>Activity 本地重建（例如改显示大小）时，Fragment 可能先于宿主就绪，
     * 此时不能就此放弃 —— onResume 通常已经拿得到 Activity 了。</p>
     */
    @Override
    public void onResume() {
        super.onResume();
        if (!bound && contentView != null) {
            tryBind(contentView);
        }
    }

    private void tryBind(View view) {
        if (bound) {
            return;
        }
        if (!ready()) {
            onBindFailed();
            return;
        }
        bound = true;
        onBindViews(view, lastSavedState);
    }

    /**
     * 绑定视图与业务数据。
     *
     * <p>进入这里时 {@link #ready()} 一定为 true，accessor 不会返回 null。</p>
     */
    protected abstract void onBindViews(View view, @Nullable Bundle savedInstanceState);

    /** 引擎不可用时的回调，默认什么都不做。 */
    protected void onBindFailed() {
    }

    @Nullable
    protected View contentView() {
        return contentView;
    }

    /** 是否已经成功绑定过业务数据。 */
    protected boolean isBound() {
        return bound;
    }

    @Nullable
    protected UiHost host() {
        android.app.Activity activity = getActivity();
        return activity instanceof UiHost ? (UiHost) activity : null;
    }

    @Nullable
    protected AppSettings settings() {
        UiHost h = host();
        return h == null ? null : h.settings();
    }

    @Nullable
    protected TransferEngine engine() {
        UiHost h = host();
        return h == null ? null : h.engine();
    }

    @Nullable
    protected TransferHistory history() {
        UiHost h = host();
        return h == null ? null : h.history();
    }

    /** 引擎与设置都已就绪。 */
    protected boolean ready() {
        return engine() != null && settings() != null;
    }

    /** 该页重新可见时调用。 */
    public void onShown() {
    }

    /** 设备列表有更新时调用。 */
    public void onPeersChanged() {
    }

    /** 传输会话有更新时调用。 */
    public void onSessionChanged() {
    }
}
