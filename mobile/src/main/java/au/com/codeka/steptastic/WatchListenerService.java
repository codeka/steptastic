package au.com.codeka.steptastic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
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
    private NotificationGenerator notificationGenerator;
    private boolean isConnectedToWifi;
    private Handler handler;

    private static final long AUTO_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000; // 6 hours

    @Override
    public void onCreate() {
        super.onCreate();
        locationListener = new LocationListener();
        locationClient = new LocationClient(this, locationListener, locationListener);
        locationClient.connect();
        notificationGenerator = new NotificationGenerator();
        handler = new Handler();

        // Register to receive notifications about wifi connection state, as we only want to sync
        // when connected to wifi.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        registerReceiver(wifiConnectionChangeBroadcastReceiver, intentFilter);

        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        isConnectedToWifi = (wifiManager.getConnectionInfo() != null);
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
                notificationGenerator.setCurrentStepCount(StepDataStore.i.getStepsToday());
            }
        }
    }

    /** Check whether we should sync our data to the cloud, and if so, do it now. */
    private void maybeSyncToCloud() {
        if (!isConnectedToWifi) {
            return;
        }

        // Don't sync more often than AUTO_SYNC_INTERVAL_MS
        long timeSyncLastSync = StepSyncer.timeSinceLastSync(this);
        if (timeSyncLastSync < AUTO_SYNC_INTERVAL_MS) {
            long timeToNextSync = AUTO_SYNC_INTERVAL_MS - timeSyncLastSync;
            postMaybeSyncToCloudRunnable(timeToNextSync);
            return;
        }

        StepSyncer.sync(this);
        postMaybeSyncToCloudRunnable(AUTO_SYNC_INTERVAL_MS);
    }

    private final BroadcastReceiver wifiConnectionChangeBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)) {
                isConnectedToWifi = intent.getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED,
                        false);
                if (isConnectedToWifi) {
                    // if we stay connected to WiFi for 5 seconds, then we can try syncing.
                    postMaybeSyncToCloudRunnable(5000);
                }
            }
        }
    };

    private void postMaybeSyncToCloudRunnable(long delayMs) {
        handler.removeCallbacks(maybeSyncToCloudRunnable);
        handler.postDelayed(maybeSyncToCloudRunnable, delayMs);
    }

    private final Runnable maybeSyncToCloudRunnable = new Runnable() {
        @Override
        public void run() {
            maybeSyncToCloud();
        }
    };

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
