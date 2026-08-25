package cn.mhook.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiClient {

    public interface Listener {
        void onDelta(String text);

        void onReasoning(String text);

        void onToolCalls(JSONArray toolCalls);

        void onDone(String fullText);

        void onError(Throwable t);
    }

    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static volatile boolean stopFlag = false;
    private static volatile Thread runningThread = null;
    private static volatile HttpURLConnection activeConn = null;

    public static void stream(final Context ctx, final String system, final String user, final Listener listener) {
        final JSONArray messages = new JSONArray();
        messages.add(msg("system", system));
        messages.add(msg("user", user));
        complete(ctx, messages, null, listener);
    }

    public static void complete(final Context ctx, final JSONArray messages, final JSONArray tools, final Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        stopFlag = false;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                runningThread = Thread.currentThread();
                final StringBuilder full = new StringBuilder();
                final Map<Integer, JSONObject> toolCalls = new LinkedHashMap<Integer, JSONObject>();
                HttpURLConnection conn = null;
                try {
                    String endpoint = buildEndpoint(AiSetting.baseUrl(ctx));
                    JSONObject body = new JSONObject(true);
                    body.put("model", AiSetting.model(ctx));
                    body.put("stream", true);
                    body.put("temperature", 0.2);
                    body.put("max_tokens", AiSetting.maxTokens(ctx));
                    body.put("messages", messages);
                    if (tools != null && !tools.isEmpty()) {
                        body.put("tools", tools);
                    }

                    conn = (HttpURLConnection) new URL(endpoint).openConnection();
                    activeConn = conn;
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(AiSetting.timeout(ctx));
                    conn.setReadTimeout(AiSetting.timeout(ctx));
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "text/event-stream");
                    String key = AiSetting.apiKey(ctx);
                    if (key != null && !key.isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + key);
                    }
                    conn.setDoOutput(true);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.toJSONString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        String err = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                        throw new IOException("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (stopFlag) {
                            break;
                        }
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(5).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) {
                            continue;
                        }
                        try {
                            JSONObject obj = JSON.parseObject(data);
                            JSONArray choices = obj.getJSONArray("choices");
                            if (choices == null || choices.isEmpty()) {
                                continue;
                            }
                            JSONObject ch = choices.getJSONObject(0);
                            JSONObject message = ch.getJSONObject("message");
                            if (message != null) {
                                String c = message.getString("content");
                                if (c != null && !c.isEmpty()) {
                                    full.append(c);
                                    final String seg = c;
                                    main.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            listener.onDelta(seg);
                                        }
                                    });
                                }
                                String rc = message.getString("reasoning_content");
                                if (rc != null && !rc.isEmpty()) {
                                    final String seg = rc;
                                    main.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            listener.onReasoning(seg);
                                        }
                                    });
                                }
                                JSONArray tcs = message.getJSONArray("tool_calls");
                                if (tcs != null) {
                                    Log.i("AiClient", "stream message tool_calls=" + tcs.toJSONString());
                                    for (Object o : tcs) {
                                        addToolCall(toolCalls, (JSONObject) o, true);
                                    }
                                }
                                if (ch.get("finish_reason") != null) {
                                    break;
                                }
                            }
                            JSONObject delta = ch.getJSONObject("delta");
                            if (delta != null) {
                                String c = delta.getString("content");
                                if (c != null && !c.isEmpty()) {
                                    full.append(c);
                                    final String seg = c;
                                    main.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            listener.onDelta(seg);
                                        }
                                    });
                                }
                                String rc = delta.getString("reasoning_content");
                                if (rc != null && !rc.isEmpty()) {
                                    final String seg = rc;
                                    main.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            listener.onReasoning(seg);
                                        }
                                    });
                                }
                                JSONArray tcs = delta.getJSONArray("tool_calls");
                                if (tcs != null) {
                                    Log.i("AiClient", "stream delta tool_calls=" + tcs.toJSONString());
                                    for (Object o : tcs) {
                                        addToolCall(toolCalls, (JSONObject) o, false);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    final JSONArray tcArr = new JSONArray();
                    for (JSONObject o : toolCalls.values()) {
                        tcArr.add(o);
                    }
                    final boolean hasTool = !tcArr.isEmpty();
                    if (hasTool) {
                        Log.i("AiClient", "final tool_calls=" + tcArr.toJSONString());
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (hasTool) {
                                listener.onToolCalls(tcArr);
                            } else {
                                listener.onDone(full.toString());
                            }
                        }
                    });
                } catch (final Throwable t) {
                    if (stopFlag) {
                        // 用户主动停止，不当作错误上报
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onDone(full.toString());
                            }
                        });
                    } else {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onError(t);
                            }
                        });
                    }
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                    if (activeConn == conn) activeConn = null;
                    runningThread = null;
                }
            }
        });
    }

    public static void stop() {
        stopFlag = true;
        // 中断阻塞读流的线程，使 readLine() 抛 IOException 立即退出
        Thread t = runningThread;
        if (t != null) t.interrupt();
        // 关闭连接，强制中断网络读取
        HttpURLConnection c = activeConn;
        if (c != null) {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static void addToolCall(Map<Integer, JSONObject> map, JSONObject tc, boolean complete) {
        int index = tc.getIntValue("index");
        JSONObject acc = map.get(index);
        if (acc == null) {
            acc = new JSONObject(true);
            acc.put("index", index);
            acc.put("id", tc.getString("id"));
            acc.put("type", tc.getString("type"));
            JSONObject fn = new JSONObject(true);
            String name = fieldName(tc);
            String args = fieldArgs(tc);
            if (name != null) {
                fn.put("name", name);
            }
            if (args != null) {
                fn.put("arguments", args);
            }
            acc.put("function", fn);
            map.put(index, acc);
        } else {
            if (tc.getString("id") != null) {
                acc.put("id", tc.getString("id"));
            }
            if (tc.getString("type") != null) {
                acc.put("type", tc.getString("type"));
            }
            JSONObject fn = acc.getJSONObject("function");
            String name = fieldName(tc);
            if (name != null) {
                fn.put("name", name);
            }
            String args = fieldArgs(tc);
            if (args != null) {
                // 流式片段有时同一 index 重复推送同一段内容；
                // 若本次片段是上次完整拼接的后缀则跳过，避免重复拼接。
                String prev = fn.getString("arguments");
                if (prev == null) prev = "";
                if (complete && args.equals(prev)) {
                    return;
                }
                if (args.length() < prev.length() && prev.endsWith(args)) {
                    return;
                }
                fn.put("arguments", prev + args);
            }
        }
    }

    private static String fieldName(JSONObject tc) {
        JSONObject fn = tc.getJSONObject("function");
        String n = fn == null ? null : fn.getString("name");
        if (n == null || n.isEmpty()) {
            n = tc.getString("name");
        }
        return n;
    }

    private static String fieldArgs(JSONObject tc) {
        JSONObject fn = tc.getJSONObject("function");
        String a = fn == null ? null : fn.getString("arguments");
        if (a == null) {
            Object top = tc.get("arguments");
            a = top == null ? null : top.toString();
        }
        return a;
    }

    private static JSONObject msg(String role, String content) {
        JSONObject m = new JSONObject(true);
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    private static String buildEndpoint(String base) {
        if (base == null) {
            base = "";
        }
        base = base.trim();
        if (base.isEmpty()) {
            return "https://api.openai.com/v1/chat/completions";
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) {
            sb.append(l);
        }
        r.close();
        return sb.toString();
    }
}
