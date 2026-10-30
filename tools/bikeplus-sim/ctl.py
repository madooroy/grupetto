"""Python port of Grupetto BikeControl ERG (bikeplus-all-v44) with variant switches."""
import math
from plant import seed
class Model:
    def __init__(s,learned=None): s.l=list(learned) if learned else [float('nan')]*101
    def norm(s,p,c): return p/((c/90)**1.7)
    def learn(s,lv,p,c):
        if not 0<=lv<=100 or c<50 or p<=5: return
        o=s.norm(p,c); sd=seed(lv)
        if not sd/1.5<=o<=sd*1.5: return
        s.l[lv]=o if math.isnan(s.l[lv]) else s.l[lv]+.2*(o-s.l[lv])
    def w90(s,lv):
        lv=min(max(lv,0),100)
        if not math.isnan(s.l[lv]): return s.l[lv]
        for d in range(1,6):
            for n in (lv-d,lv+d):
                if 0<=n<=100 and not math.isnan(s.l[n]): return seed(lv)*min(max(s.l[n]/seed(n),1/1.5),1.5)
        return seed(lv)
    def at(s,lv,c): return s.w90(lv)*(c/90)**1.7
    def level_for(s,w,c):
        if c<50 or w<=0: return None
        want=s.norm(w,c); prev=s.w90(0)
        if want<=prev: return 0.
        for lv in range(1,101):
            cur=s.w90(lv)
            if cur>=want and cur>prev: return lv-1+(want-prev)/(cur-prev)
            prev=max(prev,cur)
        return 100.
    def nearest(s,w,c):
        f=s.level_for(w,c)
        if f is None: return None
        b=min(max(int(math.floor(f)),0),100); a=min(b+1,100)
        return b if abs(s.at(b,c)-w)<=abs(s.at(a,c)-w) else a

