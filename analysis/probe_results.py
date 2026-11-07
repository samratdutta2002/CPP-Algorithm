"""Analyses of the AnalysisProbe outputs.  Usage: python analysis/probe_results.py [structure|fixes|orders_alpha|grid|boundary|all]
Expects the CSVs of results/ (see results/README.md)."""
import sys
import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from common import RES, load_both
A, H = load_both()

def structure():
    """Tree check, region counts, degrees, angles, boundary-pass path effect (structure_base.csv)"""
    pd.set_option('display.width',200)
    U=RES+'/'
    S={n:pd.read_csv(U+f'structure_{n}.csv') for n in ['base','fixAB','fixABT']}
    b=S['base']
    print(b.shape, b.ok.value_counts().to_dict(), b.rotation.value_counts().to_dict())
    bo=b[b.ok]
    print('tree check: edges==k-1 :',(bo.graph_edges==bo.k-1).mean()*100,'% ; connected:',bo.connected.mean()*100,'% ; visited==k:',(bo.visited==bo.k).mean()*100,'%')
    for n,d in S.items():
        d=d[d.ok]; print(n,'tree ok',((d.graph_edges==d.k-1)&d.connected&(d.visited==d.k)).mean()*100, 'split share',d.split.mean()*100)
    ns=bo[~bo.split]
    on=ns[ns.rotation==True]; off=ns[ns.rotation==False]
    print('non-split rows',len(ns),'on',len(on),'off',len(off))
    q=lambda s:(s.median(),s.mean(),s.quantile(.95),s.max())
    print('k on  (median,mean,p95,max):',q(on.k)); print('k off :',q(off.k))
    print('max degree on:',q(on.max_degree)); print('offset vertices n:',q(on.offset_vertices))
    print('k share ==1 on %.1f%% off %.1f%%'%((on.k==1).mean()*100,(off.k==1).mean()*100))
    # paired k off vs on
    m=off.merge(on,on=['polygon','width'],suffixes=('_off','_on'))
    print('pairs',len(m)); d=m.k_off-m.k_on
    print('regions removed by rotation: mean %.2f median %.1f ; share with k_on<k_off %.1f%%, equal %.1f%%, greater %.1f%%'%(d.mean(),d.median(),(d>0).mean()*100,(d==0).mean()*100,(d<0).mean()*100))
    print('angle: share theta*=0: %.1f%%'%((m.angle_deg_on.abs()<1e-9).mean()*100))
    nz=m[m.angle_deg_on.abs()>1e-9]
    print('nonzero angle quantiles',nz.angle_deg_on.quantile([0,.1,.25,.5,.75,.9,1]).round(2).to_dict())
    print('hist of angles (30deg bins):',np.histogram(nz.angle_deg_on,bins=[0,30,60,90,120,150,180])[0])
    # k relation: max degree vs k
    print('Delta/k:',(on.max_degree/on.k).describe()[['mean','50%','max']].round(3).to_dict())
    print('Delta q:',on.max_degree.quantile([.5,.9,.99]).to_dict(), ' Delta>=4 share',(on.max_degree>=4).mean())
    # k vs 2n-5 bound and generic formula parity
    print('k always odd? ',((on.k%2)==1).mean())
    print('k <= 2n-5:',(on.k<=2*on.offset_vertices-5).mean())
    # boundary pass effect
    mm=on
    print('boundary pass: path_bs mean %.1f path_nobs mean %.1f ; share rows with pass added %.1f%% ; mean added %.1f%% (all) '%(mm.path_bs.mean(),mm.path_nobs.mean(),(mm.path_bs>mm.path_nobs+1e-6).mean()*100,((mm.path_bs-mm.path_nobs)/mm.path_nobs*100).mean()))
    add=(mm.path_bs-mm.path_nobs)/mm.path_nobs*100
    print('added % quantiles',add.quantile([.5,.75,.9,.99]).round(2).to_dict())


