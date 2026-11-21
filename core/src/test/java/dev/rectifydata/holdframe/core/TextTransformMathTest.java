package dev.rectifydata.holdframe.core;

public final class TextTransformMathTest {
    private static void eq(float e,float a,String label){if(Math.abs(e-a)>.001f)throw new AssertionError(label+": "+a+" expected "+e);}
    public static void main(String[] args){
        for(int corner=0;corner<4;corner++){
            float x=100,y=50,w=100,h=40;
            float ax=(corner==0||corner==2)?x+w:x;
            float ay=(corner==0||corner==1)?y+h:y;
            float px=(corner==0||corner==2)?ax-200:ax+200;
            float py=(corner==0||corner==1)?ay-120:ay+120;
            TextTransformMath.Result r=TextTransformMath.resize(x,y,w,h,1,1,corner,px,py);
            eq(2,r.scaleX,"horizontal");eq(3,r.scaleY,"vertical");
            eq(ax,(corner==0||corner==2)?r.x+w*r.scaleX:r.x,"opposite X anchor");
            eq(ay,(corner==0||corner==1)?r.y+h*r.scaleY:r.y,"opposite Y anchor");
        }
        TextTransformMath.Result r=TextTransformMath.resize(100,50,100,40,1,1,0,10000,10000);
        eq(.05f,r.scaleX,"smallest X");eq(.05f,r.scaleY,"smallest Y");
        r=TextTransformMath.resize(100,50,100,40,1,1,3,100000,100000);
        eq(50,r.scaleX,"largest X");eq(50,r.scaleY,"largest Y");
        boolean error=false;
        try{TextTransformMath.resize(1,1,1,1,1,1,7,1,1);}catch(IllegalArgumentException e){error=true;}
        if(!error)throw new AssertionError("invalid corner accepted");
        System.out.println("TextTransformMathTest passed");
    }
}
