package yhjmew.no.ore.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下载管理页：列出已下载 / 下载中的安装包，可暂停、继续、删除、直接处理。
 *
 * <p>进度信息存在 {@link GameDownloader.Task} 里（静态），
 * 所以关掉对话框再打开、或者从版本列表重新进来，都能立刻看到真实进度，
 * 不用等下一次回调。
 *
 * <p>列表的版本来源有三处：磁盘上的成品 apk、磁盘上的 .partN 残片、
 * 以及还在跑的任务。只扫 apk 是不够的 —— 现在多段下载时成品要等全部
 * 拼完才出现，只认 apk 会导致"正在下载"的那一行根本不显示。
 */
final class DownloadManagerDialog {

    private final Activity activity;

    /** 每次 refresh() 重新构建的行视图，key 是版本号 */
    private final java.util.Map<String, Row> rows =
            new java.util.HashMap<String, Row>();

    private LinearLayout list;
    private TextView tvEmpty;
    private TextView tvDir;

    private AlertDialog dialog;

    /** refresh() 正在重建视图时，别让 ticker 再插一次 refresh */
    private boolean refreshing;

    private static final class Row {
        String version;
        ProgressBar bar;
        TextView tvPct;
        TextView tvSize;
        TextView tvState;
        Button btnPause;
        /** 上一帧是否在跑，用来发现「刚停下」这种状态跳变 */
        boolean wasRunning;
    }

    DownloadManagerDialog(Activity activity) {
        this.activity = activity;
    }

    void show() {
        float dp = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // ---- 保存位置：用户必须知道文件下到哪了 ----
        tvDir = new TextView(activity);
        tvDir.setTextSize(11f);
        tvDir.setTextColor(Color.parseColor("#666666"));

        String dirText = activity.getString(R.string.msg_save_to) + "\n"
                + GameDownloader.displayDir(activity);
        if (!GameDownloader.usingPublicDir(activity)) {
            // 没拿到「所有文件访问权限」，退回了私有目录 —— 要说清楚
            tvDir.setTextColor(Color.parseColor("#C62828"));
            dirText = activity.getString(R.string.msg_download_dir_fallback) + "\n" + dirText;
        }
        tvDir.setText(dirText);
        tvDir.setPadding(0, 0, 0, (int) (8 * dp));
        root.addView(tvDir);

        tvEmpty = new TextView(activity);
        tvEmpty.setText(activity.getString(R.string.msg_no_downloads));
        tvEmpty.setTextSize(13f);
        tvEmpty.setTextColor(Color.parseColor("#888888"));
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        ScrollView scroll = new ScrollView(activity);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);

        dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_download_page))
                .setView(root)
                .setPositiveButton(activity.getString(R.string.btn_ok), null)
                .create();

        // 关闭时停掉刷新循环，否则对话框没了还在每 500ms 跑
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (mTicker != null && list != null) {
                    list.removeCallbacks(mTicker);
                }
                dialog = null;
                list = null;
            }
        });
        dialog.show();

        refresh();

        // 每 500ms 刷一次界面 —— 下载线程只更新 Task 里的数字，
        // 由这里统一读出来显示，省得两边抢 UI 线程。
        mTicker = new Runnable() {
            @Override
            public void run() {
                updateProgressOnly();
                if (dialog != null && dialog.isShowing() && list != null) {
                    list.postDelayed(this, 500);
                }
            }
        };
        list.postDelayed(mTicker, 500);
    }

    private Runnable mTicker;

    /** 打开下载页并立刻开始下载（从版本列表点进来时用） */
    void showAndDownload(final String versionAll, final String directUrl) {
        show();
        startDownload(versionAll, directUrl);
    }

    void refresh() {
        if (list == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (list == null || refreshing) {
                    return;
                }
                refreshing = true;
                try {
                    rebuild();
                } finally {
                    refreshing = false;
                }
                // 建完再刷一次数字，别让进度条空着等下一次 ticker
                updateProgressOnly();
            }
        });
    }

    private void rebuild() {
        rows.clear();
        list.removeAllViews();

        float dp = activity.getResources().getDisplayMetrics().density;

        // 1) 磁盘上的成品
        List<File> apks = new ArrayList<File>();
        for (File f : GameDownloader.listAll(activity)) {
            if (f != null && f.isFile() && f.getName().endsWith(".apk") && f.length() > 0) {
                apks.add(f);
            }
        }

        // 2) 还没拼好的（.partN 残片），以及 3) 正在跑但一个字节都还没落盘的
        Set<String> versions = new HashSet<String>();
        for (File f : apks) {
            versions.add(versionFromFile(f.getName()));
        }
        for (File f : GameDownloader.listAll(activity)) {
            if (f != null && f.isFile() && f.getName().indexOf(".part") > 0) {
                versions.add(versionFromFile(f.getName()));
            }
        }
        for (Map.Entry<String, GameDownloader.Task> e : GameDownloader.allTasks().entrySet()) {
            GameDownloader.Task t = e.getValue();
            // 在跑的，以及暂停着的（暂停那一刻可能还没写出任何 .part）
            if (t != null && (!t.finished || (t.paused && !t.cancelled))) {
                versions.add(e.getKey());
            }
        }

        // 成品排前面，按修改时间倒序；没有成品（只下了半截）的排后面
        final java.util.Map<String, Long> order = new java.util.HashMap<String, Long>();
        for (File f : apks) {
            order.put(versionFromFile(f.getName()), Long.valueOf(f.lastModified()));
        }
        List<String> sorted = new ArrayList<String>(versions);
        Collections.sort(sorted, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                long ta = order.containsKey(a) ? order.get(a).longValue() : 0L;
                long tb = order.containsKey(b) ? order.get(b).longValue() : 0L;
                if (ta == tb) {
                    return b.compareTo(a);
                }
                return tb > ta ? 1 : -1;
            }
        });

        tvEmpty.setVisibility(sorted.isEmpty() ? View.VISIBLE : View.GONE);

        for (String ver : sorted) {
            list.addView(buildRow(ver, dp));
        }
    }

    /** 只更新进度数字，不重建视图（每 500ms 跑一次） */
    private void updateProgressOnly() {
        if (refreshing) {
            return;
        }
        boolean needRebuild = false;

        for (final Map.Entry<String, Row> e : rows.entrySet()) {
            String ver = e.getKey();
            Row r = e.getValue();
            GameDownloader.Task t = GameDownloader.taskOf(ver);
            boolean running = t != null && !t.finished;

            if (running != r.wasRunning) {
                // 从「在跑」变成「停了」：按钮要换成「继续」，得重建
                needRebuild = true;
                r.wasRunning = running;
            }

            if (!running) {
                r.bar.setVisibility(View.GONE);
                r.tvPct.setVisibility(View.GONE);
                continue;
            }

            r.bar.setVisibility(View.VISIBLE);
            r.tvPct.setVisibility(View.VISIBLE);

            int pct = t.percent();
            r.bar.setProgress(pct);
            r.tvPct.setText(pct + "%");

            String size;
            long total = t.lastTotal;
            long done = t.lastDownloaded;
            if (total > 0) {
                size = GameDownloader.fmt(done) + " / " + GameDownloader.fmt(total);
            } else {
                size = GameDownloader.fmt(done);
            }
            if (t.segments > 1) {
                size = size + "  ·  "
                        + activity.getString(R.string.msg_download_threads, t.segments);
            }
            r.tvSize.setText(size);

            r.btnPause.setText(activity.getString(R.string.btn_pause));
        }

        if (needRebuild && list != null) {
            // 下一帧重建，别在遍历 rows 的时候把 rows 换掉
            list.post(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
        }
    }

    private View buildRow(final String version, float dp) {
        Row row = new Row();
        row.version = version;

        File apk = GameDownloader.targetFile(activity, version);
        boolean complete = apk.isFile() && apk.length() > 0;
        long onDisk = GameDownloader.downloadedBytes(activity, version);

        GameDownloader.Task task = GameDownloader.taskOf(version);
        boolean running = task != null && !task.finished;
        boolean paused = task != null && task.finished && task.paused;

        row.wasRunning = running;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int vp = (int) (10 * dp);
        box.setPadding(0, vp, 0, vp);

        // 版本号 + 百分比同一行
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvName = new TextView(activity);
        tvName.setText(version);
        tvName.setTextSize(15f);
        tvName.setTextColor(Color.BLACK);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tvName);

        row.tvPct = new TextView(activity);
        row.tvPct.setTextSize(14f);
        row.tvPct.setTextColor(Color.parseColor("#2E7D32"));
        row.tvPct.setTypeface(null, Typeface.BOLD);
        head.addView(row.tvPct);

        box.addView(head);

        row.tvSize = new TextView(activity);
        row.tvSize.setTextSize(11f);
        row.tvSize.setTextColor(Color.parseColor("#888888"));
        row.tvSize.setText(GameDownloader.fmt(complete ? apk.length() : onDisk));
        box.addView(row.tvSize);

        row.bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        row.bar.setMax(100);
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * dp));
        bpl.topMargin = (int) (6 * dp);
        row.bar.setLayoutParams(bpl);
        row.bar.setVisibility(View.GONE);
        box.addView(row.bar);

        // 状态行：已下载 / 已暂停 / 下了多少
        row.tvState = new TextView(activity);
        row.tvState.setTextSize(11f);
        if (running) {
            row.tvState.setText(activity.getString(R.string.log_downloading));
            row.tvState.setTextColor(Color.parseColor("#1565C0"));
        } else if (paused) {
            row.tvState.setText(activity.getString(R.string.msg_download_paused_label)
                    + "  " + GameDownloader.fmt(onDisk));
            row.tvState.setTextColor(Color.parseColor("#EF6C00"));
        } else if (complete) {
            row.tvState.setText(activity.getString(R.string.msg_already_downloaded));
            row.tvState.setTextColor(Color.parseColor("#2E7D32"));
        } else if (onDisk > 0) {
            row.tvState.setText(activity.getString(R.string.msg_partial_downloaded)
                    + GameDownloader.fmt(onDisk));
            row.tvState.setTextColor(Color.parseColor("#EF6C00"));
        } else {
            row.tvState.setText(activity.getString(R.string.msg_not_downloaded));
            row.tvState.setTextColor(Color.parseColor("#999999"));
        }
        box.addView(row.tvState);

        // 按钮行
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams apl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apl.topMargin = (int) (8 * dp);
        actions.setLayoutParams(apl);

        row.btnPause = new Button(activity);
        row.btnPause.setTextSize(12f);
        row.btnPause.setBackground(activity.getResources().getDrawable(R.drawable.bg_black_border));
        row.btnPause.setTextColor(Color.BLACK);
        row.btnPause.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (running) {
            row.btnPause.setText(activity.getString(R.string.btn_pause));
            row.btnPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    GameDownloader.Task t = GameDownloader.taskOf(version);
                    if (t != null) {
                        t.pause();
                    }
                    refresh();
                }
            });
        } else if (complete) {
            // 已经下完了，「继续」在这儿其实是从头下一份新的 ——
            // 文案得说实话，而且要先确认，别让人以为点一下只是打开
            row.btnPause.setText(activity.getString(R.string.btn_redownload));
            row.btnPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmRedownload(version);
                }
            });
        } else {
            row.btnPause.setText(activity.getString(R.string.btn_resume));
            row.btnPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startResume(version);
                }
            });
        }
        actions.addView(row.btnPause);

        // 已经下完的：给一个「处理」按钮，直接进普通/共存选择，
        // 不用用户再去文件管理器里把同一个包找一遍
        if (complete && !running) {
            Button btnProcess = new Button(activity);
            btnProcess.setTextSize(12f);
            btnProcess.setText(activity.getString(R.string.btn_process_this));
            btnProcess.setBackground(activity.getResources().getDrawable(R.drawable.bg_black_border));
            btnProcess.setTextColor(Color.BLACK);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            plp.leftMargin = (int) (8 * dp);
            btnProcess.setLayoutParams(plp);
            btnProcess.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmProcess(version);
                }
            });
            actions.addView(btnProcess);
        }

        Button btnDel = new Button(activity);
        btnDel.setTextSize(12f);
        btnDel.setText(activity.getString(R.string.btn_delete_download));
        btnDel.setBackground(activity.getResources().getDrawable(R.drawable.bg_black_border));
        btnDel.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = (int) (8 * dp);
        btnDel.setLayoutParams(dlp);
        btnDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.title_delete_download))
                        .setMessage(activity.getString(R.string.msg_confirm_delete_download, version))
                        .setPositiveButton(activity.getString(R.string.btn_delete_download),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        GameDownloader.Task t = GameDownloader.taskOf(version);
                                        if (t != null) {
                                            t.cancel();
                                        }
                                        GameDownloader.delete(activity, version);
                                        refresh();
                                    }
                                })
                        .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                        .show();
            }
        });
        actions.addView(btnDel);

        box.addView(actions);

        View line = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = (int) (10 * dp);
        line.setLayoutParams(lp);
        line.setBackgroundColor(Color.parseColor("#E0E0E0"));
        box.addView(line);

        rows.put(version, row);
        return box;
    }

    /**
     * 点「处理」：先问普通还是共存，**选定之后整个下载页关掉**，
     * 回到主页看主界面那套处理流程（日志、进度条都在那边）。
     */
    private void confirmProcess(final String version) {
        final File target = GameDownloader.targetFile(activity, version);
        if (!target.exists()) {
            toast(activity.getString(R.string.err_apk_missing));
            return;
        }
        if (!(activity instanceof MainActivity)) {
            return;
        }
        final MainActivity main = (MainActivity) activity;
        // 先把权限要下来再问模式：没权限的话这一下会跳去授权页，
        // 下载页留在原地，用户回来还能接着点
        if (!main.ensureStoragePermission()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_choose_mode))
                .setMessage(target.getName())
                .setPositiveButton(activity.getString(R.string.btn_normal_processing),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                closeAndProcess(main, target, false);
                            }
                        })
                .setNeutralButton(activity.getString(R.string.btn_coexistence_processing),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                closeAndProcess(main, target, true);
                            }
                        })
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .show();
    }

    private void closeAndProcess(final MainActivity main, final File target,
                                 final boolean cloneMode) {
        // 先停掉刷新循环，再收起下载页，最后交给主页 ——
        // 顺序反了会看见处理日志被压在对话框底下
        if (mTicker != null && list != null) {
            list.removeCallbacks(mTicker);
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        main.startProcessingFile(target, cloneMode);
    }

    /** 从 Minecraft_1.26.60.4_v8a.apk（或它的 .part0）反推 1.26.60.4 */
    private static String versionFromFile(String name) {
        String n = name;
        int part = n.indexOf(".part");
        if (part > 0) {
            n = n.substring(0, part);
        }
        if (n.startsWith("Minecraft_")) {
            n = n.substring("Minecraft_".length());
        }
        if (n.endsWith(".tmp")) {
            n = n.substring(0, n.length() - 4);
        }
        if (n.endsWith(".apk")) {
            n = n.substring(0, n.length() - 4);
        }
        if (n.endsWith("_v8a") || n.endsWith("_v7a")) {
            n = n.substring(0, n.length() - 4);
        }
        return n;
    }

    /**
     * 继续：任务句柄还在（比如刚在界面上点了暂停，或通知栏点的）就用它的 url 直接续，
     * 省一次 API 查询；进程被杀过就没句柄了，只能重新问一次直链。
     */
    private void startResume(final String version) {
        if (!GameDownloader.isRunning(version)) {
            GameDownloader.Task t = GameDownloader.taskOf(version);
            if (t != null && t.url != null) {
                // 有 url 就直接续，省一次 API 查询。
                // 别在这里改 t.paused / t.finished —— 下载线程还在收尾，
                // 改了会让它跳出重试循环。交给 resumeTask 等它退干净再接手。
                GameDownloader.resumeTask(activity, version, silentListener(version));
                refresh();
                return;
            }
        }

        VersionRepo.searchOfficial(version, false, new VersionRepo.Callback() {
            @Override
            public void onResult(List<VersionRepo.Entry> entries, String error) {
                if (error != null || entries.isEmpty()) {
                    toast(activity.getString(R.string.err_resume_lookup));
                    return;
                }
                final VersionRepo.Entry e = entries.get(0);
                final String slug = VersionRepo.oneDriveSlug(e);
                if (slug == null) {
                    toast(activity.getString(R.string.err_no_onedrive_link_hint));
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final String direct = VersionRepo.resolveDirectLink(slug);
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    startDownload(e.versionAll, direct);
                                }
                            });
                        } catch (Exception ex) {
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    toast(activity.getString(R.string.err_no_download_link));
                                }
                            });
                        }
                    }
                }).start();
            }
        });
    }

    /** 已经下完的包再点一次：说清楚是覆盖，再开 */
    private void confirmRedownload(final String version) {
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_confirm_download))
                .setMessage(activity.getString(R.string.msg_confirm_redownload, version))
                .setPositiveButton(activity.getString(R.string.btn_redownload),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                startResume(version);
                            }
                        })
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .show();
    }

    /** 续传用：只弹 toast，不动这个可能已经被关掉的对话框 */
    private GameDownloader.Listener silentListener(final String version) {
        return new GameDownloader.Listener() {
            @Override
            public void onProgress(long downloaded, long total) {
            }

            @Override
            public void onLog(String message) {
            }

            @Override
            public void onDone(File file) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(activity.getString(R.string.msg_download_finished,
                                GameDownloader.fmt(file.length())));
                        refresh();
                    }
                });
            }

            @Override
            public void onError(final String message) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(activity.getString(R.string.err_download_failed) + message);
                        refresh();
                    }
                });
            }

            @Override
            public void onPaused(long downloaded) {
            }

            @Override
            public void onRetry(int attempt, int max, String reason) {
            }
        };
    }

    /** 真正开始下载 */
    void startDownload(final String versionAll, final String directUrl) {
        // 已有同名任务在跑就不重复开
        GameDownloader.Task exist = GameDownloader.taskOf(versionAll);
        if (exist != null && !exist.finished) {
            refresh();
            return;
        }

        GameDownloader.download(activity, directUrl, versionAll,
                new GameDownloader.Listener() {
                    @Override
                    public void onProgress(long downloaded, long total) {
                        // 由 updateProgressOnly 定时读取，这里不用动 UI
                    }

                    @Override
                    public void onLog(String message) {
                    }

                    @Override
                    public void onDone(final File file) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast(activity.getString(R.string.msg_download_finished,
                                        GameDownloader.fmt(file.length())));
                                refresh();
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast(activity.getString(R.string.err_download_failed) + message);
                                refresh();
                            }
                        });
                    }

                    @Override
                    public void onPaused(long downloaded) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast(activity.getString(R.string.msg_download_paused,
                                        GameDownloader.fmt(downloaded)));
                                refresh();
                            }
                        });
                    }

                    @Override
                    public void onRetry(int attempt, int max, String reason) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (tvEmpty != null && tvEmpty.getVisibility() == View.VISIBLE) {
                                    tvEmpty.setText(activity.getString(
                                            R.string.msg_download_retrying));
                                    tvEmpty.setVisibility(View.VISIBLE);
                                }
                            }
                        });
                    }
                });

        refresh();
    }

    private void toast(final String msg) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show();
    }
}
