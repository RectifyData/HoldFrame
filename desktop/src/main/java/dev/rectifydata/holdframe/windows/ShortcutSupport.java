package dev.rectifydata.holdframe.windows;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;

/** User-triggered shortcut creation; no silent or privileged installation. */
final class ShortcutSupport {
    private ShortcutSupport() {}
    static String ps(String s) {
        if(s.indexOf('\n')>=0||s.indexOf('\r')>=0)throw new IllegalArgumentException("Unsafe path");
        return "'" + s.replace("'","''") + "'";
    }
    static String quote(String s) {
        if(s.indexOf('\n')>=0||s.indexOf('\r')>=0)throw new IllegalArgumentException("Unsafe path");
        return "\"" + s.replace("\\","\\\\").replace("\"","\\\"")
            .replace("$","\\$").replace("%","%%") + "\"";
    }
    static Path stableJar(Path home,boolean windows) {
        return windows ? home.resolve("AppData/Local/Programs/HoldFrame/HoldFrame.jar")
            : home.resolve(".local/share/holdframe/HoldFrame.jar");
    }
    static String desktopFile(Path jar,String icon) {
        return "[Desktop Entry]\nVersion=1.0\nType=Application\nName=HoldFrame\n"
            + "Comment=Frame animation editor\nExec=java -jar " + quote(jar.toAbsolutePath().toString())
            + "\nTryExec=java\nIcon="+icon
            + "\nTerminal=false\nCategories=Graphics;AudioVideo;2DGraphics;\nStartupNotify=true\n";
    }
    static void create(boolean desktop,boolean applications) throws Exception {
        if(!desktop&&!applications)throw new IllegalArgumentException("Select a shortcut location.");
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        boolean windows=os.contains("win");
        if(!windows&&!os.contains("linux"))
            throw new UnsupportedOperationException("Shortcuts supported on Windows and Linux.");
        Path home=Path.of(System.getProperty("user.home"));
        Path source;
        try {source=Path.of(HoldFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());}
        catch(URISyntaxException e){throw new IOException("Cannot locate running application.",e);}
        String command=ProcessHandle.current().info().command().orElse("");
        Path exe=(windows&&command.toLowerCase(Locale.ROOT).endsWith("holdframe.exe"))
            ?Path.of(command):null;
        Path jar=null;
        if(exe==null) {
            if(!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                throw new IOException("Please start the packaged HoldFrame.jar (not a development build).");
            if(windows){
                String local=System.getenv("LOCALAPPDATA");
                jar=local==null||local.isBlank()?stableJar(home,true)
                    :Path.of(local).resolve("Programs/HoldFrame/HoldFrame.jar");
            }else jar=stableJar(home,false);
            Files.createDirectories(jar.getParent());
            if(!Files.exists(jar)||!Files.isSameFile(source,jar))
                Files.copy(source,jar,StandardCopyOption.REPLACE_EXISTING);
        }
        if(windows) {
            Path target=exe!=null?exe:Path.of(System.getProperty("java.home"),"bin","javaw.exe");
            if(!Files.isRegularFile(target))throw new IOException("Launcher missing: "+target);
            String args=exe!=null?"":"-jar \""+jar.toString()+"\"";
            String work=(exe!=null?exe.getParent():jar.getParent()).toString();
            String script="$shell=New-Object -ComObject WScript.Shell\n"
                +"$destinations=@()\n"
                +(desktop?"$destinations+=Join-Path ([Environment]::GetFolderPath('Desktop')) 'HoldFrame.lnk'\n":"")
                +(applications?"$destinations+=Join-Path ([Environment]::GetFolderPath('Programs')) 'HoldFrame.lnk'\n":"")
                +"foreach($file in $destinations) {\n"
                +" $parent=Split-Path $file -Parent\n"
                +" New-Item -Force -ItemType Directory -Path $parent | Out-Null\n"
                +" $link=$shell.CreateShortcut($file)\n"
                +" $link.TargetPath="+ps(target.toString())+"\n"
                +" $link.Arguments="+ps(args)+"\n"
                +" $link.WorkingDirectory="+ps(work)+"\n"
                +" $link.Description='HoldFrame animation editor'\n"
                +" $link.Save()\n"
                +"}\n";
            String encoded=Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process p=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive",
                "-EncodedCommand",encoded).redirectErrorStream(true).start();
            byte[] output=p.getInputStream().readNBytes(16384);
            int status=p.waitFor();
            if(status!=0)throw new IOException("Shortcut command failed (exit "+status+
                "): "+new String(output,StandardCharsets.UTF_8));
        }else {
            if(jar==null)throw new IOException("Missing installed Java archive.");
            String icon="applications-graphics";
            try(InputStream in=HoldFrame.class.getResourceAsStream("/assets/HoldFrame.png")) {
                if(in!=null){
                    Path img=home.resolve(".local/share/icons/hicolor/256x256/apps/holdframe.png");
                    Files.createDirectories(img.getParent());
                    Files.copy(in,img,StandardCopyOption.REPLACE_EXISTING);
                    icon=img.toString();
                }
            }
            String contents=desktopFile(jar,icon);
            if(applications){
                Path f=home.resolve(".local/share/applications/holdframe.desktop");
                Files.createDirectories(f.getParent());
                Files.writeString(f,contents,StandardCharsets.UTF_8);
            }
            if(desktop){
                Path folder=home.resolve("Desktop");
                if(!Files.isDirectory(folder))
                    throw new IOException("Desktop folder not present; choose application-menu shortcut.");
                Path file=folder.resolve("HoldFrame.desktop");
                Files.writeString(file,contents,StandardCharsets.UTF_8);
                file.toFile().setExecutable(true,false);
            }
        }
    }
}
