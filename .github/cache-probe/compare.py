import collections, pathlib, sys, xml.etree.ElementTree as E

def read(path, recording_only=False):
    items = collections.Counter()
    for p in pathlib.Path(path).glob('TEST-*.xml'):
        root = E.parse(p).getroot()
        for t in root.iter('testcase'):
            cls = t.get('classname', '')
            if recording_only and not cls.startswith('com.knot.backend.recording.'):
                continue
            status = next((x for x in ('failure', 'error', 'skipped') if t.find(x) is not None), 'passed')
            items[(cls, t.get('name', ''), status)] += 1
    return items

cached = read(sys.argv[1])
fresh = read(sys.argv[2])
full = read(sys.argv[3], True)
print('recording cached/fresh/full:', sum(cached.values()), sum(fresh.values()), sum(full.values()))
print('cached versus fresh:', sum((cached-fresh).values()), sum((fresh-cached).values()))
print('fresh versus full recording:', sum((fresh-full).values()), sum((full-fresh).values()))
if not cached or cached != fresh or fresh != full:
    raise SystemExit('comparison failed or incomplete XML')
