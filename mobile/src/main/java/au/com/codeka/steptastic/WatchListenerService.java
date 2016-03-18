package au.com.codeka.steptastic;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
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

  private MyLocationListener locationListener;
  private GoogleApiClient apiClient;
  private NotificationGenerator notificationGenerator;
  private boolean isConnectedToWifi;
  private Handler handler;

  private static final long AUTO_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000; // 6 hours

  @Override
  public void onCreate() {
    super.onCreate();
    locationListener = new MyLocationListener();

    apiClient = new GoogleApiClient.Builder(this)
        .addApi(LocationServices.API)
        .addConnectionCallbacks(locationListener)
        .addOnConnectionFailedListener(locationListener)
        .build();
    apiClient.connect();

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
  public void onDestroy() {
    super.onDestroy();
    apiClient.disconnect();
  }

  @Override
  public void onDataChanged(DataEventBuffer dataEvents) {
    final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
    for (DataEvent event : events) {
      DataMapItem dataItem = DataMapItem.fromDataItem(event.getDataItem());
      if (dataItem.getUri().getPath().equals("/steptastic/steps")) {
        int steps = dataItem.getDataMap().getInt("steps");
        long timestamp = dataItem.getDataMap().getLong("timestamp");

        Location loc = locationListener.isConnected()
            ? locationListener.getLastLocation() : null;
        StepDataStore.i.addSteps(timestamp, steps, loc);
        notificationGenerator.setCurrentStepCount(StepDataStore.i.getStepsToday());
      }
    }
  }

  /**
   * Check whether we should sync our data to the cloud, and if so, do it now.
   */
  private void maybeSyncToCloud() {
    // Don't sync if we're not connected to wi-fi
    if (!isConnectedToWifi) {
      return;
    }

    // Don't sync if we're not configured to sync
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
    if (!preferences.getBoolean("au.com.codeka.steptastic.SyncToCloud", false)) {
      return;
    }

    // Don't sync more often than AUTO_SYNC_INTERVAL_MS
    long timeSyncLastSync = StepSyncer.timeSinceLastSync(this);
    if (timeSyncLastSync < AUTO_SYNC_INTERVAL_MS) {
      long timeToNextSync = AUTO_SYNC_INTERVAL_MS - timeSyncLastSync;
      postMaybeSyncToCloudRunnable(timeToNextSync);
      return;
    }

    StepSyncer.sync(this, false);
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

  private class MyLocationListener implements GoogleApiClient.ConnectionCallbacks,
      GoogleApiClient.OnConnectionFailedListener, LocationListener {
    private boolean isConnected;
    private Location lastLocation;

    public boolean isConnected() {
      return isConnected;
    }

    public Location getLastLocation() {
      return lastLocation;
    }

    @Override
    public void onConnected(Bundle bundle) {
      Log.d(TAG, "Location.onConnected");
      boolean hasFineLocation = ActivityCompat.checkSelfPermission(WatchListenerService.this,
          Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
      boolean hasCoarseLocation = ActivityCompat.checkSelfPermission(WatchListenerService.this,
          Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
      if (!hasFineLocation && !hasCoarseLocation) {
        // no permission!
        return;
      }
      LocationServices.FusedLocationApi.requestLocationUpdates(apiClient,
          new LocationRequest()
              .setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY)
              .setFastestInterval(60 * 1000) // at most once a minute
              .setInterval(10 * 60 * 1000), // at least once every 10 minutes
          this);
      isConnected = true;
    }

    @Override
    public void onConnectionSuspended(int i) {
      Log.d(TAG, "Location.onConnectionSuspended");
      isConnected = false;
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
      Log.d(TAG, "Location.onConnectionFailed");
      isConnected = false;
      // TODO: handler errors?
    }

    @Override
    public void onLocationChanged(Location location) {
      lastLocation = location;
    }
  }

}
