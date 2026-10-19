package com.example.voicekill.util;

import com.example.voicekill.config.ModConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 谐音别名。
 *
 * 问题：玩家叫 "ad"，Vosk 中文模型永远不可能输出 "ad"，它会输出「哎地」「哎弟」「阿德」。
 * 解法：把英文缩写按字母拆开，每个字母映射到中文模型常见的几种识别结果，做笛卡尔积。
 *
 * 于是 "ad" 会得到：诶地 / 诶弟 / 诶的 / 哎地 / 哎弟 / 阿地 / 阿弟 …（最多 24 个）
 * 说到其中任何一个，都算踩中了 "ad"。
 *
 * 也可以在配置的 aliases 里手动补充（手动优先，且不受数量限制）。
 */
public final class WordAliases {

    /** 单个词最多生成多少个自动变体（避免 6 个字母爆成几千个） */
    private static final int MAX_VARIANTS = 48;

    /** 英文夹中文（p老板）最多生成多少个变体 */
    private static final int MAX_MIXED_VARIANTS = 48;

    /** 超过这个长度就不自动生成了 —— 组合爆炸，而且长词本来就能被正常识别 */
    private static final int MAX_AUTO_LENGTH = 5;

    /**
     * 字母 → 中文语音识别常见输出。
     * 这些不是「标准音译」，是**Vosk 中文小模型实际会吐出来的字**，所以故意选了口语化的写法。
     */
    private static final String[][] LETTER_CANDIDATES = {
            /* a */ {"诶", "哎", "阿", "啊", "欸", "爱", "艾"},
            /* b */ {"比", "逼", "毕", "币"},
            /* c */ {"西", "希", "溪", "洗"},
            /* d */ {"地", "弟", "的", "底", "迪", "滴"},
            /* e */ {"一", "依", "衣", "伊"},
            /* f */ {"艾弗", "夫", "福"},
            /* g */ {"记", "吉", "机", "基"},
            /* h */ {"艾尺", "吃", "尺"},
            /* i */ {"爱", "艾", "哎"},
            /* j */ {"杰", "街", "结", "接"},
            /* k */ {"开", "凯", "克"},
            /* l */ {"艾勒", "勒", "了"},
            /* m */ {"艾姆", "姆", "木", "母"},
            /* n */ {"恩", "嗯"},
            /* o */ {"欧", "哦", "噢"},
            /* p */ {"屁", "批", "皮", "劈", "匹"},
            /* q */ {"扣", "丘", "秋", "求", "球"},
            /* r */ {"阿尔", "儿", "啊"},
            /* s */ {"艾斯", "斯", "思", "丝"},
            /* t */ {"提", "梯", "踢", "替"},
            /* u */ {"优", "由", "有"},
            /* v */ {"维", "威", "微"},
            /* w */ {"达不溜", "打不溜", "威"},
            /* x */ {"艾克斯", "克斯", "克思"},
            /* y */ {"歪", "外", "为"},
            /* z */ {"贼", "泽", "兹", "滋"}
    };

    /**
     * 中文同音/近音字表。
     *
     * 为什么需要：Vosk 中文模型不会输出 "ta" 这种拼音，它输出的是**另一个同音汉字**。
     * 所以「她」被识别成「他」「它」时，按字面匹配永远不中。
     *
     * 覆盖两类：
     * - 高频虚词（她他它 / 的得地 / 在再 / 那哪 …）
     * - 人名常用字（晴青清 / 明名鸣 / 装庄中 / 小晓 …）
     */
    private static final java.util.Map<Character, String> HOMOPHONES = new java.util.HashMap<>();

