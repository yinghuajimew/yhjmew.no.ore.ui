package yhjmew.no.ore.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分段下载的纯 I/O 部分：区间划分、单段拉取、拼接。
 *
 * <p>单独拎出来是为了能不依赖 Android 直接跑测试 ——
 * 这几块最容易错（差一个字节就是坏包），却又不碰任何系统 API。
 * 网络那一层只用了 {@link HttpURLConnection}，标准 JDK 就有。
 */
final class SegmentIo {

    private SegmentIo() {
    }

    // ---------------- 区间 ----------------

    /**
     * 第 i 段的起始偏移。total &lt;= 0 表示长度未知，那就只有第 0 段。
     *
     * <p>注意 {@code segs == 1} 时 i=1 要返回 total —— 段长靠这个差值算，
     * 在这里提前 return 0 会让最后一段变成 0 字节。
     */
    static long spanStart(long total, int segs, int i) {
        if (total <= 0) {
            return 0;
        }
        if (segs < 1) {
            segs = 1;
        }
        return total * i / segs;
    }

    /** 第 i 段该有多少字节；-1 表示未知（读到流结束为止） */
    static long spanLength(long total, int segs, int i) {
        if (total <= 0) {
            return -1;
        }
        return spanStart(total, segs, i + 1) - spanStart(total, segs, i);
    }

    // ---------------- 探测 ----------------

    /** 一次探测的结果 */
    static final class Probe {
        /** 总字节数；&lt;= 0 表示没探出来 */
        long total = -1;
        /** 服务器认不认 Range（多段下载的前提） */
        boolean ranges;
        /** 最后一次的失败原因，方便日志 */
        String lastError;
    }

