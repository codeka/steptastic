package au.com.codeka.steptastic;

import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.location.LocationClient;
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

    private LocationListener locationListener;
    private LocationClient locationClient;

    @Override
    public void onCreate() {
        super.onCreate();
        locationListener = new LocationListener();
        locationClient = new LocationClient(this, locationListener, locationListener);
        locationClient.connect();
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

                Location loc = locationListener.isConnected()
                        ? locationClient.getLastLocation() : null;
                StepDataStore.i.addSteps(timestamp, steps, loc);
            }
        }
    }

    private class LocationListener implements GooglePlayServicesClient.ConnectionCallbacks,
            GooglePlayServicesClient.OnConnectionFailedListener {
        private boolean isConnected;

        public boolean isConnected() {
            return isConnected;
        }

        @Override
        public void onConnected(Bundle bundle) {
            isConnected = true;
        }

        @Override
        public void onDisconnected() {
            isConnected = false;
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            isConnected = false;
            // TODO: handler errors?
        }
    }

}