class Ctl:
    def __init__(s,v,gain=.003,learned=None):
        s.v=v; s.gain=gain; s.m=Model(learned)
        s.req=None; s.fp=None; s.expected=None; s.cmd_t=0; s.ack=1.5; s.prev_tick=0; s.last_sample=None
        s.fft=None; s.ffu=0; s.ffl=0; s.ffpend=False; s.ffdead=0; s.sres=-1; s.ssince=0
        s.cav=None; s.cavt=0; s.hist=[]; s.stopped=None; s.writes=[]; s.ramp=None
    def ffactive(s,now): return now<s.ffu or (s.ffpend and now<s.ffdead)
    def tick(s,now,sample,target_w,write):
        if sample is None: return
        ts,p,c,r=sample
        ramp_tick = s.v.get('smooth') and s.ramp is not None
        if ts==s.last_sample and not ramp_tick: return
        new = ts!=s.last_sample; s.last_sample=ts
        if new:
            if r!=s.sres: s.sres=r; s.ssince=ts
            elif ts-s.ssince>=2.5: s.m.learn(r,p,c)
            prev=s.cav; sec=min(max(ts-s.cavt,0),1); s.cavt=ts
            s.cav=None if c<50 else (c if prev is None else prev+sec/(5+sec)*(c-prev))
            steady_now = s.fft is not None and abs(p-s.fft)<=7 and ts-getattr(s,'fft_t',-99)>=8
            if c>=50 and steady_now: s.hist.append((ts,c,p)); s.hist=[h for h in s.hist if ts-h[0]<=600]
        if s.stopped: return
        if s.v.get('smooth') and s.ramp is not None:
            return s.smooth_tick(now,sample,target_w,write,new)
        dt=min(max(now-s.prev_tick,0),.4); s.prev_tick=now
        if s.req is None: s.req=float(r)
        if s.expected is not None and r!=s.expected:
            if now-s.cmd_t>s.ack: s.stopped='ack'
            return
        if c<25: s.fp=None; s.req=float(r); return
        v=s.v
        if s.fft!=target_w:
            s.lastw=s.fft if s.fft is not None else target_w; s.fft=target_w; s.fft_t=ts; cadsel = s.cadref(target_w,c,ts)
            lv=s.m.nearest(target_w,cadsel) if abs(target_w-p)>=15 else None
            if lv is not None:
                lv=min(lv,75); s.ffl=float(lv); s.ffu=now+v['settle']; s.ffpend=v.get('arrival',True); s.ffdead=now+10; s.fp=None
                if v.get('smooth'): s.ramp=float(r); s.ramp_t=now-.1
            else: s.ffu=0; s.ffpend=False
        if s.ffpend and r==round(s.ffl): s.ffpend=False; s.ffu=max(s.ffu,now+v['settle'])
        if s.ffactive(now): tgt=s.ffl
        else:
            s.fp=p if s.fp is None else s.fp+dt/(2+dt)*(p-s.fp)
            err=target_w-s.fp
            band=v.get('band',lambda lv,c,m:3.)(round(s.req),c,s.m)
            persist=v.get('persist')
            act=abs(err)>band
            if act and persist:
                sgn=1 if err>0 else -1
                if getattr(s,'psign',0)!=sgn: s.psign=sgn; s.psince=now
                act = now-s.psince>=persist or abs(err)>=v.get('immediate',99)
            else: s.psign=0
            tgt=s.req+(s.gain*err*dt/.1 if act else 0)
        jump=s.ffactive(now)
        if v.get('smooth') and s.ramp is not None: return s.smooth_tick(now,sample,target_w,write,new)
        rate=v['rate'] if jump else 3.
        s.req=min(max(tgt,s.req-rate*dt),s.req+rate*dt); s.req=min(max(s.req,0),100)
        nx=round(s.req)
        if nx!=r:
            write(nx); s.writes.append((now,nx)); s.ack=1.5+(abs(nx-r)*.1 if jump else 0); s.expected=nx; s.cmd_t=now
    def smooth_tick(s,now,sample,target_w,write,new):
        # one level per 100 ms tick toward the modeled level; tolerate the echo trailing by up to 3 levels
        ts,p,c,r=sample; goal=round(s.ffl)
        if s.expected is not None and abs(r-s.expected)>3 and now-s.cmd_t>1.5: s.stopped='ack'; return
        if round(s.ramp)==goal:
            s.ramp=None; s.req=float(goal); s.prev_tick=now
            if r==goal: s.ffpend=False; s.ffu=max(s.ffu,now+s.v['settle'])
            return
        step=s.v.get('ramp_rate',10)*max(min(now-getattr(s,'ramp_t',now-.1),.2),0); s.ramp_t=now
        s.ramp = min(s.ramp+step,goal) if goal>s.ramp else max(s.ramp-step,goal)
        nx=int(round(s.ramp))
        if nx!=s.expected and nx!=r: write(nx); s.writes.append((now,nx)); s.expected=nx; s.cmd_t=now
        s.req=float(nx)

    def cadref(s,target_w,c,ts):
        import numpy as np
        mode=s.v.get('cadref','avg5') if s.v.get('cadavg',True) else 'inst'
        avg5=s.cav or c
        if mode=='inst': return c
        if mode=='avg5': return avg5
        recent=[h for h in s.hist if ts-h[0]<=120]
        med120=float(np.median([h[1] for h in recent])) if len(recent)>=50 else avg5
        if mode=='med120': return med120
        near=[h[1] for h in s.hist if abs(h[2]-target_w)<=0.1*target_w]
        mem=float(np.median(near)) if len(near)>=15 else None
        if mode=='mem_med': return mem if mem is not None else med120
        if mode=='mem_avg5': return mem if mem is not None else avg5
        if mode=='down_med': return med120 if target_w<s.lastw else avg5
        if mode=='down_mem':
            if target_w>=s.lastw: return avg5
            return mem if mem is not None else med120
        return avg5
