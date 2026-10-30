package yhjmew.no.ore.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 下载线程数的读写。
 *
 * <p>为什么单独一个类：这三个数（默认值、上下限、档位）下载器和设置界面
 * 都要用，散在两边迟早对不上。之前 `SEGMENTS = 4` 是常量，用户无从调整。
 */
final class DownloadConfig {

    private static final String PREFS = "DownloadConfig";
    private static final String KEY_THREADS = "threads";

    /** 默认 4 条：实测对 OpenList 够快，再多容易被限速反噬 */
    static final int DEFAULT_THREADS = 4;

    /** 下限 1（等于不分段，服务器不认 Range 时也就是这个数） */
    static final int MIN_THREADS = 1;

    /**
     * 上限 16。再高意义不大：一个包被切成 16 段之后，每段的握手开销
     * 和限流风险都上来了，实测反而慢。
     */
    static final int MAX_THREADS = 16;

    /** 设置里给的预设档位 */
    static final int[] PRESETS = {1, 2, 4, 8, 16};

    private DownloadConfig() {
    }

    static int getThreads(Context ctx) {
        int v = prefs(ctx).getInt(KEY_THREADS, DEFAULT_THREADS);
        return clamp(v);
    }

    static void setThreads(Context ctx, int threads) {
        prefs(ctx).edit().putInt(KEY_THREADS, clamp(threads)).apply();
    }

    static int clamp(int v) {
        if (v < MIN_THREADS) {
            return MIN_THREADS;
        }
        if (v > MAX_THREADS) {
            return MAX_THREADS;
        }
        return v;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 界面上显示成 "4"（预设里有的就直接数字，自定义也显示成数字） */
    static String label(int threads) {
        return String.valueOf(clamp(threads));
    }
}
