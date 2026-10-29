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

/**
 * 下载管理页：列出所有已下载 / 下载中的安装包，可暂停、继续、删除。
 *
 * <p>暂停不销毁进度 —— 文件留在原地，下次继续时用 HTTP Range 接着下。
 */
final class DownloadManagerDialog {

    /** 进行中的任务，按版本号索引；退出对话框时也要能继续跑 */
    static final java.util.Map<String, GameDownloader.Task> RUNNING =
            new java.util.HashMap<String, GameDownloader.Task>();

    private final Activity activity;
    private LinearLayout list;
    private TextView tvEmpty;
    /** 当前正在下载那一行的进度条，用来实时更新 */
    private static ProgressBar currentBar;

    DownloadManagerDialog(Activity activity) {
        this.activity = activity;
    }

    void show() {
        float dp = activity.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        root.setPadding(pad, pad, pad, pad);

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

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_download_page))
                .setView(root)
                .setPositiveButton(activity.getString(R.string.btn_ok), null)
                .show();

        refresh();
    }

    void refresh() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                float dp = activity.getResources().getDisplayMetrics().density;

                File[] files = GameDownloader.listAll(activity);
                java.util.List<File> show = new java.util.ArrayList<File>();
                for (File f : files) {
                    if (f != null && f.isFile() && f.getName().endsWith(".apk") && f.length() > 0) {
                        show.add(f);
                    }
                }

                tvEmpty.setVisibility(show.isEmpty() ? View.VISIBLE : View.GONE);

                java.util.Collections.sort(show, new java.util.Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
                    }
                });

                for (File f : show) {
                    list.addView(buildRow(f, dp));
                }
            }
        });
    }

    private View buildRow(final File f, float dp) {
        final String version = versionFromFile(f.getName());

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int vp = (int) (10 * dp);
        row.setPadding(0, vp, 0, vp);

        TextView tvName = new TextView(activity);
        tvName.setText(version);
        tvName.setTextSize(15f);
        tvName.setTextColor(Color.BLACK);
        tvName.setTypeface(null, Typeface.BOLD);
        row.addView(tvName);

        TextView tvInfo = new TextView(activity);
        tvInfo.setText(GameDownloader.fmt(f.length()));
        tvInfo.setTextSize(11f);
        tvInfo.setTextColor(Color.parseColor("#888888"));
        row.addView(tvInfo);

        final GameDownloader.Task running = RUNNING.get(version);

        final ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setVisibility(running != null ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (4 * dp));
        bpl.topMargin = (int) (6 * dp);
        bar.setLayoutParams(bpl);
        row.addView(bar);
        if (running != null) {
            currentBar = bar;
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        int tm = (int) (8 * dp);
        LinearLayout.LayoutParams apl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apl.topMargin = tm;
        actions.setLayoutParams(apl);

        // ---- 暂停 / 继续 ----
        Button btnPause = new Button(activity);
        btnPause.setTextSize(12f);
        btnPause.setBackground(activity.getResources().getDrawable(R.drawable.bg_black_border));
        btnPause.setTextColor(Color.BLACK);
        btnPause.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (running != null) {
            btnPause.setText(activity.getString(R.string.btn_pause));
            btnPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    running.pause();
                    RUNNING.remove(version);
                    refresh();
                }
            });
        } else {
            btnPause.setText(activity.getString(R.string.btn_resume));
            btnPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 继续需要直链，这里从版本库里重新解析一次
                    startResume(version, f);
                }
            });
        }
        actions.addView(btnPause);

        // ---- 删除 ----
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
                                        GameDownloader.Task t = RUNNING.remove(version);
                                        if (t != null) {
                                            t.cancel();
                                        }
                                        f.delete();
                                        refresh();
                                    }
                                })
                        .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                        .show();
            }
        });
        actions.addView(btnDel);

        row.addView(actions);

        View line = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = (int) (10 * dp);
        line.setLayoutParams(lp);
        line.setBackgroundColor(Color.parseColor("#E0E0E0"));
        row.addView(line);

        return row;
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
        if (n.endsWith("_v8a")) {
            n = n.substring(0, n.length() - 4);
        }
        return n;
    }

    /** 续传：重新问一次直链，然后从已有字节继续 */
    private void startResume(final String version, final File f) {
        VersionRepo.searchOfficial(version, false, new VersionRepo.Callback() {
            @Override
            public void onResult(java.util.List<VersionRepo.Entry> entries, String error) {
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

    /** 真正开始下载，进度写进这一行 */
    void startDownload(final String versionAll, final String directUrl) {
        final GameDownloader.Task task = GameDownloader.download(
                activity, directUrl, versionAll, new GameDownloader.Listener() {
                    @Override
                    public void onProgress(final long downloaded, final long total) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (total > 0 && currentBar != null) {
                                    currentBar.setProgress((int) (downloaded * 100 / total));
                                }
                            }
                        });
                    }

                    @Override public void onLog(String message) { }

                    @Override
                    public void onDone(final File file) {
                        RUNNING.remove(versionAll);
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
                        RUNNING.remove(versionAll);
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
                                refresh();
                            }
                        });
                    }
                });

        RUNNING.put(versionAll, task);
        refresh();
    }

    private void toast(final String msg) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show();
    }
}
