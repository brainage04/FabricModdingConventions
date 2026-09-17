"""Final matched hub layouts, derived from the approved blender-i scenes.
Saved scripts, not Git commits: this directory is explicitly local-only.
CLI: blender --background -noaudio --python render_hubs.py -- legacy-hub-1 modern-hub-1
Both templates use the same alternating layout, camera and canonical geometry.
"""
import bpy
import sys
import math
import json
import time
import hashlib
from pathlib import Path
from mathutils import Vector, Matrix
from bpy_extras.object_utils import world_to_camera_view
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
import minecraft as mc
SAMPLES = 32
PITCH = math.degrees(math.atan(1 / math.sqrt(2)))
STATIONS = {
    'legacy': ['furnace', 'enchanting_table', 'anvil', 'chest', 'ender_chest', 'brewing_stand'],
    'modern': ['blast_furnace', 'enchanting_table', 'smithing_table', 'chest', 'grindstone', 'brewing_stand'],
}
POSITIONS = ['top', 'top-right', 'bottom-right', 'bottom', 'bottom-left', 'top-left']
APPROVED = {
    'legacy': ['brewing_stand', 'furnace', 'anvil', 'chest', 'ender_chest', 'enchanting_table'],
    'modern': ['brewing_stand', 'enchanting_table', 'grindstone', 'chest', 'smithing_table', 'blast_furnace'],
}
DIRECTIONAL = {'furnace', 'blast_furnace', 'anvil', 'chest', 'ender_chest', 'grindstone'}
CARDINALS = [
    (0, 'north', Vector((1, 0, 0))),
    (90, 'east', Vector((0, -1, 0))),
    (180, 'south', Vector((-1, 0, 0))),
    (270, 'west', Vector((0, 1, 0))),
]
LAYOUT_RULE = 'Evenly spaced differing stations: top, bottom-right, bottom-left (alternating outer slots 1, 3, 5).'


def set_facing(station, location):
    """Use the nearest real cardinal block facing, not an arbitrary radial turn."""
    name = station['station']
    station['placement_rotation_degrees'] = 0
    station['facing'] = 'none; no facing blockstate'
    if name in DIRECTIONAL:
        toward = -location.normalized()
        degrees, facing, direction = max(CARDINALS, key=lambda item: round(item[2].dot(toward), 6))
        station['placement_rotation_degrees'] = degrees
        station['facing'] = facing
        station.rotation_euler.z = -math.radians(degrees)
        station['center_alignment_dot'] = direction.dot(toward)
        station['center_error_degrees'] = math.degrees(math.acos(max(-1, min(1, direction.dot(toward)))))
    station['canonical_model_y_degrees'] = (
        (station['placement_rotation_degrees'] + 180) % 360 if name == 'anvil'
        else station['placement_rotation_degrees'])


def facing_table():
    rows = []
    scene = bpy.context.scene
    for station in sorted((o for o in scene.objects if o.get('station')), key=lambda o: o['slot_index']):
        name = station['station']
        loc = station.matrix_world.translation
        row = {
            'station': name, 'position': station['position'], 'slot_index': station['slot_index'],
            'position_blender_xyz': list(loc), 'position_minecraft_xyz': [-loc.y, loc.z, -loc.x],
            'approved_position': 'center' if name == 'crafting_table' else POSITIONS[APPROVED[scene['era']].index(name)],
            'facing': station['facing'], 'facing_angle_degrees_from_north': station['placement_rotation_degrees'] if name in DIRECTIONAL else None,
            'applied_rotation_degrees': station['placement_rotation_degrees'],
            'canonical_model_y_degrees': station['canonical_model_y_degrees'],
            'nearest_cardinal_error_degrees': station.get('center_error_degrees'),
            'layout_rule': LAYOUT_RULE,
            'shared_between_templates': name in {'crafting_table', 'brewing_stand', 'chest', 'enchanting_table'},
        }
        if name == 'enchanting_table':
            row['book_entity_yaw_degrees_blender'] = bpy.data.objects['Book facing hub center']['yaw_degrees']
            row['note'] = 'Table has no facing property; canonical table is unchanged. The animated book entity points exactly toward the centre, as in the approved geometry.'
        elif name not in DIRECTIONAL:
            row['note'] = 'No legal facing property; canonical orientation retained rather than inventing a rotation.'
        elif name == 'anvil':
            row['note'] = 'Legacy canonical anvil model faces south at model y=0; semantic south-facing 180 degrees therefore has canonical model y=0.'
        rows.append(row)
    return rows


