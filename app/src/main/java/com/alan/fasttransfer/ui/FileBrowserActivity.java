package com.alan.fasttransfer.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.util.Formatters;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 内置文件浏览器：直接读文件系统目录树。
 *
 * <p>权限策略（拿不到文件时逐级升级）：</p>
 * <ol>
 *   <li>读取权限：Android 6–12 用 {@code READ_EXTERNAL_STORAGE}；
 *       Android 13+ 用 {@code READ_MEDIA_IMAGES/VIDEO/AUDIO}。</li>
 *   <li>Android 11+ 受分区存储限制仍然列不出文件时，引导用户去系统设置
 *       开启「所有文件访问权限」({@code MANAGE_EXTERNAL_STORAGE})。</li>
 *   <li>都不给的话，退回「选择文件」走系统 SAF 选择器。</li>
 * </ol>
 */
public class FileBrowserActivity extends BaseActivity {

    /** 返回选中的文件系统路径列表。 */
    public static final String EXTRA_PATHS = "extra_paths";

    private static final int REQUEST_PERMISSION = 2001;
    private static final int MAX_SELECTION = 200;

    private final List<File> entries = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private RecyclerView list;
    private TextView tvSelected;
    private View messageBox;
    private TextView tvMessage;
    private MaterialButton btnAction;
    /** 快捷访问横排卡片。 */
    private LinearLayout llQuick;
    /** 可点击的面包屑。 */
    private LinearLayout llBreadcrumb;
    private HorizontalScrollView svBreadcrumb;

    /** 排序方式：false 按名称，true 按时间（新的在前）。 */
    private boolean sortByTime;
    /** 是否显示以「.」开头的隐藏文件。 */
    private boolean showHidden;

    /** 普通目录浏览。 */
    private static final int FILTER_NONE = 0;
    /** 正在看「安装包」筛选结果（此时 currentDir 为 null）。 */
    private static final int FILTER_APK = 1;
    private int filterMode = FILTER_NONE;

    private File currentDir;
    /** 因缺少「所有文件访问权限」而打不开的目录，授权回来后自动重开。 */
    private File pendingDir;
    private boolean needsAllFilesAccess;
    private EntryAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        list = findViewById(R.id.rv_files);
        tvSelected = findViewById(R.id.tv_selected);
        messageBox = findViewById(R.id.ll_message);
        tvMessage = findViewById(R.id.tv_message);
        btnAction = findViewById(R.id.btn_action);
        llQuick = findViewById(R.id.ll_quick);
        llBreadcrumb = findViewById(R.id.ll_breadcrumb);
        svBreadcrumb = findViewById(R.id.sv_breadcrumb);

