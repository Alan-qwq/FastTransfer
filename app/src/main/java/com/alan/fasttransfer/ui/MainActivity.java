package com.alan.fasttransfer.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.RecyclerView;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.AppSettings;
import com.alan.fasttransfer.core.ReceiveManager;
import com.alan.fasttransfer.core.TransferEngine;
import com.alan.fasttransfer.core.TransferService;
import com.alan.fasttransfer.core.net.NetworkUtils;
import com.alan.fasttransfer.core.transfer.Peer;
import com.alan.fasttransfer.core.transfer.PeerInfo;
import com.alan.fasttransfer.core.transfer.SendItem;
import com.alan.fasttransfer.core.transfer.TransferHistory;
import com.alan.fasttransfer.core.transfer.TransferSession;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationBarView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 主界面（Material 3）：
 * TopAppBar + 内容区（Fragment 淡入淡出切换）+ NavigationBar，
 * 宽屏自动切换为 NavigationRail。
 *
 * <p>内容区不用 ViewPager2：底部导航本来就不是「可滑动」的心智模型，
 * 换成 Fragment 切换后可以精确控制切换动画，也省掉一层嵌套滚动冲突。</p>
 */
public class MainActivity extends BaseActivity
        implements UiHost, TransferEngine.Listener {

    private static final int REQUEST_PERMISSIONS = 3001;
    private static final int[] TAB_TITLES = {
            R.string.tab_send, R.string.tab_receive, R.string.tab_history, R.string.tab_settings
    };
    private static final int[] TAB_IDS = {
            R.id.nav_send, R.id.nav_receive, R.id.nav_history, R.id.nav_settings
    };
    private static final String[] TAB_TAGS = {"send", "receive", "history", "settings"};
    private static final int PAGE_SEND = 0;
    private static final int PAGE_SETTINGS = 3;

    private TransferEngine engine;
    private AppSettings settings;
    private TransferHistory history;

    private MaterialToolbar toolbar;
    private View contentContainer;
    private NavigationBarView navBar;

    private TransferProgressDialog transferDialog;
    private IncomingRequestDialog incomingDialog;

    /**
     * 程序化设置底栏选中项时置为 true，用来忽略随之触发的回调。
     *
     * <p>否则 showTab → syncNavSelection → setSelectedItemId → 回调 → showTab
     * 会无限递归，最终 StackOverflowError。</p>
     */
    private boolean syncingNavSelection;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<SendItem> pendingSharedItems = new ArrayList<>();
    private final Queue<Peer> broadcastQueue = new LinkedList<>();
    private List<SendItem> broadcastItems;
    /** 扫码得到的 PIN，按 ip:port 记下来，发送时自动带上。 */
    private final Map<String, String> knownPins = new HashMap<>();

    private int currentTab = PAGE_SEND;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = TransferEngine.get(this);
        settings = engine.settings();
        history = engine.history();

        toolbar = findViewById(R.id.toolbar);
        contentContainer = findViewById(R.id.content_container);
        navBar = findViewById(R.id.nav_bar);

        applyInsets();
        setupAppBar();
        setupNavigation();
        setupSwipe();

        if (savedInstanceState == null) {
            // 先不碰底栏选中状态，等页面内容就绪后再同步
            showTab(PAGE_SEND, false);
        } else {
            currentTab = savedInstanceState.getInt("tab", PAGE_SEND);
            updateToolbarTitle(currentTab);
        }
        syncNavSelection(currentTab);

        requestRuntimePermissions();
        handleShareIntent(getIntent());
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", currentTab);
    }

    // ==================== inset ====================

    /**
     * 用 **margin** 而不是 padding 处理系统栏。
     *
     * <p>margin 会真正压缩可用高度：线性布局里「顶栏上边距 + 底栏下边距」加上后，
     * 内容区（weight=1）自然变矮。用 padding 则控件自身变高，反而把内容挤出去 ——
     * 这正是之前「底栏遮挡内容」的根因。</p>
     */
    private void applyInsets() {
        InsetsHelper.applyMargin(findViewById(R.id.app_bar), false, true, false, false);
        if (navBar != null) {
            InsetsHelper.applyMargin(navBar, true, false, true, true);
        }
    }

    private void setupSwipe() {
        if (contentContainer instanceof SwipeNavigationLayout) {
            ((SwipeNavigationLayout) contentContainer).setOnSwipeListener(
                    new SwipeNavigationLayout.OnSwipeListener() {
                        @Override
                        public boolean onSwipe(int direction) {
                            int next = currentTab + direction;
                            if (next < 0 || next >= TAB_TAGS.length) {
                                return false;
                            }
                            showTab(next, true);
                            return true;
                        }
                    });
        }
    }

    // ==================== 顶栏 ====================

    private void setupAppBar() {
        toolbar.inflateMenu(R.menu.main_toolbar);
        toolbar.setOnMenuItemClickListener(new MaterialToolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_visibility) {
                    engine.setVisible(!engine.isVisible());
                    refreshToolbar();
                    notifySendTab(true);
                    return true;
                }
                return false;
            }
        });
        updateToolbarTitle(currentTab);
        refreshToolbar();
    }

    private void updateToolbarTitle(int position) {
        if (toolbar == null) {
            return;
        }
        // 首页顶部固定显示软件名；其余页面显示当前页名
        if (position == PAGE_SEND) {
            toolbar.setTitle(R.string.app_name);
        } else if (position >= 0 && position < TAB_TITLES.length) {
            toolbar.setTitle(TAB_TITLES[position]);
        }
    }

    private void refreshToolbar() {
        if (toolbar == null || engine == null) {
            return;
        }
        MenuItem item = toolbar.getMenu().findItem(R.id.action_visibility);
        if (item != null) {
            boolean visible = engine.isVisible();
            item.setIcon(visible ? R.drawable.ic_eye_on : R.drawable.ic_eye_off);
            item.setTitle(visible ? R.string.visibility_on : R.string.visibility_off);
        }
    }

    // ==================== 导航 + 内容切换 ====================

    private void setupNavigation() {
        if (navBar == null) {
            return;
        }
        navBar.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                for (int i = 0; i < TAB_IDS.length; i++) {
                    if (TAB_IDS[i] == item.getItemId()) {
                        showTab(i, true);
                        return true;
                    }
                }
                return false;
            }
        });
        syncNavSelection(currentTab);
    }

    private void syncNavSelection(int tab) {
        if (navBar == null || tab < 0 || tab >= TAB_IDS.length) {
            return;
        }
        if (navBar.getSelectedItemId() == TAB_IDS[tab]) {
            return;
        }
        syncingNavSelection = true;
        try {
            navBar.setSelectedItemId(TAB_IDS[tab]);
        } finally {
            syncingNavSelection = false;
        }
    }

    /**
     * 切到指定页。
     *
     * @param animate 是否使用淡入淡出动画（首次展示不必动画）
     */
    private void showTab(int tab, boolean animate) {
        if (tab < 0 || tab >= TAB_TAGS.length) {
            return;
        }
        // 底栏回调解引用：程序化同步选中项时不要再进来一次
        if (syncingNavSelection) {
            return;
        }
        boolean changed = tab != currentTab || fragmentAt(tab) == null;
        currentTab = tab;

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (animate && changed) {
            transaction.setCustomAnimations(R.anim.fragment_in, R.anim.fragment_out);
        }
        // 非当前页全部隐藏（不销毁，保留列表滚动位置与状态）
        for (int i = 0; i < TAB_TAGS.length; i++) {
            Fragment existing = fragmentAt(i);
            if (existing == null) {
                continue;
            }
            if (i == tab) {
                transaction.show(existing);
            } else {
                transaction.hide(existing);
            }
        }
        if (fragmentAt(tab) == null) {
            transaction.add(R.id.content_container, createFragment(tab), TAB_TAGS[tab]);
        }
        transaction.commitAllowingStateLoss();

        updateToolbarTitle(tab);
        syncNavSelection(tab);

        // 等事务落地后再通知页面「被展示了」
        handler.post(new Runnable() {
            @Override
            public void run() {
                BaseTabFragment fragment = fragmentAt(currentTab);
                if (fragment != null && fragment.isAdded()) {
                    fragment.onShown();
                }
            }
        });
    }

    private BaseTabFragment createFragment(int tab) {
        switch (tab) {
            case 0:
                return new SendFragment();
            case 1:
                return new ReceiveFragment();
            case 2:
                return new HistoryFragment();
            default:
                return new SettingsFragment();
        }
    }

    private BaseTabFragment fragmentAt(int tab) {
        if (tab < 0 || tab >= TAB_TAGS.length) {
            return null;
        }
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAB_TAGS[tab]);
        return fragment instanceof BaseTabFragment ? (BaseTabFragment) fragment : null;
    }

    private void notifySendTab(boolean peersChanged) {
        BaseTabFragment fragment = fragmentAt(PAGE_SEND);
        if (fragment instanceof SendFragment) {
            if (peersChanged) {
                fragment.onPeersChanged();
            }
            ((SendFragment) fragment).refreshLocalCard();
        }
    }

    // ==================== 权限 ====================

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add("android.permission.POST_NOTIFICATIONS");
        }
        // 上界是 Q(29) 而不是 P(28)：Android 10 配 requestLegacyExternalStorage
        // 走的还是旧存储模型，**照样需要读写权限**。限到 P 会让 Android 10
        // 既读不了也写不了外部存储。
        // 11 起分区存储强制开启，这两个权限失效，写入改走 MediaStore。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (permissions.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]),
                    REQUEST_PERMISSIONS);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onStart() {
        super.onStart();
        engine.addListener(this);
        engine.start();
        if (engine.isVisible()) {
            TransferService.start(this, getString(R.string.notif_waiting_text));
        }
        refreshToolbar();
        onPeersChanged();
        ReceiveManager.PendingRequest request = engine.receiveManager().currentPending();
        if (request != null) {
            showIncomingRequest(request);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        engine.removeListener(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** 处理「其他应用分享到极速互传」。 */
    private void handleShareIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                uris.addAll(list);
            }
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                uris.add(uri);
            }
        }
        if (uris.isEmpty()) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(text)) {
                pendingSharedItems.add(SendItem.fromText(text));
            }
        } else {
            for (Uri uri : uris) {
                try {
                    pendingSharedItems.add(SendItem.fromUri(this, uri));
                } catch (Throwable ignored) {
                }
            }
        }
        if (pendingSharedItems.isEmpty()) {
            return;
        }
        showTab(PAGE_SEND, true);
        handler.post(new Runnable() {
            @Override
            public void run() {
                BaseTabFragment fragment = fragmentAt(PAGE_SEND);
                if (fragment instanceof SendFragment) {
                    SendFragment send = (SendFragment) fragment;
                    send.clearSelection();
                    List<SendItem> files = new ArrayList<>();
                    List<SendItem> texts = new ArrayList<>();
                    for (SendItem item : pendingSharedItems) {
                        if (item.isText()) {
                            texts.add(item);
                        } else {
                            files.add(item);
                        }
                    }
                    if (!files.isEmpty()) {
                        send.setFiles(files);
                    }
                    if (!texts.isEmpty()) {
                        send.setText(texts.get(0).text);
                    }
                }
                pendingSharedItems.clear();
                UiUtils.toast(MainActivity.this, R.string.selected_files);
            }
        });
    }

    // ==================== UiHost ====================

    @Override
    public TransferEngine engine() {
        return engine;
    }

    @Override
    public AppSettings settings() {
        return settings;
    }

    @Override
    public TransferHistory history() {
        return history;
    }

    @Override
    public void refreshHeader() {
        refreshToolbar();
        notifySendTab(false);
    }

    @Override
    public void onPeerSelected(Peer peer) {
        BaseTabFragment fragment = fragmentAt(PAGE_SEND);
        if (!(fragment instanceof SendFragment)) {
            return;
        }
        SendFragment send = (SendFragment) fragment;
        List<SendItem> items = send.collectItems();
        if (items.isEmpty()) {
            return;
        }
        sendTo(peer, items);
    }

    @Override
    public void sendTo(final Peer target, final List<SendItem> items) {
        if (target == null || items == null || items.isEmpty()) {
            return;
        }
        if (NetworkUtils.getLocalIpv4() == null) {
            UiUtils.toast(this, R.string.error_no_network);
            return;
        }
        if (engine.sendManager().isBusy()) {
            UiUtils.toast(this, R.string.error_already_running);
            return;
        }
        String pin = knownPins.get(target.ip + ":" + target.port);
        TransferSession session = engine.send(target, items, pin, null);
        if (session == null) {
            UiUtils.toast(this, R.string.error_already_running);
            return;
        }
        showTransferDialog(session);
        BaseTabFragment fragment = fragmentAt(PAGE_SEND);
        if (fragment instanceof SendFragment) {
            final SendFragment send = (SendFragment) fragment;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (send.isAdded()) {
                        send.clearSelection();
                    }
                }
            }, 300);
        }
    }

    @Override
    public void requestPinAndSend(final Peer target, final List<SendItem> items, boolean pinNeeded) {
        sendTo(target, items);
    }

    @Override
    public void sendToMany(List<Peer> targets, List<SendItem> items) {
        if (targets == null || targets.isEmpty() || items == null || items.isEmpty()) {
            return;
        }
        broadcastItems = items;
        broadcastQueue.clear();
        broadcastQueue.addAll(targets);
        broadcastNext();
    }

    private void broadcastNext() {
        final Peer next = broadcastQueue.poll();
        if (next == null) {
            broadcastItems = null;
            return;
        }
        TransferSession session = engine.send(next, broadcastItems, null, null);
        if (session == null) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    broadcastNext();
                }
            }, 500);
            return;
        }
        showTransferDialog(session);
    }

    @Override
    public void showManualConnectDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_manual_ip, null);
        final EditText etIp = view.findViewById(R.id.et_ip);
        final EditText etPort = view.findViewById(R.id.et_ip_port);
        etPort.setText(String.valueOf(settings.getPort()));

        final AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        view.findViewById(R.id.btn_ip_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        view.findViewById(R.id.btn_ip_connect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ip = etIp.getText().toString().trim();
                String portText = etPort.getText().toString().trim();
                if (!NetworkUtils.isIpv4(ip)) {
                    UiUtils.toast(MainActivity.this, R.string.error_invalid_ip);
                    return;
                }
                int port;
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    port = settings.getPort();
                }
                dialog.dismiss();
                connectByAddress(ip + ":" + port, null);
            }
        });
        dialog.show();
    }

    @Override
    public void connectByAddress(String address, String pin) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        final String target = address.trim();
        if (pin != null && !pin.isEmpty()) {
            knownPins.put(target, pin);
        }
        UiUtils.toast(this, R.string.send_scanning);
        engine.addPeerByAddress(target, new TransferEngine.ProbeCallback() {
            @Override
            public void onResult(Peer peer, String error) {
                if (peer == null) {
                    UiUtils.toast(MainActivity.this, R.string.error_connect_failed);
                    return;
                }
                onPeersChanged();
                UiUtils.toast(MainActivity.this,
                        getString(R.string.send_to_device, peer.displayName()));
            }
        });
    }

    @Override
    public void showTransferDialog(TransferSession session) {
        if (session == null) {
            return;
        }
        if (transferDialog != null && transferDialog.isShowing() && !transferDialog.isDismissed()) {
            transferDialog.dismiss();
        }
        transferDialog = new TransferProgressDialog(this, session,
                new TransferProgressDialog.OnCancel() {
                    @Override
                    public void onCancel() {
                        if (session.direction == TransferSession.DIRECTION_SEND) {
                            engine.sendManager().cancel();
                        } else {
                            engine.receiveManager().cancelActiveSession();
                        }
                    }
                });
        transferDialog.show();
        if (settings.isKeepScreenOn()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void showIncomingRequest(final ReceiveManager.PendingRequest request) {
        if (request == null || isFinishing()) {
            return;
        }
        android.util.Log.d("MainActivity", "incoming request: session=" + request.sessionId
                + " files=" + request.files.size()
                + " autoAccept=" + settings.isAutoAccept());
        if (settings.isAutoAccept() && !settings.isPinEnabled()) {
            engine.respondToRequest(request.sessionId, true, null);
            return;
        }
        // 旧弹窗还开着时不能把新请求静默丢掉：那样用户点的是旧弹窗的按钮，
        // sessionId 对不上等于白点，而新请求会一直挂到超时。
        // 先关掉旧的（它会顺带把旧请求按「拒绝」结掉），再显示新的。
        if (incomingDialog != null && incomingDialog.isShowing()) {
            android.util.Log.w("MainActivity", "closing stale incoming dialog before showing new one");
            incomingDialog.dismiss();
        }
        incomingDialog = new IncomingRequestDialog(this);
        incomingDialog.show(request, new IncomingRequestDialog.OnDecision() {
            @Override
            public void onDecision(String sessionId, boolean accepted, List<String> acceptedIds) {
                boolean ok = engine.respondToRequest(sessionId, accepted, acceptedIds);
                android.util.Log.d("MainActivity", "incoming decision: session=" + sessionId
                        + " accepted=" + accepted + " delivered=" + ok);
            }
        });
    }

    @Override
    public void onTextReceived(PeerInfo from, String text, String savedPath) {
        if (isFinishing() || text == null) {
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_incoming, null);
        android.widget.TextView tvFrom = view.findViewById(R.id.tv_incoming_from);
        android.widget.TextView tvSummary = view.findViewById(R.id.tv_incoming_summary);
        android.widget.TextView tvText = view.findViewById(R.id.tv_incoming_text);
        RecyclerView list = view.findViewById(R.id.rv_incoming_files);
        MaterialButton accept = view.findViewById(R.id.btn_accept);
        View decline = view.findViewById(R.id.btn_decline);

        tvFrom.setText(getString(R.string.received_text_from,
                from == null ? "" : from.displayName()));
        tvSummary.setText(savedPath == null
                ? getString(R.string.received_text_title)
                : getString(R.string.received_text_saved, savedPath));
        tvText.setVisibility(View.VISIBLE);
        tvText.setText(text);
        list.setVisibility(View.GONE);
        decline.setVisibility(View.GONE);
        accept.setText(R.string.action_copy);

        final String content = text;
        final AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        accept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(content);
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager manager = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(android.content.ClipData.newPlainText("FastTransfer", text));
                UiUtils.toast(this, R.string.action_copied);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== TransferEngine.Listener ====================

    @Override
    public void onPeersChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                BaseTabFragment fragment = fragmentAt(PAGE_SEND);
                if (fragment != null) {
                    fragment.onPeersChanged();
                }
            }
        });
    }

    @Override
    public void onSessionUpdated(final TransferSession session) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (transferDialog != null && transferDialog.isShowing()) {
                    transferDialog.update();
                }
            }
        });
    }

    @Override
    public void onHistoryChanged() {
        // 网页上传完成时走这里：刷新传输记录页，并在发送页提示一下
        if (isFinishing()) {
            return;
        }
        BaseTabFragment historyTab = fragmentAt(2);
        if (historyTab != null) {
            historyTab.onShown();
        }
        notifySendTab(false);
    }

    @Override
    public void onSessionFinished(final TransferSession session) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (transferDialog != null && transferDialog.isShowing()) {
                    transferDialog.update();
                }
                getWindow().clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                BaseTabFragment historyTab = fragmentAt(2);
                if (historyTab != null) {
                    historyTab.onShown();
                }
                if (session != null && session.state == TransferSession.STATE_DONE
                        && session.direction == TransferSession.DIRECTION_RECEIVE) {
                    showSavedPath(session);
                }
                if (session != null && session.direction == TransferSession.DIRECTION_RECEIVE) {
                    notifySendTab(false);
                }
                if (!broadcastQueue.isEmpty()) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            broadcastNext();
                        }
                    }, 600);
                }
            }
        });
    }

    private void showSavedPath(TransferSession session) {
        if (session.firstTextFile() != null) {
            return;
        }
        String path = null;
        for (com.alan.fasttransfer.core.transfer.TransferFile file : session.files) {
            if (file.savedPath != null) {
                path = file.savedPath;
                break;
            }
        }
        if (path != null) {
            UiUtils.toast(this, getString(R.string.received_text_saved, path));
        }
    }

    @Override
    public void onIncomingRequest(final ReceiveManager.PendingRequest request) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                showIncomingRequest(request);
            }
        });
    }

    @Override
    public void onRequestCancelled(String sessionId) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (incomingDialog != null) {
                    incomingDialog.dismiss();
                }
            }
        });
    }

    @Override
    public void onEngineStateChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                refreshHeader();
            }
        });
    }
}