def vanilla_material(name, tint=(1, 1, 1), emission=0, solid=False):
    """Vanilla fixed face shading, with gamma-domain texture multiplication.
    No studio lamps, filmic tone mapping, specular highlights or ray-traced AO.
    Textures remain byte-identical official assets; colour factors are attributes.
    """
    key = (mc.ERA, name, tuple(tint))
    if key in mc.MATERIALS:
        return mc.MATERIALS[key]
    mat = bpy.data.materials.new(mc.ERA + ':' + name)
    mat.use_nodes = True
    n, l = mat.node_tree.nodes, mat.node_tree.links
    n.clear()
    out = n.new('ShaderNodeOutputMaterial')
    emit = n.new('ShaderNodeEmission')
    alpha = n.new('ShaderNodeBsdfTransparent')
    mix = n.new('ShaderNodeMixShader')
    tex = n.new('ShaderNodeTexImage')
    path = mc.asset('textures', mc.texture(name) + '.png')
    assert path.is_file(), path
    image = bpy.data.images.load(str(path), check_existing=True)
    image.pack()
    tex.image = image
    tex.interpolation = 'Closest'
    tex.extension = 'REPEAT'
    mc.SOURCES.add(str(path.relative_to(ROOT)))
    if path.with_suffix('.png.mcmeta').exists() and image.size[1] > image.size[0]:
        mat['frame_ratio'] = image.size[0] / image.size[1]
    # Minecraft's vertex shade is multiplied into gamma-encoded texels.
    gamma_out = n.new('ShaderNodeGamma')
    gamma_out.inputs[1].default_value = 1 / 2.2
    l.new(tex.outputs['Color'], gamma_out.inputs[0])
    attr = n.new('ShaderNodeAttribute')
    attr.attribute_name = 'vanilla_daylight'
    multiply = n.new('ShaderNodeMixRGB')
    multiply.blend_type = 'MULTIPLY'
    multiply.inputs[0].default_value = 1
    l.new(gamma_out.outputs[0], multiply.inputs[1])
    l.new(attr.outputs['Color'], multiply.inputs[2])
    gamma_in = n.new('ShaderNodeGamma')
    gamma_in.inputs[1].default_value = 2.2
    l.new(multiply.outputs[0], gamma_in.inputs[0])
    l.new(gamma_in.outputs[0], emit.inputs[0])
    l.new(tex.outputs['Alpha'], mix.inputs[0])
    l.new(alpha.outputs[0], mix.inputs[1])
    l.new(emit.outputs[0], mix.inputs[2])
    l.new(mix.outputs[0], out.inputs[0])
    mc.MATERIALS[key] = mat
    return mat


mc.material = vanilla_material


def chest(parent, ender=False):
    tex = 'entity/chest/' + ('ender' if ender else 'normal')
    holder = mc.empty('Vanilla closed ' + ('ender chest' if ender else 'chest'), parent)
    holder['entity'] = 'ender_chest' if ender else 'chest'
    # Vanilla bottom is 10 pixels high, overlapping the closed lid by one pixel.
    # Keep its visible 9-pixel surface only, with the corresponding UV subrectangle:
    # no coplanar ray-tracing surfaces, no gap, no added black separator geometry.
    body_uv = mc.entity_rects(0, 19, 14, 10, 14)
    lid_uv = mc.entity_rects(0, 0, 14, 5, 14)
    lock_uv = mc.entity_rects(0, 0, 2, 4, 1)
    for side in ['north', 'south', 'west', 'east']:
        u, v, w, h = body_uv[side]
        body_uv[side] = (u, v + 1, w, h - 1)
        if mc.ERA == 'modern':
            # Modern ModelPart uses upward Y in this model, unlike the old chest.
            body_uv[side] = (u, v + h - 1, w, -(h - 1))
            u2, v2, w2, h2 = lid_uv[side]
            lid_uv[side] = (u2, v2 + h2, w2, -h2)
            u2, v2, w2, h2 = lock_uv[side]
            lock_uv[side] = (u2, v2 + h2, w2, -h2)
    if mc.ERA == 'modern':
        lid_uv['up'], lid_uv['down'] = lid_uv['down'], lid_uv['up']
        body_uv['up'], body_uv['down'] = body_uv['down'], body_uv['up']
    body = mc.box('Chest body visible exterior', (0, 0, 4.5/16), (14/16, 14/16, 9/16), tex, holder, rects=body_uv)
    lid = mc.box('Chest lid', (0, 0, 11.5/16), (14/16, 14/16, 5/16), tex, holder, rects=lid_uv)
    # Remove the two wholly internal horizontal faces at the shared closed seam.
    for obj, normal_z in [(body, 1), (lid, -1)]:
        import bmesh
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        bm.normal_update()
        bmesh.ops.delete(bm, geom=[f for f in bm.faces if f.normal.z * normal_z > .99], context='FACES')
        bm.to_mesh(obj.data)
        bm.free()
    mc.box('North-facing vanilla latch', (0, -7.5/16, (9 if mc.ERA == 'modern' else 8)/16), (2/16, 1/16, 4/16), tex, holder, rects=lock_uv)
    holder['closed_seam_z'] = 9/16
    holder['visible_body_uv'] = json.dumps(body_uv)
    holder['lid_uv'] = json.dumps(lid_uv)
    return holder


