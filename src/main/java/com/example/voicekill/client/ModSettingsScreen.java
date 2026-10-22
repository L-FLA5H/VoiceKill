package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.net.URI;
import java.util.*;
import java.util.function.*;

/** F7 统一设置。使用草稿，保存时才应用；远程服务器参数不由本地客户端覆盖。 */
public final class ModSettingsScreen extends Screen {
    private interface Row {AbstractWidget create(int x,int y,int width);}
    private record Label(String text,int x,int y) {}
    private final Screen parent;
    private final ModConfig draft;
    private int tab,page;
    private boolean gameDirty;
    private String status="";
    private final List<Label> labels=new ArrayList<>();
    private static final String[] TABS={"语音识别","设备与模型","画面提示","排行榜","游戏与词库"};
    public ModSettingsScreen(Screen parent) {
        super(Component.literal("VoiceKill 设置 · F7"));this.parent=parent;
        ModConfig current=ModConfig.get();draft=ClientSettings.draft(current==null?new ModConfig():current);
    }
    @Override protected void init() {
        clearWidgets();labels.clear();
        int panel=Math.min(560,width-24),left=(width-panel)/2,tabWidth=panel/TABS.length;
        for(int i=0;i<TABS.length;i++) {
            final int index=i;
            var button=Button.builder(Component.literal(TABS[i]),b->{tab=index;page=0;init();}).bounds(left+i*tabWidth,34,tabWidth-3,20).build();
            button.active=i!=tab;addRenderableWidget(button);
        }
        List<Row> rows=rows();int capacity=Math.max(1,(height-134)/24);
        int pages=Math.max(1,(rows.size()+capacity-1)/capacity);page=Math.min(page,pages-1);
        for(int i=page*capacity;i<Math.min(rows.size(),(page+1)*capacity);i++)addRenderableWidget(rows.get(i).create(left,68+(i-page*capacity)*24,panel));
        if(pages>1) {
            var prev=Button.builder(Component.literal("上一页"),b->{page--;init();}).bounds(left,height-65,72,18).build();prev.active=page>0;addRenderableWidget(prev);
            var next=Button.builder(Component.literal("下一页"),b->{page++;init();}).bounds(left+panel-72,height-65,72,18).build();next.active=page+1<pages;addRenderableWidget(next);
            labels.add(new Label((page+1)+" / "+pages,width/2-12,height-60));
        }
        int third=panel/3;
        addRenderableWidget(Button.builder(Component.literal("保存"),b->save(false)).bounds(left,height-27,third-4,20).build());
        addRenderableWidget(Button.builder(Component.literal("保存并重连麦克风"),b->save(true)).bounds(left+third,height-27,third-4,20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left+2*third,height-27,panel-2*third,20).build());
    }
    private List<Row> rows() {
        List<Row> r=new ArrayList<>();
        if(tab==0) {
            r.add(toggle("声学自然断句",()->draft.speechNaturalBoundary,()->draft.speechNaturalBoundary=!draft.speechNaturalBoundary));
            r.add(number("停顿确认",.35,1.5,.05,()->draft.speechEndpointSilenceSeconds,v->draft.speechEndpointSilenceSeconds=(float)v," 秒"));
            r.add(number("持续讲话兜底上限",2,15,1,()->draft.speechMaxUtteranceSeconds,v->draft.speechMaxUtteranceSeconds=(float)v," 秒"));
            r.add(toggle("句末音频复核",()->draft.speechSentenceReview,()->draft.speechSentenceReview=!draft.speechSentenceReview));
            r.add(toggle("离线标点恢复",()->draft.speechPunctuation,()->draft.speechPunctuation=!draft.speechPunctuation));
            r.add(toggle("本轮词表识别提示",()->draft.speechContextHints,()->draft.speechContextHints=!draft.speechContextHints));
        } else if(tab==1) {
            r.add(action("模型下载进度（实时查看）",()->Minecraft.getInstance().setScreen(new ModelDownloadsScreen(this)),true));
            r.add(action("选择麦克风 / 连接状态（F8）",()->Minecraft.getInstance().setScreen(new MicScreen(this)),true));
            r.add(toggle("启动游戏后自动连接麦克风",()->draft.autoStartMicrophone,()->draft.autoStartMicrophone=!draft.autoStartMicrophone));
            r.add(action("识别引擎："+(draft.speechEngine.equals("zipformer")?"Zipformer 中英双语":"Vosk 兼容模式（需模型）"),()->{draft.speechEngine=draft.speechEngine.equals("zipformer")?"vosk":"zipformer";init();},true));
            r.add(number("识别 CPU 线程",1,4,1,()->draft.speechThreads,v->draft.speechThreads=(int)v,""));
            r.add(toggle("缺失模型时自动下载",()->draft.autoModelDownload,()->draft.autoModelDownload=!draft.autoModelDownload));
            r.add(toggle("允许回退 Vosk",()->draft.allowVoskFallback,()->draft.allowVoskFallback=!draft.allowVoskFallback));
            r.add(toggle("语音排查日志",()->draft.logSpeech,()->draft.logSpeech=!draft.logSpeech));
            r.add(path("主模型目录",()->draft.speechModelDir,v->draft.speechModelDir=v));
            r.add(path("句末模型目录",()->draft.speechPostModelDir,v->draft.speechPostModelDir=v));
        } else if(tab==2) {
            r.add(toggle("显示 VoiceKill HUD",()->draft.hudEnabled,()->draft.hudEnabled=!draft.hudEnabled));
            r.add(toggle("左上实时转写（危险词粉红）",()->draft.hudTranscript,()->draft.hudTranscript=!draft.hudTranscript));
            r.add(number("转写保留时间",2,15,1,()->draft.hudTranscriptSeconds,v->draft.hudTranscriptSeconds=(float)v," 秒"));
            r.add(number("转写最多行数",1,6,1,()->draft.hudTranscriptLines,v->draft.hudTranscriptLines=(int)v,""));
            r.add(number("左上危险词最大宽度",120,600,10,()->draft.hudWordWidth,v->draft.hudWordWidth=(int)v," px"));
            r.add(number("左上危险词每页行数",2,12,1,()->draft.hudWordLines,v->draft.hudWordLines=(int)v,""));
            r.add(number("面板背景不透明度",0,.85,.05,()->draft.hudPanelOpacity,v->draft.hudPanelOpacity=(float)v,""));
            r.add(toggle("物品栏上方提示自动换行",()->draft.hudHotbarHint,()->draft.hudHotbarHint=!draft.hudHotbarHint));
            r.add(number("物品栏提示最大宽度",160,800,20,()->draft.hudHotbarWidth,v->draft.hudHotbarWidth=(int)v," px"));
            r.add(number("物品栏提示保留时间",2,15,1,()->draft.hudHotbarSeconds,v->draft.hudHotbarSeconds=(float)v," 秒"));
            r.add(number("物品栏提示距底部",40,180,2,()->draft.hudHotbarBottom,v->draft.hudHotbarBottom=(int)v," px"));
        } else if(tab==3) {
            r.add(toggle("显示言多必失榜",()->draft.hudShowBoard,()->draft.hudShowBoard=!draft.hudShowBoard));
            r.add(action("切换位置预设（右中 / 右上 / 右下 / 左上 / 左下）",()->{int current=positionIndex();int next=(current+1)%5;draft.hudBoardX=next<3?1:0;draft.hudBoardY=switch(next){case 0->.5f;case 1,3->0f;default->1f;};init();},true));
            r.add(number("排行榜横向位置",0,100,1,()->draft.hudBoardX*100,v->draft.hudBoardX=(float)v/100,"%"));
            r.add(number("排行榜纵向位置",0,100,1,()->draft.hudBoardY*100,v->draft.hudBoardY=(float)v/100,"%"));
            r.add(action("布局预览（示例数据）",()->Minecraft.getInstance().setScreen(new HudPreviewScreen(this,draft)),true));
        } else {
            boolean editable=Minecraft.getInstance().getConnection()==null||Minecraft.getInstance().hasSingleplayerServer();
            if(!editable) {
                r.add(action("联机游戏参数、词库和人名由服主管理",()->{},false));
                return r;
            }
            r.add(action("自定义死亡文本（开关 / 编辑 / 预览）",()->Minecraft.getInstance().setScreen(new DeathMessagesScreen(this,draft,()->gameDirty=true)),true));
            r.add(toggle("按读音向危险词模糊匹配",()->draft.phoneticMatching,()->{draft.phoneticMatching=!draft.phoneticMatching;gameDirty=true;}));
            r.add(number("读音模糊强度（越大越宽）",.05,.75,.05,()->draft.phoneticThreshold,v->{draft.phoneticThreshold=(float)v;gameDirty=true;},""));
            r.add(toggle("未识别到文字时持续扣血",()->draft.silencePenaltyEnabled,()->{draft.silencePenaltyEnabled=!draft.silencePenaltyEnabled;gameDirty=true;}));
            r.add(number("无新识别文字后开始扣血",5,600,1,()->draft.silenceTimeoutSeconds,v->{draft.silenceTimeoutSeconds=(float)v;gameDirty=true;}," 秒"));
            r.add(number("每次扣血（2 点 = 1 颗心）",.5,20,.5,()->draft.silenceDamageAmount,v->{draft.silenceDamageAmount=(float)v;gameDirty=true;}," 点"));
            r.add(number("持续扣血间隔",.5,60,.5,()->draft.silenceDamageIntervalSeconds,v->{draft.silenceDamageIntervalSeconds=(float)v;gameDirty=true;}," 秒"));
            r.add(number("每轮危险词",1,20,1,()->draft.wordsPerRound,v->{draft.wordsPerRound=(int)v;gameDirty=true;}," 个"));
            r.add(number("换词间隔",5,3600,5,()->draft.intervalSeconds,v->{draft.intervalSeconds=(int)v;gameDirty=true;}," 秒"));
            r.add(number("其中人名数量",0,20,1,()->draft.namesPerRound,v->{draft.namesPerRound=(int)v;gameDirty=true;},""));
            r.add(number("其中高频词数量",0,20,1,()->draft.highFreqPerRound,v->{draft.highFreqPerRound=(int)v;gameDirty=true;},""));
            r.add(toggle("智能选词",()->draft.smartPick,()->{draft.smartPick=!draft.smartPick;gameDirty=true;}));
            r.add(number("智能选词概率",0,100,5,()->draft.smartPickChance,v->{draft.smartPickChance=(int)v;gameDirty=true;},"%"));
            r.add(toggle("换轮时物品栏提示",()->draft.enableActionbarHint,()->{draft.enableActionbarHint=!draft.enableActionbarHint;gameDirty=true;}));
            r.add(toggle("进服时提示当前词",()->draft.hintOnJoin,()->{draft.hintOnJoin=!draft.hintOnJoin;gameDirty=true;}));
            r.add(action("管理词库 / 人名 / 别名（打开网页）",()->net.minecraft.util.Util.getPlatform().openUri(URI.create("http://127.0.0.1:"+ModConfig.get().webPort+"/")),Minecraft.getInstance().hasSingleplayerServer()&&ModConfig.get().webEnabled));
        }
        return r;
    }
    private int positionIndex(){if(draft.hudBoardX<.1)return draft.hudBoardY<.5?3:4;if(draft.hudBoardY<.1)return 1;if(draft.hudBoardY>.9)return 2;return 0;}
    private Row toggle(String title,BooleanSupplier get,Runnable change) {return action(title+"："+(get.getAsBoolean()?"开":"关"),()->{change.run();init();},true);}
    private Row action(String title,Runnable action,boolean enabled) {
        return (x,y,w)->{var b=Button.builder(Component.literal(title),button->action.run()).bounds(x,y,w,20).build();b.active=enabled;return b;};
    }
    private Row path(String title,Supplier<String> get,Consumer<String> set) {
        return (x,y,w)->{labels.add(new Label(title,x,y+6));var box=new EditBox(font,x+86,y,w-86,20,Component.literal(title));box.setMaxLength(1024);box.setHint(Component.literal("留空使用默认目录"));box.setValue(get.get());box.setResponder(set);return box;};
    }
    private Row number(String title,double min,double max,double step,DoubleSupplier get,DoubleConsumer set,String suffix) {
        return (x,y,w)->new AbstractSliderButton(x,y,w,20,Component.empty(),Math.max(0,Math.min(1,(get.getAsDouble()-min)/(max-min)))) {
            {updateMessage();}
            private double actual(){return Math.max(min,Math.min(max,Math.round((min+value*(max-min))/step)*step));}
            @Override protected void updateMessage(){double n=actual();String shown=step>=1?Long.toString(Math.round(n)):String.format(Locale.ROOT,"%.2f",n).replaceAll("0+$","").replaceAll("\\.$","");setMessage(Component.literal(title+"："+shown+suffix));}
            @Override protected void applyValue(){set.accept(actual());}
        };
    }
    private void save(boolean reconnect) {
        ModConfig live=ModConfig.get();if(live==null)return;
        ClientSettings.apply(draft,live);live.save();
        if(gameDirty&&(Minecraft.getInstance().getConnection()==null||Minecraft.getInstance().hasSingleplayerServer())) {
            var integrated=Minecraft.getInstance().getSingleplayerServer();
            ModConfig saved=ClientSettings.draft(draft);
            Runnable apply=()->{
                live.enableActionbarHint=saved.enableActionbarHint;live.hintOnJoin=saved.hintOnJoin;
                live.customDeathMessages=saved.customDeathMessages;
                live.deathMessageStyle=com.example.voicekill.DeathMessages.Style.from(saved.deathMessageStyle).id;
                live.deathMessages=new ArrayList<>(com.example.voicekill.DeathMessages.sanitize(saved.deathMessages));
                if(integrated!=null)VoiceKillMod.manager().requestUpdateConfig(saved.intervalSeconds,saved.smartPick,saved.smartPickChance,saved.wordsPerRound,saved.highFreqPerRound,saved.namesPerRound,saved.silencePenaltyEnabled,saved.silenceTimeoutSeconds,saved.silenceDamageAmount,saved.silenceDamageIntervalSeconds,saved.phoneticMatching,saved.phoneticThreshold);
                else {live.intervalSeconds=saved.intervalSeconds;live.wordsPerRound=saved.wordsPerRound;live.namesPerRound=Math.min(saved.namesPerRound,saved.wordsPerRound);live.highFreqPerRound=Math.min(saved.highFreqPerRound,saved.wordsPerRound);live.smartPick=saved.smartPick;live.smartPickChance=saved.smartPickChance;live.applySilenceRules(saved.silenceRules());live.phoneticMatching=saved.phoneticMatching;live.phoneticThreshold=saved.phoneticSettings().threshold();}
                live.save();
            };
            if(integrated!=null)integrated.execute(apply);else apply.run();gameDirty=false;
        }
        status=reconnect?"已保存，正在重新连接麦克风…":"已保存；玩法立即生效，语音参数需重连麦克风";
        if(reconnect){var thread=new Thread(()->MicCapture.start(live.audioDevice),"VoiceKill-SettingsReconnect");thread.setDaemon(true);thread.start();}
    }
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float tick) {
        super.render(g,mouseX,mouseY,tick);g.drawCenteredString(font,title,width/2,12,0xFFFFFFFF);
        String note=tab==4?"本地世界可编辑；联机设置由服主管理":"修改后点击保存；未保存返回会放弃更改";
        g.drawCenteredString(font,Component.literal(note),width/2,56,0xFF9FAAB5);
        for(Label label:labels)g.drawString(font,label.text(),label.x(),label.y(),0xFFB5C2CF,false);
        if(!status.isEmpty())g.drawCenteredString(font,Component.literal(status),width/2,height-41,0xFF73DDA7);
    }
}
