package dev.rectifydata.holdframe;

/** Pure timeline geometry; no Android UI dependencies or bitmap allocations. */
public final class TimelineScrollPolicy {
    private TimelineScrollPolicy() {}
    public static int followTarget(int viewportWidth,int contentWidth,float playheadContentX) {
        if(viewportWidth<=0 || contentWidth<=viewportWidth || !Float.isFinite(playheadContentX))return 0;
        long target=Math.round((double)playheadContentX-viewportWidth*.60d);
        return (int)Math.max(0,Math.min(contentWidth-viewportWidth,target));
    }
    public static int edgeStep(float viewportX,int viewportWidth,int margin) {
        if(viewportWidth<=0||margin<=0||!Float.isFinite(viewportX))return 0;
        int m=Math.min(margin,Math.max(1,viewportWidth/3));
        if(viewportX<m)return -Math.max(3,Math.round((m-viewportX)*.42f));
        if(viewportX>viewportWidth-m)return Math.max(3,Math.round((viewportX-(viewportWidth-m))*.42f));
        return 0;
    }
}
