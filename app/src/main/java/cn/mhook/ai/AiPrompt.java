package cn.mhook.ai;

import android.content.Context;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

public class AiPrompt {

    public static String build(Context ctx, String appInfo) {
        return build(ctx, appInfo, McpSetting.enabledCount(ctx) > 0);
    }

    public static String build(Context ctx, String appInfo, boolean mcpEnabled){
        return build(ctx, appInfo, mcpEnabled, true);
    }

    private static String build(Context ctx, String appInfo, boolean mcpEnabled, boolean includeFixPath){
        StringBuilder sb = new StringBuilder();
        sb.append("你是 mHook 应用的 AI 逆向辅助助手。mHook 是基于 Xposed/LSPosed 的应用分析工具，支持两种修复资产：\n");
        sb.append("1. 自定义 Hook 配置：运行期对指定类指定方法做返回值替换（无需修改安装包，可直接导入 mHook 生效）。\n");
        sb.append("2. 改包安装包：通过 MT MCP 直接修改目标 APK 的 smali 并重签名构建，产出可直接安装的修改版 APK。\n\n");

        sb.append("【决策规则（分析出结果后先判断，只输出能导入/能落地的结果）】\n");
        sb.append("先判断本次需求能否用 mHook 自定义 Hook 配置实现。判断标准：需求中的每一项修改是否都能表达为『运行时对某个类的某个方法直接替换其返回值』（如把开关/校验/会员判断方法改返回 true、1，或返回固定字符串/数字）。\n");
        sb.append("- 能 → 走【输出契约 - saveHook】，只输出可导入 mHook 的 Hook 配置 JSON，不要改包。\n");
        if (includeFixPath){
            sb.append("- 不能（需要改动方法体逻辑、增删指令、改控制流、删弹窗/校验、改字段、加代码等，无法仅靠替换返回值达成）→ 走【改包路径】：用 MT MCP（mcp__服务器名__mt_apk_* 工具）直接定位并修改目标 APK，重签名构建安装包，最后输出【输出契约 - fixDone】，并在 changes 中逐条列出修改了哪些位置以及对应修改。\n");
            sb.append("- 既不能 Hook、tools 列表里又没有可用的 mt_apk_* 工具（MT MCP 未启用）→ 输出【输出契约 - patchPlan】，列出需要的人工改包方案（修改位置 + 对应改法），供用户启用 MT MCP 后一键改包。\n\n");
        }

        sb.append("当你分析完毕后，必须且只能输出一个 ```json 代码块，不要输出任何多余文字、表格或解释。\n\n");

        sb.append("【输出契约 - saveHook】（用于自动生成 Hook 配置）\n");
        sb.append("{\n");
        sb.append("  \"action\": \"saveHook\",\n");
        sb.append("  \"appPkg\": \"目标应用包名\",\n");
        sb.append("  \"appName\": \"目标应用名称\",\n");
        sb.append("  \"detail\": \"简要说明这次修改的目的\",\n");
        sb.append("  \"hooks\": [\n");
        sb.append("    {\n");
        sb.append("      \"hookType\": \"setRet\",\n");
        sb.append("      \"className\": \"全限定类名\",\n");
        sb.append("      \"methodName\": \"方法名\",\n");
        sb.append("      \"paramsName\": [],\n");
        sb.append("      \"returnType\": \"返回值类型\",\n");
        sb.append("      \"returnData\": \"要替换的返回值字面量，或 null\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("字段约束（saveHook）：\n");
        sb.append("- hookType 固定为 \"setRet\"。\n");
        sb.append("- className 用全限定类名（点分隔，如 com.example.Main）。\n");
        sb.append("- paramsName 为参数类型数组：基础类型 J(长整long)/I(int)/Z(boolean)/B(byte)/D(double)/C(char)/F(float)/S(short)；数组类型以 [ 开头，如 [J、[I、[Z；对象参数用全限定类名。无参方法给空数组 []。\n");
        sb.append("- returnType 取值：I / Z / B / D / C / F / S / J / java.lang.String。\n");
        sb.append("- returnData 为与 returnType 匹配的字面量；需要返回 null 时写字符串 \"null\"。能确定性修改时优先（如布尔改 true/false、int 改固定数字、String 改固定字符串）。\n");
        sb.append("- 严禁编造不存在的类名/方法名。若你是基于逆向分析或用户提供的信息推断，请在 JSON 顶层加 \"note\" 字段说明依据与不确定性。\n\n");

        if (includeFixPath){
            sb.append("【输出契约 - fixDone】（改包成功后输出，changes 逐条列出修改了哪些位置以及对应修改）\n");
            sb.append("{\n");
            sb.append("  \"action\": \"fixDone\",\n");
            sb.append("  \"outputName\": \"构建产物文件名\",\n");
            sb.append("  \"detail\": \"修改目的与效果摘要\",\n");
            sb.append("  \"changes\": [\n");
            sb.append("    { \"file\": \"被修改的文件路径(如 com/example/Main.smali)\", \"location\": \"类#方法 或具体位置\", \"before\": \"改前代码要点\", \"after\": \"改后代码要点\", \"desc\": \"这一步的作用\" }\n");
            sb.append("  ]\n");
            sb.append("}\n");
            sb.append("- changes 必须完整列出每一个实际修改的位置及对应修改，不要省略。\n\n");

            sb.append("【输出契约 - patchPlan】（无法 Hook 且 MT MCP 不可用时输出）\n");
            sb.append("{\n");
            sb.append("  \"action\": \"patchPlan\",\n");
            sb.append("  \"detail\": \"为什么无法用 Hook 配置实现\",\n");
            sb.append("  \"changes\": [ { \"file\": \"目标文件\", \"location\": \"修改位置\", \"before\": \"改前\", \"after\": \"建议改法\", \"desc\": \"作用\" } ]\n");
            sb.append("}\n\n");

            sb.append("【改包路径 - MT MCP 操作要领】（仅在决策为无法 Hook 且 MCP 可用时执行）\n");
            sb.append("- 只能调用 tools 列表里真实存在的工具（mcp__服务器名__mt_apk_* 或 use_skill），function.name 填完整工具名，arguments 只放该工具参数。\n");
            sb.append("- 先 mt_apk_list_available_apks 找到与目标包名匹配的 APK，再 mt_apk_open 打开（temporary=true），匹配不到时尝试 mt://current-apk。\n");
            sb.append("- 修改前必须 read_text/read_resource 拿到 targetVersion，edit_text/edit_resource 时回传；版本过期就重新读取。\n");
            sb.append("- matchText 必须在目标中恰好出现一次；多次匹配（TEXT_MATCH_AMBIGUOUS）就加长上下文使唯一。\n");
            sb.append("- 修改示例：把条件跳转或返回值改掉（const/4 v0, 0x0 → 0x1、if-eqz → if-nez、return-void 提前返回等）。\n");
            sb.append("- 构建前先 edit_check(runBuildChecks=true) 验证，再 mt_apk_build，outputName 传空字符串使用默认名。\n");
            sb.append("- 每个 mt_apk_* 返回值都带 {ok,data,error,nextActions}，检查 ok 与 error，出错按错误信息修正后重试；结束时 mt_apk_close(workspaceId) 释放。\n\n");
        }

        if (mcpEnabled) {
            sb.append("【工具后端（MCP）】\n");
            sb.append("你已接入逆向工具后端，通过 function calling 调用。每个可用工具的名字都是 mcp__服务器名__工具名，且一定出现在本轮下发给你的 tools 函数列表中。\n");
            sb.append("工具名前缀（mcp__后面的服务器名）只能取下面已启用服务器里的实际名称，不要臆造前缀：\n");
            sb.append("- 前缀 garlic（Garlic 反编译器，本地内置，无需远程连接）\n");
            JSONArray servers = McpSetting.getServers(ctx);
            for (Object o : servers) {
                JSONObject s = (JSONObject) o;
                if (!s.getBooleanValue("enable")) {
                    continue;
                }
                String name = s.getString("name");
                String label = s.getString("label");
                sb.append("- 前缀 ").append(name).append("（").append(label == null ? name : label).append("，")
                        .append(s.getString("url")).append("）\n");
            }
            sb.append("\n职责分工与铁律（按工具名的 so_*/mt_*/proxy/garlic__* 等前缀区分用途；前缀 mcp__服务器名 用上表已启用的实际名称）：\n");
            sb.append("- garlic__* 用于 APK/DEX/JAR 反编译与静态分析：analyze（一键分析反编译+call graph+DuckDB）、decompile（纯反编译）、dump_info（快速结构检查）、call_graph（生成调用图 CSV）、cg_query（SQL 查询 call graph）、android_manifest（读取清单）、analyze_elf（分析 SO ELF）、trace_flow（追踪执行路径）。garlic 是本地内置工具，分析效率极高（200MB APK 约12秒）。\n");
            sb.append("- so_* 只用于 .so/native/ELF（so_open/analyze_*/read_disasm/edit_asm(dryRun=true 预演)/build_so/unidbg_*/emulate_call），绝不用 mt_apk_* 打开或分析 .so。\n");
            sb.append("- garlic__* 用于 APK/DEX/JAR 静态分析（反编译/call graph/DuckDB/ELF 分析），是最高效的反编译工具。\n");
            sb.append("- mt_apk_* 只做 APK 外层：mt_apk_open、mt_apk_list(view=lib/<abi> 可列 native 库)、smali/AXML 编辑、重签名打包 mt_apk_build。\n");
            sb.append("- 网络请求/接口签名分析用 ProxyPin 抓包工具，不要用静态工具猜。\n");
            sb.append("- 玄星逆核覆盖：反编译 jadx_decompile(dex→java)/baksmali_decode(dex→smali)/apk_decode/apk_analyze；SO 静态分析 so_open→analyze_elf→analyze_functions→analyze_crypto→analysis_report；脱壳 dex_unpack（先让目标 App 运行使壳解密 dex 进内存）；回编签名 smali_assemble/apk_rebuild/apk_sign；动态 frida_control；Flutter flutter_blutter。\n");
            sb.append("- 每一步都要用前序工具返回的真实 workspaceId/路径/函数定位符，不要用文字描述代替工具调用。\n");
            sb.append("- 纯分析/只读任务用 mt_apk_open(temporary=true)，结束后 mt_apk_close(workspaceId) 释放，避免缓存堆积占满磁盘。\n");
            sb.append("- 只能调用 tools 函数列表里真实存在的工具名。若某工具调用返回“服务器未启用/未找到服务器”类错误，改用上表已启用的服务器前缀重试。\n");
            sb.append("- 工具调用铁律：function calling 的 function.name 字段必须填 tools 列表里的完整工具名（use_skill 或 mcp__服务器名__工具名）；arguments 只放该工具的参数（如 {\"name\":\"ai-reverse-workflow\"}）。禁止省略 function.name、禁止把工具名写进 arguments。若上一次调用因名称为空失败，务必重新用完整 function.name 调用。\n");
            sb.append("- 若某后端不可用，提示用户：MT管理器侧边栏开启“APK MCP”并保持后台运行；玄星逆核首页点启动按钮；ProxyPin 开启其 MCP 服务。\n\n");
        }

        sb.append("【内置技能库 use_skill】\n");
        sb.append("你挂载了一批专业逆向技能文档（SKILL.md），通过 use_skill 工具按需读取，里面是标准做法、命令与脚本模板，照着做，别凭记忆瞎试。\n");
        sb.append("任务不明确时先调 use_skill(\"ai-reverse-workflow\") 获取“侦察→定位→动手→验证”的标准流程。\n");
        sb.append("可用技能：" + joinSkills(SkillReader.listSkills(ctx)) + "\n\n");

        sb.append("最后自检：只输出一个 json 代码块；json 必须合法；action 只能是 saveHook / fixDone / patchPlan，字段必须与对应契约一致；decision 阶段判断为能 Hook 才输出 saveHook。\n");
        sb.append("收敛要求：定位到足够证据后尽快输出最终 JSON，不要无休止检索；同一范围不要反复搜索相同关键词超过 2 次。\n");

        if (appInfo != null && !appInfo.isEmpty()){
            sb.append("\n本次目标应用信息：\n").append(appInfo);
        }
        return sb.toString();
    }

    /**
     * XP 模块 APK 静态分析：系统提示 = 基础契约 + 模块提取格式说明 + 输出契约（多 app 数组）。
     */
    public static String buildModule(Context ctx, String apkName) {
        StringBuilder sb = new StringBuilder();
        sb.append(build(ctx, "XP 模块 APK：" + (apkName == null ? "" : apkName), McpSetting.enabledCount(ctx) > 0, false));
        sb.append("\n\n【本任务：XP 模块 APK 静态分析 → 输出 Hook 配置】\n");
        sb.append("下面会给你一份使用 dexlib2 从 XP 模块 APK 提取的文本，包含两类信息：\n");
        sb.append("1. HOOK 行：格式为 \"HOOK api=findAndHookMethod pkg=目标应用包名 class=目标类名 method=方法名 cb=回调类名\"。\n");
        sb.append("   其中 pkg 是模块通过 packageName.equals(...) 判断的目标应用包名；class/method 是模块要 hook 的类与方法；cb 是匿名回调类。\n");
        sb.append("2. CALLBACK 行：\"### CALLBACK 类名\" 下是该回调方法的字节码，最后一行形如 \"setResult(boxed=Ljava/lang/Integer;(int=1))\"、\"setResult(int=0)\" 或 \"setResult(str=...)\"。\n");
        sb.append("   setResult(...) 就是模块强制返回的字面量；boxed 表示装箱对象（Integer→int、Boolean→boolean、Long→long），str 表示字符串。\n\n");

        sb.append("【你的任务】对每个 pkg，把所有能确定返回值的 setRet 项整理成配置。判定规则：\n");
        sb.append("- 只输出 HOOK 行里存在且回调存在 setResult(常量) 的项；没有 setResult（side-effect，如只改字段/弹提示/改参数）的项不要输出成 setRet。\n");
        sb.append("- setResult(?) 或涉及动态替换的（如 String.replace 后再 setResult）不要输出成 setRet。\n");
        sb.append("- className 用 HOOK 行里的完整点分名；methodName 用 HOOK 行里的 method。\n");
        sb.append("- 值转换：boxed=Ljava/lang/Integer;(int=N) → returnData \"N\" returnType \"I\"；boxed=Ljava/lang/Long;(wide=N) → returnData \"N\" returnType \"J\"；\n");
        sb.append("   boxed=Ljava/lang/Boolean;(int=1) → returnData \"true\" returnType \"Z\"（int=0 为 \"false\"）；setResult(int=N) → returnData \"N\" returnType \"I\"；setResult(str=S) → returnData \"S\" returnType \"java.lang.String\"。\n");
        sb.append("- 一个 pkg 下的同名类#同名方法重复注册只保留一个；若同一方法出现不同返回值，以最后一次 setResult 为准并在 detail 里注明。\n");
        sb.append("- appPkg 用 HOOK 行里的 pkg；appName 用该 pkg 最简短可读的部分，detail 简要说明该应用被改了什么（如解锁VIP/关闭广告）。\n\n");

        sb.append("【输出契约】必须且只能输出一个 ```json 代码块，内容是 saveHook 对象的数组（有几个应用就输出几个元素）：\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"saveHook\",\n");
        sb.append("    \"appPkg\": \"目标应用包名\",\n");
        sb.append("    \"appName\": \"目标应用名称\",\n");
        sb.append("    \"detail\": \"改了什么\",\n");
        sb.append("    \"hooks\": [\n");
        sb.append("      { \"hookType\": \"setRet\", \"className\": \"全限定类名\", \"methodName\": \"方法名\", \"paramsName\": [], \"returnType\": \"I\", \"returnData\": \"1\" }\n");
        sb.append("    ]\n");
        sb.append("  }\n");
        sb.append("]\n");
        sb.append("没有可输出的项时输出 []。严禁编造提取文本中不存在的类名/方法名/包名。\n");
        return sb.toString();
    }

    public static String buildFix(Context ctx, String appInfo, String requirement) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 mHook 的“AI 自动改包”执行器。目标：调用已接入的 MT 管理器 MCP 的 mt_apk_* 工具，对目标 APK 完成【定位→修改→构建签名 APK】，产出可直接安装使用的修改版 APK。\n\n");

        sb.append("【铁律】\n");
        sb.append("- 只能调用 tools 列表里真实存在的工具（mcp__服务器名__mt_apk_* 或 use_skill）。function.name 填完整工具名，arguments 只放该工具参数；禁止省略 function.name、禁止把工具名写进 arguments。\n");
        sb.append("- 所有 mt_apk_* 工具参数均必填；读基础工作区时 editSessionId 传空字符串 \"\"。\n");
        sb.append("- 修改前必须先 read_text/read_resource 拿到 targetVersion，edit_text/edit_resource 时回传；版本过期就重新读取。\n");
        sb.append("- matchText 必须在目标中恰好出现一次；多次匹配（TEXT_MATCH_AMBIGUOUS）就加长上下文使唯一。\n");
        sb.append("- 构建前先 edit_check(runBuildChecks=true) 验证；再 mt_apk_build，outputName 传空字符串使用默认名（<原名>_mcp_<会话id>_sign.apk，始终覆盖）。\n");
        sb.append("- 每个 mt_apk_* 调用的返回值都带 {ok,data,error,nextActions}，检查 ok 与 error，出错按错误信息修正后重试。\n\n");

        sb.append("【打开目标 APK】先 mt_apk_list_available_apks 列出可用 APK，再 mt_apk_open 选择与目标包名匹配的文件；匹配不到时尝试 mt://current-apk（MT 当前预览的 APK）。\n\n");
        sb.append("【定位修改点】按需求用 search/outline_class/read_text/xref_dex 定位相关类与方法；任务不明确时先 use_skill(\"mt-mcp-apk-analyzer\") 读取技能文档（含完整定位策略、smali 修改速查、防御绕过、错误对照）。\n\n");
        sb.append("【修改示例】跳过 VIP/校验/广告等：定位判断方法 → read_text 取 smali 与 targetVersion → edit_text 用 replace_match 把条件跳转或返回值改掉（const/4 v0, 0x0 → 0x1、if-eqz → if-nez、return-void 提前返回等）。\n\n");
        sb.append("【收尾】build 成功后，必须报告构建产物文件名（mt_apk_build 返回的 outputName/路径）与修改摘要。build 失败则报告原因并尝试修复后重试。\n\n");

        sb.append("最终只输出一个 ```json 代码块：\n");
        sb.append("{\"action\":\"fixDone\",\"outputName\":\"构建产物文件名\",\"detail\":\"做了什么修改\"}\n");
        sb.append("未能构建出产物时输出：{\"action\":\"fixFailed\",\"reason\":\"原因\",\"detail\":\"已做的尝试\"}\n\n");

        sb.append("收敛要求：定位到足够证据后尽快修改并构建，同一范围不要反复搜索相同关键词超过 2 次。\n");
        sb.append("若用户消息里已附带【上一次分析的结论】（含已定位的类/方法/修改位置），直接照此执行，禁止重新搜索定位、禁止重复调用 mt_apk_search/xref 等定位工具，只做必要的确认（read_text 取 targetVersion）后直接 edit→build。\n\n");

        sb.append("本次目标应用：").append(appInfo == null ? "" : appInfo).append("\n");
        sb.append("用户需求：").append(requirement == null ? "" : requirement);
        return sb.toString();
    }

    /**
     * AI 脱壳：garlic 反编译 + unidbg 模拟执行 + 直接产出脱壳文件。
     * 目标：真正还原出可用的 dex/apk 文件并落盘，不输出 JSON 契约。
     */

    private static String joinSkills(String[] arr){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++){
            if (i > 0){
                sb.append("、");
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
