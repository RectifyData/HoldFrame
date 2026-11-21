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
