"""Extract only byte-identical vanilla model dependencies; never paint substitute assets."""
import hashlib
import json
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent
JAR = Path('/home/thomas/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar')
ASSETS = ROOT/'source/assets/modern'
CHOICES = {
    'observer': 'facing=east,powered=false',
    'lectern': 'facing=east',
    'comparator': 'facing=east,mode=compare,powered=false',
    'repeater': 'delay=1,facing=east,locked=false,powered=false',
    'crafter': 'crafting=false,orientation=east_up,triggered=false',
    'redstone_lamp': 'lit=false',
}

def main():
    evidence = []
    states = {}
    extracted = set()
    with zipfile.ZipFile(JAR) as jar:
        def extract(member):
            content = jar.read(member)
            if member not in extracted:
                path = ASSETS/member
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
                assert path.read_bytes() == jar.read(member)
                evidence.append({'source': str(JAR), 'member': member, 'path': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(content).hexdigest(), 'byte_identical_to_jar': True})
                extracted.add(member)
            return content
        def model(name):
            name = name.replace('minecraft:', '')
            if '/' not in name:
                name = 'block/'+name
            data = json.loads(extract('assets/minecraft/models/'+name+'.json'))
            base = model(data['parent']) if data.get('parent') and not data['parent'].startswith('builtin/') else {}
            merged = {**base, **data, 'textures': {**base.get('textures', {}), **data.get('textures', {})}}
            for texture in merged.get('textures', {}).values():
                if texture.startswith('#'):
                    continue
                member = 'assets/minecraft/textures/'+texture.replace('minecraft:', '')+'.png'
                extract(member)
                if member+'.mcmeta' in jar.namelist():
                    extract(member+'.mcmeta')
            return merged
        names = sorted({name for candidate in json.loads((ROOT/'candidates.json').read_text()) for name in candidate['blocks']})
        for name in names:
            data = json.loads(extract('assets/minecraft/blockstates/'+name+'.json'))
            key = CHOICES.get(name, '')
            variant = data['variants'][key]
            assert isinstance(variant, dict) and not variant.get('x') and not variant.get('uvlock'), (name, variant)
            north_key = key.replace('facing=east', 'facing=north').replace('orientation=east_up', 'orientation=north_up')
            north = data['variants'][north_key]
            directional = north_key != key
            states[name] = {'variant_key': key, 'variant': variant, 'north_variant_key': north_key, 'north_variant': north, 'directional': directional, 'facing': 'east' if directional else 'n/a - no facing blockstate'}
            model(variant['model'])
    (ROOT/'asset-provenance.json').write_text(json.dumps({'minecraft_version':'26.2','jar':str(JAR),'assets':evidence}, indent=2)+'\n')
    (ROOT/'selected-blockstates.json').write_text(json.dumps(states, indent=2)+'\n')
    snapshots=[]
    for name in ['minecraft.py','render_hubs.py','modern-hub-down-left-1.blend','modern-hub-down-left-1-metadata.json','facing-table.json']:
        path=ROOT/'source'/name
        original=ROOT.parent/'blender-q'/('source' if name.endswith('.py') else '')/name
        assert path.read_bytes()==original.read_bytes()
        snapshots.append({'source':str(original),'path':str(path.relative_to(ROOT)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    (ROOT/'source-snapshot.json').write_text(json.dumps(snapshots,indent=2)+'\n')
    print(json.dumps({'blocks':names,'extracted_assets':len(evidence),'copied_reference_files':len(snapshots)}))

if __name__ == '__main__':
    main()
