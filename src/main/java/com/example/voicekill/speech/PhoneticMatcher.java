package com.example.voicekill.speech;

import com.example.voicekill.util.TextUtil;
import com.example.voicekill.util.WordAliases;
import java.util.*;
import java.util.regex.Pattern;

/** Target-directed pronunciation matching of the selected final sentence. No raw-text veto. */
public final class PhoneticMatcher {
    public record Settings(boolean enabled,float threshold) {
        public Settings {threshold=Float.isFinite(threshold)?Math.max(.05f,Math.min(.75f,threshold)):.30f;}
        public static Settings defaults(){return new Settings(true,.30f);}
    }
    private static final Pattern HAN=Pattern.compile("[\\u3400-\\u9fff]+");
    private PhoneticMatcher() {}
    public static WordMatcher.Hit find(List<WordMatcher.Word> words,String input,Settings settings) {
        return find(words,input,settings,0);
    }
    public static WordMatcher.Hit find(List<WordMatcher.Word> words,String input,Settings settings,int previousLength) {
        String text=TextUtil.normalize(input);var ordered=WordMatcher.ordered(words);
        for(var word:ordered){String target=TextUtil.normalize(word.text());int at=target.isEmpty()?-1:text.indexOf(target,Math.max(0,previousLength-target.length()+1));if(at>=0)return new WordMatcher.Hit(word.text(),"exact",text.substring(at,at+target.length()),at,at+target.length());}
        for(var word:ordered)if(word.aliases()!=null)for(String alias:word.aliases()){
            String target=TextUtil.normalize(alias);int at=target.isEmpty()?-1:text.indexOf(target,Math.max(0,previousLength-target.length()+1));if(at>=0)return new WordMatcher.Hit(word.text(),"alias",text.substring(at,at+target.length()),at,at+target.length());
        }
        for(var word:ordered)if(word.text().matches(".*[a-zA-Z].*")){
            var pattern=WordAliases.pronunciationPattern(TextUtil.normalize(word.text()),false);
            if(pattern!=null){var match=pattern.matcher(text);while(match.find())if(match.end()>previousLength)return new WordMatcher.Hit(word.text(),"pronunciation",match.group(),match.start(),match.end());}
        }
        if(!settings.enabled())return null;
        WordMatcher.Hit best=null;double bestScore=Double.POSITIVE_INFINITY;
        for(var word:ordered) {
            String target=TextUtil.normalize(word.text());if(target.isEmpty()||target.length()>64||!HAN.matcher(target).matches())continue;
            var expected=GameTerms.syllables(target);if(expected.isEmpty())continue;
            var runs=HAN.matcher(text);
            while(runs.find()) {
                var actual=GameTerms.syllables(runs.group());if(actual.isEmpty())continue;
                int n=expected.size(),m=actual.size();double[][] costs=new double[n+1][m+1];
                for(int i=1;i<=n;i++)costs[i][0]=i;
                for(int i=1;i<=n;i++)for(int j=1;j<=m;j++)costs[i][j]=Math.min(costs[i-1][j-1]+soundCost(expected.get(i-1),actual.get(j-1)),Math.min(costs[i-1][j]+1,costs[i][j-1]+1));
                for(int end=1;end<=m;end++) {
                    if(runs.start()+end<=previousLength)continue;
                    double score=costs[n][end]/n;if(score>settings.threshold()+1e-6||score>bestScore+1e-6)continue;
                    int i=n,j=end;
                    while(i>0){if(j>0&&Math.abs(costs[i][j]-costs[i-1][j-1]-soundCost(expected.get(i-1),actual.get(j-1)))<1e-6){i--;j--;}else if(Math.abs(costs[i][j]-costs[i-1][j]-1)<1e-6)i--;else j--;}
                    if(end==j||Math.abs(end-j-n)>Math.max(1,(int)Math.ceil(n*settings.threshold())))continue;
                    int start=runs.start()+j,stop=runs.start()+end;
                    var hit=new WordMatcher.Hit(word.text(),score<1e-6?"phonetic-exact":"phonetic-fuzzy",text.substring(start,stop),start,stop);
                    if(score<bestScore-1e-6||WordMatcher.prefer(hit,best,words)){best=hit;bestScore=score;}
                }
            }
        }
        return best;
    }
    private static String initial(String sound){if(sound.startsWith("zh")||sound.startsWith("ch")||sound.startsWith("sh"))return sound.substring(0,2);return "aeov".indexOf(sound.charAt(0))>=0?"":sound.substring(0,1);}
    private static double soundCost(String a,String b) {
        if(a.equals(b))return 0;
        String x=initial(a),y=initial(b),af=a.substring(x.length()),bf=b.substring(y.length());
        String pair=x.compareTo(y)<0?x+"/"+y:y+"/"+x;
        double onset=x.equals(y)?0:Set.of("l/n","f/h").contains(pair)?.08:Set.of("z/zh","c/ch","s/sh").contains(pair)?.1:(x.isEmpty()||y.isEmpty())?.18:.55;
        if(af.equals(bf))return onset;
        if(pair.equals("f/h")&&(af.startsWith("u")?af.substring(1):af).equals(bf.startsWith("u")?bf.substring(1):bf))return onset+.1;
        if((af.endsWith("g")?af.substring(0,af.length()-1):af).equals(bf.endsWith("g")?bf.substring(0,bf.length()-1):bf))return onset+.12;
        int[] previous=new int[bf.length()+1];for(int j=0;j<=bf.length();j++)previous[j]=j;
        for(int i=1;i<=af.length();i++){int[] row=new int[bf.length()+1];row[0]=i;for(int j=1;j<=bf.length();j++)row[j]=Math.min(previous[j-1]+(af.charAt(i-1)==bf.charAt(j-1)?0:1),Math.min(previous[j]+1,row[j-1]+1));previous=row;}
        return Math.min(1,onset+.7*previous[bf.length()]/Math.max(af.length(),bf.length()));
    }
}
