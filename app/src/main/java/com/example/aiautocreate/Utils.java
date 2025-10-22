package com.example.aiautocreate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class Utils {
    public static String readFile(File f) throws Exception {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int read = 0;
            int offset = 0;
            while (offset < b.length && (read = fis.read(b, offset, b.length - offset)) >= 0) {
                offset += read;
            }
            return new String(b, "UTF-8");
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception e) {}
        }
    }

    public static void writeBytesToFile(byte[] data, File out) throws Exception {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            fos.write(data);
            fos.flush();
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception e) {}
        }
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
