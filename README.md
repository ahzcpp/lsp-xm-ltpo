# 刷新率钉死 (RefreshRateLock)

把屏幕刷新率钉死在指定档位，**系统任何投票都改不动**。小米 / 红米 / POCO 通用，不依赖任何 MIUI 内部类名。

---

## 为什么属性和 settings 都没用

小米在 `DisplayModeDirector` 里加了一个 AOSP 没有的投票优先级 `PRIORITY_MIUI_REFRESH_RATE`。它由框架里的服务**实时改写**，而且只设上限、下限留 0：

```
PRIORITY_MIUI_REFRESH_RATE -> Vote: {refreshRateRanges: physical: (0.0 Infinity) render: (0.0 132.0)}
```

下限是 0，意味着系统随时可以往下掉；它的值还会自己变（实测见过 132 → 90 → 144）。

`settings put system min_refresh_rate` 在这套机制下是失效的——它属于 AOSP 的投票链，而小米用自己那一票替掉了。属性（`ro.surface_flinger.*`、`ro.vendor.mi_sf.*`）是投票者读的配置，关掉一个投票者还有下一个。

## 落点

```java
// AOSP: frameworks/base/services/core/java/com/android/server/display/[mode/]DisplayModeDirector.java
public DesiredDisplayModeSpecs getDesiredDisplayModeSpecs(int displayId) {
    SparseArray<Vote> votes = getVotesLocked(displayId);
    ...
    return new DesiredDisplayModeSpecs(baseMode.getModeId(), allowGroupSwitching,
            new RefreshRateRange(primarySummary.minRefreshRate, primarySummary.maxRefreshRate),
            new RefreshRateRange(appRequestSummary.minRefreshRate, appRequestSummary.maxRefreshRate));
}
```

**所有投票在这里收敛成一个返回值，这个返回值直接送进 SurfaceFlinger。** 在这里改，上游谁投票、投几、类名混不混淆，全都无所谓。

本模块的做法：把返回对象里每一个 `RefreshRateRange` 的 **min 和 max 都设成目标档位**（上下限相等＝真正钉死，而不是只抬上限），并把 `baseModeId` 指向同一档。

## 兼容性怎么做到的

- **两个类名都挂**：Android 14 起这个类挪进了 `.mode` 子包，老版本在 `com.android.server.display`，两个都试。
- **反射递归找范围对象**，不写死结构：判定方式是「类里同时声明了 `float min` 和 `float max`」——这正是 AOSP `RefreshRateRange` 的结构。Android 14 起外面套了一层 `RefreshRateRanges{physical, render}`，递归自然覆盖到。两种结构都实测验证过。
- **安全兜底**：目标档位必须真实存在于这块屏的档位表里，否则**什么都不做**。钉一个面板没有的档会让 SurfaceFlinger 设不上任何模式。

## 安装

1. 需要 Root（Magisk / KernelSU / APatch）+ LSPosed
2. 装 APK，在 LSPosed 里启用，**作用域勾 `android`**（部分版本显示为 `system_server`）
3. 重启

**没有第四步。** 装上就自动跟随你在系统里选的档位——你切到 132 就全程 132，切到 144 就全程 144，锁屏、息屏、静止都不会掉。

### 跟随是怎么做的

模块在独立线程上监听这几个设置项，哪个有值就用哪个：

```
secure/miui_refresh_rate   HyperOS 2/3
secure/user_refresh_rate   HyperOS 1
system/peak_refresh_rate   AOSP
system/user_refresh_rate
```

值是 `1` 的（部分底包用它表示"最高"）按最高档处理。

读 Settings 全部在独立线程上做，Hook 里只读一个 volatile 变量——
`getDesiredDisplayModeSpecs` 是在显示锁里跑的，在那里面碰 ContentResolver 有死锁风险。

## 可选：手动指定档位

默认不需要配置。想钉死某个值不跟随，设属性 `persist.sys.rrlock.hz`：

| 值 | 行为 |
|---|---|
| 未设置 / `follow` | **跟随系统所选档位（默认）** |
| `max` | 钉在这块屏支持的最高档 |
| 数字（如 `120`、`144`） | 钉在该档位；档位表里没有就不生效 |
| `0` / `off` | 模块停用 |

改完立即生效，不用重启。

出问题随时退回：`su -c "setprop persist.sys.rrlock.hz 0"`，或者在 LSPosed 里关掉模块。

## 和 Magisk 模块的关系

本模块在 framework 最终出口上改结果，**优先级高于一切属性和 settings**。
如果你装过改 `ro.vendor.display.fod_monitor_default_fps`、`ro.vendor.mi_sf.*`
之类的 Magisk 模块，建议卸掉——那些是在上游跟投票者打，和本模块同时存在
只会让现象变乱，没有叠加收益。

## 怎么确认生效

```sh
adb shell dumpsys SurfaceFlinger | grep displayManagerPolicy
```

看到 `primaryRanges={physical=[144.00 Hz, 144.00 Hz], render=[144.00 Hz, 144.00 Hz]}` 这种**上下限相等**的就是成了。原来是 `[0.00 Hz, 144.00 Hz]`，下限 0。

LSPosed 日志里会有 `[RRLock] 钉死 display 0 -> 144.0Hz (modeId 2)`。

## 已知代价

- **AOD / 息屏也会被钉在高刷**，待机耗电上升。这是「任何情况都不切换」的必然代价。
- 视频、相机、游戏想把刷新率拉低的请求一律无效。
- 开机瞬间面板用的是自己的启动时序，那一次切换本模块够不着（那是 DTBO / 内核层面的事）。

## 构建

本仓库不带预编译 APK。推到你自己的 GitHub 后，Actions 会自动编译，在 Actions 页面下载 `RefreshRateLock-debug`。也可以本地 `gradle assembleDebug`。

## 致谢

Hook 落点的思路来自 [microllate/joyose](https://github.com/microllate/joyose) 的 `FrameworkHook`。本模块为重写，原因：那份实现只抬 `max` 不抬 `min`（下限仍是 0，照样会掉）、只在上限 ≤60Hz 时才动手、目标写死 120，且它的 `xposed_init` 里没有注册 `FrameworkHook`。
