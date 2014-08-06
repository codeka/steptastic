package au.com.codeka.steptastic;

import android.app.Application;

/**
 * Our {@link Application} class.
 */
public class App extends Application {
    public static App i;

    @Override
    public void onCreate() {
        super.onCreate();
        i = this;
    }
}
