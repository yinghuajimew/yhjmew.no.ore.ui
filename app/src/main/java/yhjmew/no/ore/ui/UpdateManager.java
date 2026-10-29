package yhjmew.no.ore.ui;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateManager {

    private static final String[] UPDATE_CHECK_URLS = {
            "https://yhjmew-no-ore-ui.pages.dev/update.json",// Cloudflare
            "https://bbk.endyun.ltd/no-ore-ui/update.json",// zihao_il
            "https://raw.githubusercontent.com/yinghuajimew/yhjmew.no.ore.ui/refs/heads/main/docs/update.json"// GitHub
    };

    private static final String NOTIFICATION_CHANNEL_ID = "update_download";
    private static final int NOTIFICATION_ID = 1001;

    private Context context;
    private UpdateCallback callback;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    
    private static OkHttpClient okHttpClient;

    public interface UpdateCallback {
    void onUpdateAvailable(String newVersion, List<DownloadSource> sources, String changelog, boolean forceUpdate, boolean isManual); 
    void onNoUpdate();
    void onCheckError(String error);
    void onDownloadProgress(int progress, int max);
    void onDownloadComplete(File apkFile);
    void onDownloadError(String error);
    void onLog(String message);
    void onForceUpdate(String newVersion, String currentVersion, String minVersion, List<DownloadSource> sources, String changelog);  // ← 添加 sources 和 changelog
}

    public static class DownloadSource {
        public String name;
        public String url;
        public String region;

        public DownloadSource(String name, String url, String region) {
            this.name = name;
            this.url = url;
            this.region = region;
        }
    }

    public UpdateManager(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        
        if (okHttpClient == null) {
            okHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.title_app_update_download),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(context.getString(R.string.msg_update_notification));
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
 * 检查更新
 * @param callback 回调
 * @param isManual 是否为手动检查（true=手动，false=自动）
 */
public void checkUpdate(final UpdateCallback callback, final boolean isManual) {
    this.callback = callback;

    new Thread(new Runnable() {
        @Override
        public void run() {
            String currentVersion = getCurrentVersion();
            
            notifyLog(context.getString(R.string.log_total_sources) + UPDATE_CHECK_URLS.length + context.getString(R.string.fmt_update_sources));
            
            for (int i = 0; i < UPDATE_CHECK_URLS.length; i++) {
                String checkUrl = UPDATE_CHECK_URLS[i];
                try {
                    notifyLog(context.getString(R.string.log_try_source) + (i + 1) + "/" + UPDATE_CHECK_URLS.length + ": " + getDomainName(checkUrl));
                    
                    String json = fetchUrl(checkUrl, 10000);
                    if (json != null && json.length() > 0) {
                        notifyLog(context.getString(R.string.log_from) + getDomainName(checkUrl) + context.getString(R.string.log_got_update_info));
                        parseUpdateResponse(json, currentVersion, isManual);
                        return;
                    }
                } catch (Exception e) {
                    notifyLog(context.getString(R.string.log_source) + (i + 1) + context.getString(R.string.err_fail) + e.getMessage());
                    
                    if (i < UPDATE_CHECK_URLS.length - 1) {
                        notifyLog(context.getString(R.string.log_next_source));
                    }
                }
            }
            
            notifyError(context.getString(R.string.err_no_update_source) + UPDATE_CHECK_URLS.length + context.getString(R.string.err_parse_sources));
        }
    }).start();
}

/**
 * 从 URL 提取域名
 */
private String getDomainName(String url) {
    try {
        java.net.URL u = new java.net.URL(url);
        return u.getHost();
    } catch (Exception e) {
        return url;
    }
}

    /**
     * 获取 URL 内容（类的成员方法）
     */
private String fetchUrl(String urlString, int timeout) throws Exception {
    notifyLog(context.getString(R.string.log_requesting) + getDomainName(urlString));
    
    java.net.URL url = new java.net.URL(urlString);
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
    
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(timeout);
    conn.setReadTimeout(timeout);
    conn.setInstanceFollowRedirects(true);  // ← 自动跟随重定向
    
    // 完整的请求头
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
    conn.setRequestProperty("Accept", "application/json, text/plain, */*");
    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    conn.setRequestProperty("Accept-Encoding", "identity");  // ← 不要压缩，避免解压问题
    conn.setRequestProperty("Connection", "close");
    conn.setRequestProperty("Cache-Control", "no-cache");
    
    // 针对不同平台
    if (urlString.contains("pages.dev")) {
        conn.setRequestProperty("Origin", "https://" + url.getHost());
    } else if (urlString.contains("raw.githubusercontent.com")) {
        conn.setRequestProperty("Accept", "text/plain");
    }

    int responseCode = conn.getResponseCode();
    notifyLog(context.getString(R.string.log_response_code) + responseCode);
    
    // 处理重定向（虽然已设置自动跟随，但有些服务器需要手动处理）
    if (responseCode >= 300 && responseCode < 400) {
        String newUrl = conn.getHeaderField("Location");
        conn.disconnect();
        notifyLog(context.getString(R.string.log_redirect) + newUrl);
        return fetchUrl(newUrl, timeout);
    }
    
    if (responseCode == 200) {
        // 检查 Content-Type
        String contentType = conn.getContentType();
        notifyLog(context.getString(R.string.log_content_type) + (contentType != null ? contentType : "null"));
        
        // 检查 Content-Length
        int contentLength = conn.getContentLength();
        notifyLog(context.getString(R.string.log_content_length) + contentLength);
        
        // 如果返回的是 HTML，说明有问题
        if (contentType != null && contentType.toLowerCase().contains("text/html")) {
            conn.disconnect();
            throw new Exception(context.getString(R.string.err_server_html) + contentType + ")");
        }
        
        java.io.InputStream is = conn.getInputStream();
        
        // 不处理压缩，直接读取原始内容
        // （因为我们在请求头中设置了 Accept-Encoding: identity）
        
        // 使用 ByteArrayOutputStream 先读取全部内容
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        
        while ((bytesRead = is.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        
        is.close();
        conn.disconnect();
        
        byte[] responseBytes = baos.toByteArray();
        notifyLog(context.getString(R.string.log_actual_read) + responseBytes.length + context.getString(R.string.fmt_bytes));
        
        // 检查是否为空
        if (responseBytes.length == 0) {
            throw new Exception(context.getString(R.string.err_server_empty));
        }
        
        // 尝试 UTF-8 解码
        String response;
        try {
            response = new String(responseBytes, "UTF-8");
        } catch (Exception e) {
            notifyLog(context.getString(R.string.log_utf8_fallback));
            try {
                response = new String(responseBytes, "GBK");
            } catch (Exception e2) {
                throw new Exception(context.getString(R.string.err_decode_failed));
            }
        }
        
        // 去除 BOM 和空白字符
        response = response.trim();
        if (response.startsWith("\uFEFF")) {
            response = response.substring(1);
        }
        
        notifyLog(context.getString(R.string.log_response_preview) + response.substring(0, Math.min(100, response.length())));
        
        // 验证是否为 JSON
        if (!response.startsWith("{") && !response.startsWith("[")) {
            throw new Exception(context.getString(R.string.err_not_json) + response.substring(0, Math.min(50, response.length())));
        }
        
        return response;
    } else if (responseCode == 404) {
        conn.disconnect();
        throw new Exception(context.getString(R.string.err_404));
    } else if (responseCode == 403) {
        conn.disconnect();
        throw new Exception(context.getString(R.string.err_403));
    } else {
        conn.disconnect();
        throw new Exception(context.getString(R.string.err_parse_http) + responseCode);
    }
}

// 保留旧方法签名，兼容性
public void checkUpdate(final UpdateCallback callback) {
    checkUpdate(callback, false);  // 默认为自动检查
}

    public void downloadApk(final String downloadUrl, final String fileName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File downloadDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates");
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                    
                    File apkFile = new File(downloadDir, fileName);
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }

                    Request request = new Request.Builder()
                            .url(downloadUrl)
                            .addHeader("User-Agent", "OreUI-Remover/" + getCurrentVersion())
                            .build();

                    Response response = okHttpClient.newCall(request).execute();

                    if (!response.isSuccessful()) {
                        response.close();
                        notifyDownloadError(context.getString(R.string.err_download_server) + response.code());
                        return;
                    }

                    long fileLength = response.body().contentLength();
                    InputStream is = response.body().byteStream();
                    FileOutputStream fos = new FileOutputStream(apkFile);

                    showDownloadNotification(0, (int) fileLength);

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytesRead = 0;
                    long lastUpdateTime = 0;

                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;

                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdateTime > 500) {
                            notifyDownloadProgress((int) totalBytesRead, (int) fileLength);
                            updateDownloadNotification((int) totalBytesRead, (int) fileLength);
                            lastUpdateTime = currentTime;
                        }
                    }

                    fos.flush();
                    fos.close();
                    is.close();
                    response.close();

                    cancelNotification();
                    notifyDownloadComplete(apkFile);

                } catch (Exception e) {
                    cancelNotification();
                    notifyDownloadError(context.getString(R.string.err_download_failed) + e.getMessage());
                }
            }
        }).start();
    }

    private void parseUpdateResponse(String json, String currentVersion, boolean isManual) {
    try {
        org.json.JSONObject root = new org.json.JSONObject(json);
        
        String latestVersion = root.getString("version");
        boolean forceUpdate = root.optBoolean("forceUpdate", false);
        String minVersion = root.optString("minVersion", "0.0.0");
        
        // 解析多语言更新日志
        String changelog;
        org.json.JSONObject changelogObj = root.optJSONObject("changelog");
        if (changelogObj != null) {
            // 只有 update.json 提供 zh / en 两种，繁体也归到 zh。
            // 这里跟随用户选定的应用语言，而不是系统语言：
            // 用户在关于页选了 English，就该给英文更新说明。
            String lang = java.util.Locale.getDefault().getLanguage();
            boolean preferZh = "zh".equals(lang);
            if (preferZh) {
                changelog = changelogObj.optString("zh", changelogObj.optString("en", context.getString(R.string.msg_no_update_note)));
            } else {
                changelog = changelogObj.optString("en", changelogObj.optString("zh", context.getString(R.string.msg_no_changelog)));
            }
        } else {
            changelog = root.optString("changelog", context.getString(R.string.msg_no_update_note));
        }
        
        // 解析下载源
        org.json.JSONArray sourcesArray = root.getJSONArray("downloadSources");
        List<DownloadSource> sources = new ArrayList<DownloadSource>();
        
        for (int i = 0; i < sourcesArray.length(); i++) {  // ← 改为 length()
			org.json.JSONObject sourceObj = sourcesArray.getJSONObject(i);
			String name = sourceObj.getString("name");
			String url = sourceObj.getString("url");
			String region = sourceObj.optString("region", "");

			sources.add(new DownloadSource(name, url, region));
		}

        // 添加详细日志
        notifyLog(context.getString(R.string.title_version_info));
        notifyLog(context.getString(R.string.fmt_current_version) + currentVersion);
        notifyLog(context.getString(R.string.fmt_latest_version) + latestVersion);
        notifyLog(context.getString(R.string.fmt_min_version) + minVersion);
        notifyLog(context.getString(R.string.log_force_update_flag) + forceUpdate);
        
        // 判断逻辑
        boolean isLowerThanMin = isVersionLowerThan(currentVersion, minVersion);
        notifyLog(context.getString(R.string.log_below_min_version) + isLowerThanMin);

        // 只有当「确实存在更新的版本」时才可能强制。
        // 原来的写法是 if (forceUpdate || isLowerThanMin)，不校验版本高低：
        // 已是最新版、甚至比 update.json 里的版本还新，照样弹强制更新框，
        // 只能靠手动改 update.json 绕开 —— 这就是之前强制更新用不了的原因。
        boolean hasNewVersion = isNewerVersion(currentVersion, latestVersion);
        notifyLog(context.getString(R.string.title_there_is_new_version) + hasNewVersion);

        if (!hasNewVersion) {
            // 已是最新（或更新），不提更新，也就不存在强制。
            notifyNoUpdate();
            return;
        }

        // 检查是否需要强制更新
        if (forceUpdate || isLowerThanMin) {
            notifyLog(context.getString(R.string.log_force_update));
            notifyForceUpdate(latestVersion, currentVersion, minVersion, sources, changelog);
            return;
        }

        notifyUpdateAvailable(latestVersion, sources, changelog, forceUpdate, isManual);

    } catch (Exception e) {
        notifyError(context.getString(R.string.err_parse_update_failed) + e.getMessage());
    }
}

