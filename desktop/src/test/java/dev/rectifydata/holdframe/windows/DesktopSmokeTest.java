package dev.rectifydata.holdframe.windows;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;

/** Smoke-tests the actual UI under a desktop/Xvfb (not run by headless CI). */
public final class DesktopSmokeTest {
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new AssertionError("Display required");
        final Editor[] ref = new Editor[1];
        SwingUtilities.invokeAndWait(() -> {
            ref[0] = new Editor();
            ref[0].setVisible(true);
            if (ref[0].project().totalFrames() != 24) throw new AssertionError("Initialization failed");
        });
        Robot robot = new Robot();
        robot.delay(700);
        BufferedImage image = robot.createScreenCapture(new Rectangle(0,0,1280,800));
        ImageIO.write(image,"png",new File(args.length>0 ? args[0] : "HoldFrame-smoke.png"));
        SwingUtilities.invokeAndWait(ref[0]::dispose);
        System.out.println("Desktop GUI smoke test passed");
    }
}
