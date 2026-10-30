import os, sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from evalv import *
from sweep1 import bandf
import plant
F=dict(V_PT6,band=bandf(.5),smooth=True,ramp_rate=10)
cache={}
def learned_for(sc_src):
    def f(sc):
        key=round(sc_src(sc),3)
        if key not in cache:
            plant.SCALE[0]=key; out,c=simulate(F,TH_CADS['r4'],seed=9,segs=TH); cache[key]=list(c.m.l); plant.SCALE[0]=sc
        return cache[key]
    return f
for n,l in [('fresh table',None),('saved, same bike',learned_for(lambda sc:sc)),('saved, bike shifted 6%',learned_for(lambda sc:2.0-sc if sc!=1 else 1.06))]:
    a=evaluate(F,learned=l); 
    # first 3 jumps only: rerun metrics restricted
    print(f"{n:24s} PT reach {a['reach']:4.2f} settle {a['settle']:5.2f} over {a['over']:5.2f} worst {a['worst']:5.1f} steady {a['steady']:5.1f} chg {a['changes']:5.1f}")
