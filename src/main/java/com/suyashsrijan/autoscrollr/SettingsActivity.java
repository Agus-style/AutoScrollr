package com.suyashsrijan.autoscrollr;

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Pengaturan");
        }

        getFragmentManager().beginTransaction()
            .replace(android.R.id.content, new SettingsFragment())
            .commit();
    }

    public static class SettingsFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);
            bindPreferenceSummary("defaultVideoDuration");
            bindPreferenceSummary("extraDelay");
        }

        private void bindPreferenceSummary(String key) {
            Preference pref = findPreference(key);
            if (pref == null) return;
            pref.setOnPreferenceChangeListener(this);
            onPreferenceChange(pref,
                PreferenceManager.getDefaultSharedPreferences(pref.getContext())
                    .getString(key, ""));
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String value = newValue.toString();
            if (preference instanceof ListPreference) {
                ListPreference lp = (ListPreference) preference;
                int idx = lp.findIndexOfValue(value);
                preference.setSummary(idx >= 0 ? lp.getEntries()[idx] : null);
            } else {
                preference.setSummary(value);
            }
            return true;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
