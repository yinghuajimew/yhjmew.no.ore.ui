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
        SharedPreferences prefs = context.getSharedPreferences(PREF_SO, Context.MODE_PRIVATE);
        currentSoType = prefs.getInt("so_type", SO_TYPE_STANDALONE);
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
            return context.getString(R.string.so_with_preloader);
        } else if (type == SO_TYPE_STANDALONE) {
            return context.getString(R.string.so_standalone);
        }
        return context.getString(R.string.unknown_so_type);
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
                progressCallback.onLog(context.getString(R.string.current_mode_coexistence_mode));
                progressCallback.onLog(context.getString(R.string.current_package_name_1) + oldPkg);
                progressCallback.onLog(context.getString(R.string.target_package_name) + targetPkg);
            } else {
                progressCallback.onLog(context.getString(R.string.current_mode_normal_mode));
                progressCallback.onLog(context.getString(R.string.current_package_name) + oldPkg);
            }

            progressCallback.onLog(context.getString(R.string.use_signature) + signatureManager.getSignDisplayName(signatureManager.getCurrentSignType()));

            progressCallback.onLog(context.getString(R.string.copying_to_temporary_directory));
            copyUriToFile(uri, tempApk);

            progressCallback.onLog(context.getString(R.string.start_unpacking_and_repacking_this_will_take_some_));
            injectFileIntoApk(tempApk, moddedApk, oldPkg, targetPkg, isCloneMode);

            progressCallback.onLog(context.getString(R.string.modification_completed_signing_in_progress));
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
                progressCallback.onLog(context.getString(R.string.warning_unable_to_write_to_original_directory_will));
                finalOutputFile = new File(Environment.getExternalStorageDirectory() + "/Download", newFileName);
            }

            progressCallback.onLog(context.getString(R.string.signature_completed_exporting_file));
            copyFile(signedApk, finalOutputFile);

            tempApk.delete();
            moddedApk.delete();
            signedApk.delete();

            progressCallback.onProgressUpdate(100, 100);
            progressCallback.setIndeterminate(false);

            if (isCloneMode) {
                progressCallback.onLog(context.getString(R.string.coexistence_processing_completed));
                progressCallback.onLog(context.getString(R.string.the_package_name_has_been_changed_from) + oldPkg + context.getString(R.string.automatically_modified_to) + targetPkg + "]");
            } else {
                progressCallback.onLog(context.getString(R.string.ordinary_processing_completed));
            }

            progressCallback.onLog(context.getString(R.string.the_final_file_has_been_saved_ton) + finalOutputFile.getAbsolutePath());

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

            progressCallback.onLog(context.getString(R.string.the_original_package_already_has_a_dex_file) + existingDexNames.size() + context.getString(R.string.indivual));
            progressCallback.onLog(context.getString(R.string.inject_dex_number_assignment));
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

                InputStream is = zipFile.getInputStream(entry);
                if (is == null) {
                    continue;
                }

                if (needClone && "AndroidManifest.xml".equals(entryName)) {
                    progressCallback.onLog(context.getString(R.string.modifying_androidmanifestxml_package_name));
                    byte[] originalBytes = Utils.readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = manifestPatcher.patchBinaryManifest(originalBytes, oldPkg, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    manifestPatched = true;
                    continue;
                }

                if (needClone && "resources.arsc".equals(entryName)) {
                    progressCallback.onLog(context.getString(R.string.modifying_resourcesarsc));
                    byte[] originalBytes = Utils.readAllBytes(is);
                    is.close();

                    byte[] patchedBytes = manifestPatcher.patchResourcesArsc(originalBytes, oldPkg, targetPkg);
                    putModifiedEntry(zos, entry, entryName, patchedBytes);
                    arscPatched = true;
                    continue;
                }

                if (needClone && entryName.endsWith("package-info.xml")) {
                    progressCallback.onLog(context.getString(R.string.modifying) + entryName + "...");
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
                        progressCallback.onLog(context.getString(R.string.dex_processing_failed) + entryName + "): " + Utils.getFullStackTrace(e));
                    }

                    if (injected) {
                        ZipEntry newEntry = new ZipEntry(entryName);
                        zos.putNextEntry(newEntry);
                        FileInputStream fisDexOut = new FileInputStream(tempDexOut);
                        Utils.copyStreamToStream(fisDexOut, zos);
                        fisDexOut.close();
                        zos.closeEntry();
                        tempDexOut.delete();
                    } else {
                        ZipEntry newEntry = new ZipEntry(entryName);
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
                    copyZipEntryAttributes(entry, newEntry);
                    zos.putNextEntry(newEntry);
                    Utils.copyStreamToStream(is, zos);
                    is.close();
                    zos.closeEntry();
                }
            }

            if (needClone) {
                if (!manifestPatched) {
                    throw new Exception(context.getString(R.string.coexistence_androidmanifestxml_was_not_found_in_th)
                            + context.getString(R.string.total_number_of_entries_in_apk) + totalEntries + "\n"
                            + context.getString(R.string.possible_reasons_the_apk_structure_is_damaged_or_t));
                }
                if (!arscPatched) {
                    throw new Exception(context.getString(R.string.coexistence_resourcesarsc_was_not_found_in_the_apk)
                            + context.getString(R.string.total_number_of_entries_in_apk) + totalEntries + "\n"
                            + context.getString(R.string.possible_reasons_the_apk_is_a_split_apk_no_indepen));
                }
                if (!packageInfoFound) {
                    progressCallback.onLog(context.getString(R.string.packageinfoxml_not_found_automatically_skipped));
                }
            }

            progressCallback.onLog(context.getString(R.string.injecting_additional_core_components));

            String soAssetName;
            if (currentSoType == SO_TYPE_WITH_PRELOADER) {
                injectAssetToZip(zos, "libpreloader.so", "lib/arm64-v8a/libpreloader.so");
                soAssetName = "libForceCloseOreUI_with_preloader.so";
                progressCallback.onLog(context.getString(R.string.inject_with_preloader_mode));
            } else {
                soAssetName = "libForceCloseOreUI_standalone.so";
                progressCallback.onLog(context.getString(R.string.inject_standalone_mode));
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

        progressCallback.onLog(context.getString(R.string.in) + entryName + context.getString(R.string.mainactivity_is_found_injecting_and_correcting_the));

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

    /**
     * 注入 Asset 到 ZIP
     */
    private void injectAssetToZip(ZipOutputStream zos, String assetName, String zipPath) {
        try {
            InputStream is = context.getAssets().open(assetName);

            boolean isSoFile = zipPath.endsWith(".so");

            ZipEntry entry = new ZipEntry(zipPath);

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
            progressCallback.onLog(context.getString(R.string.successful_injection) + zipPath);
        } catch (Exception e) {
            progressCallback.onLog(context.getString(R.string.injection_failed) + assetName);
            progressCallback.onLog(context.getString(R.string.reason) + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 对齐到 4K
     */
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
            throw new Exception(context.getString(R.string.io_uri_is_null_file_cannot_be_copied));
        }

        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
        } catch (Exception e) {
            throw new Exception(context.getString(R.string.io_unable_to_open_input_stream_for_selected_file) + uri.toString() + "\n"
                    + context.getString(R.string.reason_1) + e.getMessage() + "\n"
                    + context.getString(R.string.possible_reasons_the_file_has_been_deleted_moved_o));
        }

        if (is == null) {
            throw new Exception(context.getString(R.string.io_getcontentresolver_returns_an_empty_stream_and_) + uri.toString());
        }

        try {
            FileOutputStream fos = new FileOutputStream(dest);
            Utils.copyStreamToStream(is, fos);
            fos.flush();
            fos.close();
            is.close();
        } catch (Exception e) {
            throw new Exception(context.getString(R.string.io_error_copying_file_streamn)
                    + context.getString(R.string.source) + uri.toString() + "\n"
                    + context.getString(R.string.target_1) + dest.getAbsolutePath() + "\n"
                    + context.getString(R.string.reason) + e.getMessage());
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