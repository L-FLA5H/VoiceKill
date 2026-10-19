package com.example.voicekill.speech;

import com.example.voicekill.util.TextUtil;
import java.util.List;
import java.util.regex.Pattern;

/** 音频复核只补充当前段的字母精确包含，不扩大谐音、模糊或跨段匹配。 */
public final class ReviewMatcher {
    private static final Pattern HAN=Pattern.compile("\\p{IsHan}+");
    private ReviewMatcher() {}
    public static WordMatcher.Hit find(List<WordMatcher.Word> words,String reviewed,String primary) {
        String text=TextUtil.normalize(reviewed),raw=TextUtil.normalize(primary);
        for(var word:words) {
            String target=TextUtil.normalize(word.text());
            if(target.length()<2||!target.matches(".*[a-z].*"))continue;
            var han=HAN.matcher(target);boolean supported=true;
            while(han.find())if(!raw.contains(han.group())){supported=false;break;}
            if(!supported)continue;
            int start=text.indexOf(target);
            if(start>=0)return new WordMatcher.Hit(word.text(),"review-exact",text.substring(start,start+target.length()),start,start+target.length());
        }
        return null;
    }
}
