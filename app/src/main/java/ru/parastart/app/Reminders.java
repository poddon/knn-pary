package ru.parastart.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.util.*;

/** Schedules a notification a few minutes before each pair of the chosen group. */
public class Reminders {
  static final String CHANNEL = "pairs";
  static final int MAX = 60, LEAD_MIN = 3;

  static void channel(Context c) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    if (nm.getNotificationChannel(CHANNEL) == null) {
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Начало пар", NotificationManager.IMPORTANCE_HIGH);
      ch.setDescription("Напоминание перед каждой парой с кнопкой входа");
      nm.createNotificationChannel(ch);
    }
  }

  static boolean auto(Context c) { return c.getSharedPreferences(MainActivity.PREFS, 0).getBoolean(MainActivity.AUTO, true); }

  static boolean canPopUp(Context c) { return android.provider.Settings.canDrawOverlays(c); }

  static boolean enabled(Context c) { return c.getSharedPreferences(MainActivity.PREFS, 0).getBoolean(MainActivity.REMIND, true); }

  static PendingIntent alarm(Context c, int id, Intent extras, int flags) {
    Intent i = new Intent(c, ReminderReceiver.class);
    i.setAction("ru.parastart.app.PAIR_" + id);
    if (extras != null) i.putExtras(extras);
    return PendingIntent.getBroadcast(c, id, i, flags | PendingIntent.FLAG_IMMUTABLE);
  }

  /** Re-plans all reminders from the stored schedules. Returns how many were set. */
  static int schedule(Context c) {
    AlarmManager am = c.getSystemService(AlarmManager.class);
    for (int id = 0; id < MAX; id++) {
      PendingIntent old = alarm(c, id, null, PendingIntent.FLAG_NO_CREATE);
      if (old != null) { am.cancel(old); old.cancel(); }
    }
    String group = c.getSharedPreferences(MainActivity.PREFS, 0).getString(MainActivity.GROUP, "");
    boolean auto = auto(c);
    if ((!enabled(c) && !auto) || group.isEmpty()) return 0;

    HashMap<String, ArrayList<String[]>> bySurname = MainActivity.surnameMap(c);
    long now = System.currentTimeMillis();
    int id = 0;
    for (ScheduleParser.Sheet sh : MainActivity.readSheets(c)) {
      ScheduleParser.Column col = sh.find(group);
      if (col == null) continue;
      String[] d = sh.date.split("\\.");
      if (d.length != 3) continue;
      int day, month, year;
      try { day = Integer.parseInt(d[0]); month = Integer.parseInt(d[1]); year = Integer.parseInt(d[2]); } catch (NumberFormatException e) { continue; }
      if (year < 100) year += 2000;
      for (ScheduleParser.Lesson l : col.lessons) {
        int[] mm = MainActivity.minutes(l.time);
        if (mm == null || id >= MAX) continue;
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day, mm[0] / 60, mm[0] % 60, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();
        long at = start - (auto ? 1 : LEAD_MIN) * 60_000L;
        Calendar endCal = Calendar.getInstance();
        endCal.set(year, month - 1, day, mm[1] / 60, mm[1] % 60, 0);
        long end = endCal.getTimeInMillis();
        if ((auto ? end - 5 * 60_000L : start + 10 * 60_000L) < now) continue;
        if (at < now) at = now + 2000;

        String url = null, who = "";
        ArrayList<String> names = new ArrayList<>();
        for (String w : l.teachers) {
          ArrayList<String[]> cand = MainActivity.lookup(bySurname, w);
          if (cand.size() == 1) { names.add(cand.get(0)[0]); if (url == null) url = cand.get(0)[1]; }
          else names.add(w);
        }
        who = android.text.TextUtils.join(", ", names);
        Intent extras = new Intent();
        extras.putExtra("title", l.num + " пара в " + l.time.split("–")[0] + (l.subject.isEmpty() ? "" : " · " + l.subject));
        extras.putExtra("text", who.isEmpty() ? "Нажми, чтобы открыть расписание" : who + (url != null ? " · нажми, чтобы войти" : ""));
        extras.putExtra("joinTitle", l.subject.isEmpty() ? who : l.subject);
        if (url != null) extras.putExtra("url", url);
        extras.putExtra("id", id);
        extras.putExtra("end", end);
        extras.putExtra("auto", auto && url != null);
        PendingIntent pi = alarm(c, id, extras, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        id++;
      }
    }
    return id;
  }

  static void show(Context c, Intent data) {
    channel(c);
    int id = data.getIntExtra("id", 0);
    String url = data.getStringExtra("url");
    boolean auto = data.getBooleanExtra("auto", false);
    Intent open;
    if (url != null) {
      open = new Intent(c, JoinActivity.class);
      open.putExtra(JoinActivity.EXTRA_URL, url);
      open.putExtra(JoinActivity.EXTRA_TITLE, data.getStringExtra("joinTitle"));
      open.putExtra(JoinActivity.EXTRA_AUTO, auto);
      open.putExtra(JoinActivity.EXTRA_END, data.getLongExtra("end", 0));
    } else {
      open = new Intent(c, MainActivity.class);
    }
    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent content = PendingIntent.getActivity(c, 1000 + id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String text = data.getStringExtra("text");
    if (auto) {
      text = text.replace(" · нажми, чтобы войти", "") + " · захожу автоматически";
      if (canPopUp(c)) {
        try { c.startActivity(open); } catch (Exception ignored) {}
      }
    }
    Notification.Builder b = new Notification.Builder(c, CHANNEL)
        .setSmallIcon(R.drawable.ic_notify)
        .setColor(MainActivity.PRIMARY)
        .setContentTitle(data.getStringExtra("title"))
        .setContentText(text)
        .setStyle(new Notification.BigTextStyle().bigText(text))
        .setCategory(auto ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(content);
    if (auto && !canPopUp(c)) b.setFullScreenIntent(content, true);
    if (url != null) b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Войти на пару", content).build());
    c.getSystemService(NotificationManager.class).notify(2000 + id, b.build());
  }
}
