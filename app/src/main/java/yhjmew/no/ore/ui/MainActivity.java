package yhjmew.no.ore.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private Button btnSelectMc;
    private Button btnMakeClone;
    private TextView tvLogOutput;
    private ScrollView scrollViewLog;
    private ProgressBar progressBar;
    private TextView tvProgressPercent;

    private static final int REQUEST_CODE_PERMISSION_STORAGE = 100;
    private static final int REQUEST_CODE_PERMISSION_ALL_FILES = 101;
    private static final int REQUEST_CODE_SELECT_APK = 102;
    private static final int REQUEST_CODE_SELECT_SIGN_FILE = 103;

    private static final int PICK_MODE_NONE = 0;
    private static final int PICK_MODE_NORMAL = 1;
    private static final int PICK_MODE_CLONE = 2;

    private static final String DEFAULT_PKG_NAME = "com.mojang.minecraftpe";
    private static final int SO_PKG_MAX_LENGTH = 34;

    private int currentPickMode = PICK_MODE_NONE;

    private String originalFileName = "minecraft.apk";
    private String originalDirectory = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download";

    private String currentDetectedPackageName = DEFAULT_PKG_NAME;
    private int currentCloneMaxLength = Math.min(DEFAULT_PKG_NAME.length(), SO_PKG_MAX_LENGTH);

    private SignatureManager signatureManager;
    private PackageDetector packageDetector;
    private ApkProcessor apkProcessor;
    private UpdateManager updateManager;
    private android.app.ProgressDialog downloadProgressDialog;
    private File pendingInstallApk = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 语言必须在 super.onCreate 与 setContentView 之前套用，
        // 否则已经 inflate 出来的控件还带着旧语言的文本。
        AppLocale.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        btnSelectMc = (Button) findViewById(R.id.btn_select_mc);
        btnMakeClone = (Button) findViewById(R.id.btn_make_clone);
        tvLogOutput = (TextView) findViewById(R.id.tv_log_output);
        scrollViewLog = (ScrollView) findViewById(R.id.scroll_view_log);
        progressBar = (ProgressBar) findViewById(R.id.progress_bar);
        tvProgressPercent = (TextView) findViewById(R.id.tv_progress_percent);

        signatureManager = new SignatureManager(this);
        packageDetector = new PackageDetector(this);
        apkProcessor = new ApkProcessor(this, signatureManager, new ApkProcessor.ProgressCallback() {
            @Override
            public void onProgressUpdate(final int current, final int max) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateProgressSafe(current, max);
                    }
                });
            }

            @Override
            public void onLog(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateLogSafe(message);
                    }
                });
            }

            @Override
            public void setIndeterminate(final boolean isIndeterminate) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setIndeterminateModeSafe(isIndeterminate);
                    }
                });
            }
        });

        btnSelectMc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkAndRequestPermissions()) {
                    currentPickMode = PICK_MODE_NORMAL;
                    openFilePicker();
                }
            }
        });

        btnMakeClone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkAndRequestPermissions()) {
                    currentPickMode = PICK_MODE_CLONE;
                    openFilePicker();
                }
            }
        });

        View btnAbout = findViewById(R.id.btn_about);
        btnAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAboutDialog();
            }
        });

        Button btnDownloadGame = (Button) findViewById(R.id.btn_download_game);
        btnDownloadGame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVersionPicker();
            }
        });

        TextView btnSettings = (TextView) findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        printLog(getString(R.string.log_app_started));
        printLog(getString(R.string.log_current_signature) + signatureManager.getSignDisplayName(signatureManager.getCurrentSignType()));
        printLog(getString(R.string.log_current_so_type) + apkProcessor.getSoDisplayName(apkProcessor.getCurrentSoType()));

        // updateManager 是懒加载的（第一次 checkForUpdates 时才建），
        // 这里要主动确保它存在，否则 NPE。
        ensureUpdateManager();

        // 清掉过时的安装包（比当前版本旧的）。
        // 下好待装的那份会留下 —— 用户点一下就能装，不用重下。
        updateManager.cleanupOldApks(currentAppVersion());

        // 检查自动更新设置
        android.content.SharedPreferences prefs = getSharedPreferences("UpdateConfig", MODE_PRIVATE);
        boolean autoUpdateEnabled = prefs.getBoolean("auto_update_enabled", true);

        if (autoUpdateEnabled) {
            printLog(getString(R.string.log_update_checking));
            checkForUpdates(false);
        } else {
            printLog(getString(R.string.msg_auto_update_off_hint));
        }

        // 有下载好还没装的包，主动提示一次
        showPendingInstallHintIfAny();
    }

    /** 确保 UpdateManager 已创建（它是懒加载的，多处要用） */
    private void ensureUpdateManager() {
        if (updateManager == null) {
            updateManager = new UpdateManager(this);
        }
    }

    /** 当前应用的 versionName，用作「哪些下载包已经过时」的判断依据 */
    private String currentAppVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /**
     * 如果上次下载的更新包还在，提示用户可以直接装，不用重新下载。
     *
     * <p>对应「我们没推送更新，但他点一下就能装已下载的版本」这种情况：
     * 用户点了稍后安装、或者从安装界面返回，包还在私有目录里。
     */
    private void showPendingInstallHintIfAny() {
        ensureUpdateManager();
        final java.util.List<File> pending = updateManager.findPendingApks();
        if (pending.isEmpty()) {
            return;
        }
        final File apk = pending.get(0);
        final String ver = UpdateManager.versionOfApk(apk);

        // 已经装上的就别再提示了
        if (!UpdateManager.isNewerVersionStatic(currentAppVersion(), ver)) {
            printLog(getString(R.string.log_pending_apk_same) + ver);
            return;
        }

        printLog(getString(R.string.log_pending_apk_found) + ver);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.title_pending_install))
                        .setMessage(getString(R.string.msg_pending_install, ver))
                        .setPositiveButton(getString(R.string.btn_install_now),
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        updateManager.installApk(apk);
                                    }
                                })
                        .setNegativeButton(getString(R.string.btn_install_later), null)
                        .show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (pendingInstallApk != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (getPackageManager().canRequestPackageInstalls()) {
                printLog(getString(R.string.log_install_perm_ok));
                if (updateManager != null) {
                    updateManager.installApk(pendingInstallApk);
                }
                pendingInstallApk = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PERMISSION_ALL_FILES) {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                printLog(getString(R.string.msg_perm_granted_retry));
            }
            return;
        }

        if (requestCode == REQUEST_CODE_SELECT_SIGN_FILE && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri signUri = data.getData();
                String fileName = getFileNameFromUri(signUri);

                if (fileName != null && !fileName.toLowerCase(Locale.getDefault()).endsWith(".bks")) {
                    printLog(getString(R.string.err_sign_need_bks) + fileName);
                    return;
                }

                printLog(getString(R.string.log_sign_file_selected) + fileName);

                String realPath = resolveSignFilePath(signUri);

                if (realPath != null) {
                    File f = new File(realPath);
                    if (f.exists()) {
                        signatureManager.setCustomSignFilePath(realPath);
                        printLog(getString(R.string.log_sign_file_path) + realPath);
                    } else {
                        try {
                            String cachedPath = signatureManager.copySignFileToCache(signUri);
                            signatureManager.setCustomSignFilePath(cachedPath);
                            printLog(getString(R.string.log_sign_file_cached) + cachedPath);
                        } catch (Exception e) {
                            printLog(getString(R.string.err_cache_sign_failed) + Utils.getFullStackTrace(e));
                            return;
                        }
                    }
                } else {
                    try {
                        String cachedPath = signatureManager.copySignFileToCache(signUri);
                        signatureManager.setCustomSignFilePath(cachedPath);
                        printLog(getString(R.string.log_sign_file_cached) + cachedPath);
                    } catch (Exception e) {
                        printLog(getString(R.string.err_cache_sign_failed) + Utils.getFullStackTrace(e));
                        return;
                    }
                }

                showCustomSignInputDialog(signatureManager.getCustomSignFilePath());
            }
            return;
        }

        if (requestCode == REQUEST_CODE_SELECT_APK && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                final Uri apkUri = data.getData();
                resolveOriginalFileInfo(apkUri);
                prepareAndProcess(apkUri, currentPickMode);
                currentPickMode = PICK_MODE_NONE;
            }
        }
    }

    /**
     * 统一入口：拿到 APK 的 Uri 后做「读包名 → 选择处理方式」。
     *
     * <p>从文件选择器选完走这里；从下载列表直接点某个包也走这里，
     * 两条路复用同一套逻辑，避免行为分叉。
     */
    private void prepareAndProcess(final Uri apkUri, final int pickMode) {
        String detectedPkg = null;
        try {
            detectedPkg = packageDetector.detectPackageNameFromApkUri(apkUri);
        } catch (Exception e) {
            printLog(getString(R.string.err_read_pkg_failed) + Utils.getFullStackTrace(e));
        }

        if (detectedPkg != null && detectedPkg.length() > 0) {
            currentDetectedPackageName = detectedPkg;
        } else {
            currentDetectedPackageName = DEFAULT_PKG_NAME;
        }

        currentCloneMaxLength = Math.min(currentDetectedPackageName.length(), SO_PKG_MAX_LENGTH);

        printLog(getString(R.string.log_selected_files) + originalFileName);
        printLog(getString(R.string.log_pkg_detected) + currentDetectedPackageName);
        printLog(getString(R.string.msg_clone_max_len) + currentCloneMaxLength);

        if (pickMode == PICK_MODE_NORMAL) {
            printLog(getString(R.string.log_ready_normal));
            processApkInBackground(apkUri, false,
                    currentDetectedPackageName, currentDetectedPackageName);
        } else if (pickMode == PICK_MODE_CLONE) {
            if (detectedPkg == null || detectedPkg.length() == 0) {
                printLog(getString(R.string.err_read_apk_pkg));
            } else {
                printLog(getString(R.string.msg_ask_continue_pkg));
                showCloneConfigDialog(apkUri);
            }
        } else {
            printLog(getString(R.string.err_mode_unknown));
        }
    }

    /**
     * 让用户选「普通处理 / 共存处理」，然后直接开始。
     *
     * <p>下载页点某个包就调这里 —— 不再要求用户回到主界面、
     * 点处理按钮、再从文件管理器里把同一个文件找一遍。
     */
    void startProcessDownloaded(final File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            showToast(getString(R.string.err_apk_missing));
            return;
        }
        // 先确认存储权限（写输出目录需要）
        if (!checkAndRequestPermissions()) {
            return;
        }

        final Uri uri = Uri.fromFile(apkFile);

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_choose_mode))
                .setMessage(apkFile.getName())
                .setPositiveButton(getString(R.string.btn_normal_processing),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                resolveOriginalFileInfo(uri);
                                prepareAndProcess(uri, PICK_MODE_NORMAL);
                            }
                        })
                .setNeutralButton(getString(R.string.btn_coexistence_processing),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                resolveOriginalFileInfo(uri);
                                prepareAndProcess(uri, PICK_MODE_CLONE);
                            }
                        })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private String getFileNameFromUri(Uri uri) {
        String name = "sign_file";
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex != -1) {
                name = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        return name;
    }

    private void resolveOriginalFileInfo(Uri uri) {
        // file:// 走不了 ContentResolver.query，先从路径直接取文件名
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            if (p != null) {
                File f = new File(p);
                if (f.getName() != null && f.getName().length() > 0) {
                    originalFileName = f.getName();
                }
                if (f.getParent() != null) {
                    originalDirectory = f.getParent();
                }
            }
        } else {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    originalFileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }
        }

        String path = uri.getPath();
        if (path != null) {
            if (path.contains("/document/primary:")) {
                String relativePath = path.replace("/document/primary:", "");
                File realFile = new File(Environment.getExternalStorageDirectory(), relativePath);
                if (realFile.getParent() != null) {
                    originalDirectory = realFile.getParent();
                }
            } else if (path.contains("/storage/emulated/0/")) {
                int index = path.indexOf("/storage/emulated/0/");
                File realFile = new File(path.substring(index));
                if (realFile.getParent() != null) {
                    originalDirectory = realFile.getParent();
                }
            }
        }
    }

    private boolean checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                printLog(getString(R.string.msg_need_all_files));
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_CODE_PERMISSION_ALL_FILES);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_CODE_PERMISSION_ALL_FILES);
                }
                return false;
            }
            return true;
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                printLog(getString(R.string.msg_need_storage));
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_CODE_PERMISSION_STORAGE);
                return false;
            }
            return true;
        }
    }

    private void openFilePicker() {
        if (currentPickMode == PICK_MODE_NORMAL) {
            printLog(getString(R.string.log_normal_open_fm));
        } else if (currentPickMode == PICK_MODE_CLONE) {
            printLog(getString(R.string.log_clone_open_fm));
        } else {
            printLog(getString(R.string.log_opening_fm));
        }

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/vnd.android.package-archive");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_CODE_SELECT_APK);
        } catch (Exception e) {
            printLog(getString(R.string.err_open_fm_failed) + Utils.getFullStackTrace(e));
        }
    }

    /**
     * 统一的设置面板：签名 + SO 放在同一个对话框里。
     *
     * <p>以前是两个独立按钮，用户想同时改签名和 SO 得点两次、关两次。
     * 现在一次打开、一次确定，两边的选择一起落盘。
     *
     * <p>用 ScrollView 包一层：小屏 + 大字号时两个 RadioGroup 加说明文字
     * 会超出屏幕，不包就点不到下面的选项。
     */
    /**
     * 打开版本选择器。
     *
     * <p>选好版本后走这里：先解析 OneDrive 中转链接拿到直链，
     * 再交给下载器。下完不自动装，交给用户决定。
     */
    private void showVersionPicker() {
        // 下载要写到公共 Download 目录，先把权限要了。
        // 用户在设置里点「允许」回来之后 onActivityResult 会走到，
        // 那时再点一次「下载游戏」就行；这里不强求一次到位。
        checkAndRequestPermissions();

        new VersionPickerDialog(this, new VersionPickerDialog.OnPickListener() {
            @Override
            public void onDownloadRequested(final VersionRepo.Entry entry) {
                final String slug = VersionRepo.oneDriveSlug(entry);
                if (slug == null) {
                    printLog(getString(R.string.err_no_onedrive_link_hint));
                    return;
                }
                printLog(getString(R.string.msg_searching) + entry.versionAll);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final String direct = VersionRepo.resolveDirectLink(slug);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    // 先弹出下载页再开始，否则 refresh() 时 list 还是 null
                                    new DownloadManagerDialog(MainActivity.this)
                                            .showAndDownload(entry.versionAll, direct);
                                }
                            });
                        } catch (Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showErrorDialog(getString(R.string.err_no_download_link),
                                            String.valueOf(e.getMessage()));
                                }
                            });
                        }
                    }
                }).start();
            }
        }).show();
    }

    private void showSettingsDialog() {
        final float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(pad, pad, pad, pad);
        scroll.addView(container);

        // ---------- 签名 ----------
        android.widget.TextView tvSignTitle = new android.widget.TextView(this);
        tvSignTitle.setText(getString(R.string.title_signature_file_selection));
        tvSignTitle.setTextSize(16f);
        tvSignTitle.setTextColor(android.graphics.Color.parseColor("#000000"));
        tvSignTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvSignTitle.setPadding(0, 0, 0, (int) (8 * density));
        container.addView(tvSignTitle);

        final android.widget.RadioGroup signGroup = new android.widget.RadioGroup(this);
        String[] signNames = new String[]{
                getString(R.string.btn_sign_mt_default),
                getString(R.string.btn_sign_zihao_il),
                getString(R.string.btn_sign_custom_bks)
        };
        for (int i = 0; i < signNames.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(signNames[i]);
            rb.setId(i);
            signGroup.addView(rb);
        }
        if (signatureManager.getCurrentSignType() < 3) {
            signGroup.check(signatureManager.getCurrentSignType());
        } else {
            signGroup.check(2);
        }
        container.addView(signGroup);

        // 自定义签名当前路径，选第三项时给个提示
        final String customPath = signatureManager.getCustomSignFilePath();
        if (customPath != null && customPath.length() > 0) {
            android.widget.TextView tvSignPath = new android.widget.TextView(this);
            tvSignPath.setText(getString(R.string.log_sign_file_path) + customPath);
            tvSignPath.setTextSize(11f);
            tvSignPath.setTextColor(android.graphics.Color.parseColor("#888888"));
            tvSignPath.setPadding(0, 0, 0, (int) (12 * density));
            container.addView(tvSignPath);
        }

        // ---------- 分隔线 ----------
        View divider = new View(this);
        android.widget.LinearLayout.LayoutParams dp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dp.topMargin = (int) (8 * density);
        dp.bottomMargin = (int) (14 * density);
        divider.setLayoutParams(dp);
        divider.setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"));
        container.addView(divider);

        // ---------- SO ----------
        android.widget.TextView tvSoTitle = new android.widget.TextView(this);
        tvSoTitle.setText(getString(R.string.title_so_selection));
        tvSoTitle.setTextSize(16f);
        tvSoTitle.setTextColor(android.graphics.Color.parseColor("#000000"));
        tvSoTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvSoTitle.setPadding(0, 0, 0, (int) (8 * density));
        container.addView(tvSoTitle);

        final android.widget.RadioGroup soGroup = new android.widget.RadioGroup(this);

        android.widget.RadioButton rbStandalone = new android.widget.RadioButton(this);
        rbStandalone.setText(getString(R.string.msg_so_standalone_desc));
        rbStandalone.setId(1);                     // SO_TYPE_STANDALONE
        soGroup.addView(rbStandalone);

        android.widget.RadioButton rbWithPreloader = new android.widget.RadioButton(this);
        rbWithPreloader.setText(getString(R.string.msg_so_preloader_desc));
        rbWithPreloader.setId(0);                  // SO_TYPE_WITH_PRELOADER
        soGroup.addView(rbWithPreloader);

        soGroup.check(apkProcessor.getCurrentSoType());
        container.addView(soGroup);

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_settings))
                .setView(scroll)
                .setPositiveButton(getString(R.string.btn_ok), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        // ---- 签名 ----
                        int selectedSign = signGroup.getCheckedRadioButtonId();
                        if (selectedSign == 2) {
                            if (signatureManager.getCustomSignFilePath() != null
                                    && signatureManager.getCustomSignFilePath().length() > 0) {
                                signatureManager.setCurrentSignType(SignatureManager.SIGN_TYPE_CUSTOM);
                            }
                            openSignFilePicker();
                        } else {
                            signatureManager.setCurrentSignType(selectedSign);
                            printLog(getString(R.string.log_switched_to)
                                    + signatureManager.getSignDisplayName(signatureManager.getCurrentSignType()));
                        }
                        signatureManager.saveSignConfig();

                        // ---- SO ----
                        int selectedSo = soGroup.getCheckedRadioButtonId();
                        apkProcessor.setCurrentSoType(selectedSo);
                        apkProcessor.saveSoConfig();
                        printLog(getString(R.string.log_switched_to)
                                + apkProcessor.getSoDisplayName(apkProcessor.getCurrentSoType()));
                    }
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void openSignFilePicker() {
        printLog(getString(R.string.log_opening_sign_picker));
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/octet-stream");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_CODE_SELECT_SIGN_FILE);
        } catch (Exception e) {
            printLog(getString(R.string.err_open_fm_failed) + Utils.getFullStackTrace(e));
        }
    }

    private void showCustomSignInputDialog(String prefillPath) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        android.widget.TextView tvHint = new android.widget.TextView(this);
        tvHint.setText(getString(R.string.msg_ask_sign_detail));
        tvHint.setTextColor(android.graphics.Color.parseColor("#333333"));
        tvHint.setTextSize(14f);
        tvHint.setPadding(0, 0, 0, 20);
        container.addView(tvHint);

        final android.widget.EditText editPath = new android.widget.EditText(this);
        editPath.setHint(getString(R.string.hint_sign_path));
        editPath.setSingleLine(true);
        if (prefillPath != null && prefillPath.length() > 0) {
            editPath.setText(prefillPath);
        } else if (signatureManager.getCustomSignFilePath() != null && signatureManager.getCustomSignFilePath().length() > 0) {
            editPath.setText(signatureManager.getCustomSignFilePath());
        }
        container.addView(editPath);

        final android.widget.EditText editAlias = new android.widget.EditText(this);
        editAlias.setHint(getString(R.string.hint_alias));
        editAlias.setText(signatureManager.getCustomSignAlias());
        editAlias.setSingleLine(true);
        container.addView(editAlias);

        final android.widget.EditText editKeystorePassword = new android.widget.EditText(this);
        editKeystorePassword.setHint(getString(R.string.hint_keystore_password));
        editKeystorePassword.setText(signatureManager.getCustomSignKeystorePassword());
        editKeystorePassword.setSingleLine(true);
        container.addView(editKeystorePassword);

        final android.widget.EditText editKeyPassword = new android.widget.EditText(this);
        editKeyPassword.setHint(getString(R.string.hint_alias_password));
        editKeyPassword.setText(signatureManager.getCustomSignKeyPassword());
        editKeyPassword.setSingleLine(true);
        container.addView(editKeyPassword);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_custom_signature))
                .setView(container)
                .setPositiveButton(getString(R.string.btn_ok), null)
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .create();

        dialog.show();

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String path = editPath.getText().toString().trim();
                String alias = editAlias.getText().toString().trim();
                String keystorePassword = editKeystorePassword.getText().toString().trim();
                String keyPassword = editKeyPassword.getText().toString().trim();

                if (path.length() == 0) {
                    printLog(getString(R.string.err_sign_path_empty));
                    return;
                }

                if (!path.toLowerCase(Locale.getDefault()).endsWith(".bks")) {
                    printLog(getString(R.string.err_sign_need_bks) + path);
                    return;
                }

                File signFile = new File(path);
                if (!signFile.exists()) {
                    printLog(getString(R.string.err_sign_file_missing) + path);
                    return;
                }

                if (alias.length() == 0) {
                    alias = "android";
                }
                if (keystorePassword.length() == 0) {
                    keystorePassword = "android";
                }
                if (keyPassword.length() == 0) {
                    keyPassword = keystorePassword;
                }

                signatureManager.setCustomSignFilePath(path);
                signatureManager.setCustomSignAlias(alias);
                signatureManager.setCustomSignKeystorePassword(keystorePassword);
                signatureManager.setCustomSignKeyPassword(keyPassword);
                signatureManager.setCurrentSignType(SignatureManager.SIGN_TYPE_CUSTOM);

                signatureManager.saveSignConfig();
                printLog(getString(R.string.msg_custom_sign_saved));
                printLog(getString(R.string.log_path) + signatureManager.getCustomSignFilePath());
                printLog(getString(R.string.hint_alias_colon) + signatureManager.getCustomSignAlias());
                printLog(getString(R.string.log_current_signature) + signatureManager.getSignDisplayName(signatureManager.getCurrentSignType()));

                dialog.dismiss();
            }
        });
    }

    private void showCloneConfigDialog(final Uri apkUri) {
        final String sourcePkg = currentDetectedPackageName;
        final int maxLength = currentCloneMaxLength;

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        android.widget.TextView tvHint = new android.widget.TextView(this);
        tvHint.setText(getString(R.string.log_pkg_detected_nl) + sourcePkg
                + getString(R.string.msg_ask_enter_pkg)
                + getString(R.string.msg_max_length) + maxLength
                + getString(R.string.msg_auto_handles));
        tvHint.setTextColor(android.graphics.Color.parseColor("#333333"));
        tvHint.setTextSize(14f);
        tvHint.setPadding(0, 0, 0, 30);
        container.addView(tvHint);

        final android.widget.TextView tvCounter = new android.widget.TextView(this);
        tvCounter.setTextSize(13f);
        tvCounter.setPadding(0, 0, 0, 10);
        container.addView(tvCounter);

        final android.widget.EditText editText = new android.widget.EditText(this);
        editText.setSingleLine(true);
        editText.setText(sourcePkg);
        editText.setSelection(sourcePkg.length());
        container.addView(editText);

        int initialLen = sourcePkg.length();
        tvCounter.setText(getString(R.string.msg_word_count) + initialLen + " / " + maxLength);
        if (initialLen > maxLength) {
            tvCounter.setTextColor(android.graphics.Color.RED);
        } else {
            tvCounter.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
        }

        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                int len = s.length();
                tvCounter.setText(getString(R.string.msg_word_count) + len + " / " + maxLength);
                if (len > maxLength) {
                    tvCounter.setTextColor(android.graphics.Color.RED);
                } else {
                    tvCounter.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_configure_clone_pkg))
                .setView(container)
                .setPositiveButton(getString(R.string.btn_start), null)
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .create();

        dialog.show();

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newPkg = editText.getText().toString().trim();

                if (newPkg.length() == 0) {
                    printLog(getString(R.string.err_pkg_empty));
                    return;
                }

                if (newPkg.length() > maxLength) {
                    printLog(getString(R.string.err_pkg_too_long) + maxLength + getString(R.string.fmt_characters));
                    return;
                }

                if (!Utils.isValidPackageName(newPkg)) {
                    printLog(getString(R.string.err_pkg_illegal));
                    return;
                }

                if (sourcePkg.equals(newPkg)) {
                    printLog(getString(R.string.err_clone_pkg_same));
                    return;
                }

                printLog(getString(R.string.log_current_pkg) + sourcePkg);
                printLog(getString(R.string.log_target_clone_pkg) + newPkg);
                dialog.dismiss();
                processApkInBackground(apkUri, true, sourcePkg, newPkg);
            }
        });
    }

    private void processApkInBackground(final Uri uri, final boolean isCloneMode, final String oldPkg, final String targetPkg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    apkProcessor.processApk(uri, isCloneMode, oldPkg, targetPkg, originalFileName, originalDirectory);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setIndeterminateModeSafe(false);

                            String msg = e.getMessage();
                            if (msg == null || msg.length() == 0) {
                                msg = e.getClass().getSimpleName();
                            }

                            updateLogSafe(getString(R.string.err_generic) + msg);

                            Throwable cause = e.getCause();
                            if (cause != null) {
                                String causeMsg = cause.getMessage();
                                if (causeMsg != null && causeMsg.length() > 0 && !causeMsg.equals(msg)) {
                                    updateLogSafe(getString(R.string.err_root_cause) + causeMsg);
                                }
                                Throwable rootCause = cause.getCause();
                                if (rootCause != null) {
                                    String rootMsg = rootCause.getMessage();
                                    if (rootMsg != null && rootMsg.length() > 0
                                            && !rootMsg.equals(msg) && !rootMsg.equals(causeMsg)) {
                                        updateLogSafe(getString(R.string.err_deep_reason) + rootMsg);
                                    }
                                }
                            }

                            StackTraceElement[] stack = e.getStackTrace();
                            if (stack != null && stack.length > 0) {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < stack.length; i++) {
                                    sb.append("  at ").append(stack[i].getClassName())
                                            .append(".").append(stack[i].getMethodName())
                                            .append("(").append(stack[i].getFileName())
                                            .append(":").append(stack[i].getLineNumber())
                                            .append(")\n");
                                }
                                updateLogSafe(getString(R.string.err_stack_top) + sb.toString());
                            }

                            e.printStackTrace();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 语言选择行：嵌在对话框中，点击展开成下拉列表。
     *
     * <p>原本是独立的滑出抽屉，但语言属于低频设置，单独占一个主按钮太浪费
     * 右上角的空间，所以收进关于页。展开用点击切换，不弹二级对话框，
     * 免得「对话框套对话框」在低版本上返回键行为混乱。
     *
     * @param closeHost 关闭外层对话框（选完语言要关掉它再重建界面）
     */
    /**
     * 通用「设置行」：左边标题，右边当前值，整行可点。
     *
     * <p>关于页里原来有三种风格：系统 CheckBox（自带深色方块）、
     * 满宽黑框按钮、以及语言那种左右布局。摆在一起很杂乱。
     * 现在统一成这一种：一眼扫下来是"设置项列表"，而不是一堆控件。
     *
     * @param outValue 回传右侧那个 TextView，方便调用方点了之后更新显示值
     */
    private View buildSettingRow(String title, String value,
                                 android.widget.TextView[] outValue,
                                 View.OnClickListener onClick) {
        float density = getResources().getDisplayMetrics().density;
        int vPad = (int) (12 * density);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, vPad, 0, vPad);
        row.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        android.widget.TextView tvTitle = new android.widget.TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15f);
        tvTitle.setTextColor(android.graphics.Color.parseColor("#000000"));
        tvTitle.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvTitle);

        android.widget.TextView tvValue = new android.widget.TextView(this);
        tvValue.setText(value + "  ▾");
        tvValue.setTextSize(14f);
        tvValue.setTextColor(android.graphics.Color.parseColor("#666666"));
        row.addView(tvValue);

        if (outValue != null && outValue.length > 0) {
            outValue[0] = tvValue;
        }
        if (onClick != null) {
            row.setOnClickListener(onClick);
        }
        return row;
    }

    /**
     * 「动作行」：看起来和设置行一样，但右侧没有箭头，因为点它是执行而不是选值。
     */
    private View buildActionRow(String title, View.OnClickListener onClick) {
        float density = getResources().getDisplayMetrics().density;
        int vPad = (int) (12 * density);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, vPad, 0, vPad);

        android.widget.TextView tvTitle = new android.widget.TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15f);
        tvTitle.setTextColor(android.graphics.Color.parseColor("#000000"));
        tvTitle.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvTitle);

        android.widget.TextView tvArrow = new android.widget.TextView(this);
        tvArrow.setText("›");
        tvArrow.setTextSize(18f);
        tvArrow.setTextColor(android.graphics.Color.parseColor("#999999"));
        row.addView(tvArrow);

        if (onClick != null) {
            row.setOnClickListener(onClick);
        }
        return row;
    }

    /**
     * 更新方式选择：仅提醒 / 自动下载。
     *
     * <p>原来只有一个「启用自动更新」复选框，语义是二元的：勾上就自动下，
     * 不勾就完全不提示 —— 中间那种"告诉我有新版本但别自己下"没法表达。
     * 现在给两个明确选项。
     */
    private void showUpdatePolicyDialog(final android.widget.TextView tvValue) {
        final boolean autoUpdate = getSharedPreferences("UpdateConfig", MODE_PRIVATE)
                .getBoolean("auto_update_enabled", true);

        final String[] options = {
                getString(R.string.msg_update_auto),
                getString(R.string.msg_update_notify_only)
        };
        final String[] descs = {
                getString(R.string.msg_update_auto_desc),
                getString(R.string.msg_update_notify_desc)
        };
        final boolean[] values = { true, false };

        int checked = autoUpdate ? 0 : 1;

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_update_policy))
                .setSingleChoiceItems(options, checked,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                boolean auto = values[which];
                                getSharedPreferences("UpdateConfig", MODE_PRIVATE)
                                        .edit()
                                        .putBoolean("auto_update_enabled", auto)
                                        .apply();
                                if (tvValue != null) {
                                    tvValue.setText(options[which] + "  ▾");
                                }
                                printLog(getString(auto
                                        ? R.string.log_auto_update_on
                                        : R.string.log_auto_update_off));
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .setMessage(descs[checked])
                .show();
    }

    private View buildLanguageRow(final Runnable closeHost) {
        final float density = getResources().getDisplayMetrics().density;
        final boolean[] expanded = {false};

        final android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);

        // 触发这一行：左边标题，右边当前语言
        final android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int hp = (int) (4 * density);
        header.setPadding(0, hp, 0, hp);

        android.widget.TextView tvTitle = new android.widget.TextView(this);
        tvTitle.setText(getString(R.string.title_language));
        tvTitle.setTextSize(15f);
        tvTitle.setTextColor(android.graphics.Color.parseColor("#000000"));
        tvTitle.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(tvTitle);

        final int current = AppLocale.getMode(this);

        final android.widget.TextView tvValue = new android.widget.TextView(this);
        tvValue.setText(getString(AppLocale.modeLabelRes(current)) + "  ▾");
        tvValue.setTextSize(14f);
        tvValue.setTextColor(android.graphics.Color.parseColor("#666666"));
        header.addView(tvValue);

        wrap.addView(header);

        // 展开区
        final android.widget.LinearLayout options = new android.widget.LinearLayout(this);
        options.setOrientation(android.widget.LinearLayout.VERTICAL);
        options.setVisibility(View.GONE);

        final int[] modes = {
                AppLocale.MODE_SYSTEM,
                AppLocale.MODE_ZH_CN,
                AppLocale.MODE_ZH_TW,
                AppLocale.MODE_ZH_HK,
                AppLocale.MODE_EN
        };

        // 跟随系统时把系统实际给的 Locale 显示出来，方便排查"为什么是这句语言"
        String sysHint = current == AppLocale.MODE_SYSTEM
                ? " (" + Locale.getDefault().toString() + ")"
                : "";

        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];

            final android.widget.TextView row = new android.widget.TextView(this);
            String label = getString(AppLocale.modeLabelRes(mode));
            if (mode == AppLocale.MODE_SYSTEM) {
                label += sysHint;
            }
            row.setText(label);
            row.setTextSize(14f);
            int rp = (int) (8 * density);
            row.setPadding((int) (12 * density), rp, rp, rp);
            row.setTextColor(android.graphics.Color.parseColor("#000000"));
            row.setBackgroundColor(mode == current
                    ? android.graphics.Color.parseColor("#F0F0F0")
                    : android.graphics.Color.TRANSPARENT);
            row.setTypeface(null, mode == current
                    ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mode == AppLocale.getMode(MainActivity.this)) {
                        options.setVisibility(View.GONE);
                        expanded[0] = false;
                        return;
                    }
                    AppLocale.setMode(MainActivity.this, mode);
                    if (closeHost != null) {
                        closeHost.run();
                    }
                    AppLocale.apply(MainActivity.this);
                    try {
                        recreate();
                    } catch (Throwable t) {
                        Intent restart = new Intent(MainActivity.this, MainActivity.class);
                        restart.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(restart);
                        finish();
                    }
                }
            });
            options.addView(row);
        }

        wrap.addView(options);

        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded[0] = !expanded[0];
                options.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                tvValue.setText(getString(AppLocale.modeLabelRes(current))
                        + (expanded[0] ? "  ▴" : "  ▾"));
            }
        });

        return wrap;
    }

    private void showAboutDialog() {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        // 主要内容
        android.widget.TextView messageView = new android.widget.TextView(this);
        messageView.setTextSize(15f);
        messageView.setTextColor(android.graphics.Color.parseColor("#000000"));
        messageView.setTextIsSelectable(true);

        String htmlContent = getString(R.string.msg_about_title) +
                getString(R.string.msg_about_purpose) +
                getString(R.string.msg_about_support) +
                getString(R.string.msg_about_signature) +
                getString(R.string.msg_about_qq) +
                getString(R.string.msg_about_qq_link) +
                getString(R.string.msg_about_telegram) +
                getString(R.string.msg_about_telegram_link);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            messageView.setText(android.text.Html.fromHtml(htmlContent, android.text.Html.FROM_HTML_MODE_LEGACY));
        } else {
            messageView.setText(android.text.Html.fromHtml(htmlContent));
        }

        messageView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        container.addView(messageView);

        // 分隔线
        View divider = new View(this);
        divider.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        2
        ));
        divider.setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"));
        android.widget.LinearLayout.LayoutParams dividerParams = (android.widget.LinearLayout.LayoutParams) divider.getLayoutParams();
        dividerParams.topMargin = (int) (15 * getResources().getDisplayMetrics().density);
        dividerParams.bottomMargin = (int) (15 * getResources().getDisplayMetrics().density);
        divider.setLayoutParams(dividerParams);
        container.addView(divider);

        // ---------- 更新设置区 ----------
        // 原来这里是一个系统 CheckBox + 一个满宽黑框按钮 + 一个语言行，
        // 三种风格混在一起，视觉上很跳。现在统一成「设置行」：
        // 左边标题、右边当前值、点击展开，与下面的语言行完全一致。

        final android.content.SharedPreferences prefs =
                getSharedPreferences("UpdateConfig", MODE_PRIVATE);
        final boolean autoUpdateEnabled = prefs.getBoolean("auto_update_enabled", true);

        // 更新方式：仅提醒 / 自动下载
        final android.widget.TextView[] tvPolicyValue = new android.widget.TextView[1];
        container.addView(buildSettingRow(
                getString(R.string.title_update_policy),
                getString(autoUpdateEnabled
                        ? R.string.msg_update_auto
                        : R.string.msg_update_notify_only),
                tvPolicyValue,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showUpdatePolicyDialog(tvPolicyValue[0]);
                    }
                }));

        // 检查更新：与语言行一样是「点一下做事」，不占满宽
        container.addView(buildActionRow(
                getString(R.string.msg_update_check_now),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        printLog(getString(R.string.log_update_checking_manual));
                        checkForUpdates(true);
                    }
                }));

        final android.app.AlertDialog[] dialogRef = new android.app.AlertDialog[1];

        // 语言：低频设置，收在关于页里，点一下展开成列表
        android.widget.LinearLayout langRow = (android.widget.LinearLayout) buildLanguageRow(
                new Runnable() {
                    @Override
                    public void run() {
                        if (dialogRef[0] != null && dialogRef[0].isShowing()) {
                            dialogRef[0].dismiss();
                        }
                    }
                });
        container.addView(langRow);

        // 包一层 ScrollView：展开语言列表 + 小屏大字时内容会超高
        android.widget.ScrollView aboutScroll = new android.widget.ScrollView(this);
        aboutScroll.addView(container);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_about))
                .setView(aboutScroll)
                .setPositiveButton(getString(R.string.btn_ok), null)
                .create();

        dialogRef[0] = dialog;
        dialog.show();
    }

    private String resolveSignFilePath(Uri uri) {
        String path = uri.getPath();
        if (path != null) {
            if (path.contains("/document/primary:")) {
                String relativePath = path.replace("/document/primary:", "");
                return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relativePath;
            } else if (path.contains("/storage/emulated/0/")) {
                int index = path.indexOf("/storage/emulated/0/");
                return path.substring(index);
            }
        }
        return null;
    }

    private void updateProgressSafe(int progress, int max) {
        progressBar.setMax(max);
        progressBar.setProgress(progress);
        if (max > 0) {
            int percent = (int) (((float) progress / (float) max) * 100f);
            tvProgressPercent.setText(percent + "%");
        }
    }

    private void setIndeterminateModeSafe(boolean isIndeterminate) {
        progressBar.setIndeterminate(isIndeterminate);
        if (isIndeterminate) {
            tvProgressPercent.setText(getString(R.string.log_reading));
        }
    }

    private void updateLogSafe(String msg) {
        printLog(msg);
    }

    private void printLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logLine = "[" + time + "] " + msg + "\n";
        tvLogOutput.append(logLine);
        scrollViewLog.post(new Runnable() {
            @Override
            public void run() {
                scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    private void showUpdateDialog(final String newVersion, final List<UpdateManager.DownloadSource> sources, final String changelog, final boolean forceUpdate) {
    android.widget.LinearLayout container = new android.widget.LinearLayout(this);
    container.setOrientation(android.widget.LinearLayout.VERTICAL);
    int padding = (int) (20 * getResources().getDisplayMetrics().density);
    container.setPadding(padding, padding, padding, padding);

    android.widget.TextView tvVersion = new android.widget.TextView(this);
    tvVersion.setText(getString(R.string.title_new_version_found) + newVersion);
    tvVersion.setTextSize(16f);
    tvVersion.setTextColor(android.graphics.Color.parseColor("#000000"));
    tvVersion.setPadding(0, 0, 0, 15);
    container.addView(tvVersion);

    // 显示跳过状态
    if (isVersionSkipped(newVersion)) {
        android.widget.TextView tvSkipped = new android.widget.TextView(this);
        tvSkipped.setText(getString(R.string.log_version_skipped_before));
        tvSkipped.setTextSize(13f);
        tvSkipped.setTextColor(android.graphics.Color.parseColor("#FF9800"));
        tvSkipped.setPadding(0, 0, 0, 10);
        container.addView(tvSkipped);
    }

    // 强制更新标签
    if (forceUpdate) {
        android.widget.TextView tvForce = new android.widget.TextView(this);
        tvForce.setText(getString(R.string.msg_force_update_tag));
        tvForce.setTextSize(14f);
        tvForce.setTextColor(android.graphics.Color.parseColor("#FF5722"));
        tvForce.setPadding(0, 0, 0, 10);
        container.addView(tvForce);
    }

    android.widget.TextView tvChangelog = new android.widget.TextView(this);
    tvChangelog.setText(getString(R.string.msg_update_content) + changelog);
    tvChangelog.setTextSize(14f);
    tvChangelog.setTextColor(android.graphics.Color.parseColor("#666666"));
    tvChangelog.setPadding(0, 0, 0, 20);
    container.addView(tvChangelog);

    android.widget.TextView tvSourceInfo = new android.widget.TextView(this);
    tvSourceInfo.setText(getString(R.string.log_total_sources) + sources.size() + getString(R.string.fmt_sources_available));
    tvSourceInfo.setTextSize(13f);
    tvSourceInfo.setTextColor(android.graphics.Color.parseColor("#999999"));
    container.addView(tvSourceInfo);

    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_new_version_available))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_download_now), new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    clearSkippedVersion();  // 清除跳过记录
                    printLog(getString(R.string.log_smart_download_start));
                    if (updateManager != null) {
                        updateManager.smartDownload(sources, "update_" + newVersion + ".apk");
                    }
                }
            });

    if (forceUpdate) {
        builder.setCancelable(false);
    } else {
        builder.setNegativeButton(getString(R.string.btn_remind_later_alt), null);
        builder.setNeutralButton(getString(R.string.btn_skip_version), new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                skipVersion(newVersion);
            }
        });
        builder.setCancelable(true);
    }

    builder.show();
}

    /**
 * 检查更新
 * @param isManual 是否为手动检查
 */
