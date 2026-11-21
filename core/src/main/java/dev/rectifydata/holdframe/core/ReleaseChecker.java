package dev.rectifydata.holdframe.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small dependency-free GitHub Releases updater; includes prereleases. */
public final class ReleaseChecker {
    private ReleaseChecker() {}
    private static final String API="https://api.github.com/repos/RectifyData/HoldFrame/releases?per_page=10";
    private static final Pattern TAG=Pattern.compile("\"tag_name\"\\s*:\\s*\"(v?[0-9]+\\.[0-9]+\\.[0-9]+)\"");
    public static final class Release {
        public final String tag,page;
        public Release(String tag) {
            this.tag=tag;
            this.page="https://github.com/RectifyData/HoldFrame/releases/tag/"+tag;
        }
        public boolean newerThan(String installed){return compare(tag,installed)>0;}
    }
    public static Release parse(String json) {
        if(json==null || json.length()>524288)throw new IllegalArgumentException("Invalid GitHub release response");
        Matcher m=TAG.matcher(json);
        if(!m.find())throw new IllegalArgumentException("No versioned GitHub release found");
        return new Release(m.group(1));
    }
    private static int[] parts(String version) {
        Matcher m=Pattern.compile("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:[-+].*)?$").matcher(version==null?"":version.trim());
        if(!m.matches())return new int[]{0,0,0};
        try {return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))};}
        catch(NumberFormatException e){return new int[]{0,0,0};}
    }
    public static int compare(String a,String b) {
        int[] x=parts(a),y=parts(b);
        for(int i=0;i<3;i++)if(x[i]!=y[i])return Integer.compare(x[i],y[i]);
        return 0;
    }
    public static Release check() throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(API).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept","application/vnd.github+json");
        conn.setRequestProperty("User-Agent","HoldFrame-update-check/0.9");
        try {
            int status=conn.getResponseCode();
            if(status!=200)throw new java.io.IOException("GitHub returned HTTP "+status);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream input=conn.getInputStream()) {
                byte[] chunk=new byte[8192];int n;
                while((n=input.read(chunk))!=-1) {
                    if(output.size()+n>524288)throw new java.io.IOException("Release list too large");
                    output.write(chunk,0,n);
                }
            }
            return parse(output.toString(StandardCharsets.UTF_8));
        } finally {conn.disconnect();}
    }
}
