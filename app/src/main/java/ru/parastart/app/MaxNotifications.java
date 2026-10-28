package ru.parastart.app;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.content.SharedPreferences;
import java.util.ArrayList;
public class MaxNotifications extends NotificationListenerService {
 @Override public void onNotificationPosted(StatusBarNotification sbn){
  String pkg=sbn.getPackageName().toLowerCase();
  if(!(pkg.contains("max")||pkg.contains("maxim")))return;
  if(sbn.getNotification().extras==null)return;
  CharSequence title=sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
  CharSequence body=sbn.getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
  if(body==null)body=sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
  if(body==null)return;
  ArrayList<String> found=MainActivity.extract(body.toString());
  if(found.isEmpty())return;
  SharedPreferences p=getSharedPreferences(MainActivity.PREFS,0);
  String saved=p.getString(MainActivity.KEY,"");
  for(String url:found)if(!java.util.Arrays.asList(saved.split("\n")).contains(url))saved=url+"\n"+saved;
  p.edit().putString(MainActivity.KEY,saved).apply();
 }
}
