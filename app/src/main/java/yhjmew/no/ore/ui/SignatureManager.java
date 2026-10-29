package yhjmew.no.ore.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SignatureManager {

    public static final int SIGN_TYPE_MT = 0;
    public static final int SIGN_TYPE_ZIHAO_IL = 1;
    public static final int SIGN_TYPE_CUSTOM = 2;

    private static final String PREF_NAME = "SignConfig";

    private Context context;
    private int currentSignType;
    private String customSignFilePath;
    private String customSignAlias;
    private String customSignKeystorePassword;
    private String customSignKeyPassword;

    public SignatureManager(Context context) {
        this.context = context;
        loadSignConfig();
    }

    /**
     * 加载签名配置
     */
    public void loadSignConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        currentSignType = prefs.getInt("sign_type", SIGN_TYPE_MT);
        customSignFilePath = prefs.getString("custom_path", null);
        customSignAlias = prefs.getString("custom_alias", "android");
        customSignKeystorePassword = prefs.getString("custom_ks_password", "android");
        customSignKeyPassword = prefs.getString("custom_key_password", "android");
    }

    /**
     * 保存签名配置
     */
    public void saveSignConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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

    /**
     * 获取签名显示名称
     */
    public String getSignDisplayName(int type) {
        if (type == SIGN_TYPE_MT) {
            return context.getString(R.string.btn_sign_mt_default);
        } else if (type == SIGN_TYPE_ZIHAO_IL) {
            return context.getString(R.string.btn_sign_zihao_il);
        } else if (type == SIGN_TYPE_CUSTOM) {
            return context.getString(R.string.btn_custom_signature);
        }
        return context.getString(R.string.err_sign_unknown);
    }

    /**
     * 签名 APK
     */
    public void signApk(File inputApk, File outputApk) throws Exception {
        InputStream is = null;
        String ksType = "BKS";
        String alias = null;
        String keystorePassword = null;
        String keyPassword = null;
        KeyStore ks = null;

        try {
            if (currentSignType == SIGN_TYPE_MT) {
                is = context.getAssets().open("debug-mt.bks");
                alias = "mt";
                keystorePassword = "android";
                keyPassword = "android";
                ksType = "BKS";
            } else if (currentSignType == SIGN_TYPE_ZIHAO_IL) {
                is = context.getAssets().open("minecraft.bks");
                alias = "minecraft";
                keystorePassword = "minecraft";
                keyPassword = "minecraft";
                ksType = "BKS";
            } else if (currentSignType == SIGN_TYPE_CUSTOM) {
                if (customSignFilePath == null || customSignFilePath.length() == 0) {
                    throw new Exception(context.getString(R.string.err_custom_sign_unset));
                }

                File signFile = new File(customSignFilePath);
                if (!signFile.exists()) {
                    throw new Exception(context.getString(R.string.err_custom_sign_missing) + customSignFilePath + context.getString(R.string.msg_check_path));
                }

                if (signFile.length() == 0) {
                    throw new Exception(context.getString(R.string.err_sign_size_0) + customSignFilePath + context.getString(R.string.msg_file_maybe_corrupt));
                }

                try {
                    is = new FileInputStream(signFile);
                } catch (Exception e) {
                    throw new RuntimeException(context.getString(R.string.err_sign_unreadable) + customSignFilePath + context.getString(R.string.err_reason_nl) + e);
                }

                String fileName = signFile.getName();
                ksType = guessKeystoreType(fileName);
                alias = customSignAlias;
                keystorePassword = customSignKeystorePassword;
                keyPassword = customSignKeyPassword;
            } else {
                throw new Exception(context.getString(R.string.err_sign_type_unknown) + currentSignType);
            }

            try {
                ks = KeyStore.getInstance(ksType);
                ks.load(is, keystorePassword.toCharArray());
            } catch (Exception e) {
                throw new RuntimeException(context.getString(R.string.err_keystore_load) + ksType + ")\n"
                        + context.getString(R.string.err_why_keystore)
                        + context.getString(R.string.err_detailed) + e);
            }

            KeyStore.PasswordProtection keyPasswordProtection = new KeyStore.PasswordProtection(keyPassword.toCharArray());

            KeyStore.PrivateKeyEntry keyEntry = null;
            try {
                keyEntry = (KeyStore.PrivateKeyEntry) ks.getEntry(alias, keyPasswordProtection);
            } catch (Exception e) {
                throw new RuntimeException(context.getString(R.string.err_alias_missing) + alias + ")\n"
                        + context.getString(R.string.err_why_alias)
                        + context.getString(R.string.err_detailed) + e);
            }

            if (keyEntry == null) {
                throw new RuntimeException(context.getString(R.string.err_alias_not_found) + alias + "\n"
                        + context.getString(R.string.msg_ask_alias_check));
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
                throw new RuntimeException(context.getString(R.string.err_sign_process_failed) + e);
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

    /**
     * 猜测 KeyStore 类型
     */
    private String guessKeystoreType(String fileName) {
        return "BKS";
    }

    /**
     * 缓存签名文件到本地
     */
    public String copySignFileToCache(Uri signUri) throws Exception {
        if (signUri == null) {
            throw new Exception(context.getString(R.string.err_cache_uri_null));
        }

        File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null) cacheDir = context.getCacheDir();

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new Exception(context.getString(R.string.err_cache_mkdir) + cacheDir.getAbsolutePath());
        }

        File signCacheFile = new File(cacheDir, "custom_sign_file");
        if (signCacheFile.exists()) {
            signCacheFile.delete();
        }

        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(signUri);
        } catch (Exception e) {
            throw new Exception(context.getString(R.string.err_cache_unreadable) + signUri.toString() + "\n"
                    + context.getString(R.string.err_reason) + e.getMessage());
        }

        if (is == null) {
            throw new Exception(context.getString(R.string.err_cache_empty_stream) + signUri.toString());
        }

        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(signCacheFile);
            Utils.copyStreamToStream(is, fos);
            fos.flush();
            fos.close();
            is.close();
        } catch (Exception e) {
            throw new Exception(context.getString(R.string.err_cache_copy)
                    + context.getString(R.string.fmt_target_open) + signCacheFile.getAbsolutePath() + "\n"
                    + context.getString(R.string.err_reason) + e.getMessage());
        }

        if (signCacheFile.length() == 0) {
            throw new Exception(context.getString(R.string.err_cache_size_0));
        }

        return signCacheFile.getAbsolutePath();
    }

    // Getters and Setters
    public int getCurrentSignType() {
        return currentSignType;
    }

    public void setCurrentSignType(int currentSignType) {
        this.currentSignType = currentSignType;
    }

    public String getCustomSignFilePath() {
        return customSignFilePath;
    }

    public void setCustomSignFilePath(String customSignFilePath) {
        this.customSignFilePath = customSignFilePath;
    }

    public String getCustomSignAlias() {
        return customSignAlias;
    }

    public void setCustomSignAlias(String customSignAlias) {
        this.customSignAlias = customSignAlias;
    }

    public String getCustomSignKeystorePassword() {
        return customSignKeystorePassword;
    }

    public void setCustomSignKeystorePassword(String customSignKeystorePassword) {
        this.customSignKeystorePassword = customSignKeystorePassword;
    }

    public String getCustomSignKeyPassword() {
        return customSignKeyPassword;
    }

    public void setCustomSignKeyPassword(String customSignKeyPassword) {
        this.customSignKeyPassword = customSignKeyPassword;
    }
}