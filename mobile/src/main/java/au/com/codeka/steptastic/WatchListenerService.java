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
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import javax.annotation.Nullable;

/** This service listens for events from the watch. */
public class WatchListenerService extends WearableListenerService {
  private static final String TAG = "WatchListenerService";

  private GoogleApiClient apiClient;
  private NotificationGenerator notificationGenerator;
  private boolean isConnectedToWifi;
  private Handler handler;
  private Location lastLocation;
  private ArrayList<String> watchNodes = new ArrayList<>();

  private static final long AUTO_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000; // 6 hours

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(TAG, "onCreate()");

    notificationGenerator = new NotificationGenerator();
    handler = new Handler();

    // Register to receive notifications about wifi connection state, as we only want to sync
    // when connected to wifi.
    IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
    registerReceiver(wifiConnectionChangeBroadcastReceiver, intentFilter);

    WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
    isConnectedToWifi = (wifiManager.getConnectionInfo() != null);

    // Make sure we are started as well.
    startService(new Intent(this, WatchListenerService.class));
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    apiClient.disconnect();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (apiClient != null) {
      apiClient.disconnect();
    }

    apiClient = new GoogleApiClient.Builder(this)
        .addApi(LocationServices.API)
        .addApi(Wearable.API)
        .addConnectionCallbacks(connectionCallbacks)
        .build();
    apiClient.connect();

    return super.onStartCommand(intent, flags, startId);
  }

  @Override
  public void onMessageReceived(MessageEvent msgEvent) {
    Log.d(TAG, "onMessageReceived");
    if (msgEvent.getPath().equals("/steptastic/steps")) {

      ByteBuffer bb = ByteBuffer.wrap(msgEvent.getData());
      int steps = bb.getInt();
      long timestamp = bb.getLong();
      Log.d(TAG, "Got value: " + steps + " timestamp " + timestamp);

      Location loc = lastLocation;
      StepDataStore.i.addSteps(timestamp, steps, loc);
      notificationGenerator.setCurrentStepCount(StepDataStore.i.getStepsToday());
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

    StepSyncer.backgroundSync(this);
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

  private void connectToWatch() {
    Wearable.NodeApi.getConnectedNodes(apiClient).setResultCallback(
        new ResultCallback<NodeApi.GetConnectedNodesResult>() {
          @Override
          public void onResult(@NonNull NodeApi.GetConnectedNodesResult result) {
            Log.d(TAG, "Got connected nodes (" + result.getNodes().size() + " nodes)");
            for (Node node : result.getNodes()) {
              watchNodes.add(node.getId());
            }

            sendMessage("/steptastic/StartCounting", null);
          }
        });
  }

  @Override
  public void onPeerConnected(Node peer) {
    Log.d(TAG, "onPeerConnected: " + peer.getId() + " " + peer.getDisplayName());
  }

  @Override
  public void onPeerDisconnected(Node peer) {
    Log.d(TAG, "onPeerDisconnected: " + peer.getId() + " " + peer.getDisplayName());
  }

  private void sendMessage(String path, @Nullable byte[] payload) {
    if (watchNodes.isEmpty()) {
      Log.d(TAG, "Cannot send message, watch not connected: " + path);
    } else {
      Log.d(TAG, "Sending message: " + path);
      for (String watchNode : watchNodes) {
        Wearable.MessageApi.sendMessage(apiClient, watchNode, path, payload);
      }
    }
  }

  private final GoogleApiClient.ConnectionCallbacks connectionCallbacks
      = new GoogleApiClient.ConnectionCallbacks() {
    @Override
    public void onConnected(Bundle bundle) {
      Log.d(TAG, "onConnected()");
      connectToWatch();

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
          locationListener);
    }

    @Override
    public void onConnectionSuspended(int i) {
      Log.d(TAG, "onConnectionSuspended()");
      lastLocation = null;
      watchNodes.clear();
    }
  };

  private final LocationListener locationListener = new LocationListener() {
    @Override
    public void onLocationChanged(Location location) {
      lastLocation = location;
    }
  };
}
