package cn.mhook.update;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 检查更新：查询 GitHub Releases API（xiamiya/mHook），
 * 获取最新版本、更新日志与 APK 下载地址，并提供下载。
 */
public class UpdateChecker {

    private static final String API = "https://api.github.com/repos/xiamiya/mHook/releases/latest";

    public static class ReleaseInfo {
        public String tagName;
        public String name;
        public String body;
        public String apkUrl;
        public long size;
        public String publishedAt;
    }

    public static ReleaseInfo fetchLatest() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "mHook-Android");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        conn.disconnect();
        JSONObject o = JSONObject.parseObject(sb.toString());
        if (o == null) throw new Exception("返回数据为空");
        ReleaseInfo info = new ReleaseInfo();
        info.tagName = o.getString("tag_name");
        info.name = o.getString("name");
        info.body = o.getString("body");
        info.publishedAt = o.getString("published_at");
        JSONArray assets = o.getJSONArray("assets");
        if (assets != null) {
            for (Object a : assets) {
                JSONObject asset = (JSONObject) a;
                String n = asset.getString("name");
                if (n != null && n.endsWith(".apk")) {
                    info.apkUrl = asset.getString("browser_download_url");
                    info.size = asset.getLongValue("size");
                    break;
                }
            }
        }
        if (info.tagName == null || info.tagName.isEmpty()) throw new Exception("版本信息缺失");
        return info;
    }

    /** 返回 >0 表示 v1 较新，=0 相同，<0 表示 v2 较新。 */
    public static int compareVersion(String v1, String v2) {
        int[] a = parseSegments(v1), b = parseSegments(v2);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] parseSegments(String v) {
        if (v == null) v = "";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        String[] parts = v.split("[^0-9]+");
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                r[i] = Integer.parseInt(parts[i]);
            } catch (Throwable t) {
                r[i] = 0;
            }
        }
        return r;
    }
}
