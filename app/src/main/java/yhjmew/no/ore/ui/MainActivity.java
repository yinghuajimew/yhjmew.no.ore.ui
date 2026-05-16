package yhjmew.no.ore.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.MethodRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;

import com.android.apksig.ApkSigner;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private Button btnSelectMc;
    private Button btnMakeClone;
    private TextView tvLogOutput;
    private ScrollView scrollViewLog;
    private ProgressBar progressBar;
    private TextView tvProgressPercent;
    private TextView btnSignConfig;

    private static final int REQUEST_CODE_PERMISSION_STORAGE = 100;
    private static final int REQUEST_CODE_PERMISSION_ALL_FILES = 101;
    private static final int REQUEST_CODE_SELECT_APK = 102;
    private static final int REQUEST_CODE_SELECT_SIGN_FILE = 103;

    private static final int PICK_MODE_NONE = 0;
    private static final int PICK_MODE_NORMAL = 1;
    private static final int PICK_MODE_CLONE = 2;

    private static final int SIGN_TYPE_MT = 0;
    private static final int SIGN_TYPE_ZIHAO_IL = 1;
    private static final int SIGN_TYPE_CUSTOM = 2;

    private static final String DEFAULT_PKG_NAME = "com.mojang.minecraftpe";
    private static final int SO_PKG_MAX_LENGTH = 34;
    private static final String PREF_NAME = "CloneConfig";
    private static final String PREF_SIGN = "SignConfig";

    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int UTF8_FLAG = 0x00000100;

    private int currentPickMode = PICK_MODE_NONE;

    private String originalFileName = "minecraft.apk";
    private String originalDirectory = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download";

    private String currentDetectedPackageName = DEFAULT_PKG_NAME;
    private int currentCloneMaxLength = Math.min(DEFAULT_PKG_NAME.length(), SO_PKG_MAX_LENGTH);

    private int currentSignType = SIGN_TYPE_MT;
    private String customSignFilePath = null;
    private String customSignAlias = "android";
    private String customSignKeystorePassword = "android";
    private String customSignKeyPassword = "android";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Locale sysLocale = Locale.getDefault();
        String lang = sysLocale.getLanguage();
        if (!"zh".equals(lang)) {
            // 非中文 → 强制英文
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

        loadSignConfig();

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

        printLog(getString(R.string.the_application_started_successfully_and_is_waitin));
        printLog(getString(R.string.current_signature) + getSignDisplayName(currentSignType));
    }

    private void loadSignConfig() {
        SharedPreferences prefs = getSharedPreferences(PREF_SIGN, MODE_PRIVATE);
        currentSignType = prefs.getInt("sign_type", SIGN_TYPE_MT);
        customSignFilePath = prefs.getString("custom_path", null);
        customSignAlias = prefs.getString("custom_alias", "android");
        customSignKeystorePassword = prefs.getString("custom_ks_password", "android");
        customSignKeyPassword = prefs.getString("custom_key_password", "android");
    }

    private void saveSignConfig() {
        SharedPreferences prefs = getSharedPreferences(PREF_SIGN, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("sign_type", currentSignType);
        if (customSignFilePath != null) {
            editor.putString("custom_path", customSignFilePath);
        }
        editor.putString("custom_alias", customSignAlias);
        editor.putString("custom_ks_password", customSignKeystorePassword);
        editor.putString("custom_key_password", customSignKeyPassword);
        editor.apply();
    }

    private String getSignDisplayName(int type) {
        if (type == SIGN_TYPE_MT) {
            return getString(R.string.default_mt_signature);
        } else if (type == SIGN_TYPE_ZIHAO_IL) {
            return getString(R.string.zihao_il_signature);
        } else if (type == SIGN_TYPE_CUSTOM) {
            return getString(R.string.custom_signature);
        }
        return getString(R.string.unknown_signature);
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
        String[] signNames = new String
                []{getString(R.string.default_mt_signature), getString(R.string.zihao_il_signature), getString(R.string.custom_signaturebks_signature_files_only)};
        for (int i = 0; i < signNames.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(signNames[i]);
            rb.setId(i);
            radioGroup.addView(rb);
        }
        if (currentSignType < 3) {
            radioGroup.check(currentSignType);
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
                            if (customSignFilePath != null && customSignFilePath.length() > 0) {
                                currentSignType = SIGN_TYPE_CUSTOM;
                            }
                            openSignFilePicker();
                        } else {
                            currentSignType = selectedId;
                            printLog(getString(R.string.switched_to) + getSignDisplayName(currentSignType));
                        }

                        saveSignConfig();
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
            printLog(getString(R.string.failed_to_open_file_manager) + getFullStackTrace(e));
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
        } else if (customSignFilePath != null && customSignFilePath.length() > 0) {
            editPath.setText(customSignFilePath);
        }
        container.addView(editPath);

        final android.widget.EditText editAlias = new android.widget.EditText(this);
        editAlias.setHint(getString(R.string.alias));
        editAlias.setText(customSignAlias);
        editAlias.setSingleLine(true);
        container.addView(editAlias);

        final android.widget.EditText editKeystorePassword = new android.widget.EditText(this);
        editKeystorePassword.setHint(getString(R.string.keystore_password_open_signature_file));
        editKeystorePassword.setText(customSignKeystorePassword);
        editKeystorePassword.setSingleLine(true);
        container.addView(editKeystorePassword);

        final android.widget.EditText editKeyPassword = new android.widget.EditText(this);
        editKeyPassword.setHint(getString(R.string.alias_password_access_private_key));
        editKeyPassword.setText(customSignKeyPassword);
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

                customSignFilePath = path;
                customSignAlias = alias;
                customSignKeystorePassword = keystorePassword;
                customSignKeyPassword = keyPassword;
                currentSignType = SIGN_TYPE_CUSTOM;

                saveSignConfig();
                printLog(getString(R.string.custom_signature_saved));
                printLog(getString(R.string.path) + customSignFilePath);
                printLog(getString(R.string.alias_1) + customSignAlias);
                printLog(getString(R.string.current_signature) + getSignDisplayName(currentSignType));

                dialog.dismiss();
            }
        });
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
                        customSignFilePath = realPath;
                        printLog(getString(R.string.signature_file_path) + realPath);
                    } else {
                        try {
                            customSignFilePath = copySignFileToCache(signUri);
                            printLog(getString(R.string.signature_file_cached) + customSignFilePath);
                        } catch (Exception e) {
                            printLog(getString(R.string.failed_to_cache_signature_file) + getFullStackTrace(e));
                            return;
                        }
                    }
                } else {
                    try {
                        customSignFilePath = copySignFileToCache(signUri);
                        printLog(getString(R.string.signature_file_cached) + customSignFilePath);
                    } catch (Exception e) {
                        printLog(getString(R.string.failed_to_cache_signature_file) + getFullStackTrace(e));
                        return;
                    }
                }

                showCustomSignInputDialog(customSignFilePath);
            }
            return;
        }

        if (requestCode == REQUEST_CODE_SELECT_APK && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                final Uri apkUri = data.getData();
                resolveOriginalFileInfo(apkUri);

                String detectedPkg = null;
                try {
                    detectedPkg = detectPackageNameFromApkUri(apkUri);
                } catch (Exception e) {
                    printLog(getString(R.string.failed_to_automatically_read_the_current_package_n) + getFullStackTrace(e));
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
                    processApk(apkUri, false, currentDetectedPackageName, currentDetectedPackageName);
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
            printLog(getString(R.string.failed_to_open_file_manager) + getFullStackTrace(e));
        }
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

                if (!isValidPackageName(newPkg)) {
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
                processApk(apkUri, true, sourcePkg, newPkg);
            }
        });
    }

    private boolean isValidPackageName(String pkg) {
        if (pkg == null) return false;
        pkg = pkg.trim();

        if (pkg.length() == 0) return false;
        if (pkg.startsWith(".") || pkg.endsWith(".")) return false;
        if (pkg.contains("..")) return false;
        if (pkg.indexOf('.') == -1) return false;

        String[] parts = pkg.split("\\.");
        if (parts.length < 2) return false;

        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() == 0) return false;

            char first = p.charAt(0);
            if (!Character.isLetter(first) && first != '_') {
                return false;
            }

            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (!Character.isLetterOrDigit(c) && c != '_') {
                    return false;
                }
            }
        }

        return true;
    }

    private String detectPackageNameFromApkUri(Uri uri) throws Exception {
        File cacheDir = getExternalCacheDir();
        if (cacheDir == null) cacheDir = getCacheDir();

        File tempApk = new File(cacheDir, "detect_pkg_temp.apk");
        if (tempApk.exists()) {
            tempApk.delete();
        }

        copyUriToFile(uri, tempApk);

        String pkg = null;
        try {
            pkg = detectPackageNameFromApkFile(tempApk);
        } finally {
            if (tempApk.exists()) {
                tempApk.delete();
            }
        }

        return pkg;
    }

    private String detectPackageNameFromApkFile(File apkFile) throws Exception {
        if (!apkFile.exists()) {
            throw new Exception(getString(R.string.detection_apk_temporary_file_does_not_exist) + apkFile.getAbsolutePath());
        }

        try {
            PackageInfo info = getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null && info.packageName != null && info.packageName.length() > 0) {
                return info.packageName;
            }
        } catch (Exception e) {
            updateLogSafe(getString(R.string.detection_packagearchiveinfo_reading_failed) + getFullStackTrace(e) + getString(R.string.try_an_alternative));
        }

        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apkFile);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith("package-info.xml")) {
                    InputStream is = zipFile.getInputStream(entry);
                    byte[] data = readAllBytes(is);
                    is.close();
                    try {
                        String pkg = extractPackageNameFromPackageInfoXml(data);
                        if (pkg != null && pkg.length() > 0) {
                            return pkg;
                        }
                    } catch (Exception e) {
                        updateLogSafe(getString(R.string.detection_packageinfoxml_parsing_failed) + getFullStackTrace(e));
                    }
                }
            }

            ZipEntry manifestEntry = zipFile.getEntry("AndroidManifest.xml");
            if (manifestEntry != null) {
                InputStream is = zipFile.getInputStream(manifestEntry);
                byte[] data = readAllBytes(is);
                is.close();
                try {
                    String pkg = extractPackageNameFromBinaryManifest(data);
                    if (pkg != null && pkg.length() > 0) {
                        return pkg;
                    }
                } catch (Exception e) {
                    updateLogSafe(getString(R.string.detection_binary_manifest_parsing_failed) + getFullStackTrace(e));
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(getString(R.string.detection_unable_to_open_apk_as_zip), e);
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Exception ignore) {
                }
            }
        }

        throw new Exception(getString(R.string.detection_the_package_name_cannot_be_read_in_all_w));
    }

    private String extractPackageNameFromPackageInfoXml(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            throw new Exception(getString(R.string.packageinfo_parsing_the_data_is_empty));
        }

        String text;
        try {
            text = new String(data, "UTF-8");
        } catch (Exception e) {
            throw new Exception(getString(R.string.packageinfo_parsing_utf8_decoding_failed) + e.getMessage());
        }

        int tagIndex = text.indexOf("<package");
        if (tagIndex == -1) {
            throw new Exception(getString(R.string.packageinfo_parsing_package_tag_not_found_nthe_fir)
                    + text.substring(0, Math.min(text.length(), 100)));
        }

        int nameIndex = text.indexOf("name=", tagIndex);
        if (nameIndex == -1) {
            throw new Exception(getString(R.string.packageinfo_parsing_the_name_attribute_was_not_fou));
        }

        int quoteIndex = nameIndex + 5;
        while (quoteIndex < text.length() && Character.isWhitespace(text.charAt(quoteIndex))) {
            quoteIndex++;
        }
        if (quoteIndex >= text.length()) {
            throw new Exception(getString(R.string.packageinfo_analysis_there_is_no_quotation_mark_af));
        }

        char quote = text.charAt(quoteIndex);
        if (quote != '"' && quote != '\'') {
            throw new Exception(getString(R.string.packageinfo_parsing_quotation_marks_are_expected_a) + quote + "'");
        }

        int end = text.indexOf(quote, quoteIndex + 1);
        if (end == -1) {
            throw new Exception(getString(R.string.packageinfo_analysis_the_attribute_value_quotation));
        }

        String result = text.substring(quoteIndex + 1, end).trim();
        if (result.length() == 0) {
            throw new Exception(getString(R.string.packageinfo_analysis_the_extracted_package_name_is));
        }

        return result;
    }

    private String extractPackageNameFromBinaryManifest(byte[] manifestData) throws Exception {
        if (manifestData == null) {
            throw new Exception(getString(R.string.manifest_parsing_the_data_is_null));
        }

        if (manifestData.length < 8) {
            throw new Exception(getString(R.string.manifest_analysis_data_is_too_small) + manifestData.length + getString(R.string.bytes_cannot_be_valid_axml));
        }

        if (readU16(manifestData, 0) != RES_XML_TYPE) {
            throw new Exception(getString(R.string.manifest_analysis_header_type_code_does_not_match_)
                    + Integer.toHexString(readU16(manifestData, 0)) + getString(R.string.expecting_0x0003));
        }

        List<String> stringPool = null;
        int xmlSize = readU32(manifestData, 4);
        int offset = readU16(manifestData, 2);
        if (offset <= 0) offset = 8;

        int chunkCount = 0;

        while (offset + 8 <= manifestData.length && offset < xmlSize) {
            int chunkType = readU16(manifestData, offset);
            int chunkSize = readU32(manifestData, offset + 4);
            chunkCount++;

            if (chunkSize <= 0) {
                throw new Exception(getString(R.string.manifest_analysis_no) + chunkCount + getString(R.string.abnormal_chunk_size) + chunkSize
                        + getString(R.string.offset_0x) + Integer.toHexString(offset) + ")");
            }

            if (chunkType == RES_STRING_POOL_TYPE && stringPool == null) {
                try {
                    stringPool = readStringPoolStrings(manifestData, offset);
                } catch (Exception e) {
                    throw new Exception(getString(R.string.manifest_parsing_failed_to_read_string_pool_offset)
                            + Integer.toHexString(offset) + "): " + e.getMessage());
                }
            } else if (chunkType == RES_XML_START_ELEMENT_TYPE) {
                if (stringPool == null) {
                    throw new Exception(getString(R.string.manifest_analysis_start_element_was_encountered_bu));
                }

                if (offset + 28 > manifestData.length) {
                    throw new Exception(getString(R.string.manifest_analysis_start_element_chunk_insufficient)
                            + Integer.toHexString(offset));
                }

                int nameIndex = readU32(manifestData, offset + 20);
                String tagName = getStringSafe(stringPool, nameIndex);
                if (tagName == null) {
                    throw new Exception(getString(R.string.manifest_analysis_tag_name_index) + nameIndex + getString(R.string.string_pool_range_exceeded_size)
                            + stringPool.size() + ")");
                }

                if ("manifest".equals(tagName)) {
                    int attributeStart = readU16(manifestData, offset + 24);
                    int attributeSize = readU16(manifestData, offset + 26);
                    int attributeCount = readU16(manifestData, offset + 28);

                    if (attributeCount == 0) {
                        throw new Exception(getString(R.string.manifest_analysis_the_number_of_manifest_tag_attri));
                    }

                    int attrBase = offset + attributeStart;
                    int i;

                    for (i = 0; i < attributeCount; i++) {
                        int attrOffset = attrBase + i * attributeSize;

                        if (attrOffset + 20 > manifestData.length) {
                            throw new Exception(getString(R.string.manifest_analysis_attributes) + i + getString(R.string.data_out_of_bounds));
                        }

                        int attrNameIndex = readU32(manifestData, attrOffset + 4);
                        int rawValueIndex = readU32(manifestData, attrOffset + 8);
                        int typedValueType = manifestData[attrOffset + 15] & 0xFF;
                        int typedValueData = readU32(manifestData, attrOffset + 16);

                        String attrName = getStringSafe(stringPool, attrNameIndex);
                        if (attrName == null) {
                            continue;
                        }

                        if ("package".equals(attrName)) {
                            if (rawValueIndex != -1) {
                                String pkg = getStringSafe(stringPool, rawValueIndex);
                                if (pkg != null && pkg.length() > 0) {
                                    return pkg;
                                }
                            }
                            if (typedValueType == 0x03) {
                                String pkg = getStringSafe(stringPool, typedValueData);
                                if (pkg != null && pkg.length() > 0) {
                                    return pkg;
                                }
                            }
                            throw new Exception(getString(R.string.manifest_parsing_the_package_attribute_was_found_b)
                                    + getString(R.string.rawvalueindex) + rawValueIndex + "\n"
                                    + getString(R.string.typedvaluetype_0x) + Integer.toHexString(typedValueType) + "\n"
                                    + getString(R.string.typedvaluedata) + typedValueData);
                        }
                    }

                    throw new Exception(getString(R.string.manifest_parsing_package_attribute_not_found_in_ma)
                            + getString(R.string.total_number_of_properties) + attributeCount);
                }
            }

            offset += chunkSize;
        }

        throw new Exception(getString(R.string.manifest_parsing_manifest_tag_not_found_n)
                + getString(R.string.file_size) + manifestData.length + getString(R.string.bytesn)
                + getString(R.string.traverse_the_number_of_chunks) + chunkCount);
    }

    private void processApk(final Uri uri, final boolean isCloneMode, final String oldPkg, final String targetPkg) {
        setIndeterminateModeSafe(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File cacheDir = getExternalCacheDir();
                    if (cacheDir == null) cacheDir = getCacheDir();

                    final File tempApk = new File(cacheDir, "source_temp.apk");
                    final File moddedApk = new File(cacheDir, "modded_temp.apk");
                    final File signedApk = new File(cacheDir, "signed_temp.apk");

                    if (isCloneMode) {
                        updateLogSafe(getString(R.string.current_mode_coexistence_mode));
                        updateLogSafe(getString(R.string.current_package_name_1) + oldPkg);
                        updateLogSafe(getString(R.string.target_package_name) + targetPkg);
                    } else {
                        updateLogSafe(getString(R.string.current_mode_normal_mode));
                        updateLogSafe(getString(R.string.current_package_name) + oldPkg);
                    }

                    updateLogSafe(getString(R.string.use_signature) + getSignDisplayName(currentSignType));

                    updateLogSafe(getString(R.string.copying_to_temporary_directory));
                    copyUriToFile(uri, tempApk);

                    updateLogSafe(getString(R.string.start_unpacking_and_repacking_this_will_take_some_));
                    injectFileIntoApk(tempApk, moddedApk, oldPkg, targetPkg, isCloneMode);

                    updateLogSafe(getString(R.string.modification_completed_signing_in_progress));
                    setIndeterminateModeSafe(true);
                    signApk(moddedApk, signedApk);

                    String newFileName;
                    if (isCloneMode) {
                        newFileName = originalFileName.replace(".apk", "_Clone_Signed.apk");
                    } else {
                        newFileName = originalFileName.replace(".apk", "_Signed.apk");
                    }

                    File finalOutputFile = new File(originalDirectory, newFileName);

                    if (finalOutputFile.getParentFile() == null
                            || !finalOutputFile.getParentFile().exists()
                            || !finalOutputFile.getParentFile().canWrite()) {
                        updateLogSafe(getString(R.string.warning_unable_to_write_to_original_directory_will));
                        finalOutputFile = new File(Environment.getExternalStorageDirectory() + "/Download", newFileName);
                    }

                    updateLogSafe(getString(R.string.signature_completed_exporting_file));
                    copyFile(signedApk, finalOutputFile);

                    tempApk.delete();
                    moddedApk.delete();
                    signedApk.delete();

                    updateProgressSafe(100, 100);
                    setIndeterminateModeSafe(false);

                    if (isCloneMode) {
                        updateLogSafe(getString(R.string.coexistence_processing_completed));
                        updateLogSafe(getString(R.string.the_package_name_has_been_changed_from) + oldPkg + getString(R.string.automatically_modified_to) + targetPkg + "]");
                    } else {
                        updateLogSafe(getString(R.string.ordinary_processing_completed));
                    }

                    updateLogSafe(getString(R.string.the_final_file_has_been_saved_ton) + finalOutputFile.getAbsolutePath());

                } catch (final Exception e) {
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
            }
        }).start();
    }

    private void injectFileIntoApk(File sourceFile, File destFile, String oldPkg, String targetPkg, boolean needClone)
            throws Exception {
        ZipFile zipFile = null;
        ZipOutputStream zos = null;

        try {
            zipFile = new ZipFile(sourceFile);

            Set<String> existingDexNames = new HashSet<String>();
            Enumeration<? extends ZipEntry> scanEntries = zipFile.entries();
            while (scanEntries.hasMoreElements()) {
                String name = scanEntries.nextElement().getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    existingDexNames.add(name);
                }
            }

            int injectDexIndex = 1;
            String injectDexName = "classes.dex";
            while (existingDexNames.contains(injectDexName)) {
                injectDexIndex++;
                injectDexName = "classes" + injectDexIndex + ".dex";
            }

            updateLogSafe(getString(R.string.the_original_package_already_has_a_dex_file) + existingDexNames.size() + getString(R.string.indivual));
            updateLogSafe(getString(R.string.inject_dex_number_assignment));
            updateLogSafe("  inject_classes.dex → " + injectDexName);

            Set<String> overridePaths = new HashSet<String>();
            overridePaths.add("lib/arm64-v8a/libForceCloseOreUI.so");
            overridePaths.add("yinghuaji");
            overridePaths.add(injectDexName);

            zos = new ZipOutputStream(new FileOutputStream(destFile));

            int totalEntries = zipFile.size();
            int currentEntry = 0;

            boolean manifestPatched = false;
            boolean arscPatched = false;
            boolean packageInfoFound = false;

            setIndeterminateModeSafe(false);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                currentEntry++;

                if (currentEntry % 50 == 0) {
                    updateProgressSafe(currentEntry, totalEntries);
                }

                String entryName = entry.getName();

                if (entryName.startsWith("META-INF/") || overridePaths.contains(entryName)) {
                    continue;
                }

                InputStream is = zipFile.getInputStream(entry);
                if (is == null) {
                    continue;
                }

                if (needClone && "AndroidManifest.xml".equals(entryName)) {
                    updateLogSafe(getString(R.string.modifying_androidmanifestxml_package_name));
                    byte[] originalBytes = readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = patchBinaryManifest(originalBytes, oldPkg, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    manifestPatched = true;
                    continue;
                }

                if (needClone && "resources.arsc".equals(entryName)) {
                    updateLogSafe(getString(R.string.modifying_resourcesarsc));
                    byte[] originalBytes = readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = patchResourcesArsc(originalBytes, oldPkg, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    arscPatched = true;
                    continue;
                }

                if (needClone && entryName.endsWith("package-info.xml")) {
                    updateLogSafe(getString(R.string.modifying) + entryName + "...");
                    byte[] originalBytes = readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = patchPackageInfoXml(originalBytes, oldPkg, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    packageInfoFound = true;
                    continue;
                }

                if (entryName.startsWith("classes") && entryName.endsWith(".dex")) {
                    File cacheDir = getExternalCacheDir();
                    if (cacheDir == null) cacheDir = getCacheDir();

                    File tempDexIn = new File(cacheDir, "temp_in.dex");
                    File tempDexOut = new File(cacheDir, "temp_out.dex");

                    FileOutputStream fosDex = new FileOutputStream(tempDexIn);
                    copyStreamToStream(is, fosDex);
                    fosDex.close();
                    is.close();

                    boolean injected = false;
                    try {
                        injected = processAndCheckDex(tempDexIn, tempDexOut, entryName);
                    } catch (Exception e) {
                        updateLogSafe(getString(R.string.dex_processing_failed) + entryName + "): " + getFullStackTrace(e));
                    }

                    if (injected) {
                        ZipEntry newEntry = new ZipEntry(entryName);
                        zos.putNextEntry(newEntry);
                        FileInputStream fisDexOut = new FileInputStream(tempDexOut);
                        copyStreamToStream(fisDexOut, zos);
                        fisDexOut.close();
                        zos.closeEntry();
                        tempDexOut.delete();
                    } else {
                        ZipEntry newEntry = new ZipEntry(entryName);
                        copyZipEntryAttributes(entry, newEntry);
                        zos.putNextEntry(newEntry);
                        FileInputStream fisDexIn = new FileInputStream(tempDexIn);
                        copyStreamToStream(fisDexIn, zos);
                        fisDexIn.close();
                        zos.closeEntry();
                    }

                    tempDexIn.delete();
                } else {
                    ZipEntry newEntry = new ZipEntry(entryName);
                    copyZipEntryAttributes(entry, newEntry);
                    zos.putNextEntry(newEntry);
                    copyStreamToStream(is, zos);
                    is.close();
                    zos.closeEntry();
                }
            }

            if (needClone) {
                if (!manifestPatched) {
                    throw new Exception(getString(R.string.coexistence_androidmanifestxml_was_not_found_in_th)
                            + getString(R.string.total_number_of_entries_in_apk) + totalEntries + "\n"
                            + getString(R.string.possible_reasons_the_apk_structure_is_damaged_or_t));
                }
                if (!arscPatched) {
                    throw new Exception(getString(R.string.coexistence_resourcesarsc_was_not_found_in_the_apk)
                            + getString(R.string.total_number_of_entries_in_apk) + totalEntries + "\n"
                            + getString(R.string.possible_reasons_the_apk_is_a_split_apk_no_indepen));
                }
                if (!packageInfoFound) {
                    updateLogSafe(getString(R.string.packageinfoxml_not_found_automatically_skipped));
                }
            }

            updateLogSafe(getString(R.string.injecting_additional_core_components));
            injectAssetToZip(zos, "libForceCloseOreUI.so", "lib/arm64-v8a/libForceCloseOreUI.so");
            injectAssetToZip(zos, "yinghuaji", "yinghuaji");
            injectAssetToZip(zos, "inject_classes.dex", injectDexName);

            updateProgressSafe(totalEntries, totalEntries);

        } finally {
            if (zos != null) {
                try {
                    zos.close();
                } catch (Exception ignore) {
                }
            }
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private boolean processAndCheckDex(File tempIn, File tempOut, String entryName)
            throws Exception {
        boolean isTargetDex = false;
        DexFile df = DexFileFactory.loadDexFile(tempIn, Opcodes.getDefault());

        for (ClassDef cd : df.getClasses()) {
            if (cd.getType().equals("Lcom/mojang/minecraftpe/MainActivity;")) {
                isTargetDex = true;
                break;
            }
        }

        if (!isTargetDex) {
            return false;
        }

        updateLogSafe(getString(R.string.in) + entryName + getString(R.string.mainactivity_is_found_injecting_and_correcting_the));

        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return new MethodRewriter(rewriters) {
                    @Override
                    public Method rewrite(Method method) {
                        if (method.getDefiningClass().equals("Lcom/mojang/minecraftpe/MainActivity;")
                                && method.getName().equals("onCreate")) {

                            MethodImplementation origImpl = method.getImplementation();
                            if (origImpl != null) {
                                List<Instruction> instructions = new ArrayList<Instruction>();
                                for (Instruction instr : origImpl.getInstructions()) {
                                    instructions.add(instr);
                                }

                                int regCount = origImpl.getRegisterCount();
                                int paramRegs = 1;
                                for (CharSequence type : method.getParameterTypes()) {
                                    if ("J".equals(type.toString()) || "D".equals(type.toString())) {
                                        paramRegs += 2;
                                    } else {
                                        paramRegs += 1;
                                    }
                                }
                                int p0Reg = regCount - paramRegs;

                                ImmutableMethodReference methodRef = new ImmutableMethodReference(
                                "Lcom/mojang/minecraftpe/SoLoader;",
                                "loadLibrary",
                                Collections.singletonList("Landroid/content/Context;"),
                                "V"
                                );

                                final int CODE_UNIT_SHIFT = 3;

                                Instruction newInstr = new ImmutableInstruction35c(
                                Opcode.INVOKE_STATIC,
                                1, p0Reg, 0, 0, 0, 0, methodRef
                                );

                                instructions.add(0, newInstr);

                                List<
                                        org.jf.dexlib2.iface.TryBlock<
                                                ? extends
                                                        org.jf.dexlib2.iface.ExceptionHandler>> newTryBlocks = new ArrayList<
                                        org.jf.dexlib2.iface.TryBlock<
                                                ? extends org.jf.dexlib2.iface.ExceptionHandler>>();

                                for (org.jf.dexlib2.iface.TryBlock<
                                        ? extends
                                                org.jf.dexlib2.iface.ExceptionHandler> tryBlock : origImpl.getTryBlocks()) {
                                    List<
                                            org.jf.dexlib2.iface.ExceptionHandler> newHandlers = new ArrayList<
                                            org.jf.dexlib2.iface.ExceptionHandler>();

                                    for (org.jf.dexlib2.iface.ExceptionHandler handler : tryBlock.getExceptionHandlers()) {
                                        newHandlers.add(new org.jf.dexlib2.immutable.ImmutableExceptionHandler(
                                        handler.getExceptionType(),
                                        handler.getHandlerCodeAddress() + CODE_UNIT_SHIFT
                                        ));
                                    }

                                    newTryBlocks.add(new org.jf.dexlib2.immutable.ImmutableTryBlock(
                                    tryBlock.getStartCodeAddress() + CODE_UNIT_SHIFT,
                                    tryBlock.getCodeUnitCount(),
                                    newHandlers
                                    ));
                                }

                                List<
                                        org.jf.dexlib2.iface.debug.DebugItem> emptyDebugItems = new ArrayList<
                                        org.jf.dexlib2.iface.debug.DebugItem>();

                                MethodImplementation newImpl = new ImmutableMethodImplementation(
                                regCount,
                                instructions,
                                newTryBlocks,
                                emptyDebugItems
                                );

                                return new ImmutableMethod(
                                method.getDefiningClass(),
                                method.getName(),
                                method.getParameters(),
                                method.getReturnType(),
                                method.getAccessFlags(),
                                method.getAnnotations(),
                                method.getHiddenApiRestrictions(),
                                newImpl
                                );
                            }
                        }
                        return super.rewrite(method);
                    }
                };
            }
        });

        DexFile rewrittenDex = rewriter.getDexFileRewriter().rewrite(df);
        DexFileFactory.writeDexFile(tempOut.getAbsolutePath(), rewrittenDex);
        return true;
    }

    private void signApk(File inputApk, File outputApk) throws Exception {
        InputStream is = null;
        String ksType = "BKS";
        String alias = null;
        String keystorePassword = null;
        String keyPassword = null;
        KeyStore ks = null;

        try {
            if (currentSignType == SIGN_TYPE_MT) {
                is = getAssets().open("debug-mt.bks");
                alias = "mt";
                keystorePassword = "android";
                keyPassword = "android";
                ksType = "BKS";
            } else if (currentSignType == SIGN_TYPE_ZIHAO_IL) {
                is = getAssets().open("minecraft.bks");
                alias = "minecraft";
                keystorePassword = "minecraft";
                keyPassword = "minecraft";
                ksType = "BKS";
            } else if (currentSignType == SIGN_TYPE_CUSTOM) {
                if (customSignFilePath == null || customSignFilePath.length() == 0) {
                    throw new Exception(getString(R.string.signature_custom_signature_file_path_is_not_set_pl));
                }

                File signFile = new File(customSignFilePath);
                if (!signFile.exists()) {
                    throw new Exception(getString(R.string.signature_custom_signature_file_does_not_exist) + customSignFilePath + getString(R.string.nplease_check_whether_the_path_is_correct_or_resel));
                }

                if (signFile.length() == 0) {
                    throw new Exception(getString(R.string.signature_signature_file_size_is_0_bytes) + customSignFilePath + getString(R.string.nthe_file_may_be_corrupted_or_copied_incorrectly));
                }

                try {
                    is = new FileInputStream(signFile);
                } catch (Exception e) {
                    throw new RuntimeException(getString(R.string.signature_unable_to_read_signature_file) + customSignFilePath + getString(R.string.nreason) + e);
                }

                String fileName = signFile.getName();
                ksType = guessKeystoreType(fileName);
                alias = customSignAlias;
                keystorePassword = customSignKeystorePassword;
                keyPassword = customSignKeyPassword;
            } else {
                throw new Exception(getString(R.string.signature_unknown_signature_type) + currentSignType);
            }

            try {
                ks = KeyStore.getInstance(ksType);
                ks.load(is, keystorePassword.toCharArray());
            } catch (Exception e) {
                throw new RuntimeException(getString(R.string.signature_unable_to_load_keystore_type) + ksType + ")\n"
                        + getString(R.string.possible_reasons_the_keystore_password_is_wrong_or)
                        + getString(R.string.detailed) + e);
            }

            KeyStore.PasswordProtection keyPasswordProtection = new KeyStore.PasswordProtection(keyPassword.toCharArray());

            KeyStore.PrivateKeyEntry keyEntry = null;
            try {
                keyEntry = (KeyStore.PrivateKeyEntry) ks.getEntry(alias, keyPasswordProtection);
            } catch (Exception e) {
                throw new RuntimeException(getString(R.string.signature_unable_to_get_private_key_entry_alias) + alias + ")\n"
                        + getString(R.string.possible_reasons_the_alias_password_is_wrong_or_th)
                        + getString(R.string.detailed) + e);
            }

            if (keyEntry == null) {
                throw new RuntimeException(getString(R.string.signature_alias_not_found_in_signature_file) + alias + "\n"
                        + getString(R.string.please_make_sure_the_alias_is_spelled_correctly_or));
            }

            PrivateKey privateKey = keyEntry.getPrivateKey();
            X509Certificate cert = (X509Certificate) keyEntry.getCertificate();

            List<X509Certificate> certs = new ArrayList<X509Certificate>();
            certs.add(cert);

            ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(alias, privateKey, certs).build();
            List<ApkSigner.SignerConfig> signerConfigs = new ArrayList<ApkSigner.SignerConfig>();
            signerConfigs.add(signerConfig);

            try {
                ApkSigner signer = new ApkSigner.Builder(signerConfigs)
                        .setInputApk(inputApk)
                        .setOutputApk(outputApk)
                        .setV1SigningEnabled(true)
                        .setV2SigningEnabled(true)
                        .setMinSdkVersion(26)
                        .build();

                signer.sign();
            } catch (Exception e) {
                throw new RuntimeException(getString(R.string.signature_apk_signing_process_failedndetails) + e);
            }

        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private String guessKeystoreType(String fileName) {
        return "BKS";
    }

    private byte[] patchBinaryManifest(byte[] manifestData, String oldPkg, String newPkg)
            throws Exception {
        if (manifestData == null || manifestData.length < 8) {
            throw new Exception(getString(R.string.manifest_androidmanifestxml_data_is_empty_or_too_s));
        }

        if (newPkg.length() > oldPkg.length()) {
            throw new Exception(getString(R.string.manifest_coexisting_package_name_length) + newPkg.length() + getString(R.string.exceeds_the_length_of_the_original_package_name)
                    + oldPkg.length() + getString(R.string.inplace_modification_is_not_allowed_to_be_longer_n)
                    + getString(R.string.old_package_name) + oldPkg + getString(R.string.nnew_package_name) + newPkg);
        }

        byte[] result = manifestData.clone();

        if (readU16(result, 0) != RES_XML_TYPE) {
            throw new Exception(getString(R.string.manifest_androidmanifestxml_is_not_valid_binary_ax)
                    + Integer.toHexString(readU16(result, 0)) + getString(R.string.expected_0x0003));
        }

        Map<String, String> replacements = new LinkedHashMap<String, String>();
        replacements.put(oldPkg, newPkg);
        replacements.put(oldPkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                newPkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        replacements.put(oldPkg + ".fileprovider", newPkg + ".fileprovider");
        replacements.put(oldPkg + ".firebaseinitprovider", newPkg + ".firebaseinitprovider");
        replacements.put(oldPkg + ".androidx-startup", newPkg + ".androidx-startup");

        int xmlSize = readU32(result, 4);
        int offset = readU16(result, 2);
        if (offset <= 0) offset = 8;

        int replacedCount = -1;

        while (offset + 8 <= result.length && offset < xmlSize) {
            int chunkType = readU16(result, offset);
            int chunkSize = readU32(result, offset + 4);

            if (chunkSize <= 0) {
                break;
            }

            if (chunkType == RES_STRING_POOL_TYPE) {
                replacedCount = patchStringPoolChunk(result, offset, replacements);
                break;
            }

            offset += chunkSize;
        }

        if (replacedCount < 0) {
            throw new Exception(getString(R.string.manifest_the_string_pool_stringpool_chunk_was_not_));
        }

        if (replacedCount == 0) {
            throw new Exception(getString(R.string.manifest_no_package_name_string_that_needs_to_be_r)
                    + getString(R.string.looking_for) + oldPkg + getString(R.string.and_its_derived_strings_n)
                    + getString(R.string.possible_reasons_the_package_name_of_the_apk_is_in));
        }

        return result;
    }

    private byte[] patchResourcesArsc(byte[] arscData, String oldPkg, String newPkg)
            throws Exception {
        if (arscData == null || arscData.length < 8) {
            throw new Exception(getString(R.string.arsc_the_resourcesarsc_data_is_empty_or_too_small_));
        }

        if (newPkg.length() > oldPkg.length()) {
            throw new Exception(getString(R.string.arsc_coexistence_package_name_length) + newPkg.length() + getString(R.string.exceeds_the_length_of_the_original_package_name)
                    + oldPkg.length() + getString(R.string.inplace_modification_is_not_allowed_to_be_longer));
        }

        byte[] result = arscData.clone();

        if (readU16(result, 0) != RES_TABLE_TYPE) {
            throw new Exception(getString(R.string.arsc_resourcesarsc_is_not_a_valid_resource_table_f)
                    + Integer.toHexString(readU16(result, 0)) + getString(R.string.expected_0x0002));
        }

        int tableSize = readU32(result, 4);
        int headerSize = readU16(result, 2);
        int offset = headerSize;
        boolean found = false;

        while (offset + 8 <= result.length && offset < tableSize) {
            int chunkType = readU16(result, offset);
            int chunkSize = readU32(result, offset + 4);

            if (chunkSize <= 0) {
                break;
            }

            if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                String packageName = readFixedUtf16String(result, offset + 12, 128);
                if (oldPkg.equals(packageName)) {
                    writeFixedUtf16String(result, offset + 12, 128, newPkg);
                    found = true;
                }
            }

            offset += chunkSize;
        }

        if (!found) {
            throw new Exception(getString(R.string.arsc_package_name_not_found_in_resourcesarsc) + oldPkg + getString(R.string.package_chunkn)
                    + getString(R.string.possible_reasons_the_package_name_in_arsc_is_incon));
        }

        return result;
    }

    private byte[] patchPackageInfoXml(byte[] data, String oldPkg, String newPkg) throws Exception {
        if (data == null || data.length == 0) {
            throw new Exception(getString(R.string.packageinfo_packageinfoxml_data_is_empty_and_canno));
        }

        String text;
        try {
            text = new String(data, "UTF-8");
        } catch (Exception e) {
            throw new Exception(getString(R.string.packageinfo_unable_to_decode_packageinfoxml_in_utf) + e.getMessage());
        }

        if (!text.contains(oldPkg)) {
            throw new Exception(getString(R.string.packageinfo_old_package_name_not_found_in_packagei) + oldPkg + "\n"
                    + getString(R.string.file_content_length) + text.length() + getString(R.string.charactern)
                    + getString(R.string.possible_reasons_the_file_has_been_modified_or_the));
        }

        text = text.replace(oldPkg, newPkg);

        return text.getBytes("UTF-8");
    }

    private int patchStringPoolChunk(byte[] data, int chunkOffset, Map<String, String> replacements)
            throws Exception {
        int stringCount = readU32(data, chunkOffset + 8);
        int flags = readU32(data, chunkOffset + 16);
        int stringsStart = readU32(data, chunkOffset + 20);
        int stylesStart = readU32(data, chunkOffset + 24);
        int headerSize = readU16(data, chunkOffset + 2);
        int chunkSize = readU32(data, chunkOffset + 4);

        boolean isUtf8 = (flags & UTF8_FLAG) != 0;

        if (stringCount <= 0) {
            throw new Exception(getString(R.string.stringpool_the_number_of_strings_is_0_and_cannot_b));
        }

        int[] stringOffsets = new int[stringCount];
        int offsetsBase = chunkOffset + headerSize;
        int i;

        for (i = 0; i < stringCount; i++) {
            stringOffsets[i] = readU32(data, offsetsBase + i * 4);
        }

        int stringDataStart = chunkOffset + stringsStart;
        int stringDataEnd = (stylesStart != 0) ? (chunkOffset + stylesStart) : (chunkOffset + chunkSize);

        int replaceCount = 0;
        int failCount = 0;
        StringBuilder failDetail = new StringBuilder();

        for (i = 0; i < stringCount; i++) {
            int relOffset = stringOffsets[i];
            int absOffset = stringDataStart + relOffset;

            if (absOffset < 0 || absOffset >= data.length) {
                continue;
            }

            String currentValue;
            try {
                if (isUtf8) {
                    currentValue = readUtf8String(data, absOffset);
                } else {
                    currentValue = readUtf16String(data, absOffset);
                }
            } catch (Exception e) {
                failCount++;
                if (failDetail.length() < 200) {
                    failDetail.append(getString(R.string.text_71) + i + getString(R.string.read_failed) + e.getMessage() + "; ");
                }
                continue;
            }

            String newValue = replacements.get(currentValue);
            if (newValue == null || currentValue.equals(newValue)) {
                continue;
            }

            int available = findAvailableStringSlot(stringOffsets, relOffset, stringDataEnd - stringDataStart);
            boolean success;

            if (isUtf8) {
                success = writeUtf8StringInPlace(data, absOffset, available, newValue);
            } else {
                success = writeUtf16StringInPlace(data, absOffset, available, newValue);
            }

            if (!success) {
                throw new Exception(getString(R.string.stringpool_replacement_failed_insufficient_spacen)
                        + getString(R.string.original_text) + currentValue + getString(R.string.length) + currentValue.length() + ")\n"
                        + getString(R.string.target) + newValue + getString(R.string.length) + newValue.length() + ")\n"
                        + getString(R.string.available_slots) + available + getString(R.string.bytesn)
                        + getString(R.string.coding) + (isUtf8 ? "UTF-8" : "UTF-16"));
            }

            replaceCount++;
        }

        if (failCount > 0) {
            throw new Exception(getString(R.string.stringpool_when_reading_a_string_there_is) + failCount + getString(R.string.failedn) + failDetail.toString());
        }

        return replaceCount;
    }

    private List<String> readStringPoolStrings(byte[] data, int chunkOffset) throws Exception {
        int stringCount = readU32(data, chunkOffset + 8);
        int flags = readU32(data, chunkOffset + 16);
        int stringsStart = readU32(data, chunkOffset + 20);
        int headerSize = readU16(data, chunkOffset + 2);

        boolean isUtf8 = (flags & UTF8_FLAG) != 0;

        int[] stringOffsets = new int[stringCount];
        int offsetsBase = chunkOffset + headerSize;
        int i;

        for (i = 0; i < stringCount; i++) {
            stringOffsets[i] = readU32(data, offsetsBase + i * 4);
        }

        int stringDataStart = chunkOffset + stringsStart;
        List<String> list = new ArrayList<String>(stringCount);

        for (i = 0; i < stringCount; i++) {
            int absOffset = stringDataStart + stringOffsets[i];
            String s;
            if (isUtf8) {
                s = readUtf8String(data, absOffset);
            } else {
                s = readUtf16String(data, absOffset);
            }
            list.add(s);
        }

        return list;
    }

    private String getStringSafe(List<String> list, int index) {
        if (list == null) return null;
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }

    private int findAvailableStringSlot(int[] offsets, int currentOffset, int stringDataSize) {
        int nextOffset = stringDataSize;
        int i;

        for (i = 0; i < offsets.length; i++) {
            int off = offsets[i];
            if (off > currentOffset && off < nextOffset) {
                nextOffset = off;
            }
        }

        return nextOffset - currentOffset;
    }

    private String readUtf8String(byte[] data, int offset) throws Exception {
        int[] charLenInfo = decodeLengthUtf8(data, offset);
        int pos = offset + charLenInfo[1];

        int[] byteLenInfo = decodeLengthUtf8(data, pos);
        pos += byteLenInfo[1];

        int byteLen = byteLenInfo[0];
        return new String(data, pos, byteLen, "UTF-8");
    }

    private String readUtf16String(byte[] data, int offset) throws Exception {
        int[] lenInfo = decodeLengthUtf16(data, offset);
        int pos = offset + lenInfo[1];
        int charLen = lenInfo[0];
        return new String(data, pos, charLen * 2, "UTF-16LE");
    }

    private boolean writeUtf8StringInPlace(byte[] data, int offset, int available, String value)
            throws Exception {
        byte[] utf8 = value.getBytes("UTF-8");
        byte[] charLen = encodeLengthUtf8(value.length());
        byte[] byteLen = encodeLengthUtf8(utf8.length);

        int total = charLen.length + byteLen.length + utf8.length + 1;
        if (total > available) {
            return false;
        }

        Arrays.fill(data, offset, offset + available, (byte) 0);

        int pos = offset;
        System.arraycopy(charLen, 0, data, pos, charLen.length);
        pos += charLen.length;

        System.arraycopy(byteLen, 0, data, pos, byteLen.length);
        pos += byteLen.length;

        System.arraycopy(utf8, 0, data, pos, utf8.length);
        pos += utf8.length;

        data[pos] = 0;
        return true;
    }

    private boolean writeUtf16StringInPlace(byte[] data, int offset, int available, String value)
            throws Exception {
        byte[] utf16 = value.getBytes("UTF-16LE");
        byte[] charLen = encodeLengthUtf16(value.length());

        int total = charLen.length + utf16.length + 2;
        if (total > available) {
            return false;
        }

        Arrays.fill(data, offset, offset + available, (byte) 0);

        int pos = offset;
        System.arraycopy(charLen, 0, data, pos, charLen.length);
        pos += charLen.length;

        System.arraycopy(utf16, 0, data, pos, utf16.length);
        pos += utf16.length;

        data[pos] = 0;
        data[pos + 1] = 0;
        return true;
    }

    private int[] decodeLengthUtf8(byte[] data, int offset) {
        int first = data[offset] & 0xFF;
        if ((first & 0x80) == 0) {
            return new int[]{first, 1};
        }

        int second = data[offset + 1] & 0xFF;
        int length = ((first & 0x7F) << 8) | second;
        return new int[]{length, 2};
    }

    private int[] decodeLengthUtf16(byte[] data, int offset) {
        int first = readU16(data, offset);
        if ((first & 0x8000) == 0) {
            return new int[]{first, 2};
        }

        int second = readU16(data, offset + 2);
        int length = ((first & 0x7FFF) << 16) | second;
        return new int[]{length, 4};
    }

    private byte[] encodeLengthUtf8(int length) throws Exception {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        if (length < 0x8000) {
            return new byte[]{
                    (byte) (((length >> 8) & 0x7F) | 0x80),
                    (byte) (length & 0xFF)
            };
        }
        throw new Exception(getString(R.string.utf8_string_length_is_too_large_to_encode));
    }

    private byte[] encodeLengthUtf16(int length) throws Exception {
        if (length < 0x8000) {
            return new byte[]{
                    (byte) (length & 0xFF),
                    (byte) ((length >> 8) & 0xFF)
            };
        }
        throw new Exception(getString(R.string.utf16_string_length_is_too_large_to_encode));
    }

    private String readFixedUtf16String(byte[] data, int offset, int maxChars) throws Exception {
        int i;
        StringBuilder sb = new StringBuilder();

        for (i = 0; i < maxChars; i++) {
            int c = readU16(data, offset + i * 2);
            if (c == 0) {
                break;
            }
            sb.append((char) c);
        }

        return sb.toString();
    }

    private void writeFixedUtf16String(byte[] data, int offset, int maxChars, String value)
            throws Exception {
        if (value.length() > maxChars) {
            throw new Exception(getString(R.string.fixed_utf16_field_running_out_of_space));
        }

        Arrays.fill(data, offset, offset + maxChars * 2, (byte) 0);
        byte[] utf16 = value.getBytes("UTF-16LE");
        System.arraycopy(utf16, 0, data, offset, utf16.length);
    }

    private int readU16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int readU32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private void putModifiedEntry(ZipOutputStream zos, ZipEntry originalEntry, String entryName, byte
                    [] newData)
            throws Exception {
        ZipEntry newEntry = new ZipEntry(entryName);

        if (originalEntry.getMethod() == ZipEntry.STORED) {
            newEntry.setMethod(ZipEntry.STORED);
            newEntry.setSize(newData.length);
            newEntry.setCompressedSize(newData.length);

            CRC32 crc32 = new CRC32();
            crc32.update(newData);
            newEntry.setCrc(crc32.getValue());
        } else {
            newEntry.setMethod(ZipEntry.DEFLATED);
        }

        zos.putNextEntry(newEntry);
        zos.write(newData);
        zos.closeEntry();
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copyStreamToStream(is, bos);
        return bos.toByteArray();
    }

    private void injectAssetToZip(ZipOutputStream zos, String assetName, String zipPath) {
        try {
            InputStream is = getAssets().open(assetName);
            ZipEntry entry = new ZipEntry(zipPath);
            zos.putNextEntry(entry);
            copyStreamToStream(is, zos);
            zos.closeEntry();
            is.close();
            updateLogSafe(getString(R.string.successful_injection) + zipPath);
        } catch (Exception e) {
            updateLogSafe(getString(R.string.injection_failed) + assetName);
            updateLogSafe(getString(R.string.reason) + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void copyStreamToStream(InputStream is, OutputStream os) throws Exception {
        byte[] buffer = new byte[65536];
        int length;
        while ((length = is.read(buffer)) > 0) {
            os.write(buffer, 0, length);
        }
    }

    private void copyUriToFile(Uri uri, File dest) throws Exception {
        if (uri == null) {
            throw new Exception(getString(R.string.io_uri_is_null_file_cannot_be_copied));
        }

        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(uri);
        } catch (Exception e) {
            throw new Exception(getString(R.string.io_unable_to_open_input_stream_for_selected_file) + uri.toString() + "\n"
                    + getString(R.string.reason_1) + e.getMessage() + "\n"
                    + getString(R.string.possible_reasons_the_file_has_been_deleted_moved_o));
        }

        if (is == null) {
            throw new Exception(getString(R.string.io_getcontentresolver_returns_an_empty_stream_and_) + uri.toString());
        }

        try {
            FileOutputStream fos = new FileOutputStream(dest);
            copyStreamToStream(is, fos);
            fos.flush();
            fos.close();
            is.close();
        } catch (Exception e) {
            throw new Exception(getString(R.string.io_error_copying_file_streamn)
                    + getString(R.string.source) + uri.toString() + "\n"
                    + getString(R.string.target_1) + dest.getAbsolutePath() + "\n"
                    + getString(R.string.reason) + e.getMessage());
        }
    }

    private void copyFile(File source, File dest) throws Exception {
        FileInputStream fis = new FileInputStream(source);
        FileOutputStream fos = new FileOutputStream(dest);
        copyStreamToStream(fis, fos);
        fos.flush();
        fos.close();
        fis.close();
    }

    private void updateProgressSafe(final int progress, final int max) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressBar.setMax(max);
                progressBar.setProgress(progress);
                if (max > 0) {
                    int percent = (int) (((float) progress / (float) max) * 100f);
                    tvProgressPercent.setText(percent + "%");
                }
            }
        });
    }

    private void setIndeterminateModeSafe(final boolean isIndeterminate) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressBar.setIndeterminate(isIndeterminate);
                if (isIndeterminate) {
                    tvProgressPercent.setText(getString(R.string.readingcalculating));
                }
            }
        });
    }

    private void updateLogSafe(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                printLog(msg);
            }
        });
    }

    private void printLog(final String msg) {
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

    private void showAboutDialog() {
        android.widget.TextView messageView = new android.widget.TextView(this);

        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        messageView.setPadding(padding, padding, padding, padding);
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

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.about))
                .setView(messageView)
                .setPositiveButton(getString(R.string.sure), null)
                .show();
    }

    private void copyZipEntryAttributes(ZipEntry original, ZipEntry newEntry) {
        newEntry.setMethod(original.getMethod());
        if (original.getMethod() == ZipEntry.STORED) {
            newEntry.setSize(original.getSize());
            newEntry.setCompressedSize(original.getSize());
            newEntry.setCrc(original.getCrc());
        }
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

    private String copySignFileToCache(Uri signUri) throws Exception {
        if (signUri == null) {
            throw new Exception(getString(R.string.signature_cache_uri_is_null_and_the_signature_file));
        }

        File cacheDir = getExternalCacheDir();
        if (cacheDir == null) cacheDir = getCacheDir();

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new Exception(getString(R.string.signature_cache_unable_to_create_cache_directory) + cacheDir.getAbsolutePath());
        }

        File signCacheFile = new File(cacheDir, "custom_sign_file");
        if (signCacheFile.exists()) {
            signCacheFile.delete();
        }

        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(signUri);
        } catch (Exception e) {
            throw new Exception(getString(R.string.signature_cache_unable_to_read_selected_signature_) + signUri.toString() + "\n"
                    + getString(R.string.reason) + e.getMessage());
        }

        if (is == null) {
            throw new Exception(getString(R.string.signature_cache_the_input_stream_is_empty_and_the_) + signUri.toString());
        }

        try {
            FileOutputStream fos = new FileOutputStream(signCacheFile);
            copyStreamToStream(is, fos);
            fos.flush();
            fos.close();
            is.close();
        } catch (Exception e) {
            throw new Exception(getString(R.string.signature_cache_error_copying_signature_file_to_ca)
                    + getString(R.string.target) + signCacheFile.getAbsolutePath() + "\n"
                    + getString(R.string.reason) + e.getMessage());
        }

        if (signCacheFile.length() == 0) {
            throw new Exception(getString(R.string.signature_cache_the_file_size_after_caching_is_0_b));
        }

        return signCacheFile.getAbsolutePath();
    }

    private String getFullStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
