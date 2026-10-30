"""Read Bike+ telemetry: data/<ride>/bike.log (S = reading, W = Grupetto write) or a raw logcat."""
import re, numpy as np
def _t(m): return int(m.group(1))*3600+int(m.group(2))*60+float(m.group(3))
def load(path):
    S=[];C=[]
    for l in open(path, errors='replace'):
        m=re.match(r'\S+ (\d\d):(\d\d):(\d\d\.\d+)',l)
        if not m: continue
        t=_t(m)
        a=re.search(r'(?:BikeStatsReading\(| S )resistance=([\d.]+).*?cadence=([\d.]+),?.*?power=([\d.]+)',l)
        if a: S.append((t,float(a.group(1)),float(a.group(2)),float(a.group(3)))); continue
        b=re.search(r'(?: D d\s+: setResistance: | W )(\d+)\s*$',l)
        if b: C.append((t,int(b.group(1))))
    return np.array(S),C
