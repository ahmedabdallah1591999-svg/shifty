package com.shifty.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    JSONObject roster;
    JSONArray emps;
    SharedPreferences sp;
    LinearLayout weekBox;
    CheckBox confirm;
    TextView status;
    Spinner spinner;
    RadioGroup timing;
    String me;

    int dp(int d) { return (int) (d * getResources().getDisplayMetrics().density); }

    TextView tv(String s, float spSize, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(spSize);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        sp = getSharedPreferences("shifty", MODE_PRIVATE);
        try {
            roster = Scheduler.loadRoster(this);
        } catch (Exception e) {
            roster = null;
        }
        if (roster == null) { showImport(); return; }
        showMain();
    }

    /** شاشة الاستيراد الخاص — التطبيق من غير أي جدول مدمج. */
    void showImport() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = dp(20);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        TextView title = tv("شيفتي", 28, true);
        title.setTextColor(Color.rgb(13, 148, 136));
        root.addView(title);
        root.addView(tv("التطبيق مفيهوش أي جدول مدمج عشان خصوصية الفريق. هتاخد الجدول من مديرك (لصق JSON)، وبيتخزن على جهازك بس.", 14, false));

        final EditText box = new EditText(this);
        box.setHint("الصق الجدول هنا (JSON)");
        box.setMinLines(6);
        box.setTextSize(13);
        root.addView(box);

        Button imp = btn("استيراد الجدول");
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String txt = box.getText().toString().trim();
                String err = validateAndSave(txt);
                if (err != null) { toast(err); return; }
                recreate();
            }
        });
        root.addView(imp);
    }

    /** تحقق من صيغة الجدول قبل الحفظ: لازم تاريخ بداية ونهاية أسبوع وقائمة موظفين. */
    String validateAndSave(String txt) {
        try {
            JSONObject r = new JSONObject(txt);
            if (Scheduler.weekEndMillis(r) == 0) return "الجدول ناقصه week_end بصيغة yyyy-MM-dd";
            JSONArray arr = r.getJSONArray("employees");
            if (arr.length() == 0) return "الجدول مفيهوش موظفين";
            sp.edit().putString("roster_json", txt)
                    .remove("me").remove("confirmed").apply();
            return null;
        } catch (Exception e) {
            return "النص ده مش JSON صالح";
        }
    }

    void showMain() {
        try {
            emps = roster.getJSONArray("employees");
        } catch (Exception e) { showImport(); return; }

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = dp(20);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        TextView title = tv("شيفتي", 28, true);
        title.setTextColor(Color.rgb(13, 148, 136));
        root.addView(title);

        if (Scheduler.isExpired(roster)) {
            TextView exp = tv("الجدول ده خلص — التنبيهات واقفة. استورد جدول الأسبوع الجديد.", 15, true);
            exp.setTextColor(Color.rgb(180, 40, 40));
            root.addView(exp);
            Button re = btn("استيراد جدول جديد");
            re.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showImport(); }
            });
            root.addView(re);
            return;
        }

        String wk = roster.optString("week", "");
        root.addView(tv("أسبوع: " + wk + " — التنبيهات بتقف تلقائيًا آخر الأسبوع ده.", 13, false));

        final String[] names = new String[emps.length()];
        try {
            for (int i = 0; i < emps.length(); i++) names[i] = emps.getJSONObject(i).getString("name");
        } catch (Exception ignored) {}

        spinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(ad);
        root.addView(spinner);

        weekBox = new LinearLayout(this);
        weekBox.setOrientation(LinearLayout.VERTICAL);
        weekBox.setPadding(0, dp(8), 0, dp(8));
        root.addView(weekBox);

        root.addView(tv("معاد التنبيه (اختار):", 15, true));
        timing = new RadioGroup(this);
        String[] opts = {"وقت بداية الشيفت بالظبط", "قبل الشيفت بساعة", "قبل الشيفت بساعتين"};
        final int[] vals = {0, 60, 120};
        int savedOffset = sp.getInt("offset_min", -1);
        for (int i = 0; i < opts.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(opts[i]);
            rb.setTextSize(15);
            rb.setId(i);
            timing.addView(rb);
            if (savedOffset == vals[i]) timing.check(i);
        }
        timing.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) {
                sp.edit().putInt("offset_min", vals[id]).apply();
                Scheduler.scheduleAll(MainActivity.this);
            }
        });
        root.addView(timing);

        confirm = new CheckBox(this);
        confirm.setText("راجعت مواعيدي اللي فوق وهي صحيحة");
        confirm.setTextSize(15);
        root.addView(confirm);

        Button enable = btn("فعّل التنبيهات");
        enable.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onEnable(); }
        });
        root.addView(enable);

        Button perm = btn("إذن المنبه الدقيق (مهم عشان الميعاد بالظبط)");
        perm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openExactAlarmSettings(); }
        });
        root.addView(perm);

        Button batt = btn("استثناء التطبيق من توفير البطارية (مُستحسن)");
        batt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askBatteryExemption(); }
        });
        root.addView(batt);

        Button test = btn("جرّب تنبيه بعد دقيقتين (اقفل التطبيق واستناه)");
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { scheduleTestAlarm(); }
        });
        root.addView(test);

        status = tv("", 13, false);
        root.addView(status);
        root.addView(tv("التنبيه بيتجدول للأيام المؤكدة ✓ بس. أيام الأجازة والأونلاين والخلايا غير الواضحة مفيهاش تنبيه. لو الموبايل اتقفل واتفتح، التطبيق بيعيد الجدولة لوحده جوّه نفس الأسبوع بس.", 12, false));

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                renderWeek(names[pos]);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        String savedName = sp.getString("me", null);
        if (savedName != null) {
            for (int i = 0; i < names.length; i++)
                if (names[i].equals(savedName)) { spinner.setSelection(i); break; }
        }
        if (names.length > 0) renderWeek(savedName != null ? savedName : names[0]);
    }

    void renderWeek(String name) {
        me = name;
        weekBox.removeAllViews();
        try {
            final JSONObject emp = Scheduler.findEmp(roster, name);
            if (emp == null) return;
            for (int d = 0; d < 7; d++) {
                final int dayIdx = d;
                final String day = Scheduler.DAYS[d];
                String ov = Scheduler.getOverride(this, name, day);
                String cell = emp.optString(day, "?");
                boolean today = Scheduler.todayKey().equals(day);
                TextView row;
                if (ov != null) {
                    row = tv(Scheduler.DAY_AR[d] + " — مؤكد: " + ShiftFmt.cellText(ov, true) + " ✓", 15, today);
                } else if (ShiftFmt.parse(cell) != null) {
                    row = tv(Scheduler.DAY_AR[d] + " — من الورقة: " + ShiftFmt.cellText(cell, false)
                            + " — اضغط للتأكيد", 15, today);
                    row.setTextColor(Color.rgb(180, 100, 0));
                    row.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { editCell(day); }
                    });
                } else {
                    row = tv(Scheduler.DAY_AR[d] + " — " + ShiftFmt.cellText(cell, false), 15, today);
                    if (cell.equals("?") || cell.isEmpty()) {
                        row.setTextColor(Color.rgb(180, 100, 0));
                        row.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) { editCell(day); }
                        });
                    }
                }
                weekBox.addView(row);
            }
            int missing = Scheduler.unconfirmedWorkDays(this, emp, name);
            if (missing > 0) {
                TextView w = tv("لسه " + ShiftFmt.ar(String.valueOf(missing)) + " يوم شغل محتاج تأكيد المعاد بالضغط عليه فوق — من غير تأكيد مش هيتنبه.", 13, false);
                w.setTextColor(Color.rgb(180, 100, 0));
                weekBox.addView(w);
            }
        } catch (Exception ignored) {}
    }

    /** تعديل صريح لخلية: المستخدم يحدد ساعة البداية والنهاية بنفسه بصيغة ٢٤ ساعة مسبوقة بـ ص/م. */
    void editCell(final String day) {
        final String[] labels = new String[24];
        for (int h = 0; h < 24; h++) {
            int h12 = h % 12 == 0 ? 12 : h % 12;
            String suffix = h == 0 ? "بالليل" : h < 12 ? "ص" : (h == 12 ? "الظهر" : "م");
            labels[h] = ShiftFmt.ar(String.valueOf(h12)) + " " + suffix;
        }
        final int[] pick = {9, 17};
        try {
            JSONObject emp = Scheduler.findEmp(roster, me);
            String existing = Scheduler.getOverride(this, me, day);
            String base = existing != null ? existing : (emp != null ? emp.optString(day, "") : "");
            int[] t = ShiftFmt.parse(base);
            if (t != null) {
                pick[0] = existing != null ? t[0] : ShiftFmt.sheetStartTo24(t[0]);
                pick[1] = existing != null ? t[1] : ShiftFmt.sheetEndTo24(t[1]);
            }
        } catch (Exception ignored) {}
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(tv("ساعة البداية:", 14, true));
        final Spinner s1 = new Spinner(this);
        s1.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
        s1.setSelection(pick[0]);
        box.addView(s1);
        box.addView(tv("ساعة النهاية:", 14, true));
        final Spinner s2 = new Spinner(this);
        s2.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
        s2.setSelection(pick[1]);
        box.addView(s2);

        new AlertDialog.Builder(this)
                .setTitle("حدد معاد " + Scheduler.DAY_AR[Scheduler.dayIndex(day)])
                .setView(box)
                .setPositiveButton("تأكيد", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) {
                        int s = s1.getSelectedItemPosition();
                        int e = s2.getSelectedItemPosition();
                        if (s == e) { toast("البداية والنهاية نفس الساعة — راجع الاختيار"); return; }
                        Scheduler.setOverride(MainActivity.this, me, day, s + "-" + e);
                        renderWeek(me);
                        Scheduler.scheduleAll(MainActivity.this);
                        toast("اتحفظ: " + ShiftFmt.cellText(s + "-" + e, true));
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    void scheduleTestAlarm() {
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        android.content.Intent i = new android.content.Intent(this, AlarmReceiver.class);
        i.putExtra("test", true);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 9999, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        long at = System.currentTimeMillis() + 2 * 60 * 1000;
        if (Scheduler.exactAllowed(this)) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
        status.setText("اتحجز تنبيه تجريبي بعد دقيقتين — اقفل التطبيق خالص واستناه. لو مفيش تنبيه وصل: راجع إذن الإشعارات والمنبه الدقيق من فوق.");
    }

    void onEnable() {
        if (me == null) { toast("اختار اسمك الأول"); return; }
        if (!confirm.isChecked()) { toast("أكد إن مواعيدك صح الأول"); return; }
        if (sp.getInt("offset_min", -1) < 0) { toast("اختار معاد التنبيه الأول"); return; }
        try {
            JSONObject emp = Scheduler.findEmp(roster, me);
            int missing = emp != null ? Scheduler.unconfirmedWorkDays(this, emp, me) : 0;
            if (missing > 0) {
                toast("فاضل " + ShiftFmt.ar(String.valueOf(missing)) + " يوم شغل محتاج تأكيد — اضغط على كل يوم فوق وأكد بدايته ونهايته");
                return;
            }
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            return;
        }
        if (!Scheduler.exactAllowed(this)) {
            toast("محتاج إذن «المنبهات والتذكيرات» الأول — هفتحلك الإعداد");
            openExactAlarmSettings();
            return;
        }
        sp.edit().putString("me", me).putBoolean("confirmed", true).apply();
        Scheduler.scheduleAll(this);
        refreshStatus();
        toast("اتفعلت");
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        if (req == 7 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) onEnable();
        else refreshStatus();
    }

    void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try { startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)); }
            catch (Exception ignored) {}
        }
    }

    void askBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception ignored) {}
        }
    }

    void refreshStatus() {
        if (status == null) return;
        StringBuilder s = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 31)
            s.append("المنبه الدقيق: ").append(Scheduler.exactAllowed(this) ? "متفعل" : "مش متفعل — اضغط زر الإذن فوق").append("\n");
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            s.append("توفير البطارية: ").append(pm.isIgnoringBatteryOptimizations(getPackageName())
                    ? "التطبيق مستثنى" : "عادي (ممكن يأخر التنبيه)").append("\n");
        }
        s.append("التنبيهات: ").append(sp.getBoolean("confirmed", false) ? "مجدولة" : "لسه مش متفعلة");
        status.setText(s.toString());
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }
}
