package au.com.codeka.steptastic;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.support.annotation.Nullable;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.TextView;

import com.appspot.steptastic_wear.syncsteps.Syncsteps;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.CameraPositionCreator;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import au.com.codeka.steptastic.eventbus.EventHandler;

public class DailyStepsActivity extends Activity {
    private WatchConnection watchConnection = new WatchConnection();
    @Nullable private GoogleMap map;
    @Nullable private Marker marker;
    @Nullable private TileOverlay heatmapOverlay;
    private TextView stepCount;
    private Handler handler;
    private CameraPosition cameraPosition;

    private static final String PREF_ACCOUNT_NAME = "AccountName";
    private static final int REQUEST_ACCOUNT_PICKER = 132;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_daily_steps);
        watchConnection.setup(this);
        stepCount = (TextView) findViewById(R.id.current_steps);
        setUpMapIfNeeded();
        handler = new Handler();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setUpMapIfNeeded();
        watchConnection.sendMessage(new WatchConnection.Message("/steptastic/StartCounting", null));
        handler.postDelayed(updateHeatmapRunnable, 1000);
    }

    @Override
    protected void onStart() {
        super.onStart();
        watchConnection.start();
        StepDataStore.eventBus.register(eventHandler);
        refreshStepCount(StepDataStore.i.getStepsToday());

        loadCameraPosition();
        if (map != null && cameraPosition != null) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
            cameraPosition = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        StepDataStore.eventBus.unregister(eventHandler);
        watchConnection.stop();

        saveCameraPosition();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.sync_to_cloud:
                startSyncing();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void startSyncing() {
        SharedPreferences settings = getSharedPreferences("Steptastic", 0);
        GoogleAccountCredential credential = GoogleAccountCredential.usingAudience(this,
                "server:client_id:988087637760-6rhh5v6lhgjobfarparsomd4gectmk1v.apps.googleusercontent.com");
        String accountName = settings.getString(PREF_ACCOUNT_NAME, null);
        if (accountName == null) {
            startActivityForResult(credential.newChooseAccountIntent(),
                    REQUEST_ACCOUNT_PICKER);
            return;
        }
        credential.setSelectedAccountName(accountName);

        Syncsteps.Builder builder = new Syncsteps.Builder(
                AndroidHttp.newCompatibleTransport(), new GsonFactory(), credential);
        builder.setApplicationName("Steptastic");
        Syncsteps service = builder.build();
        new StepSyncer(this, service).sync(new Runnable() {
            @Override
            public void run() {
                refreshStepCount(StepDataStore.i.getStepsToday());
                updateHeatmapRunnable.run();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
        case REQUEST_ACCOUNT_PICKER:
            // if you've just picked an account (for syncing) then we'll save it and start
            // the sync again.
            if (data != null && data.getExtras() != null) {
                String accountName = data.getExtras().getString(AccountManager.KEY_ACCOUNT_NAME);
                if (accountName != null) {
                    SharedPreferences settings = getSharedPreferences("Steptastic", 0);
                    settings.edit()
                            .putString(PREF_ACCOUNT_NAME, accountName)
                            .commit();
                    startSyncing();
                }
            }
            break;
        }
    }

    private void saveCameraPosition() {
        if (map == null) {
            return;
        }

        FileOutputStream fos = null;
        try {
            fos = openFileOutput("CameraPosition.dat", Context.MODE_PRIVATE);
            Parcel parcel = Parcel.obtain();
            map.getCameraPosition().writeToParcel(parcel, 0);
            byte[] bytes = parcel.marshall();
            fos.write(bytes, 0, bytes.length);
        } catch (IOException e) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException e) { }
            }
        }
    }

    private void loadCameraPosition() {
        FileInputStream fis = null;
        try {
            fis = openFileInput("CameraPosition.dat");
            Parcel parcel = Parcel.obtain();
            byte[] buffer = new byte[1000];
            int numBytes = 0;
            while ((numBytes = fis.read(buffer, 0, buffer.length)) > 0) {
                parcel.unmarshall(buffer, 0, numBytes);
            }
            parcel.setDataPosition(0);
            cameraPosition = new CameraPositionCreator().createFromParcel(parcel);
        } catch (IOException e) {
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException e) { }
            }
        }
    }

    private final Object eventHandler = new Object() {
        @EventHandler(thread = EventHandler.UI_THREAD)
        public void onStepCountUpdated(StepDataStore.StepsUpdatedEvent event) {
            refreshStepCount(event.stepsToday);
            showLocation(event.currentLocation);
        }
    };

    private void refreshStepCount(long steps) {
        stepCount.setText(Long.toString(steps));
    }

    private void showLocation(Location loc) {
        LatLng latlng = new LatLng(loc.getLatitude(), loc.getLongitude());
        if (marker != null) {
            marker.setPosition(latlng);
        } else {
            marker = map.addMarker(new MarkerOptions().position(latlng).title("You"));
        }
    }

    private Runnable updateHeatmapRunnable = new Runnable() {
        @Override
        public void run() {
            if (map == null) {
                return;
            }

            LatLngBounds bounds = null;

            ArrayList<WeightedLatLng> heatmap = new ArrayList<WeightedLatLng>();
            for (StepDataStore.StepHeatmapEntry entry : StepDataStore.i.getHeatmap()) {
                if (entry.lat == 0.0 && entry.lng == 0.0) {
                    // Ignore these outliers.
                    continue;
                }
                LatLng latlng = new LatLng(entry.lat, entry.lng);
                if (bounds == null) {
                    bounds = new LatLngBounds(latlng, latlng);
                } else {
                    bounds = bounds.including(latlng);
                }
                heatmap.add(new WeightedLatLng(latlng, (double) entry.steps / 10.0));
            }

            if (heatmap.size() > 0) {
                HeatmapTileProvider provider = new HeatmapTileProvider.Builder()
                        .weightedData(heatmap)
                        .build();
                if (heatmapOverlay != null) {
                    heatmapOverlay.remove();
                }
                heatmapOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(provider));

                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
            }
        }
    };

    private void setUpMapIfNeeded() {
        // Do a null check to confirm that we have not already instantiated the map.
        if (map == null) {
            // Try to obtain the map from the SupportMapFragment.
            map = ((MapFragment) getFragmentManager().findFragmentById(R.id.map)).getMap();
            if (cameraPosition != null) {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
            }
        }
    }
}