def book(parent):
    tex = 'entity/enchanting_table_book' if mc.ERA == 'legacy' else 'entity/enchantment/enchanting_table_book'
    root = mc.empty('Book facing hub center', parent)
    root.location = (0, 0, 1)
    # Local -Y is the reader side / bottom of the open page spread.
    toward = -parent.parent.location
    root.rotation_euler.z = math.atan2(toward.x, -toward.y)
    root.rotation_euler.x = math.radians(12)
    root['reader_direction_local'] = [0, -1, 0]
    root['yaw_degrees'] = math.degrees(root.rotation_euler.z)
    for sign in (-1, 1):
        leaf = mc.empty('Book leaf', root)
        leaf.rotation_euler.y = sign * math.radians(-18)
        leaf.location.x = sign * .025
        rect = {k: (0 if sign == -1 else 16, 0, 6, 10) for k in ['north','south','east','west','up','down']}
        mc.box('Book leather cover', (sign*3/16, 0, 0), (6/16, 10/16, .005), tex, leaf, rects=rect, atlas=(64,32))
        # Vanilla page cubes are 5x8x1 (width, page length, thickness).
        # Their broad page face is the atlas's 5x8 north face, not the
        # 5x8 'up' of a fictitious 5x1x8 cuboid (which crosses unused black UVs).
        source = mc.entity_rects(0 if sign == -1 else 12, 10, 5, 8, 1)
        rect = {'up':source['north'], 'down':source['south'],
                'north':source['up'], 'south':source['down'],
                'west':source['west'], 'east':source['east']}
        mc.box('Book page stack', (sign*2.6/16, 0, 1/32), (5/16, 8/16, 1/16), tex, leaf, rects=rect, atlas=(64,32))
    return root


def aim(target, scale, yaw=45):
    target = Vector(target)
    yaw_r = math.radians(yaw)
    cam = bpy.context.scene.camera
    cam.location = target + Vector((12*math.sin(yaw_r), -12*math.cos(yaw_r), 12*math.tan(math.radians(PITCH))))
    cam.rotation_euler = (target - cam.location).to_track_quat('-Z','Y').to_euler()
    cam.data.ortho_scale = scale
    bpy.context.scene['camera_target'] = list(target)
    bpy.context.scene['camera_yaw_degrees'] = yaw


def light_brightness(level):
    normalized = level / 15
    return normalized / (4 - 3 * normalized)


def lightmap_rgb(era, sky, block):
    """Noon, gamma=0, no effects; legacy EntityRenderer / modern lightmap.fsh."""
    sky_brightness = light_brightness(sky)
    block_brightness = light_brightness(block)
    if era == 'legacy':
        torch = block_brightness * 1.5  # torchFlickerX = 0
        channels = [sky_brightness + torch,
                    sky_brightness + torch * ((torch*.6 + .4)*.6 + .4),
                    sky_brightness + torch * (torch*torch*.6 + .4)]
        # 1.8.9 performs these two .96/.03 passes around the gamma mix.
        channels = [min(1, c*.96 + .03) for c in channels]
        return [int(min(1, c*.96 + .03)*255)/255 for c in channels]
    # Current 26.2 shader, explicitly selected neutral open-sky parameters:
    # SkyFactor=BlockFactor=1; SkyLightColor=(1,1,1); AmbientColor=(.03)*3.
    # BrightnessFactor, DarknessScale, BossOverlay, NightVisionFactor = 0.
    factor = .9 * (2*block/15 - 1)**2
    tint = [c*(1-factor) + factor for c in (1,.9,.75)]
    return [min(1, .03 + sky_brightness + block_brightness*c) for c in tint]


