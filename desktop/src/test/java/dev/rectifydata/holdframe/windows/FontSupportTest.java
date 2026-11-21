package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import java.awt.Font;
import java.awt.GraphicsEnvironment;

/** Exercises installed-family font resolution and optional local font-file import. */
public final class FontSupportTest {
    private static void check(boolean ok,String label) {
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        String[] families=GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        check(families.length>0,"system fonts exposed");
        Model.TextItem item=new Model.TextItem("Sample\n123",families[0],"",Font.BOLD,
            0xFF010203,48,100,120);
        check(FontSupport.resolve(item).getSize2D()==48,"system font size");
        check(FontSupport.bounds(item).getWidth()>10,"system font bounds");
        check(FontSupport.bounds(item).getHeight()>40,"multiline font bounds");
        item.fontPath="/nonexistent/holdframe/fallback-font.ttf";
        check(FontSupport.bounds(item).getWidth()>10,"missing font fallback");
        if (args.length>0) {
            Font custom=FontSupport.loadFile(args[0]);
            item.fontFamily=custom.getFamily();
            item.fontPath=args[0];
            check(FontSupport.resolve(item).getSize2D()==48,"imported font size");
            check(FontSupport.bounds(item).getWidth()>10,"imported font text bounds");
            check(FontSupport.loadFile(args[0])==custom,"font path cache");
        }
        System.out.println("Font support test passed (installed families, multiline, missing-font fallback"
            +(args.length>0?", imported local font":"")+")");
    }
}
