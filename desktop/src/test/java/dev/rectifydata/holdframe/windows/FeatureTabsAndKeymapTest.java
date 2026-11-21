package dev.rectifydata.holdframe.windows;

import javax.swing.JTabbedPane;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.prefs.Preferences;

/** Regression: visible feature tabs, keyboard navigation, stored remaps and reset. */
public final class FeatureTabsAndKeymapTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @SuppressWarnings("unchecked")
    private static <T> T find(Component c,Class<T> type) {
        if (type.isInstance(c)) return (T)c;
        if (c instanceof Container) for(Component v:((Container)c).getComponents()) {
            T nested=find(v,type);if(nested!=null) return nested;
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        new javax.swing.JPanel();
        SwingUtilities.invokeAndWait(()->{
            Editor editor=new Editor();
            Preferences prefs=Preferences.userNodeForPackage(Editor.class).node("keymaps");
            String before=prefs.get("Project tab",null);
            try {
                editor.setSize(900,650);editor.setVisible(true);
                JTabbedPane tabs=find(editor.getContentPane(),JTabbedPane.class);
                check(tabs!=null && tabs.getTabCount()==4,"must have 4 feature tabs");
                check(tabs.getTitleAt(0).equals("Project") && tabs.getTitleAt(1).equals("Draw") &&
                    tabs.getTitleAt(2).equals("Animate") && tabs.getTitleAt(3).equals("Media"),"tabs named for features");
                check(tabs.getSelectedIndex()==1,"drawing tools default to Draw tab");
                trigger(editor,"control 3");
                check(tabs.getSelectedIndex()==2,"Ctrl+3 opens Animate tab");
                trigger(editor,"control 4");
                check(tabs.getSelectedIndex()==3,"Ctrl+4 opens Media tab");
                prefs.put("Project tab","alt F7");
                Method bind=Editor.class.getDeclaredMethod("installShortcuts");bind.setAccessible(true);
                bind.invoke(editor);
                trigger(editor,"alt F7");
                check(tabs.getSelectedIndex()==0,"custom saved hotkey opens Project tab");
                check(editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(KeyStroke.getKeyStroke("control 1"))==null,"old shortcut unregistered");
                System.out.println("Tabbed editor and persisted remappable keymaps passed");
            } catch (Exception exception) {throw new RuntimeException(exception);}
            finally {
                if (before==null) prefs.remove("Project tab"); else prefs.put("Project tab",before);
                editor.dispose();
            }
        });
    }
    private static void trigger(Editor editor,String spec) {
        JComponent root=editor.getRootPane();
        Object key=root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(spec));
        check(key!=null,"hotkey registered: "+spec);
        root.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(root,0,"test"));
    }
}
