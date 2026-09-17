"""The single owner-selected conventions hub; exact modern template shoot.
Reproduce via run-blender.py conventions2-selected-owner-layout.py. No display/audio/GPU.
The copied reference importer and vanilla lighting functions are used unchanged.
"""
import bpy
import hashlib
import itertools
import json
import math
import os
import sys
import time
from pathlib import Path
from mathutils import Matrix, Vector
from bpy_extras.object_utils import world_to_camera_view
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT/'source'))
import render_hubs as approved
mc = approved.mc
SAMPLES = 32
THREADS = 2
TARGET = Vector((0, -1, 0))


def dump(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resources():
    relative = next(row.split(':', 2)[2] for row in Path('/proc/self/cgroup').read_text().splitlines() if row.startswith('0:'))
    cg = Path('/sys/fs/cgroup')/relative.lstrip('/')
    weight = int((cg/'cpu.weight').read_text())
    maximum = (cg/'cpu.max').read_text().strip()
    quota, period = maximum.split()
    affinity = sorted(os.sched_getaffinity(0))
    assert 'render-blender.service' in relative and weight == 20
    assert quota != 'max' and int(quota)/int(period) <= 3
    assert len(affinity) <= 2
    assert not os.getenv('DISPLAY') and not os.getenv('WAYLAND_DISPLAY')
    assert not os.getenv('CUDA_VISIBLE_DEVICES') and bpy.app.background
    return {'cgroup':relative,'cpu_weight':weight,'cpu_max':maximum,'affinity':affinity,'threads':THREADS,'background':True,'display':None,'audio':None,'gpu':None}


def shoot_signature():
    scene = bpy.context.scene
    cam = scene.camera
    direction = (cam.matrix_world.to_quaternion() @ Vector((0, 0, 1))).normalized()
    return {
        'camera_matrix': [list(row) for row in cam.matrix_world],
        'camera_location': list(cam.location),
        'camera_target': list(scene['camera_target']),
        'projection': cam.data.type,
        'orthographic_scale': cam.data.ortho_scale,
        'measured_yaw_degrees': math.degrees(math.atan2(direction.x, -direction.y)),
        'measured_pitch_degrees': math.degrees(math.atan2(direction.z, math.hypot(direction.x, direction.y))),
        'background_linear_rgba': list(scene.world.node_tree.nodes['Background'].inputs[0].default_value),
        'background_strength': scene.world.node_tree.nodes['Background'].inputs[1].default_value,
        'view': [scene.view_settings.view_transform, scene.view_settings.look, scene.view_settings.exposure, scene.view_settings.gamma],
        'dither': scene.render.dither_intensity,
        'denoising': scene.cycles.use_denoising,
    }


def meshes(station):
    return [child for child in station.children_recursive if child.type == 'MESH']


def verify(candidate, reference_signature, reference_slots, state_specs):
    scene = bpy.context.scene
    shoot = shoot_signature()
    assert shoot == reference_signature, ('Reference shoot changed', shoot, reference_signature)
    assert abs(shoot['measured_yaw_degrees']-45) < 1e-4
    assert abs(shoot['measured_pitch_degrees']-math.degrees(math.atan(1/math.sqrt(2)))) < 1e-4
    assert scene.render.engine == 'CYCLES' and scene.cycles.device == 'CPU'
    assert scene.cycles.samples == SAMPLES and scene.render.threads == THREADS
    assert scene.render.resolution_x == scene.render.resolution_y == 1024
    assert not scene.render.film_transparent
    assert not any(group.bl_idname == 'CompositorNodeTree' for group in bpy.data.node_groups)
    assert not any(o.type in {'LIGHT','FONT'} for o in scene.objects)
    stations = sorted([o for o in scene.objects if o.get('station')], key=lambda o:o['slot_index'])
    assert [o['station'] for o in stations] == candidate['blocks']
    assert len(stations) == 7
    bounds = {}
    world_bounds = {}
    facings = []
    camera_inverse = scene.camera.matrix_world.to_3x3().inverted()
    for station, slot in zip(stations, reference_slots):
        name = station['station']
        assert list(station.location) == slot['position_blender_xyz']
        assert station['position'] == slot['position']
        spec = state_specs[name]
        degrees = spec['variant'].get('y',0)
        assert abs(station.rotation_euler.z + math.radians(degrees)) < 1e-6
        assert tuple(station.scale) == (1,1,1)
        children = meshes(station)
        model_holder = next(o for o in station.children_recursive if o.get('model'))
        resolved = mc.resolve_model(spec['variant']['model'])
        assert len(children) == len(resolved['elements']), (name, 'vanilla element count')
        assert tuple(model_holder.scale) == (1,1,1) and max(abs(x) for x in model_holder.rotation_euler) < 1e-7
        for child in children:
            # A basis change and legal cardinal rotations may reflect axes, never scale them.
            linear = child.matrix_world.to_3x3()
            assert all(abs(linear.col[k].length-1) < 1e-6 for k in range(3))
            assert all(abs(linear.col[a].dot(linear.col[b])) < 1e-6 for a,b in itertools.combinations(range(3),2))
            assert not child.modifiers
        points = [child.matrix_world @ v.co for child in children for v in child.data.vertices]
        projected = [world_to_camera_view(scene, scene.camera, p) for p in points]
        bb = [min(p.x for p in projected),min(p.y for p in projected),max(p.x for p in projected),max(p.y for p in projected)]
        assert all(.035 < x < .965 for x in bb), (name, bb)
        bounds[name] = bb
        world_bounds[name] = [[min(p[k] for p in points) for k in range(3)],[max(p[k] for p in points) for k in range(3)]]
        facing = {'block':name,'slot':slot['position'],'position_blender_xyz':list(station.location),'blockstate':spec,'applied_blender_z_degrees':math.degrees(station.rotation_euler.z),'scale_xyz':list(station.scale),'official_model_element_count':len(children)}
        if spec['directional']:
            north_y = spec['north_variant'].get('y',0)
            # North variants normalize models such as comparators/repeaters whose raw model points south.
            local_semantic_front = Matrix.Rotation(math.radians(north_y),3,'Z') @ Vector((1,0,0))
            actual = (station.matrix_world.to_3x3() @ local_semantic_front).normalized()
            assert (actual-TARGET).length < 1e-6, (name, list(actual))
            screen = camera_inverse @ actual
            delta = [screen.x,-screen.y]
            length = math.hypot(*delta)
            direction = [v/length for v in delta]
            expected = [-math.sqrt(3)/2,.5]
            assert max(abs(a-b) for a,b in zip(direction,expected)) < 1e-6
            facing.update({'blender_front_xyz':list(actual),'normalized_png_direction':direction,'matches_blender_q_east_down_left':True})
            if name == 'observer':
                fronts = [(child.matrix_world.to_3x3().inverted().transposed() @ p.normal).normalized() for child in children for p in child.data.polygons if 'observer_front' in child.data.materials[p.material_index].name]
                assert fronts and all(n.dot(TARGET) > .999999 for n in fronts)
                facing['observer_textured_front_normals'] = [list(n) for n in fronts]
        else:
            assert abs(station.rotation_euler.z) < 1e-7
            facing['canonical_no_facing_rotation_preserved'] = True
        facings.append(facing)
    pairs = []
    for a,b in itertools.combinations(candidate['blocks'],2):
        wa,wb = world_bounds[a],world_bounds[b]
        separating_axes = [k for k in range(3) if wa[1][k] <= wb[0][k] or wb[1][k] <= wa[0][k]]
        assert separating_axes, ('Inter-block geometry intersects',a,b)
        pa,pb = bounds[a],bounds[b]
        assert min(pa[2],pb[2]) <= max(pa[0],pb[0]) or min(pa[3],pb[3]) <= max(pa[1],pb[1]), ('Screen overlap',a,b)
        pairs.append({'a':a,'b':b,'world_aabb_separating_axes':separating_axes,'projected_aabbs_disjoint':True})
    assert {o.name for o in scene.objects if o.type=='MESH'} == {o.name for s in stations for o in meshes(s)}
    packed=[]
    for image in bpy.data.images:
        if image.source != 'FILE':
            continue
        assert image.packed_file
        payload = bytes(image.packed_file.data)
        disk = Path(bpy.path.abspath(image.filepath))
        assert payload == disk.read_bytes(), ('Packed image mismatch',image.name)
        packed.append({'image':image.name,'sha256':hashlib.sha256(payload).hexdigest(),'packed_matches_extracted_vanilla_bytes':True})
    bb = [min(b[0] for b in bounds.values()),min(b[1] for b in bounds.values()),max(b[2] for b in bounds.values()),max(b[3] for b in bounds.values())]
    margins = [bb[0]*1024,(1-bb[2])*1024,(1-bb[3])*1024,bb[1]*1024]
    return {'reference_camera_scale_lighting_background_preserved':True,'camera':shoot,'reference_camera':reference_signature,'measured_angle_deltas_degrees':[shoot['measured_yaw_degrees']-reference_signature['measured_yaw_degrees'],shoot['measured_pitch_degrees']-reference_signature['measured_pitch_degrees']],'station_positions_identical_to_reference':True,'uniform_one_minecraft_block_scale':True,'no_floor_architecture_connections_glow_or_text':True,'official_vanilla_models_only':True,'facings':facings,'world_bounds':world_bounds,'station_bounds_normalized_xy_y_up':bounds,'subject_bounds_normalized_xy_y_up':bb,'margins_pixels_left_right_top_bottom':margins,'inter_block_nonintersection_pairs':pairs,'projected_station_bounds_disjoint':True,'packed_textures':packed}


def render(index):
    resource_report = resources()
    candidates = json.loads((ROOT/'candidates.json').read_text())
    candidate = candidates[index-1]
    name = candidate['name']
    states = json.loads((ROOT/'selected-blockstates.json').read_text())
    slots = json.loads((ROOT/'source/facing-table.json').read_text())['tables']['modern']
    assert len(candidates) == 1 and index == 1
    assert dict(zip((slot['position'] for slot in slots), candidate['blocks'])) == {
        'center': 'crafting_table', 'top-left': 'redstone_lamp', 'top': 'note_block',
        'top-right': 'observer', 'bottom-left': 'target', 'bottom': 'jukebox',
        'bottom-right': 'lectern',
    }, 'Owner-selected slot assignment changed'
    bpy.ops.wm.open_mainfile(filepath=str(ROOT/'source/modern-hub-down-left-1.blend'))
    scene = bpy.context.scene
    baseline = shoot_signature()
    reference_lighting = json.loads(scene['lighting_parameters_json'])
    for obj in list(bpy.data.objects):
        if obj != scene.camera:
            bpy.data.objects.remove(obj,do_unlink=True)
    for collection in [bpy.data.meshes,bpy.data.materials,bpy.data.images,bpy.data.texts]:
        for data in list(collection):
            collection.remove(data,do_unlink=True)
    mc.ERA = 'modern'
    mc.MATERIALS.clear()
    mc.SOURCES.clear()
    for name_block,slot in zip(candidate['blocks'],slots):
        station = mc.empty(slot['position'].upper()+' '+name_block, role='center_station' if slot['position']=='center' else 'station')
        station['station'] = name_block
        station['slot_index'] = slots.index(slot)
        station['position'] = slot['position']
        station.location = slot['position_blender_xyz']
        state = states[name_block]
        station.rotation_euler.z = -math.radians(state['variant'].get('y',0))
        station['facing'] = state['facing']
        mc.model(state['variant']['model'], approved.placement_basis(station))
    approved.apply_daylight()
    lighting = json.loads(scene['lighting_parameters_json'])
    assert {k:v for k,v in lighting.items() if k!='block_levels_sampled'} == {k:v for k,v in reference_lighting.items() if k!='block_levels_sampled'}
    scene.cycles.samples = SAMPLES
    scene.cycles.device = 'CPU'
    scene.render.threads_mode = 'FIXED'
    scene.render.threads = THREADS
    scene.render.filepath = str(ROOT/(name+'.png'))
    scene['source_approved_png'] = 'blender-q/modern-hub-down-left-1.png'
    scene['facing_rule'] = 'Every directional block is east; blockstate-specific canonical offsets retained; no-facing blocks unrotated.'
    scene['layout_rule'] = 'Exact seven station positions copied from modern-hub-down-left-1; block choices only.'
    scene['candidate_json'] = json.dumps(candidate)
    scene['reference_shoot_json'] = json.dumps(baseline)
    scene['render_resources_json'] = json.dumps(resource_report)
    geometry = verify(candidate,baseline,slots,states)
    author_files = ['scene.py',name+'.py','candidates.json','selected-blockstates.json','source/minecraft.py','source/render_hubs.py']
    for filename in author_files:
        text = bpy.data.texts.load(str(ROOT/filename))
        text.use_fake_user = True
    bpy.ops.file.pack_all()
    bpy.context.preferences.filepaths.save_version = 0
    bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/(name+'.blend')))
    started = time.perf_counter()
    bpy.ops.render.render(write_still=True)
    duration = time.perf_counter()-started
    image = bpy.data.images.load(str(ROOT/(name+'.png')),check_existing=False)
    assert list(image.size) == [1024,1024] and len(image.pixels) == 1024*1024*4
    assert max(image.pixels[0:4]) > 0
    bpy.data.images.remove(image)
    metadata = {'name':name,'label':candidate['label'],'idea':candidate['idea'],'blocks':candidate['blocks'],'reference':'blender-q/modern-hub-down-left-1.png','blender_version':bpy.app.version_string,'engine':scene.render.engine,'device':scene.cycles.device,'samples':SAMPLES,'render_duration_seconds':duration,'resolution':[1024,1024],'png_sha256':sha(ROOT/(name+'.png')),'blend_sha256':sha(ROOT/(name+'.blend')),'author_scripts_sha256':{filename:sha(ROOT/filename) for filename in author_files},'lighting_parameters':lighting,'geometry':geometry,'png_decoded_after_render':True,'resources':resource_report,'background_note':'The actual modern reference is tinted near-black, not RGB zero. Its exact world color and strength are preserved per parent clarification; no environment or floor.'}
    dump(ROOT/(name+'-metadata.json'),metadata)
    bpy.ops.wm.open_mainfile(filepath=str(ROOT/(name+'.blend')))
    saved = verify(candidate,baseline,slots,states)
    for filename in author_files:
        assert bpy.data.texts[Path(filename).name].as_string() == (ROOT/filename).read_text()
    dump(ROOT/(name+'-verification.json'),{'reopened_saved_blend':True,'embedded_author_scripts_match_disk':True,'geometry':saved})
    print('RENDER_COMPLETE '+json.dumps({k:metadata[k] for k in ['name','blender_version','engine','device','samples','render_duration_seconds']}),flush=True)
