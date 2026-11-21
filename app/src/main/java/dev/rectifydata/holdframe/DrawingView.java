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

/** One reusable bitmap is used only as a render cache; project strokes remain vector data. */
public final class DrawingView extends View {
    interface Listener { void startStroke(); void endStroke(); }
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

    DrawingView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(231, 235, 241));
        brushPaint.setStrokeCap(Paint.Cap.ROUND);
        brushPaint.setStrokeJoin(Paint.Join.ROUND);
        brushPaint.setStyle(Paint.Style.STROKE);
    }
    void setListener(Listener l) { listener = l; }
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
            for (Model.TextItem text : drawing.texts) renderText(raster,text);
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
    private void rect() {
        float scale = Math.min(Math.max(1, getWidth()-24f) / Model.WIDTH,
                               Math.max(1, getHeight()-24f) / Model.HEIGHT);
        float w = Model.WIDTH * scale, h = Model.HEIGHT * scale;
        bounds.set((getWidth()-w)/2, (getHeight()-h)/2,
                   (getWidth()+w)/2, (getHeight()+h)/2);
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
