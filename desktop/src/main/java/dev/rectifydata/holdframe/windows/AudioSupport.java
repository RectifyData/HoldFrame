package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Audio playback for the desktop timeline; Java Sound native formats or FFmpeg fallback. */
final class AudioSupport implements AutoCloseable {
    private final Map<String, Clip> loaded=new HashMap<>();
    private final Map<String,Path> conversions=new HashMap<>();
    private final Set<String> failures=new HashSet<>();
    private int previousFrame=-1;
    private String lastError="";

    static long durationMillis(Path source) throws Exception {
        if (!Files.isRegularFile(source)) throw new IOException("Audio file not found");
        try {
            AudioFileFormat format=AudioSystem.getAudioFileFormat(source.toFile());
            AudioFormat audio=format.getFormat();
            if (format.getFrameLength()>0 && audio.getFrameRate()>0)
                return Math.max(1,Math.round(1000d*format.getFrameLength()/audio.getFrameRate()));
        } catch (Exception ignored) { /* Compressed audio may need ffprobe. */ }
        Process process;
        try {
            process=new ProcessBuilder("ffprobe","-v","error","-show_entries","format=duration",
                "-of","default=noprint_wrappers=1:nokey=1",source.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Unsupported audio. WAV/AIFF/AU work directly; MP3/OGG need FFmpeg on PATH.",e);
        }
        try {
            boolean done=process.waitFor(12,TimeUnit.SECONDS);
            if (!done) { process.destroyForcibly(); throw new IOException("Audio probe timed out"); }
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
            if (process.exitValue()!=0) throw new IOException("Cannot read audio: "+output);
            long millis=Math.round(Double.parseDouble(output)*1000d);
            if (millis<1 || millis>8*60*60*1000L) throw new IOException("Unsupported audio duration");
            return millis;
        } catch (NumberFormatException e) { throw new IOException("Cannot determine audio duration",e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Audio probing interrupted",e); }
    }
    /** Play and seek are frame-based, so audio does not affect drawing exposure durations. */
    void sync(Model.Project project,int frame,boolean forceSeek) {
        boolean jumped=forceSeek || previousFrame<0 || frame<previousFrame || frame-previousFrame>Math.max(4,project.fps/3);
        previousFrame=frame;
        Set<String> active=new HashSet<>();
        for (Model.AudioClip item:project.audioClips) {
            if (item.muted || item.volume<=0 || frame<item.startFrame || frame>=item.endFrame()) continue;
            active.add(item.id);
            Clip player=loaded.get(item.id);
            if (player==null && !failures.contains(item.id)) {
                try {
                    player=open(item);
                    loaded.put(item.id,player);
                } catch (Exception ex) {
                    failures.add(item.id);
                    lastError=ex.getMessage();
                    continue;
                }
            }
            if (player==null) continue;
            long offset=item.sourceOffsetMillis*1000L+
                Math.round((frame-item.startFrame)*1_000_000d/project.fps);
            long limit=player.getMicrosecondLength();
            if (offset>=limit || offset<0) { player.stop(); continue; }
            // Clip looping is disabled. Resync only on actual seeking, not every UI timer tick.
            if (jumped || !player.isRunning()) {
                player.stop();
                player.setMicrosecondPosition(Math.max(0,offset));
                player.start();
            }
            if (player.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain=(FloatControl)player.getControl(FloatControl.Type.MASTER_GAIN);
                float db=item.volume<=0 ? gain.getMinimum() : (float)(20*Math.log10(item.volume));
                gain.setValue(Math.max(gain.getMinimum(),Math.min(gain.getMaximum(),db)));
            }
            if (player.isControlSupported(BooleanControl.Type.MUTE))
                ((BooleanControl)player.getControl(BooleanControl.Type.MUTE)).setValue(item.muted);
        }
        for (Map.Entry<String,Clip> entry:loaded.entrySet())
            if (!active.contains(entry.getKey())) entry.getValue().stop();
    }
    String lastError() { return lastError; }
    private Clip open(Model.AudioClip item) throws Exception {
        Path source=Path.of(item.sourcePath);
        if (!Files.isRegularFile(source)) throw new IOException("Missing audio file: "+source);
        AudioInputStream stream;
        try { stream=AudioSystem.getAudioInputStream(source.toFile()); }
        catch (Exception ex) {
            source=convertWithFfmpeg(source,item.id);
            stream=AudioSystem.getAudioInputStream(source.toFile());
        }
        try (AudioInputStream original=stream) {
            AudioFormat f=original.getFormat();
            AudioFormat pcm=new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                f.getSampleRate(),16,f.getChannels(),f.getChannels()*2,f.getSampleRate(),false);
            long frames=original.getFrameLength();
            if (frames>0 && frames*(long)pcm.getFrameSize()>96_000_000)
                throw new IOException("Audio exceeds 96 MB playback limit; trim or compress the track");
            if (!AudioSystem.isConversionSupported(pcm,f)) throw new IOException("Cannot decode audio format");
            try (AudioInputStream decoded=AudioSystem.getAudioInputStream(pcm,original)) {
                Clip clip=AudioSystem.getClip();
                try { clip.open(decoded); return clip; }
                catch (Exception e) { clip.close(); throw e; }
            }
        }
    }
    private Path convertWithFfmpeg(Path source,String id) throws Exception {
        Path dest=Files.createTempFile("holdframe-audio-", ".wav");
        Process proc;
        try {
            proc=new ProcessBuilder("ffmpeg","-hide_banner","-loglevel","error","-nostdin","-y",
                "-i",source.toString(),"-ac","2","-ar","44100","-c:a","pcm_s16le",dest.toString())
                .redirectErrorStream(true).start();
        } catch (IOException e) {
            Files.deleteIfExists(dest);
            throw new IOException("MP3/OGG playback requires FFmpeg installed and on PATH",e);
        }
        boolean done;
        try { done=proc.waitFor(30,TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); proc.destroyForcibly(); throw new IOException("Audio conversion interrupted",e); }
        if (!done || proc.exitValue()!=0 || Files.size(dest)>96_000_000) {
            proc.destroyForcibly(); Files.deleteIfExists(dest);
            throw new IOException("Cannot decode audio or decoded file exceeds 96 MB");
        }
        conversions.put(id,dest);
        return dest;
    }
    void stopAll() {
        previousFrame=-1;
        for (Clip clip:loaded.values()) clip.stop();
    }
    @Override public void close() {
        stopAll();
        for (Clip clip:loaded.values()) clip.close();
        loaded.clear(); failures.clear();
        for (Path file:conversions.values()) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
        conversions.clear();
    }
}
