package dev.rectifydata.holdframe;

/** Pure Java tests for automatic RAM classification and user overrides. */
public final class RamProfileTest {
    private static void expect(boolean pass,String message) {
        if (!pass) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        long gib=1024L*1024L*1024L;
        expect(RamProfile.enabled(4*gib,RamProfile.AUTO),"4 GB auto on");
        expect(RamProfile.enabled(6*gib,RamProfile.AUTO),"6 GB auto on");
        expect(!RamProfile.enabled(8*gib,RamProfile.AUTO),"8 GB auto off");
        expect(RamProfile.enabled(0,RamProfile.AUTO),"unknown RAM conservative");
        expect(!RamProfile.enabled(4*gib,RamProfile.OFF),"manual off persisted");
        expect(RamProfile.enabled(12*gib,RamProfile.ON),"manual on persisted");
        expect(RamProfile.normalize(-1)==RamProfile.AUTO,"unknown pref uses auto");
        expect(RamProfile.maxDecodedPixels(true)<RamProfile.maxDecodedPixels(false),"decode budget");
        expect(RamProfile.undoLimit(true)<RamProfile.undoLimit(false),"history budget");
        expect(RamProfile.rasterScale(true)<RamProfile.rasterScale(false),"raster budget");
        expect(RamProfile.maxImageAssetsBytes(true)<RamProfile.maxImageAssetsBytes(false),"asset budget");
        System.out.println("HoldFrame RAM profiles: 11 checks passed");
    }
}
