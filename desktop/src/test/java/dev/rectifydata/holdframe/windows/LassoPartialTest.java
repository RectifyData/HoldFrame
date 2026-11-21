package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import java.util.List;

public final class LassoPartialTest {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    private static Model.Stroke stroke(float... pts){
        Model.Stroke s=new Model.Stroke(0xFF124578,7,false);
        for(int i=0;i<pts.length;i+=2)s.points.add(new Model.Point(pts[i],pts[i+1]));
        return s;
    }
    static final List<Model.Point> BOX=List.of(new Model.Point(0,0),
        new Model.Point(50,0),new Model.Point(50,50),new Model.Point(0,50));
    public static void main(String[] args){
        var p=LassoMath.partition(stroke(-20,25,70,25),BOX);
        check(p.size()==3,"long line separated into three fragments");
        check(!p.get(0).selected && p.get(1).selected && !p.get(2).selected,"selected only middle");
        check(p.get(1).stroke.points.get(0).x==0 && p.get(1).stroke.points.get(1).x==50,"exact line intersections");
        check(p.get(0).stroke.points.get(0).x==-20 &&
              p.get(2).stroke.points.get(1).x==70,"outside drawing retained");
        check(p.get(1).stroke.color==0xFF124578 && p.get(1).stroke.width==7,"appearance retained");
        var inside=LassoMath.partition(stroke(20,20,30,30),BOX);
        check(inside.size()==1&&inside.get(0).selected,"whole stroke inside");
        var outside=LassoMath.partition(stroke(60,60,70,70),BOX);
        check(outside.size()==1&&!outside.get(0).selected,"outside never selected");
        var dot=LassoMath.partition(stroke(25,25),BOX);
        check(dot.size()==1&&dot.get(0).selected,"point inside");
        var bent=LassoMath.partition(stroke(-10,20,20,20,65,20),BOX);
        check(bent.size()==3 && bent.get(1).selected,"multi-segment crossing split");
        var eraser=new Model.Stroke(0,7,true);
        eraser.points.add(new Model.Point(-10,25));eraser.points.add(new Model.Point(70,25));
        check(LassoMath.partition(eraser,BOX).stream().allMatch(x->x.stroke.eraser),"eraser fragments remain erasers");
        System.out.println("LassoPartialTest passed");
    }
}
