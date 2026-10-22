import com.ibm.icu.text.Transliterator;
import java.nio.file.*;
import java.nio.charset.*;
import java.text.Normalizer;
var t=Transliterator.getInstance("Han-Latin");
var result=new StringBuilder("{");
int total=0;
for(int c=0x3400;c<=0x9fff;c++) {
    String raw=Normalizer.normalize(t.transliterate(String.valueOf((char)c)),Normalizer.Form.NFD).toLowerCase(java.util.Locale.ROOT);
    var value=new StringBuilder();boolean valid=true;
    for(char x:raw.toCharArray()) {
        if(x>='a'&&x<='z')value.append(x);
        else if(x=='\u0308'&&!value.isEmpty()&&value.charAt(value.length()-1)=='u')value.setCharAt(value.length()-1,'v');
        else if(Character.getType(x)!=Character.NON_SPACING_MARK){valid=false;break;}
    }
    if(valid&&!value.isEmpty()) {if(total++>0)result.append(',');result.append('"').append((char)c).append("\":\"").append(value).append('"');}
}
Files.writeString(Path.of("src/main/resources/voicekill/pinyin-icu-77.1.json"),result.append('}'),StandardCharsets.UTF_8);
System.out.println("PINYIN_ENTRIES="+total);
/exit
