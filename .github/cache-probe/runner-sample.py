#!/usr/bin/env python3
import csv,datetime,os,signal,time
from pathlib import Path
running=True

def stop(*_):
    global running
    running=False
signal.signal(signal.SIGTERM,stop)
signal.signal(signal.SIGINT,stop)
def cpu():
    values=list(map(int,Path('/proc/stat').read_text().splitlines()[0].split()[1:]))
    return sum(values),values[3],values[4]
def mem():
    values={}
    for line in Path('/proc/meminfo').read_text().splitlines():
        key,_,val=line.partition(':')
        if key in ('MemTotal','MemAvailable','SwapTotal','SwapFree'):
            values[key]=int(val.strip().split()[0])
    return values
with open(os.environ['OBSERVE_METRICS'],'w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=['utc','cpu_total_pct','cpu_iowait_pct','mem_total_kib','mem_available_kib','swap_total_kib','swap_free_kib','load1','cgroup_memory_bytes'])
    w.writeheader();previous=cpu()
    while running:
        time.sleep(2)
        now=cpu(); delta=max(1,now[0]-previous[0]); m=mem()
        c=Path('/sys/fs/cgroup/memory.current')
        try: cg=int(c.read_text().strip()) if c.exists() else ''
        except (OSError,ValueError):cg=''
        w.writerow({'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'cpu_total_pct':round(100*(1-(now[1]-previous[1])/delta),2),'cpu_iowait_pct':round(100*(now[2]-previous[2])/delta,2),'mem_total_kib':m.get('MemTotal',''),'mem_available_kib':m.get('MemAvailable',''),'swap_total_kib':m.get('SwapTotal',''),'swap_free_kib':m.get('SwapFree',''),'load1':os.getloadavg()[0],'cgroup_memory_bytes':cg});f.flush();previous=now
