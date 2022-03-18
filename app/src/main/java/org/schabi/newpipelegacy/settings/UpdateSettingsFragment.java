package org.schabi.newpipelegacy.settings;

import android.os.Bundle;

import androidx.preference.Preference;

import org.schabi.newpipelegacy.R;

public class UpdateSettingsFragment extends BasePreferenceFragment {
    private final Preference.OnPreferenceChangeListener updatePreferenceChange
            = (preference, newValue) -> {
        defaultPreferences.edit()
                .putBoolean(getString(R.string.update_app_key), (boolean) newValue).apply();
        return true;
    };

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResource(R.xml.update_settings);

        final String updateToggleKey = getString(R.string.update_app_key);
        findPreference(updateToggleKey).setOnPreferenceChangeListener(updatePreferenceChange);
    }
}