def fixes():
    """Effect of the three optional implementation fixes (structure_*.csv)"""
    pd.set_option('display.width',200)
    U=RES+'/'
    S={n:pd.read_csv(U+f'structure_{n}.csv') for n in ['base','fixAB','fixABT']}
    key=['polygon','width','rotation']
    b=S['base'].set_index(key)
    for n in ['fixAB','fixABT']:
        d=S[n].set_index(key)
        j=b.join(d,lsuffix='_b',rsuffix='_f',how='inner'); j=j[~j.split_b]
        for rot in [True,False]:
            x=j.xs(rot,level='rotation')
            for col in ['path_bs','path_nobs']:
                r=x[col+'_f']/x[col+'_b']
                same=(r.sub(1).abs()<1e-9).mean()*100
                print(f'{n} rot={rot} {col}: mean base {x[col+"_b"].mean():8.1f} fixed {x[col+"_f"].mean():8.1f} ({(x[col+"_f"].mean()/x[col+"_b"].mean()-1)*100:+.2f}%) | median ratio {r.median():.4f} | unchanged {same:.1f}% | better(>0.5%) {(r<0.995).mean()*100:.1f}% worse(>0.5%) {(r>1.005).mean()*100:.1f}%')
        # does k change?
        print(n,'k changed share',(j.k_b!=j.k_f).mean()*100,' angle changed',((j.angle_deg_b-j.angle_deg_f).abs()>1e-6).mean()*100)


def orders_alpha():
    """Greedy versus optimal visit order, and turn-penalty sensitivity (orders_*.csv, alpha_base.csv)"""
    pd.set_option('display.width',200)
    U=RES+'/'
    for n in ['base','fixABT']:
        o=pd.read_csv(U+f'orders_{n}.csv')
        o['gap']=(o.greedy_path-o.best_path)/o.best_path*100
        o['gap_mean']=(o.mean_path-o.best_path)/o.best_path*100
        print('=====',n,len(o),'instances; k median',o.k.median(),'max',o.k.max(),'; orders median',o.n_orders.median(),'max',o.n_orders.max())
        print(' greedy optimal (gap<0.01pct): %.1f%%'%((o.gap<0.01).mean()*100))
        print(' gap %% mean %.2f median %.2f p90 %.2f max %.2f'%(o.gap.mean(),o.gap.median(),o.gap.quantile(.9),o.gap.max()))
        print(' random-order gap (mean path vs best) mean %.2f median %.2f ; worst-vs-best mean %.2f'%(o.gap_mean.mean(),o.gap_mean.median(),((o.worst_path-o.best_path)/o.best_path*100).mean()))
        print(' greedy rank: median %d, rank<=2: %.1f%%, top 10%% of orders: %.1f%%'%(o.greedy_rank.median(),(o.greedy_rank<=2).mean()*100,(o.greedy_rank/o.n_orders<=0.1).mean()*100))
        print(' turns: greedy mean %.1f best-path-order mean %.1f'%(o.greedy_turns.mean(),o.best_turns.mean()))
        for lo,hi in [(2,3),(4,5),(6,9)]:
            s=o[(o.k>=lo)&(o.k<=hi)]
            if len(s): print(f'  k {lo}-{hi}: n={len(s)} optimal {((s.gap<0.01).mean()*100):.1f}% mean gap {s.gap.mean():.2f}% max {s.gap.max():.2f}%')
    b=pd.read_csv(U+'orders_base.csv'); f=pd.read_csv(U+'orders_fixABT.csv')
    m=b.merge(f,on=['polygon','width'],suffixes=('_b','_f'))
    print('base vs fixABT on common instances',len(m),': greedy path mean base %.1f fixed %.1f ; best same? %.1f%%'%(m.greedy_path_b.mean(),m.greedy_path_f.mean(),((m.best_path_b-m.best_path_f).abs()<1e-6).mean()*100))
    print('fixed gap mean %.2f vs base %.2f'%(((m.greedy_path_f-m.best_path_f)/m.best_path_f).mean()*100,((m.greedy_path_b-m.best_path_b)/m.best_path_b).mean()*100))
    # alpha
    a=pd.read_csv(U+'alpha_base.csv'); print('===== alpha',a.shape, a.ok.mean())
    a=a[a.ok]
    p=a.pivot_table(index=['polygon','width'],columns='alpha',values='path_nobs')
    ref=p[2.0]
    print('mean path_nobs by alpha:',p.mean().round(1).to_dict())
    for al in p.columns:
        r=p[al]/ref
        print(f' alpha={al:6.1f}: path ratio vs alpha=2: mean {r.mean():.4f}  changed(>0.01%) {(r.sub(1).abs()>1e-4).mean()*100:.1f}%  better {(r<0.999).mean()*100:.1f}% worse {(r>1.001).mean()*100:.1f}%  max|dev| {(r.sub(1).abs().max()*100):.2f}%')
    od=a.pivot_table(index=['polygon','width'],columns='alpha',values='order',aggfunc='first')
    print('order differs from alpha=2:',{al:round((od[al]!=od[2.0]).mean()*100,1) for al in od.columns})
    multi=od[2.0].str.contains(r'\|')
    print('instances with >=2 regions: {:.1f} pct'.format(multi.mean()*100))
    for al in [0.0,10.0,50.0,200.0]:
        print('  alpha',al,'order changes among multi-region instances: {:.1f} pct'.format((od.loc[multi,al]!=od.loc[multi,2.0]).mean()*100))


