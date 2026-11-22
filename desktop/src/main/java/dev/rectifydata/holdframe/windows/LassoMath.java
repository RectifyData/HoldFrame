package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/** Geometry is in project coordinates and works on either Windows or Linux. */
final class LassoMath {
    private LassoMath() {}
    static List<Model.Stroke> inside(List<Model.Stroke> strokes, List<Model.Point> polygon) {
        List<Model.Stroke> found = new ArrayList<>();
        if (polygon.size() < 3) return found;
        Path2D.Float path = new Path2D.Float();
        path.moveTo(polygon.get(0).x, polygon.get(0).y);
        for (int i = 1; i < polygon.size(); i++) path.lineTo(polygon.get(i).x, polygon.get(i).y);
        path.closePath();
        Rectangle2D outer = path.getBounds2D();
        for (Model.Stroke stroke : strokes) {
            boolean hit = false;
            List<Model.Point> points = stroke.points;
            for (Model.Point pt : points) {
                if (path.contains(pt.x, pt.y)) { hit = true; break; }
            }
            if (!hit && outer.getWidth() > 0 && outer.getHeight() > 0) {
                for (int j = 1; j < points.size() && !hit; j++) {
                    Model.Point a = points.get(j-1), b = points.get(j);
                    // A long stroke can pass across the lasso with no sampled vertices inside.
                    for (int k = 0; k < polygon.size(); k++) {
                        Model.Point c = polygon.get(k), d = polygon.get((k+1)%polygon.size());
                        if (Line2D.linesIntersect(a.x,a.y,b.x,b.y,c.x,c.y,d.x,d.y)) {
                            hit = true; break;
                        }
                    }
                }
            }
            if (hit) found.add(stroke);
        }
        return found;
    }

    /** A path fragment and whether that exact fragment was enclosed by the lasso. */
    static final class Piece {
        final Model.Stroke stroke;
        final boolean selected;
        Piece(Model.Stroke stroke, boolean selected) {this.stroke=stroke;this.selected=selected;}
    }
    private static Model.Stroke fresh(Model.Stroke original) {
        return new Model.Stroke(original.color,original.width,original.eraser);
    }
    private static Model.Point interpolate(Model.Point a,Model.Point b,double t) {
        return new Model.Point((float)(a.x+(b.x-a.x)*t),(float)(a.y+(b.y-a.y)*t));
    }
    private static boolean same(Model.Point a,Model.Point b) {
        return Math.abs(a.x-b.x)<=0.0001f && Math.abs(a.y-b.y)<=0.0001f;
    }
    private static void addUnique(Model.Stroke s,Model.Point p,float pressure,boolean weighted) {
        if(s.points.isEmpty() || !same(s.points.get(s.points.size()-1),p)) {
            s.points.add(p);
            if(weighted)s.pressures.add(pressure);
        }
    }
    private static Path2D.Float polygonPath(List<Model.Point> polygon) {
        Path2D.Float path=new Path2D.Float(Path2D.WIND_EVEN_ODD);
        path.moveTo(polygon.get(0).x,polygon.get(0).y);
        for(int i=1;i<polygon.size();i++)
            path.lineTo(polygon.get(i).x,polygon.get(i).y);
        path.closePath();
        return path;
    }
    /**
     * Split a drawn stroke at the exact lasso boundary. Moving or deleting the
     * selected fragments will leave the original outside fragments untouched.
     * All fragment coordinates remain vector geometry (no raster cropping).
     */
    static List<Piece> partition(Model.Stroke source,List<Model.Point> polygon) {
        List<Piece> pieces=new ArrayList<>();
        if(source.points.isEmpty())return pieces;
        if(polygon.size()<3) {
            pieces.add(new Piece(source,false));
            return pieces;
        }
        Path2D.Float area=polygonPath(polygon);
        if(area.getBounds2D().getWidth()<0.0001||area.getBounds2D().getHeight()<0.0001) {
            pieces.add(new Piece(source,false));
            return pieces;
        }
        if(source.points.size()==1) {
            pieces.add(new Piece(source,area.contains(source.points.get(0).x,source.points.get(0).y)));
            return pieces;
        }
        Model.Stroke active=null;
        Boolean currentInside=null;
        for(int i=1;i<source.points.size();i++) {
            Model.Point a=source.points.get(i-1),b=source.points.get(i);
            boolean weighted=!source.pressures.isEmpty();
            float pa=weighted?source.pressures.get(i-1):1f;
            float pb=weighted?source.pressures.get(i):1f;
            if(same(a,b))continue;
            java.util.ArrayList<Double> cuts=new java.util.ArrayList<>();
            cuts.add(0d);
            cuts.add(1d);
            double rx=b.x-a.x,ry=b.y-a.y;
            for(int j=0;j<polygon.size();j++) {
                Model.Point c=polygon.get(j),d=polygon.get((j+1)%polygon.size());
                double sx=d.x-c.x,sy=d.y-c.y;
                double determinant=rx*sy-ry*sx;
                if(Math.abs(determinant)<1.0e-9)continue;
                double qx=c.x-a.x,qy=c.y-a.y;
                double t=(qx*sy-qy*sx)/determinant;
                double u=(qx*ry-qy*rx)/determinant;
                if(t>=-1e-9 && t<=1+1e-9 && u>=-1e-9 && u<=1+1e-9)
                    cuts.add(Math.max(0d,Math.min(1d,t)));
            }
            java.util.Collections.sort(cuts);
            double previous=cuts.get(0);
            for(int j=1;j<cuts.size();j++) {
                double next=cuts.get(j);
                if(next-previous<1e-7)continue;
                Model.Point midpoint=interpolate(a,b,(previous+next)/2d);
                boolean inside=area.contains(midpoint.x,midpoint.y);
                Model.Point start=interpolate(a,b,previous),end=interpolate(a,b,next);
                if(active==null || currentInside!=inside ||
                   !same(active.points.get(active.points.size()-1),start)) {
                    if(active!=null)pieces.add(new Piece(active,currentInside));
                    active=fresh(source);
                    currentInside=inside;
                    active.points.add(start);
                    if(weighted)active.pressures.add((float)(pa+(pb-pa)*previous));
                }
                addUnique(active,end,(float)(pa+(pb-pa)*next),weighted);
                previous=next;
            }
        }
        if(active!=null)pieces.add(new Piece(active,currentInside));
        if(pieces.isEmpty()) {
            pieces.add(new Piece(source,area.contains(source.points.get(0).x,source.points.get(0).y)));
        } else if(pieces.size()==1) {
            // Keep the original stroke identity and preserve its exact points.
            boolean selected=pieces.get(0).selected;
            pieces.clear();
            pieces.add(new Piece(source,selected));
        }
        return pieces;
    }

    static Rectangle2D.Float bounds(List<Model.Stroke> strokes) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Model.Stroke stroke : strokes) for (Model.Point p : stroke.points) {
            minX = Math.min(p.x,minX); minY = Math.min(p.y,minY);
            maxX = Math.max(p.x,maxX); maxY = Math.max(p.y,maxY);
        }
        return minX == Float.MAX_VALUE ? null : new Rectangle2D.Float(minX,minY,maxX-minX,maxY-minY);
    }
}
