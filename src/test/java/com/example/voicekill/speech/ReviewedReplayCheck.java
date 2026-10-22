package com.example.voicekill.speech;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Re-evaluate unchanged saved ASR events; no model inference or latency claim. */
public final class ReviewedReplayCheck {
    public static void main(String[] args) throws Exception {
        GameTerms.awaitReady();
        var gson=new GsonBuilder().setPrettyPrinting().create();var summaries=new ArrayList<Map<String,Object>>();
        String input=args.length>0?args[0]:".local/diagnostics/dev7";String prefix=args.length>0?"":"final-";
        PhoneticMatcher.Settings phonetic=args.length>2?new PhoneticMatcher.Settings(true,Float.parseFloat(args[2])):null;
        for(String name:List.of("original","negative","letters","sentences","mixed")) {
            var report=JsonParser.parseString(Files.readString(Path.of(input+"/"+prefix+name+".json"))).getAsJsonObject();
            var baseline=JsonParser.parseString(Files.readString(Path.of(".local/diagnostics/dev7/final-"+name+".json"))).getAsJsonObject();
            var baselineRows=new HashMap<String,JsonObject>();for(var row:baseline.getAsJsonArray("rows"))baselineRows.put(row.getAsJsonObject().getAsJsonObject("clip").get("id").getAsString(),row.getAsJsonObject());
            var words=List.of(gson.fromJson(report.get("evaluationVocabulary"),WordMatcher.Word[].class));
            var losses=new ArrayList<String>();var newFalse=new ArrayList<String>();var removedFalse=new ArrayList<String>();int hits=0,falseHits=0;
            for(var item:report.getAsJsonArray("rows")) {
                var row=item.getAsJsonObject();String id=row.getAsJsonObject("clip").get("id").getAsString();
                Set<String> expected=new HashSet<>();for(var value:row.getAsJsonArray("expectedWords"))expected.add(value.getAsString());
                if(!row.getAsJsonObject("clip").get("sha256").equals(baselineRows.get(id).getAsJsonObject("clip").get("sha256")))throw new AssertionError("WAV changed");
                Set<String> previous=baselineRows.get(id).getAsJsonObject("hits").keySet();var current=new HashSet<String>();
                for(var word:words) {
                    var window=new RoundSpeechWindow();window.rotate(List.of(word),100000);
                    for(var value:row.getAsJsonArray("events")) {
                        var event=value.getAsJsonObject();var candidates=new ArrayList<String>();for(var c:event.getAsJsonArray("candidates"))candidates.add(c.getAsString());
                        boolean accepted=event.has("postprocess")&&event.getAsJsonObject("postprocess").has("latest")&&event.getAsJsonObject("postprocess").getAsJsonObject("latest").get("accepted").getAsBoolean();
                        boolean confirmed=accepted||event.has("reviewConfirmed")&&event.get("reviewConfirmed").getAsBoolean()||event.has("postprocess")&&event.getAsJsonObject("postprocess").has("latest")&&event.getAsJsonObject("postprocess").getAsJsonObject("latest").get("reason").getAsString().equals("unchanged");
                        var packet=SpeechPacket.parse(SpeechPacket.encode(candidates,accepted?Set.of(candidates.size()-1):Set.of(),confirmed));
                        long now=100000+Math.round(event.get(report.get("realtime").getAsBoolean()?"wallMs":"audioMs").getAsDouble());
                        if(window.accept("p",packet,now,phonetic).hit()!=null){current.add(word.text());break;}
                    }
                }
                for(String word:current)if(expected.contains(word))hits++;else {falseHits++;if(!previous.contains(word))newFalse.add(id+":"+word);}
                for(String word:previous)if(!current.contains(word)){if(expected.contains(word))losses.add(id+":"+word);else removedFalse.add(id+":"+word);}
            }
            var summary=new LinkedHashMap<String,Object>();summary.put("group",name);summary.put("hits",hits);summary.put("falseHits",falseHits);summary.put("lost",losses);summary.put("newFalse",newFalse);summary.put("removedFalse",removedFalse);summaries.add(summary);
            if(phonetic==null&&!newFalse.isEmpty())throw new AssertionError(gson.toJson(summary));
        }
        Path output=Path.of(args.length>1?args[1]:input).resolve("reviewed-events.json");
        Files.createDirectories(output.getParent());Files.writeString(output,gson.toJson(summaries));
        System.out.println(gson.toJson(summaries));
    }
}
