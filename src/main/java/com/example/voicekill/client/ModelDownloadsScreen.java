package com.example.voicekill.client;

import com.example.voicekill.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Reads immutable download snapshots each frame; never downloads or hashes files on the render thread. */
public final class ModelDownloadsScreen extends Screen {
    private record Model(String id,String label,Path directory,boolean enabled,long size,String error) {}
    private final Screen parent;
    private List<Model> models=List.of();
    private int page,capacity;
    public ModelDownloadsScreen(Screen parent){super(Component.literal("模型下载进度 · 实时"));this.parent=parent;}
    @Override protected void init() {
        clearWidgets();
        ModConfig c=ModConfig.get();if(c==null)c=new ModConfig();
        Path root=FabricLoader.getInstance().getGameDir().resolve("voicekill/models");
        boolean bilingual=c.speechEngine.equals("zipformer");
        models=List.of(model("zipformer-bilingual-small-int8","中英双语识别",root.resolve("zipformer-bilingual-small-int8"),c.speechModelDir,false,bilingual),
                model("silero-vad","声学自然断句",root.resolve("silero-vad"),c.speechPostModelDir,true,bilingual&&c.speechNaturalBoundary),
                model("sensevoice-review-int8","句末音频复核",root.resolve("sensevoice-review-int8"),c.speechPostModelDir,true,c.speechSentenceReview),
                model("punctuation-zh-en-int8","离线标点恢复",root.resolve("punctuation-zh-en-int8"),c.speechPostModelDir,true,c.speechPunctuation));
        capacity=Math.max(1,(height-104)/64);int pages=(models.size()+capacity-1)/capacity;page=Math.min(page,pages-1);
        int panel=Math.min(560,width-24),left=(width-panel)/2;
        if(pages>1) {
            var previous=Button.builder(Component.literal("上一页"),b->{page--;init();}).bounds(left,height-55,76,18).build();previous.active=page>0;addRenderableWidget(previous);
            var next=Button.builder(Component.literal("下一页"),b->{page++;init();}).bounds(left+panel-76,height-55,76,18).build();next.active=page+1<pages;addRenderableWidget(next);
        }
        addRenderableWidget(Button.builder(Component.literal("返回设置"),b->onClose()).bounds(width/2-90,height-27,180,20).build());
    }
    private Model model(String id,String label,Path fallback,String configured,boolean parentDirectory,boolean enabled) {
        try {
            Path path=configured.isBlank()?fallback:parentDirectory?Path.of(configured).resolve(id):Path.of(configured);
            return new Model(id,label,path,enabled,SpeechModels.manifest(id).files().stream().mapToLong(SpeechModels.Asset::size).sum(),"");
        } catch(Exception failure){return new Model(id,label,null,enabled,0,CaptureSession.rootCause(failure));}
    }
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float tick) {
        super.render(g,mouseX,mouseY,tick);g.drawCenteredString(font,title,width/2,12,0xFFFFFFFF);
        int panel=Math.min(560,width-24),left=(width-panel)/2;
        String note="按已保存的设置显示；保存并重连后开始准备，悬停查看详情";
        g.drawCenteredString(font,clip(note,panel),width/2,30,0xFF9FAAB5);
        for(int i=page*capacity;i<Math.min(models.size(),(page+1)*capacity);i++) {
            Model m=models.get(i);SpeechModels.Progress p=m.directory()==null?null:SpeechModels.progress(m.directory());
            if(p!=null&&!p.id().equals(m.id()))p=null;
            int y=48+(i-page*capacity)*64;
            g.fill(left,y,left+panel,y+59,0x88000000);
            String state=p==null?(m.error().isEmpty()?(m.enabled()?"尚未检查 · 连接后校验":"未启用"):"路径或清单错误"):stage(p.stage());
            int color=p!=null&&(p.stage()==SpeechModels.Stage.FAILED||p.stage()==SpeechModels.Stage.CANCELLED)?0xFFFF8C8C:0xFF76CFEF;
            double fraction=p==null?0:p.fraction();
            g.drawString(font,m.label(),left+6,y+5,0xFFE6EDF3,false);
            String percent=p==null?"":String.format(Locale.ROOT,"%.1f%%",fraction*100);
            g.drawString(font,percent,left+panel-font.width(percent)-6,y+5,color,false);
            g.drawString(font,clip(state,panel-12),left+6,y+17,color,false);
            g.fill(left+6,y+30,left+panel-6,y+35,0xFF263442);
            g.fill(left+6,y+30,left+6+(int)((panel-12)*fraction),y+35,color);
            String detail=p==null?"总大小 "+size(m.size()):size(p.completedBytes())+" / "+size(p.totalBytes())+" · "+p.fileIndex()+"/"+p.fileCount()+" "+(p.file().isEmpty()?"已校验":p.file());
            if(p!=null&&!p.error().isEmpty())detail=p.error();
            g.drawString(font,clip(detail,panel-12),left+6,y+43,0xFFB5C2CF,false);
            if(mouseX>=left&&mouseX<left+panel&&mouseY>=y&&mouseY<y+59) {
                String tooltip=(m.directory()==null?m.error():m.directory().toString())+"\n"+state+"\n"+detail;
                g.setTooltipForNextFrame(font,Component.literal(tooltip),mouseX,mouseY);
            }
        }
        int pages=(models.size()+capacity-1)/capacity;
        if(pages>1)g.drawCenteredString(font,(page+1)+" / "+pages,width/2,height-51,0xFF9FAAB5);
    }
    private String clip(String value,int width){return font.width(value)<=width?value:font.plainSubstrByWidth(value,Math.max(1,width-font.width("…")))+"…";}
    private static String size(long bytes){return String.format(Locale.ROOT,"%.2f MiB",bytes/1048576.);}
    private static String stage(SpeechModels.Stage stage) {
        return switch(stage){case CHECKING->"正在校验本地文件";case DOWNLOADING->"正在下载";case VERIFYING->"下载完成，正在校验";case READY->"文件就绪（已校验）";case FAILED->"准备失败";case CANCELLED->"已取消";};
    }
}
