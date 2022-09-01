package org.schabi.newpipelegacy.util;

import org.schabi.newpipelegacy.App;

public final class FireTvUtils {
    private FireTvUtils() {
        // not call
    }

    public static boolean isFireTv() {
        final String amazonFeatureFireTv = "amazon.hardware.fire_tv";
        return App.getApp().getPackageManager().hasSystemFeature(amazonFeatureFireTv);
    }
}
