import os
import numpy as np, pandas as pd, json, math
from plant import Plant, A
from ctl import Ctl
HERE=os.path.dirname(os.path.abspath(__file__)); DATA=os.path.join(HERE,'data')
PAR=json.load(open(os.path.join(HERE,'plantpar.json')))
PT=[155,200,250,155,300,155,320,155,345,155]
def fitcad(d):
    r=pd.read_csv(os.path.join(DATA,d,'ride.csv')); return r.cadence.values.astype(float)
def simulate(variant, cad, targets=PT, lap=60, segs=None, seed=1, learned=None, tr_delay=.4, echo=.25, noise=1.5, start_level=43, gain=.003):
    if segs is None: segs=[(w,lap) for w in targets]
    bounds=np.cumsum([0]+[d for _,d in segs]); rng=np.random.default_rng(seed); dt=.05; T=bounds[-1]
    def tgt_at(x):
        i=int(np.searchsorted(bounds,x,side='right')-1); return segs[min(max(i,0),len(segs)-1)][0]
    cadf=lambda t: np.interp(t,np.arange(len(cad)),cad)
    pl=Plant(start_level,cadf(0),dt=dt,**PAR); ctl=Ctl(variant,gain=gain,learned=learned)
    pend=[]; echo_r=start_level; sample=None; out=[]; t=0.; k=0
    def write(lv): pend.append((t+echo,lv)); pl.command(lv)
    rep=pl.step(cadf(0))
    while t<T:
        rep=pl.step(cadf(t))
        while pend and pend[0][0]<=t: echo_r=pend.pop(0)[1]
        if k%4==0: sample=(round(t,2),rep+rng.normal(0,noise),round(cadf(t)),echo_r)
        if k%2==0:
            tw=tgt_at(max(t-tr_delay,0))
            ctl.tick(t,sample,tw,write)
        if k%20==0: out.append((t,rep,pl.pos))
        t+=dt; k+=1
    return np.array(out), ctl
def metrics(out,targets=PT,lap=60,segs=None):
    if segs is None: segs=[(w,lap) for w in targets]
    b=np.cumsum([0]+[d for _,d in segs]).astype(int)
    p=out[:,1]; res=[]
    for i in range(1,len(segs)):
        s=b[i]; T=segs[i][0]; prev=segs[i-1][0]; up=T>prev; L=segs[i][1]
        w=p[s:s+30]; full=p[s:s+L]
        over=(w.max()-T) if up else (T-w.min())
        ok=np.abs(full-T)<=7; settle=next((k for k in range(len(ok)-4) if ok[k:k+5].all()),99)
        reach=next((k for k in range(len(w)) if (w[k]>=T-7 if up else w[k]<=T+7)),99)
        steady=np.mean(np.abs(p[s+15:s+L]-T)<=7)
        res.append((reach,settle,max(over,0),steady))
    r=np.array(res,float); return dict(reach=r[:,0].mean(),settle=r[:,1].mean(),over=r[:,2].mean(),worst=r[:,1].max(),steady=100*r[:,3].mean())
def levelchanges(out,targets=PT,lap=60,segs=None,minw=0):
    if segs is None: segs=[(w,lap) for w in targets]
    b=np.cumsum([0]+[d for _,d in segs]).astype(int); pos=out[:,2]; n=0
    for i in range(len(segs)):
        if segs[i][0]<minw: continue
        seg=np.round(pos[b[i]+15:b[i+1]]); n+=int((np.diff(seg)!=0).sum())
    return n
V_PT3=dict(rate=8,settle=3.,cadavg=False,arrival=True)
V_PT5=dict(rate=1000,settle=3.,cadavg=False,arrival=True)
V_PT6=dict(rate=16,settle=4.5,cadavg=True,arrival=True)
