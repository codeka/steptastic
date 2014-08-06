package au.com.codeka.steptastic;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import java.util.Calendar;

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
        // counts to be in 5 minute increments, so we'll round this timestamp down to the nearest
        // 5 minutes.
        final long millisPerFiveMinutes = 1000 * 60 * 5;
        timestamp = timestamp - (timestamp % millisPerFiveMinutes);

        double lat = loc == null ? 0 : loc.getLatitude();
        double lng = loc == null ? 0 : loc.getLongitude();
        long stepsToday = new Store().addSteps(timestamp, steps, lat, lng);
        eventBus.publish(new StepsUpdatedEvent(stepsToday, loc));
    }

    public long getStepsToday() {
        return new Store().getStepsSinceMidnight();
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
                    // TODO: doing a SELECT(COUNT()) would be faster, but we'll stick with this for
                    // TODO: now.
                    cursor = db.query("steps", new String[] {"steps"}, "timestamp > " + midnight,
                            null, null, null, null);
                    if (!cursor.moveToFirst()) {
                        return 0;
                    }

                    long totalSteps = 0;
                    do {
                        totalSteps += cursor.getLong(0);
                    } while (cursor.moveToNext());
                    return totalSteps;
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
