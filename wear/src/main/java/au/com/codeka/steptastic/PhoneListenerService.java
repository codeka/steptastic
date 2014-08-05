package au.com.codeka.steptastic;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * This is the {@link WearableListenerService} that listens for messages from the phone app.
 */
public class PhoneListenerService extends WearableListenerService {
    @Override
    public void onMessageReceived(MessageEvent msgEvent) {
        if (msgEvent.getPath().equals("/steptastic/StartCounting")) {
            AlarmReceiver.schedule(this);
        }
    }
}
