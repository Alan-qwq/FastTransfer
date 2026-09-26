package com.alan.fasttransfer.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.transfer.HistoryEntry;
import com.alan.fasttransfer.core.util.FileOpener;

/**
 * 传输记录页。
 */
public class HistoryFragment extends BaseTabFragment {

    private HistoryAdapter adapter;
    private View emptyView;
    private View clearButton;
    /** 入场动画只播一次。 */
    private boolean entrancePlayed;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    protected void onBindViews(View view, @Nullable Bundle savedInstanceState) {
        RecyclerView list = view.findViewById(R.id.rv_history);
        emptyView = view.findViewById(R.id.ll_history_empty);
        clearButton = view.findViewById(R.id.btn_clear_history);

        adapter = new HistoryAdapter();
        adapter.setOnEntryClick(new HistoryAdapter.OnEntryClick() {
            @Override
            public void onEntryClick(HistoryEntry entry) {
                openEntry(entry);
            }
        });
        list.setLayoutManager(new LinearLayoutManager(getActivity()));
        list.setAdapter(adapter);
        if (list.getItemAnimator() != null) {
            list.getItemAnimator().setAddDuration(220);
            list.getItemAnimator().setRemoveDuration(180);
            list.getItemAnimator().setChangeDuration(200);
        }

        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DialogHelper.confirm(requireActivity(), getString(R.string.history_title),
                        getString(R.string.history_clear_confirm), new Runnable() {
                            @Override
                            public void run() {
                                if (history() != null) {
                                    history().clear();
                                }
                                UiUtils.toast(getActivity(), R.string.history_cleared);
                                refresh();
                            }
                        });
            }
        });
        refresh();
    }

    /**
     * 点一条记录：用别的应用打开它。
     *
     * <p>收到文件后用户最自然的动作就是「打开看看」，所以这里直接跳转，
     * 而不是只显示一个保存路径让人自己去翻文件管理器。</p>
     */
    private void openEntry(HistoryEntry entry) {
        if (entry == null || !isAdded()) {
            return;
        }
        // 文字消息没有文件可开，改成复制内容
        if (entry.isText()) {
            UiUtils.copyToClipboard(getActivity(), entry.textPreview);
            UiUtils.toast(getActivity(), R.string.history_text_copied);
            return;
        }
        if (entry.savedUris == null || entry.savedUris.isEmpty()) {
            UiUtils.toast(getActivity(), R.string.history_open_no_file);
            return;
        }
        // 多个文件时让用户挑一个，别默默开第一个
        if (entry.savedUris.size() > 1) {
            final String[] names = new String[entry.savedUris.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = getString(R.string.history_file_index, i + 1);
            }
            DialogHelper.choose(requireActivity(), getString(R.string.history_pick_file), names, -1,
                    new DialogHelper.OnText() {
                        @Override
                        public void onText(String value) {
                            try {
                                int index = Integer.parseInt(value);
                                if (index >= 0 && index < entry.savedUris.size()) {
                                    FileOpener.open(getActivity(),
                                            entry.savedUris.get(index), entry.firstMime);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    });
            return;
        }
        FileOpener.open(getActivity(), entry.savedUris.get(0), entry.firstMime);
    }

    @Override
    public void onShown() {
        refresh();
        if (!entrancePlayed && getView() != null && adapter.size() > 0) {
            entrancePlayed = true;
            UiUtils.playListEntrance(getView(), R.id.rv_history);
        }
    }

    private void refresh() {
        if (!isAdded() || adapter == null || history() == null) {
            return;
        }
        adapter.submit(history().snapshot());
        boolean empty = adapter.size() == 0;
        UiUtils.setVisible(emptyView, empty);
        UiUtils.setVisible(clearButton, !empty);
    }
}
