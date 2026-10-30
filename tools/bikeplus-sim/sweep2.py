import os, sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from evalv import *
from sweep1 import bandf
B=dict(V_PT6,band=bandf(.5))
V=[('band .5 (stepped)',B)]
for rr in [6,8,10,15]: V.append((f'smooth {rr}/s',dict(B,smooth=True,ramp_rate=rr)))
for n,v in V:
    a=evaluate(v); b=evaluate(v,workout='th')
    print(f"{n:18s} PT reach {a['reach']:4.2f} settle {a['settle']:5.2f} over {a['over']:5.2f} steady {a['steady']:5.1f} chg {a['changes']:5.1f} stop {a['stopped']} | TH settle {b['settle']:5.2f} over {b['over']:5.2f} steady {b['steady']:5.1f}")
