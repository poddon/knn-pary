package ru.parastart.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** The schedule published by the admin (poddon.github.io/knn-pary/admin.html), merged into the stored days. */
public class Shared {
  static final String SOURCE = "https://raw.githubusercontent.com/poddon/knn-pary/data/schedule.json";
  static final String ACTION = "ru.parastart.app.SYNC";
  static final Object LOCK = new Object();

  static final String REPO = "poddon/knn-pary";

  static String get(String url, String accept) throws java.io.IOException {
    HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
    h.setConnectTimeout(15000);
    h.setReadTimeout(20000);
    h.setUseCaches(false);
    if (accept != null) h.setRequestProperty("Accept", accept);
    if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
    StringBuilder sb = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) sb.append(line).append('\n');
    }
    return sb.toString();
  }

  /** The newest published file: by commit (no CDN delay), or by branch when GitHub limits requests. */
  static String download() throws java.io.IOException {
    try {
      String sha = get("https://api.github.com/repos/" + REPO + "/commits/data", "application/vnd.github.sha").trim();
      if (!sha.matches("[0-9a-f]{40}")) throw new java.io.IOException("bad sha");
      return get("https://raw.githubusercontent.com/" + REPO + "/" + sha + "/schedule.json", null);
    } catch (java.io.IOException e) {
      return get(SOURCE + "?t=" + System.currentTimeMillis(), null);
    }
  }

  /** Downloads and merges the published days. Blocking: call off the main thread.
   *  Returns 1 when the days changed, 0 when nothing new, -1 when there is no connection. */
  static int sync(Context c) {
    android.content.SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, 0);
    String text;
    try {
      text = download();
    } catch (Exception e) {
      p.edit().putBoolean("shared_ok", false).putLong("shared_checked", System.currentTimeMillis()).apply();
      return -1;
    }
    synchronized (LOCK) {
      try {
        JSONObject root = new JSONObject(text);
        p.edit().putBoolean("shared_ok", true).putLong("shared_checked", System.currentTimeMillis())
            .putString("shared_updated", root.optString("updated", "")).apply();
        ArrayList<ScheduleParser.Sheet> incoming = MainActivity.parseSheets(root.optJSONArray("sheets"));
        for (ScheduleParser.Sheet s : incoming) s.shared = true;
        ArrayList<ScheduleParser.Sheet> all = MainActivity.readSheets(c);
        String before = MainActivity.sheetsJson(onlyShared(all)).toString();
        ArrayList<ScheduleParser.Sheet> out = new ArrayList<>();
        for (ScheduleParser.Sheet s : all) {
          if (s.shared) continue;
          boolean replaced = false;
          for (ScheduleParser.Sheet n : incoming) if (n.date.equals(s.date) && n.course.equals(s.course)) replaced = true;
          if (!replaced) out.add(s);
        }
        out.addAll(incoming);
        out.sort((a, b) -> Long.compare(MainActivity.dayKey(b.date), MainActivity.dayKey(a.date)));
        while (out.size() > 20) out.remove(out.size() - 1);
        if (MainActivity.sheetsJson(onlyShared(out)).toString().equals(before)) return 0;
        MainActivity.writeSheets(c, out);
        Reminders.schedule(c);
        return 1;
      } catch (Exception e) {
        return 0;
      }
    }
  }

  static List<ScheduleParser.Sheet> onlyShared(List<ScheduleParser.Sheet> list) {
    List<ScheduleParser.Sheet> r = new ArrayList<>();
    for (ScheduleParser.Sheet s : list) if (s.shared) r.add(s);
    return r;
  }

  /** Checks for a new published schedule about every 30 minutes, also when the app is closed. */
  static void plan(Context c) {
    AlarmManager am = c.getSystemService(AlarmManager.class);
    Intent i = new Intent(c, ReminderReceiver.class).setAction(ACTION);
    PendingIntent pi = PendingIntent.getBroadcast(c, 9001, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 60_000L, AlarmManager.INTERVAL_HALF_HOUR, pi);
  }
}