/**
 * 检查版本是否低于最低版本
 * 注意：等于最低版本时返回 false（不需要更新）
 */
private boolean isVersionLowerThan(String current, String min) {
    try {
        // 如果最低版本未设置或为 0.0.0，不需要强制更新
        if (min == null || min.equals("0.0.0") || min.trim().isEmpty()) {
            return false;
        }
        
        String[] currentParts = sanitizeVersion(current).split("\\.");
        String[] minParts = sanitizeVersion(min).split("\\.");

        int length = Math.max(currentParts.length, minParts.length);
        for (int i = 0; i < length; i++) {
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            int minPart = i < minParts.length ? Integer.parseInt(minParts[i]) : 0;

            if (currentPart < minPart) {
                return true;  // 当前版本低于最低版本
            } else if (currentPart > minPart) {
                return false;  // 当前版本高于最低版本
            }
            // 相等，继续比较下一位
        }
        
        // 所有位都相等，返回 false（不需要更新）
        return false;
        
    } catch (Exception e) {
        return false;  // 解析失败，不强制更新
    }
}

    /** 下载目录：<外置私有目录>/Download/updates */
    private File getDownloadDir() {
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 清扫下载目录里已经没用的安装包。
     *
     * <p>原来下载完就再也没人删过：用户点「稍后安装」之后，
     * update_2.5.apk 这类文件会一直躺在私有目录里，每更新一次多一个，
     * 越攒越多（一个包 4~5 MB，装几十个版本就上百 MB）。
     *
     * <p>但不能一刀切全删 —— 两种包得留着：
     * <ol>
     *   <li><b>比当前版本新的</b>：用户下好了，等着装。删了他得重下。
     *       这种情况正是"我们没推送更新，但他点一下就能装已下载的版本"。</li>
     *   <li><b>等于当前版本的</b>：那是刚才装完的，留着无害，
     *       下次启动时自然变成"不高于当前版本"被清掉。</li>
     * </ol>
     * 只清**低于当前版本**的旧包。
     *
     * <p>为什么不在安装完立刻删：installApk 只是拉起系统安装界面，
     * 我们无法知道用户装没装、什么时候装。删早了用户从安装器返回再点就失败。
     *
     * @param currentVersion 当前应用版本名，用来判断哪些包已经过时
     */
    public void cleanupOldApks(String currentVersion) {
        try {
            File[] files = getDownloadDir().listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f == null || !f.isFile()) {
                    continue;
                }
                String name = f.getName();
                // 只处理本工具产生的安装包
                if (!name.startsWith("update_") || !name.endsWith(".apk")) {
                    continue;
                }
                String ver = versionOfApk(f);
                // 版本号 >= 当前版本 → 留着；否则是过时的包
                if (isNewerVersion(currentVersion, ver) || ver.equals(currentVersion)) {
                    continue;
                }
                if (f.delete()) {
                    notifyLog(context.getString(R.string.log_cleaned_old_apk) + name);
                }
            }
        } catch (Exception e) {
            // 清理失败不影响主流程
        }
    }

    /**
     * 找出「已经下载好、还没安装」的更新包。
     *
     * <p>用户下载完点「稍后安装」，或从安装界面点返回，包会留在
     * Download/updates/ 里。这时应该能直接用它安装，而不是重新下载 4~5 MB。
     *
     * <p>按修改时间倒序，最新的排前面。
     */
    public java.util.List<File> findPendingApks() {
        java.util.List<File> result = new java.util.ArrayList<File>();
        try {
            File[] files = getDownloadDir().listFiles();
            if (files == null) {
                return result;
            }
            for (File f : files) {
                if (f != null && f.isFile()
                        && f.getName().startsWith("update_")
                        && f.getName().endsWith(".apk")
                        && f.length() > 0) {
                    result.add(f);
                }
            }
            java.util.Collections.sort(result, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
                }
            });
        } catch (Exception ignore) {
        }
        return result;
    }

    /** 这个包对应的更新版本号，取自文件名 update_<版本>.apk */
    public static String versionOfApk(File apk) {
        String n = apk.getName();
        if (n.startsWith("update_") && n.endsWith(".apk")) {
            return n.substring("update_".length(), n.length() - ".apk".length());
        }
        return n;
    }

    public void installApk(File apkFile) {
    try {
        notifyLog(context.getString(R.string.log_start_install));
        
        if (!apkFile.exists()) {
            notifyDownloadError(context.getString(R.string.err_apk_missing));
            return;
        }

        notifyLog(context.getString(R.string.fmt_file) + apkFile.getName() + " (" + (apkFile.length() / 1024 / 1024) + " MB)");

        // Android 8.0+ 检查安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                notifyLog(context.getString(R.string.err_need_install_perm_jump));
                Intent permIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                permIntent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
                permIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(permIntent);
                return;
            }
        }

        // 构建安装 Intent
        android.net.Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile
            );
        } else {
            apkUri = android.net.Uri.fromFile(apkFile);
        }

        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(apkUri);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        context.startActivity(intent);
        notifyLog(context.getString(R.string.log_install_ui_shown));

    } catch (Exception e) {
        notifyDownloadError(context.getString(R.string.err_install_failed) + e.getMessage());
        notifyLog(context.getString(R.string.err_details) + android.util.Log.getStackTraceString(e));
    }
}

    private void showDownloadNotification(int progress, int max) {
        notificationBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.log_downloading_update))
                .setContentText(context.getString(R.string.log_ready_download))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        if (max > 0) {
            notificationBuilder.setProgress(max, progress, false);
        } else {
            notificationBuilder.setProgress(0, 0, true);
        }

        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private void updateDownloadNotification(int progress, int max) {
        if (notificationBuilder != null && max > 0) {
            int percent = (int) ((progress * 100.0f) / max);
            notificationBuilder
                    .setProgress(max, progress, false)
                    .setContentText(context.getString(R.string.log_downloaded) + percent + "%");
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private String getCurrentVersion() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    /**
     * 把版本号洗成纯数字点分形式，容忍常见脏数据。
     *
     * <p>原实现遇到 "v2.5"、"2.5-beta"、"2.5
" 这类直接抛 NumberFormatException，
     * 上层 catch 成 false —— 也就是"没有新版本"，用户永远收不到更新提示。
     * 方向虽然安全（不会误强制更新），但会静默漏更新。
     *
     * <p>这里只做去掉前缀 v、截掉第一个非数字后缀、去空白三件事，
     * 不做语义上的预发布判断 —— 本项目的版本号本来就是纯数字点分。
     */
    private static String sanitizeVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return "";
        }
        // 去掉 v / V 前缀
        if (v.charAt(0) == 'v' || v.charAt(0) == 'V') {
            v = v.substring(1).trim();
        }
        // 截到第一个非法字符为止（-、+、空格、换行…）
        int cut = v.length();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!(c >= '0' && c <= '9') && c != '.') {
                cut = i;
                break;
            }
        }
        v = v.substring(0, cut);
        // 去掉尾部多余的点
        while (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private boolean isNewerVersion(String current, String latest) {
        return isNewerVersionStatic(current, latest);
    }

    /**
     * 静态版本的本体，供外部（MainActivity）不持有实例时也能比较。
     * 版本号清洗与比较逻辑和实例方法完全一致。
     */
    public static boolean isNewerVersionStatic(String current, String latest) {
        try {
            String cur = sanitizeVersion(current);
            String lat = sanitizeVersion(latest);
            if (cur.isEmpty() || lat.isEmpty()) {
                // 洗不出数字就没法按位比，退回字符串比较，
                // 至少 update.json 写成 "v2.5" 时还能提示有新版
                return latest != null && current != null
                        && latest.compareTo(current) > 0;
            }
            String[] currentParts = cur.split("\\.");
            String[] latestParts = lat.split("\\.");

            int length = Math.max(currentParts.length, latestParts.length);
            for (int i = 0; i < length; i++) {
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;

                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyUpdateAvailable(final String version, final List<DownloadSource> sources, final String changelog, final boolean forceUpdate, final boolean isManual) {
    if (context instanceof Activity) {
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onUpdateAvailable(version, sources, changelog, forceUpdate, isManual);
                }
            }
        });
    }
}

