package yhjmew.no.ore.ui;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 让下载在后台活下来。
 *
 * <p>下载本身跑在 {@link GameDownloader} 的线程里，这个 Service 不做实际工作，
 * 只是把那条通知提升成前台，把进程优先级提上去。
 *
 * <p>原来没有它：用户切到后台/锁屏，进程被降级，socket 很快被系统掐断，
 * 表现就是 "Software caused connection abort"，或者下载直接停住，
 * 得切回来才继续。
 *
 * <p>关键点一：前台通知**就是**下载进度通知（同一个 id）。
 * 否则用户会在通知栏看到两条 —— 一条干巴巴的"正在下载"，一条能展开暂停的。
 * {@code startForeground} 之后 {@link GameDownloader} 用 {@code notify()}
 * 更新同一个 id，Android 会原地替换，前台身份也保留。
 *
 * <p>关键点二：停止时不能靠 {@code startService(ACTION_STOP)} ——
 * Android 8 起禁止从后台启动普通服务，那条 intent 大概率被丢掉，
 * 结果就是前台通知永远挂在那儿。所以直接持有实例调 stopForeground。
 *
 * <p>minSdk 14 不支持 startForegroundService（API 26+），
 * 所以这里按版本分两条路走。
 */
public class DownloadService extends Service {

    static final String ACTION_START = "yhjmew.no.ore.ui.DL_START";
    /** 停服务，连通知一起撤掉 */
    static final String ACTION_STOP = "yhjmew.no.ore.ui.DL_STOP";
    /** 停服务，但把通知留着（下载完成 / 已暂停那种还要用户点的情况） */
    static final String ACTION_STOP_KEEP = "yhjmew.no.ore.ui.DL_STOP_KEEP";

    /** 同进程直连用的实例引用，省得靠 startService 传控制指令 */
    private static volatile DownloadService instance;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;

        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopForegroundCompat(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_KEEP.equals(action)) {
            // false = 别把通知撤掉，它还要留在通知栏里让用户点
            stopForegroundCompat(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        String version = intent == null ? null : intent.getStringExtra(GameDownloader.EXTRA_VERSION);
        if (version == null) {
            // 系统重建服务时 intent 是 null：挑一个还在跑的任务，别显示一条空通知
            version = GameDownloader.firstActiveVersion();
        }

        try {
            // 通知渠道必须在 startForeground 之前建好，否则 O+ 上会直接被拒
            GameDownloader.createChannel(this);
            int id = GameDownloader.notifIdFor(version);
            Notification n = GameDownloader.buildNotification(this, version, false);
            startForeground(id, n);
        } catch (Throwable t) {
            // startForegroundService 之后 5 秒内必须 startForeground，
            // 否则系统直接判 ANR。所以这里哪怕用的是最简通知也得补一次。
            try {
                Notification.Builder fb =
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                                ? new Notification.Builder(this, GameDownloader.CHANNEL_ID)
                                : new Notification.Builder(this);
                fb.setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle(getString(R.string.log_downloading));
                startForeground(GameDownloader.notifIdFor(version), fb.build());
            } catch (Throwable ignore) {
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopForegroundCompat(boolean removeNotification) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                stopForeground(removeNotification ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(removeNotification);
            }
        } catch (Throwable ignore) {
        }
    }

    /** Service 的 stopForeground/stopSelf 要在主线程调，下载线程那边只投递 */
    private static void runOnMain(Runnable r) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        } catch (Throwable ignore) {
        }
    }

    static void start(final Context ctx, final String version) {
        // 服务已经在跑（最常见：继续下载）就直接原地更新前台通知。
        // 从通知栏点「继续」时应用可能处于后台，Android 12+ 不允许后台
        // 启动前台服务，这里绕开那条路。
        final DownloadService running = instance;
        if (running != null) {
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    try {
                        GameDownloader.createChannel(ctx);
                        running.startForeground(
                                GameDownloader.notifIdFor(version),
                                GameDownloader.buildNotification(ctx, version, false));
                    } catch (Throwable ignore) {
                    }
                }
            });
            return;
        }
        try {
            Intent it = new Intent(ctx, DownloadService.class);
            it.setAction(ACTION_START);
            if (version != null) {
                it.putExtra(GameDownloader.EXTRA_VERSION, version);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(it);
            } else {
                ctx.startService(it);
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 保持前台身份，只是把通知换成暂停/继续那版。
     *
     * <p>暂停时不能撤前台：撤了进程就可能被回收，「继续」按钮也就没得点。
     */
    static void keepForeground(final Context ctx, final String version, final boolean paused) {
        final DownloadService running = instance;
        if (running == null) {
            // 服务已经不在了（比如进程重建过）。这时候不重新拉前台 ——
            // 暂停状态本来就不该占着前台，通知照样挂在栏里。
            return;
        }
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    running.startForeground(GameDownloader.notifIdFor(version),
                            GameDownloader.buildNotification(ctx, version, paused));
                } catch (Throwable ignore) {
                }
            }
        });
    }

    /** 停服务并撤掉通知 */
    static void stop(final Context ctx) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                DownloadService s = instance;
                if (s != null) {
                    s.stopForegroundCompat(true);
                    s.stopSelf();
                } else {
                    sendStop(ctx, ACTION_STOP);
                }
            }
        });
    }

    /** 停服务但保留通知（给「已暂停」「下载完成」留个可点的入口） */
    static void stopKeepNotification(final Context ctx) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                DownloadService s = instance;
                if (s != null) {
                    s.stopForegroundCompat(false);
                    s.stopSelf();
                } else {
                    sendStop(ctx, ACTION_STOP_KEEP);
                }
            }
        });
    }

    private static void sendStop(Context ctx, String action) {
        try {
            Intent it = new Intent(ctx, DownloadService.class);
            it.setAction(action);
            ctx.startService(it);
        } catch (Throwable ignore) {
        }
    }
}
