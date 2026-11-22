package dev.rectifydata.holdframe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;
import dev.rectifydata.holdframe.core.ReleaseChecker;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Android's system download + interactive Package Installer; no silent APK installs. */
public final class AndroidUpdater {
    private static final String API="https://api.github.com/repos/RectifyData/HoldFrame/releases?per_page=20";
    private static final String APK="HoldFrame-Android12-LowRAM.apk";
    private AndroidUpdater() {}
    static final class Result {
        final String tag,url,page;
        Result(String t,String u,String p){tag=t;url=u;page=p;}
    }
    static Result parse(String json,String installed) throws Exception {
        JSONArray releases=new JSONArray(json);
        Result best=null;
        for(int i=0;i<releases.length();i++) {
            JSONObject release=releases.getJSONObject(i);
            if(release.optBoolean("draft",false))continue;
            String tag=release.optString("tag_name","");
            if(ReleaseChecker.compare(tag,installed)<=0)continue;
            JSONArray assets=release.optJSONArray("assets");
            if(assets==null)continue;
            for(int j=0;j<assets.length();j++){
                JSONObject asset=assets.getJSONObject(j);
                if(!APK.equals(asset.optString("name")))continue;
                String url=asset.optString("browser_download_url","");
                if(!url.startsWith("https://github.com/RectifyData/HoldFrame/releases/download/"))continue;
                if(best==null||ReleaseChecker.compare(tag,best.tag)>0)
                    best=new Result(tag,url,release.optString("html_url","https://github.com/RectifyData/HoldFrame/releases"));
            }
        }
        return best;
    }
    static void check(Activity activity,boolean manual){
        if(manual)Toast.makeText(activity,"Checking HoldFrame releases…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            try {
                String installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName;
                HttpURLConnection conn=(HttpURLConnection)new URL(API).openConnection();
                conn.setConnectTimeout(6000);conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept","application/vnd.github+json");
                conn.setRequestProperty("User-Agent","HoldFrame-Android-Updater");
                String json;
                try {
                    if(conn.getResponseCode()!=200)throw new java.io.IOException("GitHub HTTP "+conn.getResponseCode());
                    ByteArrayOutputStream output=new ByteArrayOutputStream();
                    try(InputStream in=conn.getInputStream()){
                        byte[] buf=new byte[8192];int n;
                        while((n=in.read(buf))!=-1){
                            if(output.size()+n>1024*1024)throw new java.io.IOException("Release response exceeds limit");
                            output.write(buf,0,n);
                        }
                    }
                    json=output.toString(StandardCharsets.UTF_8);
                } finally {conn.disconnect();}
                Result result=parse(json,installed);
                activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    if(result==null) {
                        if(manual)Toast.makeText(activity,"No newer compatible APK release found",Toast.LENGTH_LONG).show();
                        return;
                    }
                    new AlertDialog.Builder(activity).setTitle("HoldFrame "+result.tag)
                        .setMessage("Installed: "+installed+"\nAvailable: "+result.tag+
                            "\n\nDownload the official Android APK? Android will ask you to approve installation. Updates must use the same signing key as your installed copy; back up projects first.")
                        .setNegativeButton("Later",null)
                        .setNeutralButton("Release notes",(d,w)->activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(result.page))))
                        .setPositiveButton("Download update",(d,w)->download(activity,result))
                        .show();
                });
            }catch(Exception error){
                if(manual)activity.runOnUiThread(()->{
                    if(!activity.isFinishing()&&!activity.isDestroyed())
                        Toast.makeText(activity,"Update check failed: "+error.getMessage(),Toast.LENGTH_LONG).show();
                });
            }
        },"holdframe-android-update").start();
    }
    private static void download(Activity activity,Result release){
        DownloadManager manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if(manager==null){Toast.makeText(activity,"Download service unavailable",Toast.LENGTH_LONG).show();return;}
        try {
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(release.url))
                .setTitle("HoldFrame "+release.tag)
                .setDescription("Official Android update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity,Environment.DIRECTORY_DOWNLOADS,"HoldFrame-"+release.tag+".apk");
            long id=manager.enqueue(request);
            LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(32,24,32,12);
            TextView label=new TextView(activity);label.setText("Downloading official APK…");
            ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);progress.setIndeterminate(true);
            content.addView(label);content.addView(progress);
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Updating HoldFrame")
                .setView(content).setNegativeButton("Hide",null).create();
            dialog.show();
            Handler handler=new Handler(Looper.getMainLooper());
            final Runnable[] poll={null};
            poll[0]=()->{
                if(activity.isFinishing()||activity.isDestroyed())return;
                try(Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(id))){
                    if(!cursor.moveToFirst()){dialog.dismiss();return;}
                    int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if(status==DownloadManager.STATUS_FAILED){
                        dialog.dismiss();
                        Toast.makeText(activity,"Update download failed; retry from Updates",Toast.LENGTH_LONG).show();
                        return;
                    }
                    if(status==DownloadManager.STATUS_SUCCESSFUL){
                        dialog.dismiss();
                        Uri uri=manager.getUriForDownloadedFile(id);
                        if(uri==null){Toast.makeText(activity,"Download completed; open it in Downloads",Toast.LENGTH_LONG).show();return;}
                        new AlertDialog.Builder(activity).setTitle("Update downloaded")
                            .setMessage("Install HoldFrame "+release.tag+"? Android will show its installation confirmation.")
                            .setNegativeButton("Later",null)
                            .setPositiveButton("Install",(d,w)->{
                                try {
                                    if(!activity.getPackageManager().canRequestPackageInstalls()){
                                        new AlertDialog.Builder(activity).setTitle("Allow app updates")
                                            .setMessage("Android requires permission for HoldFrame to open its downloaded APK in the system installer. Enable 'Allow from this source', then return to Downloads to install.")
                                            .setNegativeButton("Cancel",null)
                                            .setPositiveButton("Open settings",(prompt,button)->{
                                                Intent settings=new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:"+activity.getPackageName()));
                                                activity.startActivity(settings);
                                            }).show();
                                        return;
                                    }
                                    Intent install=new Intent(Intent.ACTION_INSTALL_PACKAGE);
                                    install.setData(uri);
                                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
                                    activity.startActivity(install);
                                }catch(Exception e){
                                    Toast.makeText(activity,"Open the downloaded APK from Downloads to install",Toast.LENGTH_LONG).show();
                                    activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                                }
                            }).show();
                        return;
                    }
                    long done=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if(total>0){progress.setIndeterminate(false);progress.setProgress((int)Math.min(100,done*100/total));label.setText("Downloaded "+(done/1024)+" / "+(total/1024)+" KiB");}
                }catch(Exception e){dialog.dismiss();Toast.makeText(activity,"Open Downloads to finish the update",Toast.LENGTH_LONG).show();return;}
                handler.postDelayed(poll[0],700);
            };
            handler.post(poll[0]);
        }catch(Exception e){Toast.makeText(activity,"Unable to download update: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
