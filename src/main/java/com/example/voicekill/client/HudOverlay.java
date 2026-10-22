package com.example.voicekill.client;

import com.example.voicekill.config.ModConfig;
import com.example.voicekill.network.HudStatePayload;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;

/** 服务端状态只负责显示；临时转写与文字标色不进入处罚路径。 */
public final class HudOverlay implements HudElement {
    private static final int LABEL=0xFF8B98A5,TEXT=0xFFE6EDF3,WORD=0xFFFF5A5F,ACCENT=0xFF3FBEEF,PINK=0xFF77C8;
    private record State(boolean running,List<String> words,int remaining,List<String> names,List<Integer> counts,int total,long changed) {}
    private static volatile State state=new State(false,List.of(),0,List.of(),List.of(),0,0);
    public static void update(HudStatePayload p) {
        State old=state;boolean changed=!old.words.equals(p.words())||old.running!=p.running();
        state=new State(p.running(),List.copyOf(p.words()),p.remainingSeconds(),List.copyOf(p.boardNames()),List.copyOf(p.boardCounts()),p.totalDeaths(),changed?System.nanoTime():old.changed);
        if(changed)SpeechTranscript.clear();
        if(!p.running())HotbarHint.clear();
    }
    public static List<String> currentWords(){return state.words;}