private void checkForUpdates(final boolean isManual) {
    ensureUpdateManager();
    
    updateManager.checkUpdate(new UpdateManager.UpdateCallback() {
        @Override
        public void onUpdateAvailable(String newVersion, List<UpdateManager.DownloadSource> sources, String changelog, boolean forceUpdate, boolean isManualCheck) {
            // 自动检查时，检查是否跳过了此版本
            if (!isManualCheck && !forceUpdate && isVersionSkipped(newVersion)) {
                printLog(getString(R.string.log_version_skipped) + newVersion + getString(R.string.msg_auto_check));
                return;
            }
            
            // 手动检查或未跳过，显示更新对话框
            if (isManualCheck && isVersionSkipped(newVersion)) {
                printLog(getString(R.string.log_show_skipped) + newVersion);
            }
            
            showUpdateDialog(newVersion, sources, changelog, forceUpdate);
        }

        @Override
        public void onNoUpdate() {
            if (isManual) {
                // 手动检查时，显示提示
                showToast(getString(R.string.log_is_latest));
            }
            printLog(getString(R.string.log_is_latest));
        }

        @Override
        public void onCheckError(String error) {
            if (isManual) {
                // 手动检查时，显示错误提示
                showToast(getString(R.string.err_update_check_failed) + error);
            }
            printLog(getString(R.string.err_update_check_failed) + error);
        }

        @Override
        public void onDownloadProgress(int progress, int max) {
            updateDownloadProgress(progress, max);
        }

        @Override
        public void onDownloadComplete(File apkFile) {
            dismissDownloadProgress();
            showInstallDialog(apkFile);
        }

        @Override
        public void onDownloadError(String error) {
            dismissDownloadProgress();
            showErrorDialog(getString(R.string.err_update_failed), error);
        }

        @Override
        public void onLog(String message) {
            printLog(message);
        }

        @Override
        public void onForceUpdate(String newVersion, String currentVersion, String minVersion, List<UpdateManager.DownloadSource> sources, String changelog) {
            showForceUpdateDialog(newVersion, currentVersion, minVersion, sources, changelog);
        }
    }, isManual);  // ← 传递检查类型
}

