package au.com.codeka.steptastic;

import android.accounts.AccountManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentActivity;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.CheckBox;

import com.appspot.steptastic_wear.syncsteps.Syncsteps;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;

import java.util.ArrayList;

public class SettingsActivity extends FragmentActivity {
    private static final String TAG = "SettingsActivity";

    private static final int REQUEST_ACCOUNT_PICKER = 132;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Display the fragment as the main content.
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ACCOUNT_PICKER:
                // if you've just picked an account (for syncing) then we'll save it and start
                // the sync again.
                if (data != null && data.getExtras() != null) {
                    saveSyncCredentials(data.getExtras().getString(AccountManager.KEY_ACCOUNT_NAME));
                }
                break;
        }
    }

    private void getSyncCredentials() {
        GoogleAccountCredential credential = GoogleAccountCredential.usingAudience(this,
                "server:client_id:988087637760-6rhh5v6lhgjobfarparsomd4gectmk1v.apps.googleusercontent.com");
        startActivityForResult(credential.newChooseAccountIntent(), REQUEST_ACCOUNT_PICKER);
    }

    private void saveSyncCredentials(String accountName) {
        if (accountName == null) {
            return;
        }

        SharedPreferences settings
                = PreferenceManager.getDefaultSharedPreferences(this);
        settings.edit()
                .putString(StepSyncer.PREF_ACCOUNT_NAME, accountName)
                .commit();

        // We'll want to initiate a full sync now, too.
        StepSyncer.sync(this, true);
    }

    public static class SettingsFragment extends PreferenceFragment {
        private Boolean isUsa;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.preferences);

            populateHeights((ListPreference) findPreference("au.com.codeka.steptastic.Height"));
            populateWeights((ListPreference) findPreference("au.com.codeka.steptastic.Weight"));

            CheckBoxPreference syncToCloudPreference = (CheckBoxPreference)
                    findPreference("au.com.codeka.steptastic.SyncToCloud");
            syncToCloudPreference.setOnPreferenceChangeListener(
                    new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if (((Boolean) newValue) == true) {
                        ((SettingsActivity) getActivity()).getSyncCredentials();
                    }
                    return true;
                }
            });
        }

        @Override
        public void onResume() {
            super.onResume();

            PreferenceManager.getDefaultSharedPreferences(getActivity())
                    .registerOnSharedPreferenceChangeListener(preferenceChangeListener);
            refreshSummaries();
        }

        @Override
        public void onPause() {
            super.onPause();

            PreferenceManager.getDefaultSharedPreferences(getActivity())
                    .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }

        private void refreshSummaries() {
            ListPreference heightPreference =
                    (ListPreference) findPreference("au.com.codeka.steptastic.Height");
            if (heightPreference.getValue() == null || heightPreference.getValue().length() == 0) {
                heightPreference.setSummary("Specify your height.");
            } else {
                float cm = Float.parseFloat(heightPreference.getValue());
                if (isUsa()) {
                    int in = (int) (cm / 2.54f);
                    int feet = in / 12;
                    int remainder = in - (feet * 12);
                    heightPreference.setSummary(String.format("%d'%d\"", feet, remainder));
                } else {
                    heightPreference.setSummary(String.format("%d cm", (int) cm));
                }
            }

            ListPreference weightPreference =
                    (ListPreference) findPreference("au.com.codeka.steptastic.Weight");
            if (weightPreference.getValue() == null || weightPreference.getValue().length() == 0) {
                weightPreference.setSummary("Specify your weight.");
            } else {
                float kg = Float.parseFloat(weightPreference.getValue());
                if (isUsa()) {
                    int lbs = (int) (kg * 2.205f);
                    weightPreference.setSummary(String.format("%d lb", lbs));
                } else {
                    weightPreference.setSummary(String.format("%d kg", (int) kg));
                }
            }

            CheckBoxPreference syncToCloudPreference = (CheckBoxPreference) findPreference(
                    "au.com.codeka.steptastic.SyncToCloud");
            if (syncToCloudPreference.isChecked()) {
                String accountName = PreferenceManager.getDefaultSharedPreferences(getActivity())
                        .getString(StepSyncer.PREF_ACCOUNT_NAME, null);
                if (accountName != null) {
                    syncToCloudPreference.setSummary(String.format("Syncing to %s", accountName));
                } else {
                    syncToCloudPreference.setSummary("Cannot sync, no account set.");
                }
            } else {
                syncToCloudPreference.setSummary("Not syncing.");
            }
        }

        /**
         * This stuff is annoyingly difficult. We'll assume for the sake of simplicity that US
         * is the only country in the world that still measures height in feet and inches (pretty
         * much anybody should be able to convert themselves, though...)
         */
        private void populateHeights(ListPreference heightPreference) {
            if (isUsa()) {
                populateHeightsImperial(heightPreference);
            } else {
                populateHeightsMetric(heightPreference);
            }
        }

        /**
         * Similarly to {#populateHeights}, we do something special for those pesky Americans, and
         * everybody else can probably understand how kilograms work.
         */
        private void populateWeights(ListPreference weightsPreference) {
            if (isUsa()) {
                populateWeightsImperial(weightsPreference);
            } else {
                populateWeightsMetric(weightsPreference);
            }
        }

        private void populateHeightsMetric(ListPreference heightPreference) {
            ArrayList<String> names = new ArrayList<String>();
            ArrayList<String> values = new ArrayList<String>();
            for (int cm = 120; cm < 240; cm += 5) {
                names.add(String.format("%d cm", cm));
                values.add(Integer.toString(cm));
            }
            heightPreference.setEntries(names.toArray(new String[names.size()]));
            heightPreference.setEntryValues(values.toArray(new String[values.size()]));
        }

        private void populateHeightsImperial(ListPreference heightPreference) {
            ArrayList<String> names = new ArrayList<String>();
            ArrayList<String> values = new ArrayList<String>();
            for (int in = 48; in < 90; in += 2) {
                int feet = in / 12;
                int remainder = in - (feet * 12);
                float cm = in * 2.54f;
                names.add(String.format("%d' %d\"", feet, remainder));
                values.add(Float.toString(cm));
            }
            heightPreference.setEntries(names.toArray(new String[names.size()]));
            heightPreference.setEntryValues(values.toArray(new String[values.size()]));
        }

        private void populateWeightsMetric(ListPreference weightPreference) {
            ArrayList<String> names = new ArrayList<String>();
            ArrayList<String> values = new ArrayList<String>();
            for (int kg = 50; kg < 150; kg += 5) {
                names.add(String.format("%d kg", kg));
                values.add(Integer.toString(kg));
            }
            weightPreference.setEntries(names.toArray(new String[names.size()]));
            weightPreference.setEntryValues(values.toArray(new String[values.size()]));
        }

        private void populateWeightsImperial(ListPreference weightPreference) {
            ArrayList<String> names = new ArrayList<String>();
            ArrayList<String> values = new ArrayList<String>();
            for (int lb = 100; lb < 350; lb += 10) {
                names.add(String.format("%d lb", lb));
                float kg = lb * 0.4536f;
                values.add(Float.toString(kg));
            }
            weightPreference.setEntries(names.toArray(new String[names.size()]));
            weightPreference.setEntryValues(values.toArray(new String[values.size()]));
        }

        private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
                new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                refreshSummaries();
            }
        };

        /** Returns {@code true} if we think you're in the USA, {@code false} otherwise. */
        private boolean isUsa() {
            if (isUsa != null) {
                return isUsa;
            }

            try {
                TelephonyManager telephonyManager = (TelephonyManager) getActivity().
                        getSystemService(Context.TELEPHONY_SERVICE);
                String countryCode = telephonyManager.getSimCountryIso();
                if (countryCode == null || countryCode.length() == 0) {
                    countryCode = telephonyManager.getNetworkCountryIso();
                    if (countryCode == null || countryCode.length() == 0) {
                        countryCode = getActivity().getResources().getConfiguration()
                                .locale.getISO3Country();
                        if (countryCode != null || countryCode.length() == 0) {
                            Log.i(TAG, "Got country from locale: " + countryCode);
                        }
                    } else {
                        Log.i(TAG, "Got country from network: " + countryCode);
                    }
                } else {
                    Log.i(TAG, "Got country from SIM: " + countryCode);
                }

                if (countryCode == null) {
                    Log.w(TAG, "Unable to determine country, assuming non-USA.");
                    isUsa = false;
                } else {
                    countryCode = countryCode.toLowerCase();
                    isUsa = (countryCode.equals("us") || countryCode.equals("usa"));
                }
            } catch (Exception e) {
                Log.w(TAG, "Unable to determine country, assuming non-USA.", e);
                isUsa = false;
            }
            return isUsa;
        }
    }
}
