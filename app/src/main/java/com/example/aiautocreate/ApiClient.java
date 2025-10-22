package com.example.aiautocreate;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ApiClient - Ultra-Full Literal
 * Java7 / AIDE Compatible
 *
 * - يفرّق تلقائيًا بين Gemini API وباقي الـ APIs
 * - Gemini يستخدم x-goog-api-key
 * - باقي الخدمات تستخدم Authorization: Bearer
 * - يحدد User-Agent و Accept الافتراضية
 */
public class ApiClient {

    private static final String UA = "AI-AutoCreate/1.0 (Java7-AIDE)";
    private static final String ACCEPT_JSON = "application/json, */*";

    // ================= POST JSON (String Response) =================
    public static String postJson(String urlString, String apiKey, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", ACCEPT_JSON);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            // ⚡ تمييز Gemini API
            if (apiKey != null && apiKey.length() > 0) {
                if (isGemini(urlString)) {
                    conn.setRequestProperty("x-goog-api-key", apiKey);
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
            }

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            byte[] data = (jsonBody == null ? "{}" : jsonBody).getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(data.length);
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();

            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String resp = sb.toString();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " | " + resp);
            }
            return resp;

        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception e) {}
        }
    }

    // ================= POST JSON (Binary Response) =================
    public static byte[] postJsonForBinary(String urlString, String apiKey, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        InputStream is = null;
        ByteArrayOutputStream baos = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            // ⚡ تمييز Gemini API
            if (apiKey != null && apiKey.length() > 0) {
                if (isGemini(urlString)) {
                    conn.setRequestProperty("x-goog-api-key", apiKey);
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
            }

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);

            byte[] data = (jsonBody == null ? "{}" : jsonBody).getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(data.length);
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            byte[] resp = baos.toByteArray();

            if (code < 200 || code >= 300) {
                String body = "";
                try { body = new String(resp, "UTF-8"); } catch (Exception ignored) {}
                throw new Exception("HTTP " + code + " | " + body);
            }

            return resp;

        } finally {
            try { if (baos != null) baos.close(); } catch (Exception e) {}
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception e) {}
        }
    }

    // ================= GET =================
    public static String get(String urlString, String apiKey) throws Exception {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", ACCEPT_JSON);

            // ⚡ تمييز Gemini API
            if (apiKey != null && apiKey.length() > 0) {
                if (isGemini(urlString)) {
                    conn.setRequestProperty("x-goog-api-key", apiKey);
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
            }

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();

            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String resp = sb.toString();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " | " + resp);
            }
            return resp;

        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception e) {}
        }
    }

    private static boolean isGemini(String url){
        if (url == null) return false;
        return url.indexOf("generativelanguage.googleapis.com") >= 0;
    }
}
