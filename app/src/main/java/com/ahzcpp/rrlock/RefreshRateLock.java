package com.ahzcpp.rrlock;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 把屏幕刷新率钉死在指定档位，任何投票都改不动。
 *
 * 落点：DisplayModeDirector#getDesiredDisplayModeSpecs
 * 这是 AOSP 里所有刷新率投票收敛成最终结果的唯一出口，返回值直接送进
 * SurfaceFlinger。在这里改，上游谁投票、投几、类名混不混淆都无所谓。
 *
 * 做法：把返回对象里所有 RefreshRateRange 的 min 和 max 都设成目标档位
 * （上下限相等 = 真正钉死，而不是只抬上限），并把 baseModeId 指向该档位。
 *
 * 落点思路参考 github.com/microllate/joyose 的 FrameworkHook；
 * 本实现为重写：它只抬 max 不抬 min，且只处理 <=60Hz 的限制。
 */
public class RefreshRateLock implements IXposedHookLoadPackage {

    private static final String TAG = "[RRLock] ";

    /**
     * 可选覆盖。未设置 = 自动跟随系统所选档位（默认）；
     * follow=同上；max=最高档；数字=该档位；0/off=停用。
     */
    private static final String PROP = "persist.sys.rrlock.hz";

    /** 系统里选的档位，由后台线程更新，Hook 里只读 —— 绝不在显示锁内访问 Settings */
    private static volatile float sFollowHz;
    private static volatile boolean sWatcherStarted;

    private static final String[] DIRECTOR_CLASSES = {
            // Android 14 起挪进了 .mode 子包
            "com.android.server.display.mode.DisplayModeDirector",
            // Android 11 ~ 13
            "com.android.server.display.DisplayModeDirector",
    };

