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
            "https://raw.githubusercontent.com/yinghuajimew/yhjmew.no.ore.ui/refs/heads/main/docs/update.json",
            "https://yhjmew-no-ore-ui.pages.dev/update.json"
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
                    "应用更新下载",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示应用更新下载进度");
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
                
                for (String checkUrl : UPDATE_CHECK_URLS) {
                    try {
                        String json = fetchUrl(checkUrl, 10000);  // ← 这里应该能访问
                        if (json != null && json.length() > 0) {
                            parseUpdateResponse(json, currentVersion, isManual);
                            return;
                        }
                    } catch (Exception e) {
                        // 继续尝试下一个源
                    }
                }
                
                notifyError("无法连接到更新服务器，请检查网络连接");
            }
        }).start();
    }

    /**
     * 获取 URL 内容（类的成员方法）
     */
    private String fetchUrl(String urlString, int timeout) throws Exception {
        java.net.URL url = new java.net.URL(urlString);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setRequestProperty("User-Agent", "OreUI-Remover/" + getCurrentVersion());

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            java.io.InputStream is = conn.getInputStream();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, "UTF-8")
            );
            StringBuilder result = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            reader.close();
            conn.disconnect();
            return result.toString();
        }
        
        conn.disconnect();
        return null;
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
                        notifyDownloadError("下载失败，服务器返回: " + response.code());
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
                    notifyDownloadError("下载失败: " + e.getMessage());
                }
            }
        }).start();
    }

    private void parseUpdateResponse(String json, String currentVersion, boolean isManual) {  // ← 新增 isManual 参数
    try {
        JSONObject root = new JSONObject(json);
        
        String latestVersion = root.getString("version");
        boolean forceUpdate = root.optBoolean("forceUpdate", false);
        String minVersion = root.optString("minVersion", "0.0.0");
        
        // 解析多语言更新日志
        String changelog;
        JSONObject changelogObj = root.optJSONObject("changelog");
        if (changelogObj != null) {
            String lang = java.util.Locale.getDefault().getLanguage();
            if ("zh".equals(lang)) {
                changelog = changelogObj.optString("zh", changelogObj.optString("en", "暂无更新说明"));
            } else {
                changelog = changelogObj.optString("en", changelogObj.optString("zh", "No changelog available"));
            }
        } else {
            changelog = root.optString("changelog", "暂无更新说明");
        }
        
        // 解析下载源
        JSONArray sourcesArray = root.getJSONArray("downloadSources");
        List<DownloadSource> sources = new ArrayList<DownloadSource>();
        
        for (int i = 0; i < sourcesArray.length(); i++) {
            JSONObject sourceObj = sourcesArray.getJSONObject(i);
            String name = sourceObj.getString("name");
            String url = sourceObj.getString("url");
            String region = sourceObj.optString("region", "");
            
            sources.add(new DownloadSource(name, url, region));
        }

        // 检查是否需要强制更新
        if (forceUpdate || isVersionLowerThan(currentVersion, minVersion)) {
            notifyForceUpdate(latestVersion, currentVersion, minVersion, sources, changelog);
            return;
        }

        // 检查是否有新版本
        if (isNewerVersion(currentVersion, latestVersion)) {
            notifyUpdateAvailable(latestVersion, sources, changelog, forceUpdate, isManual);  // ← 传递 isManual
        } else {
            notifyNoUpdate();
        }

    } catch (Exception e) {
        notifyError("解析更新信息失败: " + e.getMessage());
    }
}

/**
 * 检查版本是否低于最低版本
 */
