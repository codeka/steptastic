package au.com.codeka.steptastic;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * This {@link BroadcastReceiver} receives intent every now and then which we use to ensure the
 * {@link StepSensorService} is still running and receiving events.
 */
public class AlarmReceiver extends BroadcastReceiver {
  private static final String TAG = AlarmReceiver.class.getSimpleName();

  /**
   * This is called by the system when an alarm is received.
   */
  @Override
  public void onReceive(Context context, Intent intent) {
    // Simple schedule the next alarm to run.
    schedule(context);
  }

  /**
   * Schedule the alarm to run in five minutes.
   */
  public static void schedule(Context context) {
    // Start the sensor service.
    try {
      Intent startServiceIntent = new Intent(context, StepSensorService.class);
      context.startService(startServiceIntent);
    } catch (Exception e) {
      Log.e(TAG, "Exception caught starting service!", e);
    }

    // Make sure the alarm is running,
    AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    Intent intent = new Intent(context, AlarmReceiver.class);
    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, 0);

    long millisPerMinute = 1000 * 60;
    long fiveMinutesFromNow = System.currentTimeMillis() + 5 * millisPerMinute;

    alarmManager.set(AlarmManager.RTC, fiveMinutesFromNow, pendingIntent);
  }
}
