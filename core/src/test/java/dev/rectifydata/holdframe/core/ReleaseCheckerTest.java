package dev.rectifydata.holdframe.core;
public final class ReleaseCheckerTest {
    private static void check(boolean yes){if(!yes)throw new AssertionError("ReleaseCheckerTest failed");}
    public static void main(String[] args){
        ReleaseChecker.Release r=ReleaseChecker.parse("[{\"tag_name\":\"v0.9.12\",\"prerelease\":true},{\"tag_name\":\"v0.8.6\"}]");
        check(r.tag.equals("v0.9.12"));
        check(r.newerThan("0.9.3"));
        check(!r.newerThan("0.9.12"));
        check(ReleaseChecker.compare("v0.9.9","v0.9.10")<0);
        check(ReleaseChecker.compare("v1.0.0","0.99.99")>0);
        check(ReleaseChecker.compare("v0.9.5","0.9.5")==0);
        boolean rejected=false;
        try {ReleaseChecker.parse("{\"tag_name\":\"../../malware\"}");}
        catch(IllegalArgumentException ex){rejected=true;}
        check(rejected);
        System.out.println("ReleaseCheckerTest passed");
    }
}
