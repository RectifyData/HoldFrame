package dev.rectifydata.holdframe;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.text.InputType;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import dev.rectifydata.holdframe.core.Model;
import dev.rectifydata.holdframe.core.ProjectIO;
import dev.rectifydata.holdframe.core.ReleaseChecker;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Lightweight native Android editor; no WebView, media encoder or per-frame bitmaps. */
public final class MainActivity extends Activity {
    private static final int CREATE_DOCUMENT = 100;
    private static final int OPEN_DOCUMENT = 101;
    private static final int IMPORT_FONT = 102;
    private static final int IMPORT_IMAGE = 103;
    private static final int IMPORT_AUDIO = 104;
    private static final String SETTINGS_PREFS = "holdframe_settings";
    private static final String AUTO_UPDATES_PREF = "check_updates_automatically";
    private static final String UPDATE_CHECKED_AT = "last_update_check";
    private static final String LOW_RAM_PREFERENCE = "low_ram_preference";
    private static final String KMBS_ENABLED = "keep_my_brush_size";
    private static final String KMBS_SIZE = "saved_brush_size";
    private long physicalRamBytes;
    private int lowRamPreference = RamProfile.AUTO;
    private boolean lowRamEnabled;
    private MediaPlayer audioPlayer;
    private String playingAudioId;
    private String importedFontPath = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Deque<Model.Stroke>> redos = new HashMap<>();
    private final Map<String, Deque<Model.Stroke>> undos = new HashMap<>();
    private static final int HISTORY_STROKE=1,HISTORY_TEXT=2;
    private static final class TextEdit {
        final int index;
        final Model.TextItem before,after;
        TextEdit(int index,Model.TextItem before,Model.TextItem after){
            this.index=index;
            this.before=before==null?null:before.copy();
            this.after=after==null?null:after.copy();
        }
    }
    private final Map<String,Deque<TextEdit>> textUndos=new HashMap<>(),textRedos=new HashMap<>();
    private final Map<String,Deque<Integer>> undoOrder=new HashMap<>(),redoOrder=new HashMap<>();
    private void trimHistory(String id,Deque<Integer> order,boolean redo){
        int limit=RamProfile.undoLimit(lowRamEnabled);
        while(order.size()>limit){
            int kind=order.removeLast();
            if(kind==HISTORY_TEXT){
                Deque<TextEdit> h=(redo?textRedos:textUndos).get(id);
                if(h!=null&&!h.isEmpty())h.removeLast();
            } else {
                Deque<Model.Stroke> h=(redo?redos:undos).get(id);
                if(h!=null&&!h.isEmpty())h.removeLast();
            }
        }
    }
    private void recordAction(String id,int kind){
        Deque<Integer> order=undoOrder.computeIfAbsent(id,k->new ArrayDeque<>());
        order.push(kind);trimHistory(id,order,false);
        redoOrder.remove(id);redos.remove(id);textRedos.remove(id);
    }
    private void recordTextEdit(Model.Drawing editable,int index,Model.TextItem before,Model.TextItem after){
        textUndos.computeIfAbsent(editable.id,k->new ArrayDeque<>())
            .push(new TextEdit(index,before,after));
        recordAction(editable.id,HISTORY_TEXT);
    }
    private void applyTextEdit(Model.Drawing editable,TextEdit change,boolean undo){
        Model.TextItem value=undo?change.before:change.after;
        int index=change.index;
        if(change.before==null || change.after==null) {
            // Insert/delete at the exact index: restoring a deleted object must
            // not overwrite the text object that followed it in the list.
            if(value==null) {
                if(index>=0 && index<editable.texts.size()) editable.texts.remove(index);
            } else if(index>=0 && index<=editable.texts.size()) {
                editable.texts.add(index,value.copy());
            }
        } else if(index>=0 && index<editable.texts.size()) {
            editable.texts.get(index).assign(value);
        }
        canvas.setDrawing(editable);
        canvas.rebuild();
    }
    private Model.ClipboardSequence clipboard;
    private int selectedFrom=-1, selectedTo=-1;
    private String selectedAudioClipId;
    private boolean timelineClipSelected;
    void selectAudioClip(String id) {
        if(canvas!=null)canvas.deselectText();
        selectedAudioClipId=id;
        timelineClipSelected=false;
        selectedFrom=selectedTo=-1;
        refresh();
    }
    String selectedAudioClipId(){return selectedAudioClipId;}
    void selectTimelineClip() {
        if(canvas!=null)canvas.deselectText();
        selectedAudioClipId=null;
        timelineClipSelected=true;
        selectedFrom=selectedTo=selected;
        refresh();
    }
    private void deleteSelection() {
        if(canvas!=null && canvas.selectedTextIndex()>=0) {deleteSelectedText();return;}
        if(selectedAudioClipId!=null) {deleteAudioClip(selectedAudioClipId);return;}
        if(timelineClipSelected) {delete();return;}
        toast("Select a drawing, audio clip or text to delete");
    }
    private void deleteAudioClip(String id) {
        if(id==null)return;
        stopPlayback();
        Model.AudioClip removed=null;
        for(int i=0;i<model.audioClips.size();i++)
            if(id.equals(model.audioClips.get(i).id)) {removed=model.audioClips.remove(i);break;}
        selectedAudioClipId=null;
        if(removed==null)return;
        boolean used=false;
        for(Model.AudioClip clip:model.audioClips)
            if(clip.sourcePath.equals(removed.sourcePath)){used=true;break;}
        File file=new File(removed.sourcePath);
        File folder=new File(getFilesDir(),"audio");
        if(!used && folder.equals(file.getParentFile()))file.delete();
        releaseAudio();changed();
    }
    private Model.Project model = new Model.Project();
    private DrawingView canvas;
    private TimelineView timeline;
    private TextView status;
    private Button playButton;
    private LinearLayout tabActions;
    private RadioGroup featureTabs;
    private int activeFeatureTab=1;
    private static final String KEYMAP_PREF="hardware_keymap";
    private int keymapProfile; // Standard or Animation preset.
    private int selected, currentFrame;
    private boolean playing;
    private long playbackOriginNanos;
    private int playbackOriginFrame;
    private Uri documentUri;
    private Runnable autosaveJob;
    private final Runnable playback = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            long advances=(System.nanoTime()-playbackOriginNanos)*model.fps/1_000_000_000L;
            seek((int)((playbackOriginFrame+advances)%model.totalFrames()));
            syncAudio();
            handler.postDelayed(this,RamProfile.playbackRefreshMs(lowRamEnabled));
        }
    };

    Model.Project project() { return model; }
    int selected() { return selected; }
    int currentFrame() { return currentFrame; }
    private int dp(float v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private void initMemoryProfile() {
        ActivityManager manager=(ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        if (manager!=null) manager.getMemoryInfo(info);
        physicalRamBytes=info.totalMem;
        SharedPreferences prefs=getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE);
        lowRamPreference=RamProfile.normalize(prefs.getInt(LOW_RAM_PREFERENCE,RamProfile.AUTO));
        lowRamEnabled=RamProfile.enabled(physicalRamBytes,lowRamPreference);
    }

    private void openSettings() {
        final String[] choices={"Automatic (6 GiB or less)","Always on","Always off"};
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22),dp(10),dp(22),dp(10));
        TextView description=new TextView(this);
        String measured=physicalRamBytes>0
            ? String.format(java.util.Locale.US,"%.1f GB detected",physicalRamBytes/1_000_000_000.0)
            : "Physical RAM unavailable";
        description.setText(measured+"\nCurrent: "+(lowRamEnabled?"Low RAM":"Normal")
            +"\n\nLow RAM reduces canvas resolution, decoded image size, onion previews,"
            +" undo history and playback redraws. Saved drawing detail is unchanged.");
        description.setTextColor(Color.DKGRAY);
        description.setTextSize(14);
        description.setPadding(0,0,0,dp(8));
        content.addView(description);
        RadioGroup group=new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i=0;i<choices.length;i++) {
            RadioButton radio=new RadioButton(this);
            radio.setText(choices[i]);
            radio.setId(View.generateViewId());
            group.addView(radio);
            if (i==lowRamPreference) group.check(radio.getId());
        }
        content.addView(group);
        new AlertDialog.Builder(this).setTitle("Settings • Memory")
            .setView(content)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Apply",(dialog,which)->{
                int choice=RamProfile.AUTO;
                for (int i=0;i<group.getChildCount();i++) {
                    if (group.getChildAt(i).getId()==group.getCheckedRadioButtonId()) {
                        choice=i;break;
                    }
                }
                applyMemoryPreference(choice);
            }).show();
    }

    private void applyMemoryPreference(int requested) {
        int mode=RamProfile.normalize(requested);
        boolean enabled=RamProfile.enabled(physicalRamBytes,mode);
        lowRamPreference=mode;
        getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE).edit()
            .putInt(LOW_RAM_PREFERENCE,mode).apply();
        if (enabled!=lowRamEnabled) {
            stopPlayback();
            lowRamEnabled=enabled;
            for (Deque<Model.Stroke> history:undos.values()) {
                while (history.size()>RamProfile.undoLimit(enabled)) history.removeLast();
            }
            for (Deque<Model.Stroke> history:redos.values()) {
                while (history.size()>RamProfile.undoLimit(enabled)) history.removeLast();
            }
            canvas.setLowRamMode(enabled);
            if (enabled) canvas.setOnionEnabled(false);
        }
        refresh();
        toast(enabled ? "Low RAM mode enabled" : "Normal memory mode enabled");
    }

    private void showZoom() {toast("Canvas zoom: "+Math.round(canvas.zoomLevel()*100)+"%");}
    private void toggleTextTransformMode(){
        stopPlayback();
        boolean enable=!canvas.isTextTransformMode();
        canvas.setTextTransformMode(enable);
        toast(enable?"Text: tap to select, drag to move, resize from four corners":"Text transform off");
    }
    private void togglePanMode() {
        canvas.setPanMode(!canvas.isPanMode());
        toast(canvas.isPanMode()?"Pan mode: drag with one finger":"Draw mode: drag to draw");
    }
    private void showUpdatePreferences() {
        SharedPreferences prefs=getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE);
        CheckBox automatic=new CheckBox(this);
        automatic.setText("Check GitHub for HoldFrame updates once per day");
        automatic.setChecked(prefs.getBoolean(AUTO_UPDATES_PREF,true));
        new AlertDialog.Builder(this).setTitle("Settings - Updates")
            .setView(automatic).setNegativeButton("Cancel",null)
            .setNeutralButton("Check now",(dialog,which)->checkUpdates(true))
            .setPositiveButton("Save",(dialog,which)->prefs.edit()
                .putBoolean(AUTO_UPDATES_PREF,automatic.isChecked()).apply()).show();
    }
    private void checkUpdatesOnLaunch() {
        SharedPreferences prefs=getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE);
        if(!prefs.getBoolean(AUTO_UPDATES_PREF,true))return;
        long now=System.currentTimeMillis(),last=prefs.getLong(UPDATE_CHECKED_AT,0);
        if(now-last<24L*60*60*1000 && last<=now)return;
        prefs.edit().putLong(UPDATE_CHECKED_AT,now).apply();
        checkUpdates(false);
    }
    private void checkUpdates(boolean manual) {
        AndroidUpdater.check(this, manual);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        clearTransientMemory(true);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level>=TRIM_MEMORY_RUNNING_LOW) {
            // UI_HIDDEN alone means the app is backgrounded, not necessarily out of RAM.
            // Free native bitmap caches without needlessly erasing Undo history.
            boolean pressure=level<TRIM_MEMORY_UI_HIDDEN || level>=TRIM_MEMORY_BACKGROUND;
            clearTransientMemory(pressure);
        }
    }

    private void clearTransientMemory(boolean dropHistory) {
        if (dropHistory) {
            redos.clear();
            undos.clear();
        }
        if (canvas!=null) canvas.releaseMemory();
    }


    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android 12 platform splash API (API 31); no compatibility dependency needed.
        getSplashScreen().setOnExitAnimationListener(splash -> splash.remove());
        getWindow().setStatusBarColor(Color.rgb(35,41,49));
        getWindow().setNavigationBarColor(Color.rgb(35,41,49));
        initMemoryProfile();
        keymapProfile=getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE).getInt(KEYMAP_PREF,0);
        loadAutosave();
        buildUI();
        refresh();
        checkUpdatesOnLaunch();
    }
    private Button button(String text,Runnable handler) {
        Button button=new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9),0,dp(9),0);
        button.setOnClickListener(v->handler.run());
        return button;
    }
    private void buildUI() {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(242,245,248));
        setContentView(root);
        // Feature tabs keep actions visible without horizontally overflowing toolbars.
        featureTabs=new RadioGroup(this);
        featureTabs.setOrientation(RadioGroup.HORIZONTAL);
        featureTabs.setPadding(dp(2),dp(1),dp(2),0);
        String[] names={"File","Draw","Animate","Media"};
        for(int i=0;i<names.length;i++) {
            RadioButton tab=new RadioButton(this);
            tab.setButtonDrawable((android.graphics.drawable.Drawable)null);
            tab.setText(names[i]);tab.setTextSize(12);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(2),0,dp(2),0);
            tab.setId(View.generateViewId());
            final int index=i;
            tab.setOnClickListener(v->selectFeatureTab(index));
            featureTabs.addView(tab,new RadioGroup.LayoutParams(0,dp(39),1));
            if(i==activeFeatureTab) featureTabs.check(tab.getId());
        }
        root.addView(featureTabs,new LinearLayout.LayoutParams(-1,dp(40)));
        tabActions=new LinearLayout(this);
        tabActions.setGravity(Gravity.CENTER_VERTICAL);
        tabActions.setPadding(dp(3),dp(1),dp(3),dp(2));
        root.addView(tabActions,new LinearLayout.LayoutParams(-1,dp(44)));
        selectFeatureTab(activeFeatureTab);
        FrameLayout workArea=new FrameLayout(this);
        canvas=new DrawingView(this);
        SharedPreferences brushPrefs=getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE);
        if(brushPrefs.getBoolean(KMBS_ENABLED,true))
            canvas.setBrushWidth(Math.max(1,Math.min(60,brushPrefs.getInt(KMBS_SIZE,6))));
        canvas.setLowRamMode(lowRamEnabled);
        canvas.setListener(new DrawingView.Listener() {
            @Override public void startStroke() {
                stopPlayback();
                canvas.setDrawing(model.ensureEditable(selected));
            }
            @Override public void endStroke() {
                Model.Drawing drawing = model.drawingAt(selected);
                if (!drawing.strokes.isEmpty()) {
                    Deque<Model.Stroke> history = undos.computeIfAbsent(drawing.id, key -> new ArrayDeque<>());
                    history.push(drawing.strokes.get(drawing.strokes.size()-1));
                    while (history.size()>RamProfile.undoLimit(lowRamEnabled)) history.removeLast();
                }
                recordAction(drawing.id,HISTORY_STROKE);
                changed();
            }
            @Override public void beginTextTransform(){
                stopPlayback();
                canvas.setDrawing(model.ensureEditable(selected));
            }
            @Override public void endTextTransform(int index,Model.TextItem before,Model.TextItem after){
                recordTextEdit(model.drawingAt(selected),index,before,after);
                changed();
            }
        });
        workArea.addView(canvas,new FrameLayout.LayoutParams(-1,-1));
        Button floating=button("✎ Tools",()->{});
        floating.setContentDescription("Floating drawing tools");
        floating.setOnClickListener(v->openTools(floating));
        FrameLayout.LayoutParams floatingLayout=new FrameLayout.LayoutParams(-2,dp(46),Gravity.END|Gravity.TOP);
        floatingLayout.setMargins(dp(8),dp(10),dp(10),0);
        workArea.addView(floating,floatingLayout);
        root.addView(workArea,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout player=new LinearLayout(this);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.addView(button("◀",()->seek(currentFrame-1)));
        playButton=button("▶ Play",this::togglePlayback);
        player.addView(playButton);
        player.addView(button("▶",()->seek(currentFrame+1)));
        status=new TextView(this);
        status.setTextSize(11);
        status.setTextColor(Color.rgb(30,39,51));
        status.setPadding(dp(6),0,dp(6),0);
        player.addView(status,new LinearLayout.LayoutParams(0,dp(42),1));
        root.addView(player,new LinearLayout.LayoutParams(-1,dp(49)));
        HorizontalScrollView timelineScroll=new HorizontalScrollView(this);
        timelineScroll.setFillViewport(true);
        timelineScroll.setHorizontalScrollBarEnabled(true);
        timeline=new TimelineView(this,this);
        timelineScroll.addView(timeline,new HorizontalScrollView.LayoutParams(dp(400),dp(146)));
        root.addView(timelineScroll,new LinearLayout.LayoutParams(-1,dp(155)));
    }
    private void actionButton(String text,Runnable action) {
        Button b=button(text,action);
        b.setTextSize(11);b.setPadding(dp(2),0,dp(2),0);
        tabActions.addView(b,new LinearLayout.LayoutParams(0,dp(43),1));
    }
    private void selectFeatureTab(int tab) {
        activeFeatureTab=Math.max(0,Math.min(3,tab));
        if(tabActions==null)return;
        if(featureTabs!=null && featureTabs.getCheckedRadioButtonId()!=
           featureTabs.getChildAt(activeFeatureTab).getId())
            featureTabs.check(featureTabs.getChildAt(activeFeatureTab).getId());
        for(int i=0;i<featureTabs.getChildCount();i++) {
            RadioButton button=(RadioButton)featureTabs.getChildAt(i);
            button.setTextColor(i==activeFeatureTab?Color.rgb(22,97,174):Color.rgb(40,50,64));
            button.setBackgroundColor(i==activeFeatureTab?Color.WHITE:Color.rgb(225,232,239));
        }
        tabActions.removeAllViews();
        switch(activeFeatureTab) {
            case 0:
                actionButton("New",this::newProject);
                actionButton("Open",this::openDocument);
                actionButton("Save",this::saveDocument);
                actionButton("More ▾",()->showFeatureMenu(0));
                break;
            case 1:
                actionButton(canvas!=null && canvas.isEraser()?"Pen":"Eraser",()->{
                    canvas.setEraser(!canvas.isEraser());selectFeatureTab(1);
                });
                actionButton("Color",this::pickColor);
                actionButton("Size",this::pickSize);
                actionButton("More ▾",()->showFeatureMenu(1));
                break;
            case 2:
                actionButton("+ Pose",()->add(false));
                actionButton("Copy",this::copyPose);
                actionButton("Paste",this::pastePose);
                actionButton("More ▾",()->showFeatureMenu(2));
                break;
            default:
                actionButton("+ Text",()->editText(-1));
                actionButton("+ Image",this::importImage);
                actionButton("+ Audio",this::importAudio);
                actionButton("More ▾",()->showFeatureMenu(3));
        }
    }
    private void showFeatureMenu(int feature) {
        View anchor=tabActions.getChildAt(tabActions.getChildCount()-1);
        PopupMenu menu=new PopupMenu(this,anchor);
        switch(feature) {
            case 0:
                menu.getMenu().add("Save as…").setOnMenuItemClickListener(i->{createDocument();return true;});
                menu.getMenu().add("Memory settings").setOnMenuItemClickListener(i->{openSettings();return true;});
                menu.getMenu().add("Keep My Brush Size (KMBS)").setOnMenuItemClickListener(i->{showKmbsSettings();return true;});
                menu.getMenu().add("Keyboard keymaps").setOnMenuItemClickListener(i->{showKeymapSettings();return true;});
                menu.getMenu().add("Check for updates").setOnMenuItemClickListener(i->{checkUpdates(true);return true;});
                menu.getMenu().add("Update preferences").setOnMenuItemClickListener(i->{showUpdatePreferences();return true;});
                break;
            case 1:
                menu.getMenu().add("Import font").setOnMenuItemClickListener(i->{importFont();return true;});
                menu.getMenu().add("Toggle onion skins").setOnMenuItemClickListener(i->{canvas.setOnionEnabled(!canvas.isOnionEnabled());return true;});
                menu.getMenu().add(canvas.isTextTransformMode()?"Finish text transform":"Move / resize text")
                    .setOnMenuItemClickListener(i->{toggleTextTransformMode();return true;});
                menu.getMenu().add("Edit existing text").setOnMenuItemClickListener(i->{chooseText();return true;});
                menu.getMenu().add("Zoom in").setOnMenuItemClickListener(i->{canvas.changeZoom(1.25f);showZoom();return true;});
                menu.getMenu().add("Zoom out").setOnMenuItemClickListener(i->{canvas.changeZoom(.8f);showZoom();return true;});
                menu.getMenu().add("Fit canvas").setOnMenuItemClickListener(i->{canvas.resetZoom();showZoom();return true;});
                menu.getMenu().add("Pan canvas (toggle)").setOnMenuItemClickListener(i->{togglePanMode();return true;});
                break;
            case 2:
                menu.getMenu().add("Duplicate pose").setOnMenuItemClickListener(i->{add(true);return true;});
                menu.getMenu().add("Split at playhead").setOnMenuItemClickListener(i->{splitAtPlayhead();return true;});
                menu.getMenu().add("Copy range…").setOnMenuItemClickListener(i->{copyRangeDialog();return true;});
                menu.getMenu().add("Delete pose").setOnMenuItemClickListener(i->{delete();return true;});
                menu.getMenu().add("Undo").setOnMenuItemClickListener(i->{undo();return true;});
                menu.getMenu().add("Redo").setOnMenuItemClickListener(i->{redo();return true;});
                break;
            default:
                menu.getMenu().add(canvas.isTextTransformMode()?"Finish text transform":"Move / resize text")
                    .setOnMenuItemClickListener(i->{toggleTextTransformMode();return true;});
                menu.getMenu().add("Edit selected text").setOnMenuItemClickListener(i->{
                    int index=canvas.selectedTextIndex();
                    if(index>=0)editText(index);else chooseText();return true;
                });
                menu.getMenu().add("Delete selected text").setOnMenuItemClickListener(i->{deleteSelectedText();return true;});
                menu.getMenu().add("Edit text").setOnMenuItemClickListener(i->{chooseText();return true;});
                menu.getMenu().add("Move/resize image").setOnMenuItemClickListener(i->{editImagePlacement();return true;});
                menu.getMenu().add("Remove audio").setOnMenuItemClickListener(i->{removeAudio();return true;});
                menu.getMenu().add("Split audio at playhead").setOnMenuItemClickListener(i->{splitAudioAtPlayhead();return true;});
                menu.getMenu().add("Import font").setOnMenuItemClickListener(i->{importFont();return true;});
        }
        menu.show();
    }
    private void showKeymapSettings() {
        final String[] names={"Standard: Ctrl+ shortcuts","Animation: J/K/L and [ / ]"};
        new AlertDialog.Builder(this).setTitle("Hardware keyboard keymaps")
            .setSingleChoiceItems(names,keymapProfile,(dialog,choice)->{
                keymapProfile=choice;
                getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE).edit()
                    .putInt(KEYMAP_PREF,choice).apply();
                dialog.dismiss();
                showKeymapReference();
            })
            .setNeutralButton("Show shortcuts",(dialog,which)->showKeymapReference())
            .setNegativeButton("Cancel",null).show();
    }
    private void showKeymapReference() {
        String common="Ctrl+S Save · Ctrl+O Open · Ctrl+N New project\n"
            +"Ctrl+C Copy · Ctrl+V Paste · Ctrl+D Duplicate\n"
            +"Ctrl+Z Undo · Ctrl+Y Redo · Ctrl+T Transform Text · Ctrl+B Split · Ctrl+1–4 Tabs\n"
            +"←/→ Step frame · Space Play/Pause · Esc Deselect";
        String extra="\n\nAnimation preset: J Add pose · K Duplicate · L Play/Pause · [ / ] Step frame";
        new AlertDialog.Builder(this).setTitle("Keyboard shortcuts")
            .setMessage(common+(keymapProfile==1?extra:""))
            .setPositiveButton("OK",null).show();
    }
    private void openTools(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("Settings / Low RAM mode").setOnMenuItemClickListener(item->{openSettings();return true;});
        menu.getMenu().add("Check for updates").setOnMenuItemClickListener(item->{checkUpdates(true);return true;});
        menu.getMenu().add("Zoom +").setOnMenuItemClickListener(item->{canvas.changeZoom(1.25f);showZoom();return true;});
        menu.getMenu().add("Zoom -").setOnMenuItemClickListener(item->{canvas.changeZoom(.8f);showZoom();return true;});
        menu.getMenu().add("Fit canvas").setOnMenuItemClickListener(item->{canvas.resetZoom();showZoom();return true;});
        menu.getMenu().add("Pan canvas (toggle)").setOnMenuItemClickListener(item->{togglePanMode();return true;});
        menu.getMenu().add("Brush color").setOnMenuItemClickListener(item->{pickColor();return true;});
        menu.getMenu().add("Add text").setOnMenuItemClickListener(item->{editText(-1);return true;});
        menu.getMenu().add("Import image").setOnMenuItemClickListener(item->{importImage();return true;});
        menu.getMenu().add("Add audio").setOnMenuItemClickListener(item->{importAudio();return true;});
        menu.getMenu().add("Remove audio").setOnMenuItemClickListener(item->{removeAudio();return true;});
        menu.getMenu().add("Edit image placement").setOnMenuItemClickListener(item->{editImagePlacement();return true;});
        menu.getMenu().add(canvas.isTextTransformMode()?"Finish text transform":"Move / resize text")
            .setOnMenuItemClickListener(item->{toggleTextTransformMode();return true;});
        menu.getMenu().add("Edit existing text").setOnMenuItemClickListener(item->{chooseText();return true;});
        menu.getMenu().add("Import font (.ttf/.otf)")
            .setOnMenuItemClickListener(item->{importFont();return true;});
        menu.getMenu().add(canvas.isEraser()?"Use pen":"Use eraser").setOnMenuItemClickListener(item->{
            canvas.setEraser(!canvas.isEraser());return true;
        });
        menu.getMenu().add("Brush size").setOnMenuItemClickListener(item->{pickSize();return true;});
        menu.getMenu().add("Duplicate pose").setOnMenuItemClickListener(item->{add(true);return true;});
        menu.getMenu().add("Copy pose").setOnMenuItemClickListener(item->{copyPose();return true;});
        menu.getMenu().add("Copy pose range…").setOnMenuItemClickListener(item->{copyRangeDialog();return true;});
        menu.getMenu().add("Paste pose").setOnMenuItemClickListener(item->{pastePose();return true;});
        menu.getMenu().add(canvas.isOnionEnabled()?"Hide onion skins":"Show onion skins")
            .setOnMenuItemClickListener(item->{canvas.setOnionEnabled(!canvas.isOnionEnabled());return true;});
        menu.getMenu().add("New pose").setOnMenuItemClickListener(item->{add(false);return true;});
        menu.show();
    }
    private void deleteSelectedText() {
        Model.Drawing current=model.drawingAt(selected);
        int index=canvas.selectedTextIndex();
        if(index<0 || index>=current.texts.size()) {
            if(current.texts.isEmpty())toast("No text in this pose");
            else chooseDeleteText();
            return;
        }
        deleteTextAt(index);
    }
    private void chooseDeleteText() {
        Model.Drawing drawing=model.drawingAt(selected);
        if(drawing.texts.isEmpty()){toast("No text to delete");return;}
        String[] labels=new String[drawing.texts.size()];
        for(int i=0;i<labels.length;i++) {
            String label=drawing.texts.get(i).value.replace('\n',' ');
            labels[i]=label.length()>45?label.substring(0,45)+"…":label;
        }
        new AlertDialog.Builder(this).setTitle("Delete text")
            .setItems(labels,(dialog,index)->deleteTextAt(index))
            .setNegativeButton("Cancel",null).show();
    }
    private void deleteTextAt(int index) {
        stopPlayback();
        Model.Drawing drawing=model.ensureEditable(selected);
        if(index<0||index>=drawing.texts.size())return;
        Model.TextItem before=drawing.texts.remove(index);
        recordTextEdit(drawing,index,before,null);
        canvas.setDrawing(drawing);
        canvas.rebuild();
        changed();
        toast("Selected text deleted (Undo to restore)");
    }
    private void chooseText() {
        Model.Drawing d=model.drawingAt(selected);
        if (d.texts.isEmpty()) {toast("No text in this pose");return;}
        String[] names=new String[d.texts.size()];
        for (int i=0;i<names.length;i++) {
            String value=d.texts.get(i).value.replace('\n',' ');
            names[i]=value.length()>45 ? value.substring(0,45)+"…" : value;
        }
        new AlertDialog.Builder(this).setTitle("Edit text")
            .setItems(names,(dialog,which)->editText(which)).show();
    }
    private void editText(int index) {
        Model.Drawing d=model.drawingAt(selected);
        if (index>=d.texts.size()) return;
        Model.TextItem existing=index<0 ? null : d.texts.get(index);
        LinearLayout form=new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22),dp(8),dp(22),0);
        EditText value=new EditText(this);
        value.setHint("Text (multiple lines allowed)");
        value.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        value.setMinLines(2);
        value.setMaxLines(6);
        value.setText(existing==null ? "Text" : existing.value);
        form.addView(value);
        EditText family=new EditText(this);
        family.setSingleLine(true);
        family.setHint("Installed font family (e.g. sans-serif)");
        family.setText(existing==null ? "sans-serif" : existing.fontFamily);
        form.addView(family);
        EditText size=new EditText(this);
        size.setSingleLine(true);
        size.setInputType(InputType.TYPE_CLASS_NUMBER);
        size.setHint("Text size");
        size.setText(String.valueOf(Math.round(existing==null?48:existing.size)));
        form.addView(size);
        CheckBox bold=new CheckBox(this);
        bold.setText("Bold");
        bold.setChecked(existing!=null && (existing.style & 1)!=0);
        form.addView(bold);
        CheckBox italic=new CheckBox(this);
        italic.setText("Italic");
        italic.setChecked(existing!=null && (existing.style & 2)!=0);
        form.addView(italic);
        String[] colorNames={"Black","White","Red","Orange","Green","Blue","Purple"};
        int[] colors={Color.BLACK,Color.WHITE,0xFFEB5353,0xFFFFA522,0xFF36B76B,0xFF318DF5,0xFF9455D4};
        Spinner color=new Spinner(this);
        color.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,colorNames));
        if (existing!=null) {
            for (int i=0;i<colors.length;i++) if (colors[i]==existing.color) color.setSelection(i);
        }
        form.addView(color);
        new AlertDialog.Builder(this).setTitle(index<0?"Add text":"Edit text")
            .setView(form)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Apply",(dialog,which)->{
                String content=value.getText().toString();
                if (content.isEmpty() || content.length()>10000) {
                    toast("Text must contain 1–10000 characters");return;
                }
                float textSize;
                try {textSize=Float.parseFloat(size.getText().toString());}
                catch (NumberFormatException e) {toast("Invalid font size");return;}
                if (!(textSize>=1f && textSize<=4096f)) {toast("Text size must be 1–4096");return;}
                stopPlayback();
                Model.Drawing editable=model.ensureEditable(selected);
                int style=(bold.isChecked()?1:0)|(italic.isChecked()?2:0);
                String chosenFamily=family.getText().toString().trim();
                if (chosenFamily.isEmpty()) chosenFamily="sans-serif";
                Model.TextItem old=index<0?null:editable.texts.get(index).copy();
                int textIndex=index<0?editable.texts.size():index;
                if (index<0) {
                    Model.TextItem item=new Model.TextItem(content,chosenFamily,importedFontPath,
                        style,colors[color.getSelectedItemPosition()],textSize,140,120);
                    editable.texts.add(item);
                } else {
                    Model.TextItem item=editable.texts.get(index);
                    item.value=content;
                    item.fontFamily=chosenFamily;
                    item.size=textSize;
                    item.style=style;
                    item.color=colors[color.getSelectedItemPosition()];
                    // Keep an existing imported font unless the user imports a new one.
                    if (!importedFontPath.isEmpty()) item.fontPath=importedFontPath;
                }
                recordTextEdit(editable,textIndex,old,editable.texts.get(textIndex));
                canvas.setDrawing(editable);
                canvas.rebuild();
                changed();
                canvas.selectText(textIndex);
            }).show();
    }
    private void importFont() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent,IMPORT_FONT);
    }
    private void readImportedFont(Uri uri) {
        String name="font.ttf";
        try (Cursor cursor=getContentResolver().query(uri,null,null,null,null)) {
            if (cursor!=null && cursor.moveToFirst()) {
                int column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column>=0) name=cursor.getString(column);
            }
        } catch (RuntimeException ignored) { }
        if (name==null) name="font.ttf";
        String lower=name.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !lower.endsWith(".ttc")) {
            toast("Choose a .ttf, .otf or .ttc font");return;
        }
        String extension=lower.substring(lower.lastIndexOf('.'));
        File folder=new File(getFilesDir(),"fonts");
        if (!folder.exists() && !folder.mkdirs()) {toast("Cannot create font directory");return;}
        File output=new File(folder,"font-"+System.nanoTime()+extension);
        try (InputStream input=getContentResolver().openInputStream(uri);
             OutputStream destination=new FileOutputStream(output)) {
            if (input==null) throw new IOException("Cannot open font");
            byte[] buffer=new byte[8192]; int length; long total=0;
            while ((length=input.read(buffer))!=-1) {
                total+=length;
                if (total>25_000_000L) throw new IOException("Font exceeds 25 MB");
                destination.write(buffer,0,length);
            }
            android.graphics.Typeface.createFromFile(output);
            importedFontPath=output.getAbsolutePath();
            toast("Font imported. Add text to use it.");
        } catch (Exception e) {
            output.delete();
            toast("Cannot import font: "+e.getMessage());
        }
    }
    private void pickColor() {
        String[] labels={"Black","White","Red","Orange","Green","Blue","Purple"};
        int[] colors={Color.BLACK,Color.WHITE,0xFFEB5353,0xFFFFA522,0xFF36B76B,0xFF318DF5,0xFF9455D4};
        new AlertDialog.Builder(this).setTitle("Brush color")
            .setItems(labels,(dialog,which)->canvas.setBrushColor(colors[which])).show();
    }
    private void showKmbsSettings() {
        SharedPreferences prefs=getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE);
        CheckBox keep=new CheckBox(this);
        keep.setText("Remember my brush size across sessions");
        keep.setChecked(prefs.getBoolean(KMBS_ENABLED,true));
        new AlertDialog.Builder(this).setTitle("Keep My Brush Size (KMBS)")
            .setView(keep).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(d,w)->{
                SharedPreferences.Editor editor=prefs.edit().putBoolean(KMBS_ENABLED,keep.isChecked());
                if(keep.isChecked())editor.putInt(KMBS_SIZE,Math.round(canvas.getBrushWidth()));
                editor.apply();
            }).show();
    }
    private void deselectAll() {
        selectedFrom=selectedTo=-1;
        selectedAudioClipId=null;timelineClipSelected=false;
        if(canvas!=null)canvas.deselectText();
        if(timeline!=null)timeline.invalidate();
    }
    private void pickSize() {
        SeekBar slider=new SeekBar(this);
        slider.setMax(59);
        slider.setProgress(Math.max(0,Math.min(59,Math.round(canvas.getBrushWidth())-1)));
        new AlertDialog.Builder(this).setTitle("Brush size (1–60)")
            .setView(slider).setPositiveButton("Apply",(dialog,which)->{
                int size=slider.getProgress()+1;
                canvas.setBrushWidth(size);
                SharedPreferences prefs=getSharedPreferences(SETTINGS_PREFS,MODE_PRIVATE);
                if(prefs.getBoolean(KMBS_ENABLED,true))prefs.edit().putInt(KMBS_SIZE,size).apply();
            })
            .setNegativeButton("Cancel",null).show();
    }
    private void refresh() {
        if (canvas==null) return;
        canvas.setImageAssets(model.imageAssets);
        canvas.setDrawing(model.drawingAt(selected));
        canvas.setOnionNeighbors(selected>0?model.drawingAt(selected-1):null,
                                 selected<model.timeline.size()-1?model.drawingAt(selected+1):null);
        timeline.refresh();
        status.setText(String.format(java.util.Locale.US,"Pose %d/%d   %d/%d frames   %.1fs",
            selected+1,model.timeline.size(),currentFrame+1,model.totalFrames(),
            model.totalFrames()/(float)model.fps)+" • "+model.audioClips.size()+" audio"
            +(lowRamEnabled ? " • Low RAM" : ""));
    }
    void seek(int frame) {
        int next=Math.max(0,Math.min(model.totalFrames()-1,frame));
        selected=model.indexAt(next);
        currentFrame=next;
        selectedFrom=selectedTo=-1;
        refresh();
        timeline.followFrame(currentFrame);
    }
    void extendClip(int clipIndex,int frames) {
        int original=model.timeline.get(clipIndex).duration;
        model.extend(clipIndex,frames);
        if (original==model.timeline.get(clipIndex).duration) return;
        selected=clipIndex;
        currentFrame=model.clipStart(clipIndex)+Math.min(model.timeline.get(clipIndex).duration-1,
                         Math.max(0,currentFrame-model.clipStart(clipIndex)));
        changed();
    }
    private void add(boolean duplicate) {
        stopPlayback();
        selected=model.addAfter(selected,duplicate);
        selectedFrom=selectedTo=-1;
        currentFrame=model.clipStart(selected);
        changed();
    }
    private void copyPose() {
        clipboard=model.copyRange(selected,selected);
        selectedFrom=selectedTo=selected;
        toast("Pose copied — paste as many times as needed");
        refresh();
    }
    private void copyRangeDialog() {
        // On touchscreens, two pose numbers are more precise than tiny resize handles.
        LinearLayout fields=new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24),dp(6),dp(24),0);
        EditText first=new EditText(this),last=new EditText(this);
        first.setInputType(InputType.TYPE_CLASS_NUMBER);
        last.setInputType(InputType.TYPE_CLASS_NUMBER);
        first.setHint("First pose (1–"+model.timeline.size()+")");
        last.setHint("Last pose (1–"+model.timeline.size()+")");
        first.setText(String.valueOf(selected+1));
        last.setText(String.valueOf(selected+1));
        fields.addView(first); fields.addView(last);
        new AlertDialog.Builder(this).setTitle("Copy a sequence of poses")
            .setView(fields).setNegativeButton("Cancel",null)
            .setPositiveButton("Copy",(d,w)->{
                try {
                    int from=Integer.parseInt(first.getText().toString())-1;
                    int to=Integer.parseInt(last.getText().toString())-1;
                    if (from<0 || from>to || to>=model.timeline.size()) {
                        toast("Enter a valid first and last pose"); return;
                    }
                    clipboard=model.copyRange(from,to);
                    selectedFrom=from;selectedTo=to;
                    toast("Copied "+clipboard.size()+" poses. Paste repeatedly to reuse them.");
                    refresh();
                } catch (NumberFormatException e) {toast("Enter pose numbers");}
            }).show();
    }
    boolean poseSelected(int i) {
        return selectedFrom>=0 && i>=selectedFrom && i<=selectedTo;
    }
    private void pastePose() {
        if (clipboard==null) { toast("Copy a pose or a range first"); return; }
        stopPlayback();
        try {
            int first=selected+1;
            int last=model.pasteSequenceAfter(selected,clipboard);
            selectedFrom=first; selectedTo=last;
            selected=last;
            currentFrame=model.clipStart(selected);
            changed();
        } catch (IllegalStateException | IllegalArgumentException e) {
            toast("Cannot paste: "+e.getMessage());
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(event.getAction()!=KeyEvent.ACTION_DOWN || event.getRepeatCount()>0)
            return super.dispatchKeyEvent(event);
        if(getCurrentFocus() instanceof EditText)
            return super.dispatchKeyEvent(event);
        int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed() && !event.isAltPressed()) {
            deselectAll();return true;
        }
        if(event.isCtrlPressed()) {
            switch(code) {
                case KeyEvent.KEYCODE_1: selectFeatureTab(0);return true;
                case KeyEvent.KEYCODE_2: selectFeatureTab(1);return true;
                case KeyEvent.KEYCODE_3: selectFeatureTab(2);return true;
                case KeyEvent.KEYCODE_4: selectFeatureTab(3);return true;
                case KeyEvent.KEYCODE_C: copyPose();return true;
                case KeyEvent.KEYCODE_V: pastePose();return true;
                case KeyEvent.KEYCODE_D: add(true);return true;
                case KeyEvent.KEYCODE_B: splitAtPlayhead();return true;
                case KeyEvent.KEYCODE_N: newProject();return true;
                case KeyEvent.KEYCODE_O: openDocument();return true;
                case KeyEvent.KEYCODE_S: saveDocument();return true;
                case KeyEvent.KEYCODE_Z: undo();return true;
                case KeyEvent.KEYCODE_T: toggleTextTransformMode();return true;
                case KeyEvent.KEYCODE_DEL: deleteSelection();return true;
                case KeyEvent.KEYCODE_EQUALS:
                case KeyEvent.KEYCODE_PLUS: canvas.changeZoom(1.25f);return true;
                case KeyEvent.KEYCODE_MINUS: canvas.changeZoom(.8f);return true;
                case KeyEvent.KEYCODE_0: canvas.resetZoom();return true;
                case KeyEvent.KEYCODE_Y: redo();return true;
                default: break;
            }
        } else if(!event.isAltPressed() && !event.isShiftPressed()) {
            switch(code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: seek(currentFrame-1);return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: seek(currentFrame+1);return true;
                case KeyEvent.KEYCODE_SPACE: togglePlayback();return true;
                case KeyEvent.KEYCODE_FORWARD_DEL: deleteSelection();return true;
                case KeyEvent.KEYCODE_DEL: deleteSelection();return true;
                default: break;
            }
            if(keymapProfile==1) {
                switch(code) {
                    case KeyEvent.KEYCODE_J: add(false);return true;
                    case KeyEvent.KEYCODE_K: add(true);return true;
                    case KeyEvent.KEYCODE_L: togglePlayback();return true;
                    case KeyEvent.KEYCODE_LEFT_BRACKET: seek(currentFrame-1);return true;
                    case KeyEvent.KEYCODE_RIGHT_BRACKET: seek(currentFrame+1);return true;
                    default: break;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }
    /** Divide an exposure at the exact current frame, retaining its audio timing. */
    private void splitAtPlayhead() {
        stopPlayback();
        try {
            selected=model.splitAt(currentFrame);
            selectedFrom=selectedTo=-1;
            changed();
            timeline.followFrame(currentFrame);
            toast("Split — edit each half separately");
        } catch(IllegalArgumentException|IllegalStateException ex) {
            toast("Cannot split: "+ex.getMessage());
        }
    }
    private void delete() {
        if (model.timeline.size()==1) {toast("Keep at least one drawing");return;}
        stopPlayback();
        model.remove(selected);
        selectedFrom=selectedTo=-1;
        selected=Math.min(selected,model.timeline.size()-1);
        currentFrame=model.clipStart(selected);
        changed();
    }
    private void undo(){
        stopPlayback();
        Model.Drawing drawing=model.drawingAt(selected);
        Deque<Integer> order=undoOrder.get(drawing.id);
        int kind=order==null||order.isEmpty()?HISTORY_STROKE:order.peek();
        if(kind==HISTORY_TEXT){
            Deque<TextEdit> h=textUndos.get(drawing.id);
            if(h==null||h.isEmpty())return;
            TextEdit edit=h.pop();
            if(order!=null&&!order.isEmpty())order.pop();
            Model.Drawing editable=model.ensureEditable(selected);
            applyTextEdit(editable,edit,true);
            textRedos.computeIfAbsent(editable.id,k->new ArrayDeque<>()).push(edit);
            Deque<Integer> target=redoOrder.computeIfAbsent(editable.id,k->new ArrayDeque<>());
            target.push(HISTORY_TEXT);trimHistory(editable.id,target,true);
        } else {
            Deque<Model.Stroke> h=undos.get(drawing.id);
            if(h==null||h.isEmpty())return;
            Model.Stroke stroke=h.pop();
            if(!drawing.strokes.remove(stroke))return;
            if(order!=null&&!order.isEmpty())order.pop();
            redos.computeIfAbsent(drawing.id,k->new ArrayDeque<>()).push(stroke);
            Deque<Integer> target=redoOrder.computeIfAbsent(drawing.id,k->new ArrayDeque<>());
            target.push(HISTORY_STROKE);trimHistory(drawing.id,target,true);
            canvas.rebuild();
        }
        changed();
    }
    private void redo(){
        stopPlayback();
        Model.Drawing drawing=model.drawingAt(selected);
        Deque<Integer> order=redoOrder.get(drawing.id);
        int kind=order==null||order.isEmpty()?HISTORY_STROKE:order.peek();
        if(kind==HISTORY_TEXT){
            Deque<TextEdit> h=textRedos.get(drawing.id);
            if(h==null||h.isEmpty())return;
            TextEdit edit=h.pop();
            if(order!=null&&!order.isEmpty())order.pop();
            Model.Drawing editable=model.ensureEditable(selected);
            applyTextEdit(editable,edit,false);
            textUndos.computeIfAbsent(editable.id,k->new ArrayDeque<>()).push(edit);
            Deque<Integer> target=undoOrder.computeIfAbsent(editable.id,k->new ArrayDeque<>());
            target.push(HISTORY_TEXT);trimHistory(editable.id,target,false);
        } else {
            Deque<Model.Stroke> h=redos.get(drawing.id);
            if(h==null||h.isEmpty())return;
            Model.Stroke stroke=h.pop();
            if(order!=null&&!order.isEmpty())order.pop();
            drawing.strokes.add(stroke);
            undos.computeIfAbsent(drawing.id,k->new ArrayDeque<>()).push(stroke);
            Deque<Integer> target=undoOrder.computeIfAbsent(drawing.id,k->new ArrayDeque<>());
            target.push(HISTORY_STROKE);trimHistory(drawing.id,target,false);
            canvas.rebuild();
        }
        changed();
    }
    private void togglePlayback() {
        if (playing) {stopPlayback();return;}
        playing=true;
        playbackOriginFrame=currentFrame;
        playbackOriginNanos=System.nanoTime();
        playButton.setText("■ Stop");
        canvas.setOnionPlaying(true);
        timeline.followFrame(currentFrame);
        syncAudio();
        handler.postDelayed(playback,16);
    }
    void stopPlayback() {
        releaseAudio();
        if (!playing) return;
        playing=false;
        handler.removeCallbacks(playback);
        playButton.setText("▶ Play");
        canvas.setOnionPlaying(false);
    }
    private void releaseAudio() {
        if (audioPlayer!=null) {
            try { audioPlayer.release(); } catch (RuntimeException ignored) {}
            audioPlayer=null;
        }
        playingAudioId=null;
    }
    private void syncAudio() {
        // One active compressed audio stream at a time: optimized for older low-RAM phones.
        Model.AudioClip clip=null;
        for (Model.AudioClip candidate:model.audioClips) {
            if (!candidate.muted && candidate.volume>0 &&
                currentFrame>=candidate.startFrame && currentFrame<candidate.endFrame()) {
                clip=candidate;break;
            }
        }
        if (clip==null) {releaseAudio();return;}
        if (playingAudioId!=null && playingAudioId.equals(clip.id) && audioPlayer!=null) {
            try { if (audioPlayer.isPlaying()) return; }
            catch (IllegalStateException ignored) {}
        }
        releaseAudio();
        try {
            MediaPlayer player=new MediaPlayer();
            player.setDataSource(clip.sourcePath);
            player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            player.prepare();
            long at=clip.sourceOffsetMillis+Math.round(
                (currentFrame-clip.startFrame)*1000d/model.fps);
            player.seekTo((int)Math.min(Integer.MAX_VALUE,Math.max(0,at)));
            player.setVolume(clip.volume,clip.volume);
            player.start();
            audioPlayer=player;
            playingAudioId=clip.id;
        } catch (Exception e) {
            releaseAudio();
            android.util.Log.w("HoldFrame","Cannot play audio",e);
        }
    }
    private void changed() {
        refresh();
        if (autosaveJob!=null) handler.removeCallbacks(autosaveJob);
        autosaveJob=this::writeAutosave;
        handler.postDelayed(autosaveJob,RamProfile.autosaveDelayMs(lowRamEnabled));
    }
    private File autosaveFile() {return new File(getFilesDir(),"autosave.holdframe");}
    private void writeAutosave() {
        File file=autosaveFile();
        File temp=new File(getFilesDir(),"autosave.holdframe.tmp");
        try {
            try (FileOutputStream stream=new FileOutputStream(temp)) {
                ProjectIO.write(model,stream);
                stream.getFD().sync();
            }
            try {
                Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,
                           StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            toast("Autosave failed: "+e.getMessage());
            temp.delete();
        }
    }
    private void loadAutosave() {
        File file=autosaveFile();
        if (!file.isFile()) return;
        try (FileInputStream input=new FileInputStream(file)) {
            model=ProjectIO.read(input);
        } catch (IOException e) { android.util.Log.w("HoldFrame","Autosave could not be opened",e); }
    }
    private void newProject() {
        stopPlayback();
        new AlertDialog.Builder(this).setTitle("New project")
            .setMessage("Start a new project? Save the current project to a file first if you want to keep it.")
            .setNegativeButton("Cancel",null).setPositiveButton("Create",(d,w)->{
                model=new Model.Project(); redos.clear(); undos.clear();textUndos.clear();textRedos.clear();undoOrder.clear();redoOrder.clear(); documentUri=null;
                selected=currentFrame=0;selectedFrom=selectedTo=-1;
                canvas.setDrawing(null);
                changed();
            }).show();
    }
    private void createDocument() {
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,"animation.holdframe");
        startActivityForResult(intent,CREATE_DOCUMENT);
    }
    private void saveDocument() {
        if (documentUri==null) {createDocument();return;}
        try (OutputStream stream=getContentResolver().openOutputStream(documentUri,"wt")) {
            if (stream==null) throw new IOException("Could not open document");
            ProjectIO.write(model,stream);
            toast("Project saved");
        } catch (IOException e) { new AlertDialog.Builder(this).setTitle("Save failed")
                .setMessage(e.getMessage()).setPositiveButton("OK",null).show(); }
    }
    private void openDocument() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent,OPEN_DOCUMENT);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (resultCode!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if (requestCode==CREATE_DOCUMENT) {documentUri=uri;saveDocument();}
        else if (requestCode==IMPORT_FONT) {
            readImportedFont(uri);
        } else if (requestCode==IMPORT_IMAGE) {
            readImportedImage(uri);
        } else if (requestCode==IMPORT_AUDIO) {
            readImportedAudio(uri);
        } else if (requestCode==OPEN_DOCUMENT) {
            try (InputStream stream=getContentResolver().openInputStream(uri)) {
                if (stream==null) throw new IOException("Could not open document");
                Model.Project loaded=ProjectIO.read(stream);
                stopPlayback();model=loaded;documentUri=uri;redos.clear(); undos.clear();textUndos.clear();textRedos.clear();undoOrder.clear();redoOrder.clear();selected=currentFrame=0;selectedFrom=selectedTo=-1;
                canvas.setDrawing(null);changed();
                toast("Project opened");
            } catch (IOException e) {
                new AlertDialog.Builder(this).setTitle("Open failed").setMessage(e.getMessage())
                    .setPositiveButton("OK",null).show();
            }
        }
    }
    private void importImage() { openMediaPicker("image/*",IMPORT_IMAGE); }
    private void importAudio() { openMediaPicker("audio/*",IMPORT_AUDIO); }
    private void openMediaPicker(String type,int request) {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent,request);
    }
    private static byte[] readLimited(InputStream stream,int limit) throws IOException {
        if (stream==null) throw new IOException("Cannot read selected file");
        try (InputStream input=stream; ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int read;
            while ((read=input.read(buffer))!=-1) {
                if (read>limit-output.size()) throw new IOException("File exceeds import limit");
                output.write(buffer,0,read);
            }
            return output.toByteArray();
        }
    }
    private void readImportedImage(Uri uri) {
        try {
            byte[] bytes=readLimited(getContentResolver().openInputStream(uri),
                RamProfile.imageImportLimit(lowRamEnabled));
            BitmapFactory.Options opts=new BitmapFactory.Options();
            opts.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
            if (opts.outWidth<=0 || opts.outHeight<=0 ||
                (long)opts.outWidth*opts.outHeight>8_000_000L)
                throw new IOException("Image too large or unsupported (8 megapixels max)");
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex=new StringBuilder(64);
            final char[] digits="0123456789abcdef".toCharArray();
            for (byte b:hash) { hex.append(digits[(b>>>4)&15]); hex.append(digits[b&15]); }
            String id=hex.toString();
            if (!model.imageAssets.containsKey(id) && model.imageAssets.size()>=256)
                throw new IOException("Project image asset limit reached");
            if (!model.imageAssets.containsKey(id)) {
                long total=bytes.length;
                for (byte[] asset:model.imageAssets.values()) total+=asset.length;
                if (total>RamProfile.maxImageAssetsBytes(lowRamEnabled))
                throw new IOException("Image storage limit reached");
            }
            float factor=Math.min(1f,Math.min(680f/opts.outWidth,390f/opts.outHeight));
            float width=opts.outWidth*factor,height=opts.outHeight*factor;
            model.imageAssets.putIfAbsent(id,bytes);
            stopPlayback();
            Model.Drawing edit=model.ensureEditable(selected);
            edit.images.add(new Model.ImageItem(id,(Model.WIDTH-width)/2,
                (Model.HEIGHT-height)/2,width,height));
            canvas.setDrawing(edit);
            canvas.rebuild();
            changed();
            toast("Image imported. Use Tools → Edit image placement.");
        } catch (Exception e) {toast("Image import failed: "+e.getMessage());}
    }
    private void editImagePlacement() {
        Model.Drawing drawing=model.drawingAt(selected);
        if (drawing.images.isEmpty()) {toast("No images in this pose");return;}
        String[] options=new String[drawing.images.size()];
        for (int i=0;i<options.length;i++) options[i]="Image "+(i+1);
        new AlertDialog.Builder(this).setTitle("Choose image")
            .setItems(options,(d,index)->showImageBoundsDialog(index)).show();
    }
    private void showImageBoundsDialog(int index) {
        Model.ImageItem item=model.drawingAt(selected).images.get(index);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20),dp(8),dp(20),0);
        EditText[] inputs=new EditText[4];
        float[] values={item.x,item.y,item.width,item.height};
        String[] names={"X","Y","Width","Height"};
        for (int i=0;i<4;i++) {
            inputs[i]=new EditText(this);
            inputs[i].setInputType(InputType.TYPE_CLASS_NUMBER|
                InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
            inputs[i].setHint(names[i]);
            inputs[i].setText(String.valueOf(values[i]));
            form.addView(inputs[i]);
        }
        new AlertDialog.Builder(this).setTitle("Move / resize image")
            .setView(form).setNegativeButton("Cancel",null)
            .setPositiveButton("Apply",(d,w)->{
                try {
                    float x=Float.parseFloat(inputs[0].getText().toString());
                    float y=Float.parseFloat(inputs[1].getText().toString());
                    float width=Float.parseFloat(inputs[2].getText().toString());
                    float height=Float.parseFloat(inputs[3].getText().toString());
                    if (!Float.isFinite(x)||!Float.isFinite(y)||
                        Math.abs(x)>100_000||Math.abs(y)>100_000||
                        !(width>=.01f&&width<=100_000f)||
                        !(height>=.01f&&height<=100_000f)) {
                        toast("Invalid position or dimensions");return;
                    }
                    Model.ImageItem edit=model.ensureEditable(selected).images.get(index);
                    edit.x=x;edit.y=y;edit.width=width;edit.height=height;
                    canvas.rebuild();changed();
                } catch (NumberFormatException e) {toast("Enter valid numbers");}
            }).show();
    }
    private void readImportedAudio(Uri uri) {
        // SAF streams are not stable across app restarts. Copy one compressed sound
        // file to app-private storage; MediaPlayer streams it rather than decoding to RAM.
        final int startingFrame=currentFrame;
        final Model.Project targetProject=model;
        Thread worker=new Thread(()->{
            File folder=new File(getFilesDir(),"audio");
            File target=new File(folder,UUID.randomUUID().toString()+".sound");
            try {
                if (!folder.isDirectory()&&!folder.mkdirs()) throw new IOException("Cannot create audio directory");
                try (InputStream in=getContentResolver().openInputStream(uri);
                     FileOutputStream out=new FileOutputStream(target)) {
                    if (in==null) throw new IOException("Cannot read audio file");
                    byte[] block=new byte[8192];int n;long size=0;
                    while ((n=in.read(block))!=-1) {
                        size+=n;
                        if (size>60_000_000L) throw new IOException("Audio exceeds 60 MB");
                        out.write(block,0,n);
                    }
                }
                MediaMetadataRetriever reader=new MediaMetadataRetriever();
                long length;
                try {
                    reader.setDataSource(target.getAbsolutePath());
                    String value=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    length=value==null?0:Long.parseLong(value);
                } finally {reader.release();}
                if (length<1 || length>8*60*60*1000L) throw new IOException("Invalid audio duration");
                final int frames=(int)Math.max(1,Math.min(Model.MAX_DURATION,
                    (long)Math.ceil(length*model.fps/1000.0)));
                runOnUiThread(()->{
                    try {
                        if (model!=targetProject) { target.delete(); return; }
                        model.addAudio(target.getAbsolutePath(),startingFrame,frames);
                        changed();toast("Audio added");
                    } catch (Exception e) {
                        target.delete();toast("Audio import failed: "+e.getMessage());
                    }
                });
            } catch (Exception e) {
                target.delete();
                runOnUiThread(()->toast("Audio import failed: "+e.getMessage()));
            }
        },"holdframe-audio-import");
        worker.setDaemon(true);
        worker.start();
    }
    private void splitAudioAtPlayhead() {
        if(model.audioClips.isEmpty()){toast("No audio clips");return;}
        String[] titles=new String[model.audioClips.size()];
        for(int i=0;i<titles.length;i++) {
            Model.AudioClip clip=model.audioClips.get(i);
            titles[i]="Audio "+(i+1)+" — "+(clip.startFrame/(float)model.fps)+"–"+
                (clip.endFrame()/(float)model.fps)+"s";
        }
        new AlertDialog.Builder(this).setTitle("Split audio at playhead")
            .setItems(titles,(dialog,which)->{
                stopPlayback();
                try {
                    Model.AudioClip current=model.audioClips.get(which);
                    model.splitAudioClip(current.id,currentFrame);
                    changed();
                    toast("Audio split — the two pieces share the original sound file");
                } catch(IllegalArgumentException|IllegalStateException ex) {
                    toast("Cannot split audio: "+ex.getMessage());
                }
            }).setNegativeButton("Cancel",null).show();
    }
    private void removeAudio() {
        if (model.audioClips.isEmpty()) {toast("No audio clips");return;}
        String[] titles=new String[model.audioClips.size()];
        for (int i=0;i<titles.length;i++) {
            Model.AudioClip clip=model.audioClips.get(i);
            titles[i]="Audio "+(i+1)+" — "+(clip.startFrame/(float)model.fps)+"s";
        }
        new AlertDialog.Builder(this).setTitle("Remove audio clip")
            .setItems(titles,(d,which)->{
                stopPlayback();
                Model.AudioClip removed=model.audioClips.remove(which);
                boolean used=false;
                for (Model.AudioClip remaining:model.audioClips)
                    if (remaining.sourcePath.equals(removed.sourcePath)) {used=true;break;}
                // Delete only files which are inside HoldFrame's private import folder.
                File file=new File(removed.sourcePath);
                File folder=new File(getFilesDir(),"audio");
                if (!used && file.getParentFile()!=null && file.getParentFile().equals(folder)) file.delete();
                changed();
            }).show();
    }
    private void toast(String text) {Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    @Override protected void onPause() {
        stopPlayback();
        if (autosaveJob!=null) handler.removeCallbacks(autosaveJob);
        writeAutosave();
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseAudio();
        super.onDestroy();
    }
}
