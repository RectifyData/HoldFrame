package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Portable raster import: protect memory, embed bytes exactly once, lazily decode on render. */
final class ImageSupport {
    static final class Imported {
        final String digest;
        final byte[] bytes;
        final int width, height;
        Imported(String digest,byte[] bytes,int width,int height) {
            this.digest=digest; this.bytes=bytes; this.width=width; this.height=height;
        }
    }
    private static final int MAX_BYTES=16_000_000;
    private static final int MAX_PIXELS=8_000_000;
    // Only a handful of decoded images are cached; the compressed originals live in the project.
    private static final Map<String, BufferedImage> CACHE=new LinkedHashMap<>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,BufferedImage> eldest) {
            return size()>3;
        }
    };
    private ImageSupport() {}
    static Imported importFile(Path path) throws IOException {
        long size=Files.size(path);
        if (size<8 || size>MAX_BYTES) throw new IOException("Image must be smaller than 16 MB");
        byte[] bytes=Files.readAllBytes(path);
        int[] dimensions=dimensions(bytes);
        String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 unavailable",ex); }
        return new Imported(digest,bytes,dimensions[0],dimensions[1]);
    }
    static int[] dimensions(byte[] bytes) throws IOException {
        try (ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input==null) throw new IOException("Invalid image");
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image format: use PNG, JPEG, GIF or BMP");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,false,true);
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if (w<1 || h<1 || w>12000 || h>12000 || (long)w*h>MAX_PIXELS)
                    throw new IOException("Image is too large to render safely (8 million pixels max)");
                return new int[]{w,h};
            } finally { reader.dispose(); }
        }
    }
    static BufferedImage get(Model.Project project,String id) {
        synchronized(CACHE) {
            BufferedImage cached=CACHE.get(id);
            if (cached!=null) return cached;
        }
        byte[] bytes=project.imageAssets.get(id);
        if (bytes==null) return null;
        try {
            dimensions(bytes); // check header before ImageIO inflates pixels
            BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
            if (image!=null) {
                synchronized(CACHE) { CACHE.put(id,image); }
            }
            return image;
        } catch (IOException | RuntimeException e) { return null; }
    }
}
