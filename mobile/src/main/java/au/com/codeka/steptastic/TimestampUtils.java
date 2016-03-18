package au.com.codeka.steptastic;

import java.util.Calendar;
import java.util.Date;

/**
 * Helper class for working with timestamps.
 */
public class TimestampUtils {
  /**
   * Gets the timestamp for midnight on the day represented by the given timestamp.
   */
  public static long midnight(long ts) {
    Calendar m = Calendar.getInstance();
    m.setTimeInMillis(ts);
    m.set(Calendar.HOUR_OF_DAY, 0);
    m.set(Calendar.MINUTE, 0);
    m.set(Calendar.SECOND, 0);
    m.set(Calendar.MILLISECOND, 0);
    return m.getTimeInMillis();
  }

  /**
   * Gets the most recent "midnight" that just passed.
   */
  public static long midnight() {
    return midnight(new Date().getTime());
  }

  /**
   * Gets the timestamp of midnight tomorrow night (i.e. the one coming up).
   */
  public static long tomorrowMidnight() {
    Calendar c = Calendar.getInstance();
    c.add(Calendar.DAY_OF_YEAR, 1);
    return midnight(c.getTimeInMillis());
  }

  public static long nextDay(long ts) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(ts);
    c.add(Calendar.DAY_OF_YEAR, 1);
    return c.getTimeInMillis();
  }
}
