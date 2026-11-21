package dev.rectifydata.holdframe.windows;

/** Small launcher that selects headless mode before Swing/AWT classes are initialized. */
public final class HoldFrame {
    private HoldFrame() {}
    public static void main(String[] args) {
        if (args.length>0) {
            System.setProperty("java.awt.headless","true");
            System.exit(ExportCli.run(args));
        }
        Editor.main(args);
    }
}
