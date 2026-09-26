package com.alan.fasttransfer.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.LocalsendProtocol;
import com.alan.fasttransfer.core.net.NetworkUtils;
import com.alan.fasttransfer.core.transfer.Peer;
import com.alan.fasttransfer.core.transfer.SendItem;
import com.alan.fasttransfer.core.util.Formatters;
import com.alan.fasttransfer.core.util.Logs;
import com.alan.fasttransfer.core.web.WebOutbox;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 发送页（Material 3）：本机信息卡 + 分段按钮切换文字/文件 + 设备列表。
 */
public class SendFragment extends BaseTabFragment implements DeviceAdapter.OnDeviceClick,
        SelectedFileAdapter.OnRemove {

    private static final int REQUEST_PICK_FILES = 1001;
    private static final int REQUEST_BROWSE_FILES = 1002;
    private static final int REQUEST_SCAN = 1003;
    private static final int REQUEST_PICK_FOR_PC = 1004;
    private static final int MAX_ITEMS = 200;
    private static final String TAG = "SendFragment";

    private TextView tvLocalAlias;
    private TextView tvLocalAddress;
    private TextView tvLocalVisibility;

    private MaterialButtonToggleGroup toggleMode;
    private MaterialButton btnModeText;
    private MaterialButton btnModeFile;

    private View cardText;
    private View cardFiles;
    private EditText etMessage;
    private TextView tvFilesSummary;
    private MaterialButton btnClearFiles;
    private RecyclerView rvSelectedFiles;
    private RecyclerView rvDevices;
    private View llEmpty;
    private TextView tvEmptyTitle;
    private TextView tvDeviceSection;

    private DeviceAdapter deviceAdapter;
    private SelectedFileAdapter fileAdapter;

    private boolean fileMode;
    private final List<SendItem> selectedFiles = new ArrayList<>();
    /** 入场动画只播一次。 */
    private boolean entrancePlayed;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_send, container, false);
    }

    @Override
    protected void onBindViews(View view, @Nullable Bundle savedInstanceState) {

        tvLocalAlias = view.findViewById(R.id.tv_local_alias);
        tvLocalAddress = view.findViewById(R.id.tv_local_address);
        tvLocalVisibility = view.findViewById(R.id.tv_local_visibility);
        toggleMode = view.findViewById(R.id.toggle_mode);
        btnModeText = view.findViewById(R.id.btn_mode_text);
        btnModeFile = view.findViewById(R.id.btn_mode_file);
        cardText = view.findViewById(R.id.card_text);
        cardFiles = view.findViewById(R.id.card_files);
        etMessage = view.findViewById(R.id.et_message);
        tvFilesSummary = view.findViewById(R.id.tv_files_summary);
        btnClearFiles = view.findViewById(R.id.btn_clear_files);
        rvSelectedFiles = view.findViewById(R.id.rv_selected_files);
        rvDevices = view.findViewById(R.id.rv_devices);
        llEmpty = view.findViewById(R.id.ll_empty);
        tvEmptyTitle = view.findViewById(R.id.tv_empty_title);
        tvDeviceSection = view.findViewById(R.id.tv_device_section);

        deviceAdapter = new DeviceAdapter(this);
        rvDevices.setLayoutManager(new LinearLayoutManager(getActivity()));
        rvDevices.setAdapter(deviceAdapter);
        if (rvDevices.getItemAnimator() != null) {
            rvDevices.getItemAnimator().setAddDuration(220);
            rvDevices.getItemAnimator().setRemoveDuration(180);
            rvDevices.getItemAnimator().setChangeDuration(200);
        }

        fileAdapter = new SelectedFileAdapter(this);
        rvSelectedFiles.setLayoutManager(new LinearLayoutManager(getActivity()));
        rvSelectedFiles.setAdapter(fileAdapter);

        view.findViewById(R.id.btn_rename).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptRename();
            }
        });
        view.findViewById(R.id.tv_local_alias).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptRename();
            }
        });

        toggleMode.addOnButtonCheckedListener(
                new MaterialButtonToggleGroup.OnButtonCheckedListener() {
                    @Override
                    public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                                boolean isChecked) {
                        if (!isChecked) {
                            return;
                        }
                        setFileMode(checkedId == R.id.btn_mode_file);
                    }
                });

        view.findViewById(R.id.btn_send_text).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSendClicked();
            }
        });
        view.findViewById(R.id.btn_send_files).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSendClicked();
            }
        });
        view.findViewById(R.id.btn_send_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSendToAllClicked();
            }
        });
        view.findViewById(R.id.btn_send_all_files).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSendToAllClicked();
            }
        });
        view.findViewById(R.id.btn_pick_files).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFilesWithSystemPicker();
            }
        });
        view.findViewById(R.id.btn_browse_files).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBrowser();
            }
        });
        btnClearFiles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearSelection();
            }
        });
        view.findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshPeers();
            }
        });
        view.findViewById(R.id.btn_manual_connect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host() != null) {
                    host().showManualConnectDialog();
                }
            }
        });
        view.findViewById(R.id.btn_scan_connect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });

        setFileMode(false);
        refreshFilesUi();
        refreshLocalCard();
        onPeersChanged();
    }

    // ==================== 本机信息卡 ====================

    /** 刷新本机名称 / 地址 / 可见性。 */
    public void refreshLocalCard() {
        if (!isAdded() || tvLocalAlias == null || !ready()) {
            return;
        }
        tvLocalAlias.setText(settings().getAlias());
        String ip = engine().localIp();
        tvLocalAddress.setText(ip == null
                ? getString(R.string.local_ip_unknown)
                : getString(R.string.local_ip_format, ip + ":" + engine().localPort()));
        tvLocalVisibility.setText(engine().isVisible()
                ? R.string.visibility_on : R.string.visibility_off);
        tvLocalVisibility.setTextColor(UiUtils.color(requireContext(),
                engine().isVisible() ? R.color.m3_primary : R.color.m3_on_surface_variant));
    }

    private void promptRename() {
        if (!ready()) {
            return;
        }
        DialogHelper.promptText(requireActivity(), getString(R.string.edit_device_name),
                getString(R.string.device_name_hint), settings().getAlias(),
                android.text.InputType.TYPE_CLASS_TEXT, new DialogHelper.OnText() {
                    @Override
                    public void onText(String value) {
                        if (value == null || value.trim().isEmpty()) {
                            UiUtils.toast(getActivity(), R.string.device_name_empty);
                            return;
                        }
                        settings().setAlias(value.trim());
                        engine().refreshLocalInfo();
                        refreshLocalCard();
                        if (host() != null) {
                            host().refreshHeader();
                        }
                        UiUtils.toast(getActivity(), R.string.device_name_saved);
                    }
                });
    }

    // ==================== 模式切换 ====================

    private void setFileMode(boolean file) {
        fileMode = file;
        if (cardText != null) {
            cardText.setVisibility(file ? View.GONE : View.VISIBLE);
        }
        if (cardFiles != null) {
            cardFiles.setVisibility(file ? View.VISIBLE : View.GONE);
        }
        if (toggleMode != null) {
            int checked = file ? R.id.btn_mode_file : R.id.btn_mode_text;
            if (toggleMode.getCheckedButtonId() != checked) {
                toggleMode.check(checked);
            }
        }
    }

    // ==================== 发送 ====================

    /** 收集当前待发内容；为空时给出提示并返回空列表。 */
    public List<SendItem> collectItems() {
        List<SendItem> items = new ArrayList<>();
        if (fileMode || !selectedFiles.isEmpty()) {
            if (selectedFiles.isEmpty()) {
                UiUtils.toast(getActivity(), R.string.send_need_files);
                return items;
            }
            items.addAll(selectedFiles);
            return items;
        }
        String text = etMessage == null || etMessage.getText() == null
                ? "" : etMessage.getText().toString().trim();
        if (text.isEmpty()) {
            UiUtils.toast(getActivity(), R.string.send_text_empty);
            return items;
        }
        items.add(SendItem.fromText(text));
        return items;
    }

    private void onSendClicked() {
        if (!ready()) {
            return;
        }
        List<SendItem> items = collectItems();
        if (items.isEmpty()) {
            return;
        }
        UiUtils.hideKeyboard(getActivity(), etMessage);
        Peer target = singleSelectedPeer();
        if (target != null) {
            host().sendTo(target, items);
        } else {
            pickTarget(items);
        }
    }

    private void onSendToAllClicked() {
        if (!ready()) {
            return;
        }
        final List<SendItem> items = collectItems();
        if (items.isEmpty()) {
            return;
        }
        final List<Peer> peers = new ArrayList<>();
        for (Peer peer : engine().peers()) {
            if (peer.reachable) {
                peers.add(peer);
            }
        }
        if (peers.isEmpty()) {
            UiUtils.toast(getActivity(), R.string.send_no_device);
            return;
        }
        DialogHelper.confirm(requireActivity(), getString(R.string.broadcast_to_all),
                getString(R.string.broadcast_confirm, peers.size()),
                new Runnable() {
                    @Override
                    public void run() {
                        if (host() != null) {
                            host().sendToMany(peers, items);
                        }
                    }
                });
    }

    /** 只有一台设备时直接用它，省一次点击。 */
    private Peer singleSelectedPeer() {
        List<Peer> peers = engine().peers();
        if (peers.size() == 1 && peers.get(0).reachable) {
            return peers.get(0);
        }
        return null;
    }

    private void pickTarget(final List<SendItem> items) {
        final List<Peer> peers = new ArrayList<>();
        // 「发给谁」也应该能选电脑，和设备列表保持一致
        if (ready()) {
            peers.add(webPeer());
            for (Peer peer : engine().peers()) {
                if (peer.reachable) {
                    peers.add(peer);
                }
            }
        }
        if (peers.isEmpty()) {
            UiUtils.toast(getActivity(), R.string.send_no_device);
            return;
        }
        final String[] names = new String[peers.size()];
        for (int i = 0; i < peers.size(); i++) {
            names[i] = peers.get(i).displayName() + "\n" + peers.get(i).subtitle();
        }
        DialogHelper.choose(requireActivity(), getString(R.string.pick_target_title), names, -1,
                new DialogHelper.OnText() {
                    @Override
                    public void onText(String value) {
                        try {
                            int index = Integer.parseInt(value);
                            if (index >= 0 && index < peers.size()) {
                                Peer peer = peers.get(index);
                                if (peer.web) {
                                    sendToWeb(items);
                                } else {
                                    host().sendTo(peer, items);
                                }
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                });
    }

    private void refreshPeers() {
        if (!ready()) {
            return;
        }
        engine().refreshLocalInfo();
        UiUtils.toast(getActivity(), R.string.send_scanning);
        engine().scanSubnet(new com.alan.fasttransfer.core.TransferEngine.ProbeCallback() {
            @Override
            public void onResult(Peer peer, String error) {
                if (!isAdded()) {
                    return;
                }
                onPeersChanged();
                if (peer == null && "no-network".equals(error)) {
                    UiUtils.toast(getActivity(), R.string.error_no_network);
                }
            }
        });
    }

    /**
     * 「电脑」是设备列表里的虚拟条目，不走 LocalSend。
     *
     * <p>点它等于沿用现有的发送流程，只是内容不通过网络推给对方，
     * 而是挂到本机的网页服务上，等电脑浏览器来取。</p>
     */
    @Override
    public void onDeviceClick(Peer peer) {
        if (peer == null) {
            return;
        }
        List<SendItem> items = collectItems();
        if (items.isEmpty()) {
            return;
        }
        UiUtils.hideKeyboard(getActivity(), etMessage);
        if (peer.web) {
            sendToWeb(items);
            return;
        }
        if (host() != null) {
            host().sendTo(peer, items);
        }
    }

    /** 构造设备列表里的虚拟「电脑」条目。 */
    private Peer webPeer() {
        Peer peer = new Peer();
        peer.web = true;
        peer.manual = true;
        peer.reachable = true;
        peer.fingerprint = Peer.WEB_KEY;
        peer.deviceType = LocalsendProtocol.DEVICE_TYPE_DESKTOP;
        peer.alias = getString(R.string.web_peer_name);
        peer.deviceModel = getString(R.string.web_peer_hint);
        peer.lastSeen = System.currentTimeMillis();
        return peer;
    }

    /**
     * 把待发内容挂到网页服务上，并把地址告诉用户。
     *
     * <p>文案和「发送」保持一致：文件进待发队列，文字进待发队列的文字条目。</p>
     */
    private void sendToWeb(List<SendItem> items) {
        // 用户点了「电脑」就是明确要用这个功能，直接打开，不必再去设置里找
        settings().setWebEnabled(true);

        int files = 0;
        int texts = 0;
        for (SendItem item : items) {
            try {
                if (item.text != null) {
                    if (WebOutbox.addText(requireContext(), item.text) != null) {
                        texts++;
                    }
                    continue;
                }
                Uri uri = item.uri;
                if (uri == null && item.file != null) {
                    // 内置浏览器选出来的本地文件：转成 file:// 交给队列也能读
                    uri = Uri.fromFile(item.file);
                }
                if (uri == null) {
                    continue;
                }
                try {
                    requireContext().getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                    // file:// 或不支持持久化的来源，忽略即可
                }
                String name = item.name;
                if (name == null || name.isEmpty()) {
                    name = uri.getLastPathSegment();
                }
                if (WebOutbox.add(requireContext(), uri, name, item.size, item.mime) != null) {
                    files++;
                }
            } catch (Throwable t) {
                Logs.w(TAG, "sendToWeb failed: " + t);
            }
        }

        int total = files + texts;
        if (total == 0) {
            UiUtils.toast(getActivity(), R.string.web_pc_none);
            return;
        }

        if (ready()) {
            if (!engine().receiveManager().isRunning()
                    && !engine().receiveManager().start()) {
                UiUtils.toast(getActivity(), getString(R.string.web_pc_need_server,
                        settings().getPort()));
                return;
            }
            engine().receiveManager().onWebSettingsChanged();
        }

        String ip = NetworkUtils.getLocalIpv4();
        if (ip == null || ip.isEmpty()) {
            UiUtils.toast(getActivity(), R.string.settings_web_need_server);
            return;
        }
        String url = "http://" + ip + ":" + settings().getPort() + "/";
        DialogHelper.showMessage(getActivity(), getString(R.string.web_pc_title),
                getString(R.string.web_pc_body, total, url));
    }

    // ==================== 文件选择 ====================

    @Override
    public void onRemove(int position) {
        if (position >= 0 && position < selectedFiles.size()) {
            selectedFiles.remove(position);
            refreshFilesUi();
        }
    }

    private void pickFilesWithSystemPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_PICK_FILES);
        } catch (Throwable t) {
            openBrowser();
        }
    }

    private void openBrowser() {
        Intent intent = new Intent(getActivity(), FileBrowserActivity.class);
        startActivityForResult(intent, REQUEST_BROWSE_FILES);
    }

    /** 打开扫码界面；拿不到相机时会提示改用手动输入。 */
    private void startScan() {
        try {
            Intent intent = new Intent(getActivity(), ScanActivity.class);
            startActivityForResult(intent, REQUEST_SCAN);
        } catch (Throwable t) {
            UiUtils.toast(getActivity(), R.string.scan_camera_failed);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_SCAN) {
            String address = data.getStringExtra(ScanActivity.EXTRA_ADDRESS);
            if (address == null || address.isEmpty()) {
                return;
            }
            UiUtils.toast(getActivity(), getString(R.string.scan_connecting, address));
            if (host() == null) {
                return;
            }
            // 复用「手动连接」的探测逻辑
            host().connectByAddress(address, data.getStringExtra(ScanActivity.EXTRA_PIN));
            return;
        }
        if (requestCode == REQUEST_PICK_FILES) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count && uris.size() < MAX_ITEMS; i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) {
                        uris.add(uri);
                    }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            int added = 0;
            for (Uri uri : uris) {
                try {
                    selectedFiles.add(SendItem.fromUri(requireContext(), uri));
                    added++;
                } catch (Throwable ignored) {
                }
            }
            if (!uris.isEmpty()) {
                setFileMode(true);
            }
            refreshFilesUi();
            // 选完文件给个明确回馈：列表本来就在下面，不给提示用户不确定加没加上
            if (added > 0) {
                UiUtils.toast(getActivity(), getResources().getQuantityString(
                        R.plurals.files_added, added, added));
            } else {
                UiUtils.toast(getActivity(), R.string.files_add_failed);
            }
        } else if (requestCode == REQUEST_BROWSE_FILES) {
            // 内置选择器回传选中的文件路径
            ArrayList<String> paths = data.getStringArrayListExtra(FileBrowserActivity.EXTRA_PATHS);
            if (paths != null && !paths.isEmpty()) {
                int added = 0;
                for (String path : paths) {
                    if (selectedFiles.size() >= MAX_ITEMS) {
                        break;
                    }
                    File file = new File(path);
                    if (file.isFile()) {
                        try {
                            selectedFiles.add(SendItem.fromFile(file));
                            added++;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (added > 0) {
                    setFileMode(true);
                }
                refreshFilesUi();
                if (added > 0) {
                    UiUtils.toast(getActivity(), getResources().getQuantityString(
                            R.plurals.files_added, added, added));
                }
            }
        }
    }

    private void refreshFilesUi() {
        long total = 0;
        for (SendItem item : selectedFiles) {
            if (item.size > 0) {
                total += item.size;
            }
        }
        if (selectedFiles.isEmpty()) {
            tvFilesSummary.setText(R.string.send_need_files);
            btnClearFiles.setVisibility(View.GONE);
            rvSelectedFiles.setVisibility(View.GONE);
        } else {
            tvFilesSummary.setText(getString(R.string.selected_files,
                    selectedFiles.size(), Formatters.size(total)));
            btnClearFiles.setVisibility(View.VISIBLE);
            rvSelectedFiles.setVisibility(View.VISIBLE);
        }
        fileAdapter.submit(selectedFiles);
    }

    // ==================== 对外接口 ====================

    public void clearSelection() {
        selectedFiles.clear();
        refreshFilesUi();
        if (etMessage != null) {
            etMessage.setText("");
        }
    }

    /** 由「分享到极速互传」写入待发文件。 */
    public void setFiles(List<SendItem> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        selectedFiles.clear();
        selectedFiles.addAll(files);
        setFileMode(true);
        refreshFilesUi();
    }

    /** 由「分享到极速互传」写入待发文字。 */
    public void setText(String text) {
        if (etMessage == null || text == null) {
            return;
        }
        etMessage.setText(text);
        if (etMessage.getText() != null) {
            etMessage.setSelection(etMessage.getText().length());
        }
        setFileMode(false);
    }

    public boolean isFileMode() {
        return fileMode;
    }

    public int selectedCount() {
        return selectedFiles.size();
    }

    public List<SendItem> selectedItems() {
        return new ArrayList<>(selectedFiles);
    }

    public String currentText() {
        return etMessage == null || etMessage.getText() == null
                ? "" : etMessage.getText().toString();
    }

    // ==================== 列表回调 ====================

    @Override
    public void onPeersChanged() {
        if (!isAdded() || deviceAdapter == null) {
            return;
        }
        List<Peer> peers = new ArrayList<>();
        // 「电脑」永远排在最前面：它不需要被发现，是随时可用的出口。
        // 注意不能拿「真实设备是否为空」来决定要不要显示列表 ——
        // 电脑上没跑 LocalSend 时真实设备就是 0，那样会把「电脑」这个入口一起藏掉。
        if (ready()) {
            peers.add(webPeer());
            peers.addAll(engine().peers());
        }
        deviceAdapter.submit(peers);

        boolean listEmpty = peers.isEmpty();
        UiUtils.setVisible(llEmpty, listEmpty);
        UiUtils.setVisible(rvDevices, !listEmpty);
        if (listEmpty) {
            String ip = ready() ? engine().localIp() : null;
            tvEmptyTitle.setText(ip == null ? R.string.error_no_network : R.string.send_scanning);
            tvDeviceSection.setText(R.string.send_no_device);
        } else {
            // 计数只算真实的局域网设备，「电脑」不是发现来的
            int discovered = ready() ? engine().peers().size() : 0;
            tvDeviceSection.setText(getString(R.string.tab_send) + " · " + discovered);
        }
    }

    @Override
    public void onShown() {
        refreshLocalCard();
        onPeersChanged();
        // 首次进入时给设备列表一个逐项淡入
        if (!entrancePlayed && getView() != null && deviceAdapter.size() > 0) {
            entrancePlayed = true;
            UiUtils.playListEntrance(getView(), R.id.rv_devices);
        }
    }
}
