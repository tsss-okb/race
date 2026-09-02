#include "native_sparse_flow.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>

void NativeSparseFlow::clear(){
    prev_.clear(); w_=h_=frameCount_=0;
    targetPts_.clear(); bgPts_.clear(); lastDx_=lastDy_=0.f;
}

float NativeSparseFlow::median(std::vector<float> v){
    if(v.empty()) return 0.f;
    const size_t n=v.size();
    std::nth_element(v.begin(),v.begin()+n/2,v.end());
    float hi=v[n/2];
    if(n&1) return hi;
    std::nth_element(v.begin(),v.begin()+n/2-1,v.end());
    return 0.5f*(hi+v[n/2-1]);
}

std::vector<NativeSparseFlow::Move> NativeSparseFlow::robust(std::vector<Move> m){
    if(m.size()<5) return m;
    std::vector<float> xs,ys; xs.reserve(m.size()); ys.reserve(m.size());
    for(const auto& q:m){ xs.push_back(q.dx); ys.push_back(q.dy); }
    float mdx=median(xs), mdy=median(ys);
    std::vector<float> dev; dev.reserve(m.size());
    for(const auto& q:m) dev.push_back(std::fabs(q.dx-mdx)+std::fabs(q.dy-mdy));
    float mad=std::max(0.75f,median(dev));
    float gate=std::clamp(2.8f*mad+1.f,2.f,7.f);
    m.erase(std::remove_if(m.begin(),m.end(),[&](const Move& q){
        return std::fabs(q.dx-mdx)+std::fabs(q.dy-mdy)>gate;
    }),m.end());
    return m;
}

float NativeSparseFlow::consistency(const std::vector<Move>& m,float dx,float dy){
    if(m.empty()) return 0.f;
    std::vector<float> d; d.reserve(m.size());
    for(const auto& q:m) d.push_back(std::fabs(q.dx-dx)+std::fabs(q.dy-dy));
    float med=median(d);
    return std::clamp(1.f-med/4.5f,0.f,1.f);
}

std::vector<NativeSparseFlow::P> NativeSparseFlow::chooseGradient(
    const uint8_t* g,int w,int h,int x1,int y1,int x2,int y2,int maxCount) const {
    struct C{P p; int s;};
    std::vector<C> c;
    int dx=std::max(1,x2-x1),dy=std::max(1,y2-y1);
    int step=std::max(3,std::min(dx/9,dy/9));
    for(int y=y1;y<=y2;y+=step) for(int x=x1;x<=x2;x+=step){
        if(x<2||x>=w-2||y<2||y>=h-2) continue;
        int gx=std::abs(pix(g,w,x+1,y)-pix(g,w,x-1,y));
        int gy=std::abs(pix(g,w,x,y+1)-pix(g,w,x,y-1));
        int a=std::abs(pix(g,w,x+1,y+1)-pix(g,w,x-1,y-1));
        int b=std::abs(pix(g,w,x+1,y-1)-pix(g,w,x-1,y+1));
        int score=gx+gy+(a+b)/2;
        if(score>=30) c.push_back({{x,y},score});
    }
    if((int)c.size()>maxCount){
        std::partial_sort(c.begin(),c.begin()+maxCount,c.end(),
                          [](const C&a,const C&b){return a.s>b.s;});
        c.resize(maxCount);
    }else{
        std::sort(c.begin(),c.end(),[](const C&a,const C&b){return a.s>b.s;});
    }
    std::vector<P> out; out.reserve(c.size());
    for(const auto& q:c) out.push_back(q.p);
    return out;
}

std::vector<NativeSparseFlow::P> NativeSparseFlow::chooseTarget(
    const uint8_t* g,int w,int h,float x1n,float y1n,float x2n,float y2n,int maxCount) const {
    float wn=x2n-x1n, hn=y2n-y1n;
    int x1=std::clamp((int)((x1n+wn*0.08f)*w),4,w-5);
    int y1=std::clamp((int)((y1n+hn*0.08f)*h),4,h-5);
    int x2=std::clamp((int)((x2n-wn*0.08f)*w),x1+1,w-5);
    int y2=std::clamp((int)((y2n-hn*0.08f)*h),y1+1,h-5);
    return chooseGradient(g,w,h,x1,y1,x2,y2,maxCount);
}

