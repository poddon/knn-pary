package ru.parastart.app;

import android.content.*;

public class ReminderReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    String a = i.getAction();
    if (a == null) return;
    if (a.equals(Intent.ACTION_BOOT_COMPLETED) || a.equals(Intent.ACTION_MY_PACKAGE_REPLACED)
        || a.equals("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) {
      Reminders.schedule(c);
    } else if (a.startsWith("ru.parastart.app.PAIR_")) {
      Reminders.show(c, i);
    }
  }
}
