package com.carrot.momorev;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 墨墨背单词：底部「认识 / 不确定 / 不认识」三个按钮 -> 四个「认识 / 熟知 / 不确定 / 不认识」。
 *
 * 背单词界面是传统 XML 布局，底部操作栏为 ConstraintLayout(id=rev_operation_bar)，
 * 内含 rev_familiar / rev_vague / rev_forget 三个 0dp 宽 TextView 组成的水平链。
 * 在链中插入一个同样 0dp 宽的 TextView，把 three-chain 变成 four-chain。
 *
 * 注意：App 经过 R8，ConstraintLayout.LayoutParams 被重命名为 s62 且字段名也被混淆，
 * 所以这里不按名字取约束字段，而是「按值反查」——某个 int 字段的值等于邻居 view 的 id，
 * 那它就是那条约束。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG = "com.maimemo.android.momo";
    private static final String TAG = "MoMoRev: ";
    private static final boolean DEBUG = true;

    /**
     * 熟知按钮配色。
     *
     * 原生按钮的配色随主题变化：深色主题是「深底 + 彩色描边 + 彩色文字」，浅色主题是
     * 「白色填充 + 浅灰描边 + 深色文字」。所以这里按原生文字颜色的明暗挑一组：
     * - 浅色主题（原生深色字）用深一点的蓝，白底上才看得清
     * - 深色主题（原生浅色字）沿用原来的浅蓝，和原生描边风格一致
     */
    private static final int WELL_COLOR_LIGHT = 0xFF0288D1;
    private static final int WELL_COLOR_DARK = 0xFF4FC3F7;
    private static final int WELL_FAMILIAR_FALLBACK = 4;

    private static final Map<View, Boolean> DONE_BARS = new WeakHashMap<View, Boolean>();
    private static final Map<View, Boolean> WATCHED_ROOTS = new WeakHashMap<View, Boolean>();

    /** 当前背单词 Fragment 及其根 view（通过 hook onViewCreated 捕获） */
    private static final Object FRAG_LOCK = new Object();
    private static final String[] FRAGMENT_CLASSES = {
            "com.maimemo.android.momo.revision.ReviewFragment",
            "com.maimemo.android.momo.study.StudyFragment"
    };
    private static volatile Object sFragment;
    private static volatile View sFragmentRoot;
    private static volatile boolean sFragmentHooked;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName) || !PKG.equals(lpparam.processName)) {
            return;
        }
        log("module loaded in " + lpparam.processName);
        try {
            XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!(param.thisObject instanceof Application)) {
                        return;
                    }
                    ((Application) param.thisObject).registerActivityLifecycleCallbacks(new Lifecycle());
                    log("activity lifecycle registered");
                    final ClassLoader cl = ((Application) param.thisObject).getClassLoader();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            for (int i = 0; i < 40 && !sFragmentHooked; i++) {
                                tryInstallFragmentHooks(cl);
                                if (sFragmentHooked) {
                                    return;
                                }
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }
                    }, "momorev-hook").start();
                }
            });
        } catch (Throwable t) {
            log("hook Application.attach failed: " + t);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            watch(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    private static void watch(final Activity activity) {
        final View decor;
        try {
            decor = activity.getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        if (decor == null) {
            return;
        }
        synchronized (WATCHED_ROOTS) {
            if (WATCHED_ROOTS.containsKey(decor)) {
                return;
            }
            WATCHED_ROOTS.put(decor, Boolean.TRUE);
        }
        decor.post(new Runnable() {
            @Override
            public void run() {
                inject(activity);
            }
        });
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                inject(activity);
            }
        });
    }

    // ---------------------------------------------------------------- injection

    private static void inject(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) {
                return;
            }
            tryInstallFragmentHooks(activity.getClassLoader());
            View root = activity.getWindow().getDecorView();
            Resources res = activity.getResources();
            int barId = res.getIdentifier("rev_operation_bar", "id", PKG);
            if (barId == 0) {
                return;
            }
            View bar = root.findViewById(barId);
            if (!(bar instanceof ViewGroup)) {
                return;
            }
            synchronized (DONE_BARS) {
                if (DONE_BARS.containsKey(bar)) {
                    return;
                }
            }
            if (buildButton(activity, (ViewGroup) bar, res)) {
                synchronized (DONE_BARS) {
                    DONE_BARS.put(bar, Boolean.TRUE);
                }
            }
        } catch (Throwable t) {
            log("inject failed: " + t);
        }
    }

    private static boolean buildButton(Activity activity, ViewGroup bar, Resources res) throws Throwable {
        int famId = res.getIdentifier("rev_familiar", "id", PKG);
        int vagueId = res.getIdentifier("rev_vague", "id", PKG);
        if (famId == 0 || vagueId == 0) {
            log("cannot resolve button ids");
            return false;
        }
        View fam = bar.findViewById(famId);
        View vague = bar.findViewById(vagueId);
        if (!(fam instanceof TextView) || vague == null) {
            log("cannot find existing buttons");
            return false;
        }
        TextView src = (TextView) fam;
        ViewGroup.LayoutParams famLp = fam.getLayoutParams();
        ViewGroup.LayoutParams vagueLp = vague.getLayoutParams();
        if (famLp == null || vagueLp == null) {
            log("no layout params");
            return false;
        }
        Class<?> clpClass = famLp.getClass();

        // 按值反查约束字段：fam.right -> vague.left, vague.left -> fam.right
        Field rightField = findIntField(clpClass, famLp, vagueId);
        Field leftField = findIntField(clpClass, vagueLp, famId);
        if (rightField == null || leftField == null) {
            log("constraint fields not found, right=" + rightField + " left=" + leftField);
            return false;
        }

        TextView tv = new TextView(activity);
        String label = "熟知";
        int labelId = res.getIdentifier("rev_well_familiar", "string", PKG);
        if (labelId != 0) {
            String s = res.getString(labelId);
            if (s != null && s.length() > 0) {
                label = s;
            }
        }
        tv.setText(label);
        tv.setTag("momorev_well_familiar");
        float textSize = src.getTextSize();
        if (textSize <= 0f) {
            // 极少数情况下这里的 TextView 还没被赋过字号，用 14sp 兜底，避免画出「有底无字」的色块
            textSize = 14f * res.getDisplayMetrics().scaledDensity;
            log("src textSize invalid, fallback to " + textSize);
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        tv.setTypeface(src.getTypeface());
        tv.setGravity(src.getGravity());
        tv.setIncludeFontPadding(true);
        tv.setPadding(src.getPaddingLeft(), src.getPaddingTop(), src.getPaddingRight(), src.getPaddingBottom());
        tv.setTextColor(WELL_COLOR_DARK);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setClickable(true);
        tv.setFocusable(true);

        int newId = View.generateViewId();
        tv.setId(newId);

        // 以「不确定」的 LayoutParams 为模板：左接认识，右接不认识 —— 正好是我们想要的
        Object ourLp = copyLp(clpClass, vagueLp, 0, vagueLp.height);
        rightField.setInt(ourLp, vagueId);          // our.right -> vague.left
        leftField.setInt(ourLp, famId);             // our.left  -> fam.right

        // 把原来「认识 -> 不确定」改成「认识 -> 熟知 -> 不确定」
        rightField.setInt(famLp, newId);
        fam.setLayoutParams(famLp);
        leftField.setInt(vagueLp, newId);
        vague.setLayoutParams(vagueLp);

        style(tv, src, res);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onWellFamiliar(v);
            }
        });
        bar.addView(tv, (ViewGroup.LayoutParams) ourLp);
        bar.requestLayout();
        log("4th button injected (" + label + ") fields=" + leftField.getName() + "/" + rightField.getName()
                + " lp=" + clpClass.getName());
        tv.post(new Runnable() {
            @Override
            public void run() {
                String textW = tv.getLayout() == null ? "null" : String.valueOf(tv.getLayout().getWidth());
                log("well button laid out: " + tv.getWidth() + "x" + tv.getHeight()
                        + " textW=" + textW + " textSize=" + tv.getTextSize()
                        + " textColor=" + Integer.toHexString(tv.getCurrentTextColor()));
            }
        });
        return true;
    }

    private static final String[] SKIP_FIELDS = {
            "width", "height", "leftMargin", "rightMargin", "topMargin", "bottomMargin",
            "marginStart", "marginEnd", "gravity", "horizontalWeight"
    };

    /** 在 lp 的 public int 字段里找值等于 value 的那个（即某条指向 value 视图的约束） */
    private static Field findIntField(Class<?> cls, Object lp, int value) {
        Field found = null;
        String foundName = null;
        for (Field f : cls.getFields()) {
            if (f.getType() != int.class) {
                continue;
            }
            String name = f.getName();
            boolean skip = false;
            for (String s : SKIP_FIELDS) {
                if (s.equals(name)) {
                    skip = true;
                    break;
                }
            }
            if (skip) {
                continue;
            }
            try {
                if (f.getInt(lp) != value) {
                    continue;
                }
            } catch (Throwable ignored) {
                continue;
            }
            if (found == null) {
                found = f;
                foundName = name;
            } else {
                log("ambiguous field for " + value + ": " + foundName + " / " + name + " (keep first)");
            }
        }
        return found;
    }

    private static Object copyLp(Class<?> cls, Object src, int width, int height) throws Throwable {
        Constructor<?> ctor = cls.getConstructor(int.class, int.class);
        Object dst = ctor.newInstance(Integer.valueOf(width), Integer.valueOf(height));
        for (Field f : cls.getFields()) {
            if (f.getType() != int.class) {
                continue;
            }
            try {
                f.setInt(dst, f.getInt(src));
            } catch (Throwable ignored) {
            }
        }
        ViewGroup.LayoutParams d = (ViewGroup.LayoutParams) dst;
        d.width = width;
        d.height = height;
        return dst;
    }

    /**
     * 克隆「认识」按钮的背景来做熟知按钮。
     *
     * 这里有个坑：原生背景随主题不同（墨墨 5.6.05 里认识按钮是 background="?rev_button_familiar"）
     * - 深色主题 res/drawable-night：透明填充 + 1.2dp 描边。整块 SRC_IN 染色只改描边颜色，
     *   外观和原生完全一致。
     * - 浅色主题 res/drawable：实心填充（#82c2ab 圆角，没有描边）。SRC_IN 会把不透明的填充
     *   一起染成纯蓝，于是得到一个「没有字的蓝色色块」——文字和底色同为蓝色，直接看不见。
     *   海信 A5 Pro 上就是这个表现。
     *
     * 所以先把源 drawable 渲染成小图采样：中间透明的照旧克隆染色；实心的则自绘一个空心按钮。
     * 填充色刻意不用采样值（它跟随主题/皮肤走，可能是绿色），改成主题无关的淡蓝，保证
     * 无论在浅色页面还是深色页面上，蓝字都读得清。
     *
     * 另外墨水屏设备上墨墨会自己把界面画成黑白（不是模块干的），这时再硬塞蓝色就很突兀，
     * 所以检测到墨水屏就跟着走黑白：描边/文字用原生文字色、填充透明，靠文字区分第四个按钮。
     */
    private static void style(TextView tv, TextView src, Resources res) {
        float density = res.getDisplayMetrics().density;
        int stroke = Math.max(2, Math.round(1f * density));

        int srcTextColor = 0xFF333333;
        try {
            srcTextColor = src.getCurrentTextColor();
        } catch (Throwable ignored) {
        }
        boolean lightTheme = luminance(srcTextColor) < 0.5f;
        int accent = lightTheme ? WELL_COLOR_LIGHT : WELL_COLOR_DARK;
        // 实际生效的颜色（墨水屏时会换成黑/深灰），按压态也跟着它走
        int effective = accent;

        tv.setTextColor(accent);
        tv.setAlpha(1f);

        Drawable clone = null;
        try {
            Drawable bg = src.getBackground();
            if (bg != null && bg.getConstantState() != null) {
                clone = bg.getConstantState().newDrawable(res).mutate();
            }
        } catch (Throwable t) {
            log("background clone failed: " + t);
        }

        Sample s = clone == null ? null : sample(clone, src);
        // 圆角取 App 自己的 @dimen/r_default（原生按钮 background 就用的它），拿不到再退到采样估算
        int radius = resolveRadius(res, s != null && s.radius > 0 ? s.radius : Math.round(10f * density));

        if (isEinkDevice(tv.getContext())) {
            // 墨水屏：App 自己会把界面画成黑白，这里跟着走黑白 —— 描边和文字直接用原生文字色，
            // 填充透明（露出页面底色），和旁边三个按钮完全同一种观感，靠「熟知」两个字区分
            int mono = ((srcTextColor >>> 24) & 0xFF) == 0 ? 0xFF4B4C4D : srcTextColor;
            effective = mono;
            tv.setTextColor(mono);
            tv.setBackground(roundStroke(radius, stroke, mono, 0x00000000));
            log("background mono (eink) accent=" + Integer.toHexString(mono)
                    + " radius=" + radius + " stroke=" + stroke);
        } else if (clone != null && s != null && s.centerAlpha < 32) {
            // 空心描边：保留原生几何，只换描边颜色
            clone.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            tv.setBackground(clone);
            log("background cloned (outline, centerAlpha=" + s.centerAlpha + ")");
        } else {
            // 实心背景：整块染色会糊成一整块纯色，改成「淡蓝填充 + 蓝色描边」
            int fill = (accent & 0x00FFFFFF) | 0x1A000000;
            tv.setBackground(roundStroke(radius, stroke, accent, fill));
            log("background rebuilt (filled src -> outline) fill=" + Integer.toHexString(fill)
                    + " srcFill=" + (s == null ? "sample-failed" : Integer.toHexString(s.centerColor))
                    + " radius=" + radius + " stroke=" + stroke);
        }

        try {
            android.graphics.drawable.StateListDrawable fg = new android.graphics.drawable.StateListDrawable();
            fg.addState(new int[]{android.R.attr.state_pressed},
                    roundStroke(radius, stroke, effective, (effective & 0x00FFFFFF) | 0x33000000));
            fg.addState(new int[0], new android.graphics.drawable.ColorDrawable(0x00000000));
            tv.setForeground(fg);
        } catch (Throwable t) {
            log("foreground failed: " + t);
        }
        log("styled: lightTheme=" + lightTheme + " accent=" + Integer.toHexString(accent)
                + " srcTextColor=" + Integer.toHexString(srcTextColor));
    }

    /** 原生按钮圆角来自 @dimen/r_default（4dp），直接读 App 资源最准；读不到才用兜底值 */
    private static int resolveRadius(Resources res, int fallback) {
        try {
            int id = res.getIdentifier("r_default", "dimen", PKG);
            if (id != 0) {
                int px = res.getDimensionPixelSize(id);
                if (px > 0) {
                    return px;
                }
            }
        } catch (Throwable t) {
            log("resolve r_default failed: " + t);
        }
        return fallback;
    }

    // ---------------------------------------------------------------- e-ink

    private static volatile Boolean sEink;

    /** 是否墨水屏设备：墨墨自己会把界面转成黑白，模块也跟着转 */
    private static boolean isEinkDevice(Context ctx) {
        Boolean cached = sEink;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean r = detectEink(ctx);
        sEink = Boolean.valueOf(r);
        log("eink device = " + r);
        return r;
    }

    private static boolean detectEink(Context ctx) {
        // 1) 系统属性：各家墨水屏基本都会留 epd / eink 属性（海信是 persist.sys.epd.*）
        String[] props = {
                "persist.sys.epd.black", "persist.sys.epd.contrast", "persist.sys.epd.mode",
                "ro.eink.enable", "ro.eink", "ro.hisense.eink", "ro.epd.version",
                "persist.sys.eink", "sys.eink.enable"
        };
        for (String key : props) {
            String v = sysProp(key);
            if (v != null && v.length() > 0 && !"0".equals(v) && !"false".equalsIgnoreCase(v)) {
                log("eink by prop " + key + "=" + v);
                return true;
            }
        }
        // 2) 墨水屏桌面：海信 / Onyx Boox / 文石 / Meebook 等
        String[] pkgs = {
                "com.hmct.einklauncher", "com.onyx", "com.onyx.android",
                "com.boox.boox", "com.meebook.launcher", "com.ireader.launcher"
        };
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            for (String p : pkgs) {
                try {
                    pm.getPackageInfo(p, 0);
                    log("eink by package " + p);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String sysProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 采样结果：中间像素（判断空/实心）+ 圆角半径 */
    private static final class Sample {
        int centerColor;
        int centerAlpha;
        int radius;
    }

    /** 把背景 drawable 画到一张小图上量尺寸；失败返回 null（调用方走默认值） */
    private static Sample sample(Drawable d, View src) {
        int w = src.getWidth();
        int h = src.getHeight();
        final boolean sized = w > 0 && h > 0;
        if (!sized) {
            w = 160;
            h = 96;
        }
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(bmp));
            Sample s = new Sample();
            int center = bmp.getPixel(w / 2, h / 2);
            s.centerColor = center;
            s.centerAlpha = (center >>> 24) & 0xFF;
            if (sized) {
                s.radius = cornerRadius(bmp);
            }
            return s;
        } catch (Throwable t) {
            log("sample background failed: " + t);
            return null;
        } finally {
            if (bmp != null) {
                try {
                    bmp.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 上边/左边第一个不透明像素的偏移量 ≈ 圆角半径 */
    private static int cornerRadius(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int x0 = -1;
        int y0 = -1;
        for (int x = 0; x < w; x++) {
            if (((b.getPixel(x, 0) >>> 24) & 0xFF) > 32) {
                x0 = x;
                break;
            }
        }
        for (int y = 0; y < h; y++) {
            if (((b.getPixel(0, y) >>> 24) & 0xFF) > 32) {
                y0 = y;
                break;
            }
        }
        if (x0 <= 0 || y0 <= 0) {
            return 0;
        }
        return Math.min(Math.max(x0, y0), h / 2);
    }

    /** 只用来判断原生文字是「深底浅字」还是「浅底深字」，不需要严格公式 */
    private static float luminance(int color) {
        float r = (color >> 16) & 0xFF;
        float g = (color >> 8) & 0xFF;
        float b = color & 0xFF;
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
    }

    private static GradientDrawable roundStroke(int radius, int stroke, int color, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setStroke(stroke, color);
        g.setColor(fill);
        return g;
    }

    // ---------------------------------------------------------------- click

    private static void onWellFamiliar(View v) {
        try {
            Activity activity = activityOf(v);
            if (activity == null) {
                toast(v, "未找到 Activity");
                return;
            }
            java.util.ArrayList<Object> candidates = new java.util.ArrayList<Object>();
            Object found = findFragment(v, activity);
            if (found != null) {
                candidates.add(found);
            }
            Object captured = sFragment;
            if (captured != null && !candidates.contains(captured)) {
                candidates.add(captured);
            }
            if (candidates.isEmpty()) {
                toast(v, "未找到背单词页面");
                log("no fragment candidate");
                return;
            }

            Throwable last = null;
            for (Object fragment : candidates) {
                int value = wellFamiliarValue(fragment);
                Method x0 = findMethod(fragment.getClass(), "X0", int.class, boolean.class);
                if (x0 == null) {
                    continue;
                }
                try {
                    x0.setAccessible(true);
                    x0.invoke(fragment, Integer.valueOf(value), Boolean.FALSE);
                    log("X0(" + value + ", false) on " + fragment.getClass().getName());
                    return;
                } catch (Throwable t) {
                    last = t;
                    log("X0 failed on " + fragment.getClass().getName() + ": " + t);
                }
            }
            for (Object fragment : candidates) {
                Method g0 = findStaticWithInstance(fragment.getClass(), "G0", fragment);
                if (g0 == null) {
                    continue;
                }
                try {
                    g0.setAccessible(true);
                    g0.invoke(null, fragment, Boolean.TRUE, Boolean.TRUE);
                    log("G0 on " + fragment.getClass().getName());
                    return;
                } catch (Throwable t) {
                    last = t;
                    log("G0 failed: " + t);
                }
            }
            toast(v, "熟知调用失败: " + (last == null ? "无匹配方法" : last.toString()));
            log("all candidates failed, last=" + last);
        } catch (Throwable t) {
            log("onWellFamiliar error: " + t);
            toast(v, "熟知异常: " + t);
        }
    }

    private static int wellFamiliarValue(Object fragment) {
        try {
            Class<?> rr = Class.forName("com.maimemo.android.momo.revision.ReviewResponse",
                    false, fragment.getClass().getClassLoader());
            Object[] constants = rr.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    if ("WELL_FAMILIAR".equals(((Enum<?>) c).name())) {
                        return rr.getField("value").getInt(c);
                    }
                }
            }
        } catch (Throwable t) {
            log("read ReviewResponse failed, use fallback: " + t);
        }
        return WELL_FAMILIAR_FALLBACK;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 找 G0(ReviewFragment, boolean, boolean) 这类第一个参数是实例本身的静态方法 */
    private static Method findStaticWithInstance(Class<?> cls, String name, Object instance) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3 && ps[0].isInstance(instance)
                        && ps[1] == boolean.class && ps[2] == boolean.class) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 通过 hook 捕获；捕获不到时用 duck typing 兜底扫描（androidx 被整体混淆） */
    private static Object findFragment(View v, Activity activity) {
        Object frag = sFragment;
        View root = sFragmentRoot;
        if (frag != null && (root == null || isAncestor(root, v))) {
            return frag;
        }
        return scanFragment(activity, v);
    }

    private static boolean isAncestor(View ancestor, View child) {
        View cur = child;
        while (cur != null) {
            if (cur == ancestor) {
                return true;
            }
            Object p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private static Object scanFragment(Activity activity, View v) {
        try {
            Object fm = activity.getClass().getMethod("getSupportFragmentManager").invoke(activity);
            List<?> fragments = anyList(fm);
            if (fragments == null) {
                log("no fragment list method found");
                return null;
            }
            View cur = v;
            while (cur != null) {
                for (Object f : fragments) {
                    if (anyView(f) == cur) {
                        return f;
                    }
                }
                Object p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable t) {
            log("scanFragment failed: " + t);
        }
        return null;
    }

    /** 找到「无参、返回 List、元素有 View getter」的方法，即 getFragments() 的混淆版本 */
    private static List<?> anyList(Object fm) {
        for (Method m : fm.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || !List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                Object r = m.invoke(fm);
                if (r instanceof List) {
                    List<?> list = (List<?>) r;
                    if (!list.isEmpty()) {
                        Object first = list.get(0);
                        if (first != null && anyView(first) != null) {
                            log("fragment list via " + m.getName());
                            return list;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 找到「无参、返回 View」的方法，即 getView() 的混淆版本 */
    private static View anyView(Object f) {
        for (Method m : f.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || !View.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                Object r = m.invoke(f);
                if (r instanceof View) {
                    return (View) r;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void tryInstallFragmentHooks(ClassLoader cl) {
        if (sFragmentHooked) {
            return;
        }
        synchronized (FRAG_LOCK) {
            if (sFragmentHooked) {
                return;
            }
            boolean any = false;
            for (String name : FRAGMENT_CLASSES) {
                try {
                    final Class<?> c = Class.forName(name, false, cl);
                    XposedBridge.hookAllMethods(c, "onViewCreated", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args != null && p.args.length > 0 && p.args[0] instanceof View) {
                                sFragment = p.thisObject;
                                sFragmentRoot = (View) p.args[0];
                                log("fragment captured: " + c.getName());
                            }
                        }
                    });
                    any = true;
                    log("fragment hook installed: " + name);
                } catch (Throwable t) {
                    log("fragment hook failed: " + name + " -> " + t);
                }
            }
            sFragmentHooked = any;
        }
    }

    private static Activity activityOf(View v) {
        Context c = v.getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static void toast(View v, String msg) {
        if (!DEBUG) {
            return;
        }
        try {
            Toast.makeText(v.getContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static void log(String msg) {
        if (DEBUG) {
            XposedBridge.log(TAG + msg);
        }
    }
}
