package com.alan.fasttransfer;

import android.app.Application;
import android.os.StrictMode;

import androidx.appcompat.app.AppCompatDelegate;

import com.alan.fasttransfer.core.AppSettings;
import com.alan.fasttransfer.core.TransferEngine;
import com.alan.fasttransfer.core.util.Logs;

/**
 * 应用入口。
 */
public class FastTransferApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Logs.setEnabled(BuildConfig.DEBUG);
        if (BuildConfig.DEBUG) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyLog()
                    .build();
            StrictMode.setThreadPolicy(policy);
        }
        applyNightMode(new AppSettings(this).getNightMode());
        // 提前初始化引擎，保证接收服务尽早可用
        TransferEngine.get(this);
    }

    /** 把设置里的深色模式应用到全局。 */
    public static void applyNightMode(int mode) {
        switch (mode) {
            case AppSettings.NIGHT_ON:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case AppSettings.NIGHT_OFF:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
