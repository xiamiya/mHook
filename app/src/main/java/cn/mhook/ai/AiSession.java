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

        void onToolEvent(String text);

        void onDone(String finalText);

        void onError(Throwable t);
    }

    public static final int MAX_STEPS = 32;
    private static volatile int maxSteps = MAX_STEPS;
    private static volatile boolean stopFlag = false;
    private static final List<String> availableTools = new ArrayList<String>();

    public static void stop() {
        stopFlag = true;
    }

    public static void run(final Context ctx, final String system, final String user, final Listener listener) {
        stopFlag = false;
        maxSteps = AiSetting.maxSteps(ctx);
        if (maxSteps <= 0) maxSteps = MAX_STEPS;
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
        AiClient.complete(ctx, messages, tools, new AiClient.Listener() {
            @Override
            public void onDelta(String text) {
                listener.onDelta(text);
            }

            @Override
            public void onToolCalls(final JSONArray toolCalls) {
                if (stopFlag) {
                    return;
                }
                final int next = step + 1;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject assistant = new JSONObject(true);
                            assistant.put("role", "assistant");
                            assistant.put("content", "");
                            assistant.put("tool_calls", toolCalls);
                            messages.add(assistant);
                            for (Object o : toolCalls) {
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
                                String result = executeTool(ctx, name, args);
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
                listener.onDone(fullText);
            }

            @Override
            public void onError(Throwable t) {
                listener.onError(t);
            }
        });
    }

    private static String executeTool(Context ctx, String name, JSONObject args) {
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
                return McpManager.truncate(McpManager.callTool(ctx, name, args), McpManager.MAX_TOOL_OUTPUT);
            }
            return "[未知工具: " + name + "] 可用工具: " + join(availableTools);
        } catch (Throwable t) {
            return "[工具执行异常] " + name + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
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
