package dev.rectifydata.holdframe;

/** Dependency-free policy: Android framework lookups happen in MainActivity only. */
public final class RamProfile {
    private RamProfile() {}

    public static final int AUTO = 0;
    public static final int ON = 1;
    public static final int OFF = 2;
    /** At or below six physical GiB, use the conservative preset by default. */
    public static final long LOW_RAM_THRESHOLD_BYTES = 6L * 1024 * 1024 * 1024;

    public static int normalize(int preference) {
        return preference == ON || preference == OFF ? preference : AUTO;
    }

    public static boolean enabled(long physicalRamBytes, int preference) {
        int mode = normalize(preference);
        if (mode == ON) return true;
        if (mode == OFF) return false;
        // If RAM detection fails, prefer safe low-memory behavior.
        return physicalRamBytes <= 0 || physicalRamBytes <= LOW_RAM_THRESHOLD_BYTES;
    }

    public static int undoLimit(boolean lowRam) { return lowRam ? 12 : 60; }
    public static int maxDecodedDimension(boolean lowRam) { return lowRam ? 768 : 1600; }
    public static int maxDecodedPixels(boolean lowRam) { return lowRam ? 600_000 : 3_200_000; }
    public static int imageImportLimit(boolean lowRam) { return lowRam ? 8_000_000 : 16_000_000; }
    public static int maxImageAssetsBytes(boolean lowRam) { return lowRam ? 64_000_000 : 128_000_000; }
    public static float rasterScale(boolean lowRam) { return lowRam ? 0.75f : 1.0f; }
    public static int playbackRefreshMs(boolean lowRam) { return lowRam ? 42 : 16; }
    public static int autosaveDelayMs(boolean lowRam) { return lowRam ? 1800 : 900; }
}
