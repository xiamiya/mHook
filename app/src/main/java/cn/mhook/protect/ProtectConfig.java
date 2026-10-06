package cn.mhook.protect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用保护 · 配置模型（档位 + 选项 + 高级参数 + 签名）。
 * TIER: 0 基础 / 1 进阶 / 2 高强度。每一项带 minTier（低于该档位则锁定灰显）。
 */
public class ProtectConfig {

    public static final int TIER_BASIC = 0, TIER_ADV = 1, TIER_STRONG = 2;

    public int tier = TIER_BASIC;

    /** 开关类（key -> 值）。 */
    public final LinkedHashMap<String, Boolean> opts = new LinkedHashMap<>();
    /** 单选项（group -> 选中值）。 */
    public final LinkedHashMap<String, String> choices = new LinkedHashMap<>();
    /** 文本项（key -> 值）。 */
    public final LinkedHashMap<String, String> texts = new LinkedHashMap<>();

    public final SignConfig sign = new SignConfig();

    public boolean on(String key) {
        Boolean b = opts.get(key);
        return b != null && b;
    }

    // ---------------- 选项描述 ----------------
    public static class Opt {
        public final String group, key, title;
        public final int minTier;

        public Opt(String group, String key, String title, int minTier) {
            this.group = group;
            this.key = key;
            this.title = title;
            this.minTier = minTier;
        }
    }

    public static final Opt[] OPTS = {
            // 代码混淆
            new Opt("代码混淆", "str", "字符串混淆", 0),
            new Opt("代码混淆", "cfg", "控制流混淆", 0),
            new Opt("代码混淆", "rename", "类名 / 方法名混淆", 1),
            new Opt("代码混淆", "res", "资源混淆", 0),
            new Opt("代码混淆", "arith", "算术 / 表达式混淆", 2),
            new Opt("代码混淆", "stripdbg", "移除日志 / 调试代码", 1),
            // 抽取 / Dex2C / 加密
            new Opt("抽取 / 加密", "dpt", "DPT 方法抽取", 1),
            new Opt("抽取 / 加密", "aes", "AES DEX 加密", 1),
            // 虚拟化
            new Opt("虚拟化（VMP）", "vmp", "VMP 虚拟化", 2),
            // SO 加固
            new Opt("SO 加固", "so_enc", "SO 代码 / 数据段加密", 1),
            new Opt("SO 加固", "so_obf", "SO 符号 / 代码混淆", 1),
            new Opt("SO 加固", "so_sig", "SO 防篡改 / 防盗用", 1),
            // 运行时防护
            new Opt("运行时防护", "antidbg", "反调试（GDB/IDA/lldb）", 1),
            new Opt("运行时防护", "rasp", "RASP 环境检测", 1),
            new Opt("运行时防护", "root", "Root / Magisk 检测", 1),
            new Opt("运行时防护", "emu", "模拟器检测", 1),
            new Opt("运行时防护", "frida", "Frida / Hook 框架检测", 1),
            new Opt("运行时防护", "antihook", "反注入 / 反 Hook", 2),
            new Opt("运行时防护", "multiopen", "多开 / 双开检测", 2),
            new Opt("运行时防护", "accel", "加速器 / 变速检测", 2),
            new Opt("运行时防护", "a11y", "反无障碍 / UI 注入", 2),
            new Opt("运行时防护", "mem", "内存自校验（.text CRC）", 2),
            new Opt("运行时防护", "fullmem", "防内存 dump / 篡改", 2),
            new Opt("运行时防护", "sig", "运行时签名校验", 1),
            new Opt("运行时防护", "integrity", "完整性校验（DEX / 资源 CRC）", 2),
            new Opt("运行时防护", "secure", "防截屏 / 防录屏", 2),
            // 数据 / 通信
            new Opt("数据 / 通信", "data_enc", "数据 / 配置文件加密", 2),
            new Opt("数据 / 通信", "noleak", "日志防泄漏", 1),
            new Opt("数据 / 通信", "sslpin", "SSL Pinning", 1),
            new Opt("数据 / 通信", "wbc", "通信加固（白盒密钥）", 2),
            new Opt("数据 / 通信", "clip", "剪贴板限制", 2),
            // 打包 / 输出
            new Opt("打包 / 输出", "abi64", "仅保留 arm64-v8a", 0),
            new Opt("打包 / 输出", "dedup", "资源去重 / 压缩", 0),
            new Opt("打包 / 输出", "crashlog", "崩溃日志服务", 1),
            new Opt("打包 / 输出", "slimupdate", "省流量升级", 1),
    };

