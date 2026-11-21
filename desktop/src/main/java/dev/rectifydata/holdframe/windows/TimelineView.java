package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.event.MouseWheelEvent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;

/** Virtualized exposure track. One draggable clip per pose, not one cell per frame. */
final class TimelineView extends JComponent {
    private static final int LEFT = 16;
    private static final int MAX_WIDTH = 2_000_000;
    private static final int EDGE_ZONE = 48;
    private static final int AUTO_SCROLL_PERIOD_MS = 25;
    private final Editor editor;
    private Model.Project indexedProject;
    private int indexedSize = -1, indexedTotal = -1;
    private long[] starts = new long[0];
    private double scale = 7;
    private int resizeIndex = -1;
    private boolean selectingRange;
    private String audioDragId;
    private boolean audioResize;
    private long audioGrabOffset;
    private boolean dragging;
    // Pointer position relative to the *viewport*, not the scrolling view. Keeping
    // this fixed lets the cursor continue resizing even when the mouse stops moving
    // at the edge of the window.
    private int pointerInViewport;
    private final Timer dragScrollTimer;

    TimelineView(Editor editor) {
        this.editor=editor;
        setOpaque(true);
        setBackground(new Color(0x292F37));
        dragScrollTimer=new Timer(AUTO_SCROLL_PERIOD_MS,event -> autoScrollStep());
        dragScrollTimer.setCoalesce(true);
        MouseAdapter mouse=new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                cancelDrag();
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                editor.stopPlayback();
                if (e.getY()<26) { editor.seek(frameAt(e.getX())); return; }
                if (e.getY()>=105) {
                    Model.AudioClip audio=hitAudio(e.getX(),e.getY());
                    if (audio==null) { editor.seek(frameAt(e.getX())); return; }
                    audioDragId=audio.id;
                    audioResize=Math.abs(e.getX()-xFor(audio.endFrame()))<=10;
                    audioGrabOffset=rawFrameAt(e.getX())-audio.startFrame;
                    editor.selectAudio(audio.id);
                } else if (e.isShiftDown()) {
                    // Shift-click/drag selects a contiguous sequence, even across
                    // a long timeline via the existing viewport auto-scroll timer.
                    selectingRange=true;
                    dragging=true;
                    JViewport vp=viewport();
                    if (vp!=null) pointerInViewport=SwingUtilities.convertPoint(
                        TimelineView.this,e.getPoint(),vp).x;
                    editor.extendPoseSelectionToFrame(frameAt(e.getX()));
                    return;
                } else resizeIndex=hitHandle(e.getX());
                dragging=true;
                pointerInViewport=SwingUtilities.convertPoint(TimelineView.this,e.getPoint(),viewport()).x;
                if (resizeIndex<0 && audioDragId==null)
                    editor.selectTimelinePose(clipAtFrame(frameAt(e.getX())),false);
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (!dragging) return;
                // Capture pointer relative to the viewport. Drag events sometimes stop
                // at its boundary; the timer continues to scroll with the last location.
                JViewport vp=viewport();
                if (vp==null) return;
                pointerInViewport=SwingUtilities.convertPoint(TimelineView.this,e.getPoint(),vp).x;
                applyDragAtPointer();
                if (!dragScrollTimer.isRunning()) dragScrollTimer.start();
                autoScrollStep();
            }
            @Override public void mouseReleased(MouseEvent e) { cancelDrag(); }
            @Override public void mouseMoved(MouseEvent e) {
                Model.AudioClip audio=e.getY()>=105?hitAudio(e.getX(),e.getY()):null;
                boolean edge=audio!=null && Math.abs(e.getX()-xFor(audio.endFrame()))<=10;
                setCursor(Cursor.getPredefinedCursor(edge || (e.getY()>=26 && e.getY()<105 && hitHandle(e.getX())>=0) ?
                    Cursor.E_RESIZE_CURSOR : Cursor.HAND_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(e -> {
            JViewport vp=viewport();
            if (vp==null || dragging) return;
            Rectangle rect=vp.getViewRect();
            int max=Math.max(0,getPreferredSize().width-rect.width);
            int next=(int)Math.max(0,Math.min(max,rect.x+Math.round(e.getPreciseWheelRotation()*90)));
            vp.setViewPosition(new Point(next,rect.y));
            e.consume();
        });
        setToolTipText("Shift-click or Shift-drag to select multiple poses, Ctrl+C/Ctrl+V to copy/paste sequence");
    }
    private JViewport viewport() {
        return getParent() instanceof JViewport ? (JViewport)getParent() : null;
    }
    private void applyDragAtPointer() {
        if (!dragging) return;
        JViewport viewport=viewport();
        int logicalX=(viewport==null ? 0 : viewport.getViewPosition().x)+pointerInViewport;
        if (selectingRange) {
            editor.extendPoseSelectionToFrame(frameAt(logicalX));
        } else if (audioDragId!=null) {
            if (audioResize) {
                for (Model.AudioClip audio : editor.project().audioClips)
                    if (audio.id.equals(audioDragId)) {
                        editor.resizeAudio(audioDragId,(int)Math.min(Model.MAX_DURATION,Math.max(1,rawFrameAt(logicalX)-audio.startFrame)));
                        break;
                    }
            } else editor.moveAudio(audioDragId,(int)Math.max(0,rawFrameAt(logicalX)-audioGrabOffset));
        } else if (resizeIndex>=0) {
            // Never clamp to the old animation end. A pose can grow past it.
            long frames=rawFrameAt(logicalX)-starts[resizeIndex];
            editor.resizeClip(resizeIndex,(int)Math.min(Model.MAX_DURATION,Math.max(1,frames)));
        } else editor.seek(frameAt(logicalX));
    }
    /** One deterministic tick; also called by the Swing timer while a drag is active. */
    boolean autoScrollStep() {
        if (!dragging) return false;
        JViewport viewport=viewport();
        if (viewport==null) return false;
        Rectangle view=viewport.getViewRect();
        if (view.width<=0) return false;
        int delta=0;
        if (pointerInViewport<EDGE_ZONE) {
            int over=EDGE_ZONE-pointerInViewport;
            delta=-Math.min(72,Math.max(4,over/2));
        } else if (pointerInViewport>view.width-EDGE_ZONE) {
            int over=pointerInViewport-(view.width-EDGE_ZONE);
            delta=Math.min(72,Math.max(4,over/2));
        }
        if (delta==0) return false;
        // A full viewport of trailing empty space permits extending even the last
        // clip. The scrollbar is revalidated by Editor.refresh as the clip grows.
        int max=Math.max(0,getPreferredSize().width-view.width);
        int x=(int)Math.min(max,Math.max(0L,(long)view.x+delta));
        if (x==view.x) return false;
        // Important: the clip's preferred width can grow during this drag, but
        // JViewport may still have its OLD view size until the next Swing layout.
        // Updating the view size now prevents the scroll position from being
        // clamped back, which made the edge-scrolling appear completely broken.
        Dimension preferred=getPreferredSize();
        if (getWidth()!=preferred.width || getHeight()!=preferred.height)
            viewport.setViewSize(preferred);
        viewport.setViewPosition(new Point(x,view.y));
        applyDragAtPointer();
        return viewport.getViewPosition().x!=view.x;
    }
    void cancelDrag() {
        dragScrollTimer.stop();
        dragging=false;
        resizeIndex=-1;
        selectingRange=false;
        audioDragId=null;
        revalidate();
        repaint();
    }
    /** Keep a playing cursor visible without constantly centering and jittering it. */
    void followPlayhead(int frame) {
        if (dragging) return;
        JViewport viewport=viewport();
        if (viewport==null) return;
        Rectangle view=viewport.getViewRect();
        int cursor=xFor(frame), margin=Math.min(52,view.width/4);
        int target=view.x;
        if (cursor<view.x+margin) target=cursor-margin;
        else if (cursor>view.x+view.width-margin) target=cursor-view.width+margin;
        Dimension preferred=getPreferredSize();
        if (getWidth()!=preferred.width || getHeight()!=preferred.height)
            viewport.setViewSize(preferred);
        int max=Math.max(0,preferred.width-view.width);
        target=Math.min(max,Math.max(0,target));
        if (target!=view.x) viewport.setViewPosition(new Point(target,view.y));
    }
    @Override public void removeNotify() {
        cancelDrag();
        super.removeNotify();
    }
    private void ensureIndex() {
        Model.Project model=editor.project();
        int size=model.timeline.size(), total=model.totalFrames();
        if (model==indexedProject && indexedSize==size && indexedTotal==total) return;
        starts=new long[size+1];
        for (int i=0;i<size;i++) starts[i+1]=starts[i]+model.timeline.get(i).duration;
        indexedProject=model;
        indexedSize=size;
        indexedTotal=total;
        scale=Math.min(7.0,(MAX_WIDTH-80.0)/Math.max(1,model.totalFrames()));
    }
    private int xFor(long frames) { return (int)Math.min(MAX_WIDTH,Math.round(LEFT+frames*scale)); }
    private long rawFrameAt(int x) {
        ensureIndex();
        return Math.max(0L,Math.round((x-LEFT)/scale));
    }
    private int frameAt(int x) {
        return (int)Math.min(editor.project().totalFrames()-1,rawFrameAt(x));
    }
    private int clipAtFrame(int frame) {
        ensureIndex();
        int at=Arrays.binarySearch(starts,(long)frame);
        if (at>=0) return Math.min(editor.project().timeline.size()-1,at);
        return Math.min(editor.project().timeline.size()-1,Math.max(0,-at-2));
    }
    private Model.AudioClip hitAudio(int x,int y) {
        java.util.List<Model.AudioClip> clips=editor.project().audioClips;
        for (int i=clips.size()-1;i>=0;i--) {
            Model.AudioClip clip=clips.get(i);
            int y0=109+(i%2)*34;
            if (y>=y0 && y<=y0+30 && x>=xFor(clip.startFrame)-2 && x<=xFor(clip.endFrame())+8)
                return clip;
        }
        return null;
    }
    private int hitHandle(int x) {
        ensureIndex();
        int around=clipAtFrame(frameAt(x));
        int hit=-1;
        int distance=9;
        for (int i=Math.max(0,around-1);i<=Math.min(starts.length-2,around+1);i++) {
            int delta=Math.abs(x-xFor(starts[i+1]));
            if (delta<=distance) { distance=delta; hit=i; }
        }
        return hit;
    }
    void revealFrame(int frame) {
        // Run immediately on the EDT: deferred scrollRectToVisible calls could
        // accumulate during rapid pastes and visibly lag behind the playhead.
        if (SwingUtilities.isEventDispatchThread()) followPlayhead(frame);
        else SwingUtilities.invokeLater(() -> followPlayhead(frame));
    }
    void refresh() {
        // Revalidate width only when duration or number of exposures actually changes.
        boolean changed=indexedProject!=editor.project() || indexedSize!=editor.project().timeline.size()
                        || indexedTotal!=editor.project().totalFrames();
        ensureIndex();
        if (changed) revalidate();
        repaint();
    }
    @Override public Dimension getPreferredSize() {
        ensureIndex();
        JViewport viewport=viewport();
        int trailing=viewport==null ? 320 : Math.max(256,viewport.getWidth()/2);
        // Trailing scroll runway allows dragging the final edge past the viewport.
        // A JScrollPane can display a view wider than its reported preferred size.
        // Never shrink the view below viewport width while an edge drag is active:
        // doing so resets horizontal scroll to zero every time the clip changes.
        int viewportWidth=viewport==null ? 640 : Math.max(640,viewport.getWidth());
        long extent=Math.max((long)viewportWidth+trailing,
                             (long)xFor(starts[starts.length-1])+trailing);
        return new Dimension((int)Math.min(MAX_WIDTH,extent),183);
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        ensureIndex();
        Graphics2D g=(Graphics2D)graphics.create();
        // A bare JComponent has no UI delegate to paint its opaque background.
        // Clear the full clip on every repaint to avoid stale pixels and grey
        // unpainted regions when the viewport moves or the timeline shrinks.
        g.setColor(getBackground());
        g.fillRect(0,0,getWidth(),getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        Rectangle visible=g.getClipBounds();
        if (visible==null) visible=new Rectangle(0,0,getWidth(),getHeight());
        long from=Math.max(0,(long)((visible.x-LEFT)/scale));
        long to=Math.min(editor.project().totalFrames(),(long)((visible.x+visible.width-LEFT)/scale)+1);
        int spacing=Math.max(editor.project().fps,
                (int)Math.ceil(70.0/Math.max(0.001,scale)/editor.project().fps)*editor.project().fps);
        long tick=(from/spacing)*spacing;
        for (long f=tick;f<=to;f+=spacing) {
            int x=xFor(f);
            g.setColor(new Color(0x46515D)); g.drawLine(x,13,x,177);
            g.setColor(new Color(0xBFC9D3));
            g.drawString((f/editor.project().fps)+"s",x+3,12);
        }
        int index=clipAtFrame((int)Math.min(Integer.MAX_VALUE,from));
        for (int i=index;i<editor.project().timeline.size();i++) {
            int x=xFor(starts[i]), right=xFor(starts[i+1]);
            if (x>visible.x+visible.width) break;
            if (right<visible.x) continue;
            int w=Math.max(1,right-x);
            g.setColor(i==editor.selected()?new Color(0x4C9BDB):
                editor.poseSelected(i)?new Color(0x397DAD):new Color(0x587488));
            g.fillRoundRect(x+1,32,Math.max(1,w-2),54,5,5);
            g.setColor(editor.poseSelected(i)?new Color(0xE1F4FF):new Color(0x3C5260));
            g.drawRoundRect(x+1,32,Math.max(1,w-2),54,5,5);
            if (w>36) {
                g.setColor(Color.WHITE);
                java.awt.Shape old=g.getClip();
                g.clipRect(x+5,40,Math.max(1,w-15),36);
                g.drawString("Pose "+(i+1),x+9,61);
                g.setClip(old);
                g.drawLine(right-7,40,right-7,80);
            }
        }
        g.setColor(new Color(0x1B252C));
        g.fillRect(0,105,getWidth(),74);
        g.setColor(new Color(0xB8C8D1));
        g.drawString("AUDIO",5,102);
        if (editor.project().audioClips.isEmpty()) {
            g.setColor(new Color(0x8A9CA6));
            g.drawString("+ Audio to import a sound clip",LEFT+10,130);
        }
        for (int i=0;i<editor.project().audioClips.size();i++) {
            Model.AudioClip clip=editor.project().audioClips.get(i);
            int x=xFor(clip.startFrame), right=xFor(clip.endFrame());
            if (right<visible.x || x>visible.x+visible.width) continue;
            int y=109+(i%2)*34, w=Math.max(3,right-x);
            boolean focused=clip.id.equals(editor.selectedAudioId());
            g.setColor(clip.muted?new Color(0x687174):new Color(focused?0x36A88C:0x407D74));
            g.fillRoundRect(x+1,y,w-2,28,5,5);
            g.setColor(focused?Color.WHITE:new Color(0x86C0B1));
            g.drawRoundRect(x+1,y,w-2,28,5,5);
            java.awt.Shape clipArea=g.getClip();
            g.clipRect(x+5,y+3,Math.max(1,w-15),23);
            String name=java.nio.file.Path.of(clip.sourcePath).getFileName().toString();
            g.setColor(Color.WHITE);
            g.drawString((clip.muted?"[Muted] ":"♫ ")+name,x+7,y+18);
            g.setClip(clipArea);
            g.setColor(Color.WHITE);
            g.drawLine(right-6,y+5,right-6,y+23);
        }
        int playhead=xFor(editor.frame());
        g.setColor(new Color(0xFFBD57));
        g.fillPolygon(new int[]{playhead-6,playhead+6,playhead},new int[]{20,20,29},3);
        g.setStroke(new java.awt.BasicStroke(2));
        g.drawLine(playhead,27,playhead,178);
        g.dispose();
    }
}
