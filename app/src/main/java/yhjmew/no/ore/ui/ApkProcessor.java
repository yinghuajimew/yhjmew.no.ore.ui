package yhjmew.no.ore.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;

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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ApkProcessor {

    private static final int SO_TYPE_WITH_PRELOADER = 0;
    private static final int SO_TYPE_STANDALONE = 1;
    private static final String PREF_SO = "SoConfig";

    /**
     * 输出包里所有条目统一使用的时间戳：2008-01-01 00:00:00。
     *
     * <p>为什么不用 {@code ZipEntry.setTime(long)}：它收的是 1970 纪元毫秒，
     * 内部还要按**本地时区**拆成年月日再转 DOS 位域。同一次构建在北京是
     * 2008-01-01 00:00，在纽约就成了 2007-12-31 11:00，做不到"统一"。
     * {@code setTimeLocal} 没有时区问题，但它是 API 24 才有的，minSdk 14 用不了。
     *
     * <p>所以直接反射写 ZipEntry 的 {@code xdostime} 字段 —— 那正是
     * ZipOutputStream 写本地头时用的值，绕开所有换算。写不进去就退回
     * setTime，至少不会崩。
     */
    private static final long FIXED_DOS_TIME = 0x38210000L;   // 2008-01-01 00:00 的 DOS 位域

    /** 2008-01-01 00:00:00 UTC 的毫秒值，反射失败时兜底用 */
    private static final long FIXED_EPOCH_MS = 1199145600000L;

    /** 注入 dex 的独有类名前缀，用来识别「上次打包的产物」 */
    private static final byte[] INJECTED_DEX_MARKER =
            "Ldev/yejdhi/loader/".getBytes(java.nio.charset.Charset.forName("UTF-8"));

    private Context context;
    private SignatureManager signatureManager;
    private ManifestPatcher manifestPatcher;
    private ProgressCallback progressCallback;

    private int currentSoType = SO_TYPE_STANDALONE;

    public interface ProgressCallback {
        void onProgressUpdate(int current, int max);
        void onLog(String message);
        void setIndeterminate(boolean isIndeterminate);
    }

    public ApkProcessor(Context context, SignatureManager signatureManager, ProgressCallback callback) {
        this.context = context;
        this.signatureManager = signatureManager;
        this.manifestPatcher = new ManifestPatcher(context);
        this.progressCallback = callback;
        loadSoConfig();
    }

    /**
     * 加载 SO 配置
     */
    private void loadSoConfig() {
        // 根据当前运行此打包工具的 Android 版本自动选择 SO。
        // Android 11 (API 30) 及以上：独立版 SO
        // Android 10 (API 29) 及以下：依赖版 SO
        // 不再使用之前保存的 SO 类型，避免在不同 Android 版本设备上选错。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            currentSoType = SO_TYPE_STANDALONE;
        } else {
            currentSoType = SO_TYPE_WITH_PRELOADER;
        }
    }

    /**
     * 保存 SO 配置
     */
    public void saveSoConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_SO, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("so_type", currentSoType);
        editor.apply();
    }

    /**
     * 获取 SO 显示名称
     */
    public String getSoDisplayName(int type) {
        if (type == SO_TYPE_WITH_PRELOADER) {
            return context.getString(R.string.msg_so_preloader);
        } else if (type == SO_TYPE_STANDALONE) {
            return context.getString(R.string.msg_so_standalone);
        }
        return context.getString(R.string.err_so_unknown);
    }

    /**
     * 处理 APK（主入口）
     */
    public void processApk(Uri uri, boolean isCloneMode, String oldPkg, String targetPkg, 
                          String originalFileName, String originalDirectory) throws Exception {
        progressCallback.setIndeterminate(true);

        File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null) cacheDir = context.getCacheDir();

        final File tempApk = new File(cacheDir, "source_temp.apk");
        final File moddedApk = new File(cacheDir, "modded_temp.apk");
        final File signedApk = new File(cacheDir, "signed_temp.apk");

        try {
            if (isCloneMode) {
                progressCallback.onLog(context.getString(R.string.log_mode_clone));
                progressCallback.onLog(context.getString(R.string.log_current_pkg_plain) + oldPkg);
                progressCallback.onLog(context.getString(R.string.log_target_pkg) + targetPkg);
            } else {
                progressCallback.onLog(context.getString(R.string.log_mode_normal));
                progressCallback.onLog(context.getString(R.string.log_current_pkg) + oldPkg);
            }

            progressCallback.onLog(context.getString(R.string.log_use_signature) + signatureManager.getSignDisplayName(signatureManager.getCurrentSignType()));

            progressCallback.onLog(context.getString(R.string.log_copying_temp));
            copyUriToFile(uri, tempApk);

            progressCallback.onLog(context.getString(R.string.log_start_repack));
            injectFileIntoApk(tempApk, moddedApk, oldPkg, targetPkg, isCloneMode);

            progressCallback.onLog(context.getString(R.string.log_signing));
            progressCallback.setIndeterminate(true);
            signatureManager.signApk(moddedApk, signedApk);

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
                progressCallback.onLog(context.getString(R.string.log_warn_fallback_dir));
                finalOutputFile = new File(Environment.getExternalStorageDirectory() + "/Download", newFileName);
            }

            progressCallback.onLog(context.getString(R.string.log_exporting));
            copyFile(signedApk, finalOutputFile);

            tempApk.delete();
            moddedApk.delete();
            signedApk.delete();

            progressCallback.onProgressUpdate(100, 100);
            progressCallback.setIndeterminate(false);

            if (isCloneMode) {
                progressCallback.onLog(context.getString(R.string.log_clone_done));
                progressCallback.onLog(context.getString(R.string.log_pkg_changed_from) + oldPkg + context.getString(R.string.log_pkg_changed_to) + targetPkg + "]");
            } else {
                progressCallback.onLog(context.getString(R.string.log_normal_done));
            }

            progressCallback.onLog(context.getString(R.string.log_saved_to) + finalOutputFile.getAbsolutePath());

        } finally {
            if (tempApk.exists()) tempApk.delete();
            if (moddedApk.exists()) moddedApk.delete();
            if (signedApk.exists()) signedApk.delete();
        }
    }

    /**
     * 注入文件到 APK
     */
    private void injectFileIntoApk(File sourceFile, File destFile, String oldPkg, String targetPkg, boolean needClone)
            throws Exception {
        ZipFile zipFile = null;
        ZipOutputStream zos = null;

        try {
            zipFile = new ZipFile(sourceFile);

            // 先扫一遍：既要算新 dex 该叫什么名字，
            // 也要找出「上一次打包留下的注入 dex」，那些必须丢掉重建，
            // 否则重打包会出现两份同名类，游戏直接崩。
            Set<String> existingDexNames = new HashSet<String>();
            Set<String> staleInjectedDexNames = new HashSet<String>();

            Enumeration<? extends ZipEntry> scanEntries = zipFile.entries();
            while (scanEntries.hasMoreElements()) {
                ZipEntry e = scanEntries.nextElement();
                String name = e.getName();
                if (!name.startsWith("classes") || !name.endsWith(".dex")) {
                    continue;
                }
                existingDexNames.add(name);
                if (isInjectedDex(zipFile, e)) {
                    staleInjectedDexNames.add(name);
                }
            }

            boolean isRepack = !staleInjectedDexNames.isEmpty();

            int injectDexIndex = 1;
            String injectDexName = "classes.dex";
            while (existingDexNames.contains(injectDexName)
                    && !staleInjectedDexNames.contains(injectDexName)) {
                injectDexIndex++;
                injectDexName = "classes" + injectDexIndex + ".dex";
            }
            // 若首选名字恰好是个旧注入 dex，直接复用那个名字覆盖掉
            if (isRepack) {
                progressCallback.onLog(context.getString(R.string.log_repack_detected)
                        + staleInjectedDexNames.size() + context.getString(R.string.fmt_unit_pieces));
            }

            progressCallback.onLog(context.getString(R.string.log_dex_existing) + existingDexNames.size() + context.getString(R.string.fmt_unit_pieces));
            progressCallback.onLog(context.getString(R.string.log_dex_assign));
            progressCallback.onLog("  inject_classes.dex → " + injectDexName);

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

            progressCallback.setIndeterminate(false);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                currentEntry++;

                if (currentEntry % 50 == 0) {
                    progressCallback.onProgressUpdate(currentEntry, totalEntries);
                }

                String entryName = entry.getName();

                if (entryName.startsWith("META-INF/") || overridePaths.contains(entryName)) {
                    continue;
                }

                // 上一次打包留下的注入 dex：整条丢弃，稍后写入全新的
                if (staleInjectedDexNames.contains(entryName)) {
                    continue;
                }

                InputStream is = zipFile.getInputStream(entry);
                if (is == null) {
                    continue;
                }

                if (needClone && "AndroidManifest.xml".equals(entryName)) {
                    progressCallback.onLog(context.getString(R.string.log_modifying_manifest));
                    byte[] originalBytes = Utils.readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = manifestPatcher.patchBinaryManifest(originalBytes, oldPkg, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    manifestPatched = true;
                    continue;
                }

                if (needClone && "resources.arsc".equals(entryName)) {
                    progressCallback.onLog(context.getString(R.string.log_modifying_arsc));
                    byte[] originalBytes = Utils.readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = manifestPatcher.patchResourcesArsc(originalBytes, oldPkg, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    arscPatched = true;
                    continue;
                }

                if (needClone && entryName.endsWith("package-info.xml")) {
                    progressCallback.onLog(context.getString(R.string.log_modifying) + entryName + "...");
                    byte[] originalBytes = Utils.readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = manifestPatcher.patchPackageInfoXml(originalBytes, oldPkg, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    packageInfoFound = true;
                    continue;
                }

                if (entryName.startsWith("classes") && entryName.endsWith(".dex")) {
                    File cacheDir = context.getExternalCacheDir();
                    if (cacheDir == null) cacheDir = context.getCacheDir();

                    File tempDexIn = new File(cacheDir, "temp_in.dex");
                    File tempDexOut = new File(cacheDir, "temp_out.dex");

                    FileOutputStream fosDex = new FileOutputStream(tempDexIn);
                    Utils.copyStreamToStream(is, fosDex);
                    fosDex.close();
                    is.close();

                    boolean injected = false;
                    try {
                        injected = processAndCheckDex(tempDexIn, tempDexOut, entryName);
                    } catch (Exception e) {
                        progressCallback.onLog(context.getString(R.string.log_dex_failed) + entryName + "): " + Utils.getFullStackTrace(e));
                    }

                    if (injected) {
                        ZipEntry newEntry = new ZipEntry(entryName);
                        applyFixedTime(newEntry);
                        zos.putNextEntry(newEntry);
                        FileInputStream fisDexOut = new FileInputStream(tempDexOut);
                        Utils.copyStreamToStream(fisDexOut, zos);
                        fisDexOut.close();
                        zos.closeEntry();
                        tempDexOut.delete();
                    } else {
                        ZipEntry newEntry = new ZipEntry(entryName);
                        applyFixedTime(newEntry);
                        copyZipEntryAttributes(entry, newEntry);
                        zos.putNextEntry(newEntry);
                        FileInputStream fisDexIn = new FileInputStream(tempDexIn);
                        Utils.copyStreamToStream(fisDexIn, zos);
                        fisDexIn.close();
                        zos.closeEntry();
                    }

                    tempDexIn.delete();
                } else {
                    ZipEntry newEntry = new ZipEntry(entryName);
                    applyFixedTime(newEntry);
                    copyZipEntryAttributes(entry, newEntry);
                    zos.putNextEntry(newEntry);
                    Utils.copyStreamToStream(is, zos);
                    is.close();
                    zos.closeEntry();
                }
            }

            if (needClone) {
                if (!manifestPatched) {
                    throw new Exception(context.getString(R.string.err_clone_no_manifest)
                            + context.getString(R.string.err_parse_total_entries) + totalEntries + "\n"
                            + context.getString(R.string.err_why_apk_structure));
                }
                if (!arscPatched) {
                    throw new Exception(context.getString(R.string.err_clone_no_arsc)
                            + context.getString(R.string.err_parse_total_entries) + totalEntries + "\n"
                            + context.getString(R.string.err_why_split_apk));
                }
                if (!packageInfoFound) {
                    progressCallback.onLog(context.getString(R.string.log_pkginfo_skipped));
                }
            }

            progressCallback.onLog(context.getString(R.string.log_injecting_components));

            String soAssetName;
            if (currentSoType == SO_TYPE_WITH_PRELOADER) {
                injectAssetToZip(zos, "libpreloader.so", "lib/arm64-v8a/libpreloader.so");
                soAssetName = "libForceCloseOreUI_with_preloader.so";
                progressCallback.onLog(context.getString(R.string.log_inject_preloader));
            } else {
                soAssetName = "libForceCloseOreUI_standalone.so";
                progressCallback.onLog(context.getString(R.string.log_inject_standalone));
            }

            injectAssetToZip(zos, soAssetName, "lib/arm64-v8a/libForceCloseOreUI.so");
            injectAssetToZip(zos, "yinghuaji", "yinghuaji");
            injectAssetToZip(zos, "inject_classes.dex", injectDexName);

            progressCallback.onProgressUpdate(totalEntries, totalEntries);

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

    /**
     * 处理并检查 DEX 文件
     */
    /**
     * 判断某个 dex 是不是「本工具上次注入的那个」。
     *
     * <p>注入 dex 里有一批 {@code dev.yejdhi.loader.*} 类，这是外部加载器
     * （inject_classes.dex）独有的命名空间，游戏本体不可能有。
     * 直接把 dex 当字节流搜字符串，比加载解析快得多，也不会因为
     * 版本差异解析失败。
     */
    /**
     * 检查 onCreate 的指令里是否已经存在 {@code SoLoader.loadLibrary(Context)}。
     *
     * <p>只看引用名，不校验寄存器：我们插入的那条是方法开头第一条指令，
     * 而游戏本体（第 0 版）没有这个类的调用，命中即视为已注入。
     */
    private boolean alreadyInjectedLoadLibrary(List<Instruction> instructions) {
        for (Instruction instr : instructions) {
            if (!(instr instanceof org.jf.dexlib2.iface.instruction.ReferenceInstruction)) {
                continue;
            }
            org.jf.dexlib2.iface.reference.Reference ref =
                    ((org.jf.dexlib2.iface.instruction.ReferenceInstruction) instr).getReference();
            if (!(ref instanceof org.jf.dexlib2.iface.reference.MethodReference)) {
                continue;
            }
            org.jf.dexlib2.iface.reference.MethodReference mr =
                    (org.jf.dexlib2.iface.reference.MethodReference) ref;
            if ("Lcom/mojang/minecraftpe/SoLoader;".equals(mr.getDefiningClass())
                    && "loadLibrary".equals(mr.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isInjectedDex(ZipFile zipFile, ZipEntry entry) {
        InputStream is = null;
        byte[] head = null;
        try {
            is = zipFile.getInputStream(entry);
            if (is == null) {
                return false;
            }

            // 流式找 dev.yejdhi.loader 命名空间 —— 这是外部加载器独有的，
            // 游戏本体的 dex 里不会出现，命中即可判定为上次打包的产物。
            byte[] marker = INJECTED_DEX_MARKER;
            int matched = 0;
            int b;
            while ((b = is.read()) != -1) {
                if ((byte) b == marker[matched]) {
                    matched++;
                    if (matched == marker.length) {
                        return true;
                    }
                } else {
                    matched = ((byte) b == marker[0]) ? 1 : 0;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (is != null) {
                try {
                    is.close();
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

        progressCallback.onLog(context.getString(R.string.log_mc_in) + entryName + context.getString(R.string.log_mainactivity_found));

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

                                // 幂等：上一次打包已经插过一次 loadLibrary 的话就别再插。
                                // 重打包时 classes.dex 还是游戏原包，但 onCreate 可能
                                // 已经被上一轮改写过了（用户拿打好的包再打一次）。
                                if (alreadyInjectedLoadLibrary(instructions)) {
                                    progressCallback.onLog(
                                            context.getString(R.string.log_repack_skip_inject));
                                    return method;
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

    /**
     * 注入 Asset 到 ZIP
     */
    private void injectAssetToZip(ZipOutputStream zos, String assetName, String zipPath) {
        try {
            InputStream is = context.getAssets().open(assetName);

            boolean isSoFile = zipPath.endsWith(".so");

            ZipEntry entry = new ZipEntry(zipPath);
            applyFixedTime(entry);

            if (isSoFile) {
                byte[] data = Utils.readAllBytes(is);
                is.close();

                                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);

                CRC32 crc32 = new CRC32();
                crc32.update(data);
                entry.setCrc(crc32.getValue());

                alignTo4K(zos);

                zos.putNextEntry(entry);
                zos.write(data);
            } else {
                zos.putNextEntry(entry);
                Utils.copyStreamToStream(is, zos);
                is.close();
            }

            zos.closeEntry();
            progressCallback.onLog(context.getString(R.string.log_inject_ok) + zipPath);
        } catch (Exception e) {
            progressCallback.onLog(context.getString(R.string.err_injection_failed) + assetName);
            progressCallback.onLog(context.getString(R.string.err_reason) + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 对齐到 4K
     */
    /** ZipEntry 里真正被写进 ZIP 的 DOS 时间字段 */
    private static java.lang.reflect.Field sDosTimeField;
    private static boolean sDosTimeFieldReady;

    /**
     * 把条目的时间戳钉死到 2008-01-01 00:00。
     *
     * <p>新建的 ZipEntry 不设时间会取当前时刻，这就是「每次打包时间都不一样」
     * 的原因。游戏本体原有的条目时间来自官方构建，保留也没意义。
     *
     * <p>字段类型要注意：AOSP 的 ZipEntry 里是 {@code int xdostime}，
     * OpenJDK 早期版本也是 int、后来改成了 long。用 setInt/setLong 按实际
     * 类型写，两边都能用；类型对不上会抛 IllegalArgumentException。
     */
    private void applyFixedTime(ZipEntry entry) {
        if (!sDosTimeFieldReady) {
            sDosTimeFieldReady = true;
            try {
                java.lang.reflect.Field f = ZipEntry.class.getDeclaredField("xdostime");
                f.setAccessible(true);
                sDosTimeField = f;
            } catch (Throwable t) {
                sDosTimeField = null;
            }
        }

        if (sDosTimeField != null) {
            try {
                if (sDosTimeField.getType() == int.class) {
                    sDosTimeField.setInt(entry, (int) FIXED_DOS_TIME);
                } else {
                    sDosTimeField.setLong(entry, FIXED_DOS_TIME);
                }
                return;
            } catch (Throwable ignore) {
                // 落到下面的兜底
            }
        }

        // 反射不可用（个别 ROM 会限制）：退回 setTime，时区不同会有偏差
        entry.setTime(FIXED_EPOCH_MS);
    }

    private void alignTo4K(ZipOutputStream zos) throws Exception {
        try {
            java.lang.reflect.Field writtenField = java.util.zip.DeflaterOutputStream.class.getDeclaredField("written");
            writtenField.setAccessible(true);
            long written = writtenField.getLong(zos);

            int remainder = (int) (written % 4096);
            if (remainder != 0) {
                int padding = 4096 - remainder;
                for (int i = 0; i < padding; i++) {
                    zos.write(0);
                }
            }
        } catch (Exception e) {
            // 反射失败，跳过对齐
        }
    }

    /**
     * 放置修改后的条目
     */
    private void putModifiedEntry(ZipOutputStream zos, ZipEntry originalEntry, String entryName, byte[] newData)
            throws Exception {
        ZipEntry newEntry = new ZipEntry(entryName);
        applyFixedTime(newEntry);

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

    /**
     * 复制 ZIP 条目属性
     */
    private void copyZipEntryAttributes(ZipEntry original, ZipEntry newEntry) {
        newEntry.setMethod(original.getMethod());
        if (original.getMethod() == ZipEntry.STORED) {
            newEntry.setSize(original.getSize());
            newEntry.setCompressedSize(original.getSize());
            newEntry.setCrc(original.getCrc());
        }
    }

    /**
     * 从 URI 复制到文件
     */
    private void copyUriToFile(Uri uri, File dest) throws Exception {
        if (uri == null) {
            throw new Exception(context.getString(R.string.err_io_uri_null));
        }

        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
        } catch (Exception e) {
            throw new Exception(context.getString(R.string.err_io_open_stream) + uri.toString() + "\n"
                    + context.getString(R.string.err_reason_alt) + e.getMessage() + "\n"
                    + context.getString(R.string.err_why_file_gone));
        }

        if (is == null) {
            throw new Exception(context.getString(R.string.err_io_empty_stream) + uri.toString());
        }

        try {
            FileOutputStream fos = new FileOutputStream(dest);
            Utils.copyStreamToStream(is, fos);
            fos.flush();
            fos.close();
            is.close();
        } catch (Exception e) {
            throw new Exception(context.getString(R.string.err_io_copy)
                    + context.getString(R.string.log_source) + uri.toString() + "\n"
                    + context.getString(R.string.fmt_target) + dest.getAbsolutePath() + "\n"
                    + context.getString(R.string.err_reason) + e.getMessage());
        }
    }

    /**
     * 复制文件
     */
    private void copyFile(File source, File dest) throws Exception {
        FileInputStream fis = new FileInputStream(source);
        FileOutputStream fos = new FileOutputStream(dest);
        Utils.copyStreamToStream(fis, fos);
        fos.flush();
        fos.close();
        fis.close();
    }

    // Getters and Setters
    public int getCurrentSoType() {
        return currentSoType;
    }

    public void setCurrentSoType(int currentSoType) {
        this.currentSoType = currentSoType;
    }
}