package au.com.codeka.steptastic;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.widget.TextView;

import java.util.Locale;

public class StepsActivity extends Activity {
  private TextView stepsTextView;
  private Handler handler;

  private StepSensorService.StepSensorBinder stepSensorBinder;
  private StepHistogramView stepHistogramView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_steps);
    stepHistogramView = findViewById(R.id.histogram);

    handler = new Handler();
    handler.postDelayed(updateRunnable, 1000);

    stepsTextView = findViewById(R.id.text);
  }

  @Override
  public void onResume() {
    super.onResume();
    AlarmReceiver.schedule(this);

    bindService(new Intent(this, StepSensorService.class), new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder service) {
        stepSensorBinder = (StepSensorService.StepSensorBinder) service;
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
      }
    }, 0);
  }

  private Runnable updateRunnable = new Runnable() {
    @Override
    public void run() {
      int[] histogram = stepSensorBinder.getHistogram();
      int total = 0;
      for (int steps : histogram) {
        total += steps;
      }

      if (stepsTextView != null) {
        stepsTextView.setText(String.format(Locale.US, "%d", total));
      }
      if (stepSensorBinder != null && stepHistogramView != null) {
        stepHistogramView.setHistogram(stepSensorBinder.getHistogram());
      }

      handler.postDelayed(updateRunnable, 1000);
    }
  };
}
