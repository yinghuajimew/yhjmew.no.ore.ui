package yhjmew.no.ore.ui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class Utils {

    /**
     * 验证包名格式是否合法
     */
    public static boolean isValidPackageName(String pkg) {
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

    /**
     * 获取完整的异常堆栈信息
     */
    public static String getFullStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * 流复制
     */
    public static void copyStreamToStream(InputStream is, OutputStream os) throws Exception {
        byte[] buffer = new byte[65536];
        int length;
        while ((length = is.read(buffer)) > 0) {
            os.write(buffer, 0, length);
        }
    }

    /**
     * 读取流的全部字节
     */
    public static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copyStreamToStream(is, bos);
        return bos.toByteArray();
    }

    /**
     * 读取 16 位无符号整数（小端序）
     */
    public static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    /**
     * 读取 32 位无符号整数（小端序）
     */
    public static int readU32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * 从字符串列表安全获取字符串
     */
    public static String getStringSafe(java.util.List<String> list, int index) {
        if (list == null) return null;
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }
}