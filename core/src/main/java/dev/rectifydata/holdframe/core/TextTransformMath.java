package dev.rectifydata.holdframe.core;

/** Pure text geometry: four corner handles with the opposite corner fixed. */
public final class TextTransformMath {
    private TextTransformMath() {}
    public static final int NW=0, NE=1, SW=2, SE=3;
    public static final float MIN_SCALE=.05f, MAX_SCALE=50f;
    public static final class Result {
        public final float x,y,scaleX,scaleY;
        public Result(float x,float y,float scaleX,float scaleY){
            this.x=x;this.y=y;this.scaleX=scaleX;this.scaleY=scaleY;
        }
    }
    public static Result resize(float x,float y,float logicalWidth,float logicalHeight,
                                float currentScaleX,float currentScaleY,
                                int corner,float pointerX,float pointerY){
        if(corner<NW||corner>SE)throw new IllegalArgumentException("corner");
        if(!Float.isFinite(pointerX)||!Float.isFinite(pointerY)||!Float.isFinite(x)||!Float.isFinite(y))
            throw new IllegalArgumentException("non-finite geometry");
        float w=Math.max(1f,logicalWidth),h=Math.max(1f,logicalHeight);
        boolean left=corner==NW||corner==SW,top=corner==NW||corner==NE;
        float anchorX=left?x+w*currentScaleX:x;
        float anchorY=top?y+h*currentScaleY:y;
        float wantedWidth=left?anchorX-pointerX:pointerX-anchorX;
        float wantedHeight=top?anchorY-pointerY:pointerY-anchorY;
        float sx=clamp(wantedWidth/w),sy=clamp(wantedHeight/h);
        return new Result(left?anchorX-w*sx:anchorX,top?anchorY-h*sy:anchorY,sx,sy);
    }
    private static float clamp(float value){
        if(!Float.isFinite(value))return MIN_SCALE;
        return Math.max(MIN_SCALE,Math.min(MAX_SCALE,value));
    }
}
