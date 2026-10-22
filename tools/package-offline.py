"""Build one verified offline distribution from local artifacts; never deploy to Minecraft."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path, algorithm='sha256'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mods-dir', required=True, type=Path)
    parser.add_argument('--metadata-dir', type=Path, default=ROOT / '.local/distributions/voicekill-dev5')
    parser.add_argument('--wordbank-source', type=Path)
    parser.add_argument('--output-dir', type=Path, default=ROOT / '.local/distributions')
    args = parser.parse_args()
    version = re.search(r'^mod_version=(.+)$', (ROOT / 'gradle.properties').read_text(encoding='utf-8'), re.M)[1].strip()
    name = f'VoiceKill-{version}-MC1.21.11-Offline-NoVosk'
    output = args.output_dir.resolve()
    dest = output / name
    archive = output / (name + '.zip')
    if archive.exists() or dest.exists():
        raise FileExistsError('Use a new version/output; existing packages are preserved.')
    dest.mkdir(parents=True)
    files = []

    def copy(source, relative, origin='local'):
        target = dest / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        files.append(dict(path=relative, size=target.stat().st_size, sha256=digest(target), source=origin))

    dependencies = [('fabric-api', 'fabric-api-0.141.6+1.21.11.jar'),
                    ('architectury-api', 'architectury-19.0.1-fabric.jar'),
                    ('shriek', 'shriek-fabric-1.1.22.jar')]
    for slug, filename in dependencies:
        metadata_file = args.metadata_dir / (slug + '-metadata.json')
        metadata = json.loads(metadata_file.read_text(encoding='utf-8'))
        asset = next(item for item in metadata['release']['files'] if item['primary'])
        source = args.mods_dir / filename
        assert digest(source, 'sha512') == asset['hashes']['sha512'], filename
        copy(source, '.minecraft/mods/' + filename, asset['url'])
        copy(metadata_file, 'sources/' + metadata_file.name)

    jar = ROOT / 'build/libs' / f'voicekill-{version}.jar'
    with zipfile.ZipFile(jar) as built:
        assert json.loads(built.read('fabric.mod.json'))['version'] == version
        assert not any(n.endswith(('.onnx', '.wav')) for n in built.namelist())
    copy(jar, '.minecraft/mods/' + jar.name)
    wordbank = ROOT / 'src/main/java/com/example/voicekill/wordbank/WordBank.java'
    if args.wordbank_source:
        assert wordbank.read_bytes() == args.wordbank_source.read_bytes()
    copy(wordbank, 'sources/WordBank.java', 'user-provided word bank')

    models = [('zipformer-bilingual-small-int8', 'zipformer-bilingual-small'),
              ('sensevoice-review-int8', 'sensevoice-int8'),
              ('punctuation-zh-en-int8', 'punctuation-int8'), ('silero-vad', 'sensevoice-int8')]
    for identifier, local in models:
        manifest_file = ROOT / 'src/main/resources/voicekill/asr' / (identifier + '.json')
        manifest = json.loads(manifest_file.read_text(encoding='utf-8'))
        copy(manifest_file, 'sources/' + manifest_file.name, manifest['source'])
        for asset in manifest['files']:
            source = ROOT / '.local/models' / local / asset['name']
            assert source.stat().st_size == asset['size'] and digest(source) == asset['sha256'], source
            copy(source, '.minecraft/voicekill/models/' + identifier + '/' + asset['name'], asset['url'])

    copy(ROOT / 'LICENSE', 'licenses/VoiceKill-MIT.txt')
    for license_file in (ROOT / 'src/main/resources/META-INF/voicekill-licenses').iterdir():
        if license_file.is_file():
            copy(license_file, 'licenses/' + license_file.name)
    for jar_name, member, label in [('fabric-api-0.141.6+1.21.11.jar', 'LICENSE-fabric-api', 'Fabric-API-LICENSE.txt'),
                                    ('shriek-fabric-1.1.22.jar', 'META-INF/LICENSE', 'Shriek-MIT.txt')]:
        with zipfile.ZipFile(args.mods_dir / jar_name) as bundled:
            (dest / 'licenses' / label).write_bytes(bundled.read(member))
    for filename in ['architectury-fabric-19.0.1-sources.jar', 'architectury-19.0.1-sources.jar', 'architectury-LICENSE.md']:
        copy(args.metadata_dir / 'downloads' / filename, 'sources/' + filename, 'https://maven.architectury.dev/')

    block = (ROOT / 'src/main/java/com/example/voicekill/DeathMessages.java').read_text(encoding='utf-8').split('private static final List<String> DEFAULTS=List.of(', 1)[1].split(');', 1)[0]
    templates = [json.loads(line.strip().rstrip(',')) for line in block.splitlines() if line.strip()]
    assert len(templates) == 20
    config = dict(speechEngine='zipformer', speechThreads=1, speechNaturalBoundary=True,
                  speechContextHints=True, speechEndpointSilenceSeconds=.6, speechMaxUtteranceSeconds=10.,
                  speechSentenceReview=True, speechPunctuation=True, speechModelDir='', speechPostModelDir='',
                  allowVoskFallback=False, autoModelDownload=False, autoStartMicrophone=True,
                  hudTranscript=True, hudHotbarHint=True, customDeathMessages=True,
                  silencePenaltyEnabled=False, silenceTimeoutSeconds=20., silenceDamageAmount=2., silenceDamageIntervalSeconds=4.,
                  phoneticMatching=True, phoneticThreshold=.30,
                  deathMessageStyle='friends', deathMessages=templates)
    (dest / 'config-template').mkdir()
    (dest / 'config-template/voicekill.json').write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    copy(ROOT / 'docs/INSTALL_DEV7.md', '安装教程.md')
    (dest / '安装教程.txt').write_text((ROOT / 'docs/INSTALL_DEV7.md').read_text(encoding='utf-8'), encoding='utf-8-sig')
    copy(ROOT / 'docs/DEV7_SETTINGS.md', '设置详解.md')
    validation = ROOT / 'docs' / ('DEV' + version.rsplit('dev.', 1)[-1] + '_VALIDATION.md')
    copy(validation if validation.exists() else ROOT / 'docs/DEV7_VALIDATION.md', '验证记录.md')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    (dest / 'manifest.json').write_text(json.dumps(dict(version=version, minecraft='1.21.11', sourceCommit=commit,
                                                       containsVoskModel=False, files=files), ensure_ascii=False, indent=2), encoding='utf-8')
    (dest / 'SHA256SUMS.txt').write_text('\n'.join(digest(p) + '  ' + p.relative_to(dest).as_posix()
                                                for p in sorted(dest.rglob('*')) if p.is_file()) + '\n', encoding='utf-8')
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as packaged:
        for file in sorted(dest.rglob('*')):
            if file.is_file():
                packaged.write(file, name + '/' + file.relative_to(dest).as_posix())
    with zipfile.ZipFile(archive) as packaged:
        assert packaged.testzip() is None
        names = packaged.namelist()
        assert len([n for n in names if '/.minecraft/mods/' in n]) == 4
        assert len([n for n in names if '/.minecraft/voicekill/models/' in n]) == 9
        assert not any('/game-root/' in n or '/gam-root/' in n for n in names)
        assert not any('/vosk/' in n.lower() or 'vosk-model-' in n.lower() for n in names)
    checksum = digest(archive)
    (output / (archive.name + '.sha256')).write_text(checksum + '  ' + archive.name + '\n', encoding='utf-8')
    report = dict(archive=str(archive), size=archive.stat().st_size, sha256=checksum, sourceCommit=commit)
    report_path = ROOT / '.local/diagnostics' / ('dev' + version.rsplit('dev.', 1)[-1]) / 'package-result.json'
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