std::vector<NativeSparseFlow::P> NativeSparseFlow::chooseBg(
    const uint8_t* g,int w,int h,float x1n,float y1n,float x2n,float y2n,int maxCount) const {
    int tx1=(int)(x1n*w),ty1=(int)(y1n*h),tx2=(int)(x2n*w),ty2=(int)(y2n*h);
    int mx=std::max(30,(int)((x2n-x1n)*w*1.15f));
    int my=std::max(24,(int)((y2n-y1n)*h*1.15f));
    int rx1=std::clamp(tx1-mx,4,w-5), ry1=std::clamp(ty1-my,4,h-5);
    int rx2=std::clamp(tx2+mx,rx1+1,w-5), ry2=std::clamp(ty2+my,ry1+1,h-5);
    auto cand=chooseGradient(g,w,h,rx1,ry1,rx2,ry2,maxCount*3);
    std::vector<P> out; out.reserve(maxCount);
    for(const auto&p:cand){
        bool inside=p.x>=tx1-8&&p.x<=tx2+8&&p.y>=ty1-8&&p.y<=ty2+8;
        if(!inside){ out.push_back(p); if((int)out.size()>=maxCount) break; }
    }
    return out;
}

int NativeSparseFlow::patchSad(const uint8_t*a,const uint8_t*b,int w,
                               int ax,int ay,int bx,int by){
    int sad=0;
    for(int py=-2;py<=2;py+=2) for(int px=-2;px<=2;px+=2)
        sad+=std::abs(pix(a,w,ax+px,ay+py)-pix(b,w,bx+px,by+py));
    return sad;
}

bool NativeSparseFlow::bestMatch(const uint8_t*a,const uint8_t*b,int w,int h,
                                 int x,int y,int r,int& odx,int& ody){
    int best=1<<30,second=1<<30,bdx=0,bdy=0;
    for(int dy=-r;dy<=r;++dy){
        int cy=y+dy; if(cy<4||cy>=h-4) continue;
        for(int dx=-r;dx<=r;++dx){
            int cx=x+dx; if(cx<4||cx>=w-4) continue;
            int s=patchSad(a,b,w,x,y,cx,cy);
            if(s<best){second=best;best=s;bdx=dx;bdy=dy;}
            else if(s<second) second=s;
        }
    }
    if(best>=620) return false;
    if(second<(1<<30) && (float)best/std::max(1,second)>=0.95f) return false;
    odx=bdx; ody=bdy; return true;
}

bool NativeSparseFlow::bestMatchAround(const uint8_t*a,const uint8_t*b,int w,int h,
                                       int sx,int sy,int ex,int ey,int r,int& obx,int& oby){
    int best=1<<30,bx=ex,by=ey;
    for(int y=ey-r;y<=ey+r;++y){
        if(y<4||y>=h-4) continue;
        for(int x=ex-r;x<=ex+r;++x){
            if(x<4||x>=w-4) continue;
            int s=patchSad(a,b,w,sx,sy,x,y);
            if(s<best){best=s;bx=x;by=y;}
        }
    }
    if(best>=680) return false;
    obx=bx; oby=by; return true;
}

std::vector<NativeSparseFlow::Move> NativeSparseFlow::trackFb(
    const uint8_t*prev,const uint8_t*cur,int w,int h,const std::vector<P>&pts,int r){
    std::vector<Move> out; out.reserve(pts.size());
    for(const auto&p:pts){
        if(p.x<5||p.x>=w-5||p.y<5||p.y>=h-5) continue;
        int dx=0,dy=0;
        if(!bestMatch(prev,cur,w,h,p.x,p.y,r,dx,dy)) continue;
        int cx=p.x+dx,cy=p.y+dy;
        if(cx<5||cx>=w-5||cy<5||cy>=h-5) continue;
        int bx=0,by=0;
        if(!bestMatchAround(cur,prev,w,h,cx,cy,p.x,p.y,2,bx,by)) continue;
        if(std::abs(bx-p.x)+std::abs(by-p.y)>2) continue;
        out.push_back({p,(float)dx,(float)dy});
    }
    return out;
}

