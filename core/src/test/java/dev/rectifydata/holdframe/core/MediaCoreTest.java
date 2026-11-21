package dev.rectifydata.holdframe.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;

/** HFM4 audio/image persistence, deduplication, limits, and independent pose edits. */
public final class MediaCoreTest {
    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Model.Project p=new Model.Project();
        String imageId="0123456789abcdef".repeat(4);
        byte[] imageBytes=new byte[]{(byte)0x89,0x50,0x4e,0x47,1,2,3,4,5};
        p.imageAssets.put(imageId,imageBytes);
        Model.ImageItem item=new Model.ImageItem(imageId,100,150,320,180);
        p.drawingAt(0).images.add(item);
        Model.AudioClip sound=p.addAudio(Path.of("assets","sound.wav").toString(),30,24);
        sound.volume=.4f;
        sound.sourceOffsetMillis=500;
        sound.muted=true;
        check(p.totalFrames()==54,"audio end extends final hold without duplicating drawing");
        Model.ClipboardFrame copied=p.copyFrame(0);
        int index=p.pasteAfter(0,copied);
        check(p.imageAssets.size()==1,"copy/paste does not duplicate image data");
        p.ensureEditable(index).images.get(0).x=120;
        check(p.drawingAt(0).images.get(0).x==100,"images on copied poses edit independently");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        ProjectIO.write(p,bytes);
        Model.Project restored=ProjectIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(restored.imageAssets.size()==1 && Arrays.equals(restored.imageAssets.get(imageId),imageBytes),
            "image bytes round-trip");
        check(restored.drawingAt(index).images.get(0).x==120,"image transform persists");
        check(restored.audioClips.size()==1 && restored.audioClips.get(0).muted &&
            Math.abs(restored.audioClips.get(0).volume-.4f)<.001 &&
            restored.audioClips.get(0).sourceOffsetMillis==500,"audio timing and settings persist");
        boolean rejected=false;
        restored.drawingAt(index).images.add(new Model.ImageItem("missing",0,0,10,10));
        try { restored.validate(); }
        catch (IllegalArgumentException e) { rejected=true; }
        check(rejected,"missing image content rejected");
        System.out.println("HFM4 media model tests passed");
    }
}