    public static class Choice {
        public final String key, title;
        public final String[] values;
        public final String def;

        public Choice(String key, String title, String[] values, String def) {
            this.key = key;
            this.title = title;
            this.values = values;
            this.def = def;
        }
    }

    public static final Choice[] CHOICES = {
            new Choice("obfLevel", "混淆强度", new String[]{"轻", "中", "重"}, "中"),
            new Choice("algo", "加密算法", new String[]{"AES-128", "AES-256", "RC4"}, "AES-256"),
            new Choice("scope", "抽取范围", new String[]{"核心包", "全部"}, "核心包"),
            new Choice("keySrc", "密钥来源", new String[]{"随机", "自定义"}, "随机"),
            new Choice("vmpLevel", "VMP 复杂度", new String[]{"标准", "加强", "最强"}, "标准"),
            new Choice("onHit", "命中动作", new String[]{"退出", "静默", "上报"}, "退出"),
            new Choice("zip", "压缩等级", new String[]{"快速", "标准", "最高"}, "标准"),
            new Choice("obfScope", "混淆范围", new String[]{"全部", "自定义"}, "全部"),
    };

    public static class Text {
        public final String key, title, hint;

        public Text(String key, String title, String hint) {
            this.key = key;
            this.title = title;
            this.hint = hint;
        }
    }

    public static final Text[] TEXTS = {
            new Text("seed", "混淆种子", "留空=随机"),
            new Text("keep", "保留规则", "keep 类/方法（反射用）"),
            new Text("exclude", "排除类", "com.a.**,com.b.Keep"),
            new Text("obfInclude", "混淆类", "com.a.**,com.b.*（仅「自定义」范围生效）"),
            new Text("vmpTarget", "虚拟化目标", "com.a.Login;onCreate,com.a.Core.*"),
            new Text("wm", "水印标识", "留空=不加（追踪泄露副本）"),
            new Text("channel", "渠道号", "185285"),
            new Text("mulch", "多渠道", "渠道:编号,渠道2:编号2"),
                new Text("outDir", "输出目录", "Download/mhook_dump/"),
    };

    // ---------------- 档位预设 ----------------
    private static String[] advOn() {
        return new String[]{"rename", "stripdbg", "dpt", "aes", "so_enc", "so_obf", "so_sig",
                "antidbg", "rasp", "root", "emu", "frida", "sig", "noleak", "sslpin", "crashlog", "slimupdate"};
    }

    private static String[] strongOn() {
        return new String[]{"str", "cfg", "rename", "res", "arith", "stripdbg", "dpt", "aes", "vmp",
                "so_enc", "so_obf", "so_sig", "antidbg", "rasp", "root", "emu", "frida", "antihook",
                "multiopen", "accel", "a11y", "mem", "fullmem", "sig", "integrity", "secure", "data_enc",
                "noleak", "sslpin", "wbc", "clip", "crashlog", "slimupdate"};
    }

    public void applyPreset(int t) {
        tier = t;
        for (Map.Entry<String, Boolean> e : opts.entrySet()) e.setValue(false);
        if (t == TIER_BASIC) {
            set("str", true);
            set("cfg", true);
            set("res", true);
            set("dedup", true);
        } else if (t == TIER_ADV) {
            set("str", true);
            set("cfg", true);
            set("res", true);
            for (String k : advOn()) set(k, true);
            set("dedup", true);
        } else {
            for (String k : strongOn()) set(k, true);
            set("dedup", true);
        }
        choices.put("obfLevel", t == TIER_STRONG ? "重" : "中");
    }

    private void set(String k, boolean v) {
        if (opts.containsKey(k)) opts.put(k, v);
    }

    public static ProtectConfig createDefault() {
        return createDefault(TIER_BASIC);
    }

    public static ProtectConfig createDefault(int tier) {
        ProtectConfig c = new ProtectConfig();
        for (Opt o : OPTS) c.opts.put(o.key, false);
        for (Choice ch : CHOICES) c.choices.put(ch.key, ch.def);
        for (Text t : TEXTS) c.texts.put(t.key, "");
        c.applyPreset(tier);
        return c;
    }

    /** 已启用选项的 key 列表（用于日志/评估）。 */
    public List<String> enabledKeys() {
        List<String> r = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : opts.entrySet()) if (e.getValue()) r.add(e.getKey());
        return r;
    }
}
