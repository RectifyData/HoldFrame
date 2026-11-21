package dev.rectifydata.holdframe.windows;

import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicReference;

/** Reproduces edge-of-viewport resize bugs without requiring a physical mouse. */
public final class TimelineAutoScrollTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static void mouse(TimelineView timeline, int type, int x) {
        int modifiers=type==MouseEvent.MOUSE_DRAGGED ? InputEvent.BUTTON1_DOWN_MASK : 0;
        timeline.dispatchEvent(new MouseEvent(timeline,type,System.currentTimeMillis(),modifiers,x,54,
                1,false,type==MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1));
    }
    @SuppressWarnings("unchecked")
    private static <T> T find(Container root, Class<T> kind) {
        for (Component child : root.getComponents()) {
            if (kind.isInstance(child)) return (T)child;
            if (child instanceof Container) {
                T descendant=find((Container)child,kind);
                if (descendant!=null) return descendant;
            }
        }
        return null;
    }
    private static JToggleButton findLasso(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JToggleButton && "Lasso / Move".equals(((JToggleButton)child).getText()))
                return (JToggleButton)child;
            if (child instanceof Container) {
                JToggleButton found=findLasso((Container)child);
                if (found!=null) return found;
            }
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        // Initialize AWT events before starting XToolkit, avoiding a Java/X11 lock inversion.
        new MouseEvent(new javax.swing.JPanel(),MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),0,0,0,0,false);
        AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Editor editor=new Editor();
            try {
                editor.setSize(820,650);
                editor.setVisible(true);
                TimelineView timeline=find(editor.getContentPane(),TimelineView.class);
                check(timeline!=null,"timeline exists");
                check(timeline.getParent() instanceof JViewport,"embedded in viewport");
                JViewport viewport=(JViewport)timeline.getParent();
                int width=viewport.getWidth();
                check(width>300,"timeline viewport has layout width");
                int old=editor.project().timeline.get(0).duration;
                // Press the last exposure's handle and drag to the far right.
                mouse(timeline,MouseEvent.MOUSE_PRESSED,16+old*7);
                mouse(timeline,MouseEvent.MOUSE_DRAGGED,viewport.getViewPosition().x+width-5);
                int atEdge=editor.project().timeline.get(0).duration;
                check(atEdge>old,"drag extended pose toward right edge");
                int before=viewport.getViewPosition().x;
                for (int i=0;i<16;i++) timeline.autoScrollStep();
                int after=viewport.getViewPosition().x;
                check(after>before,"viewport automatically scrolls right without additional mouse movement");
                check(editor.project().timeline.get(0).duration>atEdge,
                      "exposure continues expanding while right edge autoscrolls");
                // Move pointer to the LEFT edge while keeping the drag active.
                mouse(timeline,MouseEvent.MOUSE_DRAGGED,viewport.getViewPosition().x+4);
                int beforeLeft=viewport.getViewPosition().x;
                int lengthBeforeLeft=editor.project().timeline.get(0).duration;
                for (int i=0;i<10;i++) timeline.autoScrollStep();
                check(viewport.getViewPosition().x<beforeLeft,"viewport automatically scrolls left");
                check(editor.project().timeline.get(0).duration<lengthBeforeLeft,
                      "left edge autoscroll keeps shrinking the exposure");
                mouse(timeline,MouseEvent.MOUSE_RELEASED,viewport.getViewPosition().x+4);
                check(!timeline.autoScrollStep(),"autoscroll stops on mouse release");

                // Very long projects should still reveal the playhead at the viewport edge.
                editor.resizeClip(0,500);
                viewport.setViewPosition(new java.awt.Point(0,0));
                editor.seek(250);
                Rectangle shown=viewport.getViewRect();
                check(shown.x>0 && 16+250*7>=shown.x && 16+250*7<=shown.x+shown.width,
                      "playhead-follow reveals a cursor beyond viewport");
                editor.seek(0);
                check(viewport.getViewPosition().x==0,"navigation scrolls back left");
                BufferedImage pixels=new BufferedImage(timeline.getWidth(),timeline.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D paint=pixels.createGraphics();
                timeline.paint(paint);
                paint.dispose();
                int sample=pixels.getRGB(Math.min(width-30,timeline.getWidth()-5),100)&0xFFFFFF;
                check(sample==(timeline.getBackground().getRGB()&0xFFFFFF),
                      "timeline background explicitly painted (no light-grey GUI glitch)");
                // Ctrl+A must select both lasso behavior and its toolbar button.
                editor.getRootPane().getActionMap().get(
                    editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                          .get(javax.swing.KeyStroke.getKeyStroke("control A")))
                          .actionPerformed(new java.awt.event.ActionEvent(editor,0,"ctrlA"));
                JToggleButton lasso=findLasso(editor.getContentPane());
                check(lasso!=null && lasso.isSelected(),"toolbar matches Ctrl+A lasso selection");
                System.out.println("Timeline edge-scroll regression passed (right/left, idle pointer, playback, toolbar)");
            } catch (Throwable thrown) { error.set(thrown); }
            finally { editor.dispose(); }
        });
        if (error.get()!=null) throw new RuntimeException(error.get());
    }
}