private boolean isVersionLowerThan(String current, String min) {
    try {
        String[] currentParts = current.split("\\.");
        String[] minParts = min.split("\\.");

        int length = Math.max(currentParts.length, minParts.length);
        for (int i = 0; i < length; i++) {
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            int minPart = i < minParts.length ? Integer.parseInt(minParts[i]) : 0;

            if (currentPart < minPart) {
                return true;
            } else if (currentPart > minPart) {
                return false;
            }
        }
        return false;
    } catch (Exception e) {
        return false;
    }
}

    public void installApk(File apkFile) {
    try {
        notifyLog("=== 开始安装 ===");
        
        if (!apkFile.exists()) {
            notifyDownloadError("APK 文件不存在");
            return;
        }

        notifyLog("📦 文件: " + apkFile.getName() + " (" + (apkFile.length() / 1024 / 1024) + " MB)");

        // Android 8.0+ 检查安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                notifyLog("⚠️ 需要安装权限，正在跳转设置...");
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
        notifyLog("✅ 已调起安装界面");

    } catch (Exception e) {
        notifyDownloadError("安装失败: " + e.getMessage());
        notifyLog("详情: " + android.util.Log.getStackTraceString(e));
    }
}

    private void showDownloadNotification(int progress, int max) {
        notificationBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载更新")
                .setContentText("准备下载...")
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
                    .setContentText("已下载 " + percent + "%");
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
                notifyLog("🔍 正在测速，寻找最快的下载源...");
                
                // 第一步：测速，找到可用且最快的源
                List<SourceWithSpeed> availableSources = testSourcesSpeed(sources);
                
                if (availableSources.isEmpty()) {
                    notifyDownloadError("所有下载源均不可用，请检查网络连接");
                    return;
                }
                
                // 第二步：按速度排序，从最快的开始尝试下载
                for (int i = 0; i < availableSources.size(); i++) {
                    SourceWithSpeed sourceWithSpeed = availableSources.get(i);
                    DownloadSource source = sourceWithSpeed.source;
                    
                    try {
                        notifyLog("📥 正在从 " + source.name + " 下载... (响应时间: " + sourceWithSpeed.responseTime + "ms)");
                        
                        boolean success = downloadApkInternal(source.url, fileName);
                        
                        if (success) {
                            notifyLog("✅ 从 " + source.name + " 下载成功！");
                            return;
                        }
                    } catch (Exception e) {
                        notifyLog("❌ " + source.name + " 下载失败: " + e.getMessage());
                        
                        // 如果还有其他源，继续尝试
                        if (i < availableSources.size() - 1) {
                            notifyLog("⚠️ 正在切换到备用下载源...");
                        } else {
                            notifyDownloadError("所有下载源均尝试失败，请稍后重试");
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
                conn.setRequestMethod("HEAD");  // 只获取头部，不下载文件
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "OreUI-Remover/" + getCurrentVersion());
                
                int responseCode = conn.getResponseCode();
                long elapsedTime = System.currentTimeMillis() - startTime;
                conn.disconnect();
                
                if (responseCode == 200 || responseCode == 302) {
                    results.add(new SourceWithSpeed(source, elapsedTime));
                    notifyLog("✅ " + source.name + " 可用 (响应: " + elapsedTime + "ms)");
                } else {
                    notifyLog("⚠️ " + source.name + " 返回错误: " + responseCode);
                }
                
            } catch (Exception e) {
                notifyLog("❌ " + source.name + " 连接失败: " + e.getMessage());
            }
        }
        
        // 按响应时间排序（从快到慢）
        java.util.Collections.sort(results, new java.util.Comparator<SourceWithSpeed>() {
            @Override
            public int compare(SourceWithSpeed s1, SourceWithSpeed s2) {
                return Long.compare(s1.responseTime, s2.responseTime);
            }
        });
        
        return results;
    }

    /**
     * 内部下载方法（返回是否成功）
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
        conn.setRequestProperty("User-Agent", "OreUI-Remover/" + getCurrentVersion());

        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 302) {
            conn.disconnect();
            throw new Exception("服务器返回错误: " + responseCode);
        }

        // 处理重定向
        if (responseCode == 302) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (java.net.HttpURLConnection) new java.net.URL(newUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "OreUI-Remover/" + getCurrentVersion());
        }

        int fileLength = conn.getContentLength();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(apkFile);

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

        // 验证文件是否完整
        if (apkFile.length() == 0) {
            apkFile.delete();
            throw new Exception("下载的文件大小为 0");
        }

        cancelNotification();
        notifyDownloadComplete(apkFile);
        return true;
    }

    // ... 保持原有的其他方法 ...

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