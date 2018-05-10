package au.com.codeka.steptastic;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.Date;

/**
 * This is a service which listens to the TYPE_STEP_COUNTER sensor to gather step count information.
 */
public class StepSensorService extends Service {
  private static final String TAG = "StepSensorService";
  private StepCountSyncer stepCountSyncer;

  private static int lastStepCount;

  /**
   * A histogram of steps over the current day, hour-by-hour.
   */
  private static int[] histogram;

  private static Date lastHistogramDate;

  /** The minimum delay between saving the histogram data. */
  private static final long HISTOGRAM_SAVE_DELAY_MS = TimeUnit.MINUTES.toMillis(5);

  /**
   * The last time we saved the histogram to preferences. We'll just do this every
   * HISTOGRAM_SAVE_DELAY_MS milliseconds so we're not constantly writing to disk.
   */
  private static long lastHistogramSaveTime;

  private static Calendar calendar = Calendar.getInstance();

  @Override
  public IBinder onBind(Intent intent) {
    return new StepSensorBinder();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.d(TAG, "onStartCommand()");
    stepCountSyncer.connect();
    return START_STICKY;
  }

  @Override
  public void onCreate() {
    super.onCreate();

    SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
    Sensor stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
    stepCountSyncer = new StepCountSyncer(this);

    // Register for sensor events in batch mode, allowing up to 5 seconds delay before events
    // get reported. We don't care about the delay *too* much, but 5 seconds seems about right,
    // and some devices seem more inclined to follow this suggestion than others.
    sensorManager.registerListener(stepSensorEventListener, stepCounterSensor,
        SensorManager.SENSOR_DELAY_NORMAL, (int) TimeUnit.SECONDS.toMicros(5));

    SharedPreferences prefs = getSharedPreferences("histogram", Context.MODE_PRIVATE);
    String[] valueStr = prefs.getString("histogram", "").split(",");
    histogram = new int[24];
    for (int i = 0; i < 24; i++) {
      if (valueStr.length > i) {
        try {
          histogram[i] = Integer.parseInt(valueStr[i]);
        } catch (NumberFormatException e) {
          histogram[i] = 0;
        }
      }
    }
  }

  private void saveHistogram() {
    String[] valueStr = new String[24];
    for (int i = 0; i < 24; i++) {
      valueStr[i] = Integer.toString(histogram[i]);
    }

    SharedPreferences prefs = getSharedPreferences("histogram", Context.MODE_PRIVATE);
    prefs.edit()
        .putString("histogram", TextUtils.join(",", valueStr))
        .apply();
  }

  /**
   * This is our implementation of {@link android.hardware.SensorEventListener} which listens for
   * step counter sensor events.
   */
  private final SensorEventListener stepSensorEventListener = new SensorEventListener() {
    @Override
    public void onSensorChanged(SensorEvent event) {
      int count = (int) event.values[0];
      Date now = new Date();
      long timestamp = now.getTime();

      int stepsThisEvent = 0;
      if (lastStepCount > 0) {
        if (count > lastStepCount) {
          stepsThisEvent = count - lastStepCount;
        }
      }
      lastStepCount = count;
      if (stepsThisEvent == 0) {
        return;
      }

      calendar.setTime(now);
      int dayOfYear = calendar.get(Calendar.DAY_OF_YEAR);
      int hour = calendar.get(Calendar.HOUR_OF_DAY);

      boolean newDay = false;
      if (lastHistogramDate == null) {
        newDay = true;
      } else {
        calendar.setTime(lastHistogramDate);
        if (calendar.get(Calendar.DAY_OF_YEAR) != dayOfYear) {
          newDay = true;
        }
      }
      if (newDay) {
        for (int i = 0; i < histogram.length; i++) {
          histogram[i] = 0;
        }
        lastHistogramDate = now;
      }
      histogram[hour] += stepsThisEvent;

      if (timestamp - lastHistogramSaveTime >= HISTOGRAM_SAVE_DELAY_MS) {
        saveHistogram();
        lastHistogramSaveTime = timestamp;
      }

      stepCountSyncer.syncStepCount(stepsThisEvent, timestamp);
    }

    /** Ignored, there's no accuracy for the step counter. */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
  };

  public class StepSensorBinder extends Binder {
    public int[] getHistogram() {
      return histogram;
    }
  }
}
