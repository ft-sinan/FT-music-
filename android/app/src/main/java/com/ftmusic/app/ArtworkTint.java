package com.ftmusic.app;

/** Produces a dark, readable player background from artwork without depending on Android. */
final class ArtworkTint {
    static int[] fromPixels(int[] pixels) {
        long red = 0, green = 0, blue = 0;
        int count = 0;
        if (pixels != null) for (int pixel : pixels) {
            if ((pixel >>> 24) < 128) continue;
            red += (pixel >>> 16) & 255;
            green += (pixel >>> 8) & 255;
            blue += pixel & 255;
            count++;
        }
        if (count == 0) return new int[] {0xff5a405e, 0xff201b2c};
        int r = (int) (red / count), g = (int) (green / count), b = (int) (blue / count);
        int max = Math.max(1, Math.max(r, Math.max(g, b)));
        float scale = Math.min(.55f, 90f / max);
        return new int[] {
                color(16 + Math.round(r * scale), 16 + Math.round(g * scale), 16 + Math.round(b * scale)),
                color(15 + Math.round(r * scale * .35f), 15 + Math.round(g * scale * .35f), 15 + Math.round(b * scale * .35f))
        };
    }
    private static int color(int r, int g, int b) { return 0xff000000 | (r << 16) | (g << 8) | b; }
    private ArtworkTint() { }
}