    static {
        HOMOPHONES.put('汤', "唐糖趟");
        HOMOPHONES.put('唐', "汤糖");
        HOMOPHONES.put('宝', "包保堡");
        HOMOPHONES.put('包', "宝保");
        // ---- 高频虚词 ----
        HOMOPHONES.put('她', "他它塔");
        HOMOPHONES.put('他', "她它塔");
        HOMOPHONES.put('它', "他她");
        HOMOPHONES.put('的', "得地底");
        HOMOPHONES.put('得', "的地德");
        HOMOPHONES.put('地', "的得第");
        HOMOPHONES.put('是', "事市式试");
        HOMOPHONES.put('事', "是市");
        HOMOPHONES.put('不', "部布步");
        HOMOPHONES.put('部', "不布");
        HOMOPHONES.put('在', "再载");
        HOMOPHONES.put('再', "在");
        HOMOPHONES.put('就', "久九旧");
        HOMOPHONES.put('这', "着者");
        HOMOPHONES.put('那', "哪纳拿");
        HOMOPHONES.put('哪', "那");
        HOMOPHONES.put('你', "尼泥拟");
        HOMOPHONES.put('我', "窝握");
        HOMOPHONES.put('吗', "嘛麻");
        HOMOPHONES.put('嘛', "吗");
        HOMOPHONES.put('吧', "巴把");
        HOMOPHONES.put('啊', "呀哇阿");
        HOMOPHONES.put('呀', "啊亚");
        HOMOPHONES.put('么', "吗嘛");
        HOMOPHONES.put('什', "甚");
        HOMOPHONES.put('没', "每美");
        HOMOPHONES.put('有', "又右");
        HOMOPHONES.put('会', "回汇");
        HOMOPHONES.put('说', "数");
        HOMOPHONES.put('来', "莱");
        HOMOPHONES.put('去', "区");
        HOMOPHONES.put('好', "号毫");
        HOMOPHONES.put('行', "形型");
        HOMOPHONES.put('对', "队");
        HOMOPHONES.put('可', "克刻");
        HOMOPHONES.put('以', "已己");
        HOMOPHONES.put('个', "各");
        HOMOPHONES.put('一', "衣依医");
        HOMOPHONES.put('完', "玩");
        HOMOPHONES.put('了', "乐勒");
        HOMOPHONES.put('快', "块");
        HOMOPHONES.put('走', "邹");
        HOMOPHONES.put('等', "邓灯");
        HOMOPHONES.put('下', "夏");
        HOMOPHONES.put('天', "添田");
        HOMOPHONES.put('时', "十实识");
        HOMOPHONES.put('候', "后厚");
        HOMOPHONES.put('点', "店电");
        HOMOPHONES.put('怎', "增");
        HOMOPHONES.put('样', "阳羊");
        HOMOPHONES.put('为', "位未");
        HOMOPHONES.put('因', "音阴");
        HOMOPHONES.put('然', "燃");
        HOMOPHONES.put('后', "候后");
        HOMOPHONES.put('还', "孩海");
        HOMOPHONES.put('或', "货");
        HOMOPHONES.put('者', "这");
        HOMOPHONES.put('能', "能");
        HOMOPHONES.put('要', "药摇");
        HOMOPHONES.put('知', "之只");
        HOMOPHONES.put('道', "到倒");
        HOMOPHONES.put('觉', "决绝");
        HOMOPHONES.put('得', "的地");
        HOMOPHONES.put('感', "敢赶");
        HOMOPHONES.put('真', "针珍");
        HOMOPHONES.put('确', "却");
        HOMOPHONES.put('实', "十时");
        HOMOPHONES.put('应', "英鹰");
        HOMOPHONES.put('该', "改盖");
        HOMOPHONES.put('大', "达");
        HOMOPHONES.put('小', "晓");
        HOMOPHONES.put('多', "朵");
        HOMOPHONES.put('少', "绍");
        HOMOPHONES.put('怎', "增");
        HOMOPHONES.put('别', "烈列");
        HOMOPHONES.put('急', "及即");
        HOMOPHONES.put('忙', "盲芒");
        HOMOPHONES.put('帮', "邦");
        HOMOPHONES.put('谢', "写些");
        HOMOPHONES.put('西', "希溪");
        HOMOPHONES.put('东', "冬");
        HOMOPHONES.put('南', "楠男");
        HOMOPHONES.put('北', "被贝");
        HOMOPHONES.put('上', "尚");
        HOMOPHONES.put('面', "棉");
        HOMOPHONES.put('里', "理李");
        HOMOPHONES.put('外', "歪");
        HOMOPHONES.put('前', "钱");
        HOMOPHONES.put('现', "线县");
        HOMOPHONES.put('已', "以己");
        HOMOPHONES.put('经', "京精");
        HOMOPHONES.put('刚', "钢刚");
        HOMOPHONES.put('才', "材财");
        HOMOPHONES.put('马', "妈码");
        HOMOPHONES.put('上', "尚");

        // ---- 人名常用字 ----
        HOMOPHONES.put('装', "庄中桩");
        HOMOPHONES.put('庄', "装中");
        HOMOPHONES.put('中', "装钟钟");
        HOMOPHONES.put('晴', "青清情");
        HOMOPHONES.put('青', "清轻晴");
        HOMOPHONES.put('清', "青轻晴");
        HOMOPHONES.put('情', "晴清");
        HOMOPHONES.put('明', "名鸣铭");
        HOMOPHONES.put('名', "明鸣");
        HOMOPHONES.put('鸣', "明名");
        HOMOPHONES.put('白', "百拜");
        HOMOPHONES.put('亮', "量凉");
        HOMOPHONES.put('强', "墙抢");
        HOMOPHONES.put('飞', "非菲");
        HOMOPHONES.put('菲', "飞非");
        HOMOPHONES.put('峰', "锋风枫");
        HOMOPHONES.put('锋', "峰风");
        HOMOPHONES.put('风', "峰锋枫");
        HOMOPHONES.put('伟', "维伟卫");
        HOMOPHONES.put('维', "伟为");
        HOMOPHONES.put('磊', "雷蕾");
        HOMOPHONES.put('雷', "磊蕾");
        HOMOPHONES.put('静', "净敬");
        HOMOPHONES.put('宇', "雨语羽");
        HOMOPHONES.put('雨', "宇语");
        HOMOPHONES.put('语', "宇雨");
        HOMOPHONES.put('航', "杭");
        HOMOPHONES.put('杭', "航");
        HOMOPHONES.put('浩', "皓豪");
        HOMOPHONES.put('皓', "浩");
        HOMOPHONES.put('轩', "宣喧");
        HOMOPHONES.put('宣', "轩");
        HOMOPHONES.put('泽', "择则");
        HOMOPHONES.put('悦', "月越");
        HOMOPHONES.put('月', "悦越");
        HOMOPHONES.put('欣', "心新");
        HOMOPHONES.put('心', "欣新");
        HOMOPHONES.put('辰', "晨沉");
        HOMOPHONES.put('晨', "辰");
        HOMOPHONES.put('阳', "杨洋扬");
        HOMOPHONES.put('杨', "阳洋");
        HOMOPHONES.put('洋', "阳杨");
        HOMOPHONES.put('琳', "林霖");
        HOMOPHONES.put('林', "琳霖");
        HOMOPHONES.put('鑫', "心新");
        HOMOPHONES.put('佳', "嘉家");
        HOMOPHONES.put('嘉', "佳家");
        HOMOPHONES.put('怡', "宜仪");
        HOMOPHONES.put('睿', "瑞");
        HOMOPHONES.put('瑞', "睿");
        HOMOPHONES.put('博', "柏");
        HOMOPHONES.put('涛', "陶淘");
        HOMOPHONES.put('鹏', "朋");
        HOMOPHONES.put('龙', "隆");
        HOMOPHONES.put('丽', "利莉");
        HOMOPHONES.put('莉', "丽利");
        HOMOPHONES.put('华', "花");
        HOMOPHONES.put('建', "健剑");
        HOMOPHONES.put('健', "建");
        HOMOPHONES.put('军', "君");
        HOMOPHONES.put('君', "军");
        HOMOPHONES.put('平', "萍苹");
        HOMOPHONES.put('萍', "平");
        HOMOPHONES.put('红', "洪宏");
        HOMOPHONES.put('洪', "红宏");
        HOMOPHONES.put('燕', "艳");
        HOMOPHONES.put('艳', "燕");
        HOMOPHONES.put('玲', "灵铃");
        HOMOPHONES.put('灵', "玲铃");
        HOMOPHONES.put('芳', "方");
        HOMOPHONES.put('方', "芳");
        HOMOPHONES.put('梅', "眉");
        HOMOPHONES.put('兰', "蓝");
        HOMOPHONES.put('蓝', "兰");
        HOMOPHONES.put('云', "运");
        HOMOPHONES.put('英', "鹰");
        HOMOPHONES.put('玉', "育");
        HOMOPHONES.put('珍', "真正");
        HOMOPHONES.put('珠', "朱");
        HOMOPHONES.put('娜', "那");
        HOMOPHONES.put('倩', "千");
        HOMOPHONES.put('露', "路");
        HOMOPHONES.put('薇', "微威");
        HOMOPHONES.put('瑶', "摇");
        HOMOPHONES.put('珊', "山");
        HOMOPHONES.put('琦', "奇其");
        HOMOPHONES.put('琪', "奇其");
        HOMOPHONES.put('昊', "浩");
        HOMOPHONES.put('晗', "含寒");
        HOMOPHONES.put('曦', "西希");
    }

