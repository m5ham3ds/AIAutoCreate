package com.example.aiautocreate;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpUtils {
    private static final String TAG = "HttpUtils";

    public static boolean postJsonAndSaveToFile(String urlStr, String jsonBody, String bearerToken,
                                                String outFilePath, int connectTimeoutMs, int readTimeoutMs) {
        final int MAX_ATTEMPTS = 3;
        long backoffMillis = 1000L;

        if (jsonBody == null) jsonBody = "";

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (bearerToken != null && bearerToken.length() > 0) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
                }

                // write body
                byte[] payload = jsonBody.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(payload.length);

                OutputStream os = null;
                try {
                    os = new BufferedOutputStream(conn.getOutputStream());
                    os.write(payload);
                    os.flush();
                } finally {
                    if (os != null) try { os.close(); } catch (Exception ignored) {}
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String contentType = conn.getContentType();
                    File out = new File(outFilePath);
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();

                    if (contentType != null && contentType.toLowerCase().contains("application/json")) {
                        InputStream is = null;
                        BufferedReader br = null;
                        try {
                            is = conn.getInputStream();
                            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line);
                            String json = sb.toString();
                            String base64 = extractBase64FromJson(json);
                            if (base64 == null) {
                                Log.w(TAG, "No base64 found in JSON response");
                                return false;
                            }
                            byte[] data = Base64.decode(base64, Base64.DEFAULT);

                            FileOutputStream fos = null;
                            try {
                                fos = new FileOutputStream(out);
                                fos.write(data);
                                fos.flush();
                            } finally {
                                if (fos != null) try { fos.close(); } catch (Exception ignored) {}
                            }

                            return true;
                        } finally {
                            if (br != null) try { br.close(); } catch (Exception ignored) {}
                            if (is != null) try { is.close(); } catch (Exception ignored) {}
                        }
                    } else {
                        InputStream is = null;
                        FileOutputStream fos = null;
                        try {
                            is = conn.getInputStream();
                            fos = new FileOutputStream(out);
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                            fos.flush();
                        } finally {
                            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
                            if (is != null) try { is.close(); } catch (Exception ignored) {}
                        }
                        return true;
                    }
                } else {
                    InputStream err = conn.getErrorStream();
                    if (err != null) {
                        BufferedReader br = null;
                        try {
                            br = new BufferedReader(new InputStreamReader(err, "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String l;
                            while ((l = br.readLine()) != null) sb.append(l);
                            Log.w(TAG, "HTTP error " + code + ": " + sb.toString());
                        } finally {
                            if (br != null) try { br.close(); } catch (Exception ignored) {}
                        }
                    } else {
                        Log.w(TAG, "HTTP error " + code + " (no error stream)");
                    }
                    return false;
                }
            } catch (SocketTimeoutException ste) {
                Log.w(TAG, "Timeout on attempt " + attempt + ": " + ste.getMessage());
                if (attempt == MAX_ATTEMPTS) return false;
                try { Thread.sleep(backoffMillis); } catch (InterruptedException ie) { return false; }
                backoffMillis *= 2;
            } catch (SocketException se) {
                Log.w(TAG, "Socket error attempt " + attempt + ": " + se.getMessage());
                if (attempt == MAX_ATTEMPTS) return false;
                try { Thread.sleep(backoffMillis); } catch (InterruptedException ie) { return false; }
                backoffMillis *= 2;
            } catch (Exception e) {
                Log.e(TAG, "Failed (attempt " + attempt + "): " + e.getMessage(), e);
                return false;
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static String extractBase64FromJson(String json) {
        if (json == null) return null;
        Pattern pDataUri = Pattern.compile("data:[^,;]+;base64,([A-Za-z0-9+/=]+)");
        Matcher mData = pDataUri.matcher(json);
        if (mData.find()) return mData.group(1);

        Pattern pQuoted = Pattern.compile("\"([A-Za-z0-9+/=]{100,})\"");
        Matcher mq = pQuoted.matcher(json);
        String best = null;
        while (mq.find()) {
            String s = mq.group(1);
            if (best == null || s.length() > best.length()) best = s;
        }
        if (best != null) return best;

        Pattern pAny = Pattern.compile("([A-Za-z0-9+/=]{50,})");
        Matcher mAny = pAny.matcher(json);
        best = null;
        while (mAny.find()) {
            String s = mAny.group(1);
            if (best == null || s.length() > best.length()) best = s;
        }
        return best;
    }

    public static String fileToBase64(String path) {
        try {
            File f = new File(path);
            int len = (int) f.length();
            byte[] b = new byte[len];
            FileInputStream fis = new FileInputStream(f);
            int read = 0;
            while (read < len) {
                int r = fis.read(b, read, len - read);
                if (r < 0) break;
                read += r;
            }
            fis.close();
            return Base64.encodeToString(b, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "fileToBase64 failed: " + e.getMessage(), e);
            return null;
        }
    }

    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = is.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }
}