def apply_daylight():
    bpy.context.view_layer.update()
    scene = bpy.context.scene
    emission = {'enchanting_table':7, 'ender_chest':7, 'brewing_stand':1}
    sources = [(o.matrix_world.translation + Vector((0,0,.5)), emission[o['station']])
               for o in scene.objects if o.get('station') in emission]
    samples = []
    for obj in bpy.context.scene.objects:
        if obj.type != 'MESH':
            continue
        entity = any(o.get('entity') for o in [obj.parent, obj.parent.parent] if o)
        attr = obj.data.color_attributes.new(name='vanilla_daylight', type='FLOAT_COLOR', domain='CORNER')
        normal_matrix = obj.matrix_world.to_3x3().inverted().transposed()
        for p in obj.data.polygons:
            normal = (normal_matrix @ p.normal).normalized()
            if entity:
                # Vanilla entity ambient .4 + two normalized diffuse directions.
                l0 = Vector((.7, -.2, 1)).normalized()
                l1 = Vector((-.7, .2, 1)).normalized()
                shade = min(1, .4 + .6 * (max(0, normal.dot(l0)) + max(0, normal.dot(l1))))
            else:
                # ClientLevel#getShade: down=.5, up=1, north/south=.8, east/west=.6.
                axis = max(range(3), key=lambda k: abs(normal[k]))
                # Minecraft X maps to Blender -Y; Minecraft Z maps to -X.
                shade = [.8, .6, 1 if normal.z > 0 else .5][axis]
            sample = obj.matrix_world @ p.center + normal * .0001
            # Every neighboring air face has open sky. Block light uses integer
            # one-level-per-block Manhattan falloff in the unobstructed icon field.
            sky = 15
            block = max([0] + [max(0, level-math.ceil(sum(abs(sample[k]-pos[k]) for k in range(3))))
                              for pos, level in sources])
            rgb = lightmap_rgb(mc.ERA, sky, block)
            samples.append({'mesh':obj.name, 'face':p.index, 'sample_position':list(sample),
                            'sky':sky, 'block':block, 'directional_shade':shade, 'lightmap_rgb':rgb})
            for loop in p.loop_indices:
                attr.data[loop].color = (*[c*shade for c in rgb], 1)
    scene['lightmap_samples_json'] = json.dumps(samples)
    scene['lighting_parameters_json'] = json.dumps({
        'era':mc.ERA, 'face_multipliers':{'up':1,'north':.8,'south':.8,'east':.6,'west':.6,'down':.5},
        'sky_level':15, 'sky_color':[1,1,1], 'sun_brightness':1,
        'brightness_falloff':'(level/15)/(4-3*level/15)',
        'block_levels_sampled':sorted({s['block'] for s in samples}),
        'emission_levels':emission, 'torch_flicker':0, 'gamma_setting':0,
        'night_vision':0, 'darkness':0, 'boss_overlay':0, 'ao':False,
        'sampling':'Per-face center offset into air; open-sky 15; unobstructed Manhattan source falloff. Not sampled from a Minecraft world save.',
        'legacy_lightmap':'EntityRenderer.updateLightmap: torch multiplier1.5; nonlinear RGB torch tint; two .96*x+.03 passes; integer8-bit quantization.',
        'modern_lightmap':'26.2 lightmap.fsh; SkyFactor=BlockFactor=1; AmbientColor=(.03,.03,.03); BlockLightTint=(1,.9,.75); BrightnessFactor=0.',
        'entity_ambient':.4, 'entity_diffuse':.6})


def placement_basis(parent):
    """Convert the inherited reflected mesh basis into a right-handed camera basis.
    Minecraft (x,y,z) -> Blender (-z,-x,y). This is not a blockstate rotation.
    A north-facing latch now projects screen-right at Minecraft camera yaw45,
    agreeing with the actual in-game close-ups. Node positions stay approved.
    """
    holder = mc.empty('Canonical Minecraft coordinate basis', parent)
    holder.matrix_local = Matrix(((0,-1,0,0),(-1,0,0,0),(0,0,1,0),(0,0,0,1)))
    return holder


