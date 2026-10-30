package yhjmew.no.ore.ui;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 游戏安装包下载器：多段并行 + 断点续传 + 断线重试 + 前台通知。
 *
 * <p>为什么要多线：OpenList 那条直链单连接实测只有几百 KB/s，
 * 一个 700MB 的包要下半小时。它支持 {@code Range}，所以拆成 4 段
 * 并行拉，各自写各自的 .partN 文件，最后按顺序拼成一个 .apk。
 *
 * <p>为什么用 .partN 而不是往同一个文件的不同偏移写：那样一旦中途退出，
 * 文件长度就已经是总长了，"下完没有"没法判断，也没法单独续传某一段。
 * 分文件之后每段各续各的，进度就是各段长度之和，崩在哪都说得清。
 *
 * <p>重试是必须的：用户切到后台或锁屏时，部分 ROM 会掐断 socket，
 * 表现为 {@code Software caused connection abort} / Read timed out。
 * 这不是致命错误 —— 段文件还在，已经写入的字节数也还在，
 * 重新连上从断点继续就行。
 */
final class GameDownloader {

    private static final int MAX_RETRY = 5;

    /**
     * 段文件最多铺到这么多个（.part0 ~ .part15）。
     *
     * <p>实际用几条由 {@link DownloadConfig#getThreads} 决定，用户可以改。
     * 这里只是"要清理/统计多少条残片"的上界 —— 用户以前开过 16，
     * 后来又改回 4，那些 .part8 之类的残片仍然要能找出来删掉。
     */
    private static final int MAX_SEGMENTS = DownloadConfig.MAX_THREADS;

    /** 小于这个体积不值得拆段（拆了反而多几次握手） */
    private static final long SEG_MIN_TOTAL = 8L * 1024 * 1024;

    private static final int NOTIF_BASE = 2100;
    static final String CHANNEL_ID = "game_download";

    /** 通知里点了暂停/继续时带的版本号 */
    static final String EXTRA_VERSION = "yhjmew.no.ore.ui.DL_VERSION";

