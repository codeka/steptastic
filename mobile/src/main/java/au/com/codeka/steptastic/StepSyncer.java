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

import au.com.codeka.steptastic.eventbus.EventBus;

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

    public static final String PREF_ACCOUNT_NAME = "AccountName";

    private static boolean isSyncing = false;
    public static EventBus eventBus = new EventBus();

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
        new StepSyncer(context, builder.build(), fullSync).sync();
    }

    /** Performs the actual sync, runs in a background thread. */
    public void sync() {
        final long syncTime = System.currentTimeMillis();
        if (isSyncing) {
            // don't sync if we're already syncing.
            return;
        }
        isSyncing = true;

        new AsyncTask<Void, Long, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                try {
                    long lastSync = lastSyncTimestamp;
                    if (fullSync) {
                        lastSync = getFirstSyncDate();
                    }

                    long startTimestamp = lastSync;
                    if (startTimestamp == 0) {
                        // If we haven't sync'd at all yet, just arbitrarily choose the last week to sync.
                        Calendar c = Calendar.getInstance();
                        c.add(Calendar.DAY_OF_YEAR, -5);
                        startTimestamp = c.getTimeInMillis();
                    }
                    startTimestamp = TimestampUtils.midnight(startTimestamp);
                    long endTimestamp = TimestampUtils.tomorrowMidnight();
                    for (long dt = startTimestamp; dt < endTimestamp;
                         dt = TimestampUtils.nextDay(dt)) {
                        syncUp(dt);
                        syncDown(dt);

                        publishProgress(dt);
                    }
                } catch(IOException e) {
                    // ignore errors, but don't update the lastSyncTimestamp
                    Log.e(TAG, "Exception occurred uploading steps.", e);
                    return false;
                }

                return true;
            }

            @Override
            protected void onProgressUpdate(Long... progress) {
                // Each day we sync, save that so that we don't have to do it again if we're killed.
                long dt = progress[0];
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
                preferences.edit().putLong("LastSyncTimestamp", dt).commit();
            }

            @Override
            protected void onPostExecute(Boolean success) {
                isSyncing = false;

                if (success) {
                    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
                    preferences.edit().putLong("LastSyncTimestamp", syncTime).commit();

                    eventBus.publish(new SyncCompleteEvent());
                }
            }
        }.execute();
    }

    /**
     * When we're doing a full sync, ask the server when the first step was taken and sync from
     * then.
     */
    private long getFirstSyncDate() throws IOException {
        Log.i(TAG, "Doing full sync, getting first sync date.");
        long syncTime = lastSyncTimestamp;
        SyncFirstStep firstStep = service.sync().firstStep().execute();
        if (firstStep != null) {
            long timestamp = firstStep.getDate();
            Log.i(TAG, "Got timestamp: " + timestamp + ", current lastSyncTimestamp: "
                    + lastSyncTimestamp);
            if (timestamp == 0) {
                syncTime = lastSyncTimestamp;
            } else if (lastSyncTimestamp == 0 || timestamp < lastSyncTimestamp) {
                syncTime = timestamp;
            }
        } else {
            Log.w(TAG, "fullSync is true, but firstStep() returned null!");
        }
        return syncTime;
    }

    /** Syncs from our local store to the server. */
    private void syncUp(long dt) throws IOException {
        eventBus.publish(new SyncStatusEvent("Uploading steps from "
                + dateFormat.format(new Date(dt)) + "..."));
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

    /** Syncs from the server down to our local data store. */
    private void syncDown(long dt) throws IOException {
        eventBus.publish(new SyncStatusEvent("Downloading steps from "
                + dateFormat.format(new Date(dt)) + "..."));

        SyncStepCountCollection coll = service.sync().getSteps(dt).execute();
        if (coll == null || coll.getSteps() == null) {
            return;
        }
        for (SyncStepCount stepCount : coll.getSteps()) {
            StepDataStore.i.setStepCount(stepCount.getTimestamp(),
                    (int) (long) stepCount.getCount(),
                    stepCount.getLat(), stepCount.getLng());
        }
    }

    /* Event that's posted to our event bus as we sync. */
    public static class SyncStatusEvent {
        public String status;

        public SyncStatusEvent(String status) {
            this.status = status;
        }
    }

    /** This event is posted to the event bus when a sync completes. */
    public static class SyncCompleteEvent {
    }
}
