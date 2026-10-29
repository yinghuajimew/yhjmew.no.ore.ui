package yhjmew.no.ore.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本选择对话框：搜索 + 列表 + 下载。
 *
 * <p>三条硬性约束（来自需求）：
 * <ul>
 *   <li>只显示正式版，测试版一律不列 —— SO 不支持测试版；</li>
 *   <li>最低 1.21，更低的版本没有对应特征码；</li>
 *   <li>只用 OneDrive 那条链 —— 其余是国内网盘，要么得登录，
 *       要么只能在自家 App 里下。</li>
 * </ul>
 *
 * <p>点某个版本不直接开下，先弹确认框，避免用户误触下载几百 MB。
 */
final class VersionPickerDialog {

    /** 选好版本并要求下载 */
    interface OnPickListener {
        void onDownloadRequested(VersionRepo.Entry entry);
    }

    private final Activity activity;
    private final OnPickListener listener;

    private LinearLayout listContainer;
    private TextView tvStatus;
    private ProgressBar progress;
    private EditText etSearch;
    private String lastQuery = "";

    VersionPickerDialog(Activity activity, OnPickListener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    void show() {
        float dp = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // ---------- 搜索行 ----------
        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        etSearch = new EditText(activity);
        etSearch.setHint(activity.getString(R.string.hint_search_version));
        etSearch.setTextSize(14f);
        etSearch.setSingleLine(true);
        etSearch.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchRow.addView(etSearch);

        Button btnSearch = new Button(activity);
        btnSearch.setText(activity.getString(R.string.btn_search));
        btnSearch.setTextSize(13f);
        btnSearch.setBackground(activity.getResources().getDrawable(R.drawable.bg_black_border));
        btnSearch.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = (int) (8 * dp);
        btnSearch.setLayoutParams(bp);
        searchRow.addView(btnSearch);

        root.addView(searchRow);

        // ---------- 状态行 ----------
        tvStatus = new TextView(activity);
        tvStatus.setTextSize(12f);
        tvStatus.setTextColor(Color.parseColor("#666666"));
        int sp = (int) (6 * dp);
        tvStatus.setPadding(0, sp, 0, sp);
        root.addView(tvStatus);

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        // ---------- 列表 ----------
        ScrollView scroll = new ScrollView(activity);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollParams);

        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        root.addView(scroll);

        // ---------- 底部：进入下载管理 ----------
        Button btnDownloads = new Button(activity);
        btnDownloads.setText(activity.getString(R.string.btn_open_download_page));
        btnDownloads.setTextSize(13f);
        btnDownloads.setBackground(activity.getResources().getDrawable(R.drawable.bg_black_border));
        btnDownloads.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp2.topMargin = (int) (10 * dp);
        btnDownloads.setLayoutParams(dp2);
        btnDownloads.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new DownloadManagerDialog(activity).show();
            }
        });
        root.addView(btnDownloads);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_pick_version))
                .setView(root)
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .create();
        dialog.show();

        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch(etSearch.getText().toString().trim());
            }
        });

        // 输入停顿 500ms 自动搜，省得每次点按钮
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                final String q = s.toString().trim();
                etSearch.removeCallbacks(pendingSearch);
                etSearch.postDelayed(pendingSearch, 500);
            }
        });

        // 打开时先给个最新版本，省得用户面对空列表
        loadLatest();
    }

    private final Runnable pendingSearch = new Runnable() {
        @Override
        public void run() {
            String q = etSearch.getText().toString().trim();
            if (!q.equals(lastQuery)) {
                doSearch(q);
            }
        }
    };

    /**
     * 默认列表：先拿最新版，再拿它所属大版本的全部正式版。
     *
     * <p>原来只显示 /last_version 返回的那一条，用户看到孤零零一个 1.26.60.4，
     * 想装次新的版本就没辙了。改成拿最新版的大版本号（1.26）再去搜，
     * 把同一大版本下的正式版都列出来。
     *
     * <p>但 API 现在只有 1.21 能搜到（1.22~1.26 全返回「查询失败」），
     * 所以搜不到时至少保住最新那一条，不能什么都不显示。
     */
    private void loadLatest() {
        setLoading(true, activity.getString(R.string.msg_fetching_latest));
        VersionRepo.latestOfficial(new VersionRepo.Callback() {
            @Override
            public void onResult(final List<VersionRepo.Entry> latest, String error) {
                if (error != null || latest.isEmpty()) {
                    setLoading(false, "");
                    renderEmpty(activity.getString(R.string.msg_search_hint));
                    return;
                }

                final VersionRepo.Entry newest = latest.get(0);

                // 1.26.60.4 -> 1.26
                String[] parts = newest.versionAll.split("\\.");
                final String major = parts.length >= 2 ? parts[0] + "." + parts[1] : newest.versionAll;

                VersionRepo.searchOfficial(major, true, new VersionRepo.Callback() {
                    @Override
                    public void onResult(List<VersionRepo.Entry> list, String err2) {
                        setLoading(false, "");

                        if (list == null || list.isEmpty()) {
                            // 这个大版本搜不到 —— 至少把最新那一条显示出来
                            render(latest, activity.getString(R.string.msg_latest_version_title));
                            return;
                        }

                        // 把最新那条并进去（防重复）
                        for (VersionRepo.Entry e : latest) {
                            boolean dup = false;
                            for (VersionRepo.Entry x : list) {
                                if (x.versionAll.equals(e.versionAll)) {
                                    dup = true;
                                    break;
                                }
                            }
                            if (!dup) {
                                list.add(e);
                            }
                        }
                        // 重新按版本倒序
                        java.util.Collections.sort(list, new java.util.Comparator<VersionRepo.Entry>() {
                            @Override
                            public int compare(VersionRepo.Entry a, VersionRepo.Entry b) {
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

                        render(list, activity.getString(R.string.msg_official_versions, major));
                    }
                });
            }
        });
    }

    private void doSearch(String query) {
        if (query.isEmpty()) {
            loadLatest();
            return;
        }
        // 低于 1.21 直接拦掉，别浪费一次请求
        if (!isAtLeast1_21(query)) {
            renderEmpty(activity.getString(R.string.err_version_too_old,
                    VersionRepo.MIN_SUPPORTED_VERSION));
            return;
        }
        lastQuery = query;
        setLoading(true, activity.getString(R.string.msg_searching));

        // 先精确查，查不到再用模糊
        VersionRepo.searchOfficial(query, false, new VersionRepo.Callback() {
            @Override
            public void onResult(final List<VersionRepo.Entry> exact, String err1) {
                if (!exact.isEmpty()) {
                    setLoading(false, "");
                    render(exact, activity.getString(R.string.msg_search_result));
                    return;
                }
                // 模糊再来一次
                VersionRepo.searchOfficial(query, true, new VersionRepo.Callback() {
                    @Override
                    public void onResult(List<VersionRepo.Entry> fuzzy, String err2) {
                        setLoading(false, "");
                        if (fuzzy.isEmpty()) {
                            renderEmpty(activity.getString(R.string.msg_no_version_found, query));
                        } else {
                            render(fuzzy, activity.getString(R.string.msg_search_result));
                        }
                    }
                });
            }
        });
    }

    private static boolean isAtLeast1_21(String q) {
        String[] p = q.split("\\.");
        try {
            int major = Integer.parseInt(p[0].replaceAll("[^0-9]", ""));
            int minor = p.length > 1 ? Integer.parseInt(p[1].replaceAll("[^0-9]", "")) : 0;
            return major > 1 || (major == 1 && minor >= 21);
        } catch (Exception e) {
            return false;
        }
    }

    private void setLoading(final boolean loading, final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progress.setVisibility(loading ? View.VISIBLE : View.GONE);
                tvStatus.setText(msg);
            }
        });
    }

    private void renderEmpty(final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                listContainer.removeAllViews();
                tvStatus.setText(msg);
            }
        });
    }

    private void render(final List<VersionRepo.Entry> entries, final String title) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                listContainer.removeAllViews();
                tvStatus.setText(title + "  (" + entries.size() + ")");

                float dp = activity.getResources().getDisplayMetrics().density;

                for (final VersionRepo.Entry e : entries) {
                    listContainer.addView(buildRow(e, dp));
                }
            }
        });
    }

    private View buildRow(final VersionRepo.Entry e, float dp) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int vp = (int) (10 * dp);
        row.setPadding(0, vp, 0, vp);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvVer = new TextView(activity);
        tvVer.setText(e.versionAll);
        tvVer.setTextSize(15f);
        tvVer.setTextColor(Color.BLACK);
        tvVer.setTypeface(null, Typeface.BOLD);
        top.addView(tvVer);

        TextView tvSize = new TextView(activity);
        String size = e.fileSize == null ? "" : e.fileSize;
        // 标出会下哪个架构：有 ARMv8 就用 64 位，没有才退回 32 位
        String arch = archLabel(e);
        tvSize.setText("  " + size + (arch.isEmpty() ? "" : "  " + arch));
        tvSize.setTextSize(11f);
        tvSize.setTextColor(Color.parseColor("#888888"));
        top.addView(tvSize);

        row.addView(top);

        // 已下载标记
        boolean running = GameDownloader.isRunning(e.versionAll);
        GameDownloader.Task task = GameDownloader.taskOf(e.versionAll);
        boolean downloaded = GameDownloader.isDownloaded(activity, e.versionAll);
        long partial = GameDownloader.downloadedBytes(activity, e.versionAll);

        TextView tvState = new TextView(activity);
        tvState.setTextSize(11f);
        if (running && task != null) {
            // 正在下：显示百分比，用户不用点进去也能看到进度
            String pct = task.lastTotal > 0 ? task.percent() + "%  " : "";
            tvState.setText(activity.getString(R.string.log_downloading) + "  "
                    + pct + GameDownloader.fmt(task.lastDownloaded));
            tvState.setTextColor(Color.parseColor("#1565C0"));
        } else if (downloaded) {
            tvState.setText(activity.getString(R.string.msg_already_downloaded));
            tvState.setTextColor(Color.parseColor("#2E7D32"));
        } else if (partial > 0) {
            tvState.setText(activity.getString(R.string.msg_partial_downloaded)
                    + GameDownloader.fmt(partial));
            tvState.setTextColor(Color.parseColor("#EF6C00"));
        } else {
            tvState.setText(activity.getString(R.string.msg_not_downloaded));
            tvState.setTextColor(Color.parseColor("#999999"));
        }
        row.addView(tvState);

        // 分隔线
        View line = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = (int) (10 * dp);
        line.setLayoutParams(lp);
        line.setBackgroundColor(Color.parseColor("#E0E0E0"));
        row.addView(line);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDownload(e);
            }
        });

        return row;
    }

    /**
     * 这个版本会下哪个架构。
     *
     * <p>默认 64 位（arm64-v8a）：现在在售的机器基本都是 64 位，
     * 32 位包只是给极老的设备兜底。只有 OneDrive 里没有 ARMv8 时才用 v7a。
     */
    private String archLabel(VersionRepo.Entry e) {
        if (e.links == null) {
            return "";
        }
        org.json.JSONObject od = e.links.optJSONObject("OneDrive");
        if (od == null) {
            return "";
        }
        if (!od.optString("ARMv8", "").isEmpty()) {
            return activity.getString(R.string.msg_arch_arm64);
        }
        return "32-bit (armv7)";
    }

    /** 点版本不直接下，先确认 —— 一个包 700MB+，误触代价太大 */
    private void confirmDownload(final VersionRepo.Entry e) {
        String slug = VersionRepo.oneDriveSlug(e);
        if (slug == null) {
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.err_no_download_link))
                    .setMessage(activity.getString(R.string.err_no_onedrive_link_hint))
                    .setPositiveButton(activity.getString(R.string.btn_ok), null)
                    .show();
            return;
        }

        boolean downloaded = GameDownloader.isDownloaded(activity, e.versionAll);
        long partial = GameDownloader.downloadedBytes(activity, e.versionAll);

        String msg;
        if (downloaded) {
            msg = activity.getString(R.string.msg_confirm_redownload, e.versionAll);
        } else if (partial > 0) {
            msg = activity.getString(R.string.msg_confirm_resume,
                    e.versionAll, GameDownloader.fmt(partial));
        } else {
            msg = activity.getString(R.string.msg_confirm_download, e.versionAll, e.fileSize);
        }

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_confirm_download))
                .setMessage(msg)
                .setPositiveButton(activity.getString(R.string.btn_download_now),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                if (listener != null) {
                                    listener.onDownloadRequested(e);
                                }
                            }
                        })
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .show();
    }
}