void NativeSparseFlow::seed(const uint8_t*g,int w,int h,
                            float x1,float y1,float x2,float y2){
    if(!g||w<32||h<24){ clear(); return; }
    prev_.assign(g,g+(size_t)w*h); w_=w;h_=h; frameCount_=0;
    targetPts_=chooseTarget(g,w,h,x1,y1,x2,y2,42);
    bgPts_=chooseBg(g,w,h,x1,y1,x2,y2,56);
    lastDx_=lastDy_=0.f;
}

NativeFlowResult NativeSparseFlow::track(const uint8_t*g,int w,int h,
                                         float x1,float y1,float x2,float y2){
    NativeFlowResult r;
    if(!g||prev_.empty()||w!=w_||h!=h_||targetPts_.size()<6||bgPts_.size()<8){
        seed(g,w,h,x1,y1,x2,y2); return r;
    }

    float lastMag=std::hypot(lastDx_,lastDy_);
    int tr=std::clamp(4+(int)std::lround(lastMag*0.8f),4,10);
    int br=std::clamp(tr+1,5,11);

    auto tm=robust(trackFb(prev_.data(),g,w,h,targetPts_,tr));
    auto bm=robust(trackFb(prev_.data(),g,w,h,bgPts_,br));
    if(tm.size()<5||bm.size()<7){
        seed(g,w,h,x1,y1,x2,y2); return r;
    }

    std::vector<float> tx,ty,bx,by;
    tx.reserve(tm.size());ty.reserve(tm.size());bx.reserve(bm.size());by.reserve(bm.size());
    for(auto&q:tm){tx.push_back(q.dx);ty.push_back(q.dy);}
    for(auto&q:bm){bx.push_back(q.dx);by.push_back(q.dy);}
    float tdx=median(tx),tdy=median(ty),gdx=median(bx),gdy=median(by);
    float tc=consistency(tm,tdx,tdy),gc=consistency(bm,gdx,gdy);
    float trust=std::clamp(0.55f*tc+0.25f*gc+
                           0.20f*std::clamp((float)tm.size()/24.f,0.f,1.f),0.f,1.f);
    float blend=std::clamp((trust-0.22f)/0.58f,0.f,1.f);
    float dx=gdx+blend*(tdx-gdx), dy=gdy+blend*(tdy-gdy);

    prev_.assign(g,g+(size_t)w*h);
    targetPts_.clear(); targetPts_.reserve(tm.size());
    for(auto&q:tm) targetPts_.push_back({
        std::clamp((int)std::lround(q.from.x+q.dx),4,w-5),
        std::clamp((int)std::lround(q.from.y+q.dy),4,h-5)});
    bgPts_.clear(); bgPts_.reserve(bm.size());
    for(auto&q:bm) bgPts_.push_back({
        std::clamp((int)std::lround(q.from.x+q.dx),4,w-5),
        std::clamp((int)std::lround(q.from.y+q.dy),4,h-5)});

    ++frameCount_;
    if(frameCount_%16==0||targetPts_.size()<16||bgPts_.size()<22){
        targetPts_=chooseTarget(g,w,h,x1,y1,x2,y2,42);
        bgPts_=chooseBg(g,w,h,x1,y1,x2,y2,56);
    }

    lastDx_=dx; lastDy_=dy;
    r.valid=true; r.dxNorm=dx/w; r.dyNorm=dy/h;
    r.targetConsistency=trust; r.globalConsistency=gc;
    r.targetPoints=(int)tm.size(); r.backgroundPoints=(int)bm.size();
    return r;
}
