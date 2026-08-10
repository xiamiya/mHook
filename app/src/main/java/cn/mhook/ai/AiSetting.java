package cn.mhook.ai;

import android.content.Context;
import android.content.SharedPreferences;

public class AiSetting {

    private static final String PREFS = "ai_setting";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_MAX_TOKENS = "max_tokens";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_MAX_STEPS = "max_steps";

    private static SharedPreferences sp(Context c){
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String baseUrl(Context c){ return sp(c).getString(KEY_BASE_URL, ""); }
    public static String apiKey(Context c){ return sp(c).getString(KEY_API_KEY, ""); }
    public static String model(Context c){ return sp(c).getString(KEY_MODEL, ""); }
    public static int maxTokens(Context c){ return sp(c).getInt(KEY_MAX_TOKENS, 2048); }
    public static int timeout(Context c){ return sp(c).getInt(KEY_TIMEOUT, 120000); }
    public static int maxSteps(Context c){ return sp(c).getInt(KEY_MAX_STEPS, 32); }

    public static void setBaseUrl(Context c, String v){ sp(c).edit().putString(KEY_BASE_URL, v==null?"":v.trim()).apply(); }
    public static void setApiKey(Context c, String v){ sp(c).edit().putString(KEY_API_KEY, v==null?"":v.trim()).apply(); }
    public static void setModel(Context c, String v){ sp(c).edit().putString(KEY_MODEL, v==null?"":v.trim()).apply(); }
    public static void setMaxTokens(Context c, int v){ sp(c).edit().putInt(KEY_MAX_TOKENS, v).apply(); }
    public static void setTimeout(Context c, int v){ sp(c).edit().putInt(KEY_TIMEOUT, v).apply(); }
    public static void setMaxSteps(Context c, int v){ sp(c).edit().putInt(KEY_MAX_STEPS, v).apply(); }
}
