package au.com.codeka.steptastic;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.AsyncTask;
import android.util.Log;

import com.appspot.steptastic_wear.syncsteps.Syncsteps;
import com.appspot.steptastic_wear.syncsteps.model.SyncStepCount;
import com.appspot.steptastic_wear.syncsteps.model.SyncStepCountCollection;
import com.google.android.gms.maps.model.LatLng;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * This class is responsible for syncing step counts to the server.
 */
public class StepSyncer {
    private static final String TAG = "StepSyncer";

    private final Context context;
    private final Syncsteps service;
    private final long lastSyncTimestamp;

    public StepSyncer(Context context, Syncsteps service) {
        this.context = context;
        this.service = service;
        SharedPreferences settings = context.getSharedPreferences("Steptastic", 0);
        long timestamp = settings.getLong("LastSyncTimestamp", 0);
        if (timestamp == 0) {
            // if we haven't synced before, we'll find the oldest entry in the data store and
            // start from there.
            timestamp = StepDataStore.i.getOldestStep();
            if (timestamp == 0) {
                // if it's still 0, that means we don't have any steps at all. Ignore for now,
                // the sync() method will check this.
            }
        }
        lastSyncTimestamp = timestamp;
    }

    /** Performs the actual sync, runs in a background thread. */
    public void sync(final Runnable completeRunnable) {
        final long syncTime = System.currentTimeMillis();

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                try {
                    syncUp();
                    syncDown();
                } catch(IOException e) {
                    // ignore errors, but don't update the lastSyncTimestamp
                    Log.e(TAG, "Exception occurred uploading steps.", e);
                    return false;
                }

                return true;
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (success) {
                    SharedPreferences settings = context.getSharedPreferences("Steptastic", 0);
                    settings.edit().putLong("LastSyncTimestamp", syncTime);

                    if (completeRunnable != null) {
                        completeRunnable.run();
                    }
                }
            }
        }.execute();
    }

    /** Syncs from our local store to the server. */
    private void syncUp() throws IOException {
        if (lastSyncTimestamp == 0) {
            // if we don't have any last sync timestamp, it means we don't have any steps at all,
            // so there's nothing to do
            Log.i(TAG, "Nothing to sync, lastSyncTimestamp == 0");
            return;
        }

        // Starting from midnight on the day of our last sync, go day-by-day until today.
        long startTimestamp = TimestampUtils.midnight(lastSyncTimestamp);
        long endTimestamp = TimestampUtils.tomorrowMidnight();

        for (long dt = startTimestamp; dt < endTimestamp; dt = TimestampUtils.nextDay(dt)) {
            Log.i(TAG, "Uploading steps from " + new Date(dt) + " to the server...");
            List<StepDataStore.StepHeatmapEntry> heatmap =
                    StepDataStore.i.getHeatmap(dt, TimestampUtils.nextDay(dt));

            SyncStepCountCollection coll = new SyncStepCountCollection();
            coll.setDate(dt);
            ArrayList<SyncStepCount> stepCounts = new ArrayList<SyncStepCount>();
            for (StepDataStore.StepHeatmapEntry heatmapEntry : heatmap) {
                SyncStepCount ssc = new SyncStepCount();
                ssc.setCount((long) heatmapEntry.steps);
                ssc.setLat(heatmapEntry.lat);
                ssc.setLng(heatmapEntry.lng);
                ssc.setTimestamp(heatmapEntry.timestamp);
                stepCounts.add(ssc);
            }
            coll.setSteps(stepCounts);
            service.sync().putSteps(coll).execute();
        }
    }

    /** Syncs from the server down to our local data store. */
    private void syncDown() throws IOException {
        long startTimestamp = lastSyncTimestamp;
        if (startTimestamp == 0) {
            // If we haven't sync'd at all yet, just arbitrarily choose the last week to sync.
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, -5);
            startTimestamp = c.getTimeInMillis();
        }
        startTimestamp = TimestampUtils.midnight(startTimestamp);
        long endTimestamp = TimestampUtils.tomorrowMidnight();

        for (long dt = startTimestamp; dt < endTimestamp; dt = TimestampUtils.nextDay(dt)) {
            Log.i(TAG, "Downloading steps from " + new Date(dt) + " from the server...");

            SyncStepCountCollection coll = service.sync().getSteps(dt).execute();
            if (coll == null || coll.getSteps() == null) {
                continue;
            }
            for (SyncStepCount stepCount : coll.getSteps()) {
                StepDataStore.i.setStepCount(stepCount.getTimestamp(),
                        (int) (long) stepCount.getCount(),
                        stepCount.getLat(), stepCount.getLng());
            }
        }
    }
}