def build(era, candidate):
    selected_scale = 7.15 if era == 'legacy' else 6.90
    scale = selected_scale if candidate == 1 else selected_scale + .2
    background = (.004, .003, .002) if era == 'legacy' else (.0025, .0035, .006)
    mc.setup(era, (8,-8,8), (0,0,.45), scale, background)
    scene = bpy.context.scene
    scene.cycles.samples = SAMPLES
    scene.cycles.use_denoising = False
    scene.cycles.transparent_max_bounces = 32
    scene.render.threads = 4
    scene.render.dither_intensity = 0
    scene.view_settings.view_transform = 'Standard'
    scene.view_settings.look = 'None'
    scene.view_settings.exposure = 0
    scene.world.node_tree.nodes['Background'].inputs[1].default_value = 1
    aim((0,0,.45), scale)
    scene['era'] = era
    scene['layout_rule'] = LAYOUT_RULE
    center = mc.empty('CENTER crafting_table', role='center_station')
    center['station'] = 'crafting_table'
    center['position'] = 'center'
    center['slot_index'] = 0
    set_facing(center, Vector((0, 0, 0)))
    mc.model('crafting_table', placement_basis(center))
    right, back = Vector((1,1,0)).normalized(), Vector((-1,1,0)).normalized()
    for index, (angle, name) in enumerate(zip([90,30,-30,-90,-150,150], STATIONS[era])):
        theta = math.radians(angle)
        node = mc.empty('OUTER NODE ' + str(index+1), role='outer_node')
        node.location = right*(2.35*math.cos(theta)) + back*(3.22*math.sin(theta))
        station = mc.empty('STATION ' + name, node, 'station')
        station['station'] = name
        station['position'] = POSITIONS[index]
        station['slot_index'] = index + 1
        set_facing(station, node.location)
        placed = placement_basis(station)
        if name in ['chest', 'ender_chest']:
            chest(placed, name == 'ender_chest')
        elif name == 'brewing_stand':
            mc.model('brewing_stand_bottles_123' if era == 'legacy' else 'brewing_stand', placed)
            if era == 'modern':
                for n in range(3):
                    mc.model('brewing_stand_bottle' + str(n), placed)
        elif name == 'enchanting_table':
            mc.model('enchanting_table_base' if era == 'legacy' else 'enchanting_table', placed)
            book(station)
        else:
            mc.model('anvil_undamaged' if name == 'anvil' else name, placed, yaw=180 if name == 'anvil' else 0)
    apply_daylight()
    return scale


def verify_geometry(full_view=True):
    scene = bpy.context.scene
    bpy.context.view_layer.update()
    nodes = [o for o in scene.objects if o.get('role') == 'outer_node']
    centers = [o for o in scene.objects if o.get('role') == 'center_station']
    stations = [o for o in scene.objects if o.get('role') == 'station']
    assert len(nodes) == 6 and len(centers) == 1 and len(stations) == 6
    assert centers[0]['station'] == 'crafting_table' and centers[0].location.length < 1e-8
    assert sorted(o['station'] for o in stations) == sorted(STATIONS[scene['era']])
    rows = facing_table()
    assert [r['slot_index'] for r in rows if not r['shared_between_templates']] == [1, 3, 5]
    for station in stations + centers:
        degrees = station['placement_rotation_degrees']
        assert degrees in [0, 90, 180, 270]
        assert abs(station.rotation_euler.z + math.radians(degrees)) < 1e-6
        if station['station'] in DIRECTIONAL:
            north = station.matrix_world.to_3x3() @ Vector((1, 0, 0))
            toward = -station.matrix_world.translation.normalized()
            assert abs(north.dot(toward) - max(d.dot(toward) for _, _, d in CARDINALS)) < 1e-6
        else:
            assert degrees == 0
    allowed = {c for o in stations + centers for c in o.children_recursive if c.type == 'MESH'}
    assert allowed == {o for o in scene.objects if o.type == 'MESH'}
    chest_root = next(o for o in stations if o['station'] == 'chest')
    expected_chest = Vector((1,-1,0)).normalized() * 3.22
    assert (chest_root.parent.location - expected_chest).length < 1e-5
    book_root = bpy.data.objects['Book facing hub center']
    reader = book_root.matrix_world.to_3x3() @ Vector((0,-1,0))
    reader.z = 0
    toward = -book_root.matrix_world.translation
    toward.z = 0
    alignment = reader.normalized().dot(toward.normalized())
    assert alignment > .999999
    brew = next(o for o in stations if o['station'] == 'brewing_stand')
    brew_meshes = [o for o in brew.children_recursive if o.type == 'MESH']
    feet = [o for o in brew_meshes if len(o.data.polygons) == 6 and abs(max(v.co.z for v in o.data.vertices) - 2/16) < 1e-7]
    arms = [o for o in brew_meshes if len(o.data.polygons) == 1]
    assert len(feet) == 3 and len(arms) == 3, (len(feet), len(arms))
    forward = (scene.camera.matrix_world.to_quaternion() @ Vector((0,0,-1))).normalized()
    arm_projection = [abs((o.matrix_world.to_3x3() @ o.data.polygons[0].normal).dot(forward)) for o in arms]
    target = Vector(scene['camera_target'])
    delta = scene.camera.location - target
    yaw = math.degrees(math.atan2(delta.x, -delta.y))
    assert abs(yaw - scene['camera_yaw_degrees']) < 1e-5
    bounds = {}
    for station in stations + centers:
        points = [world_to_camera_view(scene, scene.camera, obj.matrix_world @ v.co) for obj in station.children_recursive if obj.type == 'MESH' for v in obj.data.vertices]
        b = [min(p.x for p in points), min(p.y for p in points), max(p.x for p in points), max(p.y for p in points)]
        if full_view:
            assert all(.035 < value < .965 for value in b), (station.name, b)
        bounds[station['station']] = b
    assert not any(o.type == 'LIGHT' for o in scene.objects)
    assert all(i.packed_file for i in bpy.data.images if i.source == 'FILE')
    return {'center':'crafting_table', 'outer_station_names':STATIONS[scene['era']], 'outer_nodes':6, 'yaw_degrees':yaw, 'pitch_degrees':PITCH, 'layout_rule':LAYOUT_RULE, 'facing_table':rows, 'no_supports_or_connections':True, 'book_reader_center_dot':alignment, 'brewing_feet':len(feet), 'brewing_arm_planes':len(arms), 'brewing_projected_area_factors':arm_projection, 'station_bounds':bounds, 'studio_lights':0, 'textures_packed':True}


