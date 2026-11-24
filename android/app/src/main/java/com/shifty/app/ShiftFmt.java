package com.shifty.app;

public class ShiftFmt {
    static final String AR = "٠١٢٣٤٥٦٧٨٩";

    public static String ar(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? AR.charAt(c - '0') : c);
        return b.toString();
    }

    static int h12(int h) {
        int r = h % 12;
        return r == 0 ? 12 : r;
    }

    /** تسمية فترة اليوم لقيمة ٢٤ ساعة صريحة (تعديلات المستخدم). */
    static String part24(int h) {
        if (h >= 5 && h <= 11) return "الصبح";
        if (h >= 12 && h <= 14) return "الظهر";
        if (h >= 15 && h <= 17) return "العصر";
        if (h >= 18 && h <= 19) return "المغرب";
        return "بالليل";
    }

    /** بداية الشيفت باتفاق الورقة: 8-12 زي ما هي (ص)، 1-7 بعد الظهر (م). */
    public static int sheetStartTo24(int v) {
        return v >= 1 && v <= 7 ? v + 12 : v;
    }

    static String partSheetStart(int v) {
        if (v >= 8 && v <= 11) return "الصبح";
        if (v == 12 || v == 1 || v == 2) return "الظهر";
        if (v >= 3 && v <= 5) return "العصر";
        return "بالليل";
    }

    static String partSheetEnd(int v) {
        if (v == 12 || v == 1 || v == 2) return "الظهر";
        if (v == 3 || v == 4) return "العصر";
        if (v >= 5 && v <= 7) return "المغرب";
        if (v >= 8 && v <= 11) return "بالليل";
        return "بالليل";
    }

    /** نهاية الشيفت باتفاق الورقة (اقتراح للمراجعة بس): 1-11 بعد الظهر/بالليل. */
    public static int sheetEndTo24(int v) {
        return v >= 1 && v <= 11 ? v + 12 : v;
    }

    /** يعيد {من, إلى} كما هو مكتوب أو null. */
    public static int[] parse(String c) {
        if (c == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2})-(\\d{1,2})$").matcher(c.trim());
        if (!m.matches()) return null;
        int s = Integer.parseInt(m.group(1));
        int e = Integer.parseInt(m.group(2));
        if (s > 24 || e > 24) return null;
        return new int[]{s, e};
    }

    /**
     * نص الخلية للعرض.
     * explicit=false: قيمة ورقة الجدول (9-5 يعني من ٩ الصبح لـ ٥ المغرب).
     * explicit=true: تعديل صريح من المستخدم بصيغة ٢٤ ساعة (14-22).
     * الخلايا الملتبسة مفروض تكون مدخلة «؟» في الجدول نفسه — التطبيق مش بيخمن اتجاهها.
     */
    public static String cellText(String c, boolean explicit) {
        if (c == null || c.isEmpty() || c.equals("?") || c.equals("\u061F")) return "غير واضح — اضغط للتحديد";
        if (c.equals("off")) return "أجازة";
        if (c.equals("online")) return "أونلاين (من البيت) — بدون تنبيه تلقائي";
        int[] t = parse(c);
        if (t != null) {
            if (explicit)
                return "من " + ar(String.valueOf(h12(t[0]))) + " " + part24(t[0])
                        + " لـ " + ar(String.valueOf(h12(t[1]))) + " " + part24(t[1]);
            return "من " + ar(String.valueOf(t[0])) + " " + partSheetStart(t[0])
                    + " لـ " + ar(String.valueOf(t[1])) + " " + partSheetEnd(t[1]);
        }
        return c;
    }
}