private void notifyForceUpdate(final String newVersion, final String currentVersion, final String minVersion, final List<DownloadSource> sources, final String changelog) {
    if (context instanceof Activity) {
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onForceUpdate(newVersion, currentVersion, minVersion, sources, changelog);
                }
            }
        });
    }
}

    private void notifyNoUpdate() {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onNoUpdate();
                    }
                }
            });
        }
    }

    private void notifyError(final String error) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onCheckError(error);
                    }
                }
            });
        }
    }

    private void notifyDownloadProgress(final int progress, final int max) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onDownloadProgress(progress, max);
                    }
                }
            });
        }
    }

    private void notifyDownloadComplete(final File apkFile) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onDownloadComplete(apkFile);
                    }
                }
            });
        }
    }

    private void notifyDownloadError(final String error) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onDownloadError(error);
                    }
                }
            });
        }
    }
    
    public void smartDownload(final List<DownloadSource> sources, final String fileName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                notifyLog(context.getString(R.string.log_testing_speed));
                
                // 第一步：测速，找到可用且最快的源
                List<SourceWithSpeed> availableSources = testSourcesSpeed(sources);
                
                if (availableSources.isEmpty()) {
                    notifyDownloadError(context.getString(R.string.err_no_download_source));
                    return;
                }
                
                // 第二步：按速度排序，从最快的开始尝试下载
                for (int i = 0; i < availableSources.size(); i++) {
                    SourceWithSpeed sourceWithSpeed = availableSources.get(i);
                    DownloadSource source = sourceWithSpeed.source;
                    
                    try {
                        notifyLog(context.getString(R.string.log_receiving_from) + source.name + context.getString(R.string.log_download_elapsed) + sourceWithSpeed.responseTime + "ms)");
                        
                        boolean success = downloadApkInternal(source.url, fileName);
                        
                        if (success) {
                            notifyLog(context.getString(R.string.log_from) + source.name + context.getString(R.string.log_download_ok));
                            return;
                        }
                    } catch (Exception e) {
                        notifyLog("❌ " + source.name + context.getString(R.string.err_download_failed) + e.getMessage());
                        
                        // 如果还有其他源，继续尝试
                        if (i < availableSources.size() - 1) {
                            notifyLog(context.getString(R.string.log_switching_source));
                        } else {
                            notifyDownloadError(context.getString(R.string.err_download_all_failed));
                        }
                    }
                }
            }
        }).start();
    }

    /**
     * 测速：测试所有下载源的响应时间
     */
