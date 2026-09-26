package com.alan.fasttransfer.core.util;

import java.util.Locale;

/**
 * 语言相关的小工具。
 */
public final class LocaleUtil {

    private LocaleUtil() {
    }

    public static boolean isChinese() {
        Locale locale = Locale.getDefault();
        if (locale == null || locale.getLanguage() == null) {
            return false;
        }
        return locale.getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }
}