        adapter = new EntryAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setItemAnimator(null);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        toolbar.inflateMenu(R.menu.file_browser);
        toolbar.setOnMenuItemClickListener(new MaterialToolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return onToolbarMenu(item);
            }
        });
        // 菜单里「当前项」打个勾，用户才知道现在是哪种排序
        syncMenuState(toolbar.getMenu());

        buildQuickAccess();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!goUp()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        findViewById(R.id.btn_confirm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithSelection();
            }
        });
        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onActionClicked();
            }
        });

        if (hasReadPermission()) {
            openDir(storageRoot());
        } else {
            showGrantMessage();
            requestReadPermission();
        }
    }

    private File storageRoot() {
        File root = Environment.getExternalStorageDirectory();
        return root == null ? new File("/") : root;
    }

    // ==================== 权限 ====================

    private boolean hasReadPermission() {
        // Android 11+ 分区存储强制开启，媒体细分权限（READ_MEDIA_*）只能读媒体文件、
        // 不能遍历目录树，所以这里只认「所有文件访问权限」。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return hasAllFilesAccess();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return isGranted(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return true;
    }

    private boolean isGranted(String permission) {
        return ActivityCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 是否已获得「所有文件访问权限」（Android 11+）。 */
    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    private String[] missingReadPermissions() {
        List<String> missing = new ArrayList<>();
        // 只有 Android 6–10 用得到 READ_EXTERNAL_STORAGE。
        // 11 起分区存储强制开启，运行时的媒体权限读不了目录树，
        // 再请求 READ_MEDIA_* 只会弹一个「照片和视频」的假授权框。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            addIfMissing(missing, Manifest.permission.READ_EXTERNAL_STORAGE);
            addIfMissing(missing, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return missing.toArray(new String[0]);
    }

    private void addIfMissing(List<String> list, String permission) {
        if (!isGranted(permission)) {
            list.add(permission);
        }
    }

    private void requestReadPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        String[] missing = missingReadPermissions();
        if (missing.length > 0) {
            ActivityCompat.requestPermissions(this, missing, REQUEST_PERMISSION);
        }
    }

    /** 跳到系统设置里的「所有文件访问权限」页。 */
    private void openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestReadPermission();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable t2) {
                UiUtils.toast(this, R.string.file_browser_no_permission);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSION) {
            return;
        }
        if (!hasReadPermission()) {
            showGrantMessage();
            return;
        }
        // 这个回调只在 Android 6–10 会走到（11+ 走「所有文件访问」设置页），
        // 权限齐了直接打开目标目录即可。
        openDir(pendingDir != null ? pendingDir : storageRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「所有文件访问权限」设置页返回后，自动重试之前打不开的目录
        if (needsAllFilesAccess && hasAllFilesAccess() && pendingDir != null) {
            File target = pendingDir;
            needsAllFilesAccess = false;
            pendingDir = null;
            openDir(target);
        }
    }

    /** 提示需要「所有文件访问权限」，并把按钮切成去设置页。 */
    private void requireAllFilesAccess(File dir) {
        needsAllFilesAccess = true;
        pendingDir = dir;
        messageBox.setVisibility(View.VISIBLE);
        tvMessage.setText(R.string.file_browser_need_all_files);
        btnAction.setText(R.string.file_browser_open_settings);
        btnAction.setVisibility(View.VISIBLE);
    }

    /** 提示需要基础读取权限，按钮为「授予权限」。 */
    private void showGrantMessage() {
        needsAllFilesAccess = false;
        messageBox.setVisibility(View.VISIBLE);
        tvMessage.setText(R.string.file_browser_no_permission);
        btnAction.setText(R.string.file_browser_grant);
        btnAction.setVisibility(View.VISIBLE);
    }

    private void onActionClicked() {
        File target = pendingDir != null ? pendingDir : storageRoot();
        if (needsAllFilesAccess) {
            openAllFilesAccessSettings();
            return;
        }
        // Android 11+ 只有「所有文件访问」这一条路，没有别的权限可要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasAllFilesAccess()) {
                openDir(target);
            } else {
                requireAllFilesAccess(target);
            }
            return;
        }
        // Android 6–10：先拿运行时读写权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasReadPermission()) {
            requestReadPermission();
            return;
        }
        openDir(target);
    }

    // ==================== 浏览 ====================

    /** 打开目录；返回是否成功打开。 */
    private boolean openDir(File dir) {
        if (dir == null || !dir.exists() || !dir.canRead()) {
            showGrantMessage();
            return false;
        }
        filterMode = FILTER_NONE;
        currentDir = dir;

        entries.clear();
        File[] children;
        try {
            children = dir.listFiles();
        } catch (Throwable t) {
            children = null;
        }
        if (children != null) {
            for (File child : children) {
                // 隐藏文件默认不显示，和参考实现一致
                if (!showHidden && isHidden(child)) {
                    continue;
                }
                entries.add(child);
            }
        }
        Collections.sort(entries, entryComparator());
        adapter.notifyDataSetChanged();
        refreshSelectionLabel();
        updateBreadcrumb();

        if (entries.isEmpty()) {
            messageBox.setVisibility(View.VISIBLE);
            tvMessage.setText(R.string.file_browser_empty);
            btnAction.setVisibility(View.GONE);
        } else {
            messageBox.setVisibility(View.GONE);
        }
        return true;
    }

    /** 以「.」开头就是隐藏文件。 */
    private static boolean isHidden(File file) {
        String name = file.getName();
        return name != null && name.startsWith(".");
    }

    /** 目录永远排在文件前面；同类里按名称或时间。 */
    private Comparator<File> entryComparator() {
        return new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                if (sortByTime) {
                    // 新的在前：找刚存下来的文件时最顺手
                    int byTime = Long.compare(b.lastModified(), a.lastModified());
                    if (byTime != 0) {
                        return byTime;
                    }
                }
                String an = a.getName() == null ? "" : a.getName().toLowerCase(Locale.ROOT);
                String bn = b.getName() == null ? "" : b.getName().toLowerCase(Locale.ROOT);
                return an.compareTo(bn);
            }
        };
    }

    // ==================== 快捷访问 ====================

    private static final class QuickDir {
        final int nameRes;
        final int iconRes;
        final File dir;
        /** 这是「跨目录筛选」而不是某个固定路径（例如安装包）。 */
        final boolean filter;

        QuickDir(int nameRes, int iconRes, File dir) {
            this(nameRes, iconRes, dir, false);
        }

        QuickDir(int nameRes, int iconRes, File dir, boolean filter) {
            this.nameRes = nameRes;
            this.iconRes = iconRes;
            this.dir = dir;
            this.filter = filter;
        }
    }

    /**
     * 铺开「快捷访问」卡片。
     *
     * <p>只放真实存在的目录 —— 有些 ROM 没有 Documents 之类的标准目录，
     * 摆一个点进去是空的入口反而添乱。筛选类入口（安装包）不受此限。</p>
     */
    private void buildQuickAccess() {
        if (llQuick == null) {
            return;
        }
        llQuick.removeAllViews();
        for (QuickDir quick : quickDirs()) {
            if (!quick.filter
                    && (quick.dir == null || !quick.dir.exists() || !quick.dir.canRead())) {
                continue;
            }
            View card = getLayoutInflater().inflate(R.layout.item_quick_dir, llQuick, false);
            ImageView icon = card.findViewById(R.id.iv_quick_icon);
            TextView name = card.findViewById(R.id.tv_quick_name);
            icon.setImageResource(quick.iconRes);
            name.setText(quick.nameRes);
            final QuickDir target = quick;
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (target.filter) {
                        showApkList();
                    } else {
                        openDir(target.dir);
                    }
                }
            });
            llQuick.addView(card);
        }
    }

    /** 快捷目录列表：根目录 + 几个标准分类目录 + 安装包筛选。 */
    private List<QuickDir> quickDirs() {
        List<QuickDir> list = new ArrayList<>();
        list.add(new QuickDir(R.string.quick_root, R.drawable.ic_folder, storageRoot()));
        list.add(new QuickDir(R.string.quick_downloads, R.drawable.ic_download,
                publicDir(Environment.DIRECTORY_DOWNLOADS)));
        // 安装包散落在下载目录、根目录等各处，所以做成跨目录筛选而不是某个固定路径
        list.add(new QuickDir(R.string.quick_apk, R.drawable.ic_quick_apk, null, true));
        list.add(new QuickDir(R.string.quick_images, R.drawable.ic_quick_image,
                publicDir(Environment.DIRECTORY_PICTURES)));
        list.add(new QuickDir(R.string.quick_dcim, R.drawable.ic_quick_image,
                publicDir(Environment.DIRECTORY_DCIM)));
        list.add(new QuickDir(R.string.quick_music, R.drawable.ic_quick_music,
                publicDir(Environment.DIRECTORY_MUSIC)));
        list.add(new QuickDir(R.string.quick_videos, R.drawable.ic_quick_video,
                publicDir(Environment.DIRECTORY_MOVIES)));
        list.add(new QuickDir(R.string.quick_documents, R.drawable.ic_quick_doc,
                publicDir(Environment.DIRECTORY_DOCUMENTS)));
        return list;
    }

    // ==================== 安装包筛选 ====================

    /** 最多扫这么深：再深既慢，也基本不会有安装包。 */
    private static final int APK_SCAN_DEPTH = 4;
    /** 结果上限，防止极端情况下列表爆炸。 */
    private static final int APK_SCAN_LIMIT = 500;

    /**
     * 列出设备上所有安装包。
     *
     * <p>放后台线程扫：从存储根往下找 {@code .apk}，跳过 {@code Android/}
     * （里面是各应用的数据和拆包，没有安装包却文件极多）。扫完回主线程刷新。</p>
     */
    private void showApkList() {
        filterMode = FILTER_APK;
        currentDir = null;
        entries.clear();
        adapter.notifyDataSetChanged();
        messageBox.setVisibility(View.VISIBLE);
        tvMessage.setText(R.string.quick_apk_scanning);
        btnAction.setVisibility(View.GONE);
        updateBreadcrumb();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<File> found = new ArrayList<>();
                scanForApk(storageRoot(), 0, found);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (filterMode != FILTER_APK) {
                            return;
                        }
                        entries.clear();
                        entries.addAll(found);
                        Collections.sort(entries, entryComparator());
                        adapter.notifyDataSetChanged();
                        refreshSelectionLabel();
                        if (entries.isEmpty()) {
                            messageBox.setVisibility(View.VISIBLE);
                            tvMessage.setText(R.string.quick_apk_empty);
                            btnAction.setVisibility(View.GONE);
                        } else {
                            messageBox.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }, "apk-scan").start();
    }

    private void scanForApk(File dir, int depth, List<File> out) {
        if (dir == null || depth > APK_SCAN_DEPTH || out.size() >= APK_SCAN_LIMIT) {
            return;
        }
        File[] children;
        try {
            children = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (out.size() >= APK_SCAN_LIMIT) {
                return;
            }
            String name = child.getName();
            if (name == null || name.startsWith(".")) {
                continue;
            }
            if (child.isDirectory()) {
                if (depth == 0 && "Android".equals(name)) {
                    continue;
                }
                scanForApk(child, depth + 1, out);
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                out.add(child);
            }
        }
    }

    private static File publicDir(String type) {
        try {
            return Environment.getExternalStoragePublicDirectory(type);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 面包屑 ====================

    /**
     * 重建面包屑：从存储根一路到当前目录，每一段都能点着跳过去。
     *
     * <p>比一行不可操作的路径文字有用得多 —— 想回上两级时不用按两次返回。</p>
     */
    private void updateBreadcrumb() {
        if (llBreadcrumb == null) {
            return;
        }
        llBreadcrumb.removeAllViews();

        // 筛选视图没有真实目录层级，直接显示一个可返回的标签
        if (filterMode == FILTER_APK) {
            addCrumb(getString(R.string.quick_apk), null, true);
            addCrumb(getString(R.string.quick_apk_all), null, false);
            if (svBreadcrumb != null) {
                svBreadcrumb.post(new Runnable() {
                    @Override
                    public void run() {
                        svBreadcrumb.fullScroll(View.FOCUS_RIGHT);
                    }
                });
            }
            return;
        }
        if (currentDir == null) {
            return;
        }

        List<File> chain = new ArrayList<>();
        File cursor = currentDir;
        File root = storageRoot();
        int guard = 0;
        while (cursor != null && guard++ < 64) {
            chain.add(0, cursor);
            if (root != null && cursor.equals(root)) {
                break;
            }
            cursor = cursor.getParentFile();
        }

        for (int i = 0; i < chain.size(); i++) {
            final File dir = chain.get(i);
            boolean isLast = i == chain.size() - 1;
            String name = i == 0 ? displayRootName(dir) : dir.getName();
            addCrumb(name == null || name.isEmpty() ? "/" : name,
                    isLast ? null : dir, isLast);
        }

        // 目录深的时候把面包屑滚到最右，当前目录才看得见
        if (svBreadcrumb != null) {
            svBreadcrumb.post(new Runnable() {
                @Override
                public void run() {
                    svBreadcrumb.fullScroll(View.FOCUS_RIGHT);
                }
            });
        }
    }

    /**
     * 加一节面包屑。
     *
     * @param target 点了要跳去的目录；为 null 表示不可点
     * @param isLast 是不是当前这一节
     */
    private void addCrumb(String label, final File target, boolean isLast) {
        View crumb = getLayoutInflater().inflate(R.layout.item_breadcrumb, llBreadcrumb, false);
        TextView text = crumb.findViewById(R.id.tv_crumb);
        View separator = crumb.findViewById(R.id.iv_crumb_sep);

        text.setText(label);
        boolean clickable = target != null && !isLast;
        // 当前目录不给点：点了也没变化，反而让人以为没反应
        text.setClickable(clickable);
        text.setFocusable(clickable);
        text.setAlpha(isLast ? 1f : 0.75f);
        text.setTextColor(isLast
                ? UiUtils.color(this, R.color.m3_on_surface)
                : UiUtils.color(this, R.color.m3_primary));
        if (clickable) {
            text.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openDir(target);
                }
            });
        }
        separator.setVisibility(isLast ? View.GONE : View.VISIBLE);
        llBreadcrumb.addView(crumb);
    }

    private String displayRootName(File root) {
        if (root == null) {
            return "/";
        }
        String path = root.getAbsolutePath();
        if (Environment.getExternalStorageDirectory() != null
                && path.equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            return getString(R.string.quick_root);
        }
        return root.getName() == null || root.getName().isEmpty() ? path : root.getName();
    }

    // ==================== 工具栏菜单 ====================

    private boolean onToolbarMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sort_name) {
            sortByTime = false;
        } else if (id == R.id.action_sort_time) {
            sortByTime = true;
        } else if (id == R.id.action_toggle_hidden) {
            showHidden = !showHidden;
            UiUtils.toast(this, showHidden
                    ? R.string.file_browser_hidden_on : R.string.file_browser_hidden_off);
        } else {
            return false;
        }
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        syncMenuState(toolbar.getMenu());
        if (currentDir != null) {
            openDir(currentDir);
        }
        return true;
    }

    /** 让菜单反映出当前排序方式和隐藏文件开关。 */
    private void syncMenuState(Menu menu) {
        if (menu == null) {
            return;
        }
        MenuItem byName = menu.findItem(R.id.action_sort_name);
        MenuItem byTime = menu.findItem(R.id.action_sort_time);
        MenuItem hidden = menu.findItem(R.id.action_toggle_hidden);
        if (byName != null) {
            byName.setChecked(!sortByTime);
        }
        if (byTime != null) {
            byTime.setChecked(sortByTime);
        }
        if (hidden != null) {
            hidden.setChecked(showHidden);
        }
    }

    /** 返回上一级；返回是否消费了本次操作。 */
    private boolean goUp() {
        if (currentDir == null) {
            return false;
        }
        File root = storageRoot();
        if (currentDir.equals(root)) {
            return false;
        }
        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            openDir(parent);
            return true;
        }
        openDir(root);
        return true;
    }

    private void refreshSelectionLabel() {
        tvSelected.setText(selected.isEmpty() ? ""
                : getString(R.string.file_browser_selected_count, selected.size()));
    }

    private void toggleSelection(File file) {
        String path = file.getAbsolutePath();
        if (selected.contains(path)) {
            selected.remove(path);
        } else {
            if (selected.size() >= MAX_SELECTION) {
                UiUtils.toast(this, R.string.file_browser_selected_count, MAX_SELECTION);
                return;
            }
            selected.add(path);
        }
        refreshSelectionLabel();
        adapter.notifyDataSetChanged();
    }

    private void finishWithSelection() {
        if (selected.isEmpty()) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }
        Intent result = new Intent();
        result.putStringArrayListExtra(EXTRA_PATHS, new ArrayList<>(selected));
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private class EntryAdapter extends RecyclerView.Adapter<EntryHolder> {

        @NonNull
        @Override
        public EntryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_browser_entry, parent, false);
            return new EntryHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull EntryHolder holder, int position) {
            final File file = entries.get(position);
            boolean directory = file.isDirectory();
            holder.name.setText(file.getName());
            holder.type.setImageResource(directory
                    ? R.drawable.ic_folder : R.drawable.ic_file);
            if (directory) {
                holder.size.setText("");
                holder.checkBox.setVisibility(View.GONE);
            } else {
                holder.size.setText(Formatters.size(file.length()));
                holder.checkBox.setVisibility(View.VISIBLE);
                holder.checkBox.setChecked(selected.contains(file.getAbsolutePath()));
            }
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (directory) {
                        openDir(file);
                    } else {
                        toggleSelection(file);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    static class EntryHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView size;
        final ImageView type;
        final CheckBox checkBox;

        EntryHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tv_name);
            size = itemView.findViewById(R.id.tv_size);
            type = itemView.findViewById(R.id.iv_type);
            checkBox = itemView.findViewById(R.id.cb_select);
        }
    }
}
