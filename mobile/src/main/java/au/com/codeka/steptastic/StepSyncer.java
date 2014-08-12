package au.com.codeka.steptastic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import com.appspot.steptastic_wear.syncsteps.Syncsteps;
import com.appspot.steptastic_wear.syncsteps.model.SyncStepCount;
import com.appspot.steptastic_wear.syncsteps.model.SyncStepCountCollection;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;

import java.io.IOException;
import java.text.DateFormat;
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
    private final DateFormat dateFormat = DateFormat.getDateInstance();

    private static boolean isSyncing = false;
    public static final String PREF_ACCOUNT_NAME = "AccountName";

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

    /** Gets the time, in milliseconds, since the last sync. */
    public static long timeSinceLastSync(Context context) {
        if (isSyncing) {
            return 0;
        }

        SharedPreferences settings = context.getSharedPreferences("Steptastic", 0);
        long timestamp = settings.getLong("LastSyncTimestamp", 0);
        return System.currentTimeMillis() - timestamp;
    }

    /** Performs a sync, without notifying the caller. Used by the background service. */
    public static void sync(Context context) {
        SharedPreferences settings = context.getSharedPreferences("Steptastic", 0);
        GoogleAccountCredential credential = GoogleAccountCredential.usingAudience(context,
                "server:client_id:988087637760-6rhh5v6lhgjobfarparsomd4gectmk1v.apps.googleusercontent.com");
        String accountName = settings.getString(PREF_ACCOUNT_NAME, null);
        if (accountName == null) {
            // If you haven't set up an account yet, then we can't sync anyway.
            return;
        }
        credential.setSelectedAccountName(accountName);

        Syncsteps.Builder builder = new Syncsteps.Builder(
                AndroidHttp.newCompatibleTransport(), new GsonFactory(), credential);
        builder.setApplicationName("Steptastic");
        new StepSyncer(context, builder.build()).sync(new SyncStatusCallback() {
            @Override
            public void setSyncStatus(String status) {
            }
        }, new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    /** Performs the actual sync, runs in a background thread. */
    public void sync(final SyncStatusCallback syncStatusCallback, final Runnable completeRunnable) {
        final long syncTime = System.currentTimeMillis();
        isSyncing = true;

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                try {
                    syncUp(syncStatusCallback);
                    syncDown(syncStatusCallback);
                } catch(IOException e) {
                    // ignore errors, but don't update the lastSyncTimestamp
                    Log.e(TAG, "Exception occurred uploading steps.", e);
                    return false;
                }

                return true;
            }

            @Override
            protected void onPostExecute(Boolean success) {
                isSyncing = false;

                if (success) {
                    SharedPreferences settings = context.getSharedPreferences("Steptastic", 0);
                    settings.edit().putLong("LastSyncTimestamp", syncTime).commit();

                    syncStatusCallback.setSyncStatus(null);
                    completeRunnable.run();
                }
            }
        }.execute();
    }

    /** Syncs from our local store to the server. */
    private void syncUp(SyncStatusCallback syncStatusCallback) throws IOException {
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
            syncStatusCallback.setSyncStatus("Uploading steps from "
                    + dateFormat.format(new Date(dt)) + "...");
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
    private void syncDown(SyncStatusCallback syncStatusCallback) throws IOException {
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
            syncStatusCallback.setSyncStatus("Downloading steps from "
                    + dateFormat.format(new Date(dt)) + "...");

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

    public interface SyncStatusCallback {
        void setSyncStatus(String status);
    }
}
