package au.com.codeka.steptastic;

import android.util.Log;

import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.List;

/**
 * This service listens for events from the watch.
 */
public class WatchListenerService extends WearableListenerService {
    private static final String TAG = "WatchListenerService";

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.i(TAG, "onDataChanged()");
        final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
        for (DataEvent event : events) {
            Log.i(TAG, "GOT DATA ITEM: " + event.getDataItem().getUri().getPath());
            DataMapItem dataItem = DataMapItem.fromDataItem(event.getDataItem());
            if (dataItem.getUri().getPath().equals("/steptastic/steps")) {
                int steps = dataItem.getDataMap().getInt("steps");
                long timestamp = dataItem.getDataMap().getLong("timestamp");
                Log.i(TAG, "ADDING: " + steps + ", timestamp = " + timestamp);
                StepDataStore.i.addSteps(timestamp, steps);
            }
        }
    }
}