    @Override public void render(GuiGraphics graphics,DeltaTracker delta) {
        Minecraft client=Minecraft.getInstance();ModConfig config=ModConfig.get();
        if(client.level==null||client.options.hideGui||config==null||!config.hudEnabled)return;
        draw(graphics,config,state,SpeechTranscript.view(),HotbarHint.view());
    }
    public static void renderDemo(GuiGraphics graphics,ModConfig config) {
        List<String> words=List.of("qaq","p老板","豆腐汤宝宝","钻石","苦力怕","岩浆","末影人","图腾","村庄","工作台","下界","苹果","火把","盾牌","挖矿","铁锭","玻璃","末地","箱子","珍珠");
        State sample=new State(true,words,24,List.of("PlayerOne","PlayerTwo","PlayerThree"),List.of(5,3,1),9,System.nanoTime());
        new HudOverlay().draw(graphics,config,sample,new SpeechTranscript.View("今天和 P老板 一起找豆腐汤宝宝，顺便测试 QAQ。",false,System.nanoTime()),new HotbarHint.View(Component.literal("本轮危险词："+String.join(" / ",words)),System.nanoTime()));
    }
    private int background(ModConfig c){return Math.round(c.hudPanelOpacity*255)<<24;}
    private void draw(GuiGraphics g,ModConfig c,State s,SpeechTranscript.View speech,HotbarHint.View hint) {
        Font font=Minecraft.getInstance().font;
        int wordWidth=Math.max(60,Math.min(c.hudWordWidth,g.guiWidth()/2-12));
        var wordPanel=drawWords(g,font,c,s,wordWidth);
        int bottom=wordPanel.y()+wordPanel.height();
        var speechPanel=drawSpeech(g,font,c,s.running?s.words:List.of(),speech,6,bottom+4,wordWidth);
        if(s.running)drawTimer(g,font,c,s.remaining);
        if(c.hudShowBoard)drawBoard(g,font,c,s,Math.max(wordPanel.x()+wordPanel.width(),speechPanel.x()+speechPanel.width()),Math.max(bottom,speechPanel.y()+speechPanel.height()));
        if(c.hudHotbarHint)drawHotbar(g,font,c,hint);
    }
    private HudPlacement.Rect drawWords(GuiGraphics g,Font f,ModConfig c,State s,int maxWidth) {
        if(!s.running){String text="未开始 · /vk start";int width=f.width(text)+10;g.fill(6,6,6+width,24,background(c));g.drawString(f,text,10,11,ACCENT,false);return new HudPlacement.Rect(6,6,width,18);}
        var all=f.split(Component.literal(String.join(" · ",s.words)),maxWidth);
        if(all.isEmpty())return new HudPlacement.Rect(6,6,0,0);
        int count=Math.min(c.hudWordLines,Math.max(1,(g.guiHeight()/2-30)/10));
        int pages=(all.size()+count-1)/count;
        int page=pages<=1?0:(int)(((System.nanoTime()-s.changed)/2_000_000_000L)%pages);
        var lines=all.subList(page*count,Math.min(all.size(),(page+1)*count));
        int panelHeight=14+lines.size()*10;
        String title="本轮危险词"+(pages>1?" "+(page+1)+"/"+pages:"");
        int panelWidth=contentWidth(f,lines,title)+10;
        g.fill(6,6,6+panelWidth,6+panelHeight,background(c));
        g.drawString(f,title,10,8,LABEL,false);
        for(int i=0;i<lines.size();i++)g.drawString(f,lines.get(i),10,19+i*10,WORD,true);
        return new HudPlacement.Rect(6,6,panelWidth,panelHeight);
    }
    private HudPlacement.Rect drawSpeech(GuiGraphics g,Font f,ModConfig c,List<String> words,SpeechTranscript.View speech,int x,int y,int width) {
        if(!c.hudTranscript||speech.text().isEmpty()||System.nanoTime()-speech.updatedNanos()>c.hudTranscriptSeconds*1e9)return new HudPlacement.Rect(x,y,0,0);
        MutableComponent component=Component.empty();
        for(var run:SpeechHighlight.runs(speech.text(),words))component.append(Component.literal(run.text()).withStyle(style->style.withColor(run.danger()?PINK:0xE6EDF3)));
        var all=f.split(component,width);int room=Math.max(0,(g.guiHeight()-y-26)/10);
        int count=Math.min(Math.min(c.hudTranscriptLines,room),all.size());if(count<1)return new HudPlacement.Rect(x,y,0,0);
        int from=all.size()-count;
        String title=speech.interim()?"你说的话 · 识别中":"你说的话";
        int panelWidth=contentWidth(f,all.subList(from,all.size()),title)+10;
        g.fill(x,y,x+panelWidth,y+14+count*10,background(c));
        g.drawString(f,title,x+4,y+2,LABEL,false);
        for(int i=0;i<count;i++)g.drawString(f,all.get(from+i),x+4,y+13+i*10,TEXT,false);
        return new HudPlacement.Rect(x,y,panelWidth,14+count*10);
    }
    private static int contentWidth(Font font,List<FormattedCharSequence> lines,String title) {
        return Math.max(font.width(title),lines.stream().mapToInt(font::width).max().orElse(0));
    }
    private void drawTimer(GuiGraphics g,Font f,ModConfig c,int remaining) {
        String text=remaining+"s 后换词";int right=g.guiWidth()-6,left=right-f.width(text)-12;
        g.fill(left,6,right,30,background(c));g.drawString(f,text,left+6,14,ACCENT,true);
    }
    private void drawBoard(GuiGraphics g,Font f,ModConfig c,State s,int leftPanelRight,int leftPanelBottom) {
        if(s.names.isEmpty())return;
        int lines=Math.min(s.names.size(),5),height=16+lines*11+(s.total>0?12:0),width=f.width("言多必失榜");
        for(int i=0;i<lines;i++)width=Math.max(width,f.width((i+1)+". "+s.names.get(i)+"  "+(i<s.counts.size()?s.counts.get(i):0)));
        width=Math.max(width,f.width("共 "+s.total+" 次"))+12;
        var r=HudPlacement.board(g.guiWidth(),g.guiHeight(),width,height,c.hudBoardX,c.hudBoardY);
        if(r.x()<leftPanelRight&&r.y()<leftPanelBottom+4)
            r=HudPlacement.below(r,leftPanelBottom+4,g.guiHeight());
        if(s.running&&r.x()+r.width()>g.guiWidth()-f.width(s.remaining+"s 后换词")-18&&r.y()<34)
            r=HudPlacement.below(r,34,g.guiHeight());
        g.fill(r.x(),r.y(),r.x()+r.width(),r.y()+r.height(),background(c));
        g.drawString(f,"言多必失榜",r.x()+4,r.y()+2,LABEL,false);
        for(int i=0;i<lines;i++){
            String text=(i+1)+". "+s.names.get(i)+"  "+(i<s.counts.size()?s.counts.get(i):0);
            if(f.width(text)>r.width()-8)text=f.plainSubstrByWidth(text,Math.max(1,r.width()-8-f.width("…")))+"…";
            g.drawString(f,text,r.x()+4,r.y()+13+i*11,i==0?0xFFFFD479:TEXT,false);
        }
        if(s.total>0)g.drawString(f,"共 "+s.total+" 次",r.x()+4,r.y()+13+lines*11,LABEL,false);
    }
    private void drawHotbar(GuiGraphics g,Font f,ModConfig c,HotbarHint.View hint) {
        if(hint.updatedNanos()==0)return;
        int width=Math.max(40,Math.min(c.hudHotbarWidth,g.guiWidth()-24));
        var all=f.split(hint.text(),width);if(all.isEmpty())return;
        int count=Math.max(1,Math.min(8,(g.guiHeight()-c.hudHotbarBottom-32)/10));
        int pages=(all.size()+count-1)/count;
        double age=(System.nanoTime()-hint.updatedNanos())/1e9;
        if(age>Math.max(c.hudHotbarSeconds,pages*1.5))return;
        int page=Math.min(pages-1,(int)(age/1.5));
        List<FormattedCharSequence> lines=all.subList(page*count,Math.min(all.size(),(page+1)*count));
        String pageLabel=pages>1?(page+1)+" / "+pages:"";
        int contentWidth=contentWidth(f,lines,pageLabel);
        int height=lines.size()*10+8+(pages>1?10:0),x=(g.guiWidth()-contentWidth-12)/2,y=Math.max(6,g.guiHeight()-c.hudHotbarBottom-height);
        g.fill(x,y,x+contentWidth+12,y+height,background(c));
        for(int i=0;i<lines.size();i++)g.drawString(f,lines.get(i),x+6,y+4+i*10,WORD,true);
        if(pages>1)g.drawString(f,pageLabel,x+6,y+4+lines.size()*10,LABEL,false);
    }
}
