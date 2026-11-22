package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.ReleaseChecker;
import javax.swing.JDialog;
import javax.swing.JProgressBar;
import javax.swing.JOptionPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks GitHub release metadata and opens native, visibly interactive installers. */
final class NativeInstallerUpdater {
    private NativeInstallerUpdater() {}
    private static final String API="https://api.github.com/repos/RectifyData/HoldFrame/releases?per_page=60";
    private static final String BASE="https://github.com/RectifyData/HoldFrame/releases/download/";
    private static final Pattern TAG=Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"((?:windows|linux)-v([0-9]+\\.[0-9]+\\.[0-9]+))\\\"");
    private static final Pattern LINK=Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern DIGEST=Pattern.compile("\\\"digest\\\"\\s*:\\s*\\\"sha256:([a-fA-F0-9]{64})\\\"");
    static final class Update {
        final String version,tag,url,sha256;
        final String asset;
        Update(String version,String tag,String url,String asset,String sha256) {
            this.version=version;this.tag=tag;this.url=url;this.asset=asset;this.sha256=sha256;
        }
        String page(){return "https://github.com/RectifyData/HoldFrame/releases/tag/"+tag;}
    }
    static String platform(){
        String os=System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win")?"windows":os.contains("linux")?"linux":"unsupported";
    }
    static String assetFor(String platform){return platform.equals("windows")?"HoldFrame-Setup.exe":platform.equals("linux")?"HoldFrame-Linux-amd64.deb":"";}
    /** Constrained parser: only accepts official GitHub URLs and the expected native asset. */
    static Update parse(String response,String installed,String platform) {
        if(response==null||response.length()>2_000_000)throw new IllegalArgumentException("Invalid release list");
        String expected=assetFor(platform);
        if(expected.isEmpty())return null;
        Matcher tags=TAG.matcher(response);
        Update best=null;
        while(tags.find()) {
            String tag=tags.group(1),version=tags.group(2);
            if(!tag.startsWith(platform+"-v")||ReleaseChecker.compare(version,installed)<=0)continue;
            int from=tags.end(),to=response.length();
            Matcher next=TAG.matcher(response);
            if(next.find(from))to=next.start();
            String block=response.substring(from,to);
            if(block.contains("\"draft\":true")||block.contains("\"draft\": true"))continue;
            Matcher links=LINK.matcher(block);
            while(links.find()) {
                String url=links.group(1);
                if(!url.equals(BASE+tag+"/"+expected))continue;
                int start=Math.max(0,block.lastIndexOf('{',links.start()));
                int end=block.indexOf('}',links.end());
                String asset=block.substring(start,end<0?block.length():end+1);
                Matcher hash=DIGEST.matcher(asset);
                String sha=hash.find()?hash.group(1).toLowerCase(java.util.Locale.ROOT):"";
                if(best==null||ReleaseChecker.compare(version,best.version)>0)
                    best=new Update(version,tag,url,expected,sha);
            }
        }
        return best;
    }
    private static String retrieve()throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection();
        c.setConnectTimeout(7000);c.setReadTimeout(12000);
        c.setRequestProperty("Accept","application/vnd.github+json");
        c.setRequestProperty("User-Agent","HoldFrame-NativeInstaller-Updater/0.12");
        try {
            if(c.getResponseCode()!=200)throw new IOException("GitHub HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream()) {
                byte[] data=in.readNBytes(2_000_001);
                if(data.length>2_000_000)throw new IOException("Release response too large");
                return new String(data,StandardCharsets.UTF_8);
            }
        }finally{c.disconnect();}
    }
    static void check(Editor app,String installed,boolean manual) {
        new SwingWorker<Update,Void>() {
            private Exception error;
            @Override protected Update doInBackground() {
                try{return parse(retrieve(),installed,platform());}
                catch(Exception ex){error=ex;return null;}
            }
            @Override protected void done() {
                if(!app.isDisplayable())return;
                if(error!=null) {
                    if(manual)JOptionPane.showMessageDialog(app,"Could not check updates: "+error.getMessage(),
                        "HoldFrame updates",JOptionPane.WARNING_MESSAGE);
                    return;
                }
                try {
                    Update update=get();
                    if(update==null) {
                        if(manual)JOptionPane.showMessageDialog(app,"No newer native installer was found.",
                            "HoldFrame updates",JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    String[] choices={"Download installer","Release notes","Later"};
                    int choice=JOptionPane.showOptionDialog(app,
                        "HoldFrame "+update.version+" is available (installed: "+installed+").\n"+
                        "Download the official "+update.asset+" and launch its visible installer?",
                        "Native update available",JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,
                        null,choices,choices[0]);
                    if(choice==0)download(app,update);
                    else if(choice==1)openRelease(app,update.page());
                }catch(Exception ex){if(manual)JOptionPane.showMessageDialog(app,ex.toString());}
            }
        }.execute();
    }
    private static void openRelease(Editor app,String page) {
        try{Desktop.getDesktop().browse(URI.create(page));}
        catch(Exception ex){JOptionPane.showMessageDialog(app,page,"Release page",JOptionPane.INFORMATION_MESSAGE);}
    }
    private static void download(Editor app,Update update) {
        Path dir=Path.of(System.getProperty("user.home","."),"Downloads");
        try{Files.createDirectories(dir);}catch(IOException e){JOptionPane.showMessageDialog(app,e.getMessage());return;}
        Path destination=dir.resolve("HoldFrame-"+update.tag+"-"+update.asset);
        JDialog dialog=new JDialog(app,"Downloading HoldFrame "+update.version,false);
        JProgressBar progress=new JProgressBar(0,100);progress.setStringPainted(true);progress.setIndeterminate(true);
        JLabel label=new JLabel("Downloading official native installer…");
        JPanel box=new JPanel(new BorderLayout(8,8));box.setBorder(javax.swing.BorderFactory.createEmptyBorder(16,18,16,18));
        box.add(label,BorderLayout.NORTH);box.add(progress,BorderLayout.CENTER);
        JButton cancel=new JButton("Cancel");JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);box.add(actions,BorderLayout.SOUTH);
        dialog.setContentPane(box);dialog.setSize(440,175);dialog.setLocationRelativeTo(app);
        SwingWorker<Path,Void> job=new SwingWorker<>() {
            @Override protected Path doInBackground() throws Exception {
                Path temp=Files.createTempFile(dir,".holdframe-update-",".part");
                try {
                    HttpURLConnection c=(HttpURLConnection)new URL(update.url).openConnection();
                    c.setConnectTimeout(12000);c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent","HoldFrame-NativeInstaller-Updater/0.12");
                    try {
                        if(c.getResponseCode()!=200)throw new IOException("Download HTTP "+c.getResponseCode());
                        long total=c.getContentLengthLong(),done=0;
                        if(total>350_000_000L)throw new IOException("Installer too large");
                        MessageDigest digest=MessageDigest.getInstance("SHA-256");
                        try(InputStream input=c.getInputStream();OutputStream out=Files.newOutputStream(temp)) {
                            byte[] block=new byte[65536];int n;
                            while((n=input.read(block))!=-1) {
                                if(isCancelled())throw new InterruptedException("Cancelled");
                                done+=n;
                                if(done>350_000_000L)throw new IOException("Installer too large");
                                digest.update(block,0,n);out.write(block,0,n);
                                if(total>0)setProgress((int)Math.min(100,done*100/total));
                            }
                        }
                        if(done<100_000L)throw new IOException("Download is not a complete installer");
                        String actual=HexFormat.of().formatHex(digest.digest());
                        if(!update.sha256.isEmpty()&&!update.sha256.equals(actual))
                            throw new IOException("Installer SHA-256 does not match GitHub release digest");
                        try{Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                        catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);}
                        return destination;
                    }finally{c.disconnect();}
                }finally{Files.deleteIfExists(temp);}
            }
            @Override protected void done() {
                dialog.dispose();
                if(isCancelled())return;
                try {
                    Path file=get();
                    int choice=JOptionPane.showConfirmDialog(app,
                        "Downloaded "+file.getFileName()+".\nOpen the native installation UI now?",
                        "Install HoldFrame update",JOptionPane.YES_NO_OPTION);
                    if(choice==JOptionPane.YES_OPTION) {
                        try{Desktop.getDesktop().open(file.toFile());}
                        catch(Exception ex){JOptionPane.showMessageDialog(app,
                            "Open this installer manually:\n"+file,"Installer saved",JOptionPane.INFORMATION_MESSAGE);}
                    }
                }catch(Exception e){JOptionPane.showMessageDialog(app,
                    "Installer download failed: "+e.getMessage(),"Update failed",JOptionPane.ERROR_MESSAGE);}
            }
        };
        job.addPropertyChangeListener(evt->{
            if("progress".equals(evt.getPropertyName())) {
                progress.setIndeterminate(false);progress.setValue((Integer)evt.getNewValue());
            }
        });
        cancel.addActionListener(evt->job.cancel(true));
        job.execute();dialog.setVisible(true);
    }
}
