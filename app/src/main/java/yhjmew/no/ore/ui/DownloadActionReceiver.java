package yhjmew.no.ore.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知栏那个「暂停 / 继续」按钮的落点。
 *
 * <p>按钮本身是 {@link android.app.PendingIntent#getBroadcast}，
 * 所以点下去不会把应用拉到前台，只在后台改一下 {@link GameDownloader.Task} 的状态，
 * 接着由下载线程自己回报新通知。
 */
public class DownloadActionReceiver extends BroadcastReceiver {

    static final String ACTION_PAUSE = "yhjmew.no.ore.ui.DL_ACTION_PAUSE";
    static final String ACTION_RESUME = "yhjmew.no.ore.ui.DL_ACTION_RESUME";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String version = intent.getStringExtra(GameDownloader.EXTRA_VERSION);
        if (version == null || version.length() == 0) {
            return;
        }
        String action = intent.getAction();

        if (ACTION_PAUSE.equals(action)) {
            GameDownloader.Task t = GameDownloader.taskOf(version);
            if (t != null && !t.finished) {
                t.pause();
            } else {
                // 任务已经结束了，通知却还挂着 —— 撤掉它
                GameDownloader.cancelNotification(context, version);
            }
            return;
        }

        if (ACTION_RESUME.equals(action)) {
            boolean ok = GameDownloader.resumeTask(context, version, null);
            if (!ok) {
                // 进程被杀过、任务句柄没了 —— 把用户送回下载页自己点
                try {
                    Intent it = new Intent(context, MainActivity.class);
                    it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                    it.putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true);
                    context.startActivity(it);
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
