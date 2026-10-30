import numpy as np, math
def seed(r): return math.exp(1.491016+.108267*r-.000584*r*r)
def A(r):  # this bike, fitted from steady readings (about 3% above seed above level 54)
    return SCALE[0]*seed(r)*(1+0.035*min(max((r-46)/9,0),1))
SCALE=[1.0]
class Plant:
    def __init__(s,pos,cad,V=12,td=.3,tau=.8,k=0.0,tf=.3,ts=1.5,dt=.05):
        s.pos=float(pos); s.tgt=pos; s.V=V; s.td=td; s.tau=tau; s.k=k; s.tf=tf; s.ts=ts; s.dt=dt
        p=A(pos)*(cad/90)**1.7; s.buf=[p]*max(1,int(round(td/dt))+1); s.lp=p; s.f=p; s.sl=p; s.travelled=0
    def command(s,lv): s.tgt=lv
    def step(s,cad):
        d=s.tgt-s.pos; mv=max(-s.V*s.dt,min(s.V*s.dt,d)); s.pos+=mv; s.travelled+=abs(mv)
        pss=A(s.pos)*(cad/90)**1.7
        s.buf.append(pss); x=s.buf.pop(0)
        a=1-math.exp(-s.dt/s.tau); s.lp+=a*(x-s.lp)
        s.f+=(1-math.exp(-s.dt/s.tf))*(x-s.f); s.sl+=(1-math.exp(-s.dt/s.ts))*(x-s.sl)
        return s.lp+s.k*(s.f-s.sl)
