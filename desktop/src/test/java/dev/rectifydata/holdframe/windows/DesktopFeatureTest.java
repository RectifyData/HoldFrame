package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import javax.swing.JComponent;
import javax.swing.Action;
import java.awt.event.ActionEvent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

/** End-to-end Swing tool regression test; use xvfb-run on Linux. */
public final class DesktopFeatureTest {
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }
    private static int sx(CanvasView stage,float x) {
        float k=Math.min((stage.getWidth()-32f)/Model.WIDTH,(stage.getHeight()-32f)/Model.HEIGHT);
        return Math.round((stage.getWidth()-Model.WIDTH*k)/2+x*k);
    }
    private static int sy(CanvasView stage,float y) {
        float k=Math.min((stage.getWidth()-32f)/Model.WIDTH,(stage.getHeight()-32f)/Model.HEIGHT);
        return Math.round((stage.getHeight()-Model.HEIGHT*k)/2+y*k);
    }
    private static void point(CanvasView stage,int type,float x,float y) {
        int buttons=type==MouseEvent.MOUSE_DRAGGED?InputEvent.BUTTON1_DOWN_MASK:0;
        MouseEvent event=new MouseEvent(stage,type,System.currentTimeMillis(),buttons,sx(stage,x),sy(stage,y),
                                         1,false,type==MouseEvent.MOUSE_DRAGGED?MouseEvent.NOBUTTON:MouseEvent.BUTTON1);
        stage.dispatchEvent(event);
    }
    private static void timelineEvent(TimelineView timeline,int type,int x,int y) {
        int mods=type==MouseEvent.MOUSE_DRAGGED?InputEvent.BUTTON1_DOWN_MASK:0;
        timeline.dispatchEvent(new MouseEvent(timeline,type,System.currentTimeMillis(),mods,
            x,y,1,false,type==MouseEvent.MOUSE_DRAGGED?MouseEvent.NOBUTTON:MouseEvent.BUTTON1));
    }
    private static void shortcut(Editor editor,String keys) {
        Object name=editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                          .get(KeyStroke.getKeyStroke(keys));
        check(name!=null,"shortcut not bound: " + keys);
        Action action=editor.getRootPane().getActionMap().get(name);
        check(action!=null,"shortcut action missing: " + keys);
        action.actionPerformed(new ActionEvent(editor,ActionEvent.ACTION_PERFORMED,keys));
    }
    private static BufferedImage render(CanvasView stage) {
        BufferedImage image=new BufferedImage(stage.getWidth(),stage.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        stage.paint(g);
        g.dispose();
        return image;
    }
    public static void main(String[] args) throws Exception {
        // Initialize the AWT mouse-event class on the caller thread before XAWT starts;
        // synthetic event creation on the EDT during XToolkit init can deadlock Java 21.
        new MouseEvent(new javax.swing.JPanel(),MouseEvent.MOUSE_MOVED,
            System.currentTimeMillis(),0,0,0,0,false);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Editor editor=new Editor();
            try {
                editor.setVisible(true);
                CanvasView stage = find(editor.getContentPane(),CanvasView.class);
                check(stage!=null && stage.getWidth()>200,"canvas was laid out");
                point(stage,MouseEvent.MOUSE_PRESSED,180,140);
                point(stage,MouseEvent.MOUSE_DRAGGED,240,175);
                point(stage,MouseEvent.MOUSE_RELEASED,240,175);
                check(editor.project().drawingAt(0).strokes.size()==1,"pen committed stroke");
                Model.Stroke stroke=editor.project().drawingAt(0).strokes.get(0);
                check(stroke.points.size()>=2,"stroke has points");
                stage.setTool(CanvasView.Tool.LASSO);
                point(stage,MouseEvent.MOUSE_PRESSED,150,105);
                point(stage,MouseEvent.MOUSE_DRAGGED,270,105);
                point(stage,MouseEvent.MOUSE_DRAGGED,270,205);
                point(stage,MouseEvent.MOUSE_DRAGGED,150,205);
                point(stage,MouseEvent.MOUSE_RELEASED,150,105);
                point(stage,MouseEvent.MOUSE_PRESSED,195,151);
                point(stage,MouseEvent.MOUSE_DRAGGED,235,171);
                point(stage,MouseEvent.MOUSE_RELEASED,235,171);
                check(Math.abs(stroke.points.get(0).x-220)<2,"lasso moved selected pose x");
                check(Math.abs(stroke.points.get(0).y-160)<2,"lasso moved selected pose y");
                point(stage,MouseEvent.MOUSE_PRESSED,280,195);
                point(stage,MouseEvent.MOUSE_DRAGGED,320,230);
                point(stage,MouseEvent.MOUSE_RELEASED,320,230);
                check(Math.abs(stroke.points.get(1).x-320)<3,"lasso scaled selection");
                shortcut(editor,"control Z");
                check(Math.abs(stroke.points.get(1).x-280)<3,"transform undo");
                shortcut(editor,"control Y");
                check(Math.abs(stroke.points.get(1).x-320)<3,"transform redo");
                int second=editor.project().addAfter(0,false);
                editor.seek(editor.project().clipStart(second));
                stage.setTool(CanvasView.Tool.PEN);
                stage.setOnion(true);
                stage.setPreviousOnion(true);
                stage.setNextOnion(false);
                BufferedImage withOnion=render(stage);
                stage.setOnion(false);
                BufferedImage without=render(stage);
                int centerX=sx(stage,220),centerY=sy(stage,160);
                Color ghostColor=new Color(withOnion.getRGB(centerX,centerY));
                Color blankColor=new Color(without.getRGB(centerX,centerY));
                check(!ghostColor.equals(blankColor),"previous pose onion skin visibly affects stage");
                editor.seek(0);
                shortcut(editor,"control A");
                check(stage.getTool()==CanvasView.Tool.LASSO,"Ctrl+A selects transform tool");
                shortcut(editor,"control C");
                shortcut(editor,"control V");
                shortcut(editor,"control V");
                check(editor.project().timeline.size()==4,"Ctrl+C / repeated Ctrl+V inserts frames");
                check(editor.project().drawings.size()==3,"repeated keyboard pastes share a drawing");
                check(editor.project().drawingAt(1).strokes.size()==1,"clipboard captured transformed pose");
                TimelineView timeline=find(editor.getContentPane(),TimelineView.class);
                check(timeline!=null,"timeline widget exists");
                int last=editor.project().timeline.size()-1;
                int oldDuration=editor.project().timeline.get(last).duration;
                int edge=16+editor.project().totalFrames()*7;
                timelineEvent(timeline,MouseEvent.MOUSE_PRESSED,edge,54);
                timelineEvent(timeline,MouseEvent.MOUSE_DRAGGED,edge+70,54);
                timelineEvent(timeline,MouseEvent.MOUSE_RELEASED,edge+70,54);
                check(editor.project().timeline.get(last).duration==oldDuration+10,
                    "last pose exposure can be extended past old timeline end");
                editor.dispose();
                System.out.println("Desktop GUI passed: pen, lasso move/scale, transform undo/redo, onion, copy/paste");
            } catch (Throwable ex) {
                failure.set(ex);
                editor.dispose();
            }
        });
        if (failure.get()!=null) throw new RuntimeException(failure.get());
    }
    @SuppressWarnings("unchecked")
    private static <T> T find(java.awt.Container parent,Class<T> cls) {
        for (java.awt.Component component : parent.getComponents()) {
            if (cls.isInstance(component)) return (T)component;
            if (component instanceof java.awt.Container) {
                T child=find((java.awt.Container)component,cls);
                if (child!=null) return child;
            }
        }
        return null;
    }
}
