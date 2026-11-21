package dev.rectifydata.holdframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import android.view.MotionEvent;
import android.view.View;
import dev.rectifydata.holdframe.core.Model;
import dev.rectifydata.holdframe.core.TextTransformMath;

/** One reusable bitmap is used only as a render cache; project strokes remain vector data. */
public final class DrawingView extends View {
    interface Listener {
        void startStroke();
        void endStroke();
        void beginTextTransform();
        void endTextTransform(int index,Model.TextItem before,Model.TextItem after);
    }
    private final Paint stagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    // At most 8 imported faces are cached to avoid bloating RAM on older devices.
    private final Map<String,Typeface> fontCache = new LinkedHashMap<String,Typeface>(9,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Typeface> eldest) {
            return size()>8;
        }
    };
    private final RectF bounds = new RectF();
    private boolean lowRamMode;
    private Bitmap cache;
    private Canvas raster;
    private Model.Drawing drawing;
    private Map<String,byte[]> images;
    private String decodedId;
    private Bitmap decodedImage;
    // Onion skins render neighboring vectors on demand; no extra frame bitmaps are retained.
    private Model.Drawing previousOnion, nextOnion;
    private boolean onionEnabled, onionPlaying;
    private Model.Stroke stroke;
    private int color = Color.rgb(25, 25, 25);
    private float brushWidth = 6;
    private boolean eraser;
    private Listener listener;
    private float lastX, lastY;
    private boolean textTransformMode,liveTextPreview;
    private int selectedTextIndex=-1,textGesture,resizeCorner=-1;
    private boolean textChanged;
    private float gestureX,gestureY,textLogicalW,textLogicalH;
    private Model.TextItem textBefore;
    private final Paint selectionPaint=new Paint(Paint.ANTI_ALIAS_FLAG);

    // Zoom only transforms the stage, not the saved vector drawing or raster cache.
    private float zoom=1f, panX, panY;
    private boolean panMode, panning, pinching, suppressStrokeUntilUp;
    private float touchX, touchY, pinchSpan, pinchFocusX, pinchFocusY;

    float zoomLevel(){return zoom;}
    boolean isPanMode(){return panMode;}
    void setPanMode(boolean enabled){
        if(enabled)setTextTransformMode(false);
        panMode=enabled;
    }
    void resetZoom(){zoom=1f;panX=panY=0f;invalidate();}
    void changeZoom(float factor){rect();zoomAt(zoom*factor,getWidth()/2f,getHeight()/2f);}
    private void zoomAt(float requested,float focusX,float focusY){
        rect();
        float currentLeft=bounds.left,currentTop=bounds.top;
        float dx=(focusX-currentLeft)/Math.max(1f,bounds.width());
        float dy=(focusY-currentTop)/Math.max(1f,bounds.height());
        float target=Math.max(.5f,Math.min(8f,requested));
        if(!Float.isFinite(target))return;
        zoom=target;
        float fit=Math.min(Math.max(1,getWidth()-24f)/Model.WIDTH,
                           Math.max(1,getHeight()-24f)/Model.HEIGHT);
        float w=Model.WIDTH*fit*zoom,h=Model.HEIGHT*fit*zoom;
        panX=focusX-dx*w-(getWidth()-w)/2f;
        panY=focusY-dy*h-(getHeight()-h)/2f;
        invalidate();
    }
    private static float touchSpacing(MotionEvent e){
        if(e.getPointerCount()<2)return 0f;
        return (float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
    }
    private static float focusX(MotionEvent e){return (e.getX(0)+e.getX(1))/2f;}
    private static float focusY(MotionEvent e){return (e.getY(0)+e.getY(1))/2f;}


    DrawingView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(231, 235, 241));
        brushPaint.setStrokeCap(Paint.Cap.ROUND);
        brushPaint.setStrokeJoin(Paint.Join.ROUND);
        brushPaint.setStyle(Paint.Style.STROKE);
    }
    void setListener(Listener l) { listener = l; }
    boolean isTextTransformMode(){return textTransformMode;}
    int selectedTextIndex(){return selectedTextIndex;}
    void setTextTransformMode(boolean enabled){
        if(textTransformMode==enabled)return;
        finishTextGesture(false);
        textTransformMode=enabled;panMode=false;
        if(!enabled)selectedTextIndex=-1;
        invalidate();
    }
    void selectText(int index){
        if(drawing==null||index<0||index>=drawing.texts.size())return;
        setTextTransformMode(true);
        selectedTextIndex=index;invalidate();
    }

    void setLowRamMode(boolean lowRam) {
        if (lowRamMode==lowRam) return;
        lowRamMode=lowRam;
        releaseMemory();
        rebuild();
    }
    void releaseMemory() {
        if (cache!=null) { cache.recycle(); cache=null; raster=null; }
        clearDecodedImage();
        fontCache.clear();
        invalidate();
    }
    private void clearDecodedImage() {
        if (decodedImage!=null && !decodedImage.isRecycled()) decodedImage.recycle();
        decodedImage=null;
        decodedId=null;
    }
    void setImageAssets(Map<String,byte[]> data) {
        if (images == data) return;
        images = data;
        clearDecodedImage();
        rebuild();
    }
    private Bitmap imageFor(String id) {
        if (id.equals(decodedId)) return decodedImage;
        clearDecodedImage();
        decodedId = id;
        byte[] bytes = images == null ? null : images.get(id);
        if (bytes == null) return null;
        try {
            BitmapFactory.Options info = new BitmapFactory.Options();
            info.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes,0,bytes.length,info);
            if (info.outWidth < 1 || info.outHeight < 1 ||
                (long)info.outWidth * info.outHeight > 8_000_000L) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            int maxDimension=RamProfile.maxDecodedDimension(lowRamMode);
            int maxPixels=RamProfile.maxDecodedPixels(lowRamMode);
            while (Math.max(info.outWidth,info.outHeight)/sample > maxDimension ||
                   ((long)info.outWidth/sample)*(info.outHeight/sample) > maxPixels) sample *= 2;
            opts.inSampleSize=sample;
            // JPEG has no transparency. RGB565 halves decoded image memory for JPEGs.
            opts.inPreferredConfig=lowRamMode && "image/jpeg".equals(info.outMimeType)
                ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            opts.inDither=true;
            decodedImage = BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
        } catch (RuntimeException | OutOfMemoryError ignored) { decodedImage=null; }
        return decodedImage;
    }
    void setOnionEnabled(boolean enabled) { onionEnabled=enabled; invalidate(); }
    boolean isOnionEnabled() { return onionEnabled; }
    void setOnionPlaying(boolean playing) { onionPlaying=playing; invalidate(); }
    void setOnionNeighbors(Model.Drawing previous,Model.Drawing next) {
        previousOnion=previous;
        nextOnion=next;
        if (onionEnabled) invalidate();
    }
    void setBrushColor(int c) { color = c; eraser = false; }
    void setEraser(boolean value) { eraser = value; }
    boolean isEraser() { return eraser; }
    void setBrushWidth(float w) { brushWidth = Math.max(1, Math.min(100, w)); }
    void setDrawing(Model.Drawing d) {
        if (d == drawing) return;
        if(textGesture==0){selectedTextIndex=-1;liveTextPreview=false;}
        drawing = d;
        rebuild();
    }
    void rebuild() {
        if (cache == null) {
            float scale=RamProfile.rasterScale(lowRamMode);
            int width=Math.max(1,Math.round(Model.WIDTH*scale));
            int height=Math.max(1,Math.round(Model.HEIGHT*scale));
            try {
                cache=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError first) {
                // Degrade further instead of allowing a fatal allocation on tight heaps.
                clearDecodedImage();
                System.gc();
                try {
                    cache=Bitmap.createBitmap(Math.max(1,width/2),Math.max(1,height/2),
                        Bitmap.Config.ARGB_8888);
                } catch (OutOfMemoryError second) {
                    cache=null;
                    raster=null;
                    invalidate();
                    return;
                }
            }
            raster=new Canvas(cache);
            raster.scale(cache.getWidth()/(float)Model.WIDTH,
                cache.getHeight()/(float)Model.HEIGHT);
        }
        cache.eraseColor(Color.TRANSPARENT);
        if (drawing != null) {
            // Images render beneath brush strokes and editable text. Originals stay
            // compressed in the project; only a scaled preview is decoded on demand.
            stagePaint.setAlpha(255);
            stagePaint.setFilterBitmap(true);
            for (Model.ImageItem image : drawing.images) {
                Bitmap bitmap=imageFor(image.assetId);
                if (bitmap!=null) {
                    RectF dest=new RectF(image.x,image.y,image.x+image.width,image.y+image.height);
                    raster.drawBitmap(bitmap,null,dest,stagePaint);
                }
            }
            for (Model.Stroke s : drawing.strokes) {
                if (s.points.size() == 0) continue;
                prepare(s);
                Model.Point first = s.points.get(0);
                raster.drawPoint(first.x, first.y, brushPaint);
                if (s.points.size() > 1) {
                    Path path = new Path();
                    path.moveTo(first.x, first.y);
                    for (int i=1; i<s.points.size(); i++) {
                        Model.Point p = s.points.get(i);
                        path.lineTo(p.x, p.y);
                    }
                    raster.drawPath(path, brushPaint);
                }
            }
            brushPaint.setXfermode(null);
            for(int i=0;i<drawing.texts.size();i++){
                if(liveTextPreview&&i==selectedTextIndex)continue;
                renderText(raster,drawing.texts.get(i));
            }
        }
        invalidate();
    }
    private void prepare(Model.Stroke s) {
        brushPaint.setStrokeWidth(s.width);
        brushPaint.setColor(s.color);
        brushPaint.setXfermode(s.eraser ? new PorterDuffXfermode(PorterDuff.Mode.CLEAR) : null);
    }
    private Typeface fontFor(Model.TextItem text) {
        if (text.fontPath!=null && !text.fontPath.isEmpty()) {
            Typeface cached=fontCache.get(text.fontPath);
            if (cached!=null) return cached;
            try {
                File file=new File(text.fontPath);
                if (file.isFile() && file.length()<25_000_000L) {
                    Typeface loaded=Typeface.createFromFile(file);
                    fontCache.put(text.fontPath,loaded);
                    return loaded;
                }
            } catch (RuntimeException ignored) { /* font not present on this device */ }
        }
        return Typeface.create(text.fontFamily,text.style);
    }
    private void renderText(Canvas canvas,Model.TextItem text) {
        if (text.value==null || text.value.isEmpty()) return;
        textPaint.reset();
        textPaint.setAntiAlias(true);
        textPaint.setSubpixelText(true);
        textPaint.setColor(text.color);
        textPaint.setTextSize(text.size);
        textPaint.setTypeface(fontFor(text));
        Paint.FontMetrics metrics=textPaint.getFontMetrics();
        float step=metrics.descent-metrics.ascent+metrics.leading;
        float baseline=-metrics.ascent;
        int saved=canvas.save();
        canvas.translate(text.x,text.y);
        canvas.scale(text.scaleX,text.scaleY);
        String[] lines=text.value.split("\\n",-1);
        for (String line : lines) {
            canvas.drawText(line,0,baseline,textPaint);
            baseline+=step;
        }
        canvas.restoreToCount(saved);
    }
    private RectF textBounds(Model.TextItem text){
        textPaint.setTextSize(text.size);
        textPaint.setTypeface(fontFor(text));
        Paint.FontMetrics metrics=textPaint.getFontMetrics();
        float lineHeight=Math.max(1f,metrics.descent-metrics.ascent+metrics.leading);
        float width=0f;
        String[] lines=text.value==null?new String[0]:text.value.split("\n",-1);
        for(String line:lines)width=Math.max(width,textPaint.measureText(line));
        return new RectF(text.x,text.y,text.x+Math.max(8f,width)*text.scaleX,
                          text.y+Math.max(8f,lineHeight*Math.max(1,lines.length))*text.scaleY);
    }
    private int hitText(float screenX,float screenY){
        if(drawing==null)return -1;
        float x=sx(screenX),y=sy(screenY);
        for(int i=drawing.texts.size()-1;i>=0;i--)
            if(textBounds(drawing.texts.get(i)).contains(x,y))return i;
        return -1;
    }
    private int hitHandle(float x,float y){
        if(drawing==null||selectedTextIndex<0||selectedTextIndex>=drawing.texts.size())return -1;
        RectF b=textBounds(drawing.texts.get(selectedTextIndex));
        float kx=bounds.width()/Model.WIDTH,ky=bounds.height()/Model.HEIGHT;
        float left=bounds.left+b.left*kx,right=bounds.left+b.right*kx;
        float top=bounds.top+b.top*ky,bottom=bounds.top+b.bottom*ky;
        float[][] xy={{left,top},{right,top},{left,bottom},{right,bottom}};
        float hitSize=Math.max(14f,18f*getResources().getDisplayMetrics().density);
        for(int i=0;i<4;i++)if(Math.hypot(x-xy[i][0],y-xy[i][1])<=hitSize)return i;
        return -1;
    }
    private void drawTextHandles(Canvas canvas){
        if(!textTransformMode||drawing==null||selectedTextIndex<0||selectedTextIndex>=drawing.texts.size())return;
        RectF b=textBounds(drawing.texts.get(selectedTextIndex));
        float kx=bounds.width()/Model.WIDTH,ky=bounds.height()/Model.HEIGHT;
        RectF box=new RectF(bounds.left+b.left*kx,bounds.top+b.top*ky,
                            bounds.left+b.right*kx,bounds.top+b.bottom*ky);
        float dp=getResources().getDisplayMetrics().density;
        selectionPaint.setColor(0xFF147BEB);
        selectionPaint.setStrokeWidth(2f*dp);
        selectionPaint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(box,selectionPaint);
        selectionPaint.setStyle(Paint.Style.FILL);
        float[][] xy={{box.left,box.top},{box.right,box.top},{box.left,box.bottom},{box.right,box.bottom}};
        for(float[] p:xy){
            selectionPaint.setColor(Color.WHITE);canvas.drawCircle(p[0],p[1],8f*dp,selectionPaint);
            selectionPaint.setColor(0xFF147BEB);canvas.drawCircle(p[0],p[1],5f*dp,selectionPaint);
        }
    }
    private boolean textTouch(MotionEvent event){
        int type=event.getActionMasked();
        if(type==MotionEvent.ACTION_DOWN){
            resizeCorner=hitHandle(event.getX(),event.getY());
            int index=resizeCorner>=0?selectedTextIndex:hitText(event.getX(),event.getY());
            if(index<0){selectedTextIndex=-1;invalidate();return true;}
            selectedTextIndex=index;
            textGesture=resizeCorner>=0?2:1;
            textChanged=false;
            textBefore=drawing.texts.get(index).copy();
            RectF b=textBounds(textBefore);
            textLogicalW=Math.max(1f,b.width()/Math.max(.05f,textBefore.scaleX));
            textLogicalH=Math.max(1f,b.height()/Math.max(.05f,textBefore.scaleY));
            gestureX=sx(event.getX());gestureY=sy(event.getY());
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();return true;
        }
        if(textGesture==0)return true;
        if(type==MotionEvent.ACTION_MOVE){
            float x=sx(event.getX()),y=sy(event.getY());
            if(!textChanged){
                float threshold=Math.max(2.5f,7f*getResources().getDisplayMetrics().density*
                                        Model.WIDTH/Math.max(1f,bounds.width()));
                if(Math.hypot(x-gestureX,y-gestureY)<threshold)return true;
                if(listener!=null)listener.beginTextTransform();
                if(drawing==null||selectedTextIndex>=drawing.texts.size()){
                    finishTextGesture(false);return true;
                }
                textChanged=true;liveTextPreview=true;
                rebuild(); // Snapshot static background once, not on every motion.
            }
            Model.TextItem item=drawing.texts.get(selectedTextIndex);
            if(textGesture==1){
                item.x=Math.max(-100000f,Math.min(100000f,textBefore.x+x-gestureX));
                item.y=Math.max(-100000f,Math.min(100000f,textBefore.y+y-gestureY));
            }else{
                TextTransformMath.Result result=TextTransformMath.resize(textBefore.x,textBefore.y,
                    textLogicalW,textLogicalH,textBefore.scaleX,textBefore.scaleY,resizeCorner,x,y);
                item.x=result.x;item.y=result.y;item.scaleX=result.scaleX;item.scaleY=result.scaleY;
            }
            invalidate();return true;
        }
        if(type==MotionEvent.ACTION_CANCEL||type==MotionEvent.ACTION_UP){
            finishTextGesture(type==MotionEvent.ACTION_UP);
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return true;
    }
    private void finishTextGesture(boolean commit){
        if(textGesture==0)return;
        if(textChanged&&drawing!=null&&selectedTextIndex>=0&&selectedTextIndex<drawing.texts.size()){
            Model.TextItem changedItem=drawing.texts.get(selectedTextIndex);
            Model.TextItem after=changedItem.copy();
            if(!commit&&textBefore!=null)changedItem.assign(textBefore);
            liveTextPreview=false;rebuild();
            if(commit&&listener!=null&&textBefore!=null)
                listener.endTextTransform(selectedTextIndex,textBefore,after);
        } else liveTextPreview=false;
        textGesture=0;textChanged=false;textBefore=null;resizeCorner=-1;
    }
    private void rect() {
        float scale = Math.min(Math.max(1, getWidth()-24f) / Model.WIDTH,
                               Math.max(1, getHeight()-24f) / Model.HEIGHT);
        float w = Model.WIDTH * scale * zoom, h = Model.HEIGHT * scale * zoom;
        bounds.set((getWidth()-w)/2+panX, (getHeight()-h)/2+panY,
                   (getWidth()+w)/2+panX, (getHeight()+h)/2+panY);
    }
    private void drawOnion(Canvas surface,Model.Drawing neighbor,int tint) {
        if (neighbor==null) return;
        // A temporary single layer respects eraser holes in the neighboring pose.
        int saved=surface.saveLayer(0,0,Model.WIDTH,Model.HEIGHT,null);
        for (Model.Stroke s : neighbor.strokes) {
            if (s.points.isEmpty()) continue;
            prepare(s);
            Model.Point first=s.points.get(0);
            surface.drawPoint(first.x,first.y,brushPaint);
            if (s.points.size()>1) {
                Path path=new Path();
                path.moveTo(first.x,first.y);
                for (int i=1;i<s.points.size();i++) {
                    Model.Point pt=s.points.get(i);
                    path.lineTo(pt.x,pt.y);
                }
                surface.drawPath(path,brushPaint);
            }
        }
        brushPaint.setXfermode(null);
        for (Model.TextItem text : neighbor.texts) renderText(surface,text);
        Paint shade=new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setColor(tint);
        shade.setAlpha(56);
        shade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        surface.drawRect(0,0,Model.WIDTH,Model.HEIGHT,shade);
        shade.setXfermode(null);
        surface.restoreToCount(saved);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        rect();
        stagePaint.setColor(Color.rgb(183, 193, 204));
        canvas.drawRect(bounds.left-2, bounds.top-2, bounds.right+2, bounds.bottom+2, stagePaint);
        stagePaint.setColor(Color.WHITE);
        canvas.drawRect(bounds,stagePaint);
        if (onionEnabled && !onionPlaying) {
            canvas.save();
            canvas.clipRect(bounds);
            canvas.translate(bounds.left,bounds.top);
            canvas.scale(bounds.width()/Model.WIDTH,bounds.height()/Model.HEIGHT);
            drawOnion(canvas,previousOnion,0xFFF05066);
            if (!lowRamMode) drawOnion(canvas,nextOnion,0xFF3992ED);
            canvas.restore();
        }
        if (cache == null) rebuild();
        if (cache != null) {
            stagePaint.setColor(Color.WHITE);
            canvas.drawBitmap(cache,null,bounds,stagePaint);
        }
        if(liveTextPreview&&drawing!=null&&selectedTextIndex>=0&&selectedTextIndex<drawing.texts.size()){
            int saved=canvas.save();
            canvas.clipRect(bounds);
            canvas.translate(bounds.left,bounds.top);
            canvas.scale(bounds.width()/Model.WIDTH,bounds.height()/Model.HEIGHT);
            renderText(canvas,drawing.texts.get(selectedTextIndex));
            canvas.restoreToCount(saved);
        }
        drawTextHandles(canvas);
    }
    private float sx(float x) {
        return Math.max(0,Math.min(Model.WIDTH,(x-bounds.left)*Model.WIDTH/Math.max(1,bounds.width())));
    }
    private float sy(float y) {
        return Math.max(0,Math.min(Model.HEIGHT,(y-bounds.top)*Model.HEIGHT/Math.max(1,bounds.height())));
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (drawing == null) return false;
        rect();
        int type=event.getActionMasked();
        if(type==MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount()>=2){
            if(textGesture!=0)finishTextGesture(true);
            // Stop an uncommitted brush stroke before beginning a pinch.
            if(stroke!=null){stroke=null;rebuild();}
            pinching=true;panning=false;suppressStrokeUntilUp=true;
            pinchSpan=touchSpacing(event);
            pinchFocusX=focusX(event);pinchFocusY=focusY(event);
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if(pinching){
            if(type==MotionEvent.ACTION_MOVE && event.getPointerCount()>=2){
                float x=focusX(event),y=focusY(event),span=touchSpacing(event);
                panX+=x-pinchFocusX;panY+=y-pinchFocusY;
                if(pinchSpan>1f&&span>1f)zoomAt(zoom*span/pinchSpan,x,y);
                pinchSpan=span;pinchFocusX=x;pinchFocusY=y;
                invalidate();
            }
            if(type==MotionEvent.ACTION_POINTER_UP || type==MotionEvent.ACTION_CANCEL || type==MotionEvent.ACTION_UP)pinching=false;
            return true;
        }
        if(suppressStrokeUntilUp){
            if(type==MotionEvent.ACTION_UP || type==MotionEvent.ACTION_CANCEL){
                suppressStrokeUntilUp=false;
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }
        if(panMode){
            if(type==MotionEvent.ACTION_DOWN){
                panning=true;touchX=event.getX();touchY=event.getY();return true;
            }
            if(type==MotionEvent.ACTION_MOVE && panning){
                panX+=event.getX()-touchX;panY+=event.getY()-touchY;
                touchX=event.getX();touchY=event.getY();invalidate();return true;
            }
            if(type==MotionEvent.ACTION_UP || type==MotionEvent.ACTION_CANCEL){
                panning=false;return true;
            }
        }
        if(textTransformMode)return textTouch(event);
        if (type == MotionEvent.ACTION_DOWN) {
            if (!bounds.contains(event.getX(),event.getY())) return false;
            if (listener != null) listener.startStroke();
            if (raster==null) { rebuild(); if (raster==null) return false; }
            stroke = new Model.Stroke(color,brushWidth,eraser);
            lastX=sx(event.getX()); lastY=sy(event.getY());
            stroke.points.add(new Model.Point(lastX,lastY));
            prepare(stroke);
            raster.drawPoint(lastX,lastY,brushPaint);
            brushPaint.setXfermode(null);
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate(); return true;
        }
        if (stroke == null) return false;
        if (type==MotionEvent.ACTION_MOVE || type==MotionEvent.ACTION_UP) {
            prepare(stroke);
            for (int i=0; i<event.getHistorySize(); i++)
                append(sx(event.getHistoricalX(i)),sy(event.getHistoricalY(i)));
            append(sx(event.getX()),sy(event.getY()));
            brushPaint.setXfermode(null);
            invalidate();
        }
        if (type==MotionEvent.ACTION_UP) {
            drawing.strokes.add(stroke);
            stroke=null;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (listener != null) listener.endStroke();
        } else if (type==MotionEvent.ACTION_CANCEL) {
            stroke=null;
            getParent().requestDisallowInterceptTouchEvent(false);
            rebuild();
        }
        return true;
    }
    private void append(float x, float y) {
        if (Math.abs(x-lastX)<0.1 && Math.abs(y-lastY)<0.1) return;
        stroke.points.add(new Model.Point(x,y));
        // The OS may reclaim the render cache during an in-progress gesture.
        if (raster==null) rebuild();
        if (raster!=null) raster.drawLine(lastX,lastY,x,y,brushPaint);
        lastX=x; lastY=y;
    }
}
