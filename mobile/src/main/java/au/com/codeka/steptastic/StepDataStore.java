package au.com.codeka.steptastic;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.location.Location;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
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
        double lat = loc == null ? 0 : loc.getLatitude();
        double lng = loc == null ? 0 : loc.getLongitude();
        addSteps(timestamp, steps, lat, lng);

        long stepsToday = new Store().getStepsSinceMidnight();
        eventBus.publish(new StepsUpdatedEvent(stepsToday, loc));
    }

    public void addSteps(long timestamp, int steps, double lat, double lng) {
        // At this point, timestamp will be milliseconds since epoch. We want all of our step
        // counts to be in one minute increments, so we'll round this timestamp down to the nearest
        // minute.
        final long millisPerMinute = 1000 * 60;
        timestamp = timestamp - (timestamp % millisPerMinute);

        new Store().addSteps(timestamp, steps, lat, lng);
    }

    /**
     * Sets the step count for the given timestamp, dropping whatever count we used to have at this
     * time. This is used when restoring step count from the server.
     */
    public void setStepCount(long timestamp, int steps, double lat, double lng) {
        new Store().setSteps(timestamp, steps, lat, lng);
    }

    public long getStepsToday() {
        return new Store().getStepsSinceMidnight();
    }

    public long getStepsBetween(long startTime, long endTime) {
        return new Store().getStepsBetween(startTime, endTime);
    }

    /** Gets the "heatmap" for today, which is a collection of lat/lngs and step counts. */
    public List<StepHeatmapEntry> getHeatmap() {
        return new Store().getHeatmap();
    }

    /** Gets the "heatmap" between the two given times. */
    public List<StepHeatmapEntry> getHeatmap(long startTime, long endTime) {
        return new Store().getHeatmap(startTime, endTime);
    }

    /** Gets the timestamp of the oldest step we have recorded in our data store. */
    public long getOldestStep() {
        return new Store().getOldestStep();
    }

    /** Gets a histogram of the number of steps you do on each day-of-the-week. */
    public long[] getDayOfWeekHistogram() {
        return new Store().getDayOfWeekHistogram();
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
        public long timestamp;
        public double lat;
        public double lng;
        public int steps;

        public StepHeatmapEntry(long timestamp, double lat, double lng, int steps) {
            this.timestamp = timestamp;
            this.lat = lat;
            this.lng = lng;
            this.steps = steps;
        }
    }

    private static class Store extends SQLiteOpenHelper {
        private static Object lock = new Object();

        public Store() {
            super(App.i, "steps.db", null, 7);
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
                    +"  lng REAL,"
                    +"  day_of_week INTEGER);");
            db.execSQL("CREATE UNIQUE INDEX IX_steps_timestamp ON steps (timestamp)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion <= 1) {
                db.execSQL("DROP INDEX IX_steps_timestamp");
                db.execSQL("CREATE UNIQUE INDEX IX_steps_timestamp ON steps (timestamp)");
            }
            if (oldVersion <= 2) {
                db.execSQL("ALTER TABLE steps ADD COLUMN day_of_week INTEGER");
            }
            if (oldVersion <= 6) {
                // Go through and populate the day_of_week field for all existing rows...
                Cursor cursor = null;
                try {
                    ArrayList<StepHeatmapEntry> heatmap = new ArrayList<StepHeatmapEntry>();
                    cursor = db.query("steps", new String[] {"timestamp"},
                            null, null, null, null, null);
                    if (cursor.moveToFirst()) {
                        do {
                            long timestamp = cursor.getLong(0);
                            int dayOfWeek = getDayOfWeek(timestamp);
                            db.execSQL("UPDATE steps SET day_of_week=" + dayOfWeek
                                    + " WHERE timestamp = " + timestamp);
                        } while (cursor.moveToNext());
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        }

        private int getDayOfWeek(long timestamp) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(timestamp);
            return cal.get(Calendar.DAY_OF_WEEK) - 1;
        }

        /** Updates the step count. */
        public void addSteps(long timestamp, int steps, double lat, double lng) {
            synchronized (lock) {
                SQLiteDatabase db = getWritableDatabase();
                try {
                    int dayOfWeek = getDayOfWeek(timestamp);
                    db.execSQL("INSERT OR REPLACE INTO steps (timestamp, steps, lat, lng, day_of_week) VALUES ("
                            + timestamp + ", "
                            + "COALESCE((SELECT steps FROM steps WHERE timestamp = " + timestamp + "), 0) + " + steps + ", "
                            + lat + ", " + lng + ", " + dayOfWeek + ")");
                } finally {
                    db.close();
                }
            }
        }

        /** Sets the step count for the given timestamp (used only when restoring from backup). */
        public void setSteps(long timestamp, int steps, double lat, double lng) {
            synchronized (lock) {
                SQLiteDatabase db = getWritableDatabase();
                try {
                    int dayOfWeek = getDayOfWeek(timestamp);
                    db.execSQL("INSERT OR REPLACE INTO steps (timestamp, steps, lat, lng, day_of_week) VALUES ("
                            + timestamp + ", "
                            + steps + ", "
                            + lat + ", " + lng + ", " + dayOfWeek + ")");
                } finally {
                    db.close();
                }
            }
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

        public long getStepsBetween(long startTime, long endTime) {
            // this is not very efficient... we should specialize this to just do SELECT(COUNT()).
            List<StepHeatmapEntry> heatmap = getHeatmap(startTime, endTime);
            long steps = 0;
            for (StepHeatmapEntry entry : heatmap) {
                steps += entry.steps;
            }
            return steps;
        }

        /** Gets the timestamp of the oldest step we have recorded in our data store. */
        public long getOldestStep() {
            synchronized (lock) {
                SQLiteDatabase db = getReadableDatabase();
                Cursor cursor = null;
                try {
                    cursor = db.rawQuery("SELECT MIN(timestamp) FROM steps WHERE timestamp > 100", null);
                    if (!cursor.moveToFirst()) {
                        return 0;
                    }
                    return cursor.getLong(0);
                } finally {
                    if (cursor != null) cursor.close();
                    db.close();
                }
            }
        }

        public List<StepHeatmapEntry> getHeatmap() {
            long midnight = TimestampUtils.midnight();
            return getHeatmap(midnight, TimestampUtils.nextDay(midnight));
        }

        public List<StepHeatmapEntry> getHeatmap(long startTime, long endTime) {
            synchronized (lock) {
                SQLiteDatabase db = getReadableDatabase();
                Cursor cursor = null;
                try {
                    ArrayList<StepHeatmapEntry> heatmap = new ArrayList<StepHeatmapEntry>();
                    cursor = db.query("steps", new String[] {"steps", "lat", "lng", "timestamp"},
                            "timestamp > " + startTime + " AND timestamp <= " + endTime,
                            null, null, null, null);
                    if (!cursor.moveToFirst()) {
                        return heatmap;
                    }

                    do {
                        heatmap.add(new StepHeatmapEntry(cursor.getLong(3), cursor.getDouble(1),
                                cursor.getDouble(2), cursor.getInt(0)));
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

        public long[] getDayOfWeekHistogram() {
            long[] histogram = new long[7];
            synchronized (lock) {
                SQLiteDatabase db = getReadableDatabase();
                Cursor cursor = null;
                try {
                    cursor = db.rawQuery("SELECT day_of_week, SUM(steps) FROM steps GROUP BY day_of_week", null);
                    if (!cursor.moveToFirst()) {
                        return histogram;
                    }

                    do {
                        histogram[cursor.getInt(0)] = cursor.getLong(1);
                    } while (cursor.moveToNext());
                } finally {
                    if (cursor != null) cursor.close();
                    db.close();
                }
            }
            return histogram;
        }
    }
}
