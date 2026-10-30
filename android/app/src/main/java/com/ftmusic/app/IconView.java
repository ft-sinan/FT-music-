package com.ftmusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small original line icons, so the native app has no icon-font or UI-library dependency. */
final class IconView extends View {
    private String kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color;
    IconView(Context context, String kind, int color) { super(context); this.kind = kind; this.color = color; setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
    void setKind(String kind) { this.kind = kind; invalidate(); }
    void setColor(int color) { this.color = color; invalidate(); }
    private void line(Canvas c, float x1, float y1, float x2, float y2) { c.drawLine(x1, y1, x2, y2, paint); }
    private void path(Canvas c, float... points) {
        Path p = new Path(); p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
        c.drawPath(p, paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        float s = Math.min(getWidth(), getHeight()) / 24f;
        canvas.translate((getWidth() - s * 24) / 2f, (getHeight() - s * 24) / 2f);
        canvas.scale(s, s);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        if (kind.equals("home")) { path(canvas, 3, 10, 12, 3, 21, 10, 21, 20, 3, 20, 3, 10); path(canvas, 9, 20, 9, 13, 15, 13, 15, 20); }
        else if (kind.equals("search")) { canvas.drawCircle(10, 10, 6.2f, paint); line(canvas, 15, 15, 21, 21); }
        else if (kind.equals("explore")) { canvas.drawRoundRect(3, 3, 10, 10, 1.5f, 1.5f, paint); canvas.drawRoundRect(14, 3, 21, 10, 1.5f, 1.5f, paint); canvas.drawRoundRect(3, 14, 10, 21, 1.5f, 1.5f, paint); canvas.drawRoundRect(14, 14, 21, 21, 1.5f, 1.5f, paint); }
        else if (kind.equals("library")) { canvas.drawRoundRect(3, 4, 8, 20, 1, 1, paint); canvas.drawRoundRect(10, 4, 15, 20, 1, 1, paint); path(canvas, 17, 5, 19, 4, 22, 19, 20, 20, 17, 5); }
        else if (kind.equals("queue")) { line(canvas, 4, 6, 19, 6); line(canvas, 4, 11, 19, 11); line(canvas, 4, 16, 14, 16); path(canvas, 17, 15, 17, 21, 21, 18, 17, 15); }
        else if (kind.equals("play")) { paint.setStyle(Paint.Style.FILL); path(canvas, 7, 3, 20, 12, 7, 21, 7, 3); }
        else if (kind.equals("pause")) { paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(6, 4, 10, 20, 1, 1, paint); canvas.drawRoundRect(14, 4, 18, 20, 1, 1, paint); }
        else if (kind.equals("next")) { paint.setStyle(Paint.Style.FILL); path(canvas, 4, 4, 16, 12, 4, 20, 4, 4); canvas.drawRoundRect(18, 4, 20, 20, 1, 1, paint); }
        else if (kind.equals("previous")) { paint.setStyle(Paint.Style.FILL); path(canvas, 20, 4, 8, 12, 20, 20, 20, 4); canvas.drawRoundRect(4, 4, 6, 20, 1, 1, paint); }
        else if (kind.equals("heart") || kind.equals("heart-filled")) {
            if (kind.equals("heart-filled")) paint.setStyle(Paint.Style.FILL);
            Path p = new Path(); p.moveTo(12, 20); p.cubicTo(9, 17, 3, 13, 3, 8); p.cubicTo(3, 3, 9, 2, 12, 6); p.cubicTo(15, 2, 21, 3, 21, 8); p.cubicTo(21, 13, 15, 17, 12, 20); canvas.drawPath(p, paint);
        }
        else if (kind.equals("down")) { path(canvas, 5, 9, 12, 16, 19, 9); }
        else if (kind.equals("back")) { path(canvas, 15, 5, 8, 12, 15, 19); }
        else if (kind.equals("arrow-right")) { line(canvas, 4, 12, 20, 12); path(canvas, 14, 6, 20, 12, 14, 18); }
        else if (kind.equals("plus")) { line(canvas, 12, 4, 12, 20); line(canvas, 4, 12, 20, 12); }
        else if (kind.equals("more")) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(5, 12, 1.5f, paint); canvas.drawCircle(12, 12, 1.5f, paint); canvas.drawCircle(19, 12, 1.5f, paint); }
        else if (kind.equals("clock")) { canvas.drawCircle(12, 12, 8, paint); path(canvas, 12, 7, 12, 12, 16, 14); }
        else if (kind.equals("link")) { path(canvas, 10, 5, 19, 5, 19, 14); line(canvas, 19, 5, 8, 16); path(canvas, 18, 15, 18, 19, 5, 19, 5, 6, 9, 6); }
        else if (kind.equals("shuffle")) { path(canvas, 4, 5, 7, 5, 17, 19, 20, 19); path(canvas, 17, 16, 20, 19, 17, 22); path(canvas, 4, 19, 7, 19, 10, 15); path(canvas, 14, 9, 17, 5, 20, 5); path(canvas, 17, 2, 20, 5, 17, 8); }
        else if (kind.equals("repeat")) { path(canvas, 5, 9, 5, 6, 18, 6, 21, 9); path(canvas, 18, 3, 21, 6, 18, 9); path(canvas, 19, 15, 19, 18, 6, 18, 3, 15); path(canvas, 6, 21, 3, 18, 6, 15); }
        else if (kind.equals("music")) { line(canvas, 14, 5, 14, 17); line(canvas, 14, 5, 20, 3); canvas.drawCircle(10.5f, 18, 3, paint); }
        canvas.restore();
    }
}
