package com.pairip.licensecheck;

/** JVM-only 3.97.1 PairIP signature fixture; no Play licensing is invoked. */
public final class LicenseClient {
    public static void checkLicense(android.content.Context context) {
        // Deliberately empty: checks verify reflection structure, not remote licensing.
    }
}
