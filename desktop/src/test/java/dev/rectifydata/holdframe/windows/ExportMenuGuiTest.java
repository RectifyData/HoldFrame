package dev.rectifydata.holdframe.windows;

import javax.swing.*;
import java.awt.*;

/** The exporter must be reachable without relying on the command line. */
public final class ExportMenuGuiTest {
    private static void check(boolean ok,String description) {
        if (!ok) throw new AssertionError(description);
    }
    static boolean containsButton(Container panel,String label) {
        for (Component c:panel.getComponents()) {
            if (c instanceof JButton && ((JButton)c).getText().contains(label)) return true;
            if (c instanceof Container && containsButton((Container)c,label)) return true;
        }
        return false;
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Editor frame=new Editor();
            try {
                JMenuBar menus=frame.getJMenuBar();
                check(menus.getMenuCount()>=2,"File and Edit menus");
                JMenu menu=menus.getMenu(0);
                boolean found=false;
                for (int i=0;i<menu.getItemCount();i++) {
                    JMenuItem item=menu.getItem(i);
                    if (item!=null && item.getText().contains("Export animation")) found=true;
                }
                check(found,"File menu exports animation");
                check(containsButton(frame.getContentPane(),"Export"),"Export toolbar button");
                check(frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(KeyStroke.getKeyStroke("control E"))!=null,"Ctrl+E shortcut");
                frame.setVisible(true);
                frame.validate();
                check(frame.getContentPane().getWidth()>=780,"Window minimum width");
                System.out.println("Export menu/toolbar/shortcut GUI test passed");
            } finally { frame.dispose(); }
        });
    }
}
