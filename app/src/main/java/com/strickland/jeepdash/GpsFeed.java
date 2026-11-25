package com.strickland.jeepdash;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import com.strickland.jeepdash.core.LiveValues;

/**
 * The tablet's GPS (platform LocationManager, no Google Play Services) for the Tune log: puts gps_mph and
 * gps_accuracy_m into the live values about once a second while a log runs. Main thread only.
 */
final class GpsFeed implements LocationListener {
    private static final double MPH_PER_MPS = 2.23694;

    private LocationManager locations;
    private LiveValues values;

    /** True when location is allowed and the tablet has a GPS. */
    static boolean usable(Context c) {
        try {
            if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
            LocationManager lm = c.getSystemService(LocationManager.class);
            return lm != null && lm.getAllProviders().contains(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean start(Context c, LiveValues v) {
        if (!usable(c)) return false;
        try {
            values = v;
            locations = c.getSystemService(LocationManager.class);
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            return true;
        } catch (RuntimeException e) {
            locations = null;
            return false;
        }
    }

    void stop() {
        LocationManager lm = locations;
        locations = null;
        if (lm == null) return;
        try {
            lm.removeUpdates(this);
        } catch (RuntimeException ignored) {
            // nothing was registered
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        LiveValues v = values;
        if (v == null || !l.hasSpeed()) return;
        long now = LinkService.now();
        if (l.hasAccuracy()) v.put("gps_accuracy_m", l.getAccuracy(), now);
        v.put("gps_mph", l.getSpeed() * MPH_PER_MPS, now);      // last, so a pair is complete when the speed is seen
    }

    // Before Android 11 these three have no default and are called, so they must exist.
    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) { }
}