private List<SourceWithSpeed> testSourcesSpeed(List<DownloadSource> sources) {
    List<SourceWithSpeed> results = new ArrayList<SourceWithSpeed>();
    
    for (DownloadSource source : sources) {
        try {
            long startTime = System.currentTimeMillis();
            
            java.net.URL url = new java.net.URL(source.url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");  // 只获取头部
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            // 完整的请求头
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "close");
            
            int responseCode = conn.getResponseCode();
            long elapsedTime = System.currentTimeMillis() - startTime;
            conn.disconnect();
            
            if (responseCode == 200 || responseCode == 302 || responseCode == 301) {
                results.add(new SourceWithSpeed(source, elapsedTime));
                notifyLog("✅ " + source.name + context.getString(R.string.log_source_available) + elapsedTime + "ms)");
            } else {
                notifyLog("⚠️ " + source.name + context.getString(R.string.err_server_response) + responseCode);
            }
            
        } catch (Exception e) {
            notifyLog("❌ " + source.name + context.getString(R.string.err_connection) + e.getMessage());
        }
    }
    
    // 按响应时间排序
    java.util.Collections.sort(results, new java.util.Comparator<SourceWithSpeed>() {
        @Override
        public int compare(SourceWithSpeed s1, SourceWithSpeed s2) {
            return Long.compare(s1.responseTime, s2.responseTime);
        }
    });
    
    return results;
}

    /**
 * 内部下载方法（优化请求头）
 */
