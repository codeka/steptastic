package au.com.codeka.steptastic;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import au.com.codeka.steptastic.eventbus.EventHandler;

public class DailyStepsActivity extends FragmentActivity {
  private WatchConnection watchConnection = new WatchConnection();
  @Nullable private GoogleMap map;
  @Nullable private Marker marker;
  @Nullable private TileOverlay heatmapOverlay;
  private TextView syncStatus;
  private ViewPager stepCountViewPager;
  private StepCountPagerAdapter stepCountPagerAdapter;
  private Handler handler;
  private CameraPosition cameraPosition;

  private static final long DAYS = 1000 * 60 * 60 * 24;
  private static final long AUTO_SYNC_INTERVAL_MS = 3 * 60 * 60 * 1000; // 3 hours

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_daily_steps);
    watchConnection.setup(this);
    stepCountViewPager = (ViewPager) findViewById(R.id.current_steps_pager);
    stepCountPagerAdapter = new StepCountPagerAdapter(getSupportFragmentManager());
    stepCountViewPager.setAdapter(stepCountPagerAdapter);
    // start on the last page (today)
    stepCountViewPager.setCurrentItem(stepCountPagerAdapter.getCount() - 1);
    syncStatus = (TextView) findViewById(R.id.sync_status);
    setUpMapIfNeeded();
    handler = new Handler();

    stepCountViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
      @Override
      public void onPageSelected(int position) {
        handler.post(updateHeatmapRunnable);
      }
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    setUpMapIfNeeded();
    watchConnection.sendMessage(new WatchConnection.Message("/steptastic/StartCounting", null));
    handler.postDelayed(updateHeatmapRunnable, 1000);
    updateSyncStatus(null);
    refreshStepCount(StepDataStore.i.getStepsToday());
    maybeStartSyncing();
  }

  @Override
  protected void onStart() {
    super.onStart();
    watchConnection.start();
    StepDataStore.eventBus.register(eventHandler);
    StepSyncer.eventBus.register(eventHandler);

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
    StepSyncer.eventBus.unregister(eventHandler);
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
      case R.id.settings:
        startActivity(new Intent(this, SettingsActivity.class));
        return true;
      case R.id.graphs:
        startActivity(new Intent(this, GraphActivity.class));
      default:
        return super.onOptionsItemSelected(item);
    }
  }

  private void maybeStartSyncing() {
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

    // Don't sync if we're not configured to sync
    if (!preferences.getBoolean("au.com.codeka.steptastic.SyncToCloud", false)) {
      return;
    }

    // Also don't sync more often than AUTO_SYNC_INTERVAL_MS
    long timeSyncLastSync = StepSyncer.timeSinceLastSync(this);
    if (timeSyncLastSync < AUTO_SYNC_INTERVAL_MS) {
      return;
    }

    StepSyncer.sync(this, false);
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
        try {
          fos.close();
        } catch (IOException e) {
        }
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
      //cameraPosition = CameraPosition.CREATOR.createFromParcel(parcel);
    } catch (Exception e) {
    } finally {
      if (fis != null) {
        try {
          fis.close();
        } catch (IOException e) {
        }
      }
    }
  }

  private final Object eventHandler = new Object() {
    @EventHandler(thread = EventHandler.UI_THREAD)
    public void onStepCountUpdated(StepDataStore.StepsUpdatedEvent event) {
      refreshStepCount(event.stepsToday);
      showLocation(event.currentLocation);
    }

    @EventHandler(thread = EventHandler.UI_THREAD)
    public void onSyncStatus(StepSyncer.SyncStatusEvent event) {
      updateSyncStatus(event.status);
    }

    @EventHandler(thread = EventHandler.UI_THREAD)
    public void onSyncComplete(StepSyncer.SyncCompleteEvent event) {
      refreshStepCount(StepDataStore.i.getStepsToday());
      updateHeatmapRunnable.run();
      updateSyncStatus(null);
    }
  };

  private void refreshStepCount(long steps) {
    if (stepCountViewPager.getCurrentItem() == stepCountPagerAdapter.getCount() - 1) {
      // if we're on the last page, we can update it with the new count.
      StepCountFragment fragment = (StepCountFragment) stepCountPagerAdapter.instantiateItem(
          stepCountViewPager, stepCountViewPager.getCurrentItem());
      fragment.updateCount(steps);
    }
  }

  private void showLocation(Location loc) {
    LatLng latlng = new LatLng(loc.getLatitude(), loc.getLongitude());
    if (marker != null) {
      marker.setPosition(latlng);
    } else {
      marker = map.addMarker(new MarkerOptions().position(latlng).title("You"));
    }
  }

  /**
   * Update the sync status, we assume this is called on a UI thread.
   */
  private void updateSyncStatus(String status) {
    String statusMsg = status;
    if (statusMsg == null) {
      SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
          DailyStepsActivity.this);
      long timestamp = preferences.getLong("LastSyncTimestamp", 0);
      if (timestamp == 0) {
        statusMsg = "Last server sync: never";
      } else {
        DateFormat dateFormat = DateFormat.getDateTimeInstance();
        statusMsg = "Last server sync: " + dateFormat.format(new Date(timestamp));
      }
    }

    syncStatus.setText(statusMsg);
  }

  private Runnable updateHeatmapRunnable = new Runnable() {
    @Override
    public void run() {
      if (map == null) {
        return;
      }

      int daysOffsetFromToday = stepCountPagerAdapter.getCount() - stepCountViewPager.getCurrentItem() - 1;
      long startTime = TimestampUtils.midnight() - (daysOffsetFromToday * DAYS);
      long endTime = TimestampUtils.nextDay(startTime);

      LatLngBounds bounds = null;

      ArrayList<WeightedLatLng> heatmap = new ArrayList<WeightedLatLng>();
      for (StepDataStore.StepHeatmapEntry entry : StepDataStore.i.getHeatmap(startTime, endTime)) {
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
            .radius(50)
            .build();
        if (heatmapOverlay != null) {
          heatmapOverlay.remove();
        }
        heatmapOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(provider));

        try {
          map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
        } catch (IllegalStateException e) {
          // This can happen if the map hasn't finished laying out yet and is 0x0 big.
          // We'll just scheduled this runnable again in a second to give it time to
          // settle down. This is a bit of a kludge...
          handler.postDelayed(updateHeatmapRunnable, 1000);
        }
      } else {
        if (heatmapOverlay != null) {
          heatmapOverlay.remove();
          heatmapOverlay = null;
        }
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

  private static class StepCountPagerAdapter extends FragmentStatePagerAdapter {
    private final int numPages;

    public StepCountPagerAdapter(FragmentManager fm) {
      super(fm);

      long oldestStep = StepDataStore.i.getOldestStep();
      if (oldestStep == 0) {
        numPages = 1;
      } else {
        long differenceMs = TimestampUtils.midnight() - TimestampUtils.midnight(oldestStep);
        // TODO: account for leap seconds?
        long differenceDays = differenceMs / DAYS;
        numPages = (int) (differenceDays + 1);
      }
    }

    @Override
    public Fragment getItem(int position) {
      Bundle args = new Bundle();
      long dt = TimestampUtils.midnight();
      dt -= (numPages - position - 1) * DAYS;
      args.putLong("Timestamp", dt);
      StepCountFragment fragment = new StepCountFragment();
      fragment.setArguments(args);
      return fragment;
    }

    @Override
    public int getCount() {
      return numPages;
    }
  }

  public static class StepCountFragment extends Fragment {
    private TextView stepCountTextView;
    private TextView calorieCountTextView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
      ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.step_count_page, container,
          false);
      stepCountTextView = (TextView) rootView.findViewById(R.id.current_steps);
      calorieCountTextView = (TextView) rootView.findViewById(R.id.calories);
      TextView dayTextView = (TextView) rootView.findViewById(R.id.date);

      Bundle args = getArguments();
      long dt = args.getLong("Timestamp", 0);

      updateCount(StepDataStore.i.getStepsBetween(dt, TimestampUtils.nextDay(dt)));

      long daysDiff = (TimestampUtils.midnight() - dt) / DAYS;
      if (daysDiff == 0) {
        dayTextView.setText("TODAY");
      } else if (daysDiff == 1) {
        dayTextView.setText("YESTERDAY");
      } else if (daysDiff < 7) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dt);
        switch (c.get(Calendar.DAY_OF_WEEK)) {
          case Calendar.MONDAY:
            dayTextView.setText("MONDAY");
            break;
          case Calendar.TUESDAY:
            dayTextView.setText("TUESDAY");
            break;
          case Calendar.WEDNESDAY:
            dayTextView.setText("WEDNESDAY");
            break;
          case Calendar.THURSDAY:
            dayTextView.setText("THURSDAY");
            break;
          case Calendar.FRIDAY:
            dayTextView.setText("FRIDAY");
            break;
          case Calendar.SATURDAY:
            dayTextView.setText("SATURDAY");
            break;
          case Calendar.SUNDAY:
            dayTextView.setText("SUNDAY");
            break;
        }
      } else {
        DateFormat dateFormat = SimpleDateFormat.getDateInstance();
        dayTextView.setText(dateFormat.format(new Date(dt)));
      }

      return rootView;
    }

    public void updateCount(long stepCount) {
      if (stepCountTextView != null) {
        stepCountTextView.setText(Long.toString(stepCount));

        Activity activity = getActivity();
        if (activity == null) {
          return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
            activity);
        if (preferences.getBoolean("au.com.codeka.steptastic.CountCalories", true)) {
          float calories = NotificationGenerator.stepsToCalories(preferences, stepCount);
          calorieCountTextView.setVisibility(View.VISIBLE);
          calorieCountTextView.setText(String.format("%.1f cal", calories));
        } else {
          calorieCountTextView.setVisibility(View.GONE);
        }
      }
    }
  }
}
