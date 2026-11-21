package dev.rectifydata.holdframe.core;
public final class TextTransformMath {
    private TextTransformMath(){}
    public static final int NW=0,NE=1,SW=2,SE=3;
    public static final float MIN_SCALE=.05f,MAX_SCALE=50f;
    public static final class Result {
        public final float x,y,scaleX,scaleY;
        public Result(float x,float y,float sx,float sy){this.x=x;this.y=y;scaleX=sx;scaleY=sy;}
    }
    public static Result resize(float x,float y,float width,float height,float scaleX,float scaleY,int corner,float px,float py){
        if(corner<0||corner>3)throw new IllegalArgumentException("corner");
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(px)||!Float.isFinite(py))throw new IllegalArgumentException("non-finite");
        float w=Math.max(1,width),h=Math.max(1,height);
        boolean left=corner==0||corner==2,top=corner==0||corner==1;
        float anchorX=left?x+w*scaleX:x,anchorY=top?y+h*scaleY:y;
        float sx=Math.max(MIN_SCALE,Math.min(MAX_SCALE,(left?anchorX-px:px-anchorX)/w));
        float sy=Math.max(MIN_SCALE,Math.min(MAX_SCALE,(top?anchorY-py:py-anchorY)/h));
        if(!Float.isFinite(sx))sx=MIN_SCALE;
        if(!Float.isFinite(sy))sy=MIN_SCALE;
        return new Result(left?anchorX-w*sx:anchorX,top?anchorY-h*sy:anchorY,sx,sy);
    }
}