package au.com.codeka.steptastic;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;

import java.util.concurrent.TimeUnit;

/**
 * This is a service which listens to the TYPE_STEP_COUNTER sensor to gather step count information.
 */
public class StepSensorService extends Service {
    private SensorManager sensorManager;
    private Sensor stepCounterSensor;
    private StepCountSyncer stepCountSyncer;

    private static int lastStepCount;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        stepCountSyncer = new StepCountSyncer(this);

        // Register for sensor events in batch mode, allowing up to 5 seconds delay before events
        // get reported. We don't care about the delay *too* much, but 5 seconds seems about right,
        // and some devices seem more inclined to follow this suggestion than others.
        sensorManager.registerListener(stepSensorEventListener, stepCounterSensor,
                SensorManager.SENSOR_DELAY_NORMAL, (int) TimeUnit.SECONDS.toMicros(5));
    }

    /**
     * This is our implementation of {@link android.hardware.SensorEventListener} which listens for
     * step counter sensor events.
     */
    private final SensorEventListener stepSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            int count = (int) event.values[0];
            long timestamp = event.timestamp;

            int stepsThisEvent = 0;
            if (lastStepCount > 0) {
                if (count > lastStepCount) {
                    stepsThisEvent = count - lastStepCount;
                }
            }
            lastStepCount = count;

            stepCountSyncer.syncStepCount(stepsThisEvent, timestamp / 1000000);
            StepsActivity.setSteps(count, timestamp);
        }

        /** Ignored, there's no accuracy for the step counter. */
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
}
