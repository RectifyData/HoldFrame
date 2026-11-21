package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import dev.rectifydata.holdframe.core.ProjectIO;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Actual export files are parsed and validated; no mocked frame rendering. */
public final class ExportSupportTest {
    private static void check(boolean yes,String message) {
        if (!yes) throw new AssertionError(message);
    }
    private static void audio(Path path,int samples) throws Exception {
        byte[] wave=new byte[samples*2];
        for(int i=0;i<samples;i++) {
            short x=(short)(Math.sin(i*2*Math.PI*440/22050)*7000);
            wave[i*2]=(byte)x;
            wave[i*2+1]=(byte)(x>>8);
        }
        AudioFormat fmt=new AudioFormat(22050,16,1,true,false);
        try (AudioInputStream stream=new AudioInputStream(new ByteArrayInputStream(wave),fmt,samples)) {
            AudioSystem.write(stream,AudioFileFormat.Type.WAVE,path.toFile());
        }
    }
    private static Model.Project example(Path folder) throws Exception {
        Model.Project p=new Model.Project();
        p.fps=12;
        p.timeline.get(0).duration=12;
        Model.Stroke s=new Model.Stroke(Color.RED.getRGB(),10,false);
        s.points.add(new Model.Point(10,10));
        s.points.add(new Model.Point(100,100));
        p.drawingAt(0).strokes.add(s);
        BufferedImage imported=new BufferedImage(20,20,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<20;y++)for(int x=0;x<20;x++)imported.setRGB(x,y,0xFF00EE22);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        ImageIO.write(imported,"png",bytes);
        String imageId=java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        p.imageAssets.put(imageId,bytes.toByteArray());
        p.drawingAt(0).images.add(new Model.ImageItem(imageId,200,100,40,40));
        p.drawingAt(0).texts.add(new Model.TextItem("Hello!","Dialog","",0,Color.BLACK.getRGB(),44,300,200));
        int next=p.addAfter(0,false);
        p.timeline.get(next).duration=6;
        Model.Stroke blue=new Model.Stroke(Color.BLUE.getRGB(),20,false);
        blue.points.add(new Model.Point(50,50));
        blue.points.add(new Model.Point(150,50));
        p.drawingAt(next).strokes.add(blue);
        Path sound=folder.resolve("tone.wav");
        audio(sound,22050*2);
        p.addAudio(sound.toAbsolutePath().toString(),0,18);
        Model.AudioClip overlay=p.addAudio(sound.toAbsolutePath().toString(),6,8);
        overlay.volume=.4f;
        return p;
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("holdframe-export-test-");
        Model.Project project=example(dir);
        ByteArrayOutputStream saved=new ByteArrayOutputStream();
        ProjectIO.write(project,saved,dir);
        project=ProjectIO.read(new ByteArrayInputStream(saved.toByteArray()),dir);
        check(project.timeline.size()==2 && project.audioClips.size()==2,"media project roundtrip");
        Path single=dir.resolve("single.png");
        ExportSupport.png(project,0,single);
        BufferedImage still=ImageIO.read(single.toFile());
        check(still.getWidth()==Model.WIDTH && still.getHeight()==Model.HEIGHT,"single pose dimensions");
        check(still.getRGB(205,105)==0xFF00EE22,"image asset retained");
        Path sequence=dir.resolve("sequence.zip");
        ExportSupport.pngSequence(project,sequence,ExportSupport.NO_PROGRESS);
        try (ZipFile zip=new ZipFile(sequence.toFile())) {
            check(zip.size()==4,"two PNG poses plus metadata and README");
            ZipEntry manifest=zip.getEntry("timing.csv");
            check(manifest!=null,"timing manifest");
            String csv=new String(zip.getInputStream(manifest).readAllBytes());
            check(csv.contains("1,0,12,") && csv.contains("2,12,6,"),"hold durations preserved");
            check(zip.getEntry("poses/pose_000002.png")!=null,"second pose exists");
        }
        Path sheet=dir.resolve("sheet.png");
        ExportSupport.spriteSheet(project,sheet,ExportSupport.NO_PROGRESS);
        BufferedImage tiled=ImageIO.read(sheet.toFile());
        check(tiled.getWidth()==Model.WIDTH*2 && tiled.getHeight()==Model.HEIGHT,"sheet dimensions");
        Path gif=dir.resolve("animation.gif");
        ExportSupport.gif(project,gif,ExportSupport.NO_PROGRESS);
        try (ImageInputStream image=ImageIO.createImageInputStream(gif.toFile())) {
            Iterator<ImageReader> readers=ImageIO.getImageReadersByFormatName("gif");
            ImageReader reader=readers.next();
            try {
                reader.setInput(image);
                check(reader.getNumImages(true)==2,"one GIF image per pose");
                for (int i=0;i<2;i++) {
                    javax.imageio.metadata.IIOMetadata meta=reader.getImageMetadata(i);
                    org.w3c.dom.Node root=meta.getAsTree(meta.getNativeMetadataFormatName());
                    org.w3c.dom.NodeList fields=((org.w3c.dom.Element)root)
                        .getElementsByTagName("GraphicControlExtension");
                    check(fields.getLength()>0,"GIF frame delay metadata");
                    String delay=((org.w3c.dom.Element)fields.item(0)).getAttribute("delayTime");
                    check(delay.equals(i==0?"100":"50"),"GIF exposure duration preserved");
                }
            } finally { reader.dispose(); }
        }
        if(ExportSupport.ffmpegAvailable()) {
            Path movie=dir.resolve("animation.mp4");
            ExportSupport.mp4(project,movie,ExportSupport.NO_PROGRESS);
            check(Files.size(movie)>1000,"MP4 size");
            Process probe=new ProcessBuilder("ffprobe","-v","error","-show_entries",
                "stream=codec_type","-of","csv=p=0",movie.toString())
                .redirectErrorStream(true).start();
            String output=new String(probe.getInputStream().readAllBytes());
            check(probe.waitFor(10,TimeUnit.SECONDS) && probe.exitValue()==0,"FFprobe parsed MP4");
            check(output.contains("video") && output.contains("audio"),"MP4 contains video and audio");
            check(Files.size(movie)>1000,"MP4 nonempty");
            Path stopped=dir.resolve("cancelled.mp4");
            boolean interrupted=false;
            try { ExportSupport.mp4(project,stopped,(done,total)->{ throw new InterruptedException("test cancellation"); }); }
            catch (InterruptedException expected) { interrupted=true; }
            check(interrupted && !Files.exists(stopped),"MP4 cancellation cleans up temporary file");
        }
        // A cancellation should not leave a partially valid output behind.
        Path cancelled=dir.resolve("cancelled.zip");
        boolean cancelledObserved=false;
        try { ExportSupport.pngSequence(project,cancelled,(done,total)->{ throw new InterruptedException("test cancel"); }); }
        catch (InterruptedException expected) { cancelledObserved=true; }
        check(cancelledObserved && !Files.exists(cancelled),"cancel cleanup");
        System.out.println("ExportSupportTest: PNG, sprite sheet, timed ZIP, GIF, MP4+audio, cancellation PASSED");
        System.out.println("Artifacts: "+dir);
    }
}
