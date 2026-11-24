package com.shifty.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class Scheduler {
    static final String[] DAYS = {"sat", "sun", "mon", "tue", "wed", "thu", "fri"};
    static final String[] DAY_AR = {"السبت", "الأحد", "الاتنين", "التلات", "الأربع", "الخميس", "الجمعة"};
    static final int[] CAL_DAY = {Calendar.SATURDAY, Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
            Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY};

    public static int dayIndex(String key) {
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i].equals(key)) return i;
        return -1;
    }

    public static String todayKey() {
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        for (int i = 0; i < CAL_DAY.length; i++) if (CAL_DAY[i] == dow) return DAYS[i];
        return "sun";
    }

    /** الجدول بيتخزن محليًا بعد استيراد خاص — مفيش جدول مدمج في التطبيق. */
    public static JSONObject loadRoster(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences("shifty", Context.MODE_PRIVATE);
        String json = sp.getString("roster_json", null);
        if (json == null) return null;
        return new JSONObject(json);
    }

    public static JSONObject findEmp(JSONObject roster, String name) throws Exception {
        JSONArray arr = roster.getJSONArray("employees");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            if (e.getString("name").equals(name)) return e;
        }
        return null;
    }

    /** الأيام الشغالة في الجدول اللي لسه معندهاش تأكيد صريح — لازم تتأكد قبل التفعيل. */
    public static int unconfirmedWorkDays(Context ctx, JSONObject emp, String name) {
        int n = 0;
        for (int d = 0; d < 7; d++) {
            if (getOverride(ctx, name, DAYS[d]) != null) continue;
            String cell = emp.optString(DAYS[d], "?");
            if (ShiftFmt.parse(cell) != null) n++;
        }
        return n;
    }

    /** نهاية الأسبوع (آخر يوم ٢٣:٥٩:٥٩) من week_end بصيغة yyyy-MM-dd. بدونها يعتبر الجدول منتهي. */
    public static long weekEndMillis(JSONObject roster) {
        try {
            String end = roster.optString("week_end", "");
            if (end.isEmpty()) return 0;
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(end);
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            c.set(Calendar.HOUR_OF_DAY, 23);
            c.set(Calendar.MINUTE, 59);
            c.set(Calendar.SECOND, 59);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean isExpired(JSONObject roster) {
        return System.currentTimeMillis() > weekEndMillis(roster);
    }

    /** تعديل صريح من المستخدم لخلية معينة (ساعة بداية ونهاية بصيغة ٢٤ ساعة "s-e"). */
    public static String getOverride(Context ctx, String name, String day) {
        SharedPreferences sp = ctx.getSharedPreferences("shifty", Context.MODE_PRIVATE);
        return sp.getString("ov_" + name + "_" + day, null);
    }

    public static void setOverride(Context ctx, String name, String day, String value) {
        SharedPreferences sp = ctx.getSharedPreferences("shifty", Context.MODE_PRIVATE);
        if (value == null) sp.edit().remove("ov_" + name + "_" + day).apply();
        else sp.edit().putString("ov_" + name + "_" + day, value).apply();
    }

    /** الخلية الفعالة للعرض والجدولة: التعديل الصريح أولًا ثم قيمة الجدول. */
    public static String effectiveCell(Context ctx, JSONObject emp, String name, String day) {
        String ov = getOverride(ctx, name, day);
        if (ov != null) return ov;
        return emp.optString(day, "?");
    }

    public static boolean exactAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am.canScheduleExactAlarms();
    }

    static PendingIntent piFor(Context ctx, int dayIdx) {
        Intent in = new Intent(ctx, AlarmReceiver.class);
        in.putExtra("day", DAYS[dayIdx]);
        return PendingIntent.getBroadcast(ctx, dayIdx, in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        for (int d = 0; d < 7; d++) am.cancel(piFor(ctx, d));
    }

    /**
     * جدولة تنبيهات أيام الأسبوع. القواعد:
     * - لازم تأكيد الموظف واختيار توقيت التنبيه (offset_min) — بدونهم مفيش أي جدولة.
     * - off / online / غير واضح (؟): بدون تنبيه. online مش معناه معاد ثابت.
     * - مفيش أي شيفت بيتجدول من قيمة الورقة مباشرة: كل يوم شغال لازم تأكيد صريح من
     *   المستخدم لساعة البداية والنهاية بـ ص/م (بيتحفظ كتعديل override). القيمة المقروءة
     *   من الورقة بتظهر كاقتراح للمراجعة بس — التأكيد العام على الصف مش بيفعّل أي معاد.
     * - الخلايا الملتبسة الاتجاه بتدخل الجدول كـ «؟» أصلًا — التطبيق مش بيخمنها أبدًا.
     * - الجدول له أسبوع محدد بتاريخ: مفيش أي تنبيه بعد نهاية الأسبوع، ومفيش تكرار تلقائي لأسبوع جديد.
     */
    public static void scheduleAll(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("shifty", Context.MODE_PRIVATE);
        String me = sp.getString("me", null);
        boolean confirmed = sp.getBoolean("confirmed", false);
        int offset = sp.getInt("offset_min", -1);
        cancelAll(ctx);
        if (me == null || !confirmed || offset < 0) return;
        try {
            JSONObject roster = loadRoster(ctx);
            if (roster == null) return;
            long weekEnd = weekEndMillis(roster);
            if (weekEnd == 0 || System.currentTimeMillis() > weekEnd) return; // منتهي: مفيش تجديد
            JSONObject emp = findEmp(roster, me);
            if (emp == null) return;
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            for (int d = 0; d < 7; d++) {
                String ov = getOverride(ctx, me, DAYS[d]);
                if (ov == null) continue;                      // بدون تأكيد صريح = بدون تنبيه
                int[] t = ShiftFmt.parse(ov);
                if (t == null) continue;
                int startHour = t[0];                          // التأكيد الصريح ٢٤ ساعة
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.DAY_OF_WEEK, CAL_DAY[d]);
                cal.set(Calendar.HOUR_OF_DAY, startHour);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                cal.add(Calendar.MINUTE, -offset);
                if (cal.getTimeInMillis() <= System.currentTimeMillis() + 60000)
                    cal.add(Calendar.DAY_OF_YEAR, 7);
                if (cal.getTimeInMillis() > weekEnd) continue; // أبعد من نهاية الجدول
                PendingIntent pi = piFor(ctx, d);
                if (exactAllowed(ctx))
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
                else
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
        } catch (Exception ignored) {
        }
    }
}