def grid():
    """Region count versus path over a rotation grid; regret of the chosen angle (grid_base_s5.csv)"""
    pd.set_option('display.width',200)
    U=RES+'/'
    g=pd.read_csv(U+'grid_base_s5.csv'); st=pd.read_csv(U+'structure_base.csv')
    sp=st[st.rotation==True][['polygon','width','split']]
    g=g.merge(sp,on=['polygon','width'],how='left'); g=g[(g.ok)&(~g.split.fillna(False).astype(bool))]
    grid=g[g.tag=='grid']; opt=g[g.tag=='opt'].set_index(['polygon','width'])
    print('instances',opt.shape[0],'grid rows',len(grid),'angles per instance',grid.groupby(['polygon','width']).size().median())
    rows=[]
    for (p,w),d in grid.groupby(['polygon','width']):
        if (p,w) not in opt.index: continue
        o=opt.loc[(p,w)]
        a0=d[d.angle_deg==0].iloc[0]
        kmin=d.k.min(); dk=d[d.k==kmin]
        rho_k=rho_t=np.nan
        if d.k.nunique()>1 and d.path_nobs.nunique()>1: rho_k=spearmanr(d.k,d.path_nobs)[0]
        if d.turns.nunique()>1 and d.path_nobs.nunique()>1: rho_t=spearmanr(d.turns,d.path_nobs)[0]
        rows.append(dict(p=p,w=w,kmax=d.k.max(),kmin=kmin,k0=a0.k,k_opt=o.k,
            opt_path=o.path_nobs,best_path=d.path_nobs.min(),med_path=d.path_nobs.median(),p0=a0.path_nobs,
            kmin_path_best=dk.path_nobs.min(),kmin_path_mean=dk.path_nobs.mean(),kmin_turns_best=dk.loc[dk.turns.idxmin()].path_nobs,
            opt_turns=o.turns,best_turns=d.turns.min(),t0=a0.turns,rho_k=rho_k,rho_t=rho_t,opt_bs=o.path_bs,best_bs=d.path_bs.min(),p0_bs=a0.path_bs,
            cov_opt=o.coverage,cov0=a0.coverage))
    r=pd.DataFrame(rows); print('n',len(r))
    print('opt k vs grid min k: opt k == min k on grid %.1f%% ; opt k < grid min %.1f%% ; opt k > min %.1f%%'%((r.k_opt==r.kmin).mean()*100,(r.k_opt<r.kmin).mean()*100,(r.k_opt>r.kmin).mean()*100))
    reg=r.opt_path/r.best_path-1
    print('opt path regret vs best grid angle (no pass): mean %.2f%% median %.2f%% p90 %.2f%% ; opt is within 1%% of best: %.1f%%; opt beats grid best: %.1f%%'%(reg.mean()*100,reg.median()*100,reg.quantile(.9)*100,(reg<0.01).mean()*100,(reg<-1e-9).mean()*100))
    print('opt vs theta=0: mean path ratio %.4f ; opt better %.1f%% worse %.1f%%'%((r.opt_path/r.p0).mean(),(r.opt_path<r.p0*0.999).mean()*100,(r.opt_path>r.p0*1.001).mean()*100))
    print('opt vs median grid angle: mean ratio %.4f'%((r.opt_path/r.med_path).mean()))
    print('best-grid vs theta0: mean %.4f'%((r.best_path/r.p0).mean()))
    print('min-k selection (best among min-k angles) vs opt: mean ratio %.4f ; avg of min-k angles vs opt %.4f ; min-k-then-min-turns vs opt %.4f'%((r.kmin_path_best/r.opt_path).mean(),(r.kmin_path_mean/r.opt_path).mean(),(r.kmin_turns_best/r.opt_path).mean()))
    v=r.dropna(subset=['rho_k'])
    print('within-instance Spearman(k, path): instances with variation %d (%.1f%%); mean %.3f median %.3f ; share rho>0 %.1f%% ; share rho>0.3 %.1f%%'%(len(v),len(v)/len(r)*100,v.rho_k.mean(),v.rho_k.median(),(v.rho_k>0).mean()*100,(v.rho_k>0.3).mean()*100))
    vt=r.dropna(subset=['rho_t'])
    print('within-instance Spearman(turns, path): n %d mean %.3f median %.3f ; share>0.5 %.1f%%'%(len(vt),vt.rho_t.mean(),vt.rho_t.median(),(vt.rho_t>0.5).mean()*100))
    print('turn counts: opt mean %.1f, best-grid %.1f, theta0 %.1f ; opt vs t0 turn reduction %.1f%%'%(r.opt_turns.mean(),r.best_turns.mean(),r.t0.mean(),(1-r.opt_turns.mean()/r.t0.mean())*100))
    # k range across angles: how much does k vary within a field?
    print('k across 36 angles: median range (max-min) %.0f ; share instances with k constant across angles %.1f%%'%((r.kmax-r.kmin).median(),(r.kmax==r.kmin).mean()*100))
    # with the boundary pass (as in the reported experiments)
    rb=r.opt_bs/r.best_bs-1; print('with boundary pass: regret mean %.2f%% median %.2f%%; opt vs theta0 mean ratio %.4f'%(rb.mean()*100,rb.median()*100,(r.opt_bs/r.p0_bs).mean()))
    # by width
    r['wb']=r.w
    print(r.groupby('w').apply(lambda x: pd.Series(dict(regret=(x.opt_path/x.best_path-1).mean()*100,vs0=(1-(x.opt_path/x.p0).mean())*100,rho=x.rho_k.mean(),kconst=(x.kmax==x.kmin).mean()*100))).round(2))


