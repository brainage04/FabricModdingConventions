"""Canonical Minecraft JSON/atlas helpers retained from blender-b; local-only copy."""
import bpy
import math
import json
from pathlib import Path
from mathutils import Vector, Matrix
ROOT = Path(__file__).resolve().parent
SAMPLES = 64
ERA = "modern"
MATERIALS = {}
SOURCES = set()

def asset(kind, name):
    name = name.replace('minecraft:', '')
    return ROOT / 'assets' / ERA / 'assets' / 'minecraft' / kind / name


def texture(name):
    if '/' not in name:
        name = ('blocks/' if ERA == 'legacy' else 'block/') + name
    return name


def material(name, tint=(1, 1, 1), emission=0, solid=False):
    key = (ERA, name, tuple(tint), emission, solid)
    if key in MATERIALS:
        return MATERIALS[key]
    mat = bpy.data.materials.new(ERA + ':' + name + ':' + str(emission))
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    shader = nodes.get('Principled BSDF')
    shader.inputs['Roughness'].default_value = .83
    if solid:
        shader.inputs['Base Color'].default_value = (*tint, 1)
        shader.inputs['Emission Color'].default_value = (*tint, 1)
    else:
        path = asset('textures', texture(name) + '.png')
        assert path.exists(), path
        SOURCES.add(str(path.relative_to(ROOT)))
        image = bpy.data.images.load(str(path), check_existing=True)
        image.pack()
        tex = nodes.new('ShaderNodeTexImage')
        tex.image = image
        tex.interpolation = 'Closest'
        tex.extension = 'REPEAT'
        output = tex.outputs['Color']
        if tint != (1, 1, 1):
            mix = nodes.new('ShaderNodeMixRGB')
            mix.blend_type = 'MULTIPLY'
            mix.inputs[0].default_value = 1
            mix.inputs[2].default_value = (*tint, 1)
            links.new(output, mix.inputs[1])
            output = mix.outputs[0]
        links.new(output, shader.inputs['Base Color'])
        links.new(output, shader.inputs['Emission Color'])
        links.new(tex.outputs['Alpha'], shader.inputs['Alpha'])
        is_strip = path.with_suffix('.png.mcmeta').exists() and image.size[1] > image.size[0]
        mat['frame_ratio'] = image.size[0] / image.size[1] if is_strip else 1.0
    shader.inputs['Emission Strength'].default_value = emission
    MATERIALS[key] = mat
    return mat

def empty(name, parent=None, role=None):
    obj = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(obj)
    obj.parent = parent
    if role:
        obj['role'] = role
    return obj

def mesh_faces(name, faces, materials, uvs, parent=None):
    verts = [v for face in faces for v in face]
    indices = [tuple(range(i * 4, i * 4 + 4)) for i in range(len(faces))]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], indices)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    obj.parent = parent
    layer = mesh.uv_layers.new(name='Minecraft pixel UV')
    for i, (mat, coords) in enumerate(zip(materials, uvs)):
        if mat.name not in obj.data.materials:
            obj.data.materials.append(mat)
        obj.data.polygons[i].material_index = list(obj.data.materials).index(mat)
        ratio = mat.get('frame_ratio', 1)
        for loop, uv in zip(obj.data.polygons[i].loop_indices, coords):
            layer.data[loop].uv = (uv[0], 1 - (1 - uv[1]) * ratio)
    return obj

def box(name, loc, size, tex, parent=None, tint=(1, 1, 1), emission=0, rects=None, atlas=(64, 64), solid=False):
    x, y, z = loc
    a, b, c = [s / 2 for s in size]
    faces = {
        'north': [(x-a,y-b,z-c),(x+a,y-b,z-c),(x+a,y-b,z+c),(x-a,y-b,z+c)],
        'south': [(x+a,y+b,z-c),(x-a,y+b,z-c),(x-a,y+b,z+c),(x+a,y+b,z+c)],
        'west': [(x-a,y+b,z-c),(x-a,y-b,z-c),(x-a,y-b,z+c),(x-a,y+b,z+c)],
        'east': [(x+a,y-b,z-c),(x+a,y+b,z-c),(x+a,y+b,z+c),(x+a,y-b,z+c)],
        'up': [(x-a,y-b,z+c),(x+a,y-b,z+c),(x+a,y+b,z+c),(x-a,y+b,z+c)],
        'down': [(x-a,y+b,z-c),(x+a,y+b,z-c),(x+a,y-b,z-c),(x-a,y-b,z-c)],
    }
    mats, coords = [], []
    for face in faces:
        face_tex = tex.get(face, tex.get('all')) if isinstance(tex, dict) else tex
        mats.append(material(face_tex, tint, emission, solid))
        if rects:
            u, v, w, h = rects[face]
            u0, v0, u1, v1 = u / atlas[0], 1-v/atlas[1], (u+w)/atlas[0], 1-(v+h)/atlas[1]
        else:
            horizontal, vertical = {'north':(size[0],size[2]),'south':(size[0],size[2]),'west':(size[1],size[2]),'east':(size[1],size[2]),'up':(size[0],size[1]),'down':(size[0],size[1])}[face]
            u0, v0, u1, v1 = 0, 1, horizontal, 1-vertical
        coords.append([(u0,v1),(u1,v1),(u1,v0),(u0,v0)])
    return mesh_faces(name, list(faces.values()), mats, coords, parent)

