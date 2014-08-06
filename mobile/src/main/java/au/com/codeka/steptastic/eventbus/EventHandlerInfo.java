package au.com.codeka.steptastic.eventbus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import android.os.Handler;
import android.os.Looper;

/** Holds all the details about a single event handler. */
class EventHandlerInfo {
    private final Class<?> eventClass;
    private final Method method;
    private final WeakReference<?> subscriber;
    private final boolean callOnUiThread;
    private final Handler handler;

    public EventHandlerInfo(Class<?> eventClass, Method method, Object subscriber,
            boolean callOnUiThread) {
        this.eventClass = eventClass;
        this.method = method;
        this.subscriber = new WeakReference<Object>(subscriber);
        this.callOnUiThread = callOnUiThread;
        if (this.callOnUiThread) {
            this.handler = new Handler(Looper.getMainLooper());
        } else {
            this.handler = null;
        }
    }

    public boolean handles(Object event) {
        return eventClass.isInstance(event);
    }

    /** Gets the subscriber object, may be null. */
    public Object getSubscriber() {
        return subscriber.get();
    }

    /** Calls the subscriber's method with the given event object, on the UI thread if needed. */
    public void call(final Object event) {
        final Exception callLocation = new Exception("Location of EventHandlerInfo.call()");
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                final Object s = subscriber.get();
                if (s == null) {
                    return;
                }
                try {
                    method.invoke(s, event);
                } catch (Exception e) {
                }
            }
        };

        if (handler != null) {
            handler.post(runnable);
        } else {
            runnable.run();
        }
    }
}
