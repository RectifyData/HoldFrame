package dev.rectifydata.holdframe.windows;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.FileDialog;
import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/** Optional Xvfb smoke: checks the native file dialog comes to the foreground. */
public final class NativeDialogSmokeTest {
    private static JButton openButton(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JButton && "Open".equals(((JButton)child).getText())) return (JButton)child;
            if (child instanceof Container) {
                JButton b=openButton((Container)child); if (b!=null) return b;
            }
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        new java.awt.event.MouseEvent(new javax.swing.JPanel(),java.awt.event.MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),0,0,0,0,false);
        AtomicReference<Editor> ref=new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Editor editor=new Editor(); editor.setSize(1100,750); editor.setVisible(true);
            ref.set(editor);
        });
        Robot robot=new Robot();
        Thread closer=new Thread(() -> {
            try {
                Thread.sleep(1200);
                FileDialog active=null;
                for (Window window : Window.getWindows())
                    if (window instanceof FileDialog && window.isVisible()) active=(FileDialog)window;
                if (active==null) throw new AssertionError("Native modal picker not visible");
                if (args.length>0) {
                    BufferedImage screenshot=robot.createScreenCapture(new Rectangle(0,0,1280,800));
                    ImageIO.write(screenshot,"png",new File(args[0]));
                }
                final FileDialog close=active;
                SwingUtilities.invokeLater(close::dispose);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        closer.setDaemon(true);
        closer.start();
        SwingUtilities.invokeAndWait(() -> {
            JButton button=openButton(ref.get().getContentPane());
            if (button==null) throw new AssertionError("Open button missing");
            button.doClick();
        });
        closer.join(3000);
        if (closer.isAlive()) throw new AssertionError("Native picker did not dismiss");
        SwingUtilities.invokeAndWait(()->ref.get().dispose());
        System.out.println("Native file dialog smoke test passed");
    }
}
