package com.alan.fasttransfer.core.transfer;

import android.os.Handler;
import android.os.Looper;

import com.alan.fasttransfer.core.AppSettings;
import com.alan.fasttransfer.core.util.Json;
import com.alan.fasttransfer.core.util.Logs;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.reflect.TypeToken;

/**
 * 传输历史记录（最多保留 200 条），JSON 持久化到 SharedPreferences。
 */
public class TransferHistory {

    private static final String TAG = "TransferHistory";
    private static final int MAX_ENTRIES = 200;
    private static final Type LIST_TYPE = new TypeToken<List<HistoryEntry>>() {
    }.getType();

    private final AppSettings settings;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<HistoryEntry> entries = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    public interface Listener {
        void onHistoryChanged();
    }

    public TransferHistory(AppSettings settings) {
        this.settings = settings;
        load();
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized List<HistoryEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized void add(HistoryEntry entry) {
        if (entry == null) {
            return;
        }
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        persist();
        notifyChanged();
    }

    public synchronized void clear() {
        entries.clear();
        persist();
        notifyChanged();
    }

    private void load() {
        String json = settings.getHistoryJson();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            List<HistoryEntry> loaded = Json.fromJson(json, LIST_TYPE);
            if (loaded != null) {
                entries.clear();
                entries.addAll(loaded);
            }
        } catch (Throwable t) {
            Logs.w(TAG, "load history failed: " + t.getMessage());
        }
    }

    private void persist() {
        try {
            settings.setHistoryJson(Json.toJson(entries));
        } catch (Throwable t) {
            Logs.w(TAG, "persist history failed: " + t.getMessage());
        }
    }

    private void notifyChanged() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                List<Listener> copy;
                synchronized (TransferHistory.this) {
                    copy = new ArrayList<>(listeners);
                }
                for (Listener listener : copy) {
                    listener.onHistoryChanged();
                }
            }
        });
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized List<HistoryEntry> immutable() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
