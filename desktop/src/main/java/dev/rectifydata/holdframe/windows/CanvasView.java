package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Cross-platform Swing stage: onion skins, pen/eraser, lasso selection and pose transforms. */
final class CanvasView extends JComponent {
    enum Tool { PEN, ERASER, LASSO, TEXT }
    private enum Drag { NONE, DRAW, SELECT, MOVE, SCALE }
    private enum Corner { NW, NE, SW, SE }
    private final Editor editor;
    private BufferedImage cache, previousGhost, nextGhost;
    private String cachedDrawingId, previousGhostId, nextGhostId;
    private Model.Stroke preview;
    private float scale = 1f, left, top;
    private float zoom=1f,panX,panY;
    private boolean panning;
    private int lastPanX,lastPanY;
    float zoomLevel(){return zoom;}
    void zoomReset(){zoom=1f;panX=panY=0f;repaint();}
    void zoomBy(float factor){zoomBy(factor,getWidth()/2f,getHeight()/2f);}
    void zoomBy(float factor,float focusX,float focusY) {
        geometry();
        float dx=(focusX-left)/Math.max(.01f,scale);
        float dy=(focusY-top)/Math.max(.01f,scale);
        float requested=zoom*factor;
        if(!Float.isFinite(requested))return;
        zoom=Math.max(.5f,Math.min(8f,requested));
        geometry();
        panX+=focusX-(left+dx*scale);
        panY+=focusY-(top+dy*scale);
        repaint();
    }

    private Color ink = new Color(0x171717);
    private float brush = 6;
    private Tool tool = Tool.PEN;
    private boolean onion = true, previousOnion = true, nextOnion = true;
    private Drag drag = Drag.NONE;
    private final List<Model.Stroke> selection = new ArrayList<>();
    private final List<Model.TextItem> selectedTexts = new ArrayList<>();
    private final List<Model.ImageItem> selectedImages = new ArrayList<>();
    private final List<Model.Point> lasso = new ArrayList<>();
    private Map<Model.Stroke,List<Model.Point>> originalPositions;
    private Map<Model.Stroke,Float> originalWidths;
    private Map<Model.TextItem,Model.TextItem> originalTexts;
    private Map<Model.ImageItem,Model.ImageItem> originalImages;
    private Corner activeCorner;
    private Rectangle2D.Float transformBounds;
    private Model.Point dragOrigin;

