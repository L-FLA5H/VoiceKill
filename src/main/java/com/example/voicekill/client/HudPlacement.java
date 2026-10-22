package com.example.voicekill.client;

/** 归一化位置，分辨率/GUI 缩放变化后仍留在屏幕内。 */
public final class HudPlacement {
    public record Rect(int x,int y,int width,int height) {}
    private HudPlacement() {}
    public static Rect below(Rect rectangle,int bottom,int screenHeight) {
        return new Rect(rectangle.x(),Math.max(6,Math.min(bottom,screenHeight-rectangle.height()-6)),rectangle.width(),rectangle.height());
    }
    public static Rect board(int screenWidth,int screenHeight,int width,int height,float x,float y) {
        int w=Math.min(Math.max(1,width),Math.max(1,screenWidth-12));
        int h=Math.min(Math.max(1,height),Math.max(1,screenHeight-12));
        float ax=Float.isFinite(x)?Math.max(0,Math.min(1,x)):1;
        float ay=Float.isFinite(y)?Math.max(0,Math.min(1,y)):.5f;
        return new Rect(6+Math.round(Math.max(0,screenWidth-w-12)*ax),6+Math.round(Math.max(0,screenHeight-h-12)*ay),w,h);
    }
}
