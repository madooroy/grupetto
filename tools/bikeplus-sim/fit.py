import os
from run import DATA
import numpy as np, itertools
from extract import load
from plant import Plant
def jumps(S,C,minlev=8):
    # group commands into episodes: first command after >2 s quiet, with total change >= minlev
    eps=[];i=0
    while i<len(C):
        j=i
        while j+1<len(C) and C[j+1][0]-C[j][0]<2.5: j+=1
        seq=C[i:j+1]
        before=S[S[:,0]<seq[0][0]]
        after=S[(S[:,0]>seq[0][0])&(S[:,0]<seq[0][0]+12)]
        full=[c for c in C if seq[0][0]<=c[0]<=seq[0][0]+12]
        if len(before) and len(after)>=10 and after[:,2].min()>50 and abs(seq[-1][1]-before[-1,1])>=minlev: eps.append((full,before[-1,1]))
        i=j+1
    return eps
def sim_ep(S,seq,start,par,dt=.05,T=12):
    t0=seq[0][0]; w=S[(S[:,0]>=t0-1)&(S[:,0]<=t0+T)]
    cad=lambda t: np.interp(t,S[:,0],S[:,2])
    p=Plant(start,cad(t0-1),dt=dt,**par); out=[];k=0;t=t0-1;ci=0
    times=list(w[:,0]); res=[]
    while t<=t0+T:
        while ci<len(seq) and seq[ci][0]<=t: p.command(seq[ci][1]); ci+=1
        v=p.step(cad(t)); 
        while times and times[0]<=t: res.append((times.pop(0),v))
        t+=dt
    sim=np.array([r[1] for r in res]); real=w[:len(sim),3]
    return real,sim
data=[]
for d in ['r8','r10','r11']:
    S,C=load(os.path.join(DATA,d,'bike.log')); S=S[S[:,2]>0]
    for seq,start in jumps(S,C): data.append((d,S,seq,start))
print(len(data),'episodes')
best=[]
for V,td,tau,k,ts in itertools.product([10,15,20,30,60],[.6,.9,1.2,1.5],[.3,.5,.8],[0,.3,.6,1.0,1.5,2.5],[1.5,3,6]):
    par=dict(V=V,td=td,tau=tau,k=k,ts=ts); e=[]
    for d,S,seq,start in data:
        r,s=sim_ep(S,seq,start,par); e.append(np.mean((r-s)**2))
    best.append((np.mean(e),par))
best.sort(key=lambda x:x[0])
for b in best[:8]: print(round(b[0],1),b[1])
import json; json.dump(best[0][1],open('plantpar.json','w'))
# per-variant residual for best
par=best[0][1]
for d in ['r8','r10','r11']:
    e=[np.sqrt(np.mean((lambda r,s:(r-s)**2)(*sim_ep(S,seq,start,par)))) for dd,S,seq,start in data if dd==d]
    print(d,'rms W',np.round(e,1))
