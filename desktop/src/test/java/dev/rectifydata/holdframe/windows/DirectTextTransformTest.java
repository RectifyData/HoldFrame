package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import dev.rectifydata.holdframe.core.ProjectIO;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.awt.event.InputEvent;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicReference;

/** TEXT mode can move/resize typed elements directly without switching to Lasso. */
public final class DirectTextTransformTest {
  private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  private static CanvasView find(Container c){
    for(java.awt.Component component:c.getComponents()) {
      if(component instanceof CanvasView)return (CanvasView)component;
      if(component instanceof Container){CanvasView found=find((Container)component);if(found!=null)return found;}
    }
    return null;
  }
  private static int pixel(CanvasView stage,float v,boolean horizontal){
    float scale=Math.min((stage.getWidth()-32f)/Model.WIDTH,(stage.getHeight()-32f)/Model.HEIGHT);
    return Math.round((horizontal?(stage.getWidth()-Model.WIDTH*scale)/2f:(stage.getHeight()-Model.HEIGHT*scale)/2f)+v*scale);
  }
  private static void event(CanvasView stage,int type,float x,float y){
    int flags=type==MouseEvent.MOUSE_DRAGGED?InputEvent.BUTTON1_DOWN_MASK:0;
    stage.dispatchEvent(new MouseEvent(stage,type,System.currentTimeMillis(),flags,pixel(stage,x,true),pixel(stage,y,false),1,false,
      type==MouseEvent.MOUSE_DRAGGED?MouseEvent.NOBUTTON:MouseEvent.BUTTON1));
  }
  private static void drag(CanvasView stage,float x,float y,float targetX,float targetY){
    event(stage,MouseEvent.MOUSE_PRESSED,x,y);
    event(stage,MouseEvent.MOUSE_DRAGGED,targetX,targetY);
    event(stage,MouseEvent.MOUSE_RELEASED,targetX,targetY);
  }
  public static void main(String[] ignored) throws Exception {
    AtomicReference<Throwable> failure=new AtomicReference<>();
    SwingUtilities.invokeAndWait(()->{
      Editor editor=new Editor();
      try {
        editor.setVisible(true);
        CanvasView canvas=find(editor.getContentPane());
        check(canvas!=null,"stage");
        Model.TextItem text=new Model.TextItem("Transform me","Dialog","",0,Color.BLACK.getRGB(),34f,160f,140f);
        editor.insertText(text);
        check(canvas.getTool()==CanvasView.Tool.TEXT,"new text switches to TEXT tool");
        Rectangle2D.Float b=FontSupport.bounds(text);
        float oldX=text.x,oldY=text.y;
        drag(canvas,b.x+b.width/2f,b.y+b.height/2f,b.x+b.width/2f+30f,b.y+b.height/2f+17f);
        check(text.x>oldX+20f && text.y>oldY+10f,"text moved directly without Lasso");
        Rectangle2D.Float afterMove=FontSupport.bounds(text);
        float previousWidth=afterMove.width,previousHeight=afterMove.height;
        drag(canvas,afterMove.x+afterMove.width,afterMove.y+afterMove.height,
            afterMove.x+afterMove.width+42,afterMove.y+afterMove.height+24);
        Rectangle2D.Float afterScale=FontSupport.bounds(text);
        check(afterScale.width>previousWidth+15f && afterScale.height>previousHeight+10f,"corner handles scale text");
        check(text.scaleX>1f && text.scaleY>1f,"text scale fields updated");
        byte[] data;
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
          ProjectIO.write(editor.project(),out);data=out.toByteArray();
        }
        Model.Project restored=ProjectIO.read(new ByteArrayInputStream(data));
        Model.TextItem reread=restored.drawingAt(0).texts.get(0);
        check(Math.abs(reread.x-text.x)<.01 && Math.abs(reread.scaleX-text.scaleX)<.01,"transformed text persisted");
      } catch(Throwable t){failure.set(t);} finally{editor.dispose();}
    });
    if(failure.get()!=null)throw new AssertionError("DirectTextTransformTest failed",failure.get());
    System.out.println("DirectTextTransformTest passed");
  }
}