    /**
     * 探一下总长和 Range 支持。
     *
     * <p>**必须重试**：OneDrive 那条链路对 Range 请求限流很凶，实测同一秒内
     * 连发两次 {@code bytes=0-0}，第一次 206、第二次直接 429。
     * 只试一次的话，被限流打掉就会以为"服务器不给总长"，于是退化成单段、
     * 总量恒为 0 —— 界面上的百分比永远是 0%，进度条一格格不动。
     *
     * <p>每轮换一个区间再试：区间不同，命中限流的概率也不同。
     * 总预算 5 秒，超了就认了 —— 交给下载过程中的 {@link SizeDiscovered} 补。
     */
    static Probe probe(String url, String ua, ConnHook hook, CancelCheck cancel) {
        Probe p = new Probe();

        // 第一个就用 bytes=0-：两件事一次问完，还不额外耗流量
        //   206 -> 支持 Range，Content-Range 里带总长
        //   200 -> 不支持 Range，但 Content-Length 就是总长
        // 后面的只是限流时的备胎 —— 这条链路上请求发得越密越容易被卡，
        // 所以候选不多，靠退避而不是靠堆数量。
        String[] ranges = {"bytes=0-", "bytes=0-0", "bytes=0-1"};

        // 总预算 5 秒、最多试 4 次。再久用户就只看到"正在下载"却什么都不动 ——
        // 比 0% 更糟。超了就直接开始下：下载过程中的 Content-Range 会把
        // 总长补上（见 SizeDiscovered），总比自己在这儿干等强。
        long deadline = System.currentTimeMillis() + 5000;
        int maxRounds = 4;
        int round = 0;

        while (round < maxRounds && System.currentTimeMillis() < deadline) {
            if (cancel != null && cancel.cancelled()) {
                return p;
            }

            String range = ranges[round % ranges.length];
            long retryAfterMs = -1;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                if (hook != null) {
                    hook.onOpen(conn);
                }
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", ua);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("Range", range);
                int code = conn.getResponseCode();

                if (code == 206) {
                    long t = parseContentRangeTotal(conn.getHeaderField("Content-Range"));
                    p.ranges = true;
                    if (t > 0) {
                        p.total = t;
                        return p;
                    }
                    // 206 但 Content-Range 里总长是 *：能分段，但暂时不知道总量
                } else if (code == HttpURLConnection.HTTP_OK) {
                    p.ranges = false;
                    long len = conn.getContentLength();
                    if (len > 0) {
                        p.total = len;
                        return p;
                    }
                } else {
                    p.lastError = "HTTP " + code;
                    if (code == 429) {
                        // 服务器会明说自己想让我们等多久，听它的
                        retryAfterMs = parseRetryAfter(conn.getHeaderField("Retry-After"));
                    }
                }
            } catch (Exception e) {
                p.lastError = String.valueOf(e.getMessage());
            } finally {
                if (conn != null) {
                    if (hook != null) {
                        hook.onClose(conn);
                    }
                    try {
                        conn.disconnect();
                    } catch (Exception ignore) {
                    }
                }
            }

            // 被限流了就别贴脸连着打：服务器给了 Retry-After 就照办，
            // 否则 800ms / 1.6s / 2.4s 递增
            long wait = retryAfterMs > 0 ? retryAfterMs
                    : Math.min(800L * (round + 1), 2400L);
            round++;
            if (System.currentTimeMillis() + wait >= deadline) {
                break;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignore) {
                return p;
            }
        }
        return p;
    }

    /** "3" 或 "3.5"（秒）；其他格式返回 -1 */
    private static long parseRetryAfter(String v) {
        if (v == null || v.length() == 0) {
            return -1;
        }
        try {
            double sec = Double.parseDouble(v.trim());
            if (sec < 0 || sec > 60) {
                return -1;
            }
            return (long) (sec * 1000);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- 拉取 ----------------

    /** 服务器不认 Range（多段请求拿回整包）时抛这个，交给外层降级成单段 */
    static final class RangeUnsupported extends Exception {
    }

    /**
     * 原先不知道总长（探测被限流/服务器不给），但这一段响应里的
     * {@code Content-Range} 把总长带出来了。
     *
     * <p>抛它而不是就地改参数：分段划分是按总长算的，
     * 知道了总长就得整轮重划，那个决定只能由外层做。
     */
    static final class SizeDiscovered extends Exception {
        final long total;

        SizeDiscovered(long total) {
            this.total = total;
        }
    }

    interface CancelCheck {
        /** true 表示该收手了（暂停/取消） */
        boolean cancelled();
    }

    /** 让调用方登记连接，暂停时才能立刻切断而不是等 30 秒超时 */
    interface ConnHook {
        void onOpen(HttpURLConnection conn);
        void onClose(HttpURLConnection conn);
    }

    /**
     * 把 url 的 {@code [start, start+len)} 这一段写进 part 文件，支持续传。
     *
     * @param len  该段应有的字节数；&lt; 0 表示总长未知，读到流结束
     * @param multi 是否处于多段模式。多段模式下服务器回 200（不认 Range）就没法并行，
     *              必须抛 {@link RangeUnsupported} 让外层清干净重来
     */
    static void fetch(String url, String ua, File part, long start, long len,
                      AtomicLong counter, boolean multi,
                      CancelCheck cancel, ConnHook hook) throws Exception {
        fetch(url, ua, part, start, len, counter, multi, cancel, hook, true);
    }

    /**
     * @param totalKnown false 表示调用方还不确定总长。这时一旦从
     *                   {@code Content-Range} 里读到总长，就抛
     *                   {@link SizeDiscovered} 让外层重划分段 —— 边下边把
     *                   进度条救回来，而不是一路显示 0%
     */
    static void fetch(String url, String ua, File part, long start, long len,
                      AtomicLong counter, boolean multi,
                      CancelCheck cancel, ConnHook hook,
                      boolean totalKnown) throws Exception {
        long have = part.isFile() ? part.length() : 0;
        if (len >= 0 && have > len) {
            part.delete();
            have = 0;
        }
        if (len >= 0 && have == len) {
            counter.set(have);
            return;
        }

        long from = start + have;
        long to = len >= 0 ? start + len - 1 : -1;

        HttpURLConnection conn = null;
        InputStream is = null;
        RandomAccessFile raf = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            if (hook != null) {
                hook.onOpen(conn);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Range",
                    "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));

            int code = conn.getResponseCode();

            if (code == 416) {
                // 已经到头了，或者段文件比服务器给的还长
                if (len >= 0 && have >= len) {
                    counter.set(have);
                    return;
                }
                throw new Exception("HTTP 416");
            }
            if (code == HttpURLConnection.HTTP_OK) {
                if (multi) {
                    throw new RangeUnsupported();
                }
                // 单段从头给：本地已有的那截作废，重写
                have = 0;
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw new Exception("HTTP " + code);
            } else {
                // 206 但给的不是我们要的那一段（服务器可能忽略了 Range 的一部分），
                // 照写进去就是错位 —— 必须拦下来
                String cr = conn.getHeaderField("Content-Range");
                long srvStart = parseContentRangeStart(cr);
                if (srvStart >= 0 && srvStart != from) {
                    throw new Exception("range mismatch " + srvStart + " != " + from);
                }
                if (!totalKnown) {
                    long t = parseContentRangeTotal(cr);
                    if (t > 0) {
                        // 先别写：外层要按真实总长重划分段
                        throw new SizeDiscovered(t);
                    }
                }
            }

            raf = new RandomAccessFile(part, "rw");
            if (have == 0) {
                raf.setLength(0);
            }
            raf.seek(have);

            is = conn.getInputStream();
            byte[] buf = new byte[64 * 1024];
            long pos = have;
            int n;
            while ((n = is.read(buf)) != -1) {
                if (cancel != null && cancel.cancelled()) {
                    return;
                }
                int w = n;
                if (len >= 0 && pos + w > len) {
                    w = (int) (len - pos);
                }
                if (w > 0) {
                    raf.write(buf, 0, w);
                    pos += w;
                    counter.set(pos);
                }
                if (len >= 0 && pos >= len) {
                    break;
                }
            }

            if (len < 0) {
                // 总长未知：读到 EOF 就算这段完了
                counter.set(pos);
                return;
            }
            if (pos < len) {
                // 没拉完就断了，抛出去让这一段的循环接着续
                throw new Exception("incomplete segment " + pos + "/" + len);
            }
            counter.set(pos);
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
                if (hook != null) {
                    hook.onClose(conn);
                }
                try {
                    conn.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** "bytes 0-0/780664832" → 0 */
    static long parseContentRangeStart(String header) {
        if (header == null) {
            return -1;
        }
        try {
            String h = header.trim();
            int sp = h.indexOf(' ');
            if (sp > 0) {
                h = h.substring(sp + 1);
            }
            int dash = h.indexOf('-');
            if (dash <= 0) {
                return -1;
            }
            return Long.parseLong(h.substring(0, dash).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** "bytes 0-0/780664832" → 780664832 */
    static long parseContentRangeTotal(String header) {
        if (header == null) {
            return -1;
        }
        int slash = header.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= header.length()) {
            return -1;
        }
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- 拼接 ----------------

    interface Progress {
        void onMerged(long merged, long total);
    }

    /**
     * 按顺序把各段拼成成品，先写 .tmp 再改名。
     *
     * <p>用 .tmp 中转而不是直接写目标文件：拼接要十几秒，中途被杀掉的话
     * 半成品会被当成"已下载"。
     *
     * @return false 表示拼接中途被取消，目标文件没有生成
     */
    static boolean merge(File target, File[] parts, long total,
                         CancelCheck check, Progress progress) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (tmp.exists() && !tmp.delete()) {
            throw new Exception("cannot delete stale tmp");
        }

        long sum = 0;
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[128 * 1024];
            long lastTick = 0;
            for (File p : parts) {
                if (p == null || !p.isFile()) {
                    continue;
                }
                FileInputStream in = new FileInputStream(p);
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (check != null && check.cancelled()) {
                            try {
                                out.close();
                            } catch (Exception ignore) {
                            }
                            tmp.delete();
                            return false;
                        }
                        out.write(buf, 0, n);
                        sum += n;

                        if (progress != null && sum - lastTick > 4L * 1024 * 1024) {
                            progress.onMerged(sum, total);
                            lastTick = sum;
                        }
                    }
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignore) {
                    }
                }
            }
            out.flush();
        } finally {
            try {
                out.close();
            } catch (Exception ignore) {
            }
        }

        if (check != null && check.cancelled()) {
            tmp.delete();
            return false;
        }

        if (total > 0 && tmp.length() != total) {
            long got = tmp.length();
            tmp.delete();
            throw new Exception("merged size mismatch " + got + "/" + total);
        }

        if (target.exists() && !target.delete()) {
            tmp.delete();
            throw new Exception("cannot replace target");
        }
        if (!tmp.renameTo(target)) {
            // 少数 ROM 上 rename 会失败，退回复制
            copy(tmp, target);
            tmp.delete();
        }
        if (!target.isFile() || target.length() == 0) {
            throw new Exception("merge failed");
        }
        for (File p : parts) {
            if (p != null) {
                p.delete();
            }
        }
        return true;
    }

    static void copy(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
            try {
                out.close();
            } catch (Exception ignore) {
            }
        }
    }
}
