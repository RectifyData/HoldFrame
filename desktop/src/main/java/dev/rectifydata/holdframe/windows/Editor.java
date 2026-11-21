package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import dev.rectifydata.holdframe.core.ProjectIO;
import dev.rectifydata.holdframe.core.ReleaseChecker;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JToggleButton;
import javax.swing.ButtonGroup;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JTextArea;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.JComponent;
import javax.swing.text.JTextComponent;
import java.awt.KeyboardFocusManager;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FileDialog;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** Desktop editor for a small, source-drawing-based animation project. */
public final class Editor extends JFrame {
    private Model.Project model = new Model.Project();
    private int selected = 0, frame = 0;
    private AudioSupport audio = new AudioSupport();
    private String selectedAudioId;
    private JCheckBox selectedAudioMute;
    private JSlider selectedAudioVolume;
    private boolean updatingAudioControls;
    private boolean dirty;
    private Path path;
    private CanvasView stage;
    private TimelineView timeline;
    private JLabel status;
    private JButton playButton;
    private Timer timer;
    private JToggleButton lassoButton;
    private JToggleButton penButton, eraserButton, textButton;
    private JTabbedPane featureTabs;
    private final Map<String,String> defaultShortcuts = new LinkedHashMap<>();
    private final Map<String,Runnable> shortcutActions = new LinkedHashMap<>();
    private final Map<String,KeyStroke> activeShortcuts = new HashMap<>();
    private final Preferences shortcutPrefs = Preferences.userNodeForPackage(Editor.class).node("keymaps");
    private interface Edit { void undo(); void redo(); }
    private final Map<String,Deque<Edit>> undoHistory = new HashMap<>();
    private final Map<String,Deque<Edit>> redoHistory = new HashMap<>();
    private Model.ClipboardSequence clipboard;
    // Inclusive contiguous pose selection. Shift-click/drag on the pose track extends it.
    private int rangeAnchor = -1, rangeStart = -1, rangeEnd = -1;
    private long playStartedNanos;
    private int playStartedFrame;