def render(name):
    assert name in ['legacy-hub-1', 'modern-hub-1'], name
    era = name.split('-')[0]
    build(era, 1)
    geometry = verify_geometry()
    geometry['coordinate_mapping'] = 'Minecraft (x,y,z) to Blender (-z,-x,y); north faces screen-right at yaw45. Six approved anchor coordinates retained; station assignments explicitly revised.'
    scene = bpy.context.scene
    scene.render.filepath = str(ROOT / (name + '.png'))
    script = ROOT / (name + '.py')
    assert script.exists(), script
    for path in [ROOT/'minecraft.py', ROOT/'render_hubs.py', script]:
        text = bpy.data.texts.load(str(path))
        text.use_fake_user = True
    bpy.ops.file.pack_all()
    scene['verification_json'] = json.dumps(geometry)
    bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / (name + '.blend')))
    start = time.perf_counter()
    bpy.ops.render.render(write_still=True)
    elapsed = time.perf_counter() - start
    image = bpy.data.images.load(str(ROOT / (name + '.png')), check_existing=False)
    assert list(image.size) == [1024,1024]
    from array import array
    pixels = array('f', [0]) * len(image.pixels)
    image.pixels.foreach_get(pixels)
    assert max(pixels[0::4]) > .1
    data = {'name':name, 'era':era, 'selected_source':'blender-i/' + name + '.png', 'blender_version':bpy.app.version_string, 'engine':scene.render.engine, 'device':scene.cycles.device, 'samples':scene.cycles.samples, 'render_duration_seconds':elapsed, 'resolution':[1024,1024], 'script':name + '.py', 'author_script_sha256':hashlib.sha256((ROOT/'render_hubs.py').read_bytes()).hexdigest(), 'geometry':geometry, 'png_decoded_after_render':True, 'packed_texture_count':len([i for i in bpy.data.images if i.source=='FILE' and i.packed_file]), 'resources':{'background':True,'display':None,'audio':None,'gpu':None,'threads':scene.render.threads}}
    data['lighting_parameters'] = json.loads(scene['lighting_parameters_json'])
    data['lightmap_face_samples'] = json.loads(scene['lightmap_samples_json'])
    (ROOT / (name + '-metadata.json')).write_text(json.dumps(data, indent=2) + '\n')
    print('RENDER_COMPLETE ' + json.dumps({'name':name, 'seconds':elapsed, 'blender':bpy.app.version_string, 'engine':'CYCLES', 'device':'CPU', 'samples':SAMPLES}), flush=True)


if __name__ == '__main__':
    for name in sys.argv[sys.argv.index('--')+1:]:
        render(name)
