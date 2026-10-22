"""Regenerate the compact game vocabulary from pinned, local official assets and ICU data."""
from pathlib import Path
import argparse,hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[1]
ICU_SHA='b3640b9f416a4411fd33c59abbeea8fd57d024c23e1819bf9673220a97499fe3'
LANG_SHA='daf8df119618b33ad7476a6be4ac2eadc802fd93'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--assets-dir',type=Path,required=True);p.add_argument('--icu-jar',type=Path,required=True);a=p.parse_args()
 assert hashlib.sha256(a.icu_jar.read_bytes()).hexdigest()==ICU_SHA,'Expected pinned ICU4J 77.1'
 source=a.assets_dir/'objects'/LANG_SHA[:2]/LANG_SHA
 assert hashlib.sha1(source.read_bytes()).hexdigest()==LANG_SHA,'Expected pinned Minecraft 1.21.11 Chinese asset'
 language=json.loads(source.read_text(encoding='utf-8'))
 terms=sorted(set(value.strip() for key,value in language.items() if key.startswith(('item.minecraft.','block.minecraft.','entity.minecraft.','biome.minecraft.','enchantment.minecraft.','effect.minecraft.')) and isinstance(value,str) and 4<=len(value.strip())<=24 and all('\u3400'<=c<='\u9fff' for c in value.strip())))
 subprocess.run(['jshell','--class-path',str(a.icu_jar.resolve()),str(ROOT/'tools/generate-pinyin.jsh')],cwd=ROOT,check=True)
 table=ROOT/'src/main/resources/voicekill/pinyin-icu-77.1.json'
 data=dict(minecraft='1.21.11',locale='zh_cn',source='https://resources.download.minecraft.net/'+LANG_SHA[:2]+'/'+LANG_SHA,sourceSha1=LANG_SHA,terms=terms,pronunciationSource=dict(library='ICU4J',version='77.1',librarySha256=ICU_SHA,tableSha256=hashlib.sha256(table.read_bytes()).hexdigest(),note='Han-Latin, one default reading per character; not acoustic evidence or semantic disambiguation'))
 (ROOT/'src/main/resources/voicekill/game-terms-zh-cn.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print('GAME_TERMS='+str(len(terms)))
if __name__=='__main__':main()
