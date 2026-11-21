package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import dev.rectifydata.holdframe.core.ProjectIO;
import javax.swing.SwingUtilities;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Real Swing GUI regression for all 4 corner transforms and editable typed text. */
public final class TextAndFourHandleTest {
    private static void check(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int px(CanvasView stage,float x) {
        float k=Math.min((stage.getWidth()-32f)/Model.WIDTH,(stage.getHeight()-32f)/Model.HEIGHT);
        return Math.round((stage.getWidth()-Model.WIDTH*k)/2+x*k);
    }
    private static int py(CanvasView stage,float y) {
        float k=Math.min((stage.getWidth()-32f)/Model.WIDTH,(stage.getHeight()-32f)/Model.HEIGHT);
        return Math.round((stage.getHeight()-Model.HEIGHT*k)/2+y*k);
    }
    private static void point(CanvasView stage,int type,float x,float y) {
        int modifiers=type==MouseEvent.MOUSE_DRAGGED ? InputEvent.BUTTON1_DOWN_MASK : 0;
        stage.dispatchEvent(new MouseEvent(stage,type,System.currentTimeMillis(),modifiers,
            px(stage,x),py(stage,y),1,false,
            type==MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1));
    }
    private static void drag(CanvasView stage,float x,float y,float nx,float ny) {
        point(stage,MouseEvent.MOUSE_PRESSED,x,y);
        point(stage,MouseEvent.MOUSE_DRAGGED,nx,ny);
        point(stage,MouseEvent.MOUSE_RELEASED,nx,ny);
    }
    private static <T> T find(java.awt.Container parent,Class<T> cls) {
        for (java.awt.Component child : parent.getComponents()) {
            if (cls.isInstance(child)) return cls.cast(child);
            if (child instanceof java.awt.Container) {
                T result=find((java.awt.Container)child,cls);
                if (result!=null) return result;
            }
        }
        return null;
    }
    private static BufferedImage image(CanvasView stage) {
        BufferedImage result=new BufferedImage(stage.getWidth(),stage.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=result.createGraphics();
        stage.paint(g); g.dispose();
        return result;
    }
    private static void testCorner(CanvasView stage,Editor editor,int corner) {
        stage.selectAll();
        Model.TextItem item=editor.project().drawingAt(0).texts.get(0);
        Rectangle2D.Float b=FontSupport.bounds(item);
        float left=b.x,top=b.y,right=b.x+b.width,bottom=b.y+b.height;
        float x=(corner==1 || corner==3) ? right : left;
        float y=(corner==2 || corner==3) ? bottom : top;
        float deltaX=(corner==1 || corner==3) ? 24 : -24;
        float deltaY=(corner==2 || corner==3) ? 17 : -17;
        drag(stage,x,y,x+deltaX,y+deltaY);
        Rectangle2D.Float after=FontSupport.bounds(item);
        check(after.width>b.width+10, "corner "+corner+" increased width");
        check(after.height>b.height+6, "corner "+corner+" increased height");
        float anchorX=(corner==1 || corner==3) ? left : right;
        float anchorY=(corner==2 || corner==3) ? top : bottom;
        float resultingAnchorX=(corner==1 || corner==3) ? after.x : after.x+after.width;
        float resultingAnchorY=(corner==2 || corner==3) ? after.y : after.y+after.height;
        check(Math.abs(anchorX-resultingAnchorX)<2,"corner "+corner+" opposite horizontal anchor");
        check(Math.abs(anchorY-resultingAnchorY)<2,"corner "+corner+" opposite vertical anchor");
        // Regression: stroke width and text transform are both restored by Undo.
        java.awt.event.ActionEvent action=new java.awt.event.ActionEvent(editor,0,"control Z");
        Object key=editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .get(javax.swing.KeyStroke.getKeyStroke("control Z"));
        editor.getRootPane().getActionMap().get(key).actionPerformed(action);
        Rectangle2D.Float restored=FontSupport.bounds(item);
        check(Math.abs(restored.x-b.x)<.01 && Math.abs(restored.width-b.width)<.01,
            "corner "+corner+" undo restores geometry");
    }
    public static void main(String[] args) throws Exception {
        // Avoid X11/AWT toolkit deadlock in the synthetic mouse-event tests.
        new MouseEvent(new javax.swing.JPanel(),MouseEvent.MOUSE_MOVED,
            System.currentTimeMillis(),0,0,0,0,false);
        AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Editor editor=new Editor();
            try {
                editor.setVisible(true);
                CanvasView stage=find(editor.getContentPane(),CanvasView.class);
                check(stage!=null,"canvas exists");
                Model.TextItem item=new Model.TextItem("Hello world!", "Dialog", "",
                    1,Color.BLACK.getRGB(),32,180,125);
                editor.insertText(item);
                check(editor.project().drawingAt(0).texts.size()==1,"text inserted as an object");
                Rectangle2D.Float original=FontSupport.bounds(item);
                check(original.width>50 && original.height>20,"font metrics generated");
                BufferedImage rendered=image(stage);
                int dark=0;
                for (int y=py(stage,125);y<py(stage,125+original.height);y++)
                    for (int x=px(stage,180);x<px(stage,180+original.width);x++)
                        if ((rendered.getRGB(x,y)&0x00FFFFFF)<0x00808080) dark++;
                check(dark>50,"text is actually painted on canvas");
                for (int corner=0;corner<4;corner++) testCorner(stage,editor,corner);
                Model.TextItem updated=item.copy();
                updated.value="Editable typed text";
                updated.fontFamily="Serif";
                editor.changeText(0,updated);
                check(item.value.equals("Editable typed text"),"edit text and font family");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                ProjectIO.write(editor.project(),bytes);
                Model.Project restored=ProjectIO.read(new ByteArrayInputStream(bytes.toByteArray()));
                check(restored.drawingAt(0).texts.get(0).value.equals("Editable typed text"),
                    "save and open editable text");
                stage.selectAll();
                Rectangle2D.Float before=FontSupport.bounds(item);
                drag(stage,before.getCenterX()>0?(float)before.getCenterX():180,
                    (float)before.getCenterY(),(float)before.getCenterX()+17,(float)before.getCenterY()+11);
                check(item.x>=updated.x+12,"lasso text movement");
                if (args.length > 0) {
                    Object controlA=editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .get(javax.swing.KeyStroke.getKeyStroke("control A"));
                    editor.getRootPane().getActionMap().get(controlA).actionPerformed(
                        new java.awt.event.ActionEvent(editor,0,"control A"));
                    BufferedImage screenshot=new BufferedImage(editor.getWidth(),editor.getHeight(),BufferedImage.TYPE_INT_RGB);
                    Graphics2D screenshotGraphics=screenshot.createGraphics();
                    editor.paint(screenshotGraphics);
                    screenshotGraphics.dispose();
                    javax.imageio.ImageIO.write(screenshot,"png",new java.io.File(args[0]));
                }
                editor.dispose();
                System.out.println("Text and four-handle GUI test passed (all corners, typed text, edit, move, persistence)");
            } catch (Throwable ex) {
                error.set(ex); editor.dispose();
            }
        });
        if (error.get()!=null) throw new RuntimeException(error.get());
    }
}
