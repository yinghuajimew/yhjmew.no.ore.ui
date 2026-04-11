package yhjmew.no.ore.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Date;
import java.util.Set;

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

    private static final int REQUEST_CODE_PERMISSION_STORAGE = 100;
    private static final int REQUEST_CODE_PERMISSION_ALL_FILES = 101;
    private static final int REQUEST_CODE_SELECT_APK = 102;

    private static final int PICK_MODE_NONE = 0;
    private static final int PICK_MODE_NORMAL = 1;
    private static final int PICK_MODE_CLONE = 2;

    private static final String DEFAULT_PKG_NAME = "com.mojang.minecraftpe";
    private static final String PREF_NAME = "CloneConfig";
    private static final int CLONE_MAX_LENGTH = 22;

    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int UTF8_FLAG = 0x00000100;

    private int currentPickMode = PICK_MODE_NONE;

    private String originalFileName = "minecraft.apk";
    private String originalDirectory = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        btnSelectMc = (Button) findViewById(R.id.btn_select_mc);
        btnMakeClone = (Button) findViewById(R.id.btn_make_clone);
        tvLogOutput = (TextView) findViewById(R.id.tv_log_output);
        scrollViewLog = (ScrollView) findViewById(R.id.scroll_view_log);
        progressBar = (ProgressBar) findViewById(R.id.progress_bar);
        tvProgressPercent = (TextView) findViewById(R.id.tv_progress_percent);

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

        printLog("应用启动成功，等待操作。");
    }

    private boolean checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                printLog("需要 [所有文件访问] 权限，正在跳转设置...");
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
                printLog("需要存储权限，正在申请...");
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
            printLog("普通模式：正在打开文件管理器...");
        } else if (currentPickMode == PICK_MODE_CLONE) {
            printLog("共存模式：正在打开文件管理器...");
        } else {
            printLog("正在打开文件管理器...");
        }

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/vnd.android.package-archive");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_CODE_SELECT_APK);
        } catch (Exception e) {
            printLog("打开文件管理器失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PERMISSION_ALL_FILES) {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                printLog("权限已授予！请再次点击按钮选择文件。");
            }
            return;
        }

        if (requestCode == REQUEST_CODE_SELECT_APK && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                final Uri apkUri = data.getData();
                resolveOriginalFileInfo(apkUri);

                if (currentPickMode == PICK_MODE_NORMAL) {
                    printLog("普通模式已选择文件: " + originalFileName);
                    printLog("准备开始普通处理...");
                    processApk(apkUri, false, DEFAULT_PKG_NAME);
                } else if (currentPickMode == PICK_MODE_CLONE) {
                    printLog("共存模式已选择文件: " + originalFileName);
                    printLog("请继续输入共存包名...");
                    showCloneConfigDialog(apkUri);
                } else {
                    printLog("未识别当前处理模式。");
                }

                currentPickMode = PICK_MODE_NONE;
            }
        }
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

    private void showCloneConfigDialog(final Uri apkUri) {
        final SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedPkg = prefs.getString("clone_pkg_name", DEFAULT_PKG_NAME);

        if (savedPkg == null || savedPkg.length() > CLONE_MAX_LENGTH) {
            savedPkg = DEFAULT_PKG_NAME;
        }

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        android.widget.TextView tvHint = new android.widget.TextView(this);
        tvHint.setText("请输入要生成的共存包名。\n最大长度 22 个字符，且必须符合 Android 包名规则。\n\n注意：共存模式会自动处理 SO、AndroidManifest.xml、resources.arsc 与 package-info.xml。");
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
        editText.setText(savedPkg);
        editText.setSelection(savedPkg.length());
        container.addView(editText);

        int initialLen = savedPkg.length();
        tvCounter.setText("当前字数: " + initialLen + " / " + CLONE_MAX_LENGTH);
        if (initialLen > CLONE_MAX_LENGTH) {
            tvCounter.setTextColor(android.graphics.Color.RED);
        } else {
            tvCounter.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
        }

        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                int len = s.length();
                tvCounter.setText("当前字数: " + len + " / " + CLONE_MAX_LENGTH);
                if (len > CLONE_MAX_LENGTH) {
                    tvCounter.setTextColor(android.graphics.Color.RED);
                } else {
                    tvCounter.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("配置共存包名")
                .setView(container)
                .setPositiveButton("开始", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.show();

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newPkg = editText.getText().toString().trim();

                if (newPkg.length() == 0) {
                    printLog("❌ 包名不能为空。");
                    return;
                }

                if (newPkg.length() > CLONE_MAX_LENGTH) {
                    printLog("❌ 包名过长：最大允许 " + CLONE_MAX_LENGTH + " 个字符。");
                    return;
                }

                if (!isValidPackageName(newPkg)) {
                    printLog("❌ 包名格式不合法，请检查后重新输入。");
                    return;
                }

                if (DEFAULT_PKG_NAME.equals(newPkg)) {
                    printLog("❌ 共存模式不能使用原版包名。");
                    return;
                }

                prefs.edit().putString("clone_pkg_name", newPkg).apply();
                printLog("✅ 共存包名已确认为: " + newPkg);
                dialog.dismiss();
                processApk(apkUri, true, newPkg);
            }
        });
    }

    private boolean isValidPackageName(String pkg) {
        if (pkg == null) return false;
        pkg = pkg.trim();

        if (pkg.length() == 0) return false;
        if (pkg.length() > CLONE_MAX_LENGTH) return false;
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

    private void processApk(final Uri uri, final boolean isCloneMode, final String targetPkg) {
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
                        updateLogSafe("当前模式：共存模式");
                        updateLogSafe("目标共存包名：" + targetPkg);
                    } else {
                        updateLogSafe("当前模式：普通模式");
                    }

                    updateLogSafe("正在复制到临时目录...");
                    copyUriToFile(uri, tempApk);

                    updateLogSafe("开始解包和重打包，这需要一些时间...");
                    injectFileIntoApk(tempApk, moddedApk, targetPkg, isCloneMode);

                    updateLogSafe("修改完成，正在进行签名...");
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
                        updateLogSafe("警告: 无法写入原目录，将保存到 Download 文件夹");
                        finalOutputFile = new File(Environment.getExternalStorageDirectory() + "/Download", newFileName);
                    }

                    updateLogSafe("签名完毕！正在导出文件...");
                    copyFile(signedApk, finalOutputFile);

                    tempApk.delete();
                    moddedApk.delete();
                    signedApk.delete();

                    updateProgressSafe(100, 100);
                    setIndeterminateModeSafe(false);

                    if (isCloneMode) {
                        updateLogSafe("✅ 共存处理完成！");
                        updateLogSafe("共存包名已自动修改为: " + targetPkg);
                    } else {
                        updateLogSafe("✅ 普通处理完成！");
                    }

                    updateLogSafe("最终文件已保存至:\n" + finalOutputFile.getAbsolutePath());

                } catch (final Exception e) {
                    setIndeterminateModeSafe(false);
                    updateLogSafe("❌ 发生错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void injectFileIntoApk(File sourceFile, File destFile, String targetPkg, boolean needClone) throws Exception {
        ZipFile zipFile = null;
        ZipOutputStream zos = null;

        try {
            zipFile = new ZipFile(sourceFile);

            int maxDex = 0;
            Enumeration<? extends ZipEntry> calcEntries = zipFile.entries();
            while (calcEntries.hasMoreElements()) {
                String name = calcEntries.nextElement().getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    if ("classes.dex".equals(name)) {
                        maxDex = Math.max(maxDex, 1);
                    } else {
                        try {
                            int num = Integer.parseInt(name.substring(7, name.length() - 4));
                            maxDex = Math.max(maxDex, num);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            }

            int nextDexIndex = maxDex + 1;
            String newDexName = "classes" + nextDexIndex + ".dex";
            updateLogSafe("原包最大 Dex 序号为 " + maxDex + "，新增文件命名为: " + newDexName);

            Set<String> overridePaths = new HashSet<String>();
            overridePaths.add("lib/arm64-v8a/libForceCloseOreUI.so");
            overridePaths.add("lib/arm64-v8a/libpreloader.so");
            overridePaths.add("yinghuaji");
            overridePaths.add(newDexName);

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
                    updateLogSafe("正在修改 AndroidManifest.xml...");
                    byte[] originalBytes = readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = patchBinaryManifest(originalBytes, DEFAULT_PKG_NAME, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    manifestPatched = true;
                    continue;
                }

                if (needClone && "resources.arsc".equals(entryName)) {
                    updateLogSafe("正在修改 resources.arsc...");
                    byte[] originalBytes = readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = patchResourcesArsc(originalBytes, DEFAULT_PKG_NAME, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    arscPatched = true;
                    continue;
                }

                if (needClone && entryName.endsWith("package-info.xml")) {
                    updateLogSafe("正在修改 " + entryName + "...");
                    byte[] originalBytes = readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = patchPackageInfoXml(originalBytes, DEFAULT_PKG_NAME, targetPkg);
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
                    throw new Exception("未找到 AndroidManifest.xml，无法完成共存修改。");
                }
                if (!arscPatched) {
                    throw new Exception("未找到 resources.arsc，无法完成共存修改。");
                }
                if (!packageInfoFound) {
                    updateLogSafe("未发现 package-info.xml，已自动跳过。");
                }
            }

            if (needClone) {
                updateLogSafe("正在执行 SO 路径修改...");
                File cacheDir = getExternalCacheDir();
                if (cacheDir == null) cacheDir = getCacheDir();

                File tempSo = new File(cacheDir, "temp_ForceCloseOreUI.so");
                extractAssetToFile("libForceCloseOreUI.so", tempSo);

                try {
                    String oldPath = "/storage/emulated/0/Android/data/com.mojang.minecraftpe";
                    String newPath = "/sdcard/Android/data/" + targetPkg;

                    replaceStringInSoFile(tempSo, oldPath, newPath, (byte) '/');
                    injectLocalFileToZip(zos, tempSo, "lib/arm64-v8a/libForceCloseOreUI.so");
                    updateLogSafe("✅ 成功修改并注入 libForceCloseOreUI.so");
                } catch (Exception e) {
                    updateLogSafe("❌ 修改 SO 失败: " + e.getMessage());
                    injectAssetToZip(zos, "libForceCloseOreUI.so", "lib/arm64-v8a/libForceCloseOreUI.so");
                }

                if (tempSo.exists()) {
                    tempSo.delete();
                }
            } else {
                injectAssetToZip(zos, "libForceCloseOreUI.so", "lib/arm64-v8a/libForceCloseOreUI.so");
            }

            updateLogSafe("正在注入附加核心组件...");
            injectAssetToZip(zos, "libpreloader.so", "lib/arm64-v8a/libpreloader.so");
            injectAssetToZip(zos, "yinghuaji", "yinghuaji");
            injectAssetToZip(zos, "inject_classes.dex", newDexName);

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

    private boolean processAndCheckDex(File tempIn, File tempOut, String entryName) throws Exception {
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

        updateLogSafe("🎯 在 " + entryName + " 中找到 MainActivity，正在注入并修正底层指针...");

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

                                List<org.jf.dexlib2.iface.TryBlock<? extends org.jf.dexlib2.iface.ExceptionHandler>> newTryBlocks =
                                        new ArrayList<org.jf.dexlib2.iface.TryBlock<? extends org.jf.dexlib2.iface.ExceptionHandler>>();

                                for (org.jf.dexlib2.iface.TryBlock<? extends org.jf.dexlib2.iface.ExceptionHandler> tryBlock : origImpl.getTryBlocks()) {
                                    List<org.jf.dexlib2.iface.ExceptionHandler> newHandlers =
                                            new ArrayList<org.jf.dexlib2.iface.ExceptionHandler>();

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

                                List<org.jf.dexlib2.iface.debug.DebugItem> emptyDebugItems =
                                        new ArrayList<org.jf.dexlib2.iface.debug.DebugItem>();

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
        InputStream is = getAssets().open("debug-mt.bks");
        KeyStore ks = KeyStore.getInstance("BKS");
        ks.load(is, "android".toCharArray());
        is.close();

        String alias = "mt";
        String keyPassword = "android";
        KeyStore.PasswordProtection keyPasswordProtection =
                new KeyStore.PasswordProtection(keyPassword.toCharArray());
        KeyStore.PrivateKeyEntry keyEntry =
                (KeyStore.PrivateKeyEntry) ks.getEntry(alias, keyPasswordProtection);

        PrivateKey privateKey = keyEntry.getPrivateKey();
        X509Certificate cert = (X509Certificate) keyEntry.getCertificate();

        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        certs.add(cert);

        ApkSigner.SignerConfig signerConfig =
                new ApkSigner.SignerConfig.Builder("mt", privateKey, certs).build();
        List<ApkSigner.SignerConfig> signerConfigs = new ArrayList<ApkSigner.SignerConfig>();
        signerConfigs.add(signerConfig);

        ApkSigner signer = new ApkSigner.Builder(signerConfigs)
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build();

        signer.sign();
    }

    private byte[] patchBinaryManifest(byte[] manifestData, String oldPkg, String newPkg) throws Exception {
        if (newPkg.length() > oldPkg.length()) {
            throw new Exception("共存包名长度超过 Manifest 原地修改允许范围。");
        }

        byte[] result = manifestData.clone();

        if (readU16(result, 0) != RES_XML_TYPE) {
            throw new Exception("AndroidManifest.xml 不是有效的二进制 AXML。");
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

        if (replacedCount <= 0) {
            throw new Exception("AndroidManifest.xml 中未找到需要替换的包名字符串。");
        }

        return result;
    }

    private byte[] patchResourcesArsc(byte[] arscData, String oldPkg, String newPkg) throws Exception {
        if (newPkg.length() > oldPkg.length()) {
            throw new Exception("共存包名长度超过 resources.arsc 原地修改允许范围。");
        }

        byte[] result = arscData.clone();

        if (readU16(result, 0) != RES_TABLE_TYPE) {
            throw new Exception("resources.arsc 不是有效的资源表文件。");
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
            throw new Exception("resources.arsc 中未找到目标 package chunk。");
        }

        return result;
    }

    private byte[] patchPackageInfoXml(byte[] data, String oldPkg, String newPkg) throws Exception {
        String text = new String(data, "UTF-8");
        if (!text.contains(oldPkg)) {
            return data;
        }
        text = text.replace(oldPkg, newPkg);
        return text.getBytes("UTF-8");
    }

    private int patchStringPoolChunk(byte[] data, int chunkOffset, Map<String, String> replacements) throws Exception {
        int stringCount = readU32(data, chunkOffset + 8);
        int flags = readU32(data, chunkOffset + 16);
        int stringsStart = readU32(data, chunkOffset + 20);
        int stylesStart = readU32(data, chunkOffset + 24);
        int headerSize = readU16(data, chunkOffset + 2);
        int chunkSize = readU32(data, chunkOffset + 4);

        boolean isUtf8 = (flags & UTF8_FLAG) != 0;

        int[] stringOffsets = new int[stringCount];
        int offsetsBase = chunkOffset + headerSize;
        int i;

        for (i = 0; i < stringCount; i++) {
            stringOffsets[i] = readU32(data, offsetsBase + i * 4);
        }

        int stringDataStart = chunkOffset + stringsStart;
        int stringDataEnd = (stylesStart != 0) ? (chunkOffset + stylesStart) : (chunkOffset + chunkSize);

        int replaceCount = 0;

        for (i = 0; i < stringCount; i++) {
            int relOffset = stringOffsets[i];
            int absOffset = stringDataStart + relOffset;

            if (absOffset < 0 || absOffset >= data.length) {
                continue;
            }

            String currentValue;
            if (isUtf8) {
                currentValue = readUtf8String(data, absOffset);
            } else {
                currentValue = readUtf16String(data, absOffset);
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
                throw new Exception("字符串池替换失败，空间不足: " + currentValue + " -> " + newValue);
            }

            replaceCount++;
        }

        return replaceCount;
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

    private boolean writeUtf8StringInPlace(byte[] data, int offset, int available, String value) throws Exception {
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

    private boolean writeUtf16StringInPlace(byte[] data, int offset, int available, String value) throws Exception {
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
        throw new Exception("UTF-8 字符串长度过大，无法编码。");
    }

    private byte[] encodeLengthUtf16(int length) throws Exception {
        if (length < 0x8000) {
            return new byte[]{
                    (byte) (length & 0xFF),
                    (byte) ((length >> 8) & 0xFF)
            };
        }
        throw new Exception("UTF-16 字符串长度过大，无法编码。");
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

    private void writeFixedUtf16String(byte[] data, int offset, int maxChars, String value) throws Exception {
        if (value.length() > maxChars) {
            throw new Exception("固定 UTF-16 字段空间不足。");
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

    private void putModifiedEntry(ZipOutputStream zos, ZipEntry originalEntry, String entryName, byte[] newData) throws Exception {
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

    /**
     * 底层二进制替换黑科技
     */
    private void replaceStringInSoFile(File soFile, String oldStr, String newStr, byte padChar) throws Exception {
        byte[] oldBytes = oldStr.getBytes("UTF-8");
        byte[] newBytes = newStr.getBytes("UTF-8");

        if (newBytes.length > oldBytes.length) {
            throw new Exception("严重错误：新字符串长度超出了原字符串腾出的极限空间！");
        }

        byte[] fileBytes = new byte[(int) soFile.length()];
        FileInputStream fis = new FileInputStream(soFile);
        fis.read(fileBytes);
        fis.close();

        boolean isModified = false;

        for (int i = 0; i <= fileBytes.length - oldBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < oldBytes.length; j++) {
                if (fileBytes[i + j] != oldBytes[j]) {
                    match = false;
                    break;
                }
            }

            if (match) {
                for (int j = 0; j < newBytes.length; j++) {
                    fileBytes[i + j] = newBytes[j];
                }

                for (int j = newBytes.length; j < oldBytes.length; j++) {
                    fileBytes[i + j] = padChar;
                }

                isModified = true;
            }
        }

        if (isModified) {
            FileOutputStream fos = new FileOutputStream(soFile);
            fos.write(fileBytes);
            fos.close();
        } else {
            throw new Exception("在 SO 文件中没有找到目标路径特征码！");
        }
    }

    private void extractAssetToFile(String assetName, File destFile) throws Exception {
        InputStream is = getAssets().open(assetName);
        FileOutputStream fos = new FileOutputStream(destFile);
        copyStreamToStream(is, fos);
        fos.close();
        is.close();
    }

    private void injectLocalFileToZip(ZipOutputStream zos, File localFile, String zipPath) throws Exception {
        ZipEntry entry = new ZipEntry(zipPath);
        zos.putNextEntry(entry);
        FileInputStream fis = new FileInputStream(localFile);
        copyStreamToStream(fis, zos);
        fis.close();
        zos.closeEntry();
    }

    private void injectAssetToZip(ZipOutputStream zos, String assetName, String zipPath) {
        try {
            InputStream is = getAssets().open(assetName);
            ZipEntry entry = new ZipEntry(zipPath);
            zos.putNextEntry(entry);
            copyStreamToStream(is, zos);
            zos.closeEntry();
            is.close();
            updateLogSafe("成功注入: " + zipPath);
        } catch (Exception e) {
            updateLogSafe("⚠️ 注入失败 (请检查 assets): " + assetName);
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
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) {
            throw new Exception("无法打开所选 APK 文件流。");
        }
        FileOutputStream fos = new FileOutputStream(dest);
        copyStreamToStream(is, fos);
        fos.flush();
        fos.close();
        is.close();
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
                    tvProgressPercent.setText("读取/计算中...");
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

    /**
     * 显示“关于”弹窗
     */
    private void showAboutDialog() {
        android.widget.TextView messageView = new android.widget.TextView(this);

        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        messageView.setPadding(padding, padding, padding, padding);
        messageView.setTextSize(15f);
        messageView.setTextColor(android.graphics.Color.parseColor("#000000"));

        String htmlContent = "No OreUI 工具<br><br>" +
                "用途：修改 Minecraft 安装包，去除 OreUI 并恢复 jsonUI。<br>" +
                "支持：普通处理模式 与 自动共存模式。<br><br>" +
                "QQ群：<br>" +
                "<a href=\"https://qm.qq.com/cgi-bin/qm/qr?_wv=1027&group_code=127681066\">加入QQ群</a><br>" +
                "Telegram频道：<br>" +
                "<a href=\"https://t.me/Minecraft_not_oreUI\">Telegram频道</a>";

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            messageView.setText(android.text.Html.fromHtml(htmlContent, android.text.Html.FROM_HTML_MODE_LEGACY));
        } else {
            messageView.setText(android.text.Html.fromHtml(htmlContent));
        }

        messageView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        new android.app.AlertDialog.Builder(this)
                .setTitle("关于")
                .setView(messageView)
                .setPositiveButton("确定", null)
                .show();
    }

    /**
     * 继承 ZipEntry 的原始压缩属性
     */
    private void copyZipEntryAttributes(ZipEntry original, ZipEntry newEntry) {
        newEntry.setMethod(original.getMethod());
        if (original.getMethod() == ZipEntry.STORED) {
            newEntry.setSize(original.getSize());
            newEntry.setCompressedSize(original.getSize());
            newEntry.setCrc(original.getCrc());
        }
    }

}