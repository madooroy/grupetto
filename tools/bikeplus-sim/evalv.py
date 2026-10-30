import os, sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, plant, pandas as pd
from run import *
sm=lambda a: pd.Series(a).rolling(5,center=True,min_periods=1).mean().values
PT_CADS={d:sm(fitcad(d)) for d in ['r8','r9','r10','r11']}
TH=[(155,60),(300,180),(155,120),(320,180),(155,120),(345,180),(155,60)]
TH_CADS={'r3':sm(pd.read_csv(os.path.join(DATA,'r3','ride.csv')).cadence.values[:900].astype(float)),'r4':sm(pd.read_csv(os.path.join(DATA,'r4','ride.csv')).cadence.values[:900].astype(float))}
def evaluate(v,n_seeds=2,scales=(.97,1.0,1.03),learned=None,workout='pt'):
    rows=[]
    segs=None if workout=='pt' else TH; cads=PT_CADS if workout=='pt' else TH_CADS
    for d,cad in cads.items():
        for sc in scales:
            plant.SCALE[0]=sc
            for sd in range(n_seeds):
                lrn=learned(sc) if callable(learned) else learned
                out,c=simulate(v,cad,seed=sd,learned=lrn,segs=segs); m=metrics(out,segs=segs); m['changes']=levelchanges(out,segs=segs); m['hi_changes']=levelchanges(out,segs=segs,minw=290); m['stopped']=bool(c.stopped); rows.append(m)
    plant.SCALE[0]=1.0
    return {k:round(float(np.mean([r[k] for r in rows])),2) for k in rows[0]}
