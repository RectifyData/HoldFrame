package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.InputEvent;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Real Swing selection, partially isolated stroke, Delete, Undo, Undo topology. */
public final class LassoPartialGuiTest {
    private static void check(boolean test,String msg){if(!test)throw new AssertionError(msg);}
    private static CanvasView find(Container c){
        for(java.awt.Component item:c.getComponents()){
            if(item instanceof CanvasView)return (CanvasView)item;
            if(item instanceof Container){
                CanvasView result=find((Container)item);if(result!=null)return result;
            }
        }
        return null;
    }
    private static void send(CanvasView stage,int type,float logicalX,float logicalY){
        float fit=Math.min((stage.getWidth()-32f)/Model.WIDTH,(stage.getHeight()-32f)/Model.HEIGHT);
        int sx=Math.round((stage.getWidth()-Model.WIDTH*fit)/2+logicalX*fit);
        int sy=Math.round((stage.getHeight()-Model.HEIGHT*fit)/2+logicalY*fit);
        int flags=type==MouseEvent.MOUSE_DRAGGED?InputEvent.BUTTON1_DOWN_MASK:0;
        stage.dispatchEvent(new MouseEvent(stage,type,System.nanoTime()/1000000,flags,sx,sy,1,false,
            type==MouseEvent.MOUSE_DRAGGED?MouseEvent.NOBUTTON:MouseEvent.BUTTON1));
    }
    private static void invoke(Editor editor,String method) throws Exception {
        Method action=Editor.class.getDeclaredMethod(method);
        action.setAccessible(true);
        action.invoke(editor);
    }
    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            Editor editor=new Editor();
            try {
                editor.setVisible(true);
                CanvasView stage=find(editor.getContentPane());
                check(stage!=null && stage.getWidth()>100,"visible canvas");
                Model.Stroke stroke=new Model.Stroke(0xff123456,8,false);
                stroke.points.add(new Model.Point(100,200));
                stroke.points.add(new Model.Point(700,200));
                editor.project().drawingAt(0).strokes.add(stroke);
                stage.invalidateDrawing();
                stage.setTool(CanvasView.Tool.LASSO);
                send(stage,MouseEvent.MOUSE_PRESSED,320,150);
                send(stage,MouseEvent.MOUSE_DRAGGED,480,150);
                send(stage,MouseEvent.MOUSE_DRAGGED,480,250);
                send(stage,MouseEvent.MOUSE_DRAGGED,320,250);
                send(stage,MouseEvent.MOUSE_RELEASED,320,150);
                var drawn=editor.project().drawingAt(0);
                check(drawn.strokes.size()==3,"crossed stroke split into three");
                var ids=stage.selectedStrokeIndexes();
                check(ids.size()==1,"only enclosed segment selected");
                Model.Stroke piece=drawn.strokes.get(ids.get(0));
                check(piece.points.get(0).x>=319 && piece.points.get(piece.points.size()-1).x<=481,
                    "selection retains only the lassoed part");
                invoke(editor,"deleteSelectedObjects");
                check(editor.project().drawingAt(0).strokes.size()==2,"only selected segment deleted");
                invoke(editor,"undo");
                check(editor.project().drawingAt(0).strokes.size()==3,"undo restores just deleted segment");
                invoke(editor,"undo");
                check(editor.project().drawingAt(0).strokes.size()==1,"second undo restores original continuous stroke");
            } catch(Throwable e){error.set(e);}finally{editor.dispose();}
        });
        if(error.get()!=null)throw new AssertionError("LassoPartialGuiTest failed",error.get());
        System.out.println("LassoPartialGuiTest passed");
    }
}
