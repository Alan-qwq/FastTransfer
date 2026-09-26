package com.alan.fasttransfer.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.alan.fasttransfer.R;

/**
 * 通用对话框。
 */
public final class DialogHelper {

    private DialogHelper() {
    }

    public interface OnText {
        void onText(String value);
    }

    /** 数字输入回调。 */
    public interface OnNumber {
        void onNumber(int value);
    }

    /**
     * 数字输入对话框。
     *
     * <p>输入非法或越界时就地报错，不回调、也不会崩 —— 数字输入必须自己做校验，
     * 否则一个 0 或空串就可能把后面的 density 计算带崩。</p>
     */
    public static void promptNumber(FragmentActivity activity, String title, String hint,
                                    String initial, final int min, final int max,
                                    final OnNumber callback) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final EditText input = new EditText(activity);
        input.setHint(hint == null ? "" : hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(initial == null ? "" : initial);
        input.setSelection(input.getText().length());
        input.setSingleLine(true);

        int padding = (int) (activity.getResources().getDisplayMetrics().density * 20);
        FrameLayout container = new FrameLayout(activity);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(R.string.action_confirm, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                int value;
                                try {
                                    value = Integer.parseInt(input.getText().toString().trim());
                                } catch (NumberFormatException e) {
                                    input.setError(activity.getString(
                                            R.string.number_input_invalid, min, max));
                                    return;
                                }
                                if (value < min || value > max) {
                                    input.setError(activity.getString(
                                            R.string.number_input_invalid, min, max));
                                    return;
                                }
                                dialog.dismiss();
                                if (callback != null) {
                                    callback.onNumber(value);
                                }
                            }
                        });
            }
        });
        dialog.show();
    }

    /** 单行输入对话框。 */
    public static void promptText(FragmentActivity activity, String title, String hint,
                                  String initial, int inputType, final OnText callback) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final EditText input = new EditText(activity);
        input.setHint(hint == null ? "" : hint);
        input.setInputType(inputType);
        input.setText(initial == null ? "" : initial);
        input.setSelection(input.getText().length());
        input.setSingleLine(true);

        int padding = (int) (activity.getResources().getDisplayMetrics().density * 20);
        FrameLayout container = new FrameLayout(activity);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(R.string.action_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (callback != null) {
                            callback.onText(input.getText().toString().trim());
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** PIN 输入。 */
    public static void promptPin(FragmentActivity activity, String title, final OnText callback) {
        promptText(activity, title, activity.getString(R.string.settings_pin_value), "",
                InputType.TYPE_CLASS_NUMBER, callback);
    }

    public static void confirm(FragmentActivity activity, String title, String message,
                               final Runnable onConfirm) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.action_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (onConfirm != null) {
                            onConfirm.run();
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 单选列表（用于选择保存目录等）。 */
    public static void choose(FragmentActivity activity, String title, String[] options,
                              int checkedIndex, final OnText callback) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(options, checkedIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (callback != null) {
                            callback.onText(String.valueOf(which));
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void info(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    /**
     * 「标题 + 正文」对话框，正文可以一键复制。
     *
     * <p>复制按钮是必需的：网页传输的地址要敲进电脑浏览器，
     * 让用户照着长按选中一个 {@code http://192.168.1.7:53317/} 太难为人了。</p>
     */
    public static void showMessage(FragmentActivity activity, String title, String message) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.action_confirm, null)
                .setNeutralButton(R.string.action_copy, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyFirstUrl(activity, message);
                    }
                })
                .show();
    }

    /** 把正文里第一段 http(s) 地址放进剪贴板。 */
    private static void copyFirstUrl(Context context, String message) {
        String url = firstUrl(message);
        if (url == null) {
            return;
        }
        try {
            ClipboardManager manager = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("url", url));
                Toast.makeText(context, R.string.action_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    static String firstUrl(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf("http://");
        if (start < 0) {
            start = text.indexOf("https://");
        }
        if (start < 0) {
            return null;
        }
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                break;
            }
            end++;
        }
        return text.substring(start, end);
    }
}
