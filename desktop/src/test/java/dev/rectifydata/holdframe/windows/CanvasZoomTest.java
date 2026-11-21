package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.InputEvent;
import java.lang.reflect.Method;

/** Headless canvas zoom math, anchored focus, reset and Ctrl+wheel regression. */
public final class CanvasZoomTest {
    private static void check(boolean v,String msg) {if(!v)throw new AssertionError(msg);}
    private static Model.Point at(CanvasView canvas,int x,int y) throws Exception {
        Method method=CanvasView.class.getDeclaredMethod("toPoint",MouseEvent.class);
        method.setAccessible(true);
        MouseEvent e=new MouseEvent(canvas,MouseEvent.MOUSE_MOVED,1,0,x,y,0,false);
        return (Model.Point)method.invoke(canvas,e);
    }
    public static void main(String[] args) throws Exception {
        CanvasView canvas=new CanvasView(null);
        canvas.setSize(1000,680);
        Model.Point original=at(canvas,350,250);
        canvas.zoomBy(2f,350f,250f);
        check(Math.abs(canvas.zoomLevel()-2f)<.001f,"Zoom doubles");
        Model.Point anchored=at(canvas,350,250);
        check(Math.abs(anchored.x-original.x)<.01f &&
              Math.abs(anchored.y-original.y)<.01f,"Cursor-anchored zoom preserves drawing coordinate");
        canvas.zoomBy(100f);
        check(Math.abs(canvas.zoomLevel()-8f)<.001f,"Upper clamp");
        canvas.zoomBy(.00001f);
        check(Math.abs(canvas.zoomLevel()-.5f)<.001f,"Lower clamp");
        canvas.zoomReset();
        check(Math.abs(canvas.zoomLevel()-1f)<.001f,"Fit resets zoom");
        MouseWheelEvent wheel=new MouseWheelEvent(canvas,MouseWheelEvent.MOUSE_WHEEL,2,
            InputEvent.CTRL_DOWN_MASK,350,250,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,-1);
        for(var listener:canvas.getMouseWheelListeners())listener.mouseWheelMoved(wheel);
        check(canvas.zoomLevel()>1f && wheel.isConsumed(),"Ctrl+wheel zoom");
        canvas.zoomReset();
        System.out.println("CanvasZoomTest passed");
    }
}
