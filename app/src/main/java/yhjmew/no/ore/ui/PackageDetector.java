package yhjmew.no.ore.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PackageDetector {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int UTF8_FLAG = 0x00000100;

    private Context context;

    public PackageDetector(Context context) {
        this.context = context;
    }

    /**
     * 从 APK URI 检测包名
     */
    public String detectPackageNameFromApkUri(Uri uri) throws Exception {
        File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null) cacheDir = context.getCacheDir();

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

    /**
     * 从 APK 文件检测包名
     */
    public String detectPackageNameFromApkFile(File apkFile) throws Exception {
        if (!apkFile.exists()) {
            throw new Exception(context.getString(R.string.detection_apk_temporary_file_does_not_exist) + apkFile.getAbsolutePath());
        }

        // 方法 1：使用 PackageManager
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null && info.packageName != null && info.packageName.length() > 0) {
                return info.packageName;
            }
        } catch (Exception e) {
            // 失败则尝试其他方法
        }

        // 方法 2 和 3：从 ZIP 中读取
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apkFile);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith("package-info.xml")) {
                    InputStream is = zipFile.getInputStream(entry);
                    byte[] data = Utils.readAllBytes(is);
                    is.close();
                    try {
                        String pkg = extractPackageNameFromPackageInfoXml(data);
                        if (pkg != null && pkg.length() > 0) {
                            return pkg;
                        }
                    } catch (Exception e) {
                        // 继续尝试其他方法
                    }
                }
            }

            // 方法 3：从 AndroidManifest.xml 读取
            ZipEntry manifestEntry = zipFile.getEntry("AndroidManifest.xml");
            if (manifestEntry != null) {
                InputStream is = zipFile.getInputStream(manifestEntry);
                byte[] data = Utils.readAllBytes(is);
                is.close();
                try {
                    String pkg = extractPackageNameFromBinaryManifest(data);
                    if (pkg != null && pkg.length() > 0) {
                        return pkg;
                    }
                } catch (Exception e) {
                    // 所有方法都失败
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(context.getString(R.string.detection_unable_to_open_apk_as_zip), e);
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Exception ignore) {
                }
            }
        }

        throw new Exception(context.getString(R.string.detection_the_package_name_cannot_be_read_in_all_w));
    }

    /**
     * 从 package-info.xml 提取包名
     */
    private String extractPackageNameFromPackageInfoXml(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            throw new Exception(context.getString(R.string.packageinfo_parsing_the_data_is_empty));
        }

        String text;
        try {
            text = new String(data, "UTF-8");
        } catch (Exception e) {
            throw new Exception(context.getString(R.string.packageinfo_parsing_utf8_decoding_failed) + e.getMessage());
        }

        int tagIndex = text.indexOf("<package");
        if (tagIndex == -1) {
            throw new Exception(context.getString(R.string.packageinfo_parsing_package_tag_not_found_nthe_fir)
                    + text.substring(0, Math.min(text.length(), 100)));
        }

        int nameIndex = text.indexOf("name=", tagIndex);
        if (nameIndex == -1) {
            throw new Exception(context.getString(R.string.packageinfo_parsing_the_name_attribute_was_not_fou));
        }

        int quoteIndex = nameIndex + 5;
        while (quoteIndex < text.length() && Character.isWhitespace(text.charAt(quoteIndex))) {
            quoteIndex++;
        }
        if (quoteIndex >= text.length()) {
            throw new Exception(context.getString(R.string.packageinfo_analysis_there_is_no_quotation_mark_af));
        }

        char quote = text.charAt(quoteIndex);
        if (quote != '"' && quote != '\'') {
            throw new Exception(context.getString(R.string.packageinfo_parsing_quotation_marks_are_expected_a) + quote + "'");
        }

        int end = text.indexOf(quote, quoteIndex + 1);
        if (end == -1) {
            throw new Exception(context.getString(R.string.packageinfo_analysis_the_attribute_value_quotation));
        }

        String result = text.substring(quoteIndex + 1, end).trim();
        if (result.length() == 0) {
            throw new Exception(context.getString(R.string.packageinfo_analysis_the_extracted_package_name_is));
        }

        return result;
    }

    /**
     * 从二进制 AndroidManifest.xml 提取包名
     */
    private String extractPackageNameFromBinaryManifest(byte[] manifestData) throws Exception {
        if (manifestData == null) {
            throw new Exception(context.getString(R.string.manifest_parsing_the_data_is_null));
        }

        if (manifestData.length < 8) {
            throw new Exception(context.getString(R.string.manifest_analysis_data_is_too_small) + manifestData.length + context.getString(R.string.bytes_cannot_be_valid_axml));
        }

        if (Utils.readU16(manifestData, 0) != RES_XML_TYPE) {
            throw new Exception(context.getString(R.string.manifest_analysis_header_type_code_does_not_match_)
                    + Integer.toHexString(Utils.readU16(manifestData, 0)) + context.getString(R.string.expecting_0x0003));
        }

        List<String> stringPool = null;
        int xmlSize = Utils.readU32(manifestData, 4);
        int offset = Utils.readU16(manifestData, 2);
        if (offset <= 0) offset = 8;

        int chunkCount = 0;

        while (offset + 8 <= manifestData.length && offset < xmlSize) {
            int chunkType = Utils.readU16(manifestData, offset);
            int chunkSize = Utils.readU32(manifestData, offset + 4);
            chunkCount++;

            if (chunkSize <= 0) {
                throw new Exception(context.getString(R.string.manifest_analysis_no) + chunkCount + context.getString(R.string.abnormal_chunk_size) + chunkSize
                        + context.getString(R.string.offset_0x) + Integer.toHexString(offset) + ")");
            }

            if (chunkType == RES_STRING_POOL_TYPE && stringPool == null) {
                try {
                    stringPool = readStringPoolStrings(manifestData, offset);
                } catch (Exception e) {
                    throw new Exception(context.getString(R.string.manifest_parsing_failed_to_read_string_pool_offset)
                            + Integer.toHexString(offset) + "): " + e.getMessage());
                }
            } else if (chunkType == RES_XML_START_ELEMENT_TYPE) {
                if (stringPool == null) {
                    throw new Exception(context.getString(R.string.manifest_analysis_start_element_was_encountered_bu));
                }

                if (offset + 28 > manifestData.length) {
                    throw new Exception(context.getString(R.string.manifest_analysis_start_element_chunk_insufficient)
                            + Integer.toHexString(offset));
                }

                int nameIndex = Utils.readU32(manifestData, offset + 20);
                String tagName = Utils.getStringSafe(stringPool, nameIndex);
                if (tagName == null) {
                    throw new Exception(context.getString(R.string.manifest_analysis_tag_name_index) + nameIndex + context.getString(R.string.string_pool_range_exceeded_size)
                            + stringPool.size() + ")");
                }

                if ("manifest".equals(tagName)) {
                    int attributeStart = Utils.readU16(manifestData, offset + 24);
                    int attributeSize = Utils.readU16(manifestData, offset + 26);
                    int attributeCount = Utils.readU16(manifestData, offset + 28);

                    if (attributeCount == 0) {
                        throw new Exception(context.getString(R.string.manifest_analysis_the_number_of_manifest_tag_attri));
                    }

                    int attrBase = offset + attributeStart;

                    for (int i = 0; i < attributeCount; i++) {
                        int attrOffset = attrBase + i * attributeSize;

                        if (attrOffset + 20 > manifestData.length) {
                            throw new Exception(context.getString(R.string.manifest_analysis_attributes) + i + context.getString(R.string.data_out_of_bounds));
                        }

                        int attrNameIndex = Utils.readU32(manifestData, attrOffset + 4);
                        int rawValueIndex = Utils.readU32(manifestData, attrOffset + 8);
                        int typedValueType = manifestData[attrOffset + 15] & 0xFF;
                        int typedValueData = Utils.readU32(manifestData, attrOffset + 16);

                        String attrName = Utils.getStringSafe(stringPool, attrNameIndex);
                        if (attrName == null) {
                            continue;
                        }

                        if ("package".equals(attrName)) {
                            if (rawValueIndex != -1) {
                                String pkg = Utils.getStringSafe(stringPool, rawValueIndex);
                                if (pkg != null && pkg.length() > 0) {
                                    return pkg;
                                }
                            }
                            if (typedValueType == 0x03) {
                                String pkg = Utils.getStringSafe(stringPool, typedValueData);
                                if (pkg != null && pkg.length() > 0) {
                                    return pkg;
                                }
                            }
                            throw new Exception(context.getString(R.string.manifest_parsing_the_package_attribute_was_found_b)
                                    + context.getString(R.string.rawvalueindex) + rawValueIndex + "\n"
                                    + context.getString(R.string.typedvaluetype_0x) + Integer.toHexString(typedValueType) + "\n"
                                    + context.getString(R.string.typedvaluedata) + typedValueData);
                        }
                    }

                    throw new Exception(context.getString(R.string.manifest_parsing_package_attribute_not_found_in_ma)
                            + context.getString(R.string.total_number_of_properties) + attributeCount);
                }
            }

            offset += chunkSize;
        }

        throw new Exception(context.getString(R.string.manifest_parsing_manifest_tag_not_found_n)
                + context.getString(R.string.file_size) + manifestData.length + context.getString(R.string.bytesn)
                + context.getString(R.string.traverse_the_number_of_chunks) + chunkCount);
    }

    /**
     * 读取字符串池
     */
    private List<String> readStringPoolStrings(byte[] data, int chunkOffset) throws Exception {
        int stringCount = Utils.readU32(data, chunkOffset + 8);
        int flags = Utils.readU32(data, chunkOffset + 16);
        int stringsStart = Utils.readU32(data, chunkOffset + 20);
        int headerSize = Utils.readU16(data, chunkOffset + 2);

        boolean isUtf8 = (flags & UTF8_FLAG) != 0;

        int[] stringOffsets = new int[stringCount];
        int offsetsBase = chunkOffset + headerSize;

        for (int i = 0; i < stringCount; i++) {
            stringOffsets[i] = Utils.readU32(data, offsetsBase + i * 4);
        }

        int stringDataStart = chunkOffset + stringsStart;
        List<String> list = new ArrayList<String>(stringCount);

        for (int i = 0; i < stringCount; i++) {
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
        int first = Utils.readU16(data, offset);
        if ((first & 0x8000) == 0) {
            return new int[]{first, 2};
        }
        int second = Utils.readU16(data, offset + 2);
        int length = ((first & 0x7FFF) << 16) | second;
        return new int[]{length, 4};
    }

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
}