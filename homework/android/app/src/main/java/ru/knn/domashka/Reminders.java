package ru.knn.domashka;

import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;

/** Deadline reminders: the page sends the list, alarms fire notifications even when the app is closed. */
public class Reminders {
  static final String CHANNEL = "deadlines";
  static final int MAX = 60;

  static void channel(Context c) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    if (nm.getNotificationChannel(CHANNEL) == null) {
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Сроки сдачи", NotificationManager.IMPORTANCE_HIGH);
      ch.setDescription("Напоминания о сроках домашних заданий");
      nm.createNotificationChannel(ch);
    }
  }

  static PendingIntent alarm(Context c, int id, Intent extras, int flags) {
    Intent i = new Intent(c, ReminderReceiver.class).setAction("ru.knn.domashka.REMIND_" + id);
    if (extras != null) i.putExtras(extras);
    return PendingIntent.getBroadcast(c, id, i, flags | PendingIntent.FLAG_IMMUTABLE);
  }

  static void set(Context c, JSONArray items) {
    c.getSharedPreferences(MainActivity.PREFS, 0).edit().putString("reminders", items == null ? "[]" : items.toString()).apply();
    schedule(c);
  }

  static void schedule(Context c) {
    channel(c);
    AlarmManager am = c.getSystemService(AlarmManager.class);
    for (int id = 0; id < MAX; id++) {
      PendingIntent old = alarm(c, id, null, PendingIntent.FLAG_NO_CREATE);
      if (old != null) { am.cancel(old); old.cancel(); }
    }
    JSONArray items;
    try { items = new JSONArray(c.getSharedPreferences(MainActivity.PREFS, 0).getString("reminders", "[]")); } catch (JSONException e) { return; }
    long now = System.currentTimeMillis();
    int id = 0;
    for (int i = 0; i < items.length() && id < MAX; i++) {
      JSONObject o = items.optJSONObject(i);
      if (o == null) continue;
      long at = o.optLong("at");
      if (at < now) continue;
      Intent x = new Intent().putExtra("title", o.optString("title")).putExtra("text", o.optString("text")).putExtra("id", id);
      PendingIntent pi = alarm(c, id, x, PendingIntent.FLAG_UPDATE_CURRENT);
      if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
      else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
      id++;
    }
  }

  static void show(Context c, Intent data) {
    channel(c);
    Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent content = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String text = data.getStringExtra("text");
    Notification n = new Notification.Builder(c, CHANNEL)
        .setSmallIcon(R.drawable.ic_notify)
        .setColor(0xFF0E7C66)
        .setContentTitle(data.getStringExtra("title"))
        .setContentText(text)
        .setStyle(new Notification.BigTextStyle().bigText(text))
        .setCategory(Notification.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(content)
        .build();
    c.getSystemService(NotificationManager.class).notify(3000 + data.getIntExtra("id", 0), n);
  }
}