    private WordAliases() {
    }

    /** 取得某个词的全部别名（手动配置的 + 自动生成的），已去掉和原词相同的项 */
    public static List<String> variants(String word, ModConfig config) {
        Set<String> out = new LinkedHashSet<>();
        if (word == null || word.isBlank()) {
            return List.of();
        }
        String trimmed = word.trim();

        // 1) 手动配置优先
        if (config != null && config.aliases != null) {
            List<String> manual = config.aliases.get(trimmed);
            if (manual != null) {
                for (String alias : manual) {
                    if (alias != null && !alias.isBlank()) {
                        out.add(alias.trim());
                    }
                }
            }
        }

        // 2) 纯英文短缩写 → 自动生成谐音
        if (isShortAscii(trimmed)) {
            out.addAll(autoGenerate(trimmed));
        } else if (hasChinese(trimmed) && hasAsciiLetter(trimmed)) {
            // 2.1) 英文夹中文（p老板 / ad哥 / 打q）→ 把字母按读音换成汉字
            out.addAll(mixedAsciiVariants(trimmed));
        }

        // 3) 中文 → 同音字变体（「她」被识别成「他/它」也算中）
        if (hasChinese(trimmed) && TextUtil.normalize(trimmed).length() > 1) {
            out.addAll(homophoneVariants(trimmed));
        }

        // 去掉和原词本身重复的（原词由匹配逻辑单独处理）
        String normalizedSelf = TextUtil.normalize(trimmed);
        out.removeIf(a -> TextUtil.normalize(a).equals(normalizedSelf));
        return new ArrayList<>(out);
    }

