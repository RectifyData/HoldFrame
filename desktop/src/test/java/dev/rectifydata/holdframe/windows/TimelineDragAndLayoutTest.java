package dev.rectifydata.holdframe.windows;

import javax.swing.JToolBar;
import javax.swing.JViewport;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.InputEvent;
import java.util.concurrent.atomic.AtomicReference;

/** EDT + real Swing timer regression: the edge pans even after the pointer stops. */
public final class TimelineDragAndLayoutTest {
    private static final AtomicReference<Editor> app=new AtomicReference<>();
    private static final AtomicReference<TimelineView> track=new AtomicReference<>();
    private static final AtomicReference<JViewport> view=new AtomicReference<>();
    private static void check(boolean test,String message) { if (!test) throw new AssertionError(message); }
    @SuppressWarnings("unchecked")
    private static <T> T find(Component parent, Class<T> type) {
        if (type.isInstance(parent)) return (T)parent;
        if (parent instanceof Container) for (Component child:((Container)parent).getComponents()) {
            T candidate=find(child,type); if (candidate!=null) return candidate;
        }
        return null;
    }
    private static void input(int id,int x) {
        TimelineView t=track.get();
        t.dispatchEvent(new MouseEvent(t,id,System.currentTimeMillis(),
                id==MouseEvent.MOUSE_DRAGGED?InputEvent.BUTTON1_DOWN_MASK:0,
                x,54,1,false,id==MouseEvent.MOUSE_DRAGGED?MouseEvent.NOBUTTON:MouseEvent.BUTTON1));
    }
    private static void ui(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    public static void main(String[] args) throws Exception {
        // Force AWT's toolkit into existence before starting X11 threads.
        new MouseEvent(new javax.swing.JPanel(),MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,0,0,0,false);
        try {
            ui(() -> {
                Editor e=new Editor();
                e.setSize(900,640); e.setVisible(true); app.set(e);
                TimelineView t=find(e.getContentPane(),TimelineView.class);
                JToolBar toolbar=find(e.getContentPane(),JToolBar.class);
                CanvasView stage=find(e.getContentPane(),CanvasView.class);
                check(t!=null && toolbar!=null && stage!=null,"editor controls exist");
                JTabbedPane tabs=find(e.getContentPane(),JTabbedPane.class);
                check(tabs!=null && tabs.getTabCount()==4,"four grouped feature tabs exist");
                check(SwingUtilities.isDescendingFrom(toolbar,tabs),
                    "drawing toolbar belongs to its tab rather than overlaying canvas");
                check(!SwingUtilities.isDescendingFrom(stage,toolbar),
                    "drawing stage does not live inside floating controls");
                track.set(t); view.set((JViewport)t.getParent());
                int initial=e.project().timeline.get(0).duration;
                input(MouseEvent.MOUSE_PRESSED,16+initial*7);
                input(MouseEvent.MOUSE_DRAGGED,view.get().getViewPosition().x+view.get().getWidth()-3);
            });
            final int[] rightStart=new int[1],rightFrame=new int[1];
            ui(()->{rightStart[0]=view.get().getViewPosition().x;rightFrame[0]=app.get().project().timeline.get(0).duration;});
            Thread.sleep(350); // Timer must advance without mouse movements (no manual scrollbar input)
            ui(()->{
                check(view.get().getViewPosition().x>rightStart[0],"timer pans right without moving pointer");
                check(app.get().project().timeline.get(0).duration>rightFrame[0],"drag grows final pose during pan");
                input(MouseEvent.MOUSE_DRAGGED,view.get().getViewPosition().x+3);
            });
            final int[] leftStart=new int[1];
            ui(()->leftStart[0]=view.get().getViewPosition().x);
            Thread.sleep(250);
            ui(()->{
                check(view.get().getViewPosition().x<leftStart[0],"timer pans left without moving pointer");
                input(MouseEvent.MOUSE_RELEASED,view.get().getViewPosition().x+3);
            });
            ui(()->{
                app.get().resizeClip(0,800);
                view.get().setViewPosition(new java.awt.Point(0,0));
                // Mousewheel must scroll horizontally for a long project.
                TimelineView t=track.get();
                t.dispatchEvent(new MouseWheelEvent(t,MouseEvent.MOUSE_WHEEL,
                     System.currentTimeMillis(),0,100,55,0,false,
                     MouseWheelEvent.WHEEL_UNIT_SCROLL,3,4));
                check(view.get().getViewPosition().x>0,"wheel pans the timeline");
            });
            System.out.println("Real timer right/left drag + tabbed GUI + mouse-wheel regression passed");
        } finally {ui(()->{if(app.get()!=null)app.get().dispose();});}
    }
}
