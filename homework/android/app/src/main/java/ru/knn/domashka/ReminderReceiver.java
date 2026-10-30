package ru.knn.domashka;

import android.content.*;

public class ReminderReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    String a = i.getAction();
    if (a == null) return;
    if (a.startsWith("ru.knn.domashka.REMIND_")) Reminders.show(c, i);
    else Reminders.schedule(c);
  }
}
