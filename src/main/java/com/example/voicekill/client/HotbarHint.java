package com.example.voicekill.client;

import net.minecraft.network.chat.Component;

/** 保留服务端提示的样式，客户端按像素宽度换行。 */
public final class HotbarHint {
    public record View(Component text,long updatedNanos) {}
    private static volatile View latest=new View(Component.empty(),0);
    private HotbarHint() {}
    public static void show(Component text){latest=new View(text.copy(),System.nanoTime());}
    public static View view(){return latest;}
    public static void clear(){latest=new View(Component.empty(),0);}
}
