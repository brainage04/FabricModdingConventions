"""Measure both delivered blender-q cameras from their packed scenes, not estimates."""
import bpy
import json
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from scene import shoot_signature, resources
report={'resources':resources(),'references':{}}
for era in ['legacy','modern']:
    path=ROOT.parent/'blender-q'/(era+'-hub-down-left-1.blend')
    bpy.ops.wm.open_mainfile(filepath=str(path))
    report['references'][era]={'source':str(path),'measured':shoot_signature(),'lighting_parameters':json.loads(bpy.context.scene['lighting_parameters_json'])}
    sc=bpy.context.scene
    print('REFERENCE_COMPOSITOR',era,'transparent',sc.render.film_transparent,'use_nodes',sc.use_nodes,'groups',[(g.name,[n.bl_idname for n in g.nodes]) for g in bpy.data.node_groups],flush=True)
(ROOT/'evidence/reference-measurements.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
