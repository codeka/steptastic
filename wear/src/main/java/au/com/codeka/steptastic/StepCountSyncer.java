package au.com.codeka.steptastic;

import android.content.Context;
import android.os.Bundle;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * This class syncs the step counter with the phone.
 */
public class StepCountSyncer {
    private GoogleApiClient googleApiClient;

    public StepCountSyncer(Context context) {
        googleApiClient = new GoogleApiClient.Builder(context)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                    }
                })
                .addApi(Wearable.API)
                .build();
        googleApiClient.connect();
    }

    public void syncStepCount(int steps, long timestamp) {
        if (steps == 0) {
            return;
        }
        PutDataMapRequest dataMap = PutDataMapRequest.create("/steptastic/steps");
        dataMap.getDataMap().putInt("steps", steps);
        dataMap.getDataMap().putLong("timestamp", timestamp);
        PutDataRequest request = dataMap.asPutDataRequest();
        Wearable.DataApi.putDataItem(googleApiClient, request);
    }
}
