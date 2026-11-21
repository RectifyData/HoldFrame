package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Exports held drawings without ever writing a directory of individual video frames.
 * GIF uses one frame per pose, video streams RGB directly into ffmpeg's stdin.
 */
final class ExportSupport {
    private ExportSupport() {}
    interface Progress {
        void update(int done, int total) throws InterruptedException;
    }
    static final Progress NO_PROGRESS = (done,total) -> {};

    static BufferedImage render(Model.Project project, int pose) {
        BufferedImage image = new BufferedImage(Model.WIDTH, Model.HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0,0,Model.WIDTH,Model.HEIGHT);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Model.Drawing drawing=project.drawingAt(pose);
            // Use a transparent intermediate surface: erasing strokes must not erase background images.
            BufferedImage layer = new BufferedImage(Model.WIDTH,Model.HEIGHT,BufferedImage.TYPE_INT_ARGB);
            Graphics2D lg=layer.createGraphics();
            try {
                lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                lg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                for (Model.ImageItem item : drawing.images) {
                    BufferedImage asset=ImageSupport.get(project,item.assetId);
                    if (asset!=null) lg.drawImage(asset,Math.round(item.x),Math.round(item.y),
                        Math.max(1,Math.round(item.width)),Math.max(1,Math.round(item.height)),null);
                }
                for (Model.Stroke stroke:drawing.strokes) CanvasView.paintStroke(lg,stroke);
                for (Model.TextItem text:drawing.texts) FontSupport.draw(lg,text);
            } finally { lg.dispose(); }
            g.drawImage(layer,0,0,null);
        } finally { g.dispose(); }
        return image;
    }

    static void png(Model.Project project,int pose,Path destination) throws Exception {
        project.validate();
        if (pose < 0 || pose >= project.timeline.size()) throw new IllegalArgumentException("Invalid pose index");
        Path temp=tempFor(destination);
        try {
            if (!ImageIO.write(render(project,pose),"png",temp.toFile()))
                throw new IOException("PNG encoder is unavailable");
            atomicReplace(temp,destination);
        } finally { Files.deleteIfExists(temp); }
    }

    /** One PNG per *pose*, not one PNG per held video frame. Includes timing metadata. */
    static void pngSequence(Model.Project project,Path destination,Progress progress) throws Exception {
        project.validate();
        if (project.timeline.size()>10000)
            throw new IOException("PNG sequence limited to 10,000 poses. Export a shorter selection.");
        Path temp=tempFor(destination);
        try {
            try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temp))) {
                StringBuilder timing=new StringBuilder("pose,start_frame,duration_frames,start_seconds,duration_seconds,filename\n");
                int start=0;
                for (int i=0;i<project.timeline.size();i++) {
                    Model.Exposure clip=project.timeline.get(i);
                    String name=String.format(Locale.ROOT,"poses/pose_%06d.png",i+1);
                    ByteArrayOutputStream frame=new ByteArrayOutputStream();
                    if (!ImageIO.write(render(project,i),"png",frame))
                        throw new IOException("PNG encoder is unavailable");
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(frame.toByteArray());
                    zip.closeEntry();
                    timing.append(i+1).append(',').append(start).append(',').append(clip.duration).append(',')
                        .append(number(start/(double)project.fps)).append(',')
                        .append(number(clip.duration/(double)project.fps)).append(',').append(name).append('\n');
                    start+=clip.duration;
                    progress.update(i+1,project.timeline.size());
                }
                zip.putNextEntry(new ZipEntry("timing.csv"));
                zip.write(timing.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("README.txt"));
                zip.write(("HoldFrame image sequence: one image per pose, not per exposure frame.\n"+
                    "Project FPS: "+project.fps+"\nUse timing.csv to reproduce exact hold durations.\n")
                    .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            atomicReplace(temp,destination);
        } finally { Files.deleteIfExists(temp); }
    }

    /** Compact contact/sprite sheet for relatively short pose sequences. */
    static void spriteSheet(Model.Project project,Path destination,Progress progress) throws Exception {
        project.validate();
        if (project.timeline.size()>32)
            throw new IOException("Sprite sheets support at most 32 poses to prevent huge memory allocation. Use PNG sequence ZIP.");
        int columns=Math.min(4,project.timeline.size());
        int rows=(project.timeline.size()+columns-1)/columns;
        BufferedImage sheet=new BufferedImage(Model.WIDTH*columns,Model.HEIGHT*rows,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=sheet.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
            for (int i=0;i<project.timeline.size();i++) {
                g.drawImage(render(project,i),i%columns*Model.WIDTH,i/columns*Model.HEIGHT,null);
                progress.update(i+1,project.timeline.size());
            }
        } finally { g.dispose(); }
        Path temp=tempFor(destination);
        try {
            if (!ImageIO.write(sheet,"png",temp.toFile())) throw new IOException("PNG encoder unavailable");
            atomicReplace(temp,destination);
        } finally { Files.deleteIfExists(temp); }
    }

    static void gif(Model.Project project,Path destination,Progress progress) throws Exception {
        project.validate();
        Iterator<ImageWriter> writers=ImageIO.getImageWritersByFormatName("gif");
        if (!writers.hasNext()) throw new IOException("GIF encoder unavailable");
        ImageWriter writer=writers.next();
        Path temp=tempFor(destination);
        boolean success=false;
        try (ImageOutputStream stream=ImageIO.createImageOutputStream(temp.toFile())) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            int cumulative=0, previousCentis=0;
            for (int i=0;i<project.timeline.size();i++) {
                Model.Exposure clip=project.timeline.get(i);
                cumulative+=clip.duration;
                int roundedCentis=Math.round(cumulative*100f/project.fps);
                int duration=Math.max(1,roundedCentis-previousCentis);
                previousCentis=roundedCentis;
                boolean firstFrame=i==0;
                BufferedImage frame=render(project,i);
                // A GIF can hold only 655.35 seconds in one frame; split longer holds.
                while (duration>0) {
                    int part=Math.min(duration,65535);
                    writeGifFrame(writer,frame,part,firstFrame);
                    firstFrame=false;
                    duration-=part;
                }
                progress.update(i+1,project.timeline.size());
            }
            writer.endWriteSequence();
            stream.flush();
            success=true;
        } finally {
            writer.dispose();
            if (!success) Files.deleteIfExists(temp);
        }
        try { atomicReplace(temp,destination); }
        finally { Files.deleteIfExists(temp); }
    }

    private static void writeGifFrame(ImageWriter writer,BufferedImage image,int delay,boolean first) throws IOException {
        ImageWriteParam params=writer.getDefaultWriteParam();
        IIOMetadata meta=writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image),params);
        String name=meta.getNativeMetadataFormatName();
        IIOMetadataNode root=(IIOMetadataNode)meta.getAsTree(name);
        IIOMetadataNode gce=node(root,"GraphicControlExtension");
        gce.setAttribute("disposalMethod","none");
        gce.setAttribute("userInputFlag","FALSE");
        gce.setAttribute("transparentColorFlag","FALSE");
        gce.setAttribute("delayTime",String.valueOf(delay));
        gce.setAttribute("transparentColorIndex","0");
        if (first) {
            IIOMetadataNode extensions=node(root,"ApplicationExtensions");
            IIOMetadataNode loop=new IIOMetadataNode("ApplicationExtension");
            loop.setAttribute("applicationID","NETSCAPE");
            loop.setAttribute("authenticationCode","2.0");
            loop.setUserObject(new byte[]{1,0,0}); // loop indefinitely
            extensions.appendChild(loop);
        }
        meta.setFromTree(name,root);
        writer.writeToSequence(new IIOImage(image,null,meta),params);
    }
    private static IIOMetadataNode node(IIOMetadataNode root,String name) {
        for (int i=0;i<root.getLength();i++) {
            if (root.item(i).getNodeName().equals(name)) return (IIOMetadataNode)root.item(i);
        }
        IIOMetadataNode created=new IIOMetadataNode(name);
        root.appendChild(created);
        return created;
    }

    static String ffmpegBinary() {
        return System.getProperty("holdframe.ffmpeg", "ffmpeg");
    }
    static boolean ffmpegAvailable() {
        try {
            Process p=new ProcessBuilder(ffmpegBinary(),"-version").redirectErrorStream(true).start();
            boolean done=p.waitFor(5,TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            return done && p.exitValue()==0;
        } catch (Exception ex) { return false; }
    }

    static void mp4(Model.Project project,Path destination,Progress progress) throws Exception {
        project.validate();
        if (!ffmpegAvailable()) throw new IOException("MP4 export needs FFmpeg installed and on PATH");
        int total=project.totalFrames();
        // Avoid accidental multihour exports. The editable project has no such limitation.
        if (total > project.fps*3600)
            throw new IOException("Video exceeds one hour. Shorten the timeline before MP4 export.");
        Path temp=tempFor(destination);
        Path log=Files.createTempFile("holdframe-ffmpeg-",".log");
        List<Model.AudioClip> sounds=new ArrayList<>();
        for (Model.AudioClip clip : project.audioClips) {
            if (clip.muted || clip.volume<=0 || clip.startFrame>=total) continue;
            Path file=Path.of(clip.sourcePath);
            if (!Files.isRegularFile(file)) throw new IOException("Audio file missing: "+file);
            sounds.add(clip);
        }
        List<String> cmd=new ArrayList<>(List.of(ffmpegBinary(),"-hide_banner","-loglevel","error",
            "-nostdin","-y","-f","rawvideo","-pixel_format","rgb24",
            "-video_size",Model.WIDTH+"x"+Model.HEIGHT,"-framerate",String.valueOf(project.fps),
            "-i","pipe:0"));
        for (Model.AudioClip clip:sounds) {
            cmd.add("-i"); cmd.add(clip.sourcePath);
        }
        if (!sounds.isEmpty()) {
            StringBuilder filters=new StringBuilder();
            for (int i=0;i<sounds.size();i++) {
                Model.AudioClip clip=sounds.get(i);
                double offset=clip.sourceOffsetMillis/1000.0;
                double length=Math.min(clip.durationFrames,total-clip.startFrame)/(double)project.fps;
                long delay=Math.round(clip.startFrame*1000d/project.fps);
                if (filters.length()>0) filters.append("; ");
                filters.append("[").append(i+1).append(":a:0]atrim=start=")
                    .append(number(offset)).append(":duration=").append(number(length))
                    .append(",asetpts=PTS-STARTPTS,volume=").append(number(clip.volume))
                    .append(",adelay=").append(delay).append(":all=1[a").append(i).append("]");
            }
            filters.append(';');
            for (int i=0;i<sounds.size();i++) filters.append("[a").append(i).append("]");
            filters.append("amix=inputs=").append(sounds.size())
                .append(":duration=longest:normalize=0[aout]");
            cmd.addAll(List.of("-filter_complex",filters.toString(),"-map","0:v:0","-map","[aout]"));
        } else cmd.addAll(List.of("-map","0:v:0","-an"));
        cmd.addAll(List.of("-t",number(total/(double)project.fps),
            "-c:v","libx264","-preset","veryfast","-crf","20","-pix_fmt","yuv420p"));
        if (!sounds.isEmpty()) cmd.addAll(List.of("-c:a","aac","-b:a","160k"));
        cmd.addAll(List.of("-movflags","+faststart","-f","mp4",temp.toString()));
        Process process=null;
        boolean success=false;
        try {
            process=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            int[] rgb=new int[Model.WIDTH*Model.HEIGHT];
            byte[] data=new byte[rgb.length*3];
            IOException pipelineError=null;
            try (OutputStream stdin=process.getOutputStream()) {
                int frameIndex=0;
                for (int i=0;i<project.timeline.size();i++) {
                    Model.Exposure clip=project.timeline.get(i);
                    BufferedImage image=render(project,i);
                    image.getRGB(0,0,Model.WIDTH,Model.HEIGHT,rgb,0,Model.WIDTH);
                    for (int p=0,o=0;p<rgb.length;p++) {
                        int color=rgb[p];
                        data[o++]=(byte)(color>>16); data[o++]=(byte)(color>>8); data[o++]=(byte)color;
                    }
                    for (int f=0;f<clip.duration;f++) {
                        stdin.write(data);
                        frameIndex++;
                        if (f%8==0 || f==clip.duration-1) progress.update(frameIndex,total);
                    }
                }
            } catch (IOException ex) { pipelineError=ex; }
            if (pipelineError!=null) {
                process.waitFor(5,TimeUnit.SECONDS);
                String details=Files.readString(log,StandardCharsets.UTF_8);
                if (details.length()>1200) details=details.substring(details.length()-1200);
                throw new IOException("FFmpeg rejected video frames: "+details,pipelineError);
            }
            if (!process.waitFor(120,TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IOException("FFmpeg timed out while finishing encoding");
            }
            if (process.exitValue()!=0) {
                String details=Files.readString(log,StandardCharsets.UTF_8);
                if (details.length()>1500) details=details.substring(details.length()-1500);
                throw new IOException("FFmpeg export failed: "+details);
            }
            success=true;
        } finally {
            if (process!=null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
            if (!success) Files.deleteIfExists(temp);
        }
        try { atomicReplace(temp,destination); }
        finally { Files.deleteIfExists(temp); }
    }
    private static void atomicReplace(Path temp,Path destination) throws IOException {
        try { Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static String number(double value) { return String.format(Locale.ROOT,"%.6f",value); }
    private static Path tempFor(Path dest) throws IOException {
        Path abs=dest.toAbsolutePath();
        Path directory=abs.getParent();
        return Files.createTempFile(directory,".holdframe-export-", "."+extension(dest));
    }
    private static String extension(Path path) {
        String name=path.getFileName().toString();
        int dot=name.lastIndexOf('.');
        return dot<0?"tmp":name.substring(dot+1).toLowerCase(Locale.ROOT);
    }
}
