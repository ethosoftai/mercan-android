package ai.mercan.app;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings in one place (SharedPreferences backed). */
public final class Prefs {
    private static final String NAME = "mercan";

    public static final float DEF_TEMPERATURE = 0.7f;
    public static final float DEF_TOP_P = 0.95f;
    public static final int DEF_TOP_K = 40;
    public static final float DEF_REPEAT = 1.10f;
    public static final int DEF_MAX_TOKENS = 512;

    public int threads;
    public float temperature;
    public float topP;
    public int topK;
    public float repeatPenalty;
    public int maxTokens;
    public boolean thinkingMode;
    public boolean typewriter;
    public String systemPrompt;
    public boolean onboarded;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        threads = sp.getInt("threads", 0);
        temperature = sp.getFloat("temperature", DEF_TEMPERATURE);
        topP = sp.getFloat("top_p", DEF_TOP_P);
        topK = sp.getInt("top_k", DEF_TOP_K);
        repeatPenalty = sp.getFloat("repeat", DEF_REPEAT);
        maxTokens = sp.getInt("max_tokens", DEF_MAX_TOKENS);
        thinkingMode = sp.getBoolean("thinking", false);
        typewriter = sp.getBoolean("typewriter", true);
        systemPrompt = sp.getString("system_prompt", "");
        onboarded = sp.getBoolean("onboarded", false);
    }

    public void save() {
        sp.edit()
            .putInt("threads", threads)
            .putFloat("temperature", temperature)
            .putFloat("top_p", topP)
            .putInt("top_k", topK)
            .putFloat("repeat", repeatPenalty)
            .putInt("max_tokens", maxTokens)
            .putBoolean("thinking", thinkingMode)
            .putBoolean("typewriter", typewriter)
            .putString("system_prompt", systemPrompt == null ? "" : systemPrompt)
            .putBoolean("onboarded", onboarded)
            .apply();
    }

    public void resetGeneration() {
        temperature = DEF_TEMPERATURE;
        topP = DEF_TOP_P;
        topK = DEF_TOP_K;
        repeatPenalty = DEF_REPEAT;
        maxTokens = DEF_MAX_TOKENS;
    }
}
