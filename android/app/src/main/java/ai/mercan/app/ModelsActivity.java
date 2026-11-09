package ai.mercan.app;

import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.Locale;

/**
 * Installed models: pick the active one, delete, add from a Hugging Face link or import a file.
 * The chat screen reloads the engine when it sees a different active model on resume.
 */
public class ModelsActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView empty, inputError, taskLabel;
    private EditText hfInput;
    private ProgressBar taskProgress;
    private View taskCard, addButton, importButton;
    private ActivityResultLauncher<String[]> pickFile;
    private final ModelTasks tasks = ModelTasks.get();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_models);
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

        list = findViewById(R.id.models_list);
        empty = findViewById(R.id.models_empty);
        hfInput = findViewById(R.id.hf_input);
        inputError = findViewById(R.id.hf_error);
        taskCard = findViewById(R.id.task_card);
        taskLabel = findViewById(R.id.task_label);
        taskProgress = findViewById(R.id.task_progress);
        addButton = findViewById(R.id.add_hf);
        importButton = findViewById(R.id.import_file);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        addButton.setOnClickListener(v -> addFromHuggingFace());
        hfInput.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { addFromHuggingFace(); return true; }
            return false;
        });
        pickFile = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importFile);
        importButton.setOnClickListener(v -> pickFile.launch(new String[]{"*/*"}));
        findViewById(R.id.task_cancel).setOnClickListener(v -> tasks.cancel());
    }

    @Override
    protected void onResume() {
        super.onResume();
        tasks.setListener(this::onTaskChanged);
        refresh();
        onTaskChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        tasks.setListener(null);
    }

    // ------------------------------------------------------------------ list

    private void refresh() {
        list.removeAllViews();
        List<ModelManager.ModelInfo> models = ModelManager.list(this);
        ModelManager.ModelInfo active = ModelManager.active(this);
        empty.setVisibility(models.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (ModelManager.ModelInfo m : models) {
            View row = inf.inflate(R.layout.item_model, list, false);
            boolean isActive = active != null && active.id.equals(m.id);
            row.findViewById(R.id.model_check).setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);
            ((TextView) row.findViewById(R.id.model_name)).setText(m.name);
            ((TextView) row.findViewById(R.id.model_meta)).setText(getString(R.string.models_meta,
                    String.format(Locale.getDefault(), "%.0f MB", m.size / 1048576.0), sourceLabel(m),
                    isActive ? getString(R.string.models_active_prefix) : ""));
            row.setOnClickListener(v -> select(m));
            row.findViewById(R.id.model_delete).setOnClickListener(v -> confirmDelete(m, models.size() == 1));
            list.addView(row);
        }
    }

    private String sourceLabel(ModelManager.ModelInfo m) {
        if (m.builtin) return getString(R.string.models_source_builtin);
        if (m.source.startsWith("file:")) return getString(R.string.models_source_file);
        try {
            return HfModelRef.parse(m.source).repo;
        } catch (IllegalArgumentException e) {
            return m.source;
        }
    }

    private void select(ModelManager.ModelInfo m) {
        ModelManager.ModelInfo active = ModelManager.active(this);
        if (active != null && active.id.equals(m.id)) return;
        if (Engine.get().generating) {
            Toast.makeText(this, R.string.wait_generation, Toast.LENGTH_SHORT).show();
            return;
        }
        ModelManager.setActive(this, m.id);
        refresh();
        Toast.makeText(this, getString(R.string.models_selected, m.name), Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(ModelManager.ModelInfo m, boolean last) {
        if (tasks.running) {
            Toast.makeText(this, R.string.models_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean loaded = m.id.equals(Engine.get().loadedModelId);
        if (loaded && (Engine.get().generating || Engine.get().loading)) {
            Toast.makeText(this, R.string.wait_generation, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.models_delete_title)
                .setMessage(getString(last ? R.string.models_delete_last : R.string.models_delete_confirm, m.name))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    ModelManager.delete(this, m.id);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ adding

    private void addFromHuggingFace() {
        if (tasks.running) {
            Toast.makeText(this, R.string.models_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        HfModelRef ref;
        try {
            ref = HfModelRef.parse(hfInput.getText().toString());
        } catch (IllegalArgumentException e) {
            showInputError(e.getMessage());
            return;
        }
        showInputError(null);
        if (ref.path != null) {
            confirmAndDownload(ref);
            return;
        }
        // Repo link without a file: list its .mercan files first.
        addButton.setEnabled(false);
        new Thread(() -> {
            List<HfModelRef.RemoteFile> files = null;
            String error = null;
            try {
                files = ref.listMercanFiles();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<HfModelRef.RemoteFile> result = files;
            final String err = error;
            runOnUiThread(() -> {
                addButton.setEnabled(true);
                if (isFinishing() || isDestroyed()) return;
                if (err != null) { showInputError(err); return; }
                if (result.isEmpty()) { showInputError(getString(R.string.models_no_mercan, ref.repo)); return; }
                if (result.size() == 1) { confirmAndDownload(ref.withPath(result.get(0).path)); return; }
                CharSequence[] items = new CharSequence[result.size()];
                for (int i = 0; i < items.length; i++) {
                    HfModelRef.RemoteFile f = result.get(i);
                    items[i] = f.size > 0
                            ? String.format(Locale.getDefault(), "%s  (%.0f MB)", f.path, f.size / 1048576.0)
                            : f.path;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.models_pick_file)
                        .setItems(items, (d, which) -> startDownload(ref.withPath(result.get(which).path)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "hf-list").start();
    }

    private void confirmAndDownload(HfModelRef ref) {
        for (ModelManager.ModelInfo m : ModelManager.list(this)) {
            if (m.id.equals(ref.key())) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.models_already_title)
                        .setMessage(getString(R.string.models_already, m.name))
                        .setPositiveButton(R.string.models_redownload, (d, w) -> startDownload(ref))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        }
        startDownload(ref);
    }

    private void startDownload(HfModelRef ref) {
        android.content.Context app = getApplicationContext();
        boolean started = tasks.start(getString(R.string.models_downloading, ref.displayName()),
                (progress, cancel) -> ModelManager.downloadFromHf(app, ref, progress, cancel));
        if (!started) Toast.makeText(this, R.string.models_busy, Toast.LENGTH_SHORT).show();
    }

    private void importFile(Uri uri) {
        if (uri == null) return;
        android.content.Context app = getApplicationContext();
        boolean started = tasks.start(getString(R.string.importing),
                (progress, cancel) -> ModelManager.importFrom(app, uri, progress, cancel));
        if (!started) Toast.makeText(this, R.string.models_busy, Toast.LENGTH_SHORT).show();
    }

    private void showInputError(String message) {
        inputError.setVisibility(message == null ? View.GONE : View.VISIBLE);
        inputError.setText(message == null ? "" : message);
    }

    // ------------------------------------------------------------------ task progress

    private void onTaskChanged() {
        taskCard.setVisibility(tasks.running ? View.VISIBLE : View.GONE);
        addButton.setEnabled(!tasks.running);
        importButton.setEnabled(!tasks.running);
        if (tasks.running) {
            long done = tasks.done, total = tasks.total;
            if (total > 0) {
                int pct = (int) (done * 100 / total);
                taskProgress.setIndeterminate(false);
                taskProgress.setProgress(pct);
                taskLabel.setText(getString(R.string.models_task_progress, tasks.label, pct, done >> 20, total >> 20));
            } else {
                taskProgress.setIndeterminate(true);
                taskLabel.setText(done > 0
                        ? getString(R.string.models_task_mb, tasks.label, done >> 20)
                        : tasks.label);
            }
        }
        if (tasks.lastFinished) {
            tasks.lastFinished = false; // report each result once
            if (tasks.lastError == null) {
                hfInput.setText("");
                Toast.makeText(this, R.string.models_installed, Toast.LENGTH_LONG).show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.models_failed)
                        .setMessage(tasks.lastError)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
            refresh();
        }
    }
}
