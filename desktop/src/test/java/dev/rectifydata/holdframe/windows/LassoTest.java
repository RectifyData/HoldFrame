package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import java.util.List;

public final class LassoTest {
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        Model.Stroke inside = new Model.Stroke(0xff000000,5,false);
        inside.points.add(new Model.Point(10,10));
        Model.Stroke outside = new Model.Stroke(0xff000000,5,false);
        outside.points.add(new Model.Point(90,90));
        Model.Stroke crossing = new Model.Stroke(0xff000000,5,false);
        crossing.points.add(new Model.Point(-10,25));
        crossing.points.add(new Model.Point(70,25));
        List<Model.Point> rectangle = List.of(new Model.Point(0,0),new Model.Point(50,0),
             new Model.Point(50,50),new Model.Point(0,50));
        List<Model.Stroke> hits = LassoMath.inside(List.of(inside,outside,crossing),rectangle);
        require(hits.size()==2 && hits.contains(inside) && hits.contains(crossing),"lasso crossings");
        require(LassoMath.inside(List.of(inside),List.of(new Model.Point(1,1))).isEmpty(),"invalid path");
        require(LassoMath.bounds(List.of(inside)).x==10,"selection bounds");
        System.out.println("Lasso selection tests passed");
    }
}
