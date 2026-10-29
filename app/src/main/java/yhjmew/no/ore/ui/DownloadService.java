package yhjmew.no.ore.ui;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 让下载在后台活下来。
 *
 * <p>下载本身跑在 {@link GameDownloader} 的线程里，这个 Service 不做实际工作，
 * 只是挂个前台通知把进程优先级提上去。
 *
 * <p>原来没有它：用户切到后台/锁屏，进程被降级，socket 很快被系统掐断，
 * 表现就是 "Software caused connection abort"，或者下载直接停住，
 * 得切回来才继续。
 *
 * <p>minSdk 14 不支持 startForegroundService（API 26+），
 * 所以这里按版本分两条路走。
 */
public class DownloadService extends Service {

    static final String ACTION_START = "yhjmew.no.ore.ui.DL_START";
    static final String ACTION_STOP = "yhjmew.no.ore.ui.DL_STOP";

    private static final int FOREGROUND_ID = 2002;
    private static final String CHANNEL_ID = "game_download";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            android.app.Notification.Builder b =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                            ? new android.app.Notification.Builder(this, CHANNEL_ID)
                            : new android.app.Notification.Builder(this);

            b.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(getString(R.string.log_downloading))
                    .setContentText(getString(R.string.title_download_page))
                    .setOngoing(true);

            startForeground(FOREGROUND_ID, b.build());
        } catch (Throwable ignore) {
            // 拿不到前台权限也不致命，退化成普通后台
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(android.content.Context ctx) {
        try {
            Intent it = new Intent(ctx, DownloadService.class);
            it.setAction(ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(it);
            } else {
                ctx.startService(it);
            }
        } catch (Throwable ignore) {
        }
    }

    static void stop(android.content.Context ctx) {
        try {
            Intent it = new Intent(ctx, DownloadService.class);
            it.setAction(ACTION_STOP);
            ctx.startService(it);
        } catch (Throwable ignore) {
        }
    }
}