    private static volatile long sPropReadAt;
    private static volatile String sPropCache = "";
    private static volatile String sLastLog = "";
    private static volatile boolean sModesWarned;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 新版 LSPosed 把 system_server 暴露成 "android"，老版本可能是 "system_server"
        if (!"android".equals(lpparam.packageName)
                && !"system_server".equals(lpparam.packageName)) {
            return;
        }
        for (String name : DIRECTOR_CLASSES) {
            hookDirector(lpparam.classLoader, name);
        }
    }

    private static void hookDirector(ClassLoader cl, String className) {
        try {
            Class<?> director = XposedHelpers.findClass(className, cl);
            XposedHelpers.findAndHookMethod(director, "getDesiredDisplayModeSpecs",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                apply(p);
                            } catch (Throwable t) {
                                // 绝不因为本模块让 system_server 崩
                            }
                        }
                    });
            XposedBridge.log(TAG + "已挂上 " + className + "#getDesiredDisplayModeSpecs");
        } catch (Throwable e) {
            // 两个类名只会命中一个，另一个找不到是正常的
        }
    }

    private static void apply(XC_MethodHook.MethodHookParam p) {
        Object specs = p.getResult();
        if (specs == null) return;

        String cfg = readProp();
        if ("0".equals(cfg) || "off".equalsIgnoreCase(cfg)) return;

        startWatcherOnce(p.thisObject);

        // 未设置属性 = 跟随系统所选档位（默认行为）
        if (cfg.isEmpty() || "follow".equalsIgnoreCase(cfg) || "auto".equalsIgnoreCase(cfg)) {
            float f = sFollowHz;
            if (f == -1f) {
                cfg = "max";              // 部分底包用 1 表示"最高"
            } else if (f > 0f) {
                cfg = String.valueOf(f);
            } else {
                return;                   // 还没读到档位，先不动
            }
        }

        int displayId = (p.args.length > 0 && p.args[0] instanceof Integer)
                ? (Integer) p.args[0] : 0;

        Display.Mode[] modes = supportedModes(p.thisObject, displayId);
        if (modes == null || modes.length == 0) {
            if (!sModesWarned) {
                sModesWarned = true;
                XposedBridge.log(TAG + "读不到档位表，为安全起见不做任何改动");
            }
            return;
        }

        Display.Mode target = pickMode(modes, cfg);
        // 目标档位在这块屏上不存在 -> 什么都不做。
        // 这条保险很重要：钉一个面板没有的档会让 SF 设不上任何模式。
        if (target == null) return;

        float hz = target.getRefreshRate();
        int changed = pinRanges(specs, hz);

        // baseModeId 指向同一档，避免范围和基准档不一致被框架回退
        try {
            Field f = findField(specs.getClass(), "baseModeId");
            if (f != null && f.getType() == int.class) {
                f.setAccessible(true);
                if (f.getInt(specs) != target.getModeId()) {
                    f.setInt(specs, target.getModeId());
                    changed++;
                }
            }
        } catch (Throwable ignored) {
        }

        if (changed > 0) {
            String line = displayId + "@" + hz + "/" + target.getModeId();
            if (!line.equals(sLastLog)) {
                sLastLog = line;
                XposedBridge.log(TAG + "钉死 display " + displayId + " -> "
                        + hz + "Hz (modeId " + target.getModeId() + ")");
            }
        }
    }

    /** 从 DisplayModeDirector 里取这块屏支持的档位表 */
    private static Display.Mode[] supportedModes(Object director, int displayId) {
        try {
            Object map = XposedHelpers.getObjectField(director, "mSupportedModesByDisplay");
            if (map instanceof SparseArray) {
                Object v = ((SparseArray<?>) map).get(displayId);
                if (v instanceof Display.Mode[]) return (Display.Mode[]) v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** cfg = max -> 最高档；cfg = 数字 -> 最接近的那一档（差距需在 1Hz 内） */
    private static Display.Mode pickMode(Display.Mode[] modes, String cfg) {
        if ("max".equalsIgnoreCase(cfg)) {
            Display.Mode best = null;
            for (Display.Mode m : modes) {
                if (best == null || m.getRefreshRate() > best.getRefreshRate()) best = m;
            }
            return best;
        }
        float want;
        try {
            want = Float.parseFloat(cfg);
        } catch (NumberFormatException e) {
            return null;
        }
        if (want <= 0f) return null;
        Display.Mode best = null;
        float bestDiff = Float.MAX_VALUE;
        for (Display.Mode m : modes) {
            float diff = Math.abs(m.getRefreshRate() - want);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = m;
            }
        }
        // 档位表里是 132.00002 这种值，容差放 1Hz
        return bestDiff <= 1.0f ? best : null;
    }

    /**
     * 递归把返回对象里每一个 RefreshRateRange 的 min 和 max 都设成 hz。
     * 判定方式：类里同时声明了 float min 和 float max —— 这正是 AOSP
     * RefreshRateRange 的结构（toString 输出 "(min max)"）。
     * A14 起外面还套了一层 RefreshRateRanges{physical, render}，递归自然覆盖到。
     */
    private static int pinRanges(Object root, float hz) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        return walk(root, hz, seen, 0);
    }

    private static int walk(Object value, float hz, Set<Object> seen, int depth) {
        if (value == null || depth > 6) return 0;
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type.isEnum() || value instanceof CharSequence) return 0;
        if (!seen.add(value)) return 0;

        int changed = 0;

        Field min = findField(type, "min");
        Field max = findField(type, "max");
        if (min != null && max != null
                && min.getType() == float.class && max.getType() == float.class) {
            try {
                min.setAccessible(true);
                max.setAccessible(true);
                if (min.getFloat(value) != hz) { min.setFloat(value, hz); changed++; }
                if (max.getFloat(value) != hz) { max.setFloat(value, hz); changed++; }
            } catch (Throwable ignored) {
            }
            return changed;   // 这就是一个范围对象，不用再往下钻
        }

        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                String fn = ft.getName();
                if (fn.startsWith("java.") || fn.startsWith("javax.")) continue;
                try {
                    f.setAccessible(true);
                    changed += walk(f.get(value), hz, seen, depth + 1);
                } catch (Throwable ignored) {
                }
            }
        }
        return changed;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }


    // ---------------------------------------------------------------
    //  跟随系统所选档位
    //  全部在独立线程上读 Settings，Hook 里只读一个 volatile。
    //  getDesiredDisplayModeSpecs 是在 mLock 里跑的，绝不能在那里面
    //  碰 ContentResolver，否则有死锁风险。
    // ---------------------------------------------------------------

    private static final String[][] KEYS = {
            {"secure", "miui_refresh_rate"},     // HyperOS 2/3
            {"secure", "user_refresh_rate"},     // HyperOS 1
            {"system", "peak_refresh_rate"},     // AOSP
            {"system", "user_refresh_rate"},
    };

    private static synchronized void startWatcherOnce(Object director) {
        if (sWatcherStarted) return;
        Context ctx;
        try {
            Object o = XposedHelpers.getObjectField(director, "mContext");
            if (!(o instanceof Context)) return;
            ctx = (Context) o;
        } catch (Throwable t) {
            return;
        }
        sWatcherStarted = true;
        try {
            final ContentResolver cr = ctx.getContentResolver();
            HandlerThread th = new HandlerThread("rrlock-settings");
            th.start();
            final Handler h = new Handler(th.getLooper());

            ContentObserver obs = new ContentObserver(h) {
                @Override
                public void onChange(boolean selfChange) {
                    refresh(cr);
                }
            };
            for (String[] k : KEYS) {
                Uri u = "secure".equals(k[0])
                        ? Settings.Secure.getUriFor(k[1])
                        : Settings.System.getUriFor(k[1]);
                try {
                    cr.registerContentObserver(u, false, obs);
                } catch (Throwable ignored) {
                }
            }

            // 开机早期 settings 可能还没就绪，兜一个低频重读
            h.post(new Runnable() {
                @Override
                public void run() {
                    refresh(cr);
                    h.postDelayed(this, 10000L);
                }
            });
            XposedBridge.log(TAG + "已开始跟随系统所选档位");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "跟随启动失败: " + t);
        }
    }

    private static void refresh(ContentResolver cr) {
        float hz = 0f;
        for (String[] k : KEYS) {
            String v;
            try {
                v = "secure".equals(k[0])
                        ? Settings.Secure.getString(cr, k[1])
                        : Settings.System.getString(cr, k[1]);
            } catch (Throwable t) {
                continue;
            }
            if (v == null || v.isEmpty()) continue;
            float f;
            try {
                f = Float.parseFloat(v.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (f == 1f) { hz = -1f; break; }          // 1 = 最高档
            if (f >= 30f && f <= 480f) { hz = f; break; }
        }
        if (hz != sFollowHz) {
            sFollowHz = hz;
            XposedBridge.log(TAG + "跟随到档位: " + (hz == -1f ? "最高档" : hz + "Hz"));
        }
    }

    /** 读属性，缓存 1 秒，避免高频反射 */
    private static String readProp() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sPropReadAt < 1000L) return sPropCache;
        String v = "";
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object r = XposedHelpers.callStaticMethod(sp, "get", PROP, "");
            if (r instanceof String) v = ((String) r).trim();
        } catch (Throwable ignored) {
        }
        sPropCache = v;
        sPropReadAt = now;
        return v;
    }
}
