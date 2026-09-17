"""Serialize finite headless Blender CLI jobs under the existing weight-20 CPU cap."""
import fcntl
import json
import os
import subprocess
import sys
import time
from pathlib import Path
ROOT = Path(__file__).resolve().parent
CG = Path('/sys/fs/cgroup/user.slice/user-1000.slice/user@1000.service/app.slice/render-blender.service')


def enter_cgroup():
    (CG/'cgroup.procs').write_text(str(os.getpid()))
    os.nice(15)


def run(filename):
    assert (CG/'cpu.weight').read_text().strip() == '20'
    quota,period = (CG/'cpu.max').read_text().split()
    assert quota != 'max' and int(quota)/int(period) <= 3
    cpus = sorted(os.sched_getaffinity(0))[::2][:2]
    env = {key:value for key,value in os.environ.items() if key not in ('DISPLAY','WAYLAND_DISPLAY','PULSE_SERVER')}
    env.update(CUDA_VISIBLE_DEVICES='',HIP_VISIBLE_DEVICES='',ROCR_VISIBLE_DEVICES='',OMP_NUM_THREADS='2',OPENBLAS_NUM_THREADS='2',SDL_AUDIODRIVER='dummy',PYTHONDONTWRITEBYTECODE='1')
    command = ['taskset','-c',','.join(map(str,cpus)),'nix','shell','nixpkgs#blender','--command','blender','--background','-noaudio','--threads','2','--python-exit-code','1','--python',str(ROOT/filename)]
    with open('/tmp/minecraft-icon-blender.lock','w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        started = time.monotonic()
        with (ROOT/'evidence'/(Path(filename).stem+'.log')).open('w') as logfile:
            result = subprocess.run(command,env=env,cwd=ROOT,stdout=logfile,stderr=subprocess.STDOUT,preexec_fn=enter_cgroup)
        report = {'script':filename,'command':command,'exit_code':result.returncode,'wall_seconds':time.monotonic()-started,'affinity_cpus':cpus,'render_threads':2,'cpu_weight':20,'cpu_max':(CG/'cpu.max').read_text().strip(),'serialization_lock':'/tmp/minecraft-icon-blender.lock','display':None,'audio':None,'gpu':None}
        (ROOT/'evidence'/(Path(filename).stem+'-run.json')).write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report),flush=True)
        if result.returncode:
            print((ROOT/'evidence'/(Path(filename).stem+'.log')).read_text(),flush=True)
            raise SystemExit(result.returncode)


if __name__ == '__main__':
    for filename in sys.argv[1:]:
        run(filename)
