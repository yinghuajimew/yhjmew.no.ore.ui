package yhjmew.no.ore.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MC 版本库（bbk / mcarc）API 客户端。
 *
 * <p>接口来自 https://github.com/zihao-il/bbk-docs ，实测只有
 * {@code /search_version} 和 {@code /last_version} 能用；
 * {@code /get_version} 对所有版本都返回「没有此版本信息」，所以不用。
 *
 * <p>另一个坑：{@code /search_version} 目前只有 {@code s=1.21} 能查出数据，
 * 1.22~1.26 一律返回 status=201「查询失败，请联系管理员」。
 * 所以界面上要能容忍这种情况，不能假定一定查得到。
 *
 * <p>OneDrive 那条链接不是直链，是 {@code https://bbk.endyun.ltd/s/<slug>}
 * 这种中转页，页面里的 Vue 会再调 {@code POST /api/get_link} 拿真正的
 * {@code o_link}。我们直接调这个接口，省掉解析 HTML。
 */
final class VersionRepo {

    /** 主域名 + 备用域名，前面的失败就试后面的 */
    private static final String[] API_HOSTS = {
            "https://api.mcarc.top",
            "http://bbk.endyun.ltd:9000/api"
    };

    /** 中转页域名，用来把 /s/xxx 换成直链 */
    private static final String LINK_HOST = "https://bbk.endyun.ltd";

    /** SO 特征码覆盖的最低版本，低于这个的装了也没用 */
    static final String MIN_SUPPORTED_VERSION = "1.21";

    private VersionRepo() {
    }

    /** 一条版本记录 */
    static final class Entry {
        String versionAll;      // 1.26.60.4
        String version;         // 1.26.60（正式版会少一位）
        boolean beta;           // is_beta == 1
        String updateTime;
        String fileSize;
        /** 网盘名 -> {架构 -> 链接}，目前只关心 OneDrive */
        JSONObject links;

        /**
         * 从 {@code file_size} 里估出总字节数。
         *
         * <p>字段长这样：{@code 713.69MB/722.51MB}，实测 1.26.52.3 的真实包是
         * 757603304 字节 = 722.5MB —— 也就是**后一个**才是整包大小，
         * 前一个大概是压缩后/实际下载量。取后一个。
         *
         * <p>用途：探测被限流、拿不到 Content-Length 时先拿它顶着，
         * 让百分比能滚起来，不至于让用户对着「19.4 MB」和一个 0% 发呆。
         *
         * @return 字节数；解析不出来返回 -1
         */
        long estimatedTotalBytes() {
            if (fileSize == null || fileSize.length() == 0) {
                return -1;
            }
            String[] parts = fileSize.split("/");
            String pick = parts[parts.length - 1].trim();
            return parseHumanSize(pick);
        }

        /** "722.51MB" → 757603304 上下 */
        private static long parseHumanSize(String s) {
            if (s == null || s.length() == 0) {
                return -1;
            }
            String t = s.trim().toUpperCase(java.util.Locale.US).replace(" ", "");
            long mul;
            String num;
            if (t.endsWith("TB")) {
                mul = 1024L * 1024 * 1024 * 1024;
                num = t.substring(0, t.length() - 2);
            } else if (t.endsWith("GB")) {
                mul = 1024L * 1024 * 1024;
                num = t.substring(0, t.length() - 2);
            } else if (t.endsWith("MB")) {
                mul = 1024L * 1024;
                num = t.substring(0, t.length() - 2);
            } else if (t.endsWith("KB")) {
                mul = 1024L;
                num = t.substring(0, t.length() - 2);
            } else if (t.endsWith("B")) {
                mul = 1L;
                num = t.substring(0, t.length() - 1);
            } else {
                return -1;
            }
            try {
                return (long) (Double.parseDouble(num) * mul);
            } catch (Exception e) {
                return -1;
            }
        }

        /** 排序用：把 1.26.60.4 变成可比较的整数序列 */
        long[] key() {
            String[] parts = (versionAll == null ? "" : versionAll).split("\\.");
            long[] k = new long[4];
            for (int i = 0; i < 4 && i < parts.length; i++) {
                try {
                    k[i] = Long.parseLong(parts[i].replaceAll("[^0-9]", ""));
                } catch (Exception ignore) {
                    k[i] = 0;
                }
            }
            return k;
        }
    }

    interface Callback {
        void onResult(List<Entry> entries, String error);
    }

    /**
     * 搜索正式版。
     *
     * @param query    版本号前缀，如 "1.21"、"1.26.60"
     * @param fuzzy    true 走模糊查询（m=1）
     * @param callback 回调，error 非空表示失败
     */
    static void searchOfficial(final String query, final boolean fuzzy, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 关键：API 要的不是 "1.26"，而是 "26." ——
                // 去掉大版本前面的 "1."，末尾补一个点，并且必须带 m=1。
                // 实测对比（同一天、同一台机器）：
                //     s=1.26   &m=1&b=0  ->  查询失败
                //     s=26.    &m=1&b=0  ->  22 条正式版
                // 而精确版本号又是完整的 "1.21.132" 才能查到，很反直觉。
                List<Q> candidates = buildQueries(query, fuzzy);

                Exception last = null;
                for (String host : API_HOSTS) {
                    for (Q q : candidates) {
                        try {
                            String url = host + "/search_version?s="
                                    + URLEncoder.encode(q.s, "UTF-8")
                                    + (q.fuzzy ? "&m=1" : "")
                                    + "&b=0";           // b=0 只要正式版
                            String json = httpGet(url);
                            List<Entry> list = parseList(json);
                            if (!list.isEmpty()) {
                                callback.onResult(list, null);
                                return;
                            }
                        } catch (Exception e) {
                            last = e;
                        }
                    }
                }
                // 全都查空：不是错误，就是没有
                callback.onResult(Collections.<Entry>emptyList(), null);
            }
        }).start();
    }

    /**
     * 按**精确版本号**找一个条目。
     *
     * <p>不能直接用 {@link #searchOfficial} 然后取第一条：
     * 查 "1.26.52.3" 时它第一个候选是模糊的 "26."，会返回整个大版本
     * 的全部正式版（22 条），取第 0 条拿到的是 **1.26.60.4**。
     * 拿它去续传，等于给 1.26.52.3 下了一份 1.26.60.4 的包。
     *
     * <p>这里先精确查，查不到再按大版本模糊查然后**按版本号过滤**。
     */
    static void findExact(final String versionAll, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String want = sanitizeQuery(versionAll);

                for (String host : API_HOSTS) {
                    // 1) 精确版本号（实测能命中单条时 message 是数组）
                    try {
                        String url = host + "/search_version?s="
                                + URLEncoder.encode(want, "UTF-8") + "&b=0";
                        Entry hit = pickExact(parseList(httpGet(url)), want);
                        if (hit != null) {
                            List<Entry> one = new ArrayList<Entry>();
                            one.add(hit);
                            callback.onResult(one, null);
                            return;
                        }
                    } catch (Exception ignore) {
                    }

                    // 2) 退到"大版本模糊查 + 按版本号筛"
                    String[] parts = want.split("\\.");
                    if (parts.length >= 2) {
                        try {
                            String small = parts[1] + ".";
                            String url = host + "/search_version?s="
                                    + URLEncoder.encode(small, "UTF-8") + "&m=1&b=0";
                            Entry hit = pickExact(parseList(httpGet(url)), want);
                            if (hit != null) {
                                List<Entry> one = new ArrayList<Entry>();
                                one.add(hit);
                                callback.onResult(one, null);
                                return;
                            }
                        } catch (Exception ignore) {
                        }
                    }
                }
                callback.onResult(Collections.<Entry>emptyList(), null);
            }
        }).start();
    }

    /** 在结果里挑版本号完全一致的那条 */
    private static Entry pickExact(List<Entry> list, String want) {
        if (list == null) {
            return null;
        }
        for (Entry e : list) {
            if (e != null && want.equals(sanitizeQuery(e.versionAll))) {
                return e;
            }
        }
        return null;
    }

    /** 一次查询的各种等价写法，按命中率从高到低 */
    private static final class Q {
        final String s;
        final boolean fuzzy;

        Q(String s, boolean fuzzy) {
            this.s = s;
            this.fuzzy = fuzzy;
        }
    }

    /**
     * 把用户输入的版本号翻译成 API 认得的形式。
     *
     * <p>规则（全部实测得出）：
     * <ul>
     *   <li>精确版本 "1.21.132" → 原样查，能命中 1 条；</li>
     *   <li>大版本 "1.26" / "26" → 变成 "26." 且带 m=1，能命中 22 条；</li>
     *   <li>"1.26.60" → 先按大版本 "26." 模糊查，再退到精确；</li>
     * </ul>
     */
    private static List<Q> buildQueries(String raw, boolean fuzzy) {
        List<Q> out = new ArrayList<Q>();
        String q = sanitizeQuery(raw);

        if (q.isEmpty()) {
            return out;
        }

        String[] parts = q.split("\\.");

        // 两段（1.26）或者单段（26）→ 当大版本处理
        if (parts.length <= 2) {
            String minor = parts[parts.length - 1];
            out.add(new Q(minor + ".", true));          // 26.  ← 最有效
            out.add(new Q(q, true));                    // 1.26（兜底）
            out.add(new Q(q, fuzzy));
        } else {
            // 三段及以上（1.26.60 / 1.21.132）→ 先大版本模糊，再精确
            out.add(new Q(parts[1] + ".", true));       // 26.
            out.add(new Q(q, fuzzy));                   // 1.26.60
            out.add(new Q(q, false));                   // 精确
        }
        return out;
    }

    /** 去掉 v 前缀、多余空格 */
    private static String sanitizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String q = raw.trim();
        if (q.startsWith("v") || q.startsWith("V")) {
            q = q.substring(1).trim();
        }
        while (q.endsWith(".")) {
            q = q.substring(0, q.length() - 1);
        }
        return q;
    }

    /** 只取最新一个正式版，用于「最新版本」入口 */
    static void latestOfficial(final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Exception last = null;
                for (String host : API_HOSTS) {
                    try {
                        String json = httpPost(host + "/last_version", "b=0");
                        callback.onResult(parseList(json), null);
                        return;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                callback.onResult(Collections.<Entry>emptyList(),
                        last == null ? "unknown" : last.getMessage());
            }
        }).start();
    }

    /**
     * 把 {@code https://bbk.endyun.ltd/s/1-26-60-4v8} 解析成真实的下载直链。
     *
     * <p>OpenList 那边支持 Range，所以拿到直链后可以断点续传。
     *
     * @param slug 形如 {@code 1-26-60-4v8}
     */
    static String resolveDirectLink(String slug) throws Exception {
        String body = new JSONObject().put("s_link", slug).toString();
        String json = postJson(LINK_HOST + "/api/get_link", body);

        JSONObject root = new JSONObject(json);
        if (root.optInt("status", 0) != 200) {
            throw new Exception("get_link status=" + root.optInt("status"));
        }
        JSONArray arr = root.optJSONArray("message");
        if (arr == null || arr.length() == 0) {
            throw new Exception("get_link empty");
        }
        String link = arr.getJSONObject(0).optString("o_link", "");
        if (link.isEmpty()) {
            throw new Exception("o_link empty");
        }
        return link;
    }

    /** 从 OneDrive 那组链接里取出 slug，没有就返回 null */
    static String oneDriveSlug(Entry e) {
        if (e == null || e.links == null) {
            return null;
        }
        JSONObject od = e.links.optJSONObject("OneDrive");
        if (od == null) {
            return null;
        }
        // 优先 64 位，其次 32 位
        String url = od.optString("ARMv8", "");
        if (url.isEmpty()) {
            url = od.optString("ARMv7", "");
        }
        if (url.isEmpty()) {
            return null;
        }
        int idx = url.indexOf("/s/");
        if (idx < 0) {
            return null;
        }
        return url.substring(idx + 3);
    }

    // ---------------- 内部 ----------------

    private static List<Entry> parseList(String json) throws Exception {
        List<Entry> out = new ArrayList<Entry>();
        JSONObject root = new JSONObject(json);
        int status = root.optInt("status", 0);

        Object msg = root.opt("message");
        if (!(msg instanceof JSONArray)) {
            // status=201 时 message 是一句话（"没有此版本信息！"），不是数组
            return out;
        }
        if (status != 200) {
            // 有数组但 status 不是 200：同样当作没有数据
            return out;
        }

        JSONArray arr = (JSONArray) msg;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Entry e = new Entry();
            e.versionAll = o.optString("version_all", "");
            e.version = o.optString("version", "");
            e.beta = o.optInt("is_beta", 0) == 1;
            e.updateTime = o.optString("update_time", "");
            e.fileSize = o.optString("file_size", "");
            e.links = o.optJSONObject("link");

            // 只要正式版
            if (e.beta) {
                continue;
            }
            // 只要 1.21 及以上 —— 更低的版本没有对应特征码，装了也用不了
            if (!isSupported(e.versionAll)) {
                continue;
            }
            out.add(e);
        }

        // 新版本排前面
        Collections.sort(out, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                long[] ka = a.key();
                long[] kb = b.key();
                for (int i = 0; i < 4; i++) {
                    if (ka[i] != kb[i]) {
                        return kb[i] > ka[i] ? 1 : -1;
                    }
                }
                return 0;
            }
        });
        return out;
    }

    /** 版本是否 >= 1.21 */
    static boolean isSupported(String versionAll) {
        if (versionAll == null) {
            return false;
        }
        String[] parts = versionAll.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > 1 || (major == 1 && minor >= 21);
        } catch (Exception e) {
            return false;
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", UA);
        try {
            return read(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPost(String urlStr, String formBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream os = conn.getOutputStream();
        os.write(formBody.getBytes("UTF-8"));
        os.close();
        try {
            return read(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static String postJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Content-Type", "application/json");
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.close();
        try {
            return read(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static String read(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString("UTF-8");
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
}
