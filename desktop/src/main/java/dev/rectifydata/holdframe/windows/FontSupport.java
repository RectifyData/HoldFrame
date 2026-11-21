package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Uses installed fonts, or a font file that the user selected locally.
 * Font binaries are neither embedded in projects nor distributed with the app. */
final class FontSupport {
    private FontSupport() {}
    private static final Map<String,Font> loaded = new HashMap<>();
    private static final FontRenderContext MEASURE = new FontRenderContext(null,true,true);

    static synchronized Font loadFile(String name) throws Exception {
        Font directlyCached=loaded.get(name);
        if (directlyCached!=null) return directlyCached;
        File file=Path.of(name).toFile();
        String lower=file.getName().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")
                || lower.endsWith(".otc") || lower.endsWith(".pfb") || lower.endsWith(".pfa")))
            throw new IOException("Select a supported TrueType, OpenType or Type 1 font file");
        if (!file.isFile() || Files.size(file.toPath())>25_000_000)
            throw new IOException("Font file is missing or larger than 25 MB");
        String canonical=file.getCanonicalPath();
        Font hit=loaded.get(canonical);
        if (hit!=null) { loaded.put(name,hit); return hit; }
        int type=(lower.endsWith(".pfb") || lower.endsWith(".pfa"))
            ? Font.TYPE1_FONT : Font.TRUETYPE_FONT;
        Font font=Font.createFont(type,file);
        loaded.put(canonical,font);
        loaded.put(name,font);
        // Register so fonts with complex families resolve in the Swing picker.
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        return font;
    }
    static Font resolve(Model.TextItem item) {
        Font base=null;
        if (item.fontPath!=null && !item.fontPath.isBlank()) {
            try { base=loadFile(item.fontPath); }
            catch (Exception ignored) { /* use saved family or system fallback */ }
        }
        if (base==null) base=new Font(item.fontFamily,Font.PLAIN,12);
        return base.deriveFont(item.style,item.size);
    }
    static Rectangle2D.Float bounds(Model.TextItem item) {
        Font font=resolve(item);
        String[] lines=item.value.split("\\n",-1);
        float width=0;
        for (String line : lines)
            width=Math.max(width,(float)font.getStringBounds(line,MEASURE).getWidth());
        float lineHeight=font.getLineMetrics("Ag",MEASURE).getHeight();
        return new Rectangle2D.Float(item.x,item.y,
            Math.max(1f,width)*item.scaleX,Math.max(1f,lineHeight*lines.length)*item.scaleY);
    }
    static void draw(Graphics2D graphics,Model.TextItem item) {
        if (item.value.isEmpty()) return;
        Font font=resolve(item);
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            g.translate(item.x,item.y);
            g.scale(item.scaleX,item.scaleY);
            g.setFont(font);
            g.setColor(new java.awt.Color(item.color,true));
            java.awt.FontMetrics fm=g.getFontMetrics(font);
            String[] lines=item.value.split("\\n",-1);
            int y=fm.getAscent();
            for (String line : lines) {
                g.drawString(line,0,y);
                y+=fm.getHeight();
            }
        } finally { g.dispose(); }
    }
}