    /** 公共下载目录下的子文件夹名 */
    private static final String PUBLIC_SUBDIR = "Minecraft_oreUI";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * 下载目录：**公共** Download/Minecraft_oreUI/
     *
     * <p>原来放在 Android/data/<包名>/files/Download/games/，
     * 那是应用私有目录 —— 卸载应用就没了，用文件管理器也不好找，
     * 更没法直接丢给别的工具。
     *
     * <p>现在改到 /storage/emulated/0/Download/Minecraft_oreUI/。
     * 写公共目录需要「所有文件访问权限」，主界面两个处理按钮
     * 本来就会申请它，这里只需要在没权限时回退到私有目录兜底。
     */
    static File dir(Context ctx) {
        File pub = publicDir();
        if (pub != null) {
            if (!pub.exists()) {
                pub.mkdirs();
            }
            if (pub.isDirectory() && pub.canWrite()) {
                return pub;
            }
        }
        // 没有权限时兜底：私有目录至少能下，界面会提示实际路径
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) {
            // 外部存储都没挂载，退到内部存储
            base = new File(ctx.getFilesDir(), "games");
        } else {
            base = new File(base, "games");
        }
        if (!base.exists()) {
            base.mkdirs();
        }
        return base;
    }

    /** 公共下载目录；拿不到（无权限/存储未挂载）返回 null */
    static File publicDir() {
        try {
            File root = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (root == null) {
                return null;
            }
            return new File(root, PUBLIC_SUBDIR);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否真的用上了公共目录（界面据此提示权限） */
    static boolean usingPublicDir(Context ctx) {
        return dir(ctx).getAbsolutePath().startsWith(
                Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/");
    }

    /** 给用户看的路径 */
    static String displayDir(Context ctx) {
        File d = dir(ctx);
        String p = d.getAbsolutePath();
        int idx = p.indexOf("/Android/data/");
        if (idx > 0) {
            return p.substring(idx + 1);
        }
        return p;
    }

    /** 版本号 → 文件名，如 1.26.60.4 → Minecraft_1.26.60.4_v8a.apk */
    static String fileNameFor(String versionAll) {
        String v = versionAll == null ? "unknown" : versionAll.replaceAll("[^0-9A-Za-z._-]", "_");
        return "Minecraft_" + v + "_v8a.apk";
    }

    static File targetFile(Context ctx, String versionAll) {
        return new File(dir(ctx), fileNameFor(versionAll));
    }

    /** 第 i 段写到哪。最终文件旁边放 .part0 / .part1 ... */
    private static File partFile(File target, int index) {
        return new File(target.getParentFile(), target.getName() + ".part" + index);
    }

    /** 只有整包拼好了才算「下完了」—— .part 文件一律不算 */
    static boolean isDownloaded(Context ctx, String versionAll) {
        File f = targetFile(ctx, versionAll);
        return f.isFile() && f.length() > 0;
    }

    /** 已落盘的字节数：成品 + 各段之和 */
    static long downloadedBytes(Context ctx, String versionAll) {
        File target = targetFile(ctx, versionAll);
        long n = target.isFile() ? target.length() : 0;
        if (n > 0) {
            return n;
        }
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            File p = partFile(target, i);
            if (p.isFile()) {
                n += p.length();
            }
        }
        // 不统计 .tmp：那是拼接中的半成品，算进来会让进度条突然翻倍
        return n;
    }

    /** 删掉某个版本的所有痕迹（成品、分段、临时文件） */
    static boolean delete(Context ctx, String versionAll) {
        File target = targetFile(ctx, versionAll);
        boolean ok = true;
        if (target.exists() && !target.delete()) {
            ok = false;
        }
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            File p = partFile(target, i);
            if (p.exists() && !p.delete()) {
                ok = false;
            }
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (tmp.exists() && !tmp.delete()) {
            ok = false;
        }
        return ok;
    }

    static File[] listAll(Context ctx) {
        File[] fs = dir(ctx).listFiles();
        return fs == null ? new File[0] : fs;
    }

    // ---------------- 任务管理 ----------------

    /** 所有任务，key 是版本号。静态的，界面重建也能拿到。 */
    // 用 ConcurrentHashMap：Collections.synchronizedMap 在旧 Android 上不可用，
    // 而下载线程和 UI 线程会同时读写这个表
    private static final java.util.Map<String, Task> RUNNING =
            new java.util.concurrent.ConcurrentHashMap<String, Task>();

    /** 主线程投递：保证「撤前台通知」先执行，紧接着才是新通知 */
    private static void postToMain(Runnable r) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        } catch (Throwable ignore) {
        }
    }

    private static void postDoneNotification(final Context ctx, final String version,
                                             final long size) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                showDoneNotification(ctx, version, size);
            }
        });
    }

    private static void postFailedNotification(final Context ctx, final String version,
                                               final String reason) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                showFailedNotification(ctx, version, reason);
            }
        });
    }

    /**
     * 还有没跑完的任务就别停服务。
     *
     * <p>停的时候用 {@code stopKeepNotification}：通知里还有「继续」可点，
     * 或者刚下完要告诉用户，直接撤掉就什么都看不见了。
     */
    private static void maybeStopService(Context ctx) {
        if (!hasActive()) {
            DownloadService.stopKeepNotification(ctx);
        }
    }

    /** 在跑，或者暂停着还没被丢弃 —— 这两种都得留着服务 */
    static boolean hasActive() {
        for (Task t : RUNNING.values()) {
            if (t != null && (!t.finished || (t.paused && !t.cancelled))) {
                return true;
            }
        }
        return false;
    }

    /** 挑一个还在跑的任务，给重启后的前台服务用 */
    static String firstActiveVersion() {
        for (java.util.Map.Entry<String, Task> e : RUNNING.entrySet()) {
            Task t = e.getValue();
            if (t != null && !t.finished) {
                return e.getKey();
            }
        }
        return null;
    }

    static boolean isRunning(String versionAll) {
        Task t = RUNNING.get(versionAll);
        return t != null && !t.finished;
    }

    static Task taskOf(String versionAll) {
        return RUNNING.get(versionAll);
    }

    /** 只读快照，供下载页遍历。别在这个 Map 上直接增删。 */
    static java.util.Map<String, Task> allTasks() {
        return new java.util.HashMap<String, Task>(RUNNING);
    }

    /** 只删自己那一份，别把刚接手的新任务顺手删了 */
    private static void removeIfSame(String versionAll, Task task) {
        if (RUNNING.get(versionAll) == task) {
            RUNNING.remove(versionAll);
        }
    }

    /** 一次下载任务的句柄 */
    static final class Task {
        final String version;
        volatile String url;

        volatile boolean cancelled;
        volatile boolean paused;
        volatile boolean finished;

        /** 服务器不认 Range（多段拿到的都是整包），本轮要降级重来 */
        volatile boolean rangeUnsupported = false;

        /** 最近一次进度，界面重建时用来立刻显示，不用等下一次回调 */
        volatile long lastDownloaded;
        volatile long lastTotal;

        /** 本次实际开了几段（服务器不认 Range 时是 1） */
        volatile int segments = 1;

        /** 版本列表给的整包大小（探测失败时当估算值用），-1 表示没有 */
        volatile long expectedTotal = -1;

        /** 这个任务用几条连接（开始时从设置里取一次，中途改设置不影响它） */
        final int threads;

        /** 总量是估的还是探出来的 —— 界面据此决定要不要加「约」 */
        volatile boolean totalEstimated = false;

        private final List<HttpURLConnection> conns =
                java.util.Collections.synchronizedList(new ArrayList<HttpURLConnection>());

        Task(String version, int threads) {
            this.version = version;
            this.threads = DownloadConfig.clamp(threads);
        }

        void addConn(HttpURLConnection c) {
            if (c != null) {
                conns.add(c);
            }
        }

        void removeConn(HttpURLConnection c) {
            try {
                conns.remove(c);
            } catch (Exception ignore) {
            }
        }

        void pause() {
            paused = true;
            abort();
        }

        void cancel() {
            cancelled = true;
            abort();
        }

        /** 把还挂着的连接全部掐掉，read() 才会立刻返回而不是等超时 */
        void abort() {
            List<HttpURLConnection> copy;
            synchronized (conns) {
                copy = new ArrayList<HttpURLConnection>(conns);
            }
            for (HttpURLConnection c : copy) {
                try {
                    c.disconnect();
                } catch (Exception ignore) {
                }
            }
        }

        int percent() {
            if (lastTotal <= 0) return 0;
            long p = lastDownloaded * 100 / lastTotal;
            return (int) Math.max(0, Math.min(100, p));
        }
    }

    interface Listener {
        void onProgress(long downloaded, long total);
        void onLog(String message);
        void onDone(File file);
        void onError(String message);
        void onPaused(long downloaded);
        void onRetry(int attempt, int max, String reason);
    }

    /** 不需要界面的时候用，比如从通知栏续传 */
    static final class SilentListener implements Listener {
        private final Context ctx;
        private final String version;

        SilentListener(Context ctx, String version) {
            this.ctx = ctx;
            this.version = version;
        }

        @Override
        public void onProgress(long downloaded, long total) {
        }

        @Override
        public void onLog(String message) {
        }

        @Override
        public void onDone(File file) {
        }

        @Override
        public void onError(String message) {
        }

        @Override
        public void onPaused(long downloaded) {
        }

        @Override
        public void onRetry(int attempt, int max, String reason) {
        }
    }

    /**
     * 开始（或继续）下载。返回值可以拿来暂停/取消。
     */
    static Task download(final Context ctx, final String url, final String versionAll,
                         final Listener listener) {
        return download(ctx, url, versionAll, -1, listener);
    }

    /**
     * @param expectedTotal 版本列表里给的整包大小。探测拿不到 Content-Length 时
     *                      先拿它顶着，百分比才有得算；真下起来拿到
     *                      Content-Range 就换成真值。
     */
    static Task download(final Context ctx, final String url, final String versionAll,
                         long expectedTotal, final Listener listener) {
        final Task task = new Task(versionAll, DownloadConfig.getThreads(ctx));
        task.url = url;
        task.expectedTotal = expectedTotal > 0 ? expectedTotal : -1;

        final Task prev = RUNNING.put(versionAll, task);
        createChannel(ctx);
        // 注意：这里**不能**删已有的 .apk。
        // 下载过程中一直是写 .partN，成品只在最后拼接时才落盘（先写 .tmp 再改名），
        // 所以旧包在这段时间里还是好的 —— 新的一旦失败，用户手上的包还在。
        DownloadService.start(ctx, versionAll);
        showNotification(ctx, task, false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 上一个同版本任务（多半是刚点了暂停的那次）得先等它把连接关干净，
                // 否则两个线程会同时往同一批 .part 文件里写
                waitForQuiet(prev);

                int attempt = 0;
                String lastError = null;

                while (attempt <= MAX_RETRY) {
                    if (task.cancelled || task.paused) {
                        break;
                    }

                    try {
                        if (runOnce(ctx, task, listener)) {
                            task.finished = true;
                            removeIfSame(versionAll, task);
                            File out = targetFile(ctx, versionAll);
                            // 顺序很重要：服务前台那条通知现在挂的文案是
                            // 「正在合并文件…」。必须**先**把它撤干净，再发完成通知，
                            // 否则两个线程抢同一个 id，很容易停在合并那条上不走。
                            // 两次操作都投到主线程，保证先撤后发。
                            DownloadService.stop(ctx);
                            postDoneNotification(ctx, versionAll, out.length());
                            listener.onDone(out);
                            return;
                        }
                        // runOnce 返回 false：中途被暂停/取消
                        finishAborted(ctx, task, listener);
                        return;
                    } catch (Exception e) {
                        lastError = e.getMessage();
                        if (task.paused || task.cancelled) {
                            finishAborted(ctx, task, listener);
                            return;
                        }
                    }

                    attempt++;
                    if (attempt > MAX_RETRY) {
                        break;
                    }

                    // 退避：1s, 2s, 4s, 8s, 16s
                    int wait = 1000 << Math.min(attempt - 1, 4);
                    listener.onRetry(attempt, MAX_RETRY, lastError == null ? "" : lastError);
                    if (!sleepMs(wait)) {
                        finishAborted(ctx, task, listener);
                        return;
                    }
                }

                task.finished = true;
                removeIfSame(versionAll, task);
                if (!task.paused && !task.cancelled) {
                    String msg = lastError == null ? "unknown" : lastError;
                    DownloadService.stop(ctx);
                    postFailedNotification(ctx, versionAll, msg);
                    listener.onError(msg);
                } else {
                    cancelNotification(ctx, versionAll);
                    DownloadService.stop(ctx);
                }
            }
        }).start();

        return task;
    }

    /**
     * 继续一个暂停中的任务。
     *
     * <p>刚点过暂停时下载线程还在收尾（连接要一个个断、段线程要退），
     * 这时候直接开新任务会和它抢同一批 .part 文件。所以先等它 finished，
     * 再接手 —— 用户体感上就是「点了就继续」，中间最多几十毫秒。
     *
     * @return false 表示已经没有这个任务了（进程被杀过），界面该重新拿直链
     */
    static boolean resumeTask(final Context ctx, final String versionAll, final Listener listener) {
        final Task t = RUNNING.get(versionAll);
        if (t == null || t.url == null) {
            return false;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = SystemClock.elapsedRealtime() + 5000;
                while (!t.finished && SystemClock.elapsedRealtime() < deadline) {
                    if (!sleepMs(50)) {
                        return;
                    }
                }
                if (!t.finished) {
                    // 5 秒还没收尾，别硬来
                    if (listener != null) {
                        listener.onError("still busy");
                    }
                    return;
                }
                download(ctx, t.url, versionAll, listener != null
                        ? listener : new SilentListener(ctx, versionAll));
            }
        }).start();
        return true;
    }

    private static void finishAborted(Context ctx, Task task, Listener listener) {
        task.finished = true;
        // 暂停的任务留在表里：界面要显示「继续」，续传还要用它的 url
        if (task.cancelled) {
            removeIfSame(task.version, task);
            cancelNotification(ctx, task.version);
            DownloadService.stop(ctx);
            return;
        }
        if (task.paused) {
            long done = task.lastDownloaded;
            // 暂停后**继续**占着前台：通知栏那个「继续」得有人接。
            // 一停服务，进程随时会被回收，任务句柄没了，按钮就成了摆设；
            // 而且 Android 12+ 也不允许从后台再拉一个前台服务起来。
            // 通知标题会变成「已暂停」，按钮换成「继续」。
            showNotification(ctx, task, true);
            DownloadService.keepForeground(ctx, task.version, true);
            listener.onPaused(done);
        }
    }

    private static void waitForQuiet(Task prev) {
        if (prev == null || prev.finished) {
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (!prev.finished && SystemClock.elapsedRealtime() < deadline) {
            if (!sleepMs(50)) {
                return;
            }
        }
    }

    /** @return false 表示被打断（暂停/取消），要立刻收手 */
    private static boolean sleepMs(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ignore) {
            return false;
        }
    }

    // ---------------- 下载本体 ----------------

    /**
     * 一轮完整尝试。
     *
     * @return true 表示整包已经拼好；false 表示被暂停/取消
     */
    private static SegmentIo.Probe probe(Task task) {
        return SegmentIo.probe(task.url, UA, new SegmentIo.ConnHook() {
            @Override
            public void onOpen(HttpURLConnection conn) {
                task.addConn(conn);
            }

            @Override
            public void onClose(HttpURLConnection conn) {
                task.removeConn(conn);
            }
        }, new SegmentIo.CancelCheck() {
            @Override
            public boolean cancelled() {
                return task.cancelled || task.paused;
            }
        });
    }

    private static boolean runOnce(Context ctx, Task task, Listener listener) throws Exception {
        SegmentIo.Probe probe = probe(task);
        long total = probe.total;

        // 探测失败（多半是被限流打掉）：版本列表里的大小先拿来当"估算总量"，
        // 只用于显示 —— **不能**拿它去切分段，切错了就下不完。
        if (total <= 0 && task.expectedTotal > 0) {
            task.totalEstimated = true;
            task.lastTotal = task.expectedTotal;
        }
        // 用户设定的线程数；服务器不认 Range 或包太小就退回单段
        int threads = DownloadConfig.clamp(task.threads);
        int segs = (probe.ranges && total >= SEG_MIN_TOTAL) ? threads : 1;

        if (total > 0) {
            task.lastTotal = total;
        }

        while (true) {
            try {
                if (!runPass(ctx, task, listener, total, segs)) {
                    return false;
                }
                break;
            } catch (SegmentIo.RangeUnsupported ru) {
                if (segs <= 1) {
                    throw new Exception("server does not support range requests");
                }
                // 服务器把带 Range 的请求当整包给了：清掉分段落单段重下
                listener.onLog(ctx.getString(R.string.log_dl_no_resume));
                clearParts(ctx, task.version);
                segs = 1;
                total = probe.total;
            } catch (SegmentIo.SizeDiscovered sd) {
                // 一开始不知道总长，下的过程中从 Content-Range 里补到了。
                // 那一刻分段的划分是按"未知"算的（只有一段），所以要按真实
                // 长度重划 —— 重划之后每段各续各的，已经下的字节不会浪费。
                total = sd.total;
                task.totalEstimated = false;
                listener.onLog(ctx.getString(R.string.log_dl_size_found,
                        GameDownloader.fmt(total)));

                // 已经落盘的 part0 是整包的前缀：只要没超过第一段的长度，
                // 就能直接当段 0 的起点，一个字节都不浪费；超了就只能
                // 保持单段往下续（进度照样准，只是没并行）。
                long have = partFile(targetFile(ctx, task.version), 0).length();
                int want = (total >= SEG_MIN_TOTAL) ? DownloadConfig.clamp(task.threads) : 1;
                if (want > 1 && have > SegmentIo.spanLength(total, want, 0)) {
                    want = 1;
                }
                segs = want;
            }
        }

        task.segments = segs;

        // 拼包：.part0 + .part1 + ... -> .apk
        File target = targetFile(ctx, task.version);
        File[] parts = new File[segs];
        for (int i = 0; i < segs; i++) {
            parts[i] = partFile(target, i);
        }
        if (total > 0) {
            task.lastTotal = total;
        }
        showMergingNotification(ctx, task.version, 0);
        try {
            if (!mergeParts(ctx, task, target, parts, total)) {
                return false;
            }
        } catch (Exception e) {
            // 分段本身有问题（长度不对/写入出错），留着它们下一轮会立刻"完成"
            clearParts(ctx, task.version);
            throw e;
        }
        task.lastDownloaded = total > 0 ? total : task.lastDownloaded;
        task.totalEstimated = false;
        return true;
    }

    private static void clearParts(Context ctx, String version) {
        File target = targetFile(ctx, version);
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            File p = partFile(target, i);
            if (p.exists()) {
                p.delete();
            }
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (tmp.exists()) {
            tmp.delete();
        }
    }

    /**
     * 开 segs 个线程并行拉，主线程负责汇总进度、响应暂停。
     */
    private static boolean runPass(final Context ctx, final Task task, final Listener listener,
                                   final long total, final int segs) throws Exception {
        final File target = targetFile(ctx, task.version);
        task.segments = segs;

        final long[] starts = new long[segs];
        final long[] lens = new long[segs];
        final File[] parts = new File[segs];
        final AtomicLong[] done = new AtomicLong[segs];

        // 这次是单段的话，之前多段留下的残片要清掉，否则进度会虚高
        if (segs == 1) {
            for (int i = 1; i < MAX_SEGMENTS; i++) {
                File extra = partFile(target, i);
                if (extra.exists()) {
                    extra.delete();
                }
            }
        }

        for (int i = 0; i < segs; i++) {
            starts[i] = SegmentIo.spanStart(total, segs, i);
            lens[i] = SegmentIo.spanLength(total, segs, i);

            parts[i] = partFile(target, i);
            // 段文件比这一段该有的还长：包换过或者写脏了，重下这一段
            if (lens[i] >= 0 && parts[i].isFile() && parts[i].length() > lens[i]) {
                parts[i].delete();
            }
            long have = parts[i].isFile() ? parts[i].length() : 0;
            done[i] = new AtomicLong(have);
        }

        final AtomicReference<Exception> err = new AtomicReference<Exception>();
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Long> found = new AtomicReference<Long>();
        final CountDownLatch latch = new CountDownLatch(segs);

        final SegmentIo.CancelCheck cancel = new SegmentIo.CancelCheck() {
            @Override
            public boolean cancelled() {
                return task.cancelled || task.paused || stop.get();
            }
        };
        final SegmentIo.ConnHook hook = new SegmentIo.ConnHook() {
            @Override
            public void onOpen(HttpURLConnection conn) {
                task.addConn(conn);
            }

            @Override
            public void onClose(HttpURLConnection conn) {
                task.removeConn(conn);
            }
        };

        for (int i = 0; i < segs; i++) {
            final int idx = i;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int tries = 0;
                        while (true) {
                            if (task.cancelled || task.paused || stop.get()) {
                                return;
                            }
                            try {
                                SegmentIo.fetch(task.url, UA, parts[idx], starts[idx],
                                        lens[idx], done[idx], segs > 1, cancel, hook,
                                        total > 0);
                                return;
                            } catch (SegmentIo.RangeUnsupported ru) {
                                stop.set(true);
                                task.rangeUnsupported = true;
                                return;
                            } catch (SegmentIo.SizeDiscovered sd) {
                                stop.set(true);
                                found.compareAndSet(null, Long.valueOf(sd.total));
                                return;
                            } catch (Exception e) {
                                if (task.cancelled || task.paused || stop.get()) {
                                    return;
                                }
                                tries++;
                                if (tries > 3) {
                                    err.compareAndSet(null, e);
                                    stop.set(true);
                                    return;
                                }
                                if (!sleepMs(1000L << Math.min(tries - 1, 3))) {
                                    return;
                                }
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            }, "dl-seg-" + i).start();
        }

        long lastTick = 0;
        while (true) {
            if (task.cancelled || task.paused) {
                stop.set(true);
                task.abort();
                return false;
            }
            if (task.rangeUnsupported) {
                stop.set(true);
                task.abort();
                latch.await(3, TimeUnit.SECONDS);
                throw new SegmentIo.RangeUnsupported();
            }
            if (found.get() != null) {
                stop.set(true);
                task.abort();
                latch.await(3, TimeUnit.SECONDS);
                throw new SegmentIo.SizeDiscovered(found.get().longValue());
            }
            Exception e = err.get();
            if (e != null) {
                task.abort();
                break;
            }

            long sum = 0;
            for (int i = 0; i < segs; i++) {
                sum += done[i].get();
            }
            task.lastDownloaded = sum;
            task.lastTotal = total > 0 ? total
                    : (task.expectedTotal > 0 ? task.expectedTotal : 0);

            long now = SystemClock.elapsedRealtime();
            if (now - lastTick > 300) {
                listener.onProgress(sum, total);
                showNotification(ctx, task, false);
                lastTick = now;
            }

            if (latch.getCount() == 0) {
                break;
            }
            if (!sleepMs(120)) {
                stop.set(true);
                task.abort();
                return false;
            }
        }

        latch.await(3, TimeUnit.SECONDS);

        if (task.cancelled || task.paused) {
            return false;
        }
        if (task.rangeUnsupported) {
            throw new SegmentIo.RangeUnsupported();
        }
        if (found.get() != null) {
            throw new SegmentIo.SizeDiscovered(found.get().longValue());
        }
        Exception e = err.get();
        if (e != null) {
            throw e;
        }

        long sum = 0;
        for (int i = 0; i < segs; i++) {
            sum += done[i].get();
        }
        if (total > 0 && sum < total) {
            throw new Exception("incomplete " + sum + "/" + total);
        }
        listener.onProgress(sum, total);
        return true;
    }

    /**
     * 按顺序把各段拼成成品，先写 .tmp 再改名，避免半成品被当成下完。
     *
     * <p>700MB 的包光拼接就要十几秒，这段时间也得响应暂停/取消 ——
     * 否则用户点了暂停，进度条还会自己往前爬一大截。
     */
    private static boolean mergeParts(final Context ctx, final Task task, File target,
                                     File[] parts, long total) throws Exception {
        return SegmentIo.merge(target, parts, total, new SegmentIo.CancelCheck() {
            @Override
            public boolean cancelled() {
                return task.cancelled || task.paused;
            }
        }, new SegmentIo.Progress() {
            @Override
            public void onMerged(long merged, long totalBytes) {
                showMergingNotification(ctx, task.version, merged);
            }
        });
    }

    // ---------------- 通知 ----------------

    private static final java.util.Map<String, Integer> NOTIF_IDS =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    /** 没有版本号时（服务刚起来还不知道下哪个）用的通用 id */
    private static final int NOTIF_DEFAULT = NOTIF_BASE;

    /** 每个版本一个通知，同时下两个也能分开暂停 */
    static int notifIdFor(String version) {
        if (version == null || version.length() == 0) {
            return NOTIF_DEFAULT;
        }
        Integer id = NOTIF_IDS.get(version);
        if (id == null) {
            synchronized (NOTIF_IDS) {
                id = NOTIF_IDS.get(version);
                if (id == null) {
                    id = Integer.valueOf(NOTIF_BASE + 1 + NOTIF_IDS.size());
                    NOTIF_IDS.put(version, id);
                }
            }
        }
        return id.intValue();
    }

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.title_download_page),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private static Notification.Builder builder(Context ctx) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
    }

    private static PendingIntent contentIntent(Context ctx) {
        Intent it = new Intent(ctx, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(ctx, 0, it, flags);
    }

    /** 暂停/继续按钮点下去要执行的广播 */
    private static PendingIntent actionIntent(Context ctx, String version, boolean resume) {
        Intent it = new Intent(ctx, DownloadActionReceiver.class);
        it.setAction(resume
                ? DownloadActionReceiver.ACTION_RESUME
                : DownloadActionReceiver.ACTION_PAUSE);
        it.putExtra(EXTRA_VERSION, version);
        int hash = version == null ? 0 : Math.abs(version.hashCode());
        int req = 1000 + (hash % 5000) * 2 + (resume ? 1 : 0);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(ctx, req, it, flags);
    }

    /**
     * 组装那条通知。前台服务启动时也走这里，
     * 保证「前台通知」和「进度通知」是同一条、同一个 id，
     * 用户不会看到两条各说各话的下载通知。
     */
    static Notification buildNotification(Context ctx, String version, boolean paused) {
        Task t = version == null ? null : RUNNING.get(version);
        long done = t == null ? 0 : t.lastDownloaded;
        long total = t == null ? 0 : t.lastTotal;
        return buildNotification(ctx, version, paused, done, total);
    }

    static Notification buildNotification(Context ctx, String version, boolean paused,
                                         long done, long total) {
        int pct = total > 0 ? (int) (done * 100 / total) : 0;

        Task t = version == null ? null : RUNNING.get(version);
        boolean estimated = t != null && t.totalEstimated;

        String text;
        if (version == null) {
            text = ctx.getString(R.string.title_download_page);
        } else {
            String size;
            if (total > 0) {
                size = fmt(done) + " / " + fmt(total);
                if (estimated) {
                    // 估出来的总量加个「约」，下完时不会显示 763/722MB 吓人
                    size = fmt(done) + " / "
                            + ctx.getString(R.string.fmt_about_size, fmt(total));
                }
            } else {
                size = fmt(done);
            }
            text = version + "  " + size;
        }

        Notification.Builder b = builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(ctx.getString(paused
                        ? R.string.msg_dl_notif_paused
                        : R.string.log_downloading))
                .setContentText(text)
                .setOngoing(!paused)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent(ctx))
                // 前台通知必须带 progress，否则某些 ROM 会把它当成无进度的常驻
                .setProgress(100, pct, total <= 0);

        if (Build.VERSION.SDK_INT >= 16 && version != null && !paused) {
            // 很多人不知道通知能展开，这里点一句
            b.setSubText(ctx.getString(R.string.msg_dl_notif_expand_hint));
        }

        if (Build.VERSION.SDK_INT >= 16 && version != null) {
            // 展开后的按钮：在跑给「暂停」，停了给「继续」
            b.addAction(paused ? android.R.drawable.ic_media_play
                            : android.R.drawable.ic_media_pause,
                    ctx.getString(paused ? R.string.btn_resume : R.string.btn_pause),
                    actionIntent(ctx, version, paused));
        }
        return b.build();
    }

    /** 后台也有进度条，展开还能暂停 */
    private static void showNotification(Context ctx, Task task, boolean paused) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(notifIdFor(task.version), buildNotification(ctx, task.version,
                        paused, task.lastDownloaded, task.lastTotal));
            }
        } catch (Throwable ignore) {
        }
    }

    private static void showMergingNotification(Context ctx, String version, long merged) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Notification.Builder b = builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(ctx.getString(R.string.msg_dl_merging))
                    .setContentText(version + "  " + fmt(merged))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(contentIntent(ctx));
            b.setProgress(0, 0, true);
            nm.notify(notifIdFor(version), b.build());
        } catch (Throwable ignore) {
        }
    }

    private static void showDoneNotification(Context ctx, String version, long size) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Notification.Builder b = builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(ctx.getString(R.string.msg_dl_notif_done))
                    .setContentText(version + "  " + fmt(size))
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(ctx));
            nm.notify(notifIdFor(version), b.build());
        } catch (Throwable ignore) {
        }
    }

    private static void showFailedNotification(Context ctx, String version, String reason) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Notification.Builder b = builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(ctx.getString(R.string.msg_dl_notif_failed))
                    .setContentText(version + (reason == null || reason.length() == 0
                            ? "" : "  " + reason))
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(ctx));
            nm.notify(notifIdFor(version), b.build());
        } catch (Throwable ignore) {
        }
    }

    static void cancelNotification(Context ctx, String version) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(notifIdFor(version));
            }
        } catch (Throwable ignore) {
        }
    }

    static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
