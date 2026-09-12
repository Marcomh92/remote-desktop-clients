package com.iiordanov.bVNC;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.undatech.remoteClientUi.R;

public class GlobalPreferencesFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        getPreferenceManager().setSharedPreferencesName(Constants.generalSettingsTag);
        setPreferencesFromResource(R.xml.global_preferences, s);
        if (Utils.isVnc(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_vnc);
        } else if (Utils.isRdp(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_rdp);
            // The legacy single-slider acceleration is superseded on RDP by the
            // pointer acceleration curve controls added by the overlay above.
            Preference legacyAcceleration = findPreference(Constants.mouseAccelerationStrength);
            if (legacyAcceleration != null) {
                legacyAcceleration.setVisible(false);
            }
        } else if (Utils.isSpice(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_spice);
        }
    }

    /**
     * Handles the preference rows without a dedicated listener: opens the
     * default connection settings editor, or resets the RDP pointer
     * acceleration preferences to their defaults.
     */
    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if ("openDefaultConnectionSettings".equals(preference.getKey())) {
            Utils.openDefaultConnectionSettings(requireContext());
            return true;
        }
        if (Constants.rdpPointerAccelReset.equals(preference.getKey())) {
            resetRdpPointerAccel();
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    /**
     * Restores the RDP pointer acceleration settings to their defaults by
     * writing through the preference widgets, so both the persisted values and
     * the visible switch/sliders update, then confirms with a brief toast.
     */
    private void resetRdpPointerAccel() {
        SeekBarPreference lowGain = findPreference(Constants.rdpPointerAccelLowGainPct);
        if (lowGain != null) {
            lowGain.setValue(Constants.DEFAULT_RDP_POINTER_ACCEL_LOW_GAIN_PCT);
        }
        SeekBarPreference highGain = findPreference(Constants.rdpPointerAccelHighGainPct);
        if (highGain != null) {
            highGain.setValue(Constants.DEFAULT_RDP_POINTER_ACCEL_HIGH_GAIN_PCT);
        }
        SwitchPreferenceCompat enabled = findPreference(Constants.rdpPointerAccelEnabled);
        if (enabled != null) {
            enabled.setChecked(Constants.DEFAULT_RDP_POINTER_ACCEL_ENABLED);
        }
        Toast.makeText(requireContext(), R.string.rdp_pointer_accel_reset_toast, Toast.LENGTH_SHORT).show();
    }
}