package com.example.voicekill.client;

import com.example.voicekill.util.TextUtil;
import java.util.*;

/** 显示用精确高亮，保留原始空格/标点与 Unicode；不调用处罚或模糊匹配。 */
public final class SpeechHighlight {
    public record Run(String text,boolean danger) {}
    private SpeechHighlight() {}
    public static List<Run> runs(String text,List<String> words) {
        if(text.isEmpty())return List.of();
        StringBuilder normalized=new StringBuilder();
        List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i),end=i+Character.charCount(cp);
            if(!Character.isWhitespace(cp)) {
                String lower=text.substring(i,end).toLowerCase(Locale.ROOT);
                normalized.append(lower);
                for(int j=0;j<lower.length();j++){starts.add(i);ends.add(end);}
            }
            i=end;
        }
        boolean[] marked=new boolean[text.length()];
        String value=normalized.toString();
        for(String word:words) {
            String target=TextUtil.normalize(word);
            if(target.isEmpty())continue;
            for(int at=value.indexOf(target);at>=0;at=value.indexOf(target,at+1))
                Arrays.fill(marked,starts.get(at),ends.get(at+target.length()-1),true);
        }
        List<Run> result=new ArrayList<>();int first=0;
        for(int i=1;i<=text.length();i++) {
            if(i==text.length()||marked[i]!=marked[first]){result.add(new Run(text.substring(first,i),marked[first]));first=i;}
        }
        return List.copyOf(result);
    }
}
