package com.hellovoid.freewidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PREFS = "freewidget_positions";
    private static final String P46 = "p46";
    private static final String L64 = "l64";

    private static volatile boolean rotating;
    private static volatile boolean internalMove;
    private static final AtomicInteger rotationGeneration = new AtomicInteger();
    private static final Map<Object, Boolean> knownCellLayouts =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Class<?> devCfg;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        if (!"com.miui.home".equals(lpp.packageName)) return;
        ClassLoader cl = lpp.classLoader;
        Config cfg = new Config();
        if (!cfg.get("free_widget", true)) return;
        XposedBridge.log("[FW] init v4");
        try { devCfg = XposedHelpers.findClass("com.miui.home.launcher.DeviceConfig", cl); } catch (Throwable e) { XposedBridge.log("[FW] DeviceConfig: " + e); }
        hookPlacement(cl);
        hookResize(cl);
        hookCellLayoutLifecycle(cl);
        hookRotationRemap(cl, cfg.get("map_4x2", true));
        hookProtection(cl);
        XposedBridge.log("[FW] ready v4");
    }

    private void hookPlacement(ClassLoader cl) {
        try { XposedHelpers.findAndHookMethod("com.miui.home.launcher.compat.LayoutDropRuleForSwapPlaces", cl,
            "isLegalXY", int.class, int.class, int.class, int.class,
            new XC_MethodHook(){@Override protected void beforeHookedMethod(MethodHookParam p){
                int c=cells("getCellCountX",6), r=cells("getCellCountY",6);
                int x=(Integer)p.args[0],y=(Integer)p.args[1],sx=(Integer)p.args[2],sy=(Integer)p.args[3];
                p.setResult(x>=0&&y>=0&&sx>0&&sy>0&&x+sx<=c&&y+sy<=r);}});
        } catch (Throwable e) {}
    }

    private void hookResize(ClassLoader cl) {
        for (String h:new String[]{"com.miui.home.launcher.widget.AppWidgetResizeHelperPad","com.miui.home.launcher.widget.AppWidgetResizeHelperPhone"})
            try { XposedHelpers.findAndHookMethod(h,cl,"getMaxResizeFrameSpan",int.class,int.class,int.class,int.class,int.class,
                new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam p){
                    p.setResult(new Pair<>(cells("getCellCountX",6),cells("getCellCountY",6)));}});
            } catch (Throwable e) {}
    }

    private void hookCellLayoutLifecycle(ClassLoader cl) {
        try { XposedHelpers.findAndHookMethod("com.miui.home.launcher.CellLayout", cl,
            "onLayout", boolean.class, int.class, int.class, int.class, int.class,
            new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam p){
                rememberCellLayout(p.thisObject);
                if(!rotating||internalMove)return;
                final WeakReference<Object> ref=new WeakReference<>(p.thisObject);
                new Handler(Looper.getMainLooper()).post(()->{Object layout=ref.get();if(layout!=null&&rotating&&!internalMove)restoreCellLayout(layout,"onLayout");});
            }});
        } catch (Throwable e) { XposedBridge.log("[FW] onLayout: "+e); }

        try { XposedHelpers.findAndHookMethod("com.miui.home.launcher.CellLayout", cl,
            "onDropCompleted",
            new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam p){
                rememberCellLayout(p.thisObject);
                if(!rotating&&!internalMove)saveUserFromCellLayout(p.thisObject,false);
            }});
        } catch (Throwable e) { XposedBridge.log("[FW] onDropCompleted: "+e); }
    }

    private void hookRotationRemap(ClassLoader cl, final boolean mapEnabled) {
        try { XposedHelpers.findAndHookMethod("com.miui.home.launcher.compat.LayoutTransformRuleGridChanged", cl,
            "transformToDstLayout",
            new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    int gen=rotationGeneration.incrementAndGet(); p.setObjectExtra("fw_gen",gen); rotating=true;
                    seedPositionsFromRule(p.thisObject,"mSrcOccupied"); seedKnownLayouts();
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    final Object rule=p.thisObject; Object extra=p.getObjectExtra("fw_gen");
                    final int gen=extra instanceof Integer?(Integer)extra:rotationGeneration.get();
                    Handler h=new Handler(Looper.getMainLooper());
                    int[] delays={0,120,320,650,950};
                    for(int d:delays)h.postDelayed(()->{if(gen!=rotationGeneration.get())return;
                        if(mapEnabled){restoreFromRule(rule,"t+"+d);restoreKnownLayouts("t+"+d);}},d);
                    h.postDelayed(()->{if(gen!=rotationGeneration.get())return;
                        if(mapEnabled){restoreFromRule(rule,"t-fin");restoreKnownLayouts("t-fin");}
                        rotating=false;},1150);
                }});
        } catch (Throwable e) { XposedBridge.log("[FW] rot: "+e); }
    }

    private void hookProtection(ClassLoader cl) {
        String c="com.miui.home.GridOccupancyController";
        try { XposedHelpers.findAndHookMethod(c,cl,"updateCellOccupiedMarks",
            View.class,"com.miui.home.launcher.ItemInfo",boolean.class,
            new XC_MethodHook(){@Override protected void beforeHookedMethod(MethodHookParam p){
                if(internalMove)return;
                try{Object info=p.args[1];View view=(View)p.args[0];if(info==null||view==null)return;
                    int cols=XposedHelpers.getIntField(p.thisObject,"mHCells"),rows=XposedHelpers.getIntField(p.thisObject,"mVCells");
                    clamp(p.thisObject,view,info,cols,rows,true);
                    if(!rotating)seedPosition(p.thisObject,view,info,cols,rows);
                }catch(Throwable e){}
            }});
        } catch (Throwable e) {}
        try { XposedHelpers.findAndHookMethod(c,cl,"saveCurrentLayout",
            boolean.class,Long.class,long.class,int.class,boolean.class,Context.class,
            new XC_MethodHook(){@Override protected void beforeHookedMethod(MethodHookParam p){protectSave(p.thisObject);}});
        } catch (Throwable e) {}
    }

    // ===== Rotation restore =====
    private void restoreFromRule(Object rule, String reason) {
        Set<View> views=new HashSet<>(); collectRuleViews(rule,"mDstOccupied",views); collectRuleViews(rule,"mSrcOccupied",views);
        int r=0;for(View v:views){Object cl=findCellLayout(v);if(cl!=null){rememberCellLayout(cl);r+=restoreCellLayout(cl,reason+"/rule");}}
        XposedBridge.log("[FW] restoreFromRule "+reason+" v="+views.size()+" r="+r);
    }

    private void collectRuleViews(Object rule, String field, Set<View> out) {
        try{Object ao=XposedHelpers.getObjectField(rule,field);if(!(ao instanceof Object[][]))return;
            Object[][] a=(Object[][])ao;for(Object[]c:a){if(c==null)continue;for(Object cell:c){View v=unwrapTransformView(cell);if(v!=null)out.add(v);}}
        }catch(Throwable e){XposedBridge.log("[FW] collect"+field+": "+e);}
    }

    private View unwrapTransformView(Object cell) {
        if(cell instanceof View)return(View)cell;if(cell==null)return null;
        try{Object d=XposedHelpers.getObjectField(cell,"mData");if(d instanceof View)return(View)d;}catch(Throwable ig){}
        try{Object d=XposedHelpers.callMethod(cell,"getMData");if(d instanceof View)return(View)d;}catch(Throwable ig){}
        return null;
    }

    private int restoreCellLayout(Object cellLayout, String reason) {
        if(!(cellLayout instanceof ViewGroup))return 0; ViewGroup lv=(ViewGroup)cellLayout;
        if(!lv.isAttachedToWindow())return 0;
        try{Object goc=XposedHelpers.getObjectField(cellLayout,"mGridOccupancyController");if(goc==null)return 0;
            int cols=XposedHelpers.getIntField(goc,"mHCells"),rows=XposedHelpers.getIntField(goc,"mVCells");
            String tg=grid(cols,rows);if(tg==null)return 0;
            String act=activeGrid(lv);if(act!=null&&!act.equals(tg))return 0;
            List<View> desc=new ArrayList<>();collectDescendantViews(lv,desc);
            int ch=0;Set<Object> seen=new HashSet<>();
            for(View v:desc){Object info=v.getTag();if(info==null||!seen.add(info)||!is4x2(info))continue;
                if(restoreView(cellLayout,goc,v,info,cols,rows,tg,reason))ch++;}
            return ch;
        }catch(Throwable e){XposedBridge.log("[FW] restoreCellLayout "+reason+": "+e);return 0;}
    }

    private boolean restoreView(Object cellLayout, Object goc, View view, Object info, int cols, int rows, String tg, String reason) {
        try{String ik=key(info);if(ik==null)return false;
            int sx=XposedHelpers.getIntField(info,"spanX"),sy=XposedHelpers.getIntField(info,"spanY");
            int ox=XposedHelpers.getIntField(info,"cellX"),oy=XposedHelpers.getIntField(info,"cellY");
            SharedPreferences sp=view.getContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            String base="item."+ik+".",xk=base+tg+".x",yk=base+tg+".y";
            int tx,ty;boolean had=sp.contains(xk)&&sp.contains(yk);
            if(had){tx=sp.getInt(xk,centerX(cols,sx));ty=sp.getInt(yk,oy);
                if(!fits(tx,ty,sx,sy,cols,rows)){tx=centerX(cols,sx);ty=cl(ty,0,Math.max(0,rows-sy));}
            }else{String oth=L64.equals(tg)?P46:L64;String oyK=base+oth+".y";
                tx=centerX(cols,sx);ty=sp.contains(oyK)?sp.getInt(oyK,oy):oy;ty=cl(ty,0,Math.max(0,rows-sy));}
            tx=cl(tx,0,Math.max(0,cols-sx));ty=cl(ty,0,Math.max(0,rows-sy));
            boolean ch=ox!=tx||oy!=ty;if(ch)moveView(cellLayout,goc,view,info,tx,ty);
            sp.edit().putInt(xk,tx).putInt(yk,ty).putString(base+"last",tg).apply();
            if(ch)XposedBridge.log("[FW] restore "+ik+" "+tg+" ("+ox+","+oy+")->("+tx+","+ty+") "+reason);
            return ch;
        }catch(Throwable e){XposedBridge.log("[FW] restoreView: "+e);return false;}
    }

    private void moveView(Object cellLayout, Object goc, View view, Object info, int x, int y) {
        internalMove=true;
        try{clear(goc,view);XposedHelpers.setIntField(info,"cellX",x);XposedHelpers.setIntField(info,"cellY",y);
            XposedHelpers.callMethod(goc,"updateCellOccupiedMarks",view,info,true);
            try{XposedHelpers.callMethod(goc,"relayoutByOccupiedCells",view);}catch(Throwable ig){
                try{XposedHelpers.callMethod(cellLayout,"relayoutByOccupiedCells");}catch(Throwable ig2){view.requestLayout();}}
            view.requestLayout();
        }finally{internalMove=false;}
    }

    // ===== Position persistence =====
    private void saveUserFromCellLayout(Object cellLayout, boolean onlyIfMissing) {
        if(!(cellLayout instanceof ViewGroup))return;
        try{Object goc=XposedHelpers.getObjectField(cellLayout,"mGridOccupancyController");if(goc==null)return;
            int cols=XposedHelpers.getIntField(goc,"mHCells"),rows=XposedHelpers.getIntField(goc,"mVCells");
            String cg=grid(cols,rows);if(cg==null)return;
            String act=activeGrid((View)cellLayout);if(act!=null&&!act.equals(cg))return;
            List<View> desc=new ArrayList<>();collectDescendantViews((ViewGroup)cellLayout,desc);
            Set<Object> seen=new HashSet<>();
            for(View v:desc){Object info=v.getTag();if(info==null||!seen.add(info)||!is4x2(info))continue;
                savePosition(v,info,cols,rows,cg,onlyIfMissing);}
        }catch(Throwable e){XposedBridge.log("[FW] saveUser: "+e);}
    }

    private void seedPosition(Object goc, View view, Object info, int cols, int rows) {
        try{String cg=grid(cols,rows);if(cg==null||!is4x2(info))return;savePosition(view,info,cols,rows,cg,true);}
        catch(Throwable e){}
    }

    private void savePosition(View view, Object info, int cols, int rows, String cg, boolean onlyIfMissing) {
        String ik=key(info);if(ik==null)return;
        int x=XposedHelpers.getIntField(info,"cellX"),y=XposedHelpers.getIntField(info,"cellY");
        int sx=XposedHelpers.getIntField(info,"spanX"),sy=XposedHelpers.getIntField(info,"spanY");
        if(!fits(x,y,sx,sy,cols,rows))return;
        SharedPreferences sp=view.getContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String base="item."+ik+".",xk=base+cg+".x",yk=base+cg+".y";
        if(onlyIfMissing&&sp.contains(xk)&&sp.contains(yk))return;
        sp.edit().putInt(xk,x).putInt(yk,y).putString(base+"last",cg).apply();
        XposedBridge.log("[FW] saved "+ik+" "+cg+" ("+x+","+y+")");
    }

    private void seedPositionsFromRule(Object rule, String field) {
        Set<View> views=new HashSet<>();collectRuleViews(rule,field,views);
        for(View v:views){Object cl=findCellLayout(v);if(cl!=null){rememberCellLayout(cl);saveUserFromCellLayout(cl,true);}}
    }

    private void seedKnownLayouts(){for(Object cl:snapshotKnownLayouts())saveUserFromCellLayout(cl,true);}

    // ===== Boundary protection =====
    private void protectSave(Object goc) {
        try{int cols=XposedHelpers.getIntField(goc,"mHCells"),rows=XposedHelpers.getIntField(goc,"mVCells");
            Object[][] occ=(Object[][])XposedHelpers.getObjectField(goc,"mOccupiedCell");if(occ==null)return;
            Set<Object> seen=new HashSet<>();
            for(Object[]c:occ){if(c==null)continue;for(Object cell:c){if(!(cell instanceof View)||!seen.add(cell))continue;
                View v=(View)cell;Object info=v.getTag();if(info==null)continue;clamp(goc,v,info,cols,rows,false);}}
        }catch(Throwable e){}
    }

    private void clamp(Object goc, View view, Object info, int cols, int rows, boolean originalWillRemark) {
        int x=XposedHelpers.getIntField(info,"cellX"),y=XposedHelpers.getIntField(info,"cellY");
        int sx=XposedHelpers.getIntField(info,"spanX"),sy=XposedHelpers.getIntField(info,"spanY");
        int nx;if(is4x2(info)&&grid(cols,rows)!=null&&(x<0||x+sx>cols))nx=centerX(cols,sx);else nx=cl(x,0,Math.max(0,cols-sx));
        int ny=cl(y,0,Math.max(0,rows-sy));if(nx==x&&ny==y)return;
        clear(goc,view);XposedHelpers.setIntField(info,"cellX",nx);XposedHelpers.setIntField(info,"cellY",ny);
        if(!originalWillRemark){internalMove=true;
            try{XposedHelpers.callMethod(goc,"updateCellOccupiedMarks",view,info,true);
                try{XposedHelpers.callMethod(goc,"relayoutByOccupiedCells",view);}catch(Throwable ig){view.requestLayout();}
            }finally{internalMove=false;}}
        view.requestLayout();
        XposedBridge.log("[FW] clamp "+key(info)+" "+cols+"x"+rows+" ("+x+","+y+")->("+nx+","+ny+") rot="+rotating);
    }

    // ===== Helpers =====
    private void rememberCellLayout(Object cl){knownCellLayouts.put(cl,Boolean.TRUE);}
    private List<Object> snapshotKnownLayouts(){synchronized(knownCellLayouts){return new ArrayList<>(knownCellLayouts.keySet());}}
    private void restoreKnownLayouts(String r){int t=0;for(Object cl:snapshotKnownLayouts())t+=restoreCellLayout(cl,r+"/kn");XposedBridge.log("[FW] restoreKnown "+r+" ch="+t);}
    private Object findCellLayout(View v){View c=v;while(c!=null){if(c.getClass().getName().equals("com.miui.home.launcher.CellLayout"))return c;ViewParent p=c.getParent();c=(p instanceof View)?(View)p:null;}return null;}
    private void collectDescendantViews(ViewGroup root, List<View> out){for(int i=0;i<root.getChildCount();i++){View c=root.getChildAt(i);out.add(c);if(c instanceof ViewGroup)collectDescendantViews((ViewGroup)c,out);}}
    private String activeGrid(View v){try{int o=v.getResources().getConfiguration().orientation;if(o==Configuration.ORIENTATION_PORTRAIT)return P46;if(o==Configuration.ORIENTATION_LANDSCAPE)return L64;}catch(Throwable ig){}return grid(cells("getCellCountX",-1),cells("getCellCountY",-1));}
    private boolean is4x2(Object info){try{return XposedHelpers.getIntField(info,"spanX")==4&&XposedHelpers.getIntField(info,"spanY")==2;}catch(Throwable e){return false;}}
    private boolean fits(int x,int y,int sx,int sy,int cols,int rows){return x>=0&&y>=0&&sx>0&&sy>0&&x+sx<=cols&&y+sy<=rows;}
    private int centerX(int cols,int spanX){return Math.max(0,(cols-spanX)/2);}
    private String key(Object info){try{return"id_"+XposedHelpers.getLongField(info,"id");}catch(Throwable ig){}try{return"aw_"+XposedHelpers.getIntField(info,"appWidgetId");}catch(Throwable ig){}return null;}
    private String grid(int c,int r){return c==4&&r==6?P46:c==6&&r==4?L64:null;}
    private int cells(String m,int d){try{if(devCfg==null)return d;Object v=XposedHelpers.callStaticMethod(devCfg,m);return v instanceof Integer?(Integer)v:d;}catch(Throwable e){return d;}}
    private void clear(Object ctrl,View v){try{Object[][]o=(Object[][])XposedHelpers.getObjectField(ctrl,"mOccupiedCell");if(o==null)return;for(Object[]c:o){if(c==null)continue;for(int i=0;i<c.length;i++)if(c[i]==v)c[i]=null;}}catch(Throwable ig){}}
    private int cl(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}

    static class Config { private JSONObject j;
        Config(){try{File f=new File("/data/local/tmp/betterdock_config.json");if(!f.exists()){j=new JSONObject();return;}FileInputStream in=new FileInputStream(f);byte[]b=new byte[4096];int n=in.read(b);in.close();j=n>0?new JSONObject(new String(b,0,n)):new JSONObject();}catch(Throwable e){j=new JSONObject();}}
        boolean get(String k,boolean d){return j.optBoolean(k,d);}
    }
}
