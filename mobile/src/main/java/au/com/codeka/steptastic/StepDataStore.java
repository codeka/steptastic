package au.com.codeka.steptastic;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import au.com.codeka.steptastic.eventbus.EventBus;

/**
 * A data store for storing step data.
 */
public class StepDataStore {
    public static StepDataStore i = new StepDataStore();

    private StepDataStore() {
    }

    public static final EventBus eventBus = new EventBus();

    /** Adds the given steps, at the given timestamp, to the data store. */
    public void addSteps(long timestamp, int steps, Location loc) {
        // At this point, timestamp will be milliseconds since epoch. We want all of our step
        // counts to be in one minute increments, so we'll round this timestamp down to the nearest
        // minute.
        final long millisPerMinute = 1000 * 60;
        timestamp = timestamp - (timestamp % millisPerMinute);

        double lat = loc == null ? 0 : loc.getLatitude();
        double lng = loc == null ? 0 : loc.getLongitude();
        long stepsToday = new Store().addSteps(timestamp, steps, lat, lng);
        eventBus.publish(new StepsUpdatedEvent(stepsToday, loc));
    }

    public long getStepsToday() {
        return new Store().getStepsSinceMidnight();
    }

    /** Gets the "heatmap" for today, which is a collection of lat/lngs and step counts. */
    public List<StepHeatmapEntry> getHeatmap() {
        return new Store().getHeatmap();
    }

    /** Event that's posted to the {@link EventBus} whenever the step count updates. */
    public static class StepsUpdatedEvent {
        /** The total number of steps we've recorded today. */
        public long stepsToday;
        public Location currentLocation;

        public StepsUpdatedEvent(long stepsToday, Location currentLocation) {
            this.stepsToday = stepsToday;
            this.currentLocation = currentLocation;
        }
    }

    /**
     * Represents an entry in the "steps heatmap", which is just a collection of lat/lngs and
     * step counts.
     */
    public static class StepHeatmapEntry {
        public double lat;
        public double lng;
        public int steps;

        public StepHeatmapEntry(double lat, double lng, int steps) {
            this.lat = lat;
            this.lng = lng;
            this.steps = steps;
        }
    }

    private static class Store extends SQLiteOpenHelper {
        private static Object lock = new Object();

        public Store() {
            super(App.i, "steps.db", null, 1);
        }

        /**
         * This is called the first time we open the database, in order to create the required
         * tables, etc.
         */
        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE steps ("
                    +"  timestamp INTEGER PRIMARY KEY,"
                    +"  steps INTEGER,"
                    +"  lat REAL,"
                    +"  lng REAL);");
            db.execSQL("CREATE INDEX IX_steps_timestamp ON steps (timestamp)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }

        /** Updates the step count and returns the total number of steps we've done today. */
        public long addSteps(long timestamp, int steps, double lat, double lng) {
            synchronized (lock) {
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL("INSERT OR REPLACE INTO steps (timestamp, steps, lat, lng) VALUES ("
                    + timestamp + ", "
                    + "COALESCE((SELECT steps FROM steps WHERE timestamp = " + timestamp + "), 0) + " + steps + ", "
                    + lat + ", " + lng + ")");
            }
            return getStepsSinceMidnight();
        }

        public long getStepsSinceMidnight() {
            // this is not very efficient... we should specialize this to just do SELECT(COUNT()).
            List<StepHeatmapEntry> heatmap = getHeatmap();
            long steps = 0;
            for (StepHeatmapEntry entry : heatmap) {
                steps += entry.steps;
            }
            return steps;
        }

        public List<StepHeatmapEntry> getHeatmap() {
            Calendar m = Calendar.getInstance(); //midnight
            m.set(Calendar.HOUR_OF_DAY, 0);
            m.set(Calendar.MINUTE, 0);
            m.set(Calendar.SECOND, 0);
            m.set(Calendar.MILLISECOND, 0);
            long midnight = m.getTimeInMillis();

            synchronized (lock) {
                SQLiteDatabase db = getReadableDatabase();
                Cursor cursor = null;
                try {
                    ArrayList<StepHeatmapEntry> heatmap = new ArrayList<StepHeatmapEntry>();
                    cursor = db.query("steps", new String[] {"steps", "lat", "lng"},
                            "timestamp > " + midnight, null, null, null, null);
                    if (!cursor.moveToFirst()) {
                        return heatmap;
                    }

                    do {
                        heatmap.add(new StepHeatmapEntry(cursor.getDouble(1), cursor.getDouble(2),
                                cursor.getInt(0)));
                    } while (cursor.moveToNext());
                    return heatmap;
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                    db.close();
                }
            }
        }
    }
}
