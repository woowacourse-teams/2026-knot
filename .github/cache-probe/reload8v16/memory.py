import csv, glob, os, time
path=os.environ['MEMORY_CSV']
with open(path,'w',newline='',buffering=1) as stream:
    out=csv.writer(stream)
    out.writerow(['unix_seconds','test_worker_count','test_workers_rss_kib','test_worker_max_rss_kib','runner_mem_available_kib'])
    while True:
        rss=[]
        for p in glob.glob('/proc/[0-9]*'):
            try:
                cmd=open(p+'/cmdline','rb').read().split(b'\0')
                if not any(x in (b'worker.org.gradle.process.internal.worker.GradleWorkerMain', b'org.gradle.process.internal.worker.GradleWorkerMain') for x in cmd) or not any(x.startswith(b'Gradle Test Executor') for x in cmd):
                    continue
                status=open(p+'/status').read()
                line=next((s for s in status.splitlines() if s.startswith('VmRSS:')),None)
                if line:rss.append(int(line.split()[1]))
            except (OSError,ProcessLookupError):
                continue
        try:
            mem=next(int(x.split()[1]) for x in open('/proc/meminfo') if x.startswith('MemAvailable:'))
        except (OSError,StopIteration): mem=0
        out.writerow([round(time.time(),2),len(rss),sum(rss),max(rss,default=0),mem])
        time.sleep(1)
