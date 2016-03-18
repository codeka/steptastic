package au.com.codeka.steptastic;

import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Generates some encouraging notifications when you reach various step goals throughout the day.
 */
public class NotificationGenerator {
  private static final NotificationDetails[] NOTIFICATIONS = {
      new NotificationDetails(52, "Apple", "apple.jpg"),
      new NotificationDetails(90, "handful of Almond", "almonds.jpg"),
      new NotificationDetails(220, "Club sandwich", "club_sandwich.jpg"),
      new NotificationDetails(371, "Slice of cake", "cake.jpg"),
      new NotificationDetails(452, "Donut", "donut.jpg"),
  };

  /**
   * Sets the current step count to the given value for today.
   */
  public void setCurrentStepCount(long steps) {
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(App.i);
    if (!preferences.getBoolean("au.com.codeka.steptastic.CountCalories", true)) {
      return;
    }

    if (!preferences.getBoolean("au.com.codeka.steptastic.ShowNotifications", true)) {
      return;
    }

    float calories = stepsToCalories(preferences, steps);
    float lastCalorieCount = preferences.getFloat("NotificationGenerator.LastCalorieCount", 0);
    if (calories <= lastCalorieCount) {
      preferences.edit()
          .putFloat("NotificationGenerator.LastCalorieCount", calories).commit();
      return;
    }

    for (NotificationDetails notificationDetails : NOTIFICATIONS) {
      if (lastCalorieCount < notificationDetails.calories
          && notificationDetails.calories <= calories) {
        showNotification(notificationDetails, steps);
      }
    }

    preferences.edit().putFloat("NotificationGenerator.LastCalorieCount", calories).commit();
  }

  /**
   * Shows the given notification when we've crossed the threshold of steps.
   */
  private void showNotification(NotificationDetails notificationDetails, long steps) {
    Resources resources = App.i.getResources();

    NotificationCompat.BigTextStyle bigStyle = new NotificationCompat.BigTextStyle();
    bigStyle.bigText(String.format(Locale.ENGLISH,
        resources.getString(R.string.notification_content_long),
        notificationDetails.message, steps));

    NotificationCompat.WearableExtender wearableExtender =
        new NotificationCompat.WearableExtender()
            .setHintHideIcon(true);

    NotificationCompat.Builder notificationBuilder =
        new NotificationCompat.Builder(App.i)
            .setSmallIcon(R.drawable.ic_launcher)
            .setLargeIcon(loadBitmap(notificationDetails.bitmapFileName))
            .setContentTitle(resources.getString(R.string.notification_title))
            .setContentText(String.format(Locale.ENGLISH,
                resources.getString(R.string.notification_content_short),
                notificationDetails.message))
            .setStyle(bigStyle)
            .extend(wearableExtender);

    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(App.i);
    notificationManager.notify(0, notificationBuilder.build());
  }

  public Bitmap loadBitmap(String filePath) {
    AssetManager assetManager = App.i.getAssets();

    try {
      InputStream ins = assetManager.open(filePath);
      return BitmapFactory.decodeStream(ins);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Guestimate how many calories you've burnt, given you've walked a given number of steps.
   */
  public static float stepsToCalories(SharedPreferences preferences, long steps) {
    float heightInCm = Float.parseFloat(preferences.getString("au.com.codeka.steptastic.Height", "0"));
    float weightInKg = Float.parseFloat(preferences.getString("au.com.codeka.steptastic.Weight", "0"));
    if (heightInCm == 0) {
      heightInCm = 171; // Average height of American person according to Google
    }
    if (weightInKg == 0) {
      weightInKg = 83; // Average weight of American person according to Google
    }

    final float strideLengthInCm = heightInCm * 0.414f;
    final float distanceInCm = strideLengthInCm * steps;
    final float distanceInKm = distanceInCm / 100.0f / 1000.0f;

    final float caloriesPerKm = 0.68f * weightInKg;
    return caloriesPerKm * distanceInKm;
  }

  private static class NotificationDetails {
    public final int calories;
    public final String message;
    public final String bitmapFileName;

    public NotificationDetails(int calories, String message, String bitmapFileName) {
      this.calories = calories;
      this.message = message;
      this.bitmapFileName = bitmapFileName;
    }
  }
}
