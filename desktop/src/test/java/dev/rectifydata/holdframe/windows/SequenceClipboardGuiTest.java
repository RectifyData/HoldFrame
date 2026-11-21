package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real Shift-range timeline gestures and Ctrl+C/V keyboard handlers. */
public final class SequenceClipboardGuiTest {
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    private static <T> T find(Container parent, Class<T> type) {
        for (Component child:parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T found=find((Container)child,type);
                if (found!=null) return found;
            }
        }
        return null;
    }
    private static void key(Editor editor,String keystroke) {
        javax.swing.KeyStroke k=javax.swing.KeyStroke.getKeyStroke(keystroke);
        Object name=editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(k);
        check(name!=null,"keyboard shortcut registered: "+keystroke);
        javax.swing.Action action=editor.getRootPane().getActionMap().get(name);
        check(action!=null,"keyboard action registered: "+keystroke);
        action.actionPerformed(new java.awt.event.ActionEvent(editor,0,keystroke));
    }
    private static void click(TimelineView track,int x,boolean shift) {
        int mods=shift?InputEvent.SHIFT_DOWN_MASK:0;
        long time=System.currentTimeMillis();
        track.dispatchEvent(new MouseEvent(track,MouseEvent.MOUSE_PRESSED,time,mods,x,56,1,false,MouseEvent.BUTTON1));
        track.dispatchEvent(new MouseEvent(track,MouseEvent.MOUSE_RELEASED,time+1,mods,x,56,1,false,MouseEvent.BUTTON1));
    }
    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> fail=new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Editor editor=new Editor();
            try {
                editor.setVisible(true);
                editor.project().addAfter(0,false);
                editor.project().addAfter(1,false);
                editor.project().addAfter(2,false);
                editor.refresh();
                TimelineView track=find(editor.getContentPane(),TimelineView.class);
                check(track!=null,"timeline exists");
                // Default 24-frame clips use 7 pixels/frame; click well inside each.
                click(track,16+24*7+60,false); // second clip
                check(editor.selected()==1 && editor.selectionCount()==1,"single click selects clip 2");
                click(track,16+72*7+60,true); // shift click clip 4
                check(editor.selectionStart()==1 && editor.selectionEnd()==3,"shift selects 2..4");
                key(editor,"control C");
                key(editor,"control V");
                check(editor.project().timeline.size()==7,"three clips pasted as group");
                check(editor.selectionStart()==4 && editor.selectionEnd()==6,"pasted group highlighted");
                check(editor.frame()==editor.project().clipStart(editor.selected()),
                      "playhead and canvas show the same pasted pose");
                key(editor,"control V");
                check(editor.project().timeline.size()==10,"can paste group repeatedly");
                check(editor.project().drawings.size()==7,"repeated copies reuse drawing objects");
                key(editor,"control shift A");
                check(editor.selectionCount()==10,"select all timeline shortcut");
                editor.dispose();
            } catch (Throwable t) { fail.set(t); editor.dispose(); }
        });
        if (fail.get()!=null) throw new RuntimeException(fail.get());
        System.out.println("SequenceClipboardGuiTest passed");
    }
}