    public Editor() {
        super("HoldFrame — Untitled");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(780, 570));
        setSize(1100, 750);
        setLocationRelativeTo(null);
        setJMenuBar(buildMenu());
        buildUi();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (confirmSave()) { stopPlayback(); audio.close(); dispose(); }
            }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent event) {
                // Don't let a stale drag timer keep scrolling after Alt+Tab.
                if (timeline != null) timeline.cancelDrag();
            }
        });
        defineShortcuts();
        installShortcuts();
        refresh();
        checkUpdatesOnLaunch();
    }

    Model.Project project() { return model; }
    int selected() { return selected; }
    int frame() { return frame; }
    boolean isPlaying() { return timer != null; }
    /** Called before a stroke or transform to detach shared clipboard drawings. */
    void prepareDrawing() {
        Model.Drawing current = model.drawingAt(selected);
        Model.Drawing independent = model.ensureEditable(selected);
        if (current != independent) stage.invalidateDrawing();
    }
    private void recordEdit(String drawingId, Edit edit) {
        Deque<Edit> stack = undoHistory.computeIfAbsent(drawingId,key -> new ArrayDeque<>());
        stack.push(edit);
        while (stack.size() > 100) stack.removeLast();
        redoHistory.remove(drawingId);
        markDirty();
        refresh();
    }
    void strokeAdded(Model.Stroke stroke) {
        Model.Drawing drawing = model.drawingAt(selected);
        recordEdit(drawing.id,new Edit() {
            public void undo() { drawing.strokes.remove(stroke); }
            public void redo() { drawing.strokes.add(stroke); }
        });
    }
    void transformFinished(List<Model.Stroke> strokes,
            Map<Model.Stroke,List<Model.Point>> oldPositions,
            Map<Model.Stroke,Float> oldWidths,
            List<Model.TextItem> texts,
            Map<Model.TextItem,Model.TextItem> oldTexts,
            List<Model.ImageItem> images,
            Map<Model.ImageItem,Model.ImageItem> oldImages) {
        Map<Model.Stroke,List<Model.Point>> beforePoints=new IdentityHashMap<>();
        Map<Model.Stroke,List<Model.Point>> afterPoints=new IdentityHashMap<>();
        Map<Model.Stroke,Float> beforeWidths=new IdentityHashMap<>();
        Map<Model.Stroke,Float> afterWidths=new IdentityHashMap<>();
        Map<Model.TextItem,Model.TextItem> beforeTexts=new IdentityHashMap<>();
        Map<Model.TextItem,Model.TextItem> afterTexts=new IdentityHashMap<>();
        Map<Model.ImageItem,Model.ImageItem> beforeImages=new IdentityHashMap<>();
        Map<Model.ImageItem,Model.ImageItem> afterImages=new IdentityHashMap<>();
        boolean modified=false;
        for (Model.Stroke stroke : strokes) {
            List<Model.Point> prior=oldPositions.get(stroke);
            if (prior==null) continue;
            beforePoints.put(stroke,new ArrayList<>(prior));
            afterPoints.put(stroke,new ArrayList<>(stroke.points));
            float previousWidth=oldWidths.getOrDefault(stroke,stroke.width);
            beforeWidths.put(stroke,previousWidth);
            afterWidths.put(stroke,stroke.width);
            if (previousWidth != stroke.width || prior.size()!=stroke.points.size()) modified=true;
            else for (int i=0;i<prior.size();i++)
                if (prior.get(i).x!=stroke.points.get(i).x || prior.get(i).y!=stroke.points.get(i).y)
                    modified=true;
        }
        for (Model.TextItem text : texts) {
            Model.TextItem old=oldTexts.get(text);
            if (old==null) continue;
            beforeTexts.put(text,old.copy());
            afterTexts.put(text,text.copy());
            if (old.x!=text.x || old.y!=text.y || old.scaleX!=text.scaleX || old.scaleY!=text.scaleY)
                modified=true;
        }
        for (Model.ImageItem image : images) {
            Model.ImageItem old=oldImages.get(image);
            if (old==null) continue;
            beforeImages.put(image,old.copy());
            afterImages.put(image,image.copy());
            if (old.x!=image.x || old.y!=image.y || old.width!=image.width || old.height!=image.height)
                modified=true;
        }
        if (!modified) return;
        recordEdit(model.drawingAt(selected).id,new Edit() {
            public void undo() { restore(beforePoints,beforeWidths,beforeTexts,beforeImages); }
            public void redo() { restore(afterPoints,afterWidths,afterTexts,afterImages); }
        });
    }
    private static void restore(Map<Model.Stroke,List<Model.Point>> points,
                                Map<Model.Stroke,Float> widths,
                                Map<Model.TextItem,Model.TextItem> texts,
                                Map<Model.ImageItem,Model.ImageItem> images) {
        for (Map.Entry<Model.Stroke,List<Model.Point>> e : points.entrySet()) {
            e.getKey().points.clear();
            e.getKey().points.addAll(e.getValue());
            e.getKey().width=widths.get(e.getKey());
        }
        for (Map.Entry<Model.TextItem,Model.TextItem> e : texts.entrySet()) e.getKey().assign(e.getValue());
        for (Map.Entry<Model.ImageItem,Model.ImageItem> e : images.entrySet()) e.getKey().assign(e.getValue());
    }
    /** Retains the typed string and font metadata, never converting letters into brush strokes. */
    void insertText(Model.TextItem item) {
        stopPlayback();
        prepareDrawing();
        Model.Drawing drawing=model.drawingAt(selected);
        drawing.texts.add(item);
        recordEdit(drawing.id,new Edit() {
            public void undo() { drawing.texts.remove(item); }
            public void redo() { drawing.texts.add(item); }
        });
        stage.invalidateDrawing();
        stage.selectOnlyText(item);
    }
    void changeText(int textIndex,Model.TextItem updated) {
        stopPlayback();
        prepareDrawing();
        Model.Drawing drawing=model.drawingAt(selected);
        if (textIndex<0 || textIndex>=drawing.texts.size()) return;
        Model.TextItem item=drawing.texts.get(textIndex);
        Model.TextItem previous=item.copy();
        item.assign(updated);
        Model.TextItem current=item.copy();
        recordEdit(drawing.id,new Edit() {
            public void undo() { item.assign(previous); }
            public void redo() { item.assign(current); }
        });
        stage.invalidateDrawing();
    }
    void addTextAt(Model.Point location) {
        Model.TextItem initial=new Model.TextItem("Text", "Dialog", "", Font.PLAIN,
            stage.getInk().getRGB(), 48, location.x, location.y);
        Model.TextItem chosen=editTextDialog(initial);
        if (chosen!=null && !chosen.value.isEmpty()) insertText(chosen);
    }
    void editTextAt(int textIndex) {
        if (textIndex<0 || textIndex>=model.drawingAt(selected).texts.size()) return;
        Model.TextItem initial=model.drawingAt(selected).texts.get(textIndex).copy();
        Model.TextItem updated=editTextDialog(initial);
        if (updated!=null) changeText(textIndex,updated);
    }
    /** Picker for installed families and user-provided .ttf/.otf fonts.
     * Only local font path is stored; the font file is never embedded in a project. */
    private Model.TextItem editTextDialog(Model.TextItem original) {
        JDialog dialog=new JDialog(this,"Text — HoldFrame",true);
        dialog.setLayout(new BorderLayout(8,8));
        JTextArea contents=new JTextArea(original.value,4,32);
        contents.setLineWrap(true); contents.setWrapStyleWord(true);
        JPanel form=new JPanel(new GridLayout(0,2,6,6));
        String[] families=GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> family=new JComboBox<>(families);
        family.setEditable(true);
        family.setSelectedItem(original.fontFamily);
        JSpinner size=new JSpinner(new SpinnerNumberModel((double)original.size,1,4096,1));
        JCheckBox bold=new JCheckBox("Bold",(original.style & Font.BOLD)!=0);
        JCheckBox italic=new JCheckBox("Italic",(original.style & Font.ITALIC)!=0);
        final Color[] ink={new Color(original.color,true)};
        final String[] fontPath={original.fontPath};
        JButton pickFont=button("Load font file…", () -> {
            FileDialog chooser=new FileDialog(dialog,"Choose font file",FileDialog.LOAD);
            chooser.setFilenameFilter((folder,name) -> name.toLowerCase(java.util.Locale.ROOT)
                .matches(".*\\.(ttf|otf|ttc|otc|pfb|pfa)"));
            chooser.setVisible(true);
            if (chooser.getFile()==null) { chooser.dispose(); return; }
            String chosen=Path.of(chooser.getDirectory(),chooser.getFile()).toAbsolutePath().toString();
            chooser.dispose();
            try {
                Font registered=FontSupport.loadFile(chosen);
                fontPath[0]=chosen;
                family.setSelectedItem(registered.getFamily());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog,ex.getMessage(),"Cannot load font",JOptionPane.ERROR_MESSAGE);
            }
        });
        JButton color=button("Text color…", () -> {
            Color newColor=JColorChooser.showDialog(dialog,"Text color",ink[0]);
            if (newColor!=null) ink[0]=newColor;
        });
        form.add(new JLabel("Font family")); form.add(family);
        form.add(new JLabel("Size (pt)") ); form.add(size);
        form.add(bold); form.add(italic);
        form.add(pickFont); form.add(color);
        JLabel note=new JLabel("Font files stay on this computer; other computers use a family fallback.");
        note.setFont(note.getFont().deriveFont(Font.PLAIN,11));
        JPanel content=new JPanel(new BorderLayout(5,5));
        content.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        content.add(new JScrollPane(contents),BorderLayout.NORTH);
        content.add(form,BorderLayout.CENTER);
        content.add(note,BorderLayout.SOUTH);
        dialog.add(content,BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final boolean[] accepted={false};
        JButton cancel=button("Cancel",dialog::dispose);
        JButton ok=button("Apply", () -> { accepted[0]=true; dialog.dispose(); });
        buttons.add(cancel); buttons.add(ok);
        dialog.add(buttons,BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.pack(); dialog.setMinimumSize(new Dimension(475,300));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        if (!accepted[0]) return null;
        Model.TextItem updated=original.copy();
        updated.value=contents.getText();
        updated.fontFamily=String.valueOf(family.getSelectedItem()).trim();
        if (updated.fontFamily.isBlank()) updated.fontFamily="Dialog";
        // Choosing a different installed family discards the previous custom font path.
        if (!fontPath[0].isBlank()) {
            try {
                Font fileFont=FontSupport.loadFile(fontPath[0]);
                if (!fileFont.getFamily().equalsIgnoreCase(updated.fontFamily)) fontPath[0]="";
            } catch (Exception ignored) { fontPath[0]=""; }
        }
        updated.fontPath=fontPath[0];
        updated.size=((Number)size.getValue()).floatValue();
        updated.style=(bold.isSelected()?Font.BOLD:0) | (italic.isSelected()?Font.ITALIC:0);
        updated.color=ink[0].getRGB();
        if (updated.value.length()>10000) {
            JOptionPane.showMessageDialog(this,"Text is limited to 10,000 characters per object.");
            return null;
        }
        return updated;
    }

    /** Embed an imported image once, shared by every copied pose, with editable position/size. */
    void insertImage(byte[] bytes,String assetId,int pixelWidth,int pixelHeight) {
        stopPlayback();
        prepareDrawing();
        Model.Drawing drawing=model.drawingAt(selected);
        model.imageAssets.putIfAbsent(assetId,bytes);
        float factor=Math.min(1f,Math.min(680f/pixelWidth,390f/pixelHeight));
        float w=Math.max(1,pixelWidth*factor),h=Math.max(1,pixelHeight*factor);
        Model.ImageItem image=new Model.ImageItem(assetId,(Model.WIDTH-w)/2,(Model.HEIGHT-h)/2,w,h);
        drawing.images.add(image);
        recordEdit(drawing.id,new Edit() {
            public void undo() { drawing.images.remove(image); }
            public void redo() { drawing.images.add(image); }
        });
        stage.invalidateDrawing();
        stage.selectOnlyImage(image);
        if (lassoButton!=null) lassoButton.setSelected(true);
    }
    private void importImage() {
        stopPlayback();
        Path file=pickMedia("Import PNG, JPEG, GIF or BMP image");
        if (file==null) return;
        try {
            ImageSupport.Imported result=ImageSupport.importFile(file);
            insertImage(result.bytes,result.digest,result.width,result.height);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,ex.getMessage(),"Image import failed",JOptionPane.ERROR_MESSAGE);
        }
    }
    private Path pickMedia(String title) {
        FileDialog chooser=new FileDialog(this,title,FileDialog.LOAD);
        chooser.setLocationRelativeTo(this);
        chooser.setVisible(true);
        String name=chooser.getFile(),directory=chooser.getDirectory();
        chooser.dispose();
        return name==null?null:(directory==null?Path.of(name):Path.of(directory,name));
    }
    private void importAudio() {
        stopPlayback();
        Path file=pickMedia("Import audio (WAV, AIFF, MP3, OGG with FFmpeg)");
        if (file==null) return;
        // Decode/probe on a worker: never freeze the drawing UI on large audio imports.
        status.setText("Reading audio…");
        final Model.Project originalProject=model;
        new SwingWorker<Long,Void>() {
            @Override protected Long doInBackground() throws Exception {
                return AudioSupport.durationMillis(file);
            }
            @Override protected void done() {
                try {
                    long millis=get();
                    if (model!=originalProject) return; // User opened another project mid-import.
                    int frames=(int)Math.max(1,Math.min(Model.MAX_DURATION,
                        (long)Math.ceil(millis*model.fps/1000.0)));
                    int start=Math.max(0,frame);
                    Model.AudioClip clip=model.addAudio(file.toAbsolutePath().toString(),start,frames);
                    selectedAudioId=clip.id;
                    markDirty(); refresh();
                    timeline.revealFrame(start);
                    status.setText("Audio added: "+file.getFileName());
                } catch (Exception ex) {
                    String message=ex.getCause()!=null?ex.getCause().getMessage():ex.getMessage();
                    JOptionPane.showMessageDialog(Editor.this,message,"Audio import failed",JOptionPane.ERROR_MESSAGE);
                    refresh();
                }
            }
        }.execute();
    }
    void selectAudio(String id) {
        selectedAudioId=id;
        updatingAudioControls=true;
        try {
            for (Model.AudioClip clip:model.audioClips) if (clip.id.equals(id)) {
                if (selectedAudioMute!=null) selectedAudioMute.setSelected(clip.muted);
                if (selectedAudioVolume!=null) selectedAudioVolume.setValue(Math.round(clip.volume*100));
                break;
            }
        } finally { updatingAudioControls=false; }
        timeline.repaint();
    }
    String selectedAudioId() { return selectedAudioId; }
    void moveAudio(String id,int newStart) {
        for (Model.AudioClip clip:model.audioClips) if (clip.id.equals(id)) {
            clip.startFrame=Math.max(0,Math.min(Integer.MAX_VALUE-clip.durationFrames,newStart));
            try { model.extendToCover(clip.endFrame()); }
            catch (IllegalArgumentException ex) { clip.startFrame=Math.max(0,model.totalFrames()-clip.durationFrames); }
            markDirty(); refresh(); return;
        }
    }
    void resizeAudio(String id,int length) {
        for (Model.AudioClip clip:model.audioClips) if (clip.id.equals(id)) {
            clip.durationFrames=Math.max(1,Math.min(Model.MAX_DURATION,length));
            try { model.extendToCover(clip.endFrame()); }
            catch (IllegalArgumentException ex) { clip.durationFrames=Math.max(1,model.totalFrames()-clip.startFrame); }
            markDirty(); refresh(); return;
        }
    }
    private void deleteAudio() {
        if (selectedAudioId==null) return;
        stopPlayback();
        if (model.audioClips.removeIf(clip -> clip.id.equals(selectedAudioId))) {
            audio.close(); audio=new AudioSupport();
            selectedAudioId=null; markDirty(); refresh();
        }
    }
    void setAudioMute(boolean mute) {
        if (selectedAudioId==null) return;
        for (Model.AudioClip clip:model.audioClips) if (clip.id.equals(selectedAudioId)) {
            clip.muted=mute; audio.stopAll(); markDirty(); refresh(); break;
        }
    }
    void setAudioVolume(float volume) {
        if (selectedAudioId==null) return;
        for (Model.AudioClip clip:model.audioClips) if (clip.id.equals(selectedAudioId)) {
            clip.volume=Math.max(0,Math.min(1,volume)); markDirty(); refresh(); break;
        }
    }
    private void shortcut(String name,String defaultBinding,Runnable action) {
        defaultShortcuts.put(name,defaultBinding);
        shortcutActions.put(name,action);
    }
    private void defineShortcuts() {
        shortcut("Save","control S",this::save);
        shortcut("Open","control O",this::open);
        shortcut("New project","control shift N",this::newProject);
        shortcut("Export","control E",this::exportProject);
        shortcut("Undo","control Z",this::undo);
        shortcut("Redo","control Y",this::redo);
        shortcut("Select objects","control A",this::selectAll);
        shortcut("Select timeline","control shift A",this::selectAllTimeline);
        shortcut("Copy poses","control C",this::copyFrame);
        shortcut("Paste poses","control V",this::pasteFrame);
        shortcut("Add pose","control N",()->add(false));
        shortcut("Duplicate pose","control D",()->add(true));
        shortcut("Previous frame","LEFT",()->seek(frame-1));
        shortcut("Next frame","RIGHT",()->seek(frame+1));
        shortcut("Play / Pause","SPACE",this::togglePlayback);
        shortcut("Draw tool","P",()->chooseTool(CanvasView.Tool.PEN,penButton));
        shortcut("Eraser tool","E",()->chooseTool(CanvasView.Tool.ERASER,eraserButton));
        shortcut("Lasso tool","L",()->chooseTool(CanvasView.Tool.LASSO,lassoButton));
        shortcut("Text tool","T",()->chooseTool(CanvasView.Tool.TEXT,textButton));
        shortcut("Project tab","control 1",()->featureTabs.setSelectedIndex(0));
        shortcut("Draw tab","control 2",()->featureTabs.setSelectedIndex(1));
        shortcut("Animate tab","control 3",()->featureTabs.setSelectedIndex(2));
        shortcut("Media tab","control 4",()->featureTabs.setSelectedIndex(3));
        shortcut("Zoom in","control EQUALS",()->stage.zoomBy(1.25f));
        shortcut("Zoom out","control MINUS",()->stage.zoomBy(.8f));
        shortcut("Fit canvas","control 0",()->stage.zoomReset());
    }
    private void chooseTool(CanvasView.Tool tool,JToggleButton button) {
        stage.setTool(tool);
        if (button!=null) button.setSelected(true);
        if (featureTabs!=null) featureTabs.setSelectedIndex(1);
    }
    private static boolean shortcutReservedByTextEditor(String action) {
        return action.equals("Copy poses") || action.equals("Paste poses") ||
            action.equals("Select objects") || action.equals("Undo") || action.equals("Redo") ||
            action.equals("Next frame") || action.equals("Previous frame") ||
            action.equals("Play / Pause") || action.endsWith(" tool");
    }
    private void installShortcuts() {
        for (KeyStroke key : activeShortcuts.values()) getRootPane().unregisterKeyboardAction(key);
        activeShortcuts.clear();
        for (Map.Entry<String,String> entry:defaultShortcuts.entrySet()) {
            String action=entry.getKey();
            String saved=shortcutPrefs.get(action,entry.getValue());
            KeyStroke key=KeyStroke.getKeyStroke(saved);
            if (key==null) key=KeyStroke.getKeyStroke(entry.getValue());
            activeShortcuts.put(action,key);
            final Runnable runnable=shortcutActions.get(action);
            getRootPane().registerKeyboardAction(e->{
                if (shortcutReservedByTextEditor(action) &&
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent)
                    return;
                runnable.run();
            },key,JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
    }
    private void showKeymapDialog() {
        JPanel rows=new JPanel(new GridLayout(0,2,8,5));
        Map<String,JTextField> editors=new LinkedHashMap<>();
        for (Map.Entry<String,String> entry:defaultShortcuts.entrySet()) {
            rows.add(new JLabel(entry.getKey()));
            JTextField field=new JTextField(shortcutPrefs.get(entry.getKey(),entry.getValue()),18);
            field.setToolTipText("Java KeyStroke syntax: control S, shift F1, alt LEFT, SPACE");
            editors.put(entry.getKey(),field);
            rows.add(field);
        }
        JScrollPane pane=new JScrollPane(rows);
        pane.setPreferredSize(new Dimension(490,470));
        Object[] buttons={"Save keymap","Reset defaults","Cancel"};
        while (true) {
            int choice=JOptionPane.showOptionDialog(this,pane,"Keyboard keymaps · customizable",
                JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,buttons,buttons[0]);
            if (choice==2 || choice==JOptionPane.CLOSED_OPTION) return;
            if (choice==1) {
                for(String name:defaultShortcuts.keySet()) shortcutPrefs.remove(name);
                installShortcuts();
                JOptionPane.showMessageDialog(this,"Default shortcuts restored.");
                return;
            }
            Map<String,String> requested=new LinkedHashMap<>();
            Set<KeyStroke> used=new HashSet<>();
            String error=null;
            for (Map.Entry<String,JTextField> entry:editors.entrySet()) {
                String value=entry.getValue().getText().trim();
                KeyStroke key=KeyStroke.getKeyStroke(value);
                if (key==null) {error="Invalid shortcut for "+entry.getKey()+": "+value;break;}
                if (!used.add(key)) {error="Duplicate shortcut: "+value;break;}
                requested.put(entry.getKey(),value);
            }
            if (error!=null) {
                JOptionPane.showMessageDialog(this,error,"Keymap conflict",JOptionPane.ERROR_MESSAGE);
                continue;
            }
            for(Map.Entry<String,String> entry:requested.entrySet()) shortcutPrefs.put(entry.getKey(),entry.getValue());
            installShortcuts();
            return;
        }
    }
    private void checkUpdatesOnLaunch() {
        Preferences prefs=Preferences.userNodeForPackage(Editor.class);
        if(!prefs.getBoolean("auto_updates",true))return;
        long now=System.currentTimeMillis(),last=prefs.getLong("last_update_check",0);
        if(last<=now && now-last<24L*60*60*1000)return;
        prefs.putLong("last_update_check",now);
        checkUpdates(false);
    }
    private String appVersion() {
        try(java.io.InputStream input=getClass().getResourceAsStream("/META-INF/holdframe-version.txt")) {
            if(input!=null)return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch(IOException ignored) {}
        return "0.9.0";
    }
    private void checkUpdates(boolean manual) {
        if(manual && status!=null)status.setText("Checking GitHub releases...");
        new SwingWorker<ReleaseChecker.Release,Void>() {
            private Exception error;
            @Override protected ReleaseChecker.Release doInBackground(){
                try {return ReleaseChecker.check();}
                catch(Exception e){error=e;return null;}
            }
            @Override protected void done(){
                if(!isDisplayable())return;
                if(error!=null){
                    if(manual)JOptionPane.showMessageDialog(Editor.this,
                        "Cannot check for updates: "+error.getMessage(),"HoldFrame Updates",JOptionPane.WARNING_MESSAGE);
                    refresh();return;
                }
                try {
                    ReleaseChecker.Release release=get();
                    String current=appVersion();
                    if(release!=null && release.newerThan(current)){
                        int choice=JOptionPane.showConfirmDialog(Editor.this,
                            "HoldFrame "+release.tag+" is available (installed: "+current+").\nOpen the GitHub release page?",
                            "HoldFrame update available",JOptionPane.YES_NO_OPTION);
                        if(choice==JOptionPane.YES_OPTION){
                            try {java.awt.Desktop.getDesktop().browse(java.net.URI.create(release.page));}
                            catch(Exception ex){JOptionPane.showMessageDialog(Editor.this,release.page);}
                        }
                    } else if(manual)JOptionPane.showMessageDialog(Editor.this,
                        "HoldFrame is up to date ("+current+")","HoldFrame Updates",JOptionPane.INFORMATION_MESSAGE);
                } catch(Exception e){if(manual)JOptionPane.showMessageDialog(Editor.this,e.toString());}
                refresh();
            }
        }.execute();
    }
    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        item(file,"New project",this::newProject);
        item(file,"Open project…",this::open);
        item(file,"Save",this::save);
        item(file,"Save As…",this::saveAs);
        item(file,"Import image…",this::importImage);
        item(file,"Add audio track…",this::importAudio);
        item(file,"Export animation… (Ctrl+E)",this::exportProject);
        item(file,"Check for updates…",()->checkUpdates(true));
        item(file,"Exit", () -> { if (confirmSave()) { stopPlayback(); audio.close(); dispose(); }});
        JMenu edit = new JMenu("Edit");
        item(edit,"Undo stroke",this::undo);
        item(edit,"Redo",this::redo);
        item(edit,"Select all objects (Ctrl+A)",this::selectAll);
        item(edit,"Add editable text…",()->addTextAt(new Model.Point(180,150)));
        item(edit,"Import image…",this::importImage);
        item(edit,"Delete selected audio clip",this::deleteAudio);
        item(edit,"Copy selected poses (Ctrl+C)",this::copyFrame);
        item(edit,"Paste poses (Ctrl+V)",this::pasteFrame);
        item(edit,"Select all timeline poses (Ctrl+Shift+A)",this::selectAllTimeline);
        item(edit,"Add blank drawing",()->add(false));
        item(edit,"Duplicate drawing",()->add(true));
        item(edit,"Delete drawing",this::remove);
        JMenu view = new JMenu("View");
        item(view,"Zoom in (Ctrl++)",()->stage.zoomBy(1.25f));
        item(view,"Zoom out (Ctrl+-)",()->stage.zoomBy(.8f));
        item(view,"Fit canvas (Ctrl+0)",()->stage.zoomReset());
        JMenu settings = new JMenu("Settings");
        item(settings,"Keyboard keymaps…",this::showKeymapDialog);
        JCheckBoxMenuItem autoUpdates=new JCheckBoxMenuItem("Check for updates automatically",
            Preferences.userNodeForPackage(Editor.class).getBoolean("auto_updates",true));
        autoUpdates.addActionListener(e->Preferences.userNodeForPackage(Editor.class)
            .putBoolean("auto_updates",autoUpdates.isSelected()));
        settings.add(autoUpdates);
        item(settings,"Restore default keyboard shortcuts",()->{
            for (String key:defaultShortcuts.keySet()) shortcutPrefs.remove(key);
            installShortcuts();
        });
        bar.add(file); bar.add(edit); bar.add(view); bar.add(settings);
        return bar;
    }
    private void item(JMenu menu, String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        menu.add(item);
    }
    private void popupItem(JPopupMenu menu, String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        menu.add(item);
    }
    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }
    private void buildUi() {
        JPanel layout = new JPanel(new BorderLayout(4,4));
        setContentPane(layout);
        // Contextual feature tabs: controls are grouped, never wrapped or overlaid.
        featureTabs = new JTabbedPane();
        JPanel projectTab=new JPanel(new FlowLayout(FlowLayout.LEFT,5,4));
        projectTab.add(button("New",this::newProject));
        projectTab.add(button("Open",this::open));
        projectTab.add(button("Save",this::save));
        projectTab.add(button("Save As",this::saveAs));
        projectTab.add(button("Export…",this::exportProject));
        projectTab.add(button("Keymaps…",this::showKeymapDialog));
        featureTabs.addTab("Project",projectTab);
        JPanel drawTab=new JPanel(new BorderLayout());
        featureTabs.addTab("Draw",drawTab);
        JPanel animateTab=new JPanel(new FlowLayout(FlowLayout.LEFT,5,4));
        animateTab.add(button("+ Pose",()->add(false)));
        animateTab.add(button("Duplicate",()->add(true)));
        animateTab.add(button("Delete",this::remove));
        animateTab.add(button("Copy",this::copyFrame));
        animateTab.add(button("Paste",this::pasteFrame));
        animateTab.add(button("Undo",this::undo));
        animateTab.add(button("Redo",this::redo));
        featureTabs.addTab("Animate",animateTab);
        JPanel mediaTab=new JPanel(new FlowLayout(FlowLayout.LEFT,5,4));
        mediaTab.add(button("+ Text",()->addTextAt(new Model.Point(180,150))));
        mediaTab.add(button("+ Image",this::importImage));
        mediaTab.add(button("+ Audio",this::importAudio));
        mediaTab.add(button("Remove audio",this::deleteAudio));
        featureTabs.addTab("Media",mediaTab);
        featureTabs.setSelectedIndex(1);
        layout.add(featureTabs,BorderLayout.NORTH);

        stage = new CanvasView(this);
        // Use a proper dockable Swing toolbar instead of overlaying a JLayeredPane.
        // The old overlay painted over the native file chooser on Windows, and
        // could leave the timeline or canvas duplicated after a dialog closed.
        JToolBar tools = new JToolBar("Drawing tools");
        tools.setFloatable(false);
        tools.setBackground(new Color(0xF1F3F6));
        tools.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xBBC4CC)),BorderFactory.createEmptyBorder(4,4,4,4)));
        tools.add(new JLabel("TOOLS"));
        ButtonGroup group = new ButtonGroup();
        JToggleButton pen = new JToggleButton("Pen",true);
        JToggleButton eraser = new JToggleButton("Erase");
        JToggleButton lasso = new JToggleButton("Lasso / Move");
        JToggleButton textTool = new JToggleButton("Text");
        group.add(pen); group.add(eraser); group.add(lasso); group.add(textTool);
        lassoButton=lasso;
        penButton=pen; eraserButton=eraser; textButton=textTool;
        pen.addActionListener(e -> stage.setTool(CanvasView.Tool.PEN));
        eraser.addActionListener(e -> stage.setTool(CanvasView.Tool.ERASER));
        lasso.addActionListener(e -> stage.setTool(CanvasView.Tool.LASSO));
        textTool.addActionListener(e -> stage.setTool(CanvasView.Tool.TEXT));
        textTool.setToolTipText("Click the canvas to add editable text. Double-click existing text in Lasso mode to edit.");
        tools.add(pen); tools.add(eraser); tools.add(lasso); tools.add(textTool);
        JButton color = button("Color…",() -> {
            Color choice=JColorChooser.showDialog(this,"Brush color",stage.getInk());
            if (choice!=null) { stage.setInk(choice); pen.setSelected(true); stage.setTool(CanvasView.Tool.PEN); }
        });
        tools.add(color);
        JSlider brush = new JSlider(1,30,6);
        brush.setPreferredSize(new Dimension(95,26));
        brush.setMinimumSize(new Dimension(75,26));
        brush.setMaximumSize(new Dimension(105,30));
        brush.addChangeListener(e -> stage.setBrush(brush.getValue()));
        tools.add(new JLabel("Size"));
        tools.add(brush);
        JCheckBox onion = new JCheckBox("Onion",true);
        onion.setOpaque(false);
        onion.setToolTipText("Toggle translucent neighboring poses");
        onion.addActionListener(e -> stage.setOnion(onion.isSelected()));
        tools.add(onion);
        JButton onionSettings=button("▾",()->{});
        onionSettings.setToolTipText("Configure previous/next onion skins");
        JPopupMenu onionMenu=new JPopupMenu();
        JCheckBoxMenuItem previous=new JCheckBoxMenuItem("Previous pose (red)",true);
        JCheckBoxMenuItem next=new JCheckBoxMenuItem("Next pose (blue)",true);
        previous.addActionListener(e->stage.setPreviousOnion(previous.isSelected()));
        next.addActionListener(e->stage.setNextOnion(next.isSelected()));
        onionMenu.add(previous);onionMenu.add(next);
        onionSettings.addActionListener(e->onionMenu.show(onionSettings,0,onionSettings.getHeight()));
        tools.add(onionSettings);
        JPanel editorArea = new JPanel(new BorderLayout());
        drawTab.add(tools,BorderLayout.CENTER);
        editorArea.add(stage,BorderLayout.CENTER);
        layout.add(editorArea,BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel player = new JPanel(new FlowLayout(FlowLayout.LEFT,8,3));
        player.add(button("◀ Frame",()->seek(frame-1)));
        playButton=button("▶ Play",this::togglePlayback);
        player.add(playButton);
        player.add(button("Frame ▶",()->seek(frame+1)));
        selectedAudioMute=new JCheckBox("Mute audio");
        selectedAudioMute.setToolTipText("Mute selected audio clip");
        selectedAudioMute.addActionListener(e -> { if (!updatingAudioControls) setAudioMute(selectedAudioMute.isSelected()); });
        selectedAudioVolume=new JSlider(0,100,100);
        selectedAudioVolume.setPreferredSize(new Dimension(120,28));
        selectedAudioVolume.setToolTipText("Selected audio clip volume");
        selectedAudioVolume.addChangeListener(e -> { if (!updatingAudioControls) setAudioVolume(selectedAudioVolume.getValue()/100f); });
        JPopupMenu audioMenu=new JPopupMenu();
        audioMenu.add(selectedAudioMute);
        JPanel volumeRow=new JPanel(new FlowLayout(FlowLayout.LEFT,4,2));
        volumeRow.add(new JLabel("Volume")); volumeRow.add(selectedAudioVolume);
        audioMenu.add(volumeRow);
        JButton audioButton=button("Audio ▾",()->{});
        audioButton.addActionListener(e->audioMenu.show(audioButton,0,audioButton.getHeight()));
        mediaTab.add(audioButton);
        status=new JLabel();
        status.setFont(status.getFont().deriveFont(Font.PLAIN,12));
        player.add(status);
        bottom.add(player,BorderLayout.NORTH);
        timeline=new TimelineView(this);
        JScrollPane scroll = new JScrollPane(timeline,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        scroll.setPreferredSize(new Dimension(300,195));
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        bottom.add(scroll,BorderLayout.CENTER);
        layout.add(bottom,BorderLayout.SOUTH);
    }
    void refresh() {
        if (stage == null) return;
        timeline.refresh();
        stage.repaint();
        status.setText(String.format("Pose %d/%d  •  Frame %d/%d  •  %.2fs  •  %d audio",
                selected+1,model.timeline.size(),frame+1,model.totalFrames(),model.totalFrames()/(double)model.fps,
                model.audioClips.size())
                +(selectionCount()>1?"  •  "+selectionCount()+" poses selected":""));
    }
    int selectionStart() { return rangeStart<0?selected:rangeStart; }
    int selectionEnd() { return rangeEnd<0?selected:rangeEnd; }
    int selectionCount() { return selectionEnd()-selectionStart()+1; }
    boolean poseSelected(int index) { return index>=selectionStart() && index<=selectionEnd(); }
    void selectTimelinePose(int index, boolean extend) {
        if (index<0 || index>=model.timeline.size()) return;
        stopPlayback();
        if (!extend) rangeAnchor=index;
        else if (rangeAnchor<0) rangeAnchor=selected;
        rangeStart=Math.min(rangeAnchor,index);
        rangeEnd=Math.max(rangeAnchor,index);
        if (selected!=index) {
            selected=index;
            stage.clearSelection(); stage.invalidateDrawing();
        }
        frame=model.clipStart(index);
        refresh();
    }
    void extendPoseSelectionToFrame(int frame) {
        selectTimelinePose(model.indexAt(Math.max(0,Math.min(model.totalFrames()-1,frame))),true);
    }
    private void clearRange() {
        rangeAnchor=selected;
        rangeStart=selected;
        rangeEnd=selected;
    }
    private void selectAllTimeline() {
        stopPlayback();
        rangeAnchor=0;
        rangeStart=0;
        rangeEnd=model.timeline.size()-1;
        refresh();
    }
    void seek(int value) {
        int clamped=Math.max(0,Math.min(model.totalFrames()-1,value));
        int next=model.indexAt(clamped);
        if (next!=selected) { selected=next; stage.clearSelection(); stage.invalidateDrawing(); }
        frame=clamped;
        clearRange();
        refresh();
        // Navigation buttons and playback follow the playhead automatically;
        // TimelineView suppresses this while a mouse drag is in progress.
        timeline.followPlayhead(frame);
    }
    private void selectAll() {
        stopPlayback();
        stage.selectAll();
        if (lassoButton!=null) lassoButton.setSelected(true);
    }
    void resizeClip(int clipIndex,int newFrames) {
        if (newFrames<1) newFrames=1;
        if (model.timeline.get(clipIndex).duration!=newFrames) {
            model.extend(clipIndex,newFrames);
            // Keep the resized clip selected instead of jumping to whichever clip owns
            // the old absolute playhead position after changing exposure lengths.
            if (selected!=clipIndex) {
                selected=clipIndex;
                stage.clearSelection();
                stage.invalidateDrawing();
            }
            frame=model.clipStart(clipIndex)+Math.min(model.timeline.get(clipIndex).duration-1,
                    Math.max(0,frame-model.clipStart(clipIndex)));
            markDirty();
            refresh();
        }
    }
    private void add(boolean duplicate) {
        stopPlayback();
        selected=model.addAfter(selected,duplicate);
        frame=model.clipStart(selected);
        stage.clearSelection();
        stage.invalidateDrawing();
        clearRange();
        markDirty();
        refresh();
        timeline.revealFrame(frame);
    }
    private void remove() {
        if (model.timeline.size()==1) return;
        stopPlayback();
        int start=selectionStart(), end=selectionEnd();
        // Keep one exposure; never leave an empty project.
        if (end-start+1>=model.timeline.size()) end--;
        for (int i=end;i>=start;i--) model.remove(i);
        selected=Math.min(start,model.timeline.size()-1);
        clearRange();
        frame=model.clipStart(selected);
        stage.clearSelection();
        stage.invalidateDrawing();
        markDirty();
        refresh();
    }
    private void copyFrame() {
        clipboard=model.copyRange(selectionStart(),selectionEnd());
        status.setText("Copied "+clipboard.size()+" pose(s) ("+clipboard.duration()+
            " frames). Ctrl+V inserts the entire sequence; repeated pastes reuse drawing assets.");
    }
    private void pasteFrame() {
        if (clipboard==null) { status.setText("Copy a pose or Shift-select a range first"); return; }
        stopPlayback();
        try {
            int insertion=selectionEnd();
            int last=model.pasteSequenceAfter(insertion,clipboard);
            rangeAnchor=insertion+1;
            rangeStart=insertion+1;
            rangeEnd=last;
            selected=last;
            frame=model.clipStart(selected);
            stage.clearSelection();
            stage.invalidateDrawing();
            markDirty();
            refresh();
            timeline.revealFrame(frame);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this,ex.getMessage(),"Cannot paste poses",JOptionPane.WARNING_MESSAGE);
        }
    }
    private void undo() {
        stopPlayback();
        String id=model.drawingAt(selected).id;
        Deque<Edit> stack=undoHistory.get(id);
        if (stack==null || stack.isEmpty()) return;
        Edit edit=stack.pop();
        edit.undo();
        redoHistory.computeIfAbsent(id,key->new ArrayDeque<>()).push(edit);
        stage.clearSelection();
        stage.invalidateDrawing();
        markDirty();
        refresh();
    }
    private void redo() {
        stopPlayback();
        String id=model.drawingAt(selected).id;
        Deque<Edit> stack=redoHistory.get(id);
        if (stack==null || stack.isEmpty()) return;
        Edit edit=stack.pop();
        edit.redo();
        undoHistory.computeIfAbsent(id,key->new ArrayDeque<>()).push(edit);
        stage.clearSelection();
        stage.invalidateDrawing();
        markDirty();
        refresh();
    }
    private void togglePlayback() {
        if (timer!=null) { stopPlayback(); return; }
        playStartedNanos=System.nanoTime();
        playStartedFrame=frame;
        timer=new Timer(15,e -> {
            long elapsed=System.nanoTime()-playStartedNanos;
            long advance=elapsed*model.fps/1_000_000_000L;
            seek((int)((playStartedFrame+advance)%model.totalFrames()));
            audio.sync(model,frame,false);
            if (!audio.lastError().isBlank()) status.setText("Audio unavailable: "+audio.lastError());
        });
        audio.sync(model,frame,true);
        timer.start();
        playButton.setText("■ Stop");
        stage.repaint();
    }
    void stopPlayback() {
        audio.stopAll();
        if (timer!=null) { timer.stop(); timer=null; playButton.setText("▶ Play"); stage.repaint(); }
    }
    private void markDirty() {
        dirty=true;
        setTitle("HoldFrame — "+(path==null?"Untitled":path.getFileName())+" *");
    }
    private boolean confirmSave() {
        if (!dirty) return true;
        int result=JOptionPane.showConfirmDialog(this,"Save changes before closing this project?",
                "Unsaved drawings",JOptionPane.YES_NO_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE);
        if (result==JOptionPane.CANCEL_OPTION || result==JOptionPane.CLOSED_OPTION) return false;
        return result!=JOptionPane.YES_OPTION || save();
    }
    private void newProject() {
        if (!confirmSave()) return;
        stopPlayback();
        timeline.cancelDrag();
        audio.close(); audio=new AudioSupport(); selectedAudioId=null;
        model=new Model.Project(); selected=frame=0; clearRange(); path=null; dirty=false; undoHistory.clear(); redoHistory.clear();
        setTitle("HoldFrame — Untitled");
        stage.clearSelection(); stage.invalidateDrawing(); refresh();
    }
    /** Native modal file picker: prevents Swing layered-paint artifacts on Windows. */
    private Path pickProject(boolean saving) {
        FileDialog dialog = new FileDialog(this,
                saving ? "Save HoldFrame project" : "Open HoldFrame project",
                saving ? FileDialog.SAVE : FileDialog.LOAD);
        if (path != null) {
            Path absolute=path.toAbsolutePath();
            dialog.setDirectory(absolute.getParent().toString());
            dialog.setFile(absolute.getFileName().toString());
        } else if (saving) dialog.setFile("Untitled.holdframe");
        dialog.setFilenameFilter((directory,name) ->
                name.toLowerCase(java.util.Locale.ROOT).endsWith(".holdframe"));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true); // blocks this frame until dismissed
        String filename=dialog.getFile();
        String directory=dialog.getDirectory();
        dialog.dispose();
        if (filename == null) return null;
        return directory == null ? Path.of(filename) : Path.of(directory,filename);
    }
    private void open() {
        if (!confirmSave()) return;
        stopPlayback();
        timeline.cancelDrag();
        Path requested=pickProject(false);
        if (requested==null) return;
        try (InputStream stream=Files.newInputStream(requested)) {
            Model.Project loaded=ProjectIO.read(stream,requested.toAbsolutePath().getParent());
            stopPlayback();
            timeline.cancelDrag();
            audio.close(); audio=new AudioSupport(); selectedAudioId=null;
            model=loaded; selected=frame=0; clearRange(); path=requested; dirty=false; undoHistory.clear(); redoHistory.clear();
            setTitle("HoldFrame — "+path.getFileName());
            stage.clearSelection(); stage.invalidateDrawing(); refresh();
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this,ex.getMessage(),"Could not open project",JOptionPane.ERROR_MESSAGE);
        }
    }
    private boolean save() {
        if (path==null) return saveAs();
        Path absolute=path.toAbsolutePath();
        Path temp=null;
        try {
            temp=Files.createTempFile(absolute.getParent(),".holdframe-",".tmp");
            try (OutputStream stream=Files.newOutputStream(temp)) { ProjectIO.write(model,stream,absolute.getParent()); }
            try { Files.move(temp,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp,absolute,StandardCopyOption.REPLACE_EXISTING);
            }
            dirty=false; setTitle("HoldFrame — "+path.getFileName());
            return true;
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this,ex.getMessage(),"Save failed",JOptionPane.ERROR_MESSAGE);
            return false;
        } finally {
            if (temp!=null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }
    private boolean saveAs() {
        stopPlayback();
        timeline.cancelDrag();
        Path target=pickProject(true);
        if (target==null) return false;
        if (!target.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".holdframe"))
            target=Path.of(target.toString()+".holdframe");
        if (Files.exists(target) && !target.equals(path)
                && JOptionPane.showConfirmDialog(this,"Overwrite "+target.getFileName()+"?",
                    "Confirm overwrite",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION) return false;
        Path original=path; path=target;
        if (save()) return true;
        path=original; return false;
    }
    /** Render directly from the editable project; no duplicated frame images are saved. */
    private void exportProject() {
        stopPlayback();
        timeline.cancelDrag();
        final String[] formats={"MP4 video + audio (FFmpeg)","Animated GIF (silent)",
            "PNG pose sequence ZIP (with timings)","PNG sprite sheet (up to 32 poses)",
            "Current pose PNG"};
        Object choice=JOptionPane.showInputDialog(this,
            "Select an export format. MP4 includes your audio tracks; GIF and PNG are silent.",
            "Export HoldFrame animation",JOptionPane.PLAIN_MESSAGE,null,formats,formats[0]);
        if (choice==null) return;
        int selectedFormat=java.util.Arrays.asList(formats).indexOf(choice);
        if (selectedFormat<0) return;
        if (selectedFormat==0 && !ensureFfmpeg()) return;
        String extension=switch(selectedFormat) { case 0 -> "mp4"; case 1 -> "gif";
            case 2 -> "zip"; default -> "png"; };
        String basename=path==null?"HoldFrame":path.getFileName().toString().replaceFirst("(?i)\\.holdframe$","");
        FileDialog chooser=new FileDialog(this,"Export "+extension.toUpperCase(Locale.ROOT),FileDialog.SAVE);
        chooser.setFile(basename+"-export."+extension);
        if (path!=null) chooser.setDirectory(path.toAbsolutePath().getParent().toString());
        chooser.setVisible(true);
        String filename=chooser.getFile(),directory=chooser.getDirectory();
        chooser.dispose();
        if (filename==null) return;
        Path target=(directory==null?Path.of(filename):Path.of(directory,filename)).toAbsolutePath();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("."+extension))
            target=Path.of(target.toString()+"."+extension);
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this,
            "Replace existing "+target.getFileName()+"?","Confirm export overwrite",
            JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION) return;
        final Path output=target;
        final int format=selectedFormat,pose=selected;
        // Modal dialog blocks drawing and media edits while allowing SwingWorker's
        // background export and the progress/cancel UI to continue running.
        JDialog progressDialog=new JDialog(this,"Exporting — HoldFrame",true);
        JProgressBar progress=new JProgressBar(0,100);
        progress.setStringPainted(true);
        progress.setValue(0);
        JLabel description=new JLabel("Rendering "+output.getFileName()+" …");
        JButton cancel=new JButton("Cancel");
        JPanel panel=new JPanel(new BorderLayout(8,12));
        panel.setBorder(BorderFactory.createEmptyBorder(18,20,18,20));
        panel.add(description,BorderLayout.NORTH);
        panel.add(progress,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);
        panel.add(actions,BorderLayout.SOUTH);
        progressDialog.setContentPane(panel);
        progressDialog.setSize(450,160);
        progressDialog.setLocationRelativeTo(this);
        SwingWorker<Void,Void> worker=new SwingWorker<>() {
            @Override protected Void doInBackground() throws Exception {
                ExportSupport.Progress update=(done,total) -> {
                    if (isCancelled() || Thread.currentThread().isInterrupted())
                        throw new InterruptedException("Export cancelled");
                    setProgress(Math.max(0,Math.min(100,(int)(100d*done/Math.max(1,total)))));
                };
                switch(format) {
                    case 0 -> ExportSupport.mp4(model,output,update);
                    case 1 -> ExportSupport.gif(model,output,update);
                    case 2 -> ExportSupport.pngSequence(model,output,update);
                    case 3 -> ExportSupport.spriteSheet(model,output,update);
                    case 4 -> ExportSupport.png(model,pose,output);
                    default -> throw new IOException("Unsupported export format");
                }
                setProgress(100);
                return null;
            }
            @Override protected void done() {
                progressDialog.dispose();
                if (isCancelled()) { status.setText("Export cancelled"); return; }
                try {
                    get();
                    status.setText("Exported: "+output.getFileName());
                    JOptionPane.showMessageDialog(Editor.this,"Export complete:\n"+output,
                        "HoldFrame export",JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status.setText("Export interrupted");
                } catch (ExecutionException e) {
                    String error=e.getCause()==null?e.toString():e.getCause().getMessage();
                    status.setText("Export failed");
                    JOptionPane.showMessageDialog(Editor.this,error,"Export failed",JOptionPane.ERROR_MESSAGE);
                } catch (CancellationException e) {
                    status.setText("Export cancelled");
                }
            }
        };
        worker.addPropertyChangeListener(e -> {
            if ("progress".equals(e.getPropertyName())) progress.setValue((Integer)e.getNewValue());
        });
        cancel.addActionListener(e -> { cancel.setEnabled(false); description.setText("Cancelling export…"); worker.cancel(true); });
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        worker.execute();
        progressDialog.setVisible(true);
    }

    private boolean ensureFfmpeg() {
        Preferences preferences=Preferences.userNodeForPackage(Editor.class);
        String configured=preferences.get("ffmpeg","");
        if (!configured.isBlank()) System.setProperty("holdframe.ffmpeg",configured);
        if (ExportSupport.ffmpegAvailable()) return true;
        int option=JOptionPane.showOptionDialog(this,
            "MP4 encoding requires FFmpeg. Choose your ffmpeg executable, or install FFmpeg on PATH.\n"+
            "GIF and PNG exports work without FFmpeg.","Locate FFmpeg",
            JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,
            new Object[]{"Locate ffmpeg executable…","Cancel"},"Locate ffmpeg executable…");
        if (option!=0) return false;
        FileDialog chooser=new FileDialog(this,"Select ffmpeg executable",FileDialog.LOAD);
        chooser.setVisible(true);
        String filename=chooser.getFile(),directory=chooser.getDirectory();
        chooser.dispose();
        if (filename==null) return false;
        String binary=(directory==null?Path.of(filename):Path.of(directory,filename)).toAbsolutePath().toString();
        System.setProperty("holdframe.ffmpeg",binary);
        if (!ExportSupport.ffmpegAvailable()) {
            JOptionPane.showMessageDialog(this,"This executable is not a working FFmpeg binary.",
                "FFmpeg not found",JOptionPane.ERROR_MESSAGE);
            System.clearProperty("holdframe.ffmpeg");
            return false;
        }
        preferences.put("ffmpeg",binary);
        return true;
    }

    public static void main(String[] args) {
        if (args.length>0) {
            System.setProperty("java.awt.headless","true");
            System.exit(ExportCli.run(args));
            return;
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new Editor().setVisible(true));
    }
}