    CanvasView(Editor editor) {
        this.editor = editor;
        setOpaque(true);
        setBackground(new Color(0xE6E9ED));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                if (tool != Tool.LASSO && tool != Tool.TEXT) return;
                Model.Point pt=toPoint(event);
                Rectangle2D.Float b=selectionBounds();
                Corner corner=b==null ? null : cornerAt(pt,b);
                int cursor=Cursor.CROSSHAIR_CURSOR;
                if (corner==Corner.NW) cursor=Cursor.NW_RESIZE_CURSOR;
                else if (corner==Corner.NE) cursor=Cursor.NE_RESIZE_CURSOR;
                else if (corner==Corner.SW) cursor=Cursor.SW_RESIZE_CURSOR;
                else if (corner==Corner.SE) cursor=Cursor.SE_RESIZE_CURSOR;
                else if ((b!=null && nearBounds(pt,b)) || hitText(pt)>=0 || (tool==Tool.LASSO && hitImage(pt)>=0)) cursor=Cursor.MOVE_CURSOR;
                setCursor(Cursor.getPredefinedCursor(cursor));
            }
            @Override public void mousePressed(MouseEvent event) {
                if(SwingUtilities.isMiddleMouseButton(event)) {
                    panning=true;lastPanX=event.getX();lastPanY=event.getY();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(event) || !onPaper(event.getX(),event.getY())) return;
                editor.stopPlayback();
                Model.Point pt = toPoint(event);
                if (tool == Tool.TEXT) {
                    int textIndex=hitText(pt);
                    if (textIndex>=0 && event.getClickCount()>=2) {
                        editor.editTextAt(textIndex);
                        return;
                    }
                    Rectangle2D.Float bounds=selectionBounds();
                    Corner corner=bounds==null?null:cornerAt(pt,bounds);
                    if(corner!=null) {
                        activeCorner=corner;
                        beginTransform(pt,Drag.SCALE);
                    } else if(bounds!=null && nearBounds(pt,bounds)) {
                        beginTransform(pt,Drag.MOVE);
                    } else if(textIndex>=0) {
                        clearSelection();
                        selectedTexts.add(editor.project().drawingAt(editor.selected()).texts.get(textIndex));
                        beginTransform(pt,Drag.MOVE);
                    } else {
                        clearSelection();
                        editor.addTextAt(pt);
                    }
                    repaint();
                    return;
                }
                if (tool != Tool.LASSO) {
                    editor.prepareDrawing();
                    clearSelection();
                    preview = new Model.Stroke(ink.getRGB(),brush,tool==Tool.ERASER);
                    preview.points.add(pt);
                    drag = Drag.DRAW;
                } else {
                    int tappedText=hitText(pt);
                    int tappedImage=hitImage(pt);
                    if (event.getClickCount() >= 2 && tappedText >= 0) {
                        editor.editTextAt(tappedText);
                        clearSelection();
                        return;
                    }
                    Rectangle2D.Float bounds = selectionBounds();
                    Corner corner=bounds==null ? null : cornerAt(pt,bounds);
                    if (corner != null) {
                        activeCorner=corner;
                        beginTransform(pt,Drag.SCALE);
                    } else if (bounds != null && nearBounds(pt,bounds)) {
                        beginTransform(pt,Drag.MOVE);
                    } else if (tappedText >= 0) {
                        clearSelection();
                        selectedTexts.add(editor.project().drawingAt(editor.selected()).texts.get(tappedText));
                        beginTransform(pt,Drag.MOVE);
                    } else if (tappedImage >= 0) {
                        clearSelection();
                        selectedImages.add(editor.project().drawingAt(editor.selected()).images.get(tappedImage));
                        beginTransform(pt,Drag.MOVE);
                    } else {
                        clearSelection();
                        drag = Drag.SELECT;
                        lasso.add(pt);
                    }
                }
                repaint();
            }
            @Override public void mouseDragged(MouseEvent event) {
                if(panning) {
                    panX+=event.getX()-lastPanX;panY+=event.getY()-lastPanY;
                    lastPanX=event.getX();lastPanY=event.getY();repaint();return;
                }
                Model.Point pt = toPoint(event);
                if (drag == Drag.DRAW && preview != null) {
                    Model.Point prev = preview.points.get(preview.points.size()-1);
                    if (Math.abs(prev.x-pt.x)>0.05 || Math.abs(prev.y-pt.y)>0.05)
                        preview.points.add(pt);
                } else if (drag == Drag.SELECT) {
                    if (lasso.isEmpty() || distance(lasso.get(lasso.size()-1),pt)>1.5)
                        lasso.add(pt);
                } else if (drag == Drag.MOVE || drag == Drag.SCALE) {
                    updateTransform(pt);
                }
                repaint();
            }
            @Override public void mouseReleased(MouseEvent event) {
                if(panning) {
                    panning=false;setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));return;
                }
                if (drag == Drag.DRAW && preview != null) {
                    // The project changes only on mouse release; the preview is not saved on cancel.
                    editor.project().drawingAt(editor.selected()).strokes.add(preview);
                    editor.strokeAdded(preview);
                    preview = null;
                    invalidateDrawing();
                } else if (drag == Drag.SELECT) {
                    if (lasso.size()>2) {
                        // Segment crossed strokes at the boundary so movement/deletion
                        // affects only the enclosed vector portions, not the whole stroke.
                        editor.prepareDrawing();
                        Model.Drawing pose=editor.project().drawingAt(editor.selected());
                        List<Model.Stroke> before=new ArrayList<>(pose.strokes);
                        List<Model.Stroke> pieces=new ArrayList<>();
                        boolean separated=false;
                        for (Model.Stroke stroke:before) {
                            List<LassoMath.Piece> regions=LassoMath.partition(stroke,lasso);
                            if(regions.size()>1) separated=true;
                            for(LassoMath.Piece part:regions) {
                                pieces.add(part.stroke);
                                if(part.selected)selection.add(part.stroke);
                            }
                        }
                        if(separated) {
                            pose.strokes.clear();
                            pose.strokes.addAll(pieces);
                            editor.lassoStrokePartition(before,new ArrayList<>(pieces));
                            invalidateDrawing();
                        }
                        Path2D.Float polygon=lassoPath();
                        for (Model.TextItem text : editor.project().drawingAt(editor.selected()).texts) {
                            Rectangle2D.Float rect=FontSupport.bounds(text);
                            if (polygon.contains(rect.getCenterX(),rect.getCenterY()))
                                selectedTexts.add(text);
                        }
                        for (Model.ImageItem image : editor.project().drawingAt(editor.selected()).images) {
                            Rectangle2D.Float rect=new Rectangle2D.Float(image.x,image.y,image.width,image.height);
                            if (polygon.intersects(rect) || polygon.contains(rect.getCenterX(),rect.getCenterY()))
                                selectedImages.add(image);
                        }
                    }
                    lasso.clear();
                } else if (drag == Drag.MOVE || drag == Drag.SCALE) {
                    updateTransform(toPoint(event));
                    if (originalPositions != null)
                        editor.transformFinished(selection,originalPositions,originalWidths,
                                                 selectedTexts,originalTexts,selectedImages,originalImages);
                    originalPositions = null;
                    originalWidths = null;
                    originalTexts = null;
                    originalImages = null;
                    invalidateDrawing();
                }
                drag=Drag.NONE;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(event -> {
            if(event.isControlDown()) {
                zoomBy((float)Math.pow(1.18,-event.getPreciseWheelRotation()),event.getX(),event.getY());
                event.consume();
            }
        });
    }

    void setTool(Tool next) {
        tool=next;
        clearSelection();
        setCursor(Cursor.getPredefinedCursor(tool==Tool.TEXT ? Cursor.TEXT_CURSOR : Cursor.CROSSHAIR_CURSOR));
        repaint();
    }
    Tool getTool() { return tool; }
    void selectAll() {
        setTool(Tool.LASSO);
        clearSelection();
        selection.addAll(editor.project().drawingAt(editor.selected()).strokes);
        selectedTexts.addAll(editor.project().drawingAt(editor.selected()).texts);
        selectedImages.addAll(editor.project().drawingAt(editor.selected()).images);
        repaint();
    }
    void selectOnlyText(Model.TextItem text) {
        setTool(Tool.TEXT);
        clearSelection();
        if (text!=null) selectedTexts.add(text);
        repaint();
    }
    void selectOnlyImage(Model.ImageItem image) {
        setTool(Tool.LASSO);
        clearSelection();
        selectedImages.add(image);
        repaint();
    }
    void setInk(Color color) { ink=color; }
    Color getInk() { return ink; }
    void setBrush(float size) { brush=size; }
    void setOnion(boolean visible) { onion=visible; repaint(); }
    void setPreviousOnion(boolean visible) { previousOnion=visible; repaint(); }
    void setNextOnion(boolean visible) { nextOnion=visible; repaint(); }
    boolean isOnion() { return onion; }
    /** Capture selected-object indexes before the drawing is detached on write. */
    List<Integer> selectedStrokeIndexes() {
        List<Integer> out=new ArrayList<>();
        List<Model.Stroke> all=editor.project().drawingAt(editor.selected()).strokes;
        for(Model.Stroke x:selection) {int i=all.indexOf(x);if(i>=0)out.add(i);}
        return out;
    }
    List<Integer> selectedTextIndexes() {
        List<Integer> out=new ArrayList<>();
        List<Model.TextItem> all=editor.project().drawingAt(editor.selected()).texts;
        for(Model.TextItem x:selectedTexts) {int i=all.indexOf(x);if(i>=0)out.add(i);}
        return out;
    }
    List<Integer> selectedImageIndexes() {
        List<Integer> out=new ArrayList<>();
        List<Model.ImageItem> all=editor.project().drawingAt(editor.selected()).images;
        for(Model.ImageItem x:selectedImages) {int i=all.indexOf(x);if(i>=0)out.add(i);}
        return out;
    }
    void clearSelection() {
        selection.clear(); selectedTexts.clear(); selectedImages.clear(); lasso.clear();
        originalPositions=null; originalWidths=null; originalTexts=null; originalImages=null;
        drag=Drag.NONE; activeCorner=null; repaint();
    }
    void invalidateDrawing() {
        cachedDrawingId=previousGhostId=nextGhostId=null;
        repaint();
    }
    private boolean nearBounds(Model.Point p,Rectangle2D.Float r) {
        return p.x >= r.x-6 && p.y >= r.y-6 && p.x <= r.x+r.width+6 && p.y <= r.y+r.height+6;
    }
    private static float distance(Model.Point a,Model.Point b) {
        return (float)Math.hypot(a.x-b.x,a.y-b.y);
    }
    private Path2D.Float lassoPath() {
        Path2D.Float path=new Path2D.Float();
        if (!lasso.isEmpty()) {
            path.moveTo(lasso.get(0).x,lasso.get(0).y);
            for (int i=1;i<lasso.size();i++) path.lineTo(lasso.get(i).x,lasso.get(i).y);
            path.closePath();
        }
        return path;
    }
    private int hitText(Model.Point p) {
        List<Model.TextItem> items=editor.project().drawingAt(editor.selected()).texts;
        for (int i=items.size()-1;i>=0;i--) if (FontSupport.bounds(items.get(i)).contains(p.x,p.y)) return i;
        return -1;
    }
    private int hitImage(Model.Point p) {
        List<Model.ImageItem> items=editor.project().drawingAt(editor.selected()).images;
        for (int i=items.size()-1;i>=0;i--) {
            Model.ImageItem image=items.get(i);
            if (new Rectangle2D.Float(image.x,image.y,image.width,image.height).contains(p.x,p.y)) return i;
        }
        return -1;
    }
    private static Rectangle2D.Float union(Rectangle2D.Float bounds,Rectangle2D.Float r) {
        if (bounds==null) return (Rectangle2D.Float)r.clone();
        float x=Math.min(bounds.x,r.x),y=Math.min(bounds.y,r.y);
        float right=Math.max(bounds.x+bounds.width,r.x+r.width);
        float bottom=Math.max(bounds.y+bounds.height,r.y+r.height);
        return new Rectangle2D.Float(x,y,right-x,bottom-y);
    }
    private Rectangle2D.Float selectionBounds() {
        Rectangle2D.Float bounds=LassoMath.bounds(selection);
        for (Model.TextItem item:selectedTexts) bounds=union(bounds,FontSupport.bounds(item));
        for (Model.ImageItem image:selectedImages)
            bounds=union(bounds,new Rectangle2D.Float(image.x,image.y,image.width,image.height));
        return bounds;
    }
    private static float cornerX(Rectangle2D.Float b,Corner c) {
        return c==Corner.NE || c==Corner.SE ? b.x+b.width : b.x;
    }
    private static float cornerY(Rectangle2D.Float b,Corner c) {
        return c==Corner.SE || c==Corner.SW ? b.y+b.height : b.y;
    }
    private Corner cornerAt(Model.Point p,Rectangle2D.Float bounds) {
        float tolerance=10f/Math.max(.3f,scale);
        for (Corner corner : Corner.values())
            if (Math.abs(p.x-cornerX(bounds,corner))<=tolerance &&
                Math.abs(p.y-cornerY(bounds,corner))<=tolerance) return corner;
        return null;
    }
    private void beginTransform(Model.Point pt,Drag operation) {
        Model.Drawing previous=editor.project().drawingAt(editor.selected());
        List<Integer> strokeIndexes=new ArrayList<>(), textIndexes=new ArrayList<>(), imageIndexes=new ArrayList<>();
        for (Model.Stroke s : selection) strokeIndexes.add(previous.strokes.indexOf(s));
        for (Model.TextItem t : selectedTexts) textIndexes.add(previous.texts.indexOf(t));
        for (Model.ImageItem image : selectedImages) imageIndexes.add(previous.images.indexOf(image));
        editor.prepareDrawing();
        Model.Drawing current=editor.project().drawingAt(editor.selected());
        selection.clear(); selectedTexts.clear(); selectedImages.clear();
        for (int index : strokeIndexes)
            if (index>=0 && index<current.strokes.size()) selection.add(current.strokes.get(index));
        for (int index : textIndexes)
            if (index>=0 && index<current.texts.size()) selectedTexts.add(current.texts.get(index));
        for (int index : imageIndexes)
            if (index>=0 && index<current.images.size()) selectedImages.add(current.images.get(index));
        if (selection.isEmpty() && selectedTexts.isEmpty() && selectedImages.isEmpty()) { drag=Drag.NONE; return; }
        originalPositions=new IdentityHashMap<>(); originalWidths=new IdentityHashMap<>();
        originalTexts=new IdentityHashMap<>(); originalImages=new IdentityHashMap<>();
        for (Model.Stroke s : selection) {
            originalPositions.put(s,new ArrayList<>(s.points));
            originalWidths.put(s,s.width);
        }
        for (Model.TextItem t : selectedTexts) originalTexts.put(t,t.copy());
        for (Model.ImageItem image : selectedImages) originalImages.put(image,image.copy());
        dragOrigin=pt;
        transformBounds=selectionBounds();
        drag=operation;
    }
    private void updateTransform(Model.Point p) {
        if (originalPositions==null || transformBounds==null) return;
        float dx=p.x-dragOrigin.x,dy=p.y-dragOrigin.y;
        float sx=1f,sy=1f;
        float anchorX=transformBounds.x,anchorY=transformBounds.y;
        if (drag==Drag.SCALE && activeCorner!=null) {
            boolean leftSide=activeCorner==Corner.NW || activeCorner==Corner.SW;
            boolean topSide=activeCorner==Corner.NW || activeCorner==Corner.NE;
            anchorX=leftSide ? transformBounds.x+transformBounds.width : transformBounds.x;
            anchorY=topSide ? transformBounds.y+transformBounds.height : transformBounds.y;
            float originalX=cornerX(transformBounds,activeCorner);
            float originalY=cornerY(transformBounds,activeCorner);
            float edgeX=originalX+dx,edgeY=originalY+dy;
            float denominatorX=originalX-anchorX,denominatorY=originalY-anchorY;
            sx=Math.max(.05f,Math.min(100f,Math.abs(denominatorX)<.001f ? 1f :
                (edgeX-anchorX)/denominatorX));
            sy=Math.max(.05f,Math.min(100f,Math.abs(denominatorY)<.001f ? 1f :
                (edgeY-anchorY)/denominatorY));
        }
        for (Model.Stroke stroke : selection) {
            List<Model.Point> prior=originalPositions.get(stroke);
            if (prior==null) continue;
            stroke.points.clear();
            for (Model.Point original : prior) {
                float x=drag==Drag.SCALE ? anchorX+(original.x-anchorX)*sx : original.x+dx;
                float y=drag==Drag.SCALE ? anchorY+(original.y-anchorY)*sy : original.y+dy;
                stroke.points.add(new Model.Point(x,y));
            }
            if (drag==Drag.SCALE)
                stroke.width=Math.max(.1f,Math.min(200f,originalWidths.get(stroke)*(float)Math.sqrt(sx*sy)));
        }
        for (Model.TextItem text : selectedTexts) {
            Model.TextItem original=originalTexts.get(text);
            if (original==null) continue;
            if (drag==Drag.SCALE) {
                text.x=anchorX+(original.x-anchorX)*sx;
                text.y=anchorY+(original.y-anchorY)*sy;
                text.scaleX=Math.max(.01f,Math.min(100f,original.scaleX*sx));
                text.scaleY=Math.max(.01f,Math.min(100f,original.scaleY*sy));
            } else {
                text.x=original.x+dx;
                text.y=original.y+dy;
            }
        }
        for (Model.ImageItem image : selectedImages) {
            Model.ImageItem previous=originalImages.get(image);
            if (previous==null) continue;
            if (drag==Drag.SCALE) {
                image.x=anchorX+(previous.x-anchorX)*sx;
                image.y=anchorY+(previous.y-anchorY)*sy;
                image.width=Math.max(.01f,previous.width*sx);
                image.height=Math.max(.01f,previous.height*sy);
            } else { image.x=previous.x+dx; image.y=previous.y+dy; }
        }
        invalidateDrawing();
    }
    private void geometry() {
        float w=Math.max(1,getWidth()-32),h=Math.max(1,getHeight()-32);
        scale=Math.min(w/Model.WIDTH,h/Model.HEIGHT)*zoom;
        left=(getWidth()-Model.WIDTH*scale)/2+panX;
        top=(getHeight()-Model.HEIGHT*scale)/2+panY;
    }
    private boolean onPaper(int x,int y) {
        geometry();
        return x>=left && x<=left+Model.WIDTH*scale && y>=top && y<=top+Model.HEIGHT*scale;
    }
    private Model.Point toPoint(MouseEvent event) {
        geometry();
        float x=Math.max(0,Math.min(Model.WIDTH,(event.getX()-left)/scale));
        float y=Math.max(0,Math.min(Model.HEIGHT,(event.getY()-top)/scale));
        return new Model.Point(x,y);
    }
    static void paintStroke(Graphics2D g,Model.Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        g.setComposite(stroke.eraser ? AlphaComposite.Clear : AlphaComposite.SrcOver);
        g.setColor(new Color(stroke.color,true));
        g.setStroke(new BasicStroke(stroke.width,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        if (stroke.points.size()==1) {
            Model.Point p=stroke.points.get(0);
            g.fillOval(Math.round(p.x-stroke.width/2),Math.round(p.y-stroke.width/2),
                       Math.max(1,Math.round(stroke.width)),Math.max(1,Math.round(stroke.width)));
        } else {
            Path2D.Float path=new Path2D.Float();
            path.moveTo(stroke.points.get(0).x,stroke.points.get(0).y);
            for (int i=1;i<stroke.points.size();i++) path.lineTo(stroke.points.get(i).x,stroke.points.get(i).y);
            g.draw(path);
        }
        g.setComposite(AlphaComposite.SrcOver);
    }
    private BufferedImage rasterize(Model.Drawing drawing) {
        BufferedImage image=new BufferedImage(Model.WIDTH,Model.HEIGHT,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        for (Model.ImageItem item:drawing.images) {
            BufferedImage src=ImageSupport.get(editor.project(),item.assetId);
            if (src!=null) g.drawImage(src,Math.round(item.x),Math.round(item.y),
                Math.max(1,Math.round(item.width)),Math.max(1,Math.round(item.height)),null);
        }
        for (Model.Stroke s : drawing.strokes) paintStroke(g,s);
        for (Model.TextItem t : drawing.texts) FontSupport.draw(g,t);
        g.dispose();
        return image;
    }
    private void rebuildCache() {
        Model.Drawing drawing=editor.project().drawingAt(editor.selected());
        if (cache!=null && drawing.id.equals(cachedDrawingId)) return;
        cache=rasterize(drawing);
        cachedDrawingId=drawing.id;
    }
    private BufferedImage ghost(Model.Drawing drawing,Color tint) {
        BufferedImage src=rasterize(drawing);
        int rgb=tint.getRGB() & 0x00FFFFFF;
        for (int y=0;y<src.getHeight();y++) for (int x=0;x<src.getWidth();x++) {
            int rgba=src.getRGB(x,y);
            src.setRGB(x,y,(rgba&0xFF000000)|rgb);
        }
        return src;
    }
    private void onion(Graphics2D out) {
        if (!onion || editor.isPlaying()) return;
        int index=editor.selected();
        if (previousOnion && index>0) {
            Model.Drawing d=editor.project().drawingAt(index-1);
            if (previousGhost==null || !d.id.equals(previousGhostId)) {
                previousGhost=ghost(d,new Color(0xE94B58)); previousGhostId=d.id;
            }
            out.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.25f));
            out.drawImage(previousGhost,0,0,Model.WIDTH,Model.HEIGHT,null);
        }
        if (nextOnion && index<editor.project().timeline.size()-1) {
            Model.Drawing d=editor.project().drawingAt(index+1);
            if (nextGhost==null || !d.id.equals(nextGhostId)) {
                nextGhost=ghost(d,new Color(0x3588E8)); nextGhostId=d.id;
            }
            out.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.20f));
            out.drawImage(nextGhost,0,0,Model.WIDTH,Model.HEIGHT,null);
        }
        out.setComposite(AlphaComposite.SrcOver);
    }
    private void overlay(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        if (preview!=null) {
            Model.Stroke visible=preview;
            if (preview.eraser) { visible=preview.copy(); visible.eraser=false; visible.color=Color.WHITE.getRGB(); }
            paintStroke(g,visible);
        }
        if (!lasso.isEmpty()) {
            Path2D.Float path=new Path2D.Float();
            path.moveTo(lasso.get(0).x,lasso.get(0).y);
            for (int i=1;i<lasso.size();i++) path.lineTo(lasso.get(i).x,lasso.get(i).y);
            g.setColor(new Color(0x197ABF));
            g.setStroke(new BasicStroke(1.5f/scale,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,
                                        1,new float[]{5/scale,4/scale},0));
            g.draw(path);
        }
        Rectangle2D.Float b=selectionBounds();
        if (b!=null && (tool==Tool.LASSO || (tool==Tool.TEXT && !selectedTexts.isEmpty()))) {
            float pad=4/scale;
            g.setColor(new Color(0x116DA8));
            g.setStroke(new BasicStroke(1.5f/scale,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                                        1,new float[]{6/scale,4/scale},0));
            g.draw(new Rectangle2D.Float(b.x-pad,b.y-pad,b.width+pad*2,b.height+pad*2));
            float handle=9/scale;
            g.setStroke(new BasicStroke(1.3f/scale));
            for (Corner corner : Corner.values()) {
                float x=cornerX(b,corner)-handle/2;
                float y=cornerY(b,corner)-handle/2;
                g.setColor(Color.WHITE);
                g.fill(new Rectangle2D.Float(x,y,handle,handle));
                g.setColor(new Color(0x116DA8));
                g.draw(new Rectangle2D.Float(x,y,handle,handle));
            }
        }
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        geometry();
        Graphics2D out=(Graphics2D)graphics.create();
        out.setColor(getBackground());
        out.fillRect(0,0,getWidth(),getHeight());
        out.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        out.setColor(new Color(0xB6BBC2));
        out.fillRect(Math.round(left)-2,Math.round(top)-2,Math.round(Model.WIDTH*scale)+4,
                     Math.round(Model.HEIGHT*scale)+4);
        out.setColor(Color.WHITE);
        out.fillRect(Math.round(left),Math.round(top),Math.round(Model.WIDTH*scale),Math.round(Model.HEIGHT*scale));
        Graphics2D stage=(Graphics2D)out.create();
        stage.translate(left,top);
        stage.scale(scale,scale);
        stage.clipRect(0,0,Model.WIDTH,Model.HEIGHT);
        onion(stage);
        rebuildCache();
        stage.drawImage(cache,0,0,null);
        overlay(stage);
        stage.dispose();
        out.dispose();
    }
}
