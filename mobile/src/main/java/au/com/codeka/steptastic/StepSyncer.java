package au.com.codeka.steptastic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;

import com.appspot.steptastic_wear.syncsteps.Syncsteps;
import com.appspot.steptastic_wear.syncsteps.model.SyncStepCount;
import com.appspot.steptastic_wear.syncsteps.model.SyncStepCountCollection;
import com.appspot.steptastic_wear.syncsteps.model.SyncFirstStep;
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
    private final boolean fullSync;
    private final DateFormat dateFormat = DateFormat.getDateInstance();
    private long lastSyncTimestamp;

    private static boolean isSyncing = false;
    public static final String PREF_ACCOUNT_NAME = "AccountName";

    public StepSyncer(Context context, Syncsteps service, boolean fullSync) {
        this.context = context;
        this.service = service;
        this.fullSync = fullSync;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        long timestamp = preferences.getLong("LastSyncTimestamp", 0);
        if (!fullSync && timestamp == 0) {
            // if we haven't synced before, we'll find the oldest entry in the data store and
            // start from there.
            timestamp = StepDataStore.i.getOldestStep();
            if (timestamp == 0) {
                // if it's still 0, that means we don't have any steps at all. Ignore for now,
                // the sync() method will check this.
            }
        }
        this.lastSyncTimestamp = timestamp;
    }

    /** Gets the time, in milliseconds, since the last sync. */
    public static long timeSinceLastSync(Context context) {
        if (isSyncing) {
            return 0;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        long timestamp = preferences.getLong("LastSyncTimestamp", 0);
        return System.currentTimeMillis() - timestamp;
    }

    /** Performs a sync, without notifying the caller. Used by the background service. */
    public static void sync(Context context, boolean fullSync) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        GoogleAccountCredential credential = GoogleAccountCredential.usingAudience(context,
                "server:client_id:988087637760-6rhh5v6lhgjobfarparsomd4gectmk1v.apps.googleusercontent.com");
        String accountName = preferences.getString(PREF_ACCOUNT_NAME, null);
        if (accountName == null) {
            // If you haven't set up an account yet, then we can't sync anyway.
            Log.w(TAG, "No account set, cannot sync!");
            return;
        }
        credential.setSelectedAccountName(accountName);

        Syncsteps.Builder builder = new Syncsteps.Builder(
                AndroidHttp.newCompatibleTransport(), new GsonFactory(), credential);
        builder.setApplicationName("Steptastic");
        new StepSyncer(context, builder.build(), fullSync).sync(new SyncStatusCallback() {
            @Override
            public void setSyncStatus(String status) {
                Log.i(TAG, status);
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
                    if (fullSync) {
                        getFirstSyncDate();
                    }
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
                    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
                    preferences.edit().putLong("LastSyncTimestamp", syncTime).commit();

                    syncStatusCallback.setSyncStatus(null);
                    completeRunnable.run();
                }
            }
        }.execute();
    }

    /**
     * When we're doing a full sync, ask the server when the first step was taken and sync from
     * then.
     */
    private void getFirstSyncDate() throws IOException {
        Log.i(TAG, "Doing full sync, getting first sync date.");
        SyncFirstStep firstStep = service.sync().firstStep().execute();
        if (firstStep != null) {
            long timestamp = firstStep.getDate();
            Log.i(TAG, "Got timestamp: " + timestamp + ", current lastSyncTimestamp: "
                    + lastSyncTimestamp);
            if (timestamp == 0) {
                return;
            }
            if (lastSyncTimestamp == 0 || timestamp < lastSyncTimestamp) {
                lastSyncTimestamp = timestamp;
            }
        } else {
            Log.w(TAG, "fullSync is true, but firstStep() returned null!");
        }
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
