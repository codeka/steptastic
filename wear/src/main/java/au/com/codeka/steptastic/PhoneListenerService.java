package au.com.codeka.steptastic;

import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * This is the {@link WearableListenerService} that listens for messages from the phone app.
 */
public class PhoneListenerService extends WearableListenerService {
  private static final String TAG = PhoneListenerService.class.getSimpleName();

  @Override
  public void onMessageReceived(MessageEvent msgEvent) {
    Log.d(TAG, "Got message from phone: " + msgEvent.getPath());
    if (msgEvent.getPath().equals("/steptastic/StartCounting")) {
      AlarmReceiver.schedule(this);
    }
  }
}
