package com.example.voicekill;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** Server-owned templates; placeholders are literal Components, never commands or JSON. */
public final class DeathMessages {
    public static final int MAX_TEMPLATES=32,MAX_LENGTH=200;
    private static final Pattern TOKEN=Pattern.compile("\\{(player|word)\\}");
    private static final List<String> CLASSIC=List.of(
            "{player} 把「{word}」说出了口，也把自己送走了。",
            "{player} 因为「{word}」荣登言多必失榜。",
            "{player} 用一句「{word}」换来了一次重生。",
            "{player} 刚说完「{word}」，生命值就归零了。",
            "{player} 对着「{word}」交出了复活申请。",
            "{player} 触发了隐藏成就：把「{word}」说出来。",
            "{player} 的嘴比脑子快了一步：「{word}」。",
            "{player} 的遗言提醒大家：别说「{word}」。");
    private static final List<String> DEFAULTS=List.of(
            "{player} 留下遗言「{word}」，然后安详地爆了一地装备。",
            "{player} 哪怕缝上嘴，也能用脚趾抠出一句「{word}」来送死。",
            "{player} 觉得身上的经验值太烫手，于是喊了一声「{word}」。",
            "医学奇迹！{player} 靠一句「{word}」当场治好了自己的低血压。",
            "{player} 嫌跑图太累，决定用一句「{word}」搭顺风车回出生点。",
            "{player} 舍身试毒，帮大家确认了「{word}」确实能把人送走。",
            "{player} 的大脑试图拦截「{word}」，但他的嘴选择了自由飞翔。",
            "听君一席话，白捡一背包。感谢 {player} 慷慨赠言：「{word}」。",
            "{player} 用一句「{word}」，将毕生积蓄无偿捐赠给了大地。",
            "{player} 喊出「{word}」的那一刻，附近的漏斗矿车兴奋地启动了。",
            "{player} 刚刚用一句「{word}」表演了什么叫“散财童子”。",
            "为了省下吃面包的饱食度，{player} 选择直接说「{word}」重开。",
            "{player} 的声带觉得他活得太久了，于是擅自播报了「{word}」。",
            "阎王爷本来没空理 {player}，奈何他非要大声朗诵「{word}」。",
            "{player} 哪怕变成哑巴，也得打手语把「{word}」比划出来再死。",
            "事实证明，{player} 的麦克风比苦力怕的引线更好用，只需一句「{word}」。",
            "古人云：一言既出，{player} 暴毙。史称「{word}」之变。",
            "根据牛顿第三定律，当 {player} 说出「{word}」，世界就会给他一记大逼斗。",
            "{player} 试图用「{word}」吟唱禁咒，结果发现施法目标是自己。",
            "鲁迅先生曾说过，如果 {player} 非要念出「{word}」，那神仙也救不了他。");
    public enum Style {
        FRIENDS("friends","损友整活"),CLASSIC("classic","经典"),LOOT("loot","装备与经验"),
        VOICE("voice","声带背锅"),DEADPAN("deadpan","正经胡说"),CUSTOM("custom","自定义混搭");
        public final String id,label;
        Style(String id,String label){this.id=id;this.label=label;}
        public static Style from(String id){for(var style:values())if(style.id.equals(id))return style;return FRIENDS;}
    }
    public static List<String> pool(String style,List<String> custom) {
        return switch(Style.from(style)) {
            case FRIENDS->DEFAULTS;case CLASSIC->CLASSIC;case LOOT->DEFAULTS.subList(8,12);
            case VOICE->DEFAULTS.subList(12,16);case DEADPAN->DEFAULTS.subList(16,20);case CUSTOM->sanitize(custom);
        };
    }
    private DeathMessages() {}
    public static List<String> defaults(){return DEFAULTS;}
    public static List<String> sanitize(List<String> templates) {
        var result=new LinkedHashSet<String>();
        if(templates!=null)for(String text:templates) {
            if(text==null)continue;
            StringBuilder cleaned=new StringBuilder();
            text.replace('\n',' ').replace('\r',' ').replace('\t',' ').codePoints()
                    .filter(c->!Character.isISOControl(c)).limit(MAX_LENGTH).forEach(cleaned::appendCodePoint);
            String value=cleaned.toString().trim();
            if(!value.isBlank())result.add(value);
            if(result.size()==MAX_TEMPLATES)break;
        }
        return result.isEmpty()?DEFAULTS:List.copyOf(result);
    }
    public static String choose(List<String> templates,RandomGenerator random) {
        List<String> valid=sanitize(templates);return valid.get(random.nextInt(valid.size()));
    }
    public static MutableComponent render(String template,Component player,String word) {
        MutableComponent message=Component.empty();var matcher=TOKEN.matcher(template);int end=0;
        while(matcher.find()) {
            message.append(Component.literal(template.substring(end,matcher.start())));
            message.append(matcher.group(1).equals("player")?player.copy():Component.literal(word).withStyle(style->style.withColor(0xFF77C8)));
            end=matcher.end();
        }
        return message.append(Component.literal(template.substring(end)));
    }
    /** Preserve the registry-backed generic-kill type/tags; only its death text changes. */
    public static DamageSource source(DamageSource genericKill,Component message) {
        Component selected=message.copy();
        return new DamageSource(genericKill.typeHolder()) {
            @Override public Component getLocalizedDeathMessage(LivingEntity victim){return selected.copy();}
        };
    }
}
