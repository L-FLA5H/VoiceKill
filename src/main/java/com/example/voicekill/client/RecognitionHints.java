package com.example.voicekill.client;

import com.example.voicekill.util.TextUtil;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ASR 词表提示，不修改服务端命中规则；中文单字和普通中文词不参与偏置。 */
public final class RecognitionHints {
    private RecognitionHints() {}

    public static List<String> names(List<String> words) {
        Set<String> names = new LinkedHashSet<>();
        for (String value : words) {
            String word = TextUtil.normalize(value);
            if (word.length() < 2 || word.length() > 32 || !word.matches(".*[a-z].*")) continue;
            if (word.codePoints().anyMatch(c -> !Character.isLetterOrDigit(c))) continue;
            names.add(word);
            if (names.size() == 20) break;
        }
        return names.stream().sorted().toList();
    }

    public static String encode(List<String> words) {
        List<String> names = names(words);
        Set<String> terms = new LinkedHashSet<>();
        for (String name : names) {
            terms.add(name.toUpperCase(Locale.ROOT));
            StringBuilder spelled = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                // 字母逐个拼读；汉字连续保留，字母/数字与汉字的边界分开。
                if (i > 0 && (c < 128 || name.charAt(i - 1) < 128)) spelled.append(' ');
                spelled.append(Character.toUpperCase(c));
            }
            terms.add(spelled.toString());
        }
        return String.join("/", terms);
    }

    /** 优先词保持既有最高强度，其余词减弱；长中文优先词仅使用较低提示强度。 */
    public static String encodePriority(List<com.example.voicekill.speech.WordMatcher.Word> words) {
        Set<String> terms=new LinkedHashSet<>();int count=0;
        for(var item:com.example.voicekill.speech.WordMatcher.ordered(words)) {
            String name=TextUtil.normalize(item.text());
            if(name.length()<2||name.length()>32||name.codePoints().anyMatch(c->!(c<128&&Character.isLetterOrDigit(c))&&Character.UnicodeScript.of(c)!=Character.UnicodeScript.HAN))continue;
            boolean latin=name.matches(".*[a-z].*");
            if(!latin&&(!item.preferred()||name.length()<4))continue;
            String encoded=latin?encode(List.of(name)):name;
            String score=!latin?"0.5":item.preferred()?"1.5":"0.75";
            for(String term:encoded.split("/"))if(!term.isEmpty())terms.add(term+" :"+score);
            if(++count>=20)break;
        }
        return String.join("/",terms);
    }
}
