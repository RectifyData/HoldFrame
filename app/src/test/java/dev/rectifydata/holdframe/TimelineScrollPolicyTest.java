package dev.rectifydata.holdframe;

public final class TimelineScrollPolicyTest {
    private static void eq(int expected,int actual,String label) {
        if(expected!=actual)throw new AssertionError(label+": "+actual+" instead of "+expected);
    }
    public static void main(String[] args) {
        eq(0,TimelineScrollPolicy.followTarget(400,300,250),"fits");
        eq(0,TimelineScrollPolicy.followTarget(400,4000,110),"start");
        eq(160,TimelineScrollPolicy.followTarget(400,4000,400),"follow");
        eq(1160,TimelineScrollPolicy.followTarget(400,4000,1400),"advance");
        eq(3600,TimelineScrollPolicy.followTarget(400,4000,4000),"end");
        eq(0,TimelineScrollPolicy.followTarget(400,4000,0),"loop");
        eq(0,TimelineScrollPolicy.followTarget(400,4000,Float.NaN),"bad pixel");
        if(TimelineScrollPolicy.edgeStep(5,400,48)>=0)throw new AssertionError("left edge");
        if(TimelineScrollPolicy.edgeStep(395,400,48)<=0)throw new AssertionError("right edge");
        eq(0,TimelineScrollPolicy.edgeStep(180,400,48),"middle");
        if(TimelineScrollPolicy.edgeStep(395,400,48)<=TimelineScrollPolicy.edgeStep(365,400,48))
            throw new AssertionError("acceleration");
        System.out.println("TimelineScrollPolicyTest passed");
    }
}
