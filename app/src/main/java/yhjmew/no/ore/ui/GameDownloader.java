package yhjmew.no.ore.ui;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;

/**
 * 游戏安装包下载器：支持断点续传、暂停、删除。
 *
 * <p>为什么不用系统 DownloadManager：它把文件下到公共 Downloads，
 * 我们拿不到「暂停」的控制权，也做不了"下完直接交给普通处理"。
 *
 * <p>断点续传靠 HTTP Range。OpenList 那条直链实测返回
 * {@code Accept-Ranges: bytes}，带 {@code Range: bytes=N-} 会回 206。
 * 所以暂停时只要记住已写入的字节数，下次从这里继续。
 */
final class GameDownloader {

    /** 下载目录：<外置私有目录>/Download/games */
    private static File dir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "games");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /** 版本号 → 安全的文件名，如 1.26.60.4 → Minecraft_1.26.60.4_v8a.apk */
    static String fileNameFor(String versionAll) {
        String v = versionAll == null ? "unknown" : versionAll.replaceAll("[^0-9A-Za-z._-]", "_");
        return "Minecraft_" + v + "_v8a.apk";
    }

    static File targetFile(Context ctx, String versionAll) {
        return new File(dir(ctx), fileNameFor(versionAll));
    }

    /** 这个版本是否已经下过（文件存在且不为空） */
    static boolean isDownloaded(Context ctx, String versionAll) {
        File f = targetFile(ctx, versionAll);
        return f.exists() && f.length() > 0;
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

    interface Listener {
        /** 已下载 / 总大小（总大小未知时为 -1） */
        void onProgress(long downloaded, long total);

        void onLog(String message);

        void onDone(File file);

        void onError(String message);

        /** 用户点了暂停 */
        void onPaused(long downloaded);
    }

    /** 一次下载任务的句柄，用来暂停/取消 */
    static final class Task {
        volatile boolean cancelled;
        volatile boolean paused;
        private HttpURLConnection conn;

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
    }

    /**
     * 开始（或继续）下载。
     *
     * <p>如果目标文件已经有一部分，就用 Range 从断点续传；
     * 服务器不支持 Range（回 200 而不是 206）时退回整体重下。
     *
     * @param url      直链（已经是 o_link，不是 /s/ 中转页）
     * @param versionAll 版本号，用来决定文件名
     */
    static Task download(final Context ctx, final String url, final String versionAll,
                         final Listener listener) {
        final Task task = new Task();
        final File target = targetFile(ctx, versionAll);

        new Thread(new Runnable() {
            @Override
            public void run() {
                long existing = target.exists() ? target.length() : 0;
                boolean resumed = existing > 0;

                RandomAccessFile raf = null;
                InputStream is = null;

                try {
                    HttpURLConnection conn = (HttpURLConnection)
                            new URL(url).openConnection();
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

                    // 206 = 服务器接受了断点；200 = 从头给，之前那截作废
                    if (resumed && code == HttpURLConnection.HTTP_OK) {
                        listener.onLog(ctx.getString(R.string.log_dl_no_resume));
                        existing = 0;
                        resumed = false;
                    } else if (code != HttpURLConnection.HTTP_OK
                            && code != HttpURLConnection.HTTP_PARTIAL) {
                        throw new Exception("HTTP " + code);
                    }

                    long contentLength = conn.getContentLength();   // 剩余字节数
                    long total = resumed ? existing + contentLength : contentLength;

                    if (resumed) {
                        listener.onLog(ctx.getString(R.string.log_dl_resume_from)
                                + fmt(existing));
                    }

                    // "rwd" 从指定位置覆盖写，支持断点
                    raf = new RandomAccessFile(target, "rwd");
                    raf.seek(existing);

                    is = conn.getInputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long written = existing;
                    long lastTick = 0;

                    while ((n = is.read(buf)) != -1) {
                        if (task.cancelled || task.paused) {
                            raf.close();
                            raf = null;
                            is.close();
                            conn.disconnect();
                            if (task.paused) {
                                listener.onPaused(written);
                            }
                            return;
                        }
                        raf.write(buf, 0, n);
                        written += n;

                        long now = System.currentTimeMillis();
                        if (now - lastTick > 300) {
                            listener.onProgress(written, total);
                            lastTick = now;
                        }
                    }

                    raf.close();
                    raf = null;
                    is.close();
                    conn.disconnect();

                    listener.onProgress(written, total);

                    // 大小对不上就是下坏了，别留个半截文件
                    if (total > 0 && written < total) {
                        listener.onError(ctx.getString(R.string.err_dl_incomplete)
                                + fmt(written) + " / " + fmt(total));
                        return;
                    }

                    listener.onDone(target);

                } catch (Exception e) {
                    if (task.paused) {
                        // 暂停时 disconnect 会让 read 抛异常，这是预期行为
                        listener.onPaused(target.exists() ? target.length() : 0);
                        return;
                    }
                    if (task.cancelled) {
                        return;
                    }
                    listener.onError(String.valueOf(e.getMessage()));
                } finally {
                    try {
                        if (raf != null) raf.close();
                    } catch (Exception ignore) {
                    }
                    try {
                        if (is != null) is.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }).start();

        return task;
    }

    static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
