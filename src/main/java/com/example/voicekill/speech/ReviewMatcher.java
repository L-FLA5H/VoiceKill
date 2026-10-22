package com.example.voicekill.speech;

import com.example.voicekill.util.TextUtil;
import java.util.List;
import java.util.regex.Pattern;

/** 已接受的复核决定本段判定；新增字母命中仍限精确，中文必须同时获原始首候选支持。 */
public final class ReviewMatcher {
    private static final Pattern HAN=Pattern.compile("\\p{IsHan}+");
    private ReviewMatcher() {}
    public static WordMatcher.Hit find(List<WordMatcher.Word> words,String reviewed,String primary) {
        String text=TextUtil.normalize(reviewed),raw=TextUtil.normalize(primary);
        WordMatcher.Hit found=null;
        for(var word:WordMatcher.ordered(words)) {
            String target=TextUtil.normalize(word.text());
            if(target.isEmpty())continue;
            if(!target.matches(".*[a-z].*")) {
                // Review may veto raw Chinese hits, but cannot create a new Chinese punishment.
                var singleton=List.of(word);
                if(WordMatcher.find(singleton,primary,false,0)!=null) {
                    var corroborated=WordMatcher.find(singleton,reviewed,false,0);
                    if(WordMatcher.prefer(corroborated,found,words))found=corroborated;
                }
                continue;
            }
            if(target.length()<2)continue;
            var han=HAN.matcher(target);boolean supported=true;
            while(han.find())if(!raw.contains(han.group())){supported=false;break;}
            if(!supported)continue;
            int start=text.indexOf(target);
            if(start>=0) {
                var hit=new WordMatcher.Hit(word.text(),"review-exact",text.substring(start,start+target.length()),start,start+target.length());
                if(WordMatcher.prefer(hit,found,words))found=hit;
            }
        }
        var domain=GameTerms.find(words,primary,reviewed,true);
        if(WordMatcher.prefer(domain,found,words))found=domain;
        return found;
    }
}
