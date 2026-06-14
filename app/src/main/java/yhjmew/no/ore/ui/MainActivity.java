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
    private TextView btnSignConfig;
    private TextView btnSoConfig;

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
        Locale sysLocale = Locale.getDefault();
        String lang = sysLocale.getLanguage();
        if (!"zh".equals(lang)) {
            Locale enLocale = new Locale("en");
            Locale.setDefault(enLocale);
            android.content.res.Configuration config = getResources().getConfiguration();
            config.setLocale(enLocale);
            getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        btnSelectMc = (Button) findViewById(R.id.btn_select_mc);
        btnMakeClone = (Button) findViewById(R.id.btn_make_clone);
        tvLogOutput = (TextView) findViewById(R.id.tv_log_output);
        scrollViewLog = (ScrollView) findViewById(R.id.scroll_view_log);
        progressBar = (ProgressBar) findViewById(R.id.progress_bar);
        tvProgressPercent = (TextView) findViewById(R.id.tv_progress_percent);
        btnSignConfig = (TextView) findViewById(R.id.btn_sign_config);
        btnSoConfig = (TextView) findViewById(R.id.btn_so_config);

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

        btnSignConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSignConfigDialog();
            }
        });

        btnSoConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSoConfigDialog();
            }
        });

        printLog(getString(R.string.the_application_started_successfully_and_is_waitin));
        printLog(getString(R.string.current_signature) + signatureManager.getSignDisplayName(signatureManager.getCurrentSignType()));
        printLog(getString(R.string.current_so_type) + apkProcessor.getSoDisplayName(apkProcessor.getCurrentSoType()));

        // 检查自动更新设置
        android.content.SharedPreferences prefs = getSharedPreferences("UpdateConfig", MODE_PRIVATE);
        boolean autoUpdateEnabled = prefs.getBoolean("auto_update_enabled", true);

        if (autoUpdateEnabled) {
            printLog(getString(R.string.auto_update_checking));
            checkForUpdates(false);
        } else {
            printLog(getString(R.string.auto_update_disabled));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (pendingInstallApk != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (getPackageManager().canRequestPackageInstalls()) {
                printLog(getString(R.string.obtained_installation_permission_installing));
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
                printLog(getString(R.string.permission_granted_please_click_the_button_again_t));
            }
            return;
        }

        if (requestCode == REQUEST_CODE_SELECT_SIGN_FILE && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri signUri = data.getData();
                String fileName = getFileNameFromUri(signUri);

                if (fileName != null && !fileName.toLowerCase(Locale.getDefault()).endsWith(".bks")) {
                    printLog(getString(R.string.only_signature_files_in_bks_format_are_supported_n) + fileName);
                    return;
                }

                printLog(getString(R.string.signature_file_selected) + fileName);

                String realPath = resolveSignFilePath(signUri);

                if (realPath != null) {
                    File f = new File(realPath);
                    if (f.exists()) {
                        signatureManager.setCustomSignFilePath(realPath);
                        printLog(getString(R.string.signature_file_path) + realPath);
                    } else {
                        try {
                            String cachedPath = signatureManager.copySignFileToCache(signUri);
                            signatureManager.setCustomSignFilePath(cachedPath);
                            printLog(getString(R.string.signature_file_cached) + cachedPath);
                        } catch (Exception e) {
                            printLog(getString(R.string.failed_to_cache_signature_file) + Utils.getFullStackTrace(e));
                            return;
                        }
                    }
                } else {
                    try {
                        String cachedPath = signatureManager.copySignFileToCache(signUri);
                        signatureManager.setCustomSignFilePath(cachedPath);
                        printLog(getString(R.string.signature_file_cached) + cachedPath);
                    } catch (Exception e) {
                        printLog(getString(R.string.failed_to_cache_signature_file) + Utils.getFullStackTrace(e));
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

                String detectedPkg = null;
                try {
                    detectedPkg = packageDetector.detectPackageNameFromApkUri(apkUri);
                } catch (Exception e) {
                    printLog(getString(R.string.failed_to_automatically_read_the_current_package_n) + Utils.getFullStackTrace(e));
                }

                if (detectedPkg != null && detectedPkg.length() > 0) {
                    currentDetectedPackageName = detectedPkg;
                } else {
                    currentDetectedPackageName = DEFAULT_PKG_NAME;
                }

                currentCloneMaxLength = Math.min(currentDetectedPackageName.length(), SO_PKG_MAX_LENGTH);

                printLog(getString(R.string.selected_files) + originalFileName);
                printLog(getString(R.string.current_package_name_detected) + currentDetectedPackageName);
                printLog(getString(R.string.the_maximum_length_of_this_coexistence_package_nam) + currentCloneMaxLength);

                if (currentPickMode == PICK_MODE_NORMAL) {
                    printLog(getString(R.string.ready_to_start_normal_processing));
                    processApkInBackground(apkUri, false, currentDetectedPackageName, currentDetectedPackageName);
                } else if (currentPickMode == PICK_MODE_CLONE) {
                    if (detectedPkg == null || detectedPkg.length() == 0) {
                        printLog(getString(R.string.unable_to_read_the_current_apk_package_name_and_en));
                    } else {
                        printLog(getString(R.string.please_continue_to_enter_the_target_coexistence_pa));
                        showCloneConfigDialog(apkUri);
                    }
                } else {
                    printLog(getString(R.string.the_current_processing_mode_is_not_recognized));
                }

                currentPickMode = PICK_MODE_NONE;
            }
        }
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
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex != -1) {
                originalFileName = cursor.getString(nameIndex);
            }
            cursor.close();
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
                printLog(getString(R.string.requires_all_file_access_permission_jumping_to_set));
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
                printLog(getString(R.string.storage_permission_is_required_applying));
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
            printLog(getString(R.string.normal_mode_opening_file_manager));
        } else if (currentPickMode == PICK_MODE_CLONE) {
            printLog(getString(R.string.coexistence_mode_opening_file_manager));
        } else {
            printLog(getString(R.string.opening_file_manager));
        }

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/vnd.android.package-archive");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_CODE_SELECT_APK);
        } catch (Exception e) {
            printLog(getString(R.string.failed_to_open_file_manager) + Utils.getFullStackTrace(e));
        }
    }

    private void showSignConfigDialog() {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        android.widget.TextView tvSignTitle = new android.widget.TextView(this);
        tvSignTitle.setText(getString(R.string.signature_file_selection));
        tvSignTitle.setTextSize(16f);
        tvSignTitle.setTextColor(android.graphics.Color.parseColor("#000000"));
        tvSignTitle.setPadding(0, 0, 0, 10);
        container.addView(tvSignTitle);

        final android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(this);
        String[] signNames = new String[]{
                getString(R.string.default_mt_signature),
                getString(R.string.zihao_il_signature),
                getString(R.string.custom_signaturebks_signature_files_only)
        };
        for (int i = 0; i < signNames.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(signNames[i]);
            rb.setId(i);
            radioGroup.addView(rb);
        }
        if (signatureManager.getCurrentSignType() < 3) {
            radioGroup.check(signatureManager.getCurrentSignType());
        } else {
            radioGroup.check(2);
        }
        container.addView(radioGroup);

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.signature_settings))
                .setView(container)
                .setPositiveButton(getString(R.string.sure), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        int selectedId = radioGroup.getCheckedRadioButtonId();

                        if (selectedId == 2) {
                            if (signatureManager.getCustomSignFilePath() != null && signatureManager.getCustomSignFilePath().length() > 0) {
                                signatureManager.setCurrentSignType(SignatureManager.SIGN_TYPE_CUSTOM);
                            }
                            openSignFilePicker();
                        } else {
                            signatureManager.setCurrentSignType(selectedId);
                            printLog(getString(R.string.switched_to) + signatureManager.getSignDisplayName(signatureManager.getCurrentSignType()));
                        }

                        signatureManager.saveSignConfig();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void openSignFilePicker() {
        printLog(getString(R.string.opening_signature_file_selector_bks_only));
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/octet-stream");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_CODE_SELECT_SIGN_FILE);
        } catch (Exception e) {
            printLog(getString(R.string.failed_to_open_file_manager) + Utils.getFullStackTrace(e));
        }
    }

    private void showCustomSignInputDialog(String prefillPath) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        android.widget.TextView tvHint = new android.widget.TextView(this);
        tvHint.setText(getString(R.string.please_enter_the_full_path_to_the_signature_file_a));
        tvHint.setTextColor(android.graphics.Color.parseColor("#333333"));
        tvHint.setTextSize(14f);
        tvHint.setPadding(0, 0, 0, 20);
        container.addView(tvHint);

        final android.widget.EditText editPath = new android.widget.EditText(this);
        editPath.setHint(getString(R.string.signature_file_path_such_as_sdcardsignjks));
        editPath.setSingleLine(true);
        if (prefillPath != null && prefillPath.length() > 0) {
            editPath.setText(prefillPath);
        } else if (signatureManager.getCustomSignFilePath() != null && signatureManager.getCustomSignFilePath().length() > 0) {
            editPath.setText(signatureManager.getCustomSignFilePath());
        }
        container.addView(editPath);

        final android.widget.EditText editAlias = new android.widget.EditText(this);
        editAlias.setHint(getString(R.string.alias));
        editAlias.setText(signatureManager.getCustomSignAlias());
        editAlias.setSingleLine(true);
        container.addView(editAlias);

        final android.widget.EditText editKeystorePassword = new android.widget.EditText(this);
        editKeystorePassword.setHint(getString(R.string.keystore_password_open_signature_file));
        editKeystorePassword.setText(signatureManager.getCustomSignKeystorePassword());
        editKeystorePassword.setSingleLine(true);
        container.addView(editKeystorePassword);

        final android.widget.EditText editKeyPassword = new android.widget.EditText(this);
        editKeyPassword.setHint(getString(R.string.alias_password_access_private_key));
        editKeyPassword.setText(signatureManager.getCustomSignKeyPassword());
        editKeyPassword.setSingleLine(true);
        container.addView(editKeyPassword);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.custom_signature_configuration))
                .setView(container)
                .setPositiveButton(getString(R.string.sure), null)
                .setNegativeButton(getString(R.string.cancel), null)
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
                    printLog(getString(R.string.the_signature_file_path_cannot_be_empty));
                    return;
                }

                if (!path.toLowerCase(Locale.getDefault()).endsWith(".bks")) {
                    printLog(getString(R.string.only_signature_files_in_bks_format_are_supported_n) + path);
                    return;
                }

                File signFile = new File(path);
                if (!signFile.exists()) {
                    printLog(getString(R.string.the_signature_file_does_not_exist) + path);
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
                printLog(getString(R.string.custom_signature_saved));
                printLog(getString(R.string.path) + signatureManager.getCustomSignFilePath());
                printLog(getString(R.string.alias_1) + signatureManager.getCustomSignAlias());
                printLog(getString(R.string.current_signature) + signatureManager.getSignDisplayName(signatureManager.getCurrentSignType()));

                dialog.dismiss();
            }
        });
    }

    private void showSoConfigDialog() {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        android.widget.TextView tvSoTitle = new android.widget.TextView(this);
        tvSoTitle.setText(getString(R.string.so_selection));
        tvSoTitle.setTextSize(16f);
        tvSoTitle.setTextColor(android.graphics.Color.parseColor("#000000"));
        tvSoTitle.setPadding(0, 0, 0, 10);
        container.addView(tvSoTitle);

        final android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(this);

        android.widget.RadioButton rbStandalone = new android.widget.RadioButton(this);
        rbStandalone.setText(getString(R.string.so_standalone_no_libpreloader));
        rbStandalone.setId(1); // SO_TYPE_STANDALONE
        radioGroup.addView(rbStandalone);

        android.widget.RadioButton rbWithPreloader = new android.widget.RadioButton(this);
        rbWithPreloader.setText(getString(R.string.so_with_preloader_need_libpreloader));
        rbWithPreloader.setId(0); // SO_TYPE_WITH_PRELOADER
        radioGroup.addView(rbWithPreloader);

        radioGroup.check(apkProcessor.getCurrentSoType());
        container.addView(radioGroup);

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.so_settings))
                .setView(container)
                .setPositiveButton(getString(R.string.sure), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        int selectedId = radioGroup.getCheckedRadioButtonId();
                        apkProcessor.setCurrentSoType(selectedId);
                        apkProcessor.saveSoConfig();
                        printLog(getString(R.string.switched_to) + apkProcessor.getSoDisplayName(apkProcessor.getCurrentSoType()));
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showCloneConfigDialog(final Uri apkUri) {
        final String sourcePkg = currentDetectedPackageName;
        final int maxLength = currentCloneMaxLength;

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        android.widget.TextView tvHint = new android.widget.TextView(this);
        tvHint.setText(getString(R.string.current_package_name_detectedn) + sourcePkg
                + getString(R.string.nnplease_enter_the_target_coexistence_package_name)
                + getString(R.string.nmaximum_length) + maxLength
                + getString(R.string.nso_androidmanifestxml_resourcesarsc_and_packagein));
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
        tvCounter.setText(getString(R.string.current_word_count) + initialLen + " / " + maxLength);
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
                tvCounter.setText(getString(R.string.current_word_count) + len + " / " + maxLength);
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
                .setTitle(getString(R.string.configure_coexistence_package_name))
                .setView(container)
                .setPositiveButton(getString(R.string.start), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create();

        dialog.show();

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newPkg = editText.getText().toString().trim();

                if (newPkg.length() == 0) {
                    printLog(getString(R.string.the_package_name_cannot_be_empty));
                    return;
                }

                if (newPkg.length() > maxLength) {
                    printLog(getString(R.string.the_package_name_is_too_long_the_maximum_allowed_t) + maxLength + getString(R.string.characters));
                    return;
                }

                if (!Utils.isValidPackageName(newPkg)) {
                    printLog(getString(R.string.the_package_name_format_is_illegal_please_check_an));
                    return;
                }

                if (sourcePkg.equals(newPkg)) {
                    printLog(getString(R.string.the_target_coexistence_package_name_cannot_be_the_));
                    return;
                }

                printLog(getString(R.string.current_package_name) + sourcePkg);
                printLog(getString(R.string.target_coexistence_package_name) + newPkg);
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

                            updateLogSafe(getString(R.string.an_error_occurred) + msg);

                            Throwable cause = e.getCause();
                            if (cause != null) {
                                String causeMsg = cause.getMessage();
                                if (causeMsg != null && causeMsg.length() > 0 && !causeMsg.equals(msg)) {
                                    updateLogSafe(getString(R.string.root_cause) + causeMsg);
                                }
                                Throwable rootCause = cause.getCause();
                                if (rootCause != null) {
                                    String rootMsg = rootCause.getMessage();
                                    if (rootMsg != null && rootMsg.length() > 0
                                            && !rootMsg.equals(msg) && !rootMsg.equals(causeMsg)) {
                                        updateLogSafe(getString(R.string.deep_reasons) + rootMsg);
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
                                updateLogSafe(getString(R.string.top_of_stackn) + sb.toString());
                            }

                            e.printStackTrace();
                        }
                    });
                }
            }
        }).start();
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

        String htmlContent = getString(R.string.no_oreui_toolsbrbr) +
                getString(R.string.purpose_modify_the_minecraft_installation_package_) +
                getString(R.string.support_normal_processing_mode_and_automatic_coexi) +
                getString(R.string.signature_supports_switching_between_multiple_sign) +
                getString(R.string.qq_groupbr) +
                getString(R.string.join_qq_group) +
                getString(R.string.telegram_channelbr) +
                getString(R.string.telegram_channel);

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

        // 自动更新复选框
        final android.widget.CheckBox checkBoxAutoUpdate = new android.widget.CheckBox(this);
        checkBoxAutoUpdate.setText(getString(R.string.enable_auto_update));
        checkBoxAutoUpdate.setTextColor(android.graphics.Color.parseColor("#000000"));
        checkBoxAutoUpdate.setTextSize(14f);

        // 读取当前设置
        android.content.SharedPreferences prefs = getSharedPreferences("UpdateConfig", MODE_PRIVATE);
        boolean autoUpdateEnabled = prefs.getBoolean("auto_update_enabled", true); // 默认启用
        checkBoxAutoUpdate.setChecked(autoUpdateEnabled);

        container.addView(checkBoxAutoUpdate);

        // 立即检查更新按钮
        android.widget.Button btnCheckUpdate = new android.widget.Button(this);
        btnCheckUpdate.setText(getString(R.string.check_update_now));
        btnCheckUpdate.setBackground(getResources().getDrawable(R.drawable.bg_black_border));
        btnCheckUpdate.setTextColor(android.graphics.Color.parseColor("#000000"));
        android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        btnCheckUpdate.setLayoutParams(btnParams);
        container.addView(btnCheckUpdate);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.about))
                .setView(container)
                .setPositiveButton(getString(R.string.sure), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        // 保存自动更新设置
                        android.content.SharedPreferences prefs = getSharedPreferences("UpdateConfig", MODE_PRIVATE);
                        android.content.SharedPreferences.Editor editor = prefs.edit();
                        editor.putBoolean("auto_update_enabled", checkBoxAutoUpdate.isChecked());
                        editor.apply();

                        if (checkBoxAutoUpdate.isChecked()) {
                            printLog(getString(R.string.auto_update_enabled_log));
                        } else {
                            printLog(getString(R.string.auto_update_disabled_log));
                        }
                    }
                })
                .create();

        dialog.show();

        // 立即检查更新按钮点击事件
        btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                printLog(getString(R.string.manually_checking_update));
                checkForUpdates(true);
            }
        });
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
            tvProgressPercent.setText(getString(R.string.readingcalculating));
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
    tvVersion.setText(getString(R.string.new_version_found) + newVersion);
    tvVersion.setTextSize(16f);
    tvVersion.setTextColor(android.graphics.Color.parseColor("#000000"));
    tvVersion.setPadding(0, 0, 0, 15);
    container.addView(tvVersion);

    // 显示跳过状态
    if (isVersionSkipped(newVersion)) {
        android.widget.TextView tvSkipped = new android.widget.TextView(this);
        tvSkipped.setText(getString(R.string.you_have_skipped_this_version_before));
        tvSkipped.setTextSize(13f);
        tvSkipped.setTextColor(android.graphics.Color.parseColor("#FF9800"));
        tvSkipped.setPadding(0, 0, 0, 10);
        container.addView(tvSkipped);
    }

    // 强制更新标签
    if (forceUpdate) {
        android.widget.TextView tvForce = new android.widget.TextView(this);
        tvForce.setText(getString(R.string.this_version_is_a_mandatory_update));
        tvForce.setTextSize(14f);
        tvForce.setTextColor(android.graphics.Color.parseColor("#FF5722"));
        tvForce.setPadding(0, 0, 0, 10);
        container.addView(tvForce);
    }

    android.widget.TextView tvChangelog = new android.widget.TextView(this);
    tvChangelog.setText(getString(R.string.update_contentn) + changelog);
    tvChangelog.setTextSize(14f);
    tvChangelog.setTextColor(android.graphics.Color.parseColor("#666666"));
    tvChangelog.setPadding(0, 0, 0, 20);
    container.addView(tvChangelog);

    android.widget.TextView tvSourceInfo = new android.widget.TextView(this);
    tvSourceInfo.setText(getString(R.string.shared) + sources.size() + getString(R.string.download_sources_available));
    tvSourceInfo.setTextSize(13f);
    tvSourceInfo.setTextColor(android.graphics.Color.parseColor("#999999"));
    container.addView(tvSourceInfo);

    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_version_available))
            .setView(container)
            .setPositiveButton(getString(R.string.download_now), new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    clearSkippedVersion();  // 清除跳过记录
                    printLog(getString(R.string.start_smart_download));
                    if (updateManager != null) {
                        updateManager.smartDownload(sources, "update_" + newVersion + ".apk");
                    }
                }
            });

    if (forceUpdate) {
        builder.setCancelable(false);
    } else {
        builder.setNegativeButton(getString(R.string.reminder_later), null);
        builder.setNeutralButton(getString(R.string.skip_this_version), new android.content.DialogInterface.OnClickListener() {
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
    if (updateManager == null) {
        updateManager = new UpdateManager(this);
    }
    
    updateManager.checkUpdate(new UpdateManager.UpdateCallback() {
        @Override
        public void onUpdateAvailable(String newVersion, List<UpdateManager.DownloadSource> sources, String changelog, boolean forceUpdate, boolean isManualCheck) {
            // 自动检查时，检查是否跳过了此版本
            if (!isManualCheck && !forceUpdate && isVersionSkipped(newVersion)) {
                printLog(getString(R.string.version_skipped) + newVersion + getString(R.string.automatic_check));
                return;
            }
            
            // 手动检查或未跳过，显示更新对话框
            if (isManualCheck && isVersionSkipped(newVersion)) {
                printLog(getString(R.string.manual_inspection_show_skipped_versions) + newVersion);
            }
            
            showUpdateDialog(newVersion, sources, changelog, forceUpdate);
        }

        @Override
        public void onNoUpdate() {
            if (isManual) {
                // 手动检查时，显示提示
                showToast(getString(R.string.it_is_currently_the_latest_version));
            }
            printLog(getString(R.string.it_is_currently_the_latest_version));
        }

        @Override
        public void onCheckError(String error) {
            if (isManual) {
                // 手动检查时，显示错误提示
                showToast(getString(R.string.update_check_failed) + error);
            }
            printLog(getString(R.string.update_check_failed) + error);
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
            showErrorDialog(getString(R.string.update_error), error);
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
        if (updateManager == null) {
            updateManager = new UpdateManager(this);
        }
        updateManager.downloadApk(downloadUrl, fileName);
    }

    private void showDownloadProgress() {
        downloadProgressDialog = new android.app.ProgressDialog(this);
        downloadProgressDialog.setTitle(getString(R.string.downloading));
        downloadProgressDialog.setMessage(getString(R.string.ready_to_download));
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
            downloadProgressDialog.setMessage(getString(R.string.downloaded) + percent + "% (" +
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
                        .setTitle(getString(R.string.requires_installation_permissions))
                        .setMessage(getString(R.string.the_app_requires_the_install_unknown_app_permissio))
                        .setPositiveButton(getString(R.string.to_authorize), new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                pendingInstallApk = apkFile; // 记录待安装文件
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            }
                        })
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show();
                return;
            }
        }

        // 有权限，直接显示安装对话框
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.download_completed))
                .setMessage(getString(R.string.the_update_package_has_been_downloaded_do_you_want))
                .setPositiveButton(getString(R.string.install_now), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (updateManager != null) {
                            updateManager.installApk(apkFile);
                        }
                    }
                })
                .setNegativeButton(getString(R.string.install_later), null)
                .setCancelable(false)
                .show();
    }

    private void showErrorDialog(String title, String message) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.sure), null)
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
        printLog(getString(R.string.skipped_version) + version);
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
        tvTitle.setText(getString(R.string.must_update));
        tvTitle.setTextSize(18f);
        tvTitle.setTextColor(android.graphics.Color.parseColor("#FF5722"));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 15);
        container.addView(tvTitle);

        android.widget.TextView tvMessage = new android.widget.TextView(this);
        tvMessage.setText(getString(R.string.your_current_version_is_too_old_and_must_be_update) +
                getString(R.string.current_version) + currentVersion + "\n" +
                getString(R.string.minimum_version) + minVersion + "\n" +
                getString(R.string.latest_version) + newVersion + "\n\n" +
                getString(R.string.update_contentn) + changelog);
        tvMessage.setTextSize(14f);
        tvMessage.setTextColor(android.graphics.Color.parseColor("#333333"));
        container.addView(tvMessage);

        new android.app.AlertDialog.Builder(this)
                .setView(container)
                .setPositiveButton(getString(R.string.update_now), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        printLog(getString(R.string.forced_update_start_downloading));
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
