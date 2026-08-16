package cn.mhook.ai;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ResultParser {

    public static String extractJson(String text){
        if (text == null){
            return null;
        }
        Matcher m = Pattern.compile("```json\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE).matcher(text);
        String last = null;
        while (m.find()){
            last = m.group(1).trim();
        }
        if (last != null && !last.isEmpty()){
            return last;
        }
        m = Pattern.compile("```\\s*([\\s\\S]*?)```").matcher(text);
        last = null;
        while (m.find()){
            last = m.group(1).trim();
        }
        if (last != null && !last.isEmpty()){
            return last;
        }
        String t = text.trim();
        if (t.startsWith("{") && t.endsWith("}")){
            return t;
        }
        return null;
    }

    public static JSONObject parseRaw(String text) throws Exception {
        String json = extractJson(text);
        if (json == null){
            throw new Exception("未在 AI 输出中找到 ```json 代码块");
        }
        JSONObject obj;
        try {
            obj = JSONObject.parseObject(json);
        }catch (Throwable e){
            throw new Exception("JSON 解析失败：" + e.getMessage());
        }
        if (obj == null){
            throw new Exception("JSON 为空");
        }
        return obj;
    }

    public static JSONObject parseAndNormalize(String text) throws Exception {
        String json = extractJson(text);
        if (json == null){
            throw new Exception("未在 AI 输出中找到 ```json 代码块");
        }
        JSONObject obj;
        try {
            obj = JSONObject.parseObject(json);
        }catch (Throwable e){
            throw new Exception("JSON 解析失败：" + e.getMessage());
        }
        if (obj == null){
            throw new Exception("JSON 为空");
        }
        String action = obj.getString("action");
        if (action == null || action.isEmpty()){
            if (obj.containsKey("hooks")){
                action = "saveHook";
            }else if (obj.containsKey("patches")){
                action = "saveFix";
            }
        }
        if (action == null){
            throw new Exception("无法识别 action 字段（需要 saveHook 或 saveFix）");
        }
        obj.put("action", action);

        if ("saveHook".equals(action)){
            String pkg = obj.getString("appPkg");
            if (pkg == null || pkg.trim().isEmpty()){
                throw new Exception("saveHook 缺少 appPkg");
            }
            JSONArray hooks = obj.getJSONArray("hooks");
            if (hooks == null || hooks.isEmpty()){
                throw new Exception("saveHook 的 hooks 为空");
            }
            JSONArray normalized = new JSONArray();
            for (Object o : hooks){
                JSONObject h;
                try {
                    h = (o instanceof JSONObject) ? (JSONObject) o : JSONObject.parseObject(o.toString());
                }catch (Throwable e){
                    h = null;
                }
                if (h == null){
                    continue;
                }
                String cls = h.getString("className");
                String mtd = h.getString("methodName");
                String rt = h.getString("returnType");
                if (cls == null || cls.trim().isEmpty()){
                    throw new Exception("hooks 中存在缺少 className 的条目");
                }
                if (mtd == null || mtd.trim().isEmpty()){
                    throw new Exception("hooks 中 [" + cls + "] 缺少 methodName");
                }
                if (rt == null || rt.trim().isEmpty()){
                    throw new Exception("hooks 中 [" + cls + "." + mtd + "] 缺少 returnType");
                }
                if (!h.containsKey("returnData")){
                    throw new Exception("hooks 中 [" + cls + "." + mtd + "] 缺少 returnData");
                }
                h.put("hookType", "setRet");
                Object params = h.get("paramsName");
                if (!(params instanceof JSONArray)){
                    try {
                        h.put("paramsName", JSONArray.parseArray(String.valueOf(params)));
                    }catch (Throwable e){
                        h.put("paramsName", new JSONArray());
                    }
                }
                normalized.add(h);
            }
            if (normalized.isEmpty()){
                throw new Exception("hooks 解析后为空");
            }
            obj.put("hooks", normalized);
        }else if ("saveFix".equals(action)){
            String pkg = obj.getString("appPkg");
            if (pkg == null || pkg.trim().isEmpty()){
                throw new Exception("saveFix 缺少 appPkg");
            }
            if (!obj.containsKey("mode")){
                obj.put("mode", 2);
            }
            JSONObject patches = obj.getJSONObject("patches");
            if (patches == null || patches.isEmpty()){
                throw new Exception("saveFix 的 patches 为空（模式2 需要补丁源码）");
            }
        }else {
            throw new Exception("未知 action：" + action);
        }
        return obj;
    }

    public static JSONObject buildHookConfig(String appPkg, String appName, String appVer, JSONObject parsed){
        JSONObject cfg = new JSONObject(true);
        cfg.put("appPkg", appPkg);
        cfg.put("appName", appName);
        cfg.put("appVer", appVer == null ? "" : appVer);
        cfg.put("author", "AI");
        String detail = parsed.getString("detail");
        cfg.put("detail", detail == null || detail.isEmpty() ? "AI生成" : detail);
        cfg.put("hooks", parsed.getJSONArray("hooks"));
        return cfg;
    }

    /**
     * 解析多个 saveHook 结果（XP 模块分析常用）。接受单个对象或对象数组。
     */
    public static List<JSONObject> parseHookApps(String text) throws Exception {
        String json = extractJson(text);
        if (json == null){
            throw new Exception("未在 AI 输出中找到 ```json 代码块");
        }
        JSONArray arr = null;
        try {
            JSONObject single = JSONObject.parseObject(json);
            arr = new JSONArray();
            arr.add(single);
        } catch (Throwable t) {
            arr = JSONArray.parseArray(json);
        }
        if (arr == null || arr.isEmpty()){
            throw new Exception("AI 结果为空");
        }
        List<JSONObject> out = new java.util.ArrayList<JSONObject>();
        int skipped = 0;
        for (Object o : arr){
            String s = (o instanceof JSONObject) ? ((JSONObject) o).toJSONString() : String.valueOf(o);
            JSONObject item;
            try {
                item = JSONObject.parseObject(s);
            } catch (Throwable t) {
                skipped++;
                continue;
            }
            if (item == null){
                skipped++;
                continue;
            }
            JSONArray h = item.getJSONArray("hooks");
            if (h == null || h.isEmpty()){
                skipped++;
                continue;
            }
            out.add(parseAndNormalize(s));
        }
        if (out.isEmpty()){
            throw new Exception("AI 结果中没有可导入的 hook 配置" + (skipped > 0 ? "（跳过 " + skipped + " 个空配置）" : ""));
        }
        return out;
    }
}