    /** 按位置匹配读音，避免笛卡尔积被展示用的 48 个别名上限截断。 */
    public static java.util.regex.Pattern pronunciationPattern(String word, boolean chineseHomophones) {
        String normalized = TextUtil.normalize(word);
        if (!hasAsciiLetter(normalized) && !chineseHomophones) return null;
        if (normalized.isEmpty() || normalized.length() > 64) return null;
        StringBuilder pattern = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            Set<String> choices = new LinkedHashSet<>();
            choices.add(String.valueOf(c));
            if (c < 128 && Character.isLetter(c)) {
                choices.addAll(java.util.Arrays.asList(candidatesFor(c)));
            } else if (chineseHomophones) {
                String alternatives = HOMOPHONES.get(c);
                if (alternatives != null) {
                    for (char alternative : alternatives.toCharArray()) choices.add(String.valueOf(alternative));
                }
            }
            pattern.append("(?:");
            pattern.append(choices.stream().map(java.util.regex.Pattern::quote)
                    .collect(java.util.stream.Collectors.joining("|")));
            pattern.append(')');
        }
        return java.util.regex.Pattern.compile(pattern.toString());
    }

    private static boolean hasChinese(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.UnicodeScript.of(word.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    /**
     * 逐字替换成同音字，生成变体。
     * 一次只替换**一个位置**（不跑全组合）：「你好呀装装」只会生成「你好呀庄装」这种，
     * 两个位置同时错的情况交给 {@link com.example.voicekill.util.FuzzyMatch} 处理。
     */
    private static List<String> homophoneVariants(String word) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < word.length() && result.size() < MAX_VARIANTS; i++) {
            String candidates = HOMOPHONES.get(word.charAt(i));
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            for (int k = 0; k < candidates.length() && result.size() < MAX_VARIANTS; k++) {
                char replacement = candidates.charAt(k);
                if (replacement == word.charAt(i)) {
                    continue;
                }
                result.add(word.substring(0, i) + replacement + word.substring(i + 1));
            }
        }
        return result;
    }

    private static boolean isShortAscii(String word) {
        if (word.isEmpty() || word.length() > MAX_AUTO_LENGTH) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c > 127 || !Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAsciiLetter(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 128 && Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 英文夹中文的词（p老板 / ad哥）：只把**字母**换成中文读音，汉字原样保留。
     * 「p老板」→ 屁老板 / 批老板 / 皮老板 …
     */
    private static List<String> mixedAsciiVariants(String word) {
        List<String> result = new ArrayList<>();
        buildMixed(word.toLowerCase(), 0, new StringBuilder(), result, MAX_MIXED_VARIANTS);
        return result;
    }

    private static void buildMixed(String word, int index, StringBuilder current,
                                   List<String> result, int limit) {
        if (result.size() >= limit) {
            return;
        }
        if (index >= word.length()) {
            result.add(current.toString());
            return;
        }

        char c = word.charAt(index);
        int length = current.length();
        if (c < 128 && Character.isLetterOrDigit(c)) {
            for (String candidate : candidatesFor(c)) {
                current.append(candidate);
                buildMixed(word, index + 1, current, result, limit);
                current.setLength(length);
                if (result.size() >= limit) {
                    return;
                }
            }
        } else {
            current.append(c);
            buildMixed(word, index + 1, current, result, limit);
            current.setLength(length);
        }
    }

    private static List<String> autoGenerate(String word) {
        List<String> result = new ArrayList<>();
        build(word.toLowerCase(), 0, new StringBuilder(), result, MAX_VARIANTS);
        return result;
    }

    private static void build(String word, int index, StringBuilder current,
                              List<String> result, int limit) {
        if (result.size() >= limit) {
            return;
        }
        if (index >= word.length()) {
            result.add(current.toString());
            return;
        }

        char c = word.charAt(index);
        String[] candidates = candidatesFor(c);
        int length = current.length();
        for (String candidate : candidates) {
            current.append(candidate);
            build(word, index + 1, current, result, limit);
            current.setLength(length);
            if (result.size() >= limit) {
                return;
            }
        }
    }

    private static String[] candidatesFor(char c) {
        if (c >= 'a' && c <= 'z') {
            return LETTER_CANDIDATES[c - 'a'];
        }
        if (c >= '0' && c <= '9') {
            // 数字：Vosk 一般直接给阿拉伯数字，个别给中文数字
            return new String[]{String.valueOf(c)};
        }
        return new String[]{String.valueOf(c)};
    }
}
