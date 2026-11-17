package com.paperjump.mobile;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class BindingData {
    static final String SOURCE = "https://paper-portal.pages.dev/private-data.enc.json";

    static final class Anchor {
        final String pageId;
        final int width;
        final int height;
        final Point[] points;
        final Mat descriptors;
        final String title;
        final String url;

        Anchor(String pageId, int width, int height, Point[] points, Mat descriptors,
               String title, String url) {
            this.pageId = pageId;
            this.width = width;
            this.height = height;
            this.points = points;
            this.descriptors = descriptors;
            this.title = title;
            this.url = url;
        }
    }

    static List<Anchor> fetch(String keyBase64) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(SOURCE).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("同步失败：HTTP " + connection.getResponseCode());
            byte[] envelopeBytes = readAll(connection.getInputStream());
            JSONObject envelope = new JSONObject(new String(envelopeBytes, StandardCharsets.UTF_8));
            if (!"paperjump-encrypted-v1".equals(envelope.optString("format")))
                throw new IllegalArgumentException("同步数据格式错误");
            byte[] key = Base64.decode(keyBase64, Base64.DEFAULT);
            if (key.length != 32) throw new IllegalArgumentException("配对密钥错误");
            byte[] nonce = Base64.decode(envelope.getString("nonce"), Base64.DEFAULT);
            byte[] ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] compressed = cipher.doFinal(ciphertext);
            JSONObject snapshot = new JSONObject(new String(readAll(
                    new InflaterInputStream(new java.io.ByteArrayInputStream(compressed))), StandardCharsets.UTF_8));
            if (!"paperjump-private-mobile-v1".equals(snapshot.optString("format")))
                throw new IllegalArgumentException("绑定数据格式错误");
            JSONObject nodes = snapshot.getJSONObject("nodes");
            JSONArray raw = snapshot.getJSONArray("local_anchors");
            List<Anchor> anchors = new ArrayList<>();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject item = raw.getJSONObject(i);
                String pageId = item.getString("page_id");
                JSONArray keypoints = item.getJSONArray("keypoints");
                byte[] bytes = Base64.decode(item.getString("descriptors_base64"), Base64.DEFAULT);
                if (keypoints.length() < 10 || bytes.length != keypoints.length() * 32) continue;
                Point[] points = new Point[keypoints.length()];
                for (int j = 0; j < points.length; j++) {
                    JSONArray coordinate = keypoints.getJSONArray(j);
                    points[j] = new Point(coordinate.getDouble(0), coordinate.getDouble(1));
                }
                Mat descriptors = new Mat(points.length, 32, CvType.CV_8U);
                descriptors.put(0, 0, bytes);
                String title = "第 " + pageId + " 页";
                String target = "";
                JSONObject pageNodes = nodes.optJSONObject(pageId);
                if (pageNodes != null) {
                    java.util.Iterator<String> names = pageNodes.keys();
                    while (names.hasNext()) {
                        String name = names.next();
                        JSONObject node = pageNodes.optJSONObject(name);
                        if (node == null) continue;
                        String candidate = firstTarget(node);
                        if (!candidate.isEmpty()) { title = name; target = candidate; break; }
                    }
                }
                anchors.add(new Anchor(pageId, item.getInt("canonical_width"),
                        item.getInt("canonical_height"), points, descriptors, title, target));
            }
            return anchors;
        } finally {
            connection.disconnect();
        }
    }

    private static String firstTarget(JSONObject node) {
        for (String field : new String[]{"android_target", "mobile_target", "conversation_url", "target", "url"}) {
            String value = node.optString(field, "");
            if (value.startsWith("https://") || value.startsWith("http://") || value.startsWith("obsidian://"))
                return value;
        }
        return "";
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[16384];
            int count;
            while ((count = source.read(block)) != -1) output.write(block, 0, count);
            return output.toByteArray();
        }
    }
}
