package yhjmew.no.ore.ui;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ManifestPatcher {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int UTF8_FLAG = 0x00000100;

    private Context context;

    public ManifestPatcher(Context context) {
        this.context = context;
    }

    /**
     * 修改 AndroidManifest.xml 中的包名
     */
    public byte[] patchBinaryManifest(byte[] manifestData, String oldPkg, String newPkg) throws Exception {
        if (manifestData == null || manifestData.length < 8) {
            throw new Exception(context.getString(R.string.err_parse_manifest_empty));
        }

        if (newPkg.length() > oldPkg.length()) {
            throw new Exception(context.getString(R.string.err_parse_manifest_clone_len) + newPkg.length() + context.getString(R.string.fmt_exceeds_orig_len)
                    + oldPkg.length() + context.getString(R.string.fmt_inplace_too_long_nl)
                    + context.getString(R.string.err_parse_old_pkg) + oldPkg + context.getString(R.string.err_parse_new_pkg) + newPkg);
        }

        byte[] result = manifestData.clone();

        if (Utils.readU16(result, 0) != RES_XML_TYPE) {
            throw new Exception(context.getString(R.string.err_parse_manifest_invalid)
                    + Integer.toHexString(Utils.readU16(result, 0)) + context.getString(R.string.err_parse_expect_0x0003));
        }

        Map<String, String> replacements = new LinkedHashMap<String, String>();
        replacements.put(oldPkg, newPkg);
        replacements.put(oldPkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                newPkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        replacements.put(oldPkg + ".fileprovider", newPkg + ".fileprovider");
        replacements.put(oldPkg + ".firebaseinitprovider", newPkg + ".firebaseinitprovider");
        replacements.put(oldPkg + ".androidx-startup", newPkg + ".androidx-startup");

        int xmlSize = Utils.readU32(result, 4);
        int offset = Utils.readU16(result, 2);
        if (offset <= 0) offset = 8;

        int replacedCount = -1;

        while (offset + 8 <= result.length && offset < xmlSize) {
            int chunkType = Utils.readU16(result, offset);
            int chunkSize = Utils.readU32(result, offset + 4);

            if (chunkSize <= 0) break;

            if (chunkType == RES_STRING_POOL_TYPE) {
                replacedCount = patchStringPoolChunk(result, offset, replacements);
                break;
            }

            offset += chunkSize;
        }

        if (replacedCount < 0) {
            throw new Exception(context.getString(R.string.err_parse_manifest_nopool));
        }

        if (replacedCount == 0) {
            throw new Exception(context.getString(R.string.err_parse_manifest_nopkg)
                    + context.getString(R.string.err_parse_looking_for) + oldPkg + context.getString(R.string.fmt_derived_strings)
                    + context.getString(R.string.err_why_apk_pkg));
        }

        return result;
    }

    /**
     * 修改 resources.arsc 中的包名
     */
    public byte[] patchResourcesArsc(byte[] arscData, String oldPkg, String newPkg) throws Exception {
        if (arscData == null || arscData.length < 8) {
            throw new Exception(context.getString(R.string.err_parse_arsc_empty));
        }

        if (newPkg.length() > oldPkg.length()) {
            throw new Exception(context.getString(R.string.err_parse_arsc_clone_len) + newPkg.length() + context.getString(R.string.fmt_exceeds_orig_len)
                    + oldPkg.length() + context.getString(R.string.fmt_inplace_too_long));
        }

        byte[] result = arscData.clone();

        if (Utils.readU16(result, 0) != RES_TABLE_TYPE) {
            throw new Exception(context.getString(R.string.err_parse_arsc_invalid)
                    + Integer.toHexString(Utils.readU16(result, 0)) + context.getString(R.string.err_parse_expect_0002));
        }

        int tableSize = Utils.readU32(result, 4);
        int headerSize = Utils.readU16(result, 2);
        int offset = headerSize;
        boolean found = false;

        while (offset + 8 <= result.length && offset < tableSize) {
            int chunkType = Utils.readU16(result, offset);
            int chunkSize = Utils.readU32(result, offset + 4);

            if (chunkSize <= 0) break;

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
            throw new Exception(context.getString(R.string.err_parse_arsc_pkg_missing) + oldPkg + context.getString(R.string.fmt_package_chunk)
                    + context.getString(R.string.err_why_arsc_pkg));
        }

        return result;
    }

    /**
     * 修改 package-info.xml 中的包名
     */
    public byte[] patchPackageInfoXml(byte[] data, String oldPkg, String newPkg) throws Exception {
        if (data == null || data.length == 0) {
            throw new Exception(context.getString(R.string.err_parse_pkginfo_data_empty));
        }

        String text;
        try {
            text = new String(data, "UTF-8");
        } catch (Exception e) {
            throw new Exception(context.getString(R.string.err_parse_pkginfo_decode) + e.getMessage());
        }

        if (!text.contains(oldPkg)) {
            throw new Exception(context.getString(R.string.err_parse_pkginfo_old_pkg) + oldPkg + "\n"
                    + context.getString(R.string.err_parse_content_len) + text.length() + context.getString(R.string.fmt_characters_nl)
                    + context.getString(R.string.err_why_file_modified));
        }

        text = text.replace(oldPkg, newPkg);

        return text.getBytes("UTF-8");
    }

    /**
     * 修改字符串池 chunk
     */
    private int patchStringPoolChunk(byte[] data, int chunkOffset, Map<String, String> replacements)
            throws Exception {
        int stringCount = Utils.readU32(data, chunkOffset + 8);
        int flags = Utils.readU32(data, chunkOffset + 16);
        int stringsStart = Utils.readU32(data, chunkOffset + 20);
        int stylesStart = Utils.readU32(data, chunkOffset + 24);
        int headerSize = Utils.readU16(data, chunkOffset + 2);
        int chunkSize = Utils.readU32(data, chunkOffset + 4);

        boolean isUtf8 = (flags & UTF8_FLAG) != 0;

        if (stringCount <= 0) {
            throw new Exception(context.getString(R.string.err_parse_pool_zero));
        }

        int[] stringOffsets = new int[stringCount];
        int offsetsBase = chunkOffset + headerSize;

        for (int i = 0; i < stringCount; i++) {
            stringOffsets[i] = Utils.readU32(data, offsetsBase + i * 4);
        }

        int stringDataStart = chunkOffset + stringsStart;
        int stringDataEnd = (stylesStart != 0) ? (chunkOffset + stylesStart) : (chunkOffset + chunkSize);

        int replaceCount = 0;
        int failCount = 0;
        StringBuilder failDetail = new StringBuilder();

        for (int i = 0; i < stringCount; i++) {
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
                    failDetail.append(context.getString(R.string.fmt_string_index) + i + context.getString(R.string.err_read_failed) + e.getMessage() + "; ");
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
                throw new Exception(context.getString(R.string.err_parse_pool_nospace)
                        + context.getString(R.string.fmt_original_open) + currentValue + context.getString(R.string.fmt_length_open) + currentValue.length() + ")\n"
                        + context.getString(R.string.fmt_target_open) + newValue + context.getString(R.string.fmt_length_open) + newValue.length() + ")\n"
                        + context.getString(R.string.err_parse_slots) + available + context.getString(R.string.fmt_bytes_nl)
                        + context.getString(R.string.err_parse_coding) + (isUtf8 ? "UTF-8" : "UTF-16"));
            }

            replaceCount++;
        }

        if (failCount > 0) {
            throw new Exception(context.getString(R.string.err_parse_pool_read) + failCount + context.getString(R.string.err_failed_nl) + failDetail.toString());
        }

        return replaceCount;
    }

    private int findAvailableStringSlot(int[] offsets, int currentOffset, int stringDataSize) {
        int nextOffset = stringDataSize;

        for (int i = 0; i < offsets.length; i++) {
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
        int first = Utils.readU16(data, offset);
        if ((first & 0x8000) == 0) {
            return new int[]{first, 2};
        }

        int second = Utils.readU16(data, offset + 2);
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
        throw new Exception(context.getString(R.string.err_parse_utf8_too_long));
    }

    private byte[] encodeLengthUtf16(int length) throws Exception {
        if (length < 0x8000) {
            return new byte[]{
                    (byte) (length & 0xFF),
                    (byte) ((length >> 8) & 0xFF)
            };
        }
        throw new Exception(context.getString(R.string.err_parse_utf16_too_long));
    }

    private String readFixedUtf16String(byte[] data, int offset, int maxChars) throws Exception {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < maxChars; i++) {
            int c = Utils.readU16(data, offset + i * 2);
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
            throw new Exception(context.getString(R.string.err_parse_utf16_nospace));
        }

        Arrays.fill(data, offset, offset + maxChars * 2, (byte) 0);
        byte[] utf16 = value.getBytes("UTF-16LE");
        System.arraycopy(utf16, 0, data, offset, utf16.length);
    }
}