package cn.mhook.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 会话工具循环：AI 可调用 MCP 后端工具与内置技能，直到产出最终结果。
 */
public class AiSession {

    public interface Listener {
        void onDelta(String text);

        void onReasoning(String text);

        void onToolEvent(String text);

        void onDone(String finalText);

        void onError(Throwable t);
    }

    public static final int MAX_STEPS = 32;
    private static volatile int maxSteps = MAX_STEPS;
    private static volatile boolean unlimited = false;
    private static volatile boolean stopFlag = false;
    private static final List<String> availableTools = new ArrayList<String>();

    public static void stop() {
        stopFlag = true;
    }

    /** 按 AiSetting 默认步数运行（有上下限限制）。 */
    public static void run(final Context ctx, final String system, final String user, final Listener listener) {
        stopFlag = false;
        unlimited = false;
        maxSteps = AiSetting.maxSteps(ctx);
        if (maxSteps <= 0) maxSteps = MAX_STEPS;
        startLoop(ctx, system, user, listener);
    }

    /**
     * 无限模式：AI 脱壳专用。
     * 不限制工具调用轮次（除非用户手动停止）；单轮看门狗时间放宽到 15 分钟，
     * 避免 unidbg 模拟执行耗时被误杀。
     */
    public static void runUnlimited(final Context ctx, final String system, final String user, final Listener listener) {
        stopFlag = false;
        unlimited = true;
        maxSteps = Integer.MAX_VALUE;
        startLoop(ctx, system, user, listener);
    }

    private static void startLoop(final Context ctx, final String system, final String user, final Listener listener) {
        availableTools.clear();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray messages = new JSONArray();
                messages.add(msg("system", system));
                messages.add(msg("user", user));
                final JSONArray tools = new JSONArray();
                final List<String> errors = new ArrayList<String>();

                try {
                    List<McpManager.McpTool> mcpTools = McpManager.collectTools(ctx, errors);
                    for (McpManager.McpTool t : mcpTools) {
                        tools.add(t.toFunction());
                        availableTools.add(t.fullName());
                    }
                } catch (Throwable t) {
                    errors.add(t.getMessage());
                }
                addUseSkill(ctx, tools);
                if (!errors.isEmpty()) {
                    final String errMsg = "部分 MCP 后端不可用：" + join(errors);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onToolEvent(errMsg);
                        }
                    });
                    messages.add(msg("system", "MCP 探测结果：以下服务器本次不可用，禁止调用其工具："
                            + join(errors) + "。可用工具仅限 tools 列表中的 mcp__ 前缀。"));
                }
                runRound(ctx, messages, tools, 0, main, listener);
            }
        }).start();
    }

    private static void runRound(final Context ctx, final JSONArray messages, final JSONArray tools, final int step,
                                 final Handler main, final Listener listener) {
        if (stopFlag) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onDone("");
                }
            });
            return;
        }
        if (step >= maxSteps) {
            final String tip = "\n[已停止：达到最大工具调用轮次 " + maxSteps + "]";
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onToolEvent(tip);
                    listener.onDone("");
                }
            });
            return;
        }
        final long watchdogMs = unlimited
                ? Math.max(900_000, AiSetting.timeout(ctx))
                : Math.max(45_000, AiSetting.timeout(ctx));
        final int[] retries = {0};
        final Runnable[] watchdog = new Runnable[1];
        watchdog[0] = new Runnable() {
            @Override
            public void run() {
                if (stopFlag) {
                    return;
                }
                stopFlag = true;
                final String tip = "\n\n[AI 无响应] 本轮在 " + (watchdogMs / 1000)
                        + " 秒内没有任何输出，已自动终止会话。若目标分析确实复杂，请再次发起分析（可调大 AI 设置中的超时）。\n\n";
                listener.onToolEvent(tip);
                listener.onDone("");
            }
        };
        // 每轮开始时即武装看门狗：若本轮请求自始至终无任何流式输出（挂起/空响应），也能超时终止
        main.postDelayed(watchdog[0], watchdogMs);
        // 重复输出检测：同一结尾片段连续重复（模型生成退化循环）时自动终止
        final String[] lastChunk = {""};
        final int[] sameChunkCount = {0};
        final long[] lastChunkTime = {0};
        final int REPEAT_LIMIT = 12;
        AiClient.complete(ctx, messages, tools, new AiClient.Listener() {
            @Override
            public void onDelta(String text) {
                main.removeCallbacks(watchdog[0]);
                main.postDelayed(watchdog[0], watchdogMs);
                if (text != null && text.trim().length() >= 2) {
                    String chunk = text.trim();
                    long now = System.currentTimeMillis();
                    if (chunk.equals(lastChunk[0]) && now - lastChunkTime[0] < 4000) {
                        sameChunkCount[0]++;
                        if (sameChunkCount[0] >= REPEAT_LIMIT) {
                            stopFlag = true;
                            final String loopTip = "\n\n[已自动终止] 检测到 AI 输出陷入重复循环（同一片段连续出现 "
                                    + REPEAT_LIMIT + " 次），已停止以免浪费 token。请重新发起分析。\n\n";
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        main.removeCallbacks(watchdog[0]);
                                    } catch (Throwable ignored) {
                                    }
                                    listener.onToolEvent(loopTip);
                                    listener.onDone("");
                                }
                            });
                            return;
                        }
                    } else {
                        sameChunkCount[0] = 1;
                    }
                    lastChunk[0] = chunk;
                    lastChunkTime[0] = now;
                }
                listener.onDelta(text);
            }

            @Override
            public void onReasoning(String text) {
                main.removeCallbacks(watchdog[0]);
                main.postDelayed(watchdog[0], watchdogMs);
                listener.onReasoning(text);
            }

            @Override
            public void onToolCalls(final JSONArray toolCalls) {
                if (stopFlag) {
                    return;
                }
                main.removeCallbacks(watchdog[0]);
                final int next = step + 1;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject assistant = new JSONObject(true);
assistant.put("role", "assistant");
                    assistant.put("content", "");
                    // 清洗 tool_calls：确保 function.arguments 是合法 JSON 字符串，避免 provider 400
                    for (Object o : toolCalls) {
                        try {
                            JSONObject tc = (JSONObject) o;
                            JSONObject fn = tc.getJSONObject("function");
                            if (fn != null) {
                                String a = fn.getString("arguments");
                                if (a == null || a.trim().isEmpty()) {
                                    fn.put("arguments", "{}");
                                } else {
                                    JSON.parseObject(a); // 校验；非法则替换
                                    fn.put("arguments", a.trim());
                                }
                            }
                        } catch (Throwable bad) {
                            try {
                                JSONObject tc = (JSONObject) o;
                                JSONObject fn = tc.getJSONObject("function");
                                if (fn != null) fn.put("arguments", "{}");
                            } catch (Throwable ignored) {}
                        }
                    }
                    assistant.put("tool_calls", toolCalls);
                            messages.add(assistant);
                            for (Object o : toolCalls) {
                                if (stopFlag) {
                                    break;
                                }
                                JSONObject tc = (JSONObject) o;
                                JSONObject fn = tc.getJSONObject("function");
                                final String name = fn == null ? "" : fn.getString("name");
                                final String argsStr = fn == null ? "{}" : fn.getString("arguments");
                                final String raw = tc.toJSONString();
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (name == null || name.isEmpty()) {
                                            listener.onToolEvent("→ 调用工具: [名称为空] " + raw);
                                        } else {
                                            listener.onToolEvent("→ 调用工具: " + name + " " + argsStr);
                                        }
                                    }
                                });
                                JSONObject args = new JSONObject(true);
                                try {
                                    if (argsStr != null && !argsStr.trim().isEmpty()) {
                                        JSONObject parsed = JSON.parseObject(argsStr);
                                        if (parsed != null) {
                                            args = parsed;
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                String result = executeTool(ctx, name, args, main, listener);
                                String callId = tc.getString("id");
                                if (callId == null || callId.isEmpty()) {
                                    callId = "call_" + System.nanoTime();
                                    tc.put("id", callId);
                                }
                                JSONObject toolMsg = new JSONObject(true);
                                toolMsg.put("role", "tool");
                                toolMsg.put("tool_call_id", callId);
                                toolMsg.put("content", result);
                                messages.add(toolMsg);
                            }
                            if (stopFlag) {
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        listener.onDone("");
                                    }
                                });
                                return;
                            }
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    runRound(ctx, messages, tools, next, main, listener);
                                }
                            });
                        } catch (final Throwable t) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onError(t);
                                }
                            });
                        }
                    }
                }).start();
            }

            @Override
            public void onDone(String fullText) {
                main.removeCallbacks(watchdog[0]);
                if (fullText == null || fullText.trim().isEmpty()) {
                    if (step < maxSteps && retries[0] < 2 && !stopFlag) {
                        retries[0]++;
                        android.util.Log.i("AiSession", "empty response, retrying round " + step + " (" + retries[0] + "/2)");
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                runRound(ctx, messages, tools, step, main, listener);
                            }
                        });
                        return;
                    }
                    listener.onToolEvent("\n\n[AI 空响应] 请求返回 HTTP 200 但无内容（可能上下文过长或输出被截断，已自动重试 " + retries[0] + " 次）。建议缩短分析范围或在 AI 设置中调大 max_tokens。\n\n");
                }
                listener.onDone(fullText);
            }

            @Override
            public void onError(Throwable t) {
                main.removeCallbacks(watchdog[0]);
                listener.onError(t);
            }
        });
    }

    private static String executeTool(Context ctx, String name, JSONObject args,
                                      final Handler main, final Listener listener) {
        try {
            if (name == null || name.trim().isEmpty()) {
                return "[工具调用异常] function.name 为空：工具名必须填在 function.name 字段（可用工具: " + join(availableTools)
                        + "），不要只写在 arguments 里。请用正确的 function.name 重新发起调用。";
            }
            if ("use_skill".equals(name)) {
                String skill = args.getString("name");
                if (skill == null || skill.isEmpty()) {
                    return "[use_skill] 需要参数 name";
                }
                String content = SkillReader.readSkill(ctx, skill);
                if (content == null) {
                    return "[技能不存在] 可用技能: " + joinArr(SkillReader.listSkills(ctx));
                }
                return McpManager.truncate(content, McpManager.MAX_TOOL_OUTPUT);
            }
            if (name.startsWith("mcp__")) {
                try {
                    return McpManager.truncate(McpManager.callTool(ctx, name, args), McpManager.MAX_TOOL_OUTPUT);
                } catch (Throwable t) {
                    if (isConnectionFailure(t)) {
                        final String server = serverOf(name);
                        stopFlag = true;
                        final String tip = "\n\n[MCP 连接断开] " + server
                                + " 的 MCP 服务连接已断开（" + shortMsg(t)
                                + "），本次会话已终止。请到 MT 管理器侧边栏重新开启 APK MCP 并保持后台运行后再试。\n\n";
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onToolEvent(tip);
                            }
                        });
                        return tip;
                    }
                    return "[工具执行异常] " + name + ": " + shortMsg(t);
                }
            }
            return "[未知工具: " + name + "] 可用工具: " + join(availableTools);
        } catch (Throwable t) {
            return "[工具执行异常] " + name + ": " + shortMsg(t);
        }
    }

    private static String serverOf(String fullName) {
        String[] parts = fullName == null ? null : fullName.split("__", 3);
        if (parts != null && parts.length == 3 && !parts[1].isEmpty()) {
            return parts[1];
        }
        return "MCP";
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m != null && m.length() > 80) {
            m = m.substring(0, 80);
        }
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static boolean isConnectionFailure(Throwable t) {
        if (t instanceof java.net.SocketTimeoutException) return true;
        if (t instanceof java.net.ConnectException) return true;
        if (t instanceof java.net.SocketException) return true;
        if (t instanceof java.io.EOFException) return true;
        String msg = t.getMessage() == null ? "" : t.getMessage();
        return msg.contains("空响应") || msg.contains("timed out") || msg.contains("refused")
                || msg.contains("reset") || msg.contains("Connection closed") || msg.contains("end of stream");
    }

    private static void addUseSkill(Context ctx, JSONArray tools) {
        String[] skills = SkillReader.listSkills(ctx);
        JSONObject params = new JSONObject(true);
        JSONObject props = new JSONObject(true);
        JSONObject nameProp = new JSONObject(true);
        nameProp.put("type", "string");
        nameProp.put("description", "要读取的逆向技能名称");
        JSONArray enumArr = new JSONArray();
        for (String s : skills) {
            enumArr.add(s);
        }
        nameProp.put("enum", enumArr);
        props.put("name", nameProp);
        params.put("type", "object");
        params.put("properties", props);
        JSONArray req = new JSONArray();
        req.add("name");
        params.put("required", req);

        JSONObject fn = new JSONObject(true);
        fn.put("name", "use_skill");
        fn.put("description", "读取内置逆向技能文档（SKILL.md）的内容，包含标准做法、命令与脚本模板。任务不明确时先调用 ai-reverse-workflow。");
        fn.put("parameters", params);
        JSONObject wrapper = new JSONObject(true);
        wrapper.put("type", "function");
        wrapper.put("function", fn);
        tools.add(wrapper);
    }

    private static JSONObject msg(String role, String content) {
        JSONObject m = new JSONObject(true);
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String joinArr(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
