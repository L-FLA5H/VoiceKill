package com.example.voicekill.client;

import com.example.voicekill.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 明确标注示例数据的预览，不改真实词表/排行榜。 */
public final class HudPreviewScreen extends Screen {
    private final Screen parent;private final ModConfig config;
    public HudPreviewScreen(Screen parent,ModConfig config){super(Component.literal("VoiceKill 布局预览 · 示例数据"));this.parent=parent;this.config=config;}
    @Override protected void init(){addRenderableWidget(Button.builder(Component.literal("返回设置"),b->onClose()).bounds(width/2-70,height-27,140,20).build());}
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
    @Override public void render(GuiGraphics g,int x,int y,float tick){super.render(g,x,y,tick);HudOverlay.renderDemo(g,config);g.drawCenteredString(font,title,width/2,height-45,0xFFE6EDF3);}
}
