package com.hellovoid.freewidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Set;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PREFS = "freewidget_positions";
    private static final String P46 = "p46", L64 = "l64";
    private static volatile boolean rotating;
    private Class<?> devCfg;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        if (!"com.miui.home".equals(lpp.packageName)) return;
        ClassLoader cl = lpp.classLoader;
        Config cfg = new Config();
        if (!cfg.get("free_widget", true)) return;
        XposedBridge.log("[FW] init");
        try { devCfg = XposedHelpers.findClass("com.miui.home.launcher.DeviceConfig", cl); } catch (Throwable e) {}

        hookPlacement(cl);
        hookResize(cl);
        hookRotationRemap(cl, cfg.get("map_4x2", true));
        hookProtection(cl);
        XposedBridge.log("[FW] ready");
    }

    private void hookPlacement(ClassLoader cl) {
        try { XposedHelpers.findAndHookMethod("com.miui.home.launcher.compat.LayoutDropRuleForSwapPlaces", cl,
            "isLegalXY", int.class,int.class,int.class,int.class,
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

    private void hookRotationRemap(ClassLoader cl, final boolean map) {
        // Detect rotation via transformToDstLayout, remap after delay
        try { XposedHelpers.findAndHookMethod("com.miui.home.launcher.compat.LayoutTransformRuleGridChanged", cl,
            "transformToDstLayout",
            new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam p){
                rotating=true;
                final Object rule=p.thisObject;
                new Handler(Looper.getMainLooper()).postDelayed(()->{
                    if(map) remapAfterTransform(rule);
                    rotating=false;
                },500);
            }});
        } catch (Throwable e) { XposedBridge.log("[FW] rot: "+e); }

        // Save user positions on manual drop
        try { XposedHelpers.findAndHookMethod("com.miui.home.launcher.CellLayout", cl,
            "onDropCompleted", View.class,
            new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam p){
                if(rotating) return;
                try { Object goc=XposedHelpers.getObjectField(p.thisObject,"mGridOccupancyController");
                    if(goc!=null) saveUser(goc); } catch (Throwable e) {}
            }});
        } catch (Throwable e) {}
    }

    private void hookProtection(ClassLoader cl) {
        String c="com.miui.home.GridOccupancyController";
        XposedHelpers.findAndHookMethod(c,cl,"updateCellOccupiedMarks",
            View.class,"com.miui.home.launcher.ItemInfo",boolean.class,
            new XC_MethodHook(){@Override protected void beforeHookedMethod(MethodHookParam p){
                try { Object info=p.args[1]; View v=(View)p.args[0]; if(info==null||v==null) return;
                    clamp(p.thisObject,v,info,
                        XposedHelpers.getIntField(p.thisObject,"mHCells"),
                        XposedHelpers.getIntField(p.thisObject,"mVCells"));
                } catch (Throwable e) {}
            }});
        XposedHelpers.findAndHookMethod(c,cl,"saveCurrentLayout",
            boolean.class,Long.class,long.class,int.class,boolean.class,Context.class,
            new XC_MethodHook(){@Override protected void beforeHookedMethod(MethodHookParam p){
                if(!rotating) protectSave(p.thisObject);
            }});
    }

    // ===== Remap after rotation =====
    private void remapAfterTransform(Object rule) {
        try {
            // Walk from any View in mDstOccupied up to Workspace
            Object[][] dst=(Object[][])XposedHelpers.getObjectField(rule,"mDstOccupied");
            View anyView=null;
            if(dst!=null){ for(Object[]c:dst){if(c==null)continue;for(Object cell:c){if(cell instanceof View){anyView=(View)cell;break;}}if(anyView!=null)break;} }
            if(anyView==null){XposedBridge.log("[FW] remap: no view");return;}

            android.view.ViewGroup ws=null;
            View p=anyView;
            while(p!=null){if(p.getClass().getName().equals("com.miui.home.launcher.Workspace")){ws=(android.view.ViewGroup)p;break;}
                android.view.ViewParent vp=p.getParent();p=(vp instanceof View)?(View)vp:null;}
            if(ws==null){XposedBridge.log("[FW] remap: no ws");return;}

            int found=0,done=0; Set<Object> seen=new HashSet<>();
            for(int i=0;i<ws.getChildCount();i++){View child=ws.getChildAt(i);
                if(!child.getClass().getName().equals("com.miui.home.launcher.CellLayout"))continue;
                Object goc=XposedHelpers.getObjectField(child,"mGridOccupancyController"); if(goc==null)continue;
                int cols=XposedHelpers.getIntField(goc,"mHCells"), rows=XposedHelpers.getIntField(goc,"mVCells");
                String g=grid(cols,rows); if(g==null)continue;
                Object[][] occ=(Object[][])XposedHelpers.getObjectField(goc,"mOccupiedCell"); if(occ==null)continue;
                for(Object[]col:occ){if(col==null)continue;for(Object cell:col){if(!(cell instanceof View)||!seen.add(cell))continue;
                    View v=(View)cell; Object info=v.getTag(); if(info==null||!is4x2(info))continue; found++;
                    String k=key(info); if(k==null)continue;
                    SharedPreferences sp=ws.getContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
                    String base="item."+k+".";
                    int tx,ty;
                    if(sp.contains(base+g+".x")){tx=sp.getInt(base+g+".x",0); ty=sp.getInt(base+g+".y",0);}
                    else {String other=L64.equals(g)?P46:L64;
                        if(sp.contains(base+other+".y")){int srcR=L64.equals(other)?4:6;int srcY=sp.getInt(base+other+".y",0);
                            tx=Math.max(0,(cols-4)/2); ty=map(srcY,2,srcR,2,rows);}
                        else {tx=Math.max(0,(cols-4)/2); ty=XposedHelpers.getIntField(info,"cellY");}}
                    tx=cl(tx,0,cols-4); ty=cl(ty,0,rows-2);
                    XposedHelpers.setIntField(info,"cellX",tx); XposedHelpers.setIntField(info,"cellY",ty);
                    Object lp=v.getLayoutParams(); lp.getClass().getField("cellX").set(lp,tx); lp.getClass().getField("cellY").set(lp,ty);
                    v.setLayoutParams((android.view.ViewGroup.LayoutParams)lp);
                    sp.edit().putString(base+"last",g).apply(); done++;
                    XposedBridge.log("[FW] remapped "+k+" "+g+" ("+tx+","+ty+")");
                }}
            }
            XposedBridge.log("[FW] remap: "+found+" found, "+done+" done");
        } catch (Throwable e) { XposedBridge.log("[FW] remap: "+e); }
    }

    private void saveUser(Object goc) {
        try { int cols=XposedHelpers.getIntField(goc,"mHCells"), rows=XposedHelpers.getIntField(goc,"mVCells");
            String g=grid(cols,rows); if(g==null)return;
            Object[][] occ=(Object[][])XposedHelpers.getObjectField(goc,"mOccupiedCell"); if(occ==null)return;
            Set<Object> seen=new HashSet<>();
            for(Object[]col:occ){if(col==null)continue;for(Object cell:col){if(!(cell instanceof View)||!seen.add(cell))continue;
                View v=(View)cell; Object info=v.getTag(); if(info==null||!is4x2(info))continue;
                String k=key(info); if(k==null)continue; int cx=XposedHelpers.getIntField(info,"cellX"), cy=XposedHelpers.getIntField(info,"cellY");
                if(cx<0||cx+4>cols)continue;
                SharedPreferences sp=v.getContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
                sp.edit().putInt("item."+k+"."+g+".x",cx).putInt("item."+k+"."+g+".y",cy).putString("item."+k+".last",g).apply();
                XposedBridge.log("[FW] saved "+k+" "+g+" ("+cx+","+cy+")");
            }}
        } catch (Throwable e) { XposedBridge.log("[FW] saveUser: "+e); }
    }

    private void protectSave(Object goc) {
        try { int cols=XposedHelpers.getIntField(goc,"mHCells"), rows=XposedHelpers.getIntField(goc,"mVCells");
            Object[][] occ=(Object[][])XposedHelpers.getObjectField(goc,"mOccupiedCell"); if(occ==null)return;
            for(Object[]col:occ){if(col==null)continue;for(Object cell:col){if(cell instanceof View){View v=(View)cell;
                Object info=v.getTag(); if(info==null)continue; clamp(goc,v,info,cols,rows);}}}
        } catch (Throwable e) {}
    }

    private void clamp(Object ctrl,View v,Object info,int cols,int rows) {
        int x=XposedHelpers.getIntField(info,"cellX"), sx=XposedHelpers.getIntField(info,"spanX");
        int y=XposedHelpers.getIntField(info,"cellY"), sy=XposedHelpers.getIntField(info,"spanY");
        int nx=cl(x,0,Math.max(0,cols-sx)), ny=cl(y,0,Math.max(0,rows-sy));
        if(nx!=x||ny!=y){clear(ctrl,v);XposedHelpers.setIntField(info,"cellX",nx);XposedHelpers.setIntField(info,"cellY",ny);v.requestLayout();}
    }

    private boolean is4x2(Object info){try{return XposedHelpers.getIntField(info,"spanX")==4&&XposedHelpers.getIntField(info,"spanY")==2;}catch(Throwable e){return false;}}
    private String key(Object info){try{return"id_"+XposedHelpers.getLongField(info,"id");}catch(Throwable ig){}try{return"aw_"+XposedHelpers.getIntField(info,"appWidgetId");}catch(Throwable ig){}return null;}
    private String grid(int c,int r){return c==4&&r==6?P46:c==6&&r==4?L64:null;}
    private int cells(String m,int d){try{if(devCfg==null)return d;Object v=XposedHelpers.callStaticMethod(devCfg,m);return v instanceof Integer?(Integer)v:d;}catch(Throwable e){return d;}}
    private int map(int oP,int oS,int oC,int nS,int nC){if(oC<=0||nC<=0)return 0;double ctr=(oP+oS/2.0)/oC;return cl((int)Math.round(ctr*nC-nS/2.0),0,Math.max(0,nC-nS));}
    private void clear(Object ctrl,View v){try{Object[][]o=(Object[][])XposedHelpers.getObjectField(ctrl,"mOccupiedCell");if(o==null)return;for(Object[]c:o){if(c==null)continue;for(int i=0;i<c.length;i++)if(c[i]==v)c[i]=null;}}catch(Throwable ig){}}
    private int cl(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}

    static class Config { private JSONObject j;
        Config(){try{File f=new File("/data/local/tmp/betterdock_config.json");if(!f.exists()){j=new JSONObject();return;}FileInputStream in=new FileInputStream(f);byte[]b=new byte[4096];int n=in.read(b);in.close();j=n>0?new JSONObject(new String(b,0,n)):new JSONObject();}catch(Throwable e){j=new JSONObject();}}
        boolean get(String k,boolean d){return j.optBoolean(k,d);}
    }
}
