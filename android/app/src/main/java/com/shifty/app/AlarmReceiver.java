package com.shifty.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent.getBooleanExtra("test", false)) {
            notifyNow(ctx, "تنبيه تجريبي من شيفتي: الإشعارات والجدولة شغالة ✓");
            return;
        }
        String day = intent.getStringExtra("day");
        SharedPreferences sp = ctx.getSharedPreferences("shifty", Context.MODE_PRIVATE);
        String me = sp.getString("me", null);
        if (me != null && day != null) {
            try {
                JSONObject roster = Scheduler.loadRoster(ctx);
                // جدول منتهي الصلاحية: مفيش إشعارات خالص
                if (roster != null && !Scheduler.isExpired(roster) && Scheduler.todayKey().equals(day)) {
                    JSONObject emp = Scheduler.findEmp(roster, me);
                    if (emp != null) {
                        int di = Scheduler.dayIndex(day);
                        String cell = Scheduler.effectiveCell(ctx, emp, me, day);
                        boolean explicit = Scheduler.getOverride(ctx, me, day) != null;
                        String txt = "شيفتك النهاردة " + Scheduler.DAY_AR[di] + ": "
                                + ShiftFmt.cellText(cell, explicit);
                        notifyNow(ctx, txt);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // إعادة الجدولة — scheduleAll بتلغي كل حاجة لو الأسبوع خلص
        Scheduler.scheduleAll(ctx);
    }

    static void notifyNow(Context ctx, String body) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        String ch = "shifts";
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ch, "تنبيهات الشيفت", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(ctx, ch);
        } else {
            b = new Notification.Builder(ctx);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            return;
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification n = b.setContentTitle("شيفتي")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.notif_icon)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);
    }
}
