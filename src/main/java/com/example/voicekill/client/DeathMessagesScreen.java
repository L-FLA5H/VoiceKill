package com.example.voicekill.client;

import com.example.voicekill.DeathMessages;
import com.example.voicekill.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** Local editor stages changes into the parent draft; saving remains a server-owned operation. */
public final class DeathMessagesScreen extends Screen {
    private final Screen parent;private final ModConfig draft;private final Runnable changed;
    private final List<String> templates;private List<String> customTemplates;
    private DeathMessages.Style style;private Button styleButton;
    private boolean enabled;private int page,previewIndex;
    public DeathMessagesScreen(Screen parent,ModConfig draft,Runnable changed) {
        super(Component.literal("自定义死亡文本"));this.parent=parent;this.draft=draft;this.changed=changed;
        customTemplates=new ArrayList<>(DeathMessages.sanitize(draft.deathMessages));style=DeathMessages.Style.from(draft.deathMessageStyle);
        templates=new ArrayList<>(DeathMessages.pool(style.id,customTemplates));enabled=draft.customDeathMessages;
    }
    @Override protected void init() {
        clearWidgets();int panel=Math.min(560,width-24),left=(width-panel)/2;
        addRenderableWidget(Button.builder(Component.literal("死亡文本："+(enabled?"开":"关")),b->{enabled=!enabled;init();}).bounds(left,46,panel/3-3,20).build());
        styleButton=Button.builder(Component.literal("风格："+style.label),b->{
            if(style==DeathMessages.Style.CUSTOM)customTemplates=new ArrayList<>(templates);
            style=DeathMessages.Style.values()[(style.ordinal()+1)%DeathMessages.Style.values().length];
            templates.clear();templates.addAll(DeathMessages.pool(style.id,customTemplates));page=0;previewIndex=0;init();
        }).bounds(left+panel/3,46,panel-panel/3,20).build();addRenderableWidget(styleButton);
        int capacity=Math.max(1,(height-202)/24),pages=Math.max(1,(templates.size()+capacity-1)/capacity);page=Math.min(page,pages-1);
        previewIndex=Math.min(previewIndex,Math.max(0,templates.size()-1));
        for(int i=page*capacity;i<Math.min(templates.size(),(page+1)*capacity);i++) {
            final int index=i;int y=82+(i-page*capacity)*24;
            var field=new EditBox(font,left,y,panel-30,20,Component.literal("死亡文本 "+(i+1)));
            field.setMaxLength(DeathMessages.MAX_LENGTH*2);field.setValue(templates.get(i));
            field.setResponder(value->{templates.set(index,value);previewIndex=index;markCustom();});addRenderableWidget(field);
            addRenderableWidget(Button.builder(Component.literal("×"),b->{templates.remove(index);markCustom();init();}).bounds(left+panel-26,y,26,20).build());
        }
        int quarter=panel/4;
        var prev=Button.builder(Component.literal("上一页"),b->{page--;init();}).bounds(left,height-115,quarter-3,20).build();prev.active=page>0;addRenderableWidget(prev);
        var next=Button.builder(Component.literal("下一页"),b->{page++;init();}).bounds(left+quarter,height-115,quarter-3,20).build();next.active=page+1<pages;addRenderableWidget(next);
        var add=Button.builder(Component.literal("新增"),b->{templates.add("");markCustom();page=(templates.size()-1)/capacity;previewIndex=templates.size()-1;init();}).bounds(left+quarter*2,height-115,quarter-3,20).build();add.active=templates.size()<DeathMessages.MAX_TEMPLATES;addRenderableWidget(add);
        addRenderableWidget(Button.builder(Component.literal("恢复默认"),b->{templates.clear();templates.addAll(style==DeathMessages.Style.CUSTOM?DeathMessages.defaults():DeathMessages.pool(style.id,customTemplates));if(style==DeathMessages.Style.CUSTOM)markCustom();page=0;previewIndex=0;init();}).bounds(left+quarter*3,height-115,panel-quarter*3,20).build());
        addRenderableWidget(Button.builder(Component.literal("换条预览"),b->{if(!templates.isEmpty())previewIndex=(previewIndex+1)%templates.size();}).bounds(left+panel-82,height-90,82,18).build());
        addRenderableWidget(Button.builder(Component.literal("应用到设置草稿"),b->{draft.customDeathMessages=enabled;draft.deathMessageStyle=style.id;draft.deathMessages=new ArrayList<>(DeathMessages.sanitize(customTemplates));changed.run();onClose();}).bounds(left,height-27,panel/2-3,20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left+panel/2,height-27,panel-panel/2,20).build());
    }
    private void markCustom(){style=DeathMessages.Style.CUSTOM;customTemplates=new ArrayList<>(templates);if(styleButton!=null)styleButton.setMessage(Component.literal("风格："+style.label));}
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float tick) {
        super.render(g,mouseX,mouseY,tick);int panel=Math.min(560,width-24),left=(width-panel)/2;
        g.drawCenteredString(font,title,width/2,12,0xFFFFFFFF);
        String hint="每行一条 · {player} 玩家 · {word} 危险词";
        g.drawCenteredString(font,Component.literal(hint),width/2,30,0xFF9FAAB5);
        g.drawString(font,templates.size()+" / "+DeathMessages.MAX_TEMPLATES+" 条 · 单条最多 "+DeathMessages.MAX_LENGTH+" 字",left,70,0xFF9FAAB5,false);
        g.drawString(font,"预览 · 示例数据",left,height-85,0xFF9FAAB5,false);
        String template=templates.isEmpty()?DeathMessages.defaults().getFirst():templates.get(Math.min(previewIndex,templates.size()-1));
        var lines=font.split(DeathMessages.render(template,Component.literal("Alex"),"qaq"),panel);
        for(int i=0;i<Math.min(3,lines.size());i++)g.drawString(font,lines.get(i),left,height-66+i*10,0xFFE6EDF3,false);
    }
}