// 添加便捷方法
private void checkForUpdates() {
    checkForUpdates(false);  // 默认为自动检查
}

    private void startDownload(String downloadUrl, String fileName) {
        showDownloadProgress();
        ensureUpdateManager();
        updateManager.downloadApk(downloadUrl, fileName);
    }

    private void showDownloadProgress() {
        downloadProgressDialog = new android.app.ProgressDialog(this);
        downloadProgressDialog.setTitle(getString(R.string.log_downloading));
        downloadProgressDialog.setMessage(getString(R.string.log_ready_download));
        downloadProgressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        downloadProgressDialog.setCancelable(false);
        downloadProgressDialog.setMax(100);
        downloadProgressDialog.show();
    }

    private void updateDownloadProgress(int progress, int max) {
        if (downloadProgressDialog != null && downloadProgressDialog.isShowing()) {
            downloadProgressDialog.setMax(max);
            downloadProgressDialog.setProgress(progress);

            int percent = max > 0 ? (int) ((progress * 100.0f) / max) : 0;
            downloadProgressDialog.setMessage(getString(R.string.log_downloaded) + percent + "% (" +
                    formatFileSize(progress) + " / " + formatFileSize(max) + ")");
        }
    }

    private void dismissDownloadProgress() {
        if (downloadProgressDialog != null && downloadProgressDialog.isShowing()) {
            downloadProgressDialog.dismiss();
        }
    }

    private void showInstallDialog(final File apkFile) {
        // Android 8.0+ 先检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                // 没有权限，先弹出引导对话框
                new android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.err_need_install_perm))
                        .setMessage(getString(R.string.msg_need_unknown_source))
                        .setPositiveButton(getString(R.string.btn_to_authorize), new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                pendingInstallApk = apkFile; // 记录待安装文件
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            }
                        })
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show();
                return;
            }
        }

        // 有权限，直接显示安装对话框
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.log_download_done))
                .setMessage(getString(R.string.msg_ask_install))
                .setPositiveButton(getString(R.string.btn_install_now), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (updateManager != null) {
                            updateManager.installApk(apkFile);
                        }
                    }
                })
                .setNegativeButton(getString(R.string.btn_install_later), null)
                .setCancelable(false)
                .show();
    }

    private void showErrorDialog(String title, String message) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.btn_ok), null)
                .show();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

