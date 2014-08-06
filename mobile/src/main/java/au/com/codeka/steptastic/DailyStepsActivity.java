package au.com.codeka.steptastic;

import android.app.Activity;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.widget.TextView;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;

import java.util.ArrayList;

import au.com.codeka.steptastic.eventbus.EventHandler;

public class DailyStepsActivity extends Activity {
    private WatchConnection watchConnection = new WatchConnection();
    @Nullable private GoogleMap map;
    @Nullable private Marker marker;
    @Nullable private TileOverlay heatmapOverlay;
    private TextView stepCount;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_daily_steps);
        watchConnection.setup(this);
        stepCount = (TextView) findViewById(R.id.current_steps);
        setUpMapIfNeeded();
        handler = new Handler();
        handler.postDelayed(updateHeatmapRunnable, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setUpMapIfNeeded();
        watchConnection.sendMessage(new WatchConnection.Message("/steptastic/StartCounting", null));
    }

    @Override
    protected void onStart() {
        super.onStart();
        watchConnection.start();
        StepDataStore.eventBus.register(eventHandler);
        refreshStepCount(StepDataStore.i.getStepsToday());
    }

    @Override
    protected void onStop() {
        super.onStop();
        StepDataStore.eventBus.unregister(eventHandler);
        watchConnection.stop();
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
            handler.postDelayed(updateHeatmapRunnable, 10000);

            if (map == null) {
                return;
            }

            ArrayList<WeightedLatLng> heatmap = new ArrayList<WeightedLatLng>();
            for (StepDataStore.StepHeatmapEntry entry : StepDataStore.i.getHeatmap()) {
                heatmap.add(new WeightedLatLng(new LatLng(entry.lat, entry.lng),
                        (double) entry.steps / 10.0));
            }

            HeatmapTileProvider provider = new HeatmapTileProvider.Builder()
                    .weightedData(heatmap)
                    .build();
            if (heatmapOverlay != null) {
                heatmapOverlay.remove();
            }
            heatmapOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(provider));
        }
    };

    private void setUpMapIfNeeded() {
        // Do a null check to confirm that we have not already instantiated the map.
        if (map == null) {
            // Try to obtain the map from the SupportMapFragment.
            map = ((MapFragment) getFragmentManager().findFragmentById(R.id.map)).getMap();
        }
    }
}
