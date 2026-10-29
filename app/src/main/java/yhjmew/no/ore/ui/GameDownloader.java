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
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 游戏安装包下载器：断点续传 + 断线重试 + 前台通知。
 *
 * <p>断点续传靠 HTTP Range。OpenList 那条直链实测返回
 * {@code Accept-Ranges: bytes}，带 {@code Range: bytes=N-} 会回 206。
 *
 * <p>重试是必须的：用户切到后台或锁屏时，部分 ROM 会掐断 socket，
 * 表现为 {@code Software caused connection abort} / Read timed out。
 * 这不是致命错误 —— 文件还在，已经写入的字节数也还在，
 * 重新连上从断点继续就行。原来没有重试，一次网络抖动就整个失败。
 */
final class GameDownloader {

    private static final int MAX_RETRY = 5;
    private static final int NOTIF_ID = 2001;
    private static final String CHANNEL_ID = "game_download";

    /** 下载目录：<外置私有目录>/Download/games */
    static File dir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "games");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /** 给用户看的目录路径（界面上要显示出来，否则找不到文件） */
    static String displayDir(Context ctx) {
        File d = dir(ctx);
        String p = d.getAbsolutePath();
        // Android/data/... 这种路径太长，缩一下但保持可辨认
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

    static boolean isDownloaded(Context ctx, String versionAll) {
        File f = targetFile(ctx, versionAll);
        return f.exists() && f.length() > 0 && !isRunning(versionAll);
    }

    static long downloadedBytes(Context ctx, String versionAll) {
        File f = targetFile(ctx, versionAll);
        return f.exists() ? f.length() : 0;
    }

    static boolean delete(Context ctx, String versionAll) {
        File f = targetFile(ctx, versionAll);
        return !f.exists() || f.delete();
    }

    static File[] listAll(Context ctx) {
        File[] fs = dir(ctx).listFiles();
        return fs == null ? new File[0] : fs;
    }

    // ---------------- 任务管理 ----------------

    /** 所有进行中的任务，key 是版本号。静态的，界面重建也能拿到。 */
    private static final java.util.Map<String, Task> RUNNING =
            new java.util.Collections.synchronizedMap(
                    new java.util.HashMap<String, Task>());

    /** 还有别的任务在跑就别停服务 */
    private static void maybeStopService(Context ctx) {
        if (RUNNING.isEmpty()) {
            DownloadService.stop(ctx);
        }
    }

    static boolean isRunning(String versionAll) {
        Task t = RUNNING.get(versionAll);
        return t != null && !t.finished;
    }

    static Task taskOf(String versionAll) {
        return RUNNING.get(versionAll);
    }

    /** 一次下载任务的句柄 */
    static final class Task {
        volatile boolean cancelled;
        volatile boolean paused;
        volatile boolean finished;
        private volatile HttpURLConnection conn;

        /** 最近一次进度，界面重建时用来立刻显示，不用等下一次回调 */
        volatile long lastDownloaded;
        volatile long lastTotal;

        void pause() {
            paused = true;
            abort();
        }

        void cancel() {
            cancelled = true;
            abort();
        }

        private void abort() {
            try {
                if (conn != null) {
                    conn.disconnect();
                }
            } catch (Exception ignore) {
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

    /**
     * 开始（或继续）下载。返回值可以拿来暂停/取消。
     */
    static Task download(final Context ctx, final String url, final String versionAll,
                         final Listener listener) {
        final Task task = new Task();
        final File target = targetFile(ctx, versionAll);

        RUNNING.put(versionAll, task);
        createChannel(ctx);
        DownloadService.start(ctx);

        new Thread(new Runnable() {
            @Override
            public void run() {
                int attempt = 0;
                String lastError = null;

                while (attempt <= MAX_RETRY) {
                    if (task.cancelled || task.paused) {
                        break;
                    }

                    try {
                        boolean complete = downloadOnce(ctx, url, target, task, listener);
                        if (complete) {
                            task.finished = true;
                            RUNNING.remove(versionAll);
                            cancelNotification(ctx);
                            maybeStopService(ctx);
                            listener.onDone(target);
                            return;
                        }
                        if (task.paused) {
                            cancelNotification(ctx);
                            listener.onPaused(target.exists() ? target.length() : 0);
                            return;
                        }
                        if (task.cancelled) {
                            cancelNotification(ctx);
                            return;
                        }
                    } catch (Exception e) {
                        lastError = e.getMessage();
                        if (task.paused) {
                            cancelNotification(ctx);
                            listener.onPaused(target.exists() ? target.length() : 0);
                            return;
                        }
                        if (task.cancelled) {
                            cancelNotification(ctx);
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
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ignore) {
                    }
                }

                task.finished = true;
                RUNNING.remove(versionAll);
                cancelNotification(ctx);
                maybeStopService(ctx);
                if (!task.paused && !task.cancelled) {
                    listener.onError(lastError == null ? "unknown" : lastError);
                }
            }
        }).start();

        return task;
    }

    /**
     * 单次下载尝试。
     *
     * @return true 表示完整下载完成；false 表示中途暂停/取消/连接断了
     */
    private static boolean downloadOnce(Context ctx, String url, File target,
                                        Task task, Listener listener) throws Exception {
        long existing = target.exists() ? target.length() : 0;
        boolean resumed = existing > 0;

        HttpURLConnection conn = null;
        RandomAccessFile raf = null;
        InputStream is = null;

        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            task.conn = conn;
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            if (resumed) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }

            int code = conn.getResponseCode();

            if (resumed && code == HttpURLConnection.HTTP_OK) {
                // 服务器不认 Range，从头给，之前那截作废
                existing = 0;
                resumed = false;
            } else if (code != HttpURLConnection.HTTP_OK
                    && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new Exception("HTTP " + code);
            }

            long contentLength = conn.getContentLength();
            long total = resumed ? existing + contentLength : contentLength;

            task.lastDownloaded = existing;
            task.lastTotal = total;

            raf = new RandomAccessFile(target, "rwd");
            raf.seek(existing);

            is = conn.getInputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            long written = existing;
            long lastTick = 0;

            while ((n = is.read(buf)) != -1) {
                if (task.cancelled || task.paused) {
                    return false;
                }
                raf.write(buf, 0, n);
                written += n;
                task.lastDownloaded = written;

                long now = SystemClock.elapsedRealtime();
                if (now - lastTick > 300) {
                    listener.onProgress(written, total);
                    showNotification(ctx, task, written, total);
                    lastTick = now;
                }
            }

            listener.onProgress(written, total);

            if (total > 0 && written < total) {
                // 没下完就断了：交给外层重试
                throw new Exception("incomplete " + written + "/" + total);
            }

            return true;

        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignore) {
            }
            try {
                if (is != null) is.close();
            } catch (Exception ignore) {
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }

    // ---------------- 通知 ----------------

    private static void createChannel(Context ctx) {
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

    /** 后台也有进度条，切出去也能看见在跑 */
    private static void showNotification(Context ctx, Task task, long downloaded, long total) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }

            int pct = total > 0 ? (int) (downloaded * 100 / total) : 0;

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);

            b.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(ctx.getString(R.string.log_downloading))
                    .setContentText(total > 0
                            ? pct + "%  " + fmt(downloaded) + " / " + fmt(total)
                            : fmt(downloaded))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);

            if (total > 0) {
                b.setProgress(100, pct, false);
            } else {
                b.setProgress(0, 0, true);
            }

            // 点通知回到应用
            Intent it = new Intent(ctx, MainActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            b.setContentIntent(PendingIntent.getActivity(ctx, 0, it, flags));

            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignore) {
        }
    }

    private static void cancelNotification(Context ctx) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIF_ID);
            }
        } catch (Exception ignore) {
        }
    }

    static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