/** 检查是否跳过了某个版本 */
    private boolean isVersionSkipped(String version) {
        android.content.SharedPreferences prefs = getSharedPreferences("UpdateConfig", MODE_PRIVATE);
        String skippedVersion = prefs.getString("skipped_version", "");
        return version.equals(skippedVersion);
    }

/** 跳过某个版本 */
    private void skipVersion(String version) {
        android.content.SharedPreferences prefs = getSharedPreferences("UpdateConfig", MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        editor.putString("skipped_version", version);
        editor.apply();
        printLog(getString(R.string.log_skipped_version) + version);
    }

/** 清除跳过的版本记录 */
    private void clearSkippedVersion() {
        android.content.SharedPreferences prefs = getSharedPreferences("UpdateConfig", MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        editor.remove("skipped_version");
        editor.apply();
    }

    private void showForceUpdateDialog(final String newVersion, final String currentVersion, final String minVersion, final List<
                    UpdateManager.DownloadSource> sources, final String changelog) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        android.widget.TextView tvTitle = new android.widget.TextView(this);
        tvTitle.setText(getString(R.string.title_must_update));
        tvTitle.setTextSize(18f);
        tvTitle.setTextColor(android.graphics.Color.parseColor("#FF5722"));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 15);
        container.addView(tvTitle);

        android.widget.TextView tvMessage = new android.widget.TextView(this);
        tvMessage.setText(getString(R.string.msg_too_old) +
                getString(R.string.fmt_current_version) + currentVersion + "\n" +
                getString(R.string.fmt_min_version) + minVersion + "\n" +
                getString(R.string.fmt_latest_version) + newVersion + "\n\n" +
                getString(R.string.msg_update_content) + changelog);
        tvMessage.setTextSize(14f);
        tvMessage.setTextColor(android.graphics.Color.parseColor("#333333"));
        container.addView(tvMessage);

        new android.app.AlertDialog.Builder(this)
                .setView(container)
                .setPositiveButton(getString(R.string.btn_update_now), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        printLog(getString(R.string.log_force_update_start));
                        if (updateManager != null) {
                            updateManager.smartDownload(sources, "update_" + newVersion + ".apk");
                        }
                    }
                })
                .setCancelable(false)
                .show();
    }
    
    /**
 * 显示 Toast 提示
 */
private void showToast(final String message) {
    runOnUiThread(new Runnable() {
        @Override
        public void run() {
            android.widget.Toast.makeText(MainActivity.this, message, android.widget.Toast.LENGTH_SHORT).show();
        }
    });
}
}
