package yhjmew.no.ore.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * 应用语言选择。
 *
 * <p>原先 MainActivity 里是这么判断的：
 * <pre>
 *   String lang = Locale.getDefault().getLanguage();
 *   if (!"zh".equals(lang)) { 强制切到 en }
 * </pre>
 * 只按 language 判断、不看 country，所以：
 * <ul>
 *   <li>zh-TW / zh-HK / zh-MO / zh-Hant 一律被当成 "zh"，走到 values-zh（简体）；</li>
 *   <li>繁体资源本身也不存在（values-zh-rTW / values-zh-rHK 缺失）。</li>
 * </ul>
 * 现在资源补齐了，选择逻辑也改成"语言 + 地区"判断，并允许用户在关于页手动指定。
 */
final class AppLocale {

    private static final String PREFS = "AppLocale";
    private static final String KEY_MODE = "mode";

    /** 跟随系统 */
    static final int MODE_SYSTEM = 0;
    /** 简体中文 */
    static final int MODE_ZH_CN = 1;
    /** 繁體中文（台灣） */
    static final int MODE_ZH_TW = 2;
    /** 繁體中文（香港） */
    static final int MODE_ZH_HK = 3;
    /** English */
    static final int MODE_EN = 4;

    private AppLocale() {
    }

    static int getMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, MODE_SYSTEM);
    }

    static void setMode(Context context, int mode) {
        SharedPreferences.Editor e =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putInt(KEY_MODE, mode);
        e.apply();
    }

    /**
     * 解析出当前应当使用的 Locale。
     *
     * <p>注意：手动选择时返回的是**显式 Locale**；跟随系统时返回 null，
     * 表示"不要覆盖"，让系统自己按区域挑资源。
     */
    static Locale resolve(Context context) {
        switch (getMode(context)) {
            case MODE_ZH_CN: return Locale.SIMPLIFIED_CHINESE;
            case MODE_ZH_TW: return Locale.TRADITIONAL_CHINESE;
            case MODE_ZH_HK: return new Locale("zh", "HK");
            case MODE_EN:    return Locale.ENGLISH;
            case MODE_SYSTEM:
            default:         return null;
        }
    }

    /**
     * 给资源套用语言。
     *
     * <p>跟随系统时**不清空**系统 Locale，只确保繁简归属正确：
     * 系统给的是 zh-TW/zh-HK 时要真正落到 values-zh-rTW / values-zh-rHK。
     */
    static void apply(Context context) {
        Locale target = resolve(context);
        if (target == null) {
            target = normalizeSystemLocale(Locale.getDefault());
        }

        Locale.setDefault(target);
        Configuration config = context.getResources().getConfiguration();
        config.setLocale(target);
        context.getResources().updateConfiguration(config,
                context.getResources().getDisplayMetrics());
    }

    /**
     * 把系统的 zh 变体归一到我们实际提供的资源。
     *
     * <p>系统可能给 zh-Hant-TW / zh-Hant-HK / zh-Hant / zh-TW / zh-HK / zh-MO。
     * Android 的资源匹配是按 language-region 逐级回退的，但 values-zh-rTW
     * 只认 region=TW；所以这里显式把"Hant + 地区"折成具体的 zh-rTW / zh-rHK。
     * 其它 zh 变体（含 zh-Hans、zh-CN、zh-SG）一律按简体处理。
     */
    private static Locale normalizeSystemLocale(Locale sys) {
        String lang = sys.getLanguage();
        if (!"zh".equals(lang)) {
            return sys;
        }

        String country = sys.getCountry();          // 可能为空
        String script = getScriptCompat(sys);       // API 21+ 才有 getScript()

        boolean traditional =
                "Hant".equalsIgnoreCase(script)
                        || "HK".equalsIgnoreCase(country)
                        || "TW".equalsIgnoreCase(country)
                        || "MO".equalsIgnoreCase(country);

        if (!traditional) {
            return Locale.SIMPLIFIED_CHINESE;
        }

        // 香港/澳门归港版，其余繁体归台湾版
        if ("HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
            return new Locale("zh", "HK");
        }
        return Locale.TRADITIONAL_CHINESE;
    }

    /** Locale#getScript() 是 API 21 才有，低版本返回空串。 */
    private static String getScriptCompat(Locale locale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String s = locale.getScript();
            return s == null ? "" : s;
        }
        return "";
    }

    /** 当前模式对应的显示名资源 id。 */
    static int modeLabelRes(int mode) {
        switch (mode) {
            case MODE_ZH_CN: return R.string.btn_language_zh_cn;
            case MODE_ZH_TW: return R.string.btn_language_zh_tw;
            case MODE_ZH_HK: return R.string.btn_language_zh_hk;
            case MODE_EN:    return R.string.btn_language_en;
            case MODE_SYSTEM:
            default:         return R.string.btn_language_follow_system;
        }
    }
}