private boolean downloadApkInternal(String downloadUrl, String fileName) throws Exception {
    File downloadDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates");
    if (!downloadDir.exists()) {
        downloadDir.mkdirs();
    }
    
    File apkFile = new File(downloadDir, fileName);
    if (apkFile.exists()) {
        apkFile.delete();
    }

    java.net.URL url = new java.net.URL(downloadUrl);
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(30000);
    
    // 完整的请求头（模拟浏览器）
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
    conn.setRequestProperty("Accept", "*/*");
    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    conn.setRequestProperty("Connection", "keep-alive");
    conn.setRequestProperty("Range", "bytes=0-");  // 支持断点续传
    
    // 针对特定平台优化
    if (downloadUrl.contains("github.com")) {
        conn.setRequestProperty("Accept", "application/octet-stream");
    }

    int responseCode = conn.getResponseCode();
    
    // 处理重定向
    if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
        responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
        responseCode == 307 || responseCode == 308) {
        String newUrl = conn.getHeaderField("Location");
        conn.disconnect();
        notifyLog(context.getString(R.string.log_redirect) + newUrl);
        return downloadApkInternal(newUrl, fileName);
    }
    
    if (responseCode != 200 && responseCode != 206) {  // 206 是部分内容（Range 请求）
        conn.disconnect();
        throw new Exception(context.getString(R.string.err_server) + responseCode);
    }

    int fileLength = conn.getContentLength();
    java.io.InputStream is = conn.getInputStream();
    
    // 处理 Gzip 压缩（某些服务器可能压缩传输）
    String encoding = conn.getContentEncoding();
    if ("gzip".equalsIgnoreCase(encoding)) {
        is = new java.util.zip.GZIPInputStream(is);
    }
    
    java.io.FileOutputStream fos = new java.io.FileOutputStream(apkFile);

    showDownloadNotification(0, fileLength);

    byte[] buffer = new byte[8192];
    int bytesRead;
    int totalBytesRead = 0;
    long lastUpdateTime = 0;

    while ((bytesRead = is.read(buffer)) != -1) {
        fos.write(buffer, 0, bytesRead);
        totalBytesRead += bytesRead;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdateTime > 500) {
            notifyDownloadProgress(totalBytesRead, fileLength);
            updateDownloadNotification(totalBytesRead, fileLength);
            lastUpdateTime = currentTime;
        }
    }

    fos.flush();
    fos.close();
    is.close();
    conn.disconnect();

    // 验证文件完整性
    if (apkFile.length() == 0) {
        apkFile.delete();
        throw new Exception(context.getString(R.string.err_download_size_0));
    }
    
    if (fileLength > 0 && apkFile.length() < fileLength * 0.9) {
        apkFile.delete();
        throw new Exception(context.getString(R.string.err_download_incomplete) + fileLength + context.getString(R.string.fmt_bytes_actual) + apkFile.length() + context.getString(R.string.fmt_bytes));
    }

    cancelNotification();
    notifyDownloadComplete(apkFile);
    return true;
}

    // 新增：通知日志
    private void notifyLog(final String message) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onLog(message);
                    }
                }
            });
        }
    }

    /**
     * 内部类：下载源 + 响应时间
     */
    private static class SourceWithSpeed {
        DownloadSource source;
        long responseTime;

        SourceWithSpeed(DownloadSource source, long responseTime) {
            this.source = source;
            this.responseTime = responseTime;
        }
    }
}
