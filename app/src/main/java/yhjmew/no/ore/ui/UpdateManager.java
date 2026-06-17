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
                    context.getString(R.string.app_update_download),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(context.getString(R.string.show_app_update_download_progress));
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
            
            notifyLog(context.getString(R.string.shared) + UPDATE_CHECK_URLS.length + context.getString(R.string.update_sources));
            
            for (int i = 0; i < UPDATE_CHECK_URLS.length; i++) {
                String checkUrl = UPDATE_CHECK_URLS[i];
                try {
                    notifyLog(context.getString(R.string.try_the_source) + (i + 1) + "/" + UPDATE_CHECK_URLS.length + ": " + getDomainName(checkUrl));
                    
                    String json = fetchUrl(checkUrl, 10000);
                    if (json != null && json.length() > 0) {
                        notifyLog(context.getString(R.string.from) + getDomainName(checkUrl) + context.getString(R.string.obtain_updated_information_successfully));
                        parseUpdateResponse(json, currentVersion, isManual);
                        return;
                    }
                } catch (Exception e) {
                    notifyLog(context.getString(R.string.source) + (i + 1) + context.getString(R.string.fail) + e.getMessage());
                    
                    if (i < UPDATE_CHECK_URLS.length - 1) {
                        notifyLog(context.getString(R.string.switch_to_next_source));
                    }
                }
            }
            
            notifyError(context.getString(R.string.all_update_sources_are_inaccessible_total) + UPDATE_CHECK_URLS.length + context.getString(R.string.sources));
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
    notifyLog(context.getString(R.string.requesting) + getDomainName(urlString));
    
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
    notifyLog(context.getString(R.string.response_code) + responseCode);
    
    // 处理重定向（虽然已设置自动跟随，但有些服务器需要手动处理）
    if (responseCode >= 300 && responseCode < 400) {
        String newUrl = conn.getHeaderField("Location");
        conn.disconnect();
        notifyLog(context.getString(R.string.redirect_to) + newUrl);
        return fetchUrl(newUrl, timeout);
    }
    
    if (responseCode == 200) {
        // 检查 Content-Type
        String contentType = conn.getContentType();
        notifyLog(context.getString(R.string.contenttype) + (contentType != null ? contentType : "null"));
        
        // 检查 Content-Length
        int contentLength = conn.getContentLength();
        notifyLog(context.getString(R.string.contentlength) + contentLength);
        
        // 如果返回的是 HTML，说明有问题
        if (contentType != null && contentType.toLowerCase().contains("text/html")) {
            conn.disconnect();
            throw new Exception(context.getString(R.string.server_returns_html_instead_of_json_contenttype) + contentType + ")");
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
        notifyLog(context.getString(R.string.actual_reading) + responseBytes.length + context.getString(R.string.byte_1));
        
        // 检查是否为空
        if (responseBytes.length == 0) {
            throw new Exception(context.getString(R.string.server_returns_empty_content));
        }
        
        // 尝试 UTF-8 解码
        String response;
        try {
            response = new String(responseBytes, "UTF-8");
        } catch (Exception e) {
            notifyLog(context.getString(R.string.utf8_decoding_failed_try_gbk));
            try {
                response = new String(responseBytes, "GBK");
            } catch (Exception e2) {
                throw new Exception(context.getString(R.string.unable_to_decode_response_content_tried_utf8_and_g));
            }
        }
        
        // 去除 BOM 和空白字符
        response = response.trim();
        if (response.startsWith("\uFEFF")) {
            response = response.substring(1);
        }
        
        notifyLog(context.getString(R.string.response_preview_first_100_characters) + response.substring(0, Math.min(100, response.length())));
        
        // 验证是否为 JSON
        if (!response.startsWith("{") && !response.startsWith("[")) {
            throw new Exception(context.getString(R.string.the_returned_content_is_not_in_json_format_startin) + response.substring(0, Math.min(50, response.length())));
        }
        
        return response;
    } else if (responseCode == 404) {
        conn.disconnect();
        throw new Exception(context.getString(R.string.file_does_not_exist_404));
    } else if (responseCode == 403) {
        conn.disconnect();
        throw new Exception(context.getString(R.string.access_denied_403));
    } else {
        conn.disconnect();
        throw new Exception(context.getString(R.string.http) + responseCode);
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
                        notifyDownloadError(context.getString(R.string.the_download_failed_and_the_server_returned) + response.code());
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
                    notifyDownloadError(context.getString(R.string.download_failed) + e.getMessage());
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
            String lang = java.util.Locale.getDefault().getLanguage();
            if ("zh".equals(lang)) {
                changelog = changelogObj.optString("zh", changelogObj.optString("en", context.getString(R.string.no_update_instructions_yet)));
            } else {
                changelog = changelogObj.optString("en", changelogObj.optString("zh", context.getString(R.string.no_changelog_available)));
            }
        } else {
            changelog = root.optString("changelog", context.getString(R.string.no_update_instructions_yet));
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
        notifyLog(context.getString(R.string.version_Information));
        notifyLog(context.getString(R.string.current_version) + currentVersion);
        notifyLog(context.getString(R.string.latest_version) + latestVersion);
        notifyLog(context.getString(R.string.minimum_version) + minVersion);
        notifyLog(context.getString(R.string.force_update) + forceUpdate);
        
        // 判断逻辑
        boolean isLowerThanMin = isVersionLowerThan(currentVersion, minVersion);
        notifyLog(context.getString(R.string.lower_than_the_minimum_version) + isLowerThanMin);
        
        // 检查是否需要强制更新
        if (forceUpdate || isLowerThanMin) {
            notifyLog(context.getString(R.string.trigger_a_forced_update));
            notifyForceUpdate(latestVersion, currentVersion, minVersion, sources, changelog);
            return;
        }

        // 检查是否有新版本
        boolean hasNewVersion = isNewerVersion(currentVersion, latestVersion);
        notifyLog(context.getString(R.string.there_is_a_new_version) + hasNewVersion);
        
        if (hasNewVersion) {
            notifyUpdateAvailable(latestVersion, sources, changelog, forceUpdate, isManual);
        } else {
            notifyLog(context.getString(R.string.it_is_currently_the_latest_version));
            notifyNoUpdate();
        }

    } catch (Exception e) {
        notifyError(context.getString(R.string.failed_to_parse_update_information) + e.getMessage());
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
        
        String[] currentParts = current.split("\\.");
        String[] minParts = min.split("\\.");

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

    public void installApk(File apkFile) {
    try {
        notifyLog(context.getString(R.string.start_installation));
        
        if (!apkFile.exists()) {
            notifyDownloadError(context.getString(R.string.apk_file_does_not_exist));
            return;
        }

        notifyLog(context.getString(R.string.file) + apkFile.getName() + " (" + (apkFile.length() / 1024 / 1024) + " MB)");

        // Android 8.0+ 检查安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                notifyLog(context.getString(R.string.requires_installation_permission_jumping_to_settin));
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
        notifyLog(context.getString(R.string.the_installation_interface_has_been_launched));

    } catch (Exception e) {
        notifyDownloadError(context.getString(R.string.installation_failed) + e.getMessage());
        notifyLog(context.getString(R.string.details) + android.util.Log.getStackTraceString(e));
    }
}

    private void showDownloadNotification(int progress, int max) {
        notificationBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.downloading_updates))
                .setContentText(context.getString(R.string.ready_to_download))
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
                    .setContentText(context.getString(R.string.downloaded) + percent + "%");
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

    private boolean isNewerVersion(String current, String latest) {
        try {
            String[] currentParts = current.split("\\.");
            String[] latestParts = latest.split("\\.");

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
                notifyLog(context.getString(R.string.testing_speed_looking_for_the_fastest_download_sou));
                
                // 第一步：测速，找到可用且最快的源
                List<SourceWithSpeed> availableSources = testSourcesSpeed(sources);
                
                if (availableSources.isEmpty()) {
                    notifyDownloadError(context.getString(R.string.all_download_sources_are_unavailable_please_check_));
                    return;
                }
                
                // 第二步：按速度排序，从最快的开始尝试下载
                for (int i = 0; i < availableSources.size(); i++) {
                    SourceWithSpeed sourceWithSpeed = availableSources.get(i);
                    DownloadSource source = sourceWithSpeed.source;
                    
                    try {
                        notifyLog(context.getString(R.string.receiving_from) + source.name + context.getString(R.string.download_response_time) + sourceWithSpeed.responseTime + "ms)");
                        
                        boolean success = downloadApkInternal(source.url, fileName);
                        
                        if (success) {
                            notifyLog(context.getString(R.string.from) + source.name + context.getString(R.string.download_successful));
                            return;
                        }
                    } catch (Exception e) {
                        notifyLog("❌ " + source.name + context.getString(R.string.download_failed) + e.getMessage());
                        
                        // 如果还有其他源，继续尝试
                        if (i < availableSources.size() - 1) {
                            notifyLog(context.getString(R.string.switching_to_alternate_download_source));
                        } else {
                            notifyDownloadError(context.getString(R.string.all_attempts_to_download_sources_failed_please_try));
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
                notifyLog("✅ " + source.name + context.getString(R.string.available_response) + elapsedTime + "ms)");
            } else {
                notifyLog("⚠️ " + source.name + context.getString(R.string.return_error) + responseCode);
            }
            
        } catch (Exception e) {
            notifyLog("❌ " + source.name + context.getString(R.string.connection_failed) + e.getMessage());
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
        notifyLog(context.getString(R.string.redirect_to) + newUrl);
        return downloadApkInternal(newUrl, fileName);
    }
    
    if (responseCode != 200 && responseCode != 206) {  // 206 是部分内容（Range 请求）
        conn.disconnect();
        throw new Exception(context.getString(R.string.server_returned_error) + responseCode);
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
        throw new Exception(context.getString(R.string.downloaded_file_size_is_0));
    }
    
    if (fileLength > 0 && apkFile.length() < fileLength * 0.9) {
        apkFile.delete();
        throw new Exception(context.getString(R.string.incomplete_download_expected) + fileLength + context.getString(R.string.bytes_actual) + apkFile.length() + context.getString(R.string.byte_1));
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
