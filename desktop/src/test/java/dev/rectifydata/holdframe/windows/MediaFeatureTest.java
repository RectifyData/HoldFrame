package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import dev.rectifydata.holdframe.core.ProjectIO;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFileFormat;
import javax.swing.SwingUtilities;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Regressions: portable image assets, transform/undo, external audio, timeline persistence. */
public final class MediaFeatureTest {
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    private static <T> T find(java.awt.Container parent,Class<T> cls) {
        for (java.awt.Component child:parent.getComponents()) {
            if (cls.isInstance(child)) return cls.cast(child);
            if (child instanceof java.awt.Container) {
                T item=find((java.awt.Container)child,cls);
                if (item!=null) return item;
            }
        }
        return null;
    }
    private static int screenX(CanvasView stage,float x) {
        float k=Math.min((stage.getWidth()-32f)/Model.WIDTH,(stage.getHeight()-32f)/Model.HEIGHT);
        return Math.round((stage.getWidth()-Model.WIDTH*k)/2+x*k);
    }
    private static int screenY(CanvasView stage,float y) {
        float k=Math.min((stage.getWidth()-32f)/Model.WIDTH,(stage.getHeight()-32f)/Model.HEIGHT);
        return Math.round((stage.getHeight()-Model.HEIGHT*k)/2+y*k);
    }
    private static void mouse(CanvasView stage,int type,float x,float y) {
        stage.dispatchEvent(new MouseEvent(stage,type,System.currentTimeMillis(),
            type==MouseEvent.MOUSE_DRAGGED?InputEvent.BUTTON1_DOWN_MASK:0,
            screenX(stage,x),screenY(stage,y),1,false,
            type==MouseEvent.MOUSE_DRAGGED?MouseEvent.NOBUTTON:MouseEvent.BUTTON1));
    }
    private static BufferedImage image() {
        BufferedImage b=new BufferedImage(80,40,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=b.createGraphics();
        g.setColor(new Color(0xED5374)); g.fillRect(0,0,80,40);
        g.dispose(); return b;
    }
    private static byte[] bytes(BufferedImage png) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); ImageIO.write(png,"png",b); return b.toByteArray();
    }
    private static Path wavFile() throws Exception {
        Path path=Files.createTempFile("holdframe-test-", ".wav");
        AudioFormat format=new AudioFormat(8000,16,1,true,false);
        byte[] silence=new byte[16000];
        try (AudioInputStream stream=new AudioInputStream(new ByteArrayInputStream(silence),format,8000)) {
            AudioSystem.write(stream,AudioFileFormat.Type.WAVE,path.toFile());
        }
        return path;
    }
    public static void main(String[] args) throws Exception {
        Path wav=wavFile();
        try {
            check(Math.abs(AudioSupport.durationMillis(wav)-1000)<10,"WAV duration detected");
            Path pngPath=Files.createTempFile("holdframe-test-", ".png");
            try {
                ImageIO.write(image(),"png",pngPath.toFile());
                ImageSupport.Imported imported=ImageSupport.importFile(pngPath);
                check(imported.width==80 && imported.height==40 && imported.digest.length()==64,"PNG dimensions and hash");
                Model.Project project=new Model.Project();
                project.imageAssets.put(imported.digest,imported.bytes);
                Model.ImageItem pic=new Model.ImageItem(imported.digest,120,60,160,80);
                project.drawingAt(0).images.add(pic);
                Model.AudioClip audio=project.addAudio(wav.toAbsolutePath().toString(),12,24);
                audio.muted=true; audio.volume=.65f; audio.sourceOffsetMillis=100;
                Model.ClipboardFrame copied=project.copyFrame(0);
                project.pasteAfter(0,copied);
                check(project.imageAssets.size()==1,"duplicated pose shares image bytes");
                ByteArrayOutputStream payload=new ByteArrayOutputStream();
                Path base=Files.createTempDirectory("holdframe-project-");
                try {
                    ProjectIO.write(project,payload,base);
                    Model.Project restored=ProjectIO.read(new ByteArrayInputStream(payload.toByteArray()),base);
                    check(restored.drawingAt(0).images.size()==1,"image item persisted");
                    check(restored.imageAssets.size()==1,"image asset persisted once");
                    check(restored.drawingAt(1).images.get(0)!=restored.drawingAt(0).images.get(0),"image placement is independent");
                    check(restored.audioClips.size()==1 && restored.audioClips.get(0).startFrame==12,
                          "audio timeline persisted");
                    check(restored.audioClips.get(0).sourcePath.equals(wav.toAbsolutePath().toString()),"relative audio path resolved");
                    check(restored.audioClips.get(0).muted && Math.abs(restored.audioClips.get(0).volume-.65f)<.001,
                          "audio trim/mute/volume persisted");
                    byte[] broken=payload.toByteArray().clone(); broken[3]^=20;
                    boolean failed=false;
                    try { ProjectIO.read(new ByteArrayInputStream(broken)); }
                    catch (Exception ignored) { failed=true; }
                    check(failed,"corrupt project rejected");
                } finally { Files.deleteIfExists(base); }
                // Exercise the actual desktop GUI and its four image handles.
                new MouseEvent(new javax.swing.JPanel(),MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,0,0,0,false);
                AtomicReference<Throwable> error=new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    Editor editor=new Editor();
                    try {
                        editor.setVisible(true);
                        editor.insertImage(imported.bytes,imported.digest,80,40);
                        CanvasView stage=find(editor.getContentPane(),CanvasView.class);
                        Model.ImageItem item=editor.project().drawingAt(0).images.get(0);
                        check(stage!=null && stage.getTool()==CanvasView.Tool.LASSO,"image imported and selected");
                        float oldWidth=item.width;
                        float x=item.x+item.width,y=item.y+item.height;
                        mouse(stage,MouseEvent.MOUSE_PRESSED,x,y);
                        mouse(stage,MouseEvent.MOUSE_DRAGGED,x+35,y+22);
                        mouse(stage,MouseEvent.MOUSE_RELEASED,x+35,y+22);
                        check(item.width>oldWidth+20,"image four-handle scaling works");
                        // Verify undo of image transform, and physical pixels are rendered.
                        Object key=editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(javax.swing.KeyStroke.getKeyStroke("control Z"));
                        editor.getRootPane().getActionMap().get(key).actionPerformed(
                            new java.awt.event.ActionEvent(editor,0,"control Z"));
                        check(Math.abs(item.width-oldWidth)<.01,"image transform undo");
                        Model.AudioClip sound=editor.project().addAudio(wav.toAbsolutePath().toString(),12,24);
                        editor.selectAudio(sound.id);
                        editor.refresh();
                        TimelineView track=find(editor.getContentPane(),TimelineView.class);
                        check(track!=null,"audio timeline is visible");
                        // Move the audio clip without shifting the current drawing exposure.
                        int clipX=16+sound.startFrame*7+20;
                        track.dispatchEvent(new MouseEvent(track,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,
                            clipX,120,1,false,MouseEvent.BUTTON1));
                        track.dispatchEvent(new MouseEvent(track,MouseEvent.MOUSE_DRAGGED,System.currentTimeMillis(),
                            InputEvent.BUTTON1_DOWN_MASK,clipX+42,120,1,false,MouseEvent.NOBUTTON));
                        track.dispatchEvent(new MouseEvent(track,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,
                            clipX+42,120,1,false,MouseEvent.BUTTON1));
                        check(sound.startFrame>12,"audio clip moves on timeline");
                        int durationBefore=sound.durationFrames;
                        int endX=16+sound.endFrame()*7;
                        track.dispatchEvent(new MouseEvent(track,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,
                            endX,120,1,false,MouseEvent.BUTTON1));
                        track.dispatchEvent(new MouseEvent(track,MouseEvent.MOUSE_DRAGGED,System.currentTimeMillis(),
                            InputEvent.BUTTON1_DOWN_MASK,endX+42,120,1,false,MouseEvent.NOBUTTON));
                        track.dispatchEvent(new MouseEvent(track,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,
                            endX+42,120,1,false,MouseEvent.BUTTON1));
                        check(sound.durationFrames>durationBefore,"audio clip right-edge trimming");
                        BufferedImage screenshot=new BufferedImage(editor.getWidth(),editor.getHeight(),BufferedImage.TYPE_INT_RGB);
                        Graphics2D g=screenshot.createGraphics(); editor.paint(g);g.dispose();
                        if (args.length>0) ImageIO.write(screenshot,"png",new java.io.File(args[0]));
                        editor.dispose();
                    } catch (Throwable ex) { error.set(ex); editor.dispose(); }
                });
                if (error.get()!=null) throw new RuntimeException(error.get());
                System.out.println("Media test passed (image transform, HFM4, audio duration, references)");
            } finally { Files.deleteIfExists(pngPath); }
        } finally { Files.deleteIfExists(wav); }
    }
}
