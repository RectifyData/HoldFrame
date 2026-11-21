package dev.rectifydata.holdframe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import dev.rectifydata.holdframe.core.Model;

/** Exposure rectangles with draggable right edges, not one cell per rendered frame. */
public final class TimelineView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final MainActivity editor;
    private float unit;
    private final float density;
    private int resizing = -1;
    private boolean seeking;
    TimelineView(Context context, MainActivity editor) {
        super(context);
        this.editor = editor;
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.rgb(38, 44, 53));
        setMinimumHeight(dp(146));
    }
    private int dp(float dp) { return Math.round(dp*density); }
    private void updateUnit() {
        // The 20k-pixel cap avoids absurd view dimensions for hours-long holds.
        unit = Math.min(dp(5), 20000f / Math.max(1,editor.project().totalFrames()));
    }
    void refresh() {
        updateUnit();
        int width = Math.max(dp(400), Math.min(20_080,(int)(dp(18)+editor.project().totalFrames()*unit+dp(70))));
        ViewGroup.LayoutParams old = getLayoutParams();
        if (old != null && old.width != width) {
            old.width = width;
            setLayoutParams(old);
        }
        invalidate();
    }
    private int start(int i) {
        return editor.project().clipStart(i);
    }
    private int frame(float x) {
        return Math.max(0,(int)Math.round((x-dp(12)) / Math.max(0.01f,unit)));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Model.Project project=editor.project();
        paint.setTextSize(dp(12));
        float header = dp(22);
        int tickFrames=Math.max(project.fps, (int)Math.ceil(dp(68) / Math.max(0.001f,unit) / project.fps)*project.fps);
        for (long f=0;f<=project.totalFrames();f+=tickFrames) {
            float x=dp(12)+f*unit;
            if (x > getWidth()) break;
            paint.setColor(Color.rgb(76,86,100));
            canvas.drawLine(x,dp(19),x,dp(93),paint);
            paint.setColor(Color.rgb(203,214,225));
            canvas.drawText((f/project.fps)+"s",x+dp(3),dp(13),paint);
        }
        long cursor=0;
        for (int i=0;i<project.timeline.size();i++) {
            Model.Exposure clip=project.timeline.get(i);
            float x=dp(12)+cursor*unit;
            cursor += clip.duration;
            if (x>getWidth()) break;
            float width=clip.duration*unit;
            paint.setColor(i==editor.selected() ? Color.rgb(65,151,222) :
                editor.poseSelected(i) ? Color.rgb(47,121,175) : Color.rgb(82,111,137));
            canvas.drawRoundRect(x+1,header+dp(7),x+Math.max(1,width-1),header+dp(59),dp(4),dp(4),paint);
            paint.setColor(Color.WHITE);
            if (width > dp(36)) {
                canvas.save();
                canvas.clipRect(x+dp(4),header+dp(7),x+width-dp(8),header+dp(59));
                canvas.drawText("Pose "+(i+1),x+dp(8),header+dp(34),paint);
                canvas.restore();
            }
            paint.setStrokeWidth(dp(2));
            canvas.drawLine(x+width-dp(5),header+dp(15),x+width-dp(5),header+dp(52),paint);
        }
        paint.setColor(Color.rgb(180,199,213));
        canvas.drawText("AUDIO",dp(8),dp(104),paint);
        for (Model.AudioClip clip:project.audioClips) {
            float left=dp(12)+clip.startFrame*unit;
            float right=dp(12)+clip.endFrame()*unit;
            if (right<0 || left>getWidth()) continue;
            paint.setColor(clip.muted?Color.rgb(98,113,121):Color.rgb(41,146,123));
            canvas.drawRoundRect(left,dp(109),right,dp(136),dp(4),dp(4),paint);
        }
        float head=dp(12)+editor.currentFrame()*unit;
        paint.setColor(Color.rgb(255,187,78));
        paint.setStrokeWidth(dp(2));
        canvas.drawLine(head,dp(21),head,dp(139),paint);
        canvas.drawCircle(head,dp(21),dp(4),paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int type=event.getActionMasked();
        if (type==MotionEvent.ACTION_DOWN) {
            resizing=-1;
            seeking=false;
            if (event.getY()>=dp(25) && event.getY()<dp(97)) {
                float nearest=dp(13);
                long cursor=0;
                for (int i=0;i<editor.project().timeline.size();i++) {
                    cursor+=editor.project().timeline.get(i).duration;
                    float edge=dp(12)+cursor*unit;
                    float delta=Math.abs(event.getX()-edge);
                    if (delta<nearest) { nearest=delta; resizing=i; }
                }
            }
            if (resizing<0) {
                seeking=true;
                editor.seek(frame(event.getX()));
            } else {
                editor.stopPlayback();
            }
            getParent().requestDisallowInterceptTouchEvent(resizing>=0);
            return true;
        }
        if (type==MotionEvent.ACTION_MOVE) {
            if (resizing>=0) {
                int length=frame(event.getX())-start(resizing);
                editor.extendClip(resizing,Math.max(1,length));
            } else if (seeking) {
                // Let the HorizontalScrollView intercept large horizontal drags to pan.
                editor.seek(frame(event.getX()));
            }
            return true;
        }
        if (type==MotionEvent.ACTION_UP || type==MotionEvent.ACTION_CANCEL) {
            resizing=-1;
            seeking=false;
            return true;
        }
        return true;
    }
}
