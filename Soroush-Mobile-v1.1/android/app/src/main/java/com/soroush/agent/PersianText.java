package com.soroush.agent;

import java.util.Locale;

public final class PersianText {
    private PersianText() {}
    public static String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT)
                .replace('ي','ی').replace('ك','ک')
                .replace("‌", " ").replaceAll("[ًٌٍَُِّْـ]", "")
                .replaceAll("\\s+", " ");
    }
    public static boolean hasAny(String s, String... xs) {
        String n = norm(s);
        for (String x : xs) if (n.contains(norm(x))) return true;
        return false;
    }
}
