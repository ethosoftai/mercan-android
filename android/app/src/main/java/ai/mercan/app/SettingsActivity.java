package ai.mercan.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.Locale;

/** Settings screen. Returns RESULT_OK with flags describing what changed. */
public class SettingsActivity extends AppCompatActivity {
    public static final String EXTRA_BACKEND = "backend";
    public static final String EXTRA_DEFAULT_THREADS = "default_threads";
    public static final String RESULT_THREADS_CHANGED = "threads_changed";
    public static final String RESULT_SYSTEM_CHANGED = "system_changed";
    public static final String RESULT_IMPORT_MODEL = "import_model";
    public static final String RESULT_CHATS_DELETED = "chats_deleted";

    private Prefs prefs;
    private EditText systemPrompt, temperature, maxTokens, topP, topK, repeat, threads;
    private Switch swThinking, swTypewriter;
    private boolean importRequested = false;
    private boolean chatsDeleted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(sb.left, sb.top, sb.right, Math.max(sb.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(!night);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(!night);
        if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);

        prefs = new Prefs(this);
        systemPrompt = findViewById(R.id.system_prompt);
        temperature = findViewById(R.id.temperature);
        maxTokens = findViewById(R.id.max_tokens);
        topP = findViewById(R.id.top_p);
        topK = findViewById(R.id.top_k);
        repeat = findViewById(R.id.repeat);
        threads = findViewById(R.id.threads);
        swThinking = findViewById(R.id.sw_thinking);
        swTypewriter = findViewById(R.id.sw_typewriter);

        systemPrompt.setText(prefs.systemPrompt);
        temperature.setText(fmt(prefs.temperature));
        maxTokens.setText(String.valueOf(prefs.maxTokens));
        topP.setText(fmt(prefs.topP));
        topK.setText(String.valueOf(prefs.topK));
        repeat.setText(fmt(prefs.repeatPenalty));
        threads.setText(String.valueOf(prefs.threads));
        swThinking.setChecked(prefs.thinkingMode);
        swTypewriter.setChecked(prefs.typewriter);

        int defThreads = getIntent().getIntExtra(EXTRA_DEFAULT_THREADS, 4);
        ((TextView) findViewById(R.id.threads_label)).setText(getString(R.string.set_threads, defThreads));

        String backend = getIntent().getStringExtra(EXTRA_BACKEND);
        File model = ModelManager.modelFile(this);
        TextView info = findViewById(R.id.model_info);
        if (model.isFile()) {
            info.setText(getString(R.string.model_info, "Mercan 0.8B SFT (Q4_K_M)",
                    String.format(Locale.getDefault(), "%.0f MB", model.length() / 1048576.0),
                    backend == null || backend.isEmpty() ? "-" : backend));
        } else {
            info.setText(R.string.model_missing);
        }
        ((TextView) findViewById(R.id.version)).setText(getString(R.string.version, BuildConfig.VERSION_NAME));

        findViewById(R.id.back).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.save).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.reset_generation).setOnClickListener(v -> {
            temperature.setText(fmt(Prefs.DEF_TEMPERATURE));
            maxTokens.setText(String.valueOf(Prefs.DEF_MAX_TOKENS));
            topP.setText(fmt(Prefs.DEF_TOP_P));
            topK.setText(String.valueOf(Prefs.DEF_TOP_K));
            repeat.setText(fmt(Prefs.DEF_REPEAT));
        });
        findViewById(R.id.import_model).setOnClickListener(v -> { importRequested = true; saveAndFinish(); });
        findViewById(R.id.delete_all).setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(R.string.delete_all_chats)
            .setMessage(R.string.delete_all_confirm)
            .setPositiveButton(R.string.delete, (d, w) -> {
                try { new ChatStore(this).deleteAll(); chatsDeleted = true; Toast.makeText(this, R.string.delete_all_chats, Toast.LENGTH_SHORT).show(); }
                catch (Exception ignored) {}
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { saveAndFinish(); }
        });
    }

    private void saveAndFinish() {
        int oldThreads = prefs.threads;
        String oldSystem = prefs.systemPrompt;
        prefs.systemPrompt = systemPrompt.getText().toString().trim();
        prefs.temperature = Math.max(0f, parseFloat(temperature, prefs.temperature));
        prefs.maxTokens = Math.max(16, Math.min(3072, parseInt(maxTokens, prefs.maxTokens)));
        prefs.topP = Math.min(1f, Math.max(0f, parseFloat(topP, prefs.topP)));
        prefs.topK = Math.max(1, parseInt(topK, prefs.topK));
        prefs.repeatPenalty = Math.max(1f, parseFloat(repeat, prefs.repeatPenalty));
        prefs.threads = Math.max(0, parseInt(threads, prefs.threads));
        prefs.thinkingMode = swThinking.isChecked();
        prefs.typewriter = swTypewriter.isChecked();
        prefs.save();
        Intent r = new Intent();
        r.putExtra(RESULT_THREADS_CHANGED, oldThreads != prefs.threads);
        r.putExtra(RESULT_SYSTEM_CHANGED, !oldSystem.equals(prefs.systemPrompt));
        r.putExtra(RESULT_IMPORT_MODEL, importRequested);
        r.putExtra(RESULT_CHATS_DELETED, chatsDeleted);
        setResult(RESULT_OK, r);
        finish();
    }

    private static String fmt(float v) { return String.format(Locale.US, "%.2f", v); }
    private static int parseInt(EditText e, int fallback) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (NumberFormatException ex) { return fallback; }
    }
    private static float parseFloat(EditText e, float fallback) {
        try { return Float.parseFloat(e.getText().toString().trim().replace(',', '.')); } catch (NumberFormatException ex) { return fallback; }
    }
}