def boundary():
    """Boundary pass on/off against Fields2Cover (structure_full.csv)"""
    pd.set_option('display.width',200)

    U=RES+'/'
    f=pd.read_csv(U+'structure_full.csv'); print(f.shape, f.ok.mean())
    f=f[f.ok&(~f.split)]
    on=f[f.rotation==True].set_index(['polygon','width']); off=f[f.rotation==False].set_index(['polygon','width'])
    for nm,d in [('rotation ON',on),('rotation OFF',off)]:
        print('==',nm,len(d))
        for m in ['cov','red','exc','out','turns','turndeg']:
            a,b=d[m+'_bs'],d[m+'_nobs']
            print(f'  {m:7s} with pass mean {a.mean():10.3f} median {a.median():10.3f} | no pass mean {b.mean():10.3f} median {b.median():10.3f}')
        drop=d.cov_bs-d.cov_nobs
        print('  coverage drop without pass: mean %.2f pp median %.2f pp p90 %.2f pp max %.2f ; rows losing >0.5pp: %.1f%% ; >2pp: %.1f%% ; >5pp: %.1f%%'%(drop.mean(),drop.median(),drop.quantile(.9),drop.max(),(drop>0.5).mean()*100,(drop>2).mean()*100,(drop>5).mean()*100))
        print('  coverage without pass quantiles', d.cov_nobs.quantile([.05,.25,.5,.75]).round(2).to_dict())
    # by width
    print(on.groupby(level=1).apply(lambda x: pd.Series(dict(cov_bs=x.cov_bs.mean(),cov_nobs=x.cov_nobs.mean(),drop=(x.cov_bs-x.cov_nobs).mean(),path_bs=x.path_bs.mean(),path_nobs=x.path_nobs.mean()))).round(2))
    # vs F2C aligned: ortools configs use exact java rows; compare means with F2C
    key=['polygon','robot_width']
    for name,d0 in [('aligned',A),('headland',H)]:
        d=d0[~d0.split].copy()
        print('=====',name,'(F2C configs, proposed matching rotation setting; centroid start)')
        out=[]
        for cid,g in d.groupby('config_id'):
            rot=('NSwath' in cid) or ('SwathLength' in cid); src=on if rot else off
            g=g.set_index(key); g.index=g.index.set_names(['polygon','width'])
            j=g.join(src.drop(columns=['split']),how='inner')
            out.append((cid.split('__',1)[1],len(j),j.f2c_coverage_pct.mean(),j.cov_bs.mean(),j.cov_nobs.mean(),j.f2c_path_length.mean(),j.path_bs.mean(),j.path_nobs.mean(),j.f2c_turns.mean(),j.turns_bs.mean(),j.turns_nobs.mean(),j.f2c_redundancy_pct.mean(),j.red_bs.mean(),j.red_nobs.mean()))
        R=pd.DataFrame(out,columns=['config','n','cov_F2C','cov_bs','cov_nobs','path_F2C','path_bs','path_nobs','turn_F2C','turn_bs','turn_nobs','red_F2C','red_bs','red_nobs']).sort_values('path_F2C')
        print(R.round(1).to_string(index=False))



if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    for name in ["structure", "fixes", "orders_alpha", "grid", "boundary"]:
        if which in ("all", name):
            print("\n" + "=" * 30, name, "=" * 30)
            globals()[name]()
