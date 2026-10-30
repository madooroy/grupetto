import os, sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, plant
from run import *
from rider import cadence
from sweep1 import bandf
PT7=[(165,60),(210,30),(270,30),(170,60),(295,150),(175,60),(360,30),(180,30),(360,30),(165,30),(360,30),(165,60)]
PTOLD=[(w,60) for w in PT]
TH=[(155,60),(300,180),(155,120),(320,180),(155,120),(345,180),(155,60)]
F=dict(V_PT6,band=bandf(.5),smooth=True,ramp_rate=10)
def ev(v,segs,prof,seeds=3,scales=(.97,1.0,1.03)):
    rows=[]
    for sc in scales:
        plant.SCALE[0]=sc
        for sd in range(seeds):
            cad=cadence(segs,prof,seed=sd)
            out,c=simulate(v,cad,segs=segs,seed=sd); m=metrics(out,segs=segs); m['chg']=levelchanges(out,segs=segs); rows.append(m)
    plant.SCALE[0]=1.0
    return {k:round(float(np.mean([r[k] for r in rows])),1) for k in ('reach','settle','over','worst','steady','chg')}
if __name__=='__main__':
    modes=['avg5','med120','mem_avg5','mem_med','down_med']
    for wname,segs in [('PT7',PT7),('PTold',PTOLD),('TH',TH)]:
        for prof in ['spinner','steady','grinder','mixed']:
            for mo in modes:
                r=ev(dict(F,cadref=mo),segs,prof)
                print(f"{wname:5s} {prof:8s} {mo:9s} reach {r['reach']:4.1f} settle {r['settle']:5.1f} over {r['over']:5.1f} worst {r['worst']:5.1f} steady {r['steady']:5.1f} chg {r['chg']:5.1f}",flush=True)
            print()
