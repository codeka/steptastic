package au.com.codeka.steptastic;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * This class syncs the step counter with the phone.
 */
public class StepCountSyncer {
  private static final String TAG = StepCountSyncer.class.getSimpleName();

  private final Context context;
  private GoogleApiClient apiClient;
  private ArrayList<Message> pendingMessages = new ArrayList<>();
  private ArrayList<String> watchNodes = new ArrayList<>();

  public StepCountSyncer(Context context) {
    this.context = context;
  }

  public void connect() {
    if (apiClient != null) {
      apiClient.disconnect();
    }
    apiClient = new GoogleApiClient.Builder(context)
        .addApi(Wearable.API)
        .addConnectionCallbacks(connectionCallbacks)
        .build();
    apiClient.connect();
  }

  public void syncStepCount(int steps, long timestamp) {
    if (steps == 0) {
      return;
    }

    ByteBuffer bb = ByteBuffer.allocate(12);
    bb.putInt(steps);
    bb.putLong(timestamp);
    byte[] payload = bb.array();

    sendMessage("/steptastic/steps", String.format("%d %d", steps, timestamp), payload);
  }

  private void sendMessage(String path, String debug, @Nullable byte[] payload) {
    if (watchNodes.isEmpty()) {
      Log.d(TAG, "Queuing message: " + path);
      pendingMessages.add(new Message(path, debug, payload));
    } else {
      for (String watchNode : watchNodes) {
        Log.d(TAG, "Sending message to " + watchNode + ": " + path);
        Wearable.MessageApi.sendMessage(apiClient, watchNode, path, payload);
      }
    }
  }

  public static class Message {
    private final String path;
    private final String debug;
    private final byte[] payload;

    public Message(String path, String debug, byte[] payload) {
      this.path = path;
      this.debug = debug;
      this.payload = payload;
    }

    public String getPath() {
      return path;
    }

    public byte[] getPayload() {
      return payload;
    }

    @Override
    public String toString() {
      return debug;
    }
  }

  private final GoogleApiClient.ConnectionCallbacks connectionCallbacks
      = new GoogleApiClient.ConnectionCallbacks() {
    @Override
    public void onConnected(Bundle bundle) {
      Log.d(TAG, "onConnected()");
      Wearable.NodeApi.getConnectedNodes(apiClient).setResultCallback(
          new ResultCallback<NodeApi.GetConnectedNodesResult>() {
            @Override
            public void onResult(@NonNull NodeApi.GetConnectedNodesResult result) {
              Log.d(TAG, "Got connected nodes (" + result.getNodes().size() + " nodes)");
              watchNodes.clear();
              for (Node node : result.getNodes()) {
                watchNodes.add(node.getId());
              }

              for (Message pendingMessage : pendingMessages) {
                sendMessage(
                    pendingMessage.getPath(),
                    pendingMessage.toString(),
                    pendingMessage.getPayload());
              }
              pendingMessages.clear();
            }
          });
    }

    @Override
    public void onConnectionSuspended(int i) {
      Log.d(TAG, "onConnectionSuspended()");
      watchNodes.clear();
    }
  };
}
