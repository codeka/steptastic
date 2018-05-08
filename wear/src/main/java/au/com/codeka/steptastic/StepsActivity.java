package au.com.codeka.steptastic;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;

public class StepsActivity extends Activity {
  private TextView stepsTextView;
  private TextView timestampTextView;
  private Handler handler;
  private static long steps;
  private static long timestamp;

  public static void setSteps(int steps, long timestamp) {
    StepsActivity.steps = steps;
    StepsActivity.timestamp = timestamp;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_steps);

    handler = new Handler();
    handler.postDelayed(updateRunnable, 1000);

    stepsTextView = findViewById(R.id.text);
    timestampTextView = findViewById(R.id.timestamp);
  }

  @Override
  public void onResume() {
    super.onResume();
    AlarmReceiver.schedule(this);
  }

  private Runnable updateRunnable = new Runnable() {
    @Override
    public void run() {
      if (stepsTextView != null) {
        stepsTextView.setText(String.format("Steps: %d", steps));

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        timestampTextView.setText(format.format(new Date(timestamp)));
      }

      handler.postDelayed(updateRunnable, 1000);
    }
  };
}