def resolve_model(name):
    name = name.replace('minecraft:', '')
    if '/' not in name:
        name = 'block/' + name
    if name.startswith('builtin/'):
        return {}
    path = asset('models', name + '.json')
    data = json.loads(path.read_text())
    SOURCES.add(str(path.relative_to(ROOT)))
    result = resolve_model(data['parent']) if data.get('parent') else {}
    result['textures'] = {**result.get('textures', {}), **data.get('textures', {})}
    if 'elements' in data:
        result['elements'] = data['elements']
    return result

def model(name, parent, scale=1, loc=(0,0,0), yaw=0, tint=(1,1,1), emission=0):
    data = resolve_model(name)
    holder = empty('Minecraft model / ' + name, parent)
    holder.location = loc
    holder.rotation_euler.z = math.radians(yaw)
    holder.scale = (scale,) * 3
    holder['model'] = name
    for n, element in enumerate(data.get('elements', [])):
        x0,y0,z0 = element['from']
        x1,y1,z1 = element['to']
        faces = {
            'north': [(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0)],
            'south': [(x1,y0,z1),(x0,y0,z1),(x0,y1,z1),(x1,y1,z1)],
            'west': [(x0,y0,z1),(x0,y0,z0),(x0,y1,z0),(x0,y1,z1)],
            'east': [(x1,y0,z0),(x1,y0,z1),(x1,y1,z1),(x1,y1,z0)],
            'up': [(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],
            'down': [(x0,y0,z1),(x1,y0,z1),(x1,y0,z0),(x0,y0,z0)]
        }
        outfaces, mats, uvs = [], [], []
        for face, spec in element['faces'].items():
            # Cycles already renders both sides. Coplanar duplicate sprite faces
            # produce black transparency artifacts; retain the canonical front.
            if (z0==z1 and face=='south') or (x0==x1 and face=='east') or (y0==y1 and face=='up'):
                continue
            points = []
            for p in faces[face]:
                p = Vector(p)
                if 'rotation' in element:
                    rot = element['rotation']
                    origin = Vector(rot['origin'])
                    axis = {'x':0,'y':1,'z':2}[rot['axis']]
                    p -= origin
                    angle = math.radians(rot['angle'])
                    if rot.get('rescale'):
                        for k in range(3):
                            if k != axis:
                                p[k] /= math.cos(angle)
                    p = Matrix.Rotation(angle, 3, rot['axis'].upper()) @ p + origin
                points.append(((p.x-8)/16,(p.z-8)/16,p.y/16))
            t = spec['texture']
            while t.startswith('#'):
                t = data['textures'][t[1:]]
            t = t.replace('minecraft:', '')
            mat = material(t, tint, emission)
            uv = spec.get('uv', [0,0,16,16])
            a,b,c,d = [v/16 for v in uv]
            coords = [(a,1-d),(c,1-d),(c,1-b),(a,1-b)]
            if face=='north':
                coords=[(c,1-d),(a,1-d),(a,1-b),(c,1-b)]
            rotation = spec.get('rotation',0)//90
            coords = coords[rotation:] + coords[:rotation]
            outfaces.append(points)
            mats.append(mat)
            uvs.append(coords)
        mesh_faces(name+' element '+str(n),outfaces,mats,uvs,holder)
    return holder

def entity_rects(x, y, w, h, d):
    return {'north':(x+d,y+d,w,h),'south':(x+2*d+w,y+d,w,h),'west':(x,y+d,d,h),'east':(x+d+w,y+d,d,h),'up':(x+d,y,w,d),'down':(x+d+w,y,w,d)}

def light(name, loc, color, energy, size=5, kind='AREA', target=(0,0,0)):
    data=bpy.data.lights.new(name,kind)
    data.energy=energy
    data.color=color
    if kind=='AREA':
        data.shape='DISK'
        data.size=size
    else:
        data.shadow_soft_size=size
    obj=bpy.data.objects.new(name,data)
    bpy.context.collection.objects.link(obj)
    obj.location=loc
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat('-Z','Y').to_euler()
    return obj

def setup(era, camera, target, ortho, background):
    global ERA
    ERA=era
    bpy.ops.wm.read_factory_settings(use_empty=True)
    MATERIALS.clear()
    SOURCES.clear()
    scene=bpy.context.scene
    scene.render.engine='CYCLES'
    scene.cycles.device='CPU'
    scene.cycles.samples=SAMPLES
    scene.cycles.use_denoising=True
    scene.cycles.max_bounces=7
    scene.cycles.transparent_max_bounces=8
    scene.render.threads_mode='FIXED'
    scene.render.threads=6
    scene.render.resolution_x=1024
    scene.render.resolution_y=1024
    scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG'
    scene.render.image_settings.color_mode='RGBA'
    scene.render.film_transparent=False
    scene.world=bpy.data.worlds.new('Quiet isolated world')
    scene.world.use_nodes=True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(*background,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=.32
    scene.view_settings.view_transform='AgX'
    scene.view_settings.look='AgX - Medium High Contrast'
    camdata=bpy.data.cameras.new('Isometric composition')
    cam=bpy.data.objects.new('Isometric composition',camdata)
    bpy.context.collection.objects.link(cam)
    cam.location=camera
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler()
    camdata.type='ORTHO'
    camdata.ortho_scale=ortho
    camdata.lens=50
    scene.camera=cam
    # The compositor is intentionally unnecessary: all colored effects are real scene lights.
    return scene
