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
import java.util.List;

/**
 * 下载管理页：列出已下载 / 下载中的安装包，可暂停、继续、删除。
 *
 * <p>进度信息存在 {@link GameDownloader.Task} 里（静态），
 * 所以关掉对话框再打开、或者从版本列表重新进来，都能立刻看到真实进度，
 * 不用等下一次回调。
 */
final class DownloadManagerDialog {

    private final Activity activity;

    /** 每次 show() 重新构建的行视图，key 是版本号 */
    private final java.util.Map<String, Row> rows =
            new java.util.HashMap<String, Row>();

    private LinearLayout list;
    private TextView tvEmpty;
    private TextView tvDir;

    private AlertDialog dialog;

    private static final class Row {
        ProgressBar bar;
        TextView tvPct;
        TextView tvSize;
        TextView tvState;
        Button btnPause;
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
        tvDir.setText(activity.getString(R.string.msg_save_to) + "\n"
                + GameDownloader.displayDir(activity));
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
                if (dialog != null && dialog.isShowing()) {
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
                if (list == null) {
                    return;
                }
                rows.clear();
                list.removeAllViews();

                float dp = activity.getResources().getDisplayMetrics().density;

                // 磁盘上的文件
                List<File> show = new ArrayList<File>();
                for (File f : GameDownloader.listAll(activity)) {
                    if (f != null && f.isFile() && f.getName().endsWith(".apk") && f.length() > 0) {
                        show.add(f);
                    }
                }

                // 正在下但还没落盘的（刚点开始，文件还没建）
                java.util.Set<String> onDisk = new java.util.HashSet<String>();
                for (File f : show) {
                    onDisk.add(versionFromFile(f.getName()));
                }

                Collections.sort(show, new java.util.Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
                    }
                });

                tvEmpty.setVisibility(show.isEmpty() ? View.VISIBLE : View.GONE);

                for (File f : show) {
                    list.addView(buildRow(versionFromFile(f.getName()), f.length(), dp));
                }

                updateProgressOnly();
            }
        });
    }

    /** 只更新进度数字，不重建视图（每 500ms 跑一次） */
    private void updateProgressOnly() {
        for (final java.util.Map.Entry<String, Row> e : rows.entrySet()) {
            String ver = e.getKey();
            Row r = e.getValue();
            GameDownloader.Task t = GameDownloader.taskOf(ver);

            if (t == null || t.finished) {
                r.bar.setVisibility(View.GONE);
                r.tvPct.setVisibility(View.GONE);
                continue;
            }

            r.bar.setVisibility(View.VISIBLE);
            r.tvPct.setVisibility(View.VISIBLE);

            int pct = t.percent();
            r.bar.setProgress(pct);
            r.tvPct.setText(pct + "%");

            if (t.lastTotal > 0) {
                r.tvSize.setText(GameDownloader.fmt(t.lastDownloaded)
                        + " / " + GameDownloader.fmt(t.lastTotal));
            } else {
                r.tvSize.setText(GameDownloader.fmt(t.lastDownloaded));
            }

            r.btnPause.setText(activity.getString(R.string.btn_pause));
        }
    }

    private View buildRow(final String version, long fileLength, float dp) {
        Row row = new Row();

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int vp = (int) (10 * dp);
        box.setPadding(0, vp, 0, vp);

        // 版本号 + 暂停/继续按钮同一行
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
        row.tvSize.setText(GameDownloader.fmt(fileLength));
        box.addView(row.tvSize);

        row.bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        row.bar.setMax(100);
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * dp));
        bpl.topMargin = (int) (6 * dp);
        row.bar.setLayoutParams(bpl);
        box.addView(row.bar);

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

        final GameDownloader.Task running = GameDownloader.taskOf(version);
        if (running != null && !running.finished) {
            row.btnPause.setText(activity.getString(R.string.btn_pause));
            row.btnPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    running.pause();
                    refresh();
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

    /** 从 Minecraft_1.26.60.4_v8a.apk 反推 1.26.60.4 */
    private static String versionFromFile(String name) {
        String n = name;
        if (n.startsWith("Minecraft_")) {
            n = n.substring("Minecraft_".length());
        }
        if (n.endsWith(".apk")) {
            n = n.substring(0, n.length() - 4);
        }
        if (n.endsWith("_v8a") || n.endsWith("_v7a")) {
            n = n.substring(0, n.length() - 4);
        }
        return n;
    }

    /** 续传：重新问一次直链，然后从已有字节继续 */
    private void startResume(final String version) {
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
                            toast(activity.getString(R.string.err_no_download_link));
                        }
                    }
                }).start();
            }
        });
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
                                if (tvEmpty.getVisibility() == View.VISIBLE) {
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
