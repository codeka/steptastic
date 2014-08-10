package au.com.codeka.steptastic;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Generates some encouraging notifications when you reach various step goals throughout the day.
 */
public class NotificationGenerator {
    private long lastStepCount;

    private static final NotificationDetails[] NOTIFICATIONS = {
        new NotificationDetails(52, "Apple", "apple.jpg"),
        new NotificationDetails(90, "handful of Almond", "almonds.jpg"),
        new NotificationDetails(220, "Club sandwich", "club_sandwich.jpg"),
        new NotificationDetails(371, "Slice of cake", "cake.jpg"),
        new NotificationDetails(452, "Donut", "donut.jpg"),
    };

    /** Sets the current step count to the given value for today. */
    public void setCurrentStepCount(long steps) {
        if (steps <= lastStepCount) {
            lastStepCount = steps;
            return;
        }

        for (NotificationDetails notificationDetails : NOTIFICATIONS) {
            if (notificationDetails.steps > lastStepCount && notificationDetails.steps <= steps) {
                showNotification(notificationDetails, steps);
            }
        }
        lastStepCount = steps;
    }

    /** Shows the given notification when we've crossed the threshold of steps. */
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

    /** Guestimate for how many steps it takes to burn the given number of calories. */
    private static int caloriesToSteps(int calories) {
        // We assume approximately 2,000 steps per 100 calories, which is about right for an 80kg
        // person walking a regular pace.
        return calories * 20;
    }

    private static class NotificationDetails {
        public final int calories;
        public final int steps;
        public final String message;
        public final String bitmapFileName;

        public NotificationDetails(int calories, String message, String bitmapFileName) {
            this.calories = calories;
            this.steps = caloriesToSteps(calories);
            this.message = message;
            this.bitmapFileName = bitmapFileName;
        }
    }
}
