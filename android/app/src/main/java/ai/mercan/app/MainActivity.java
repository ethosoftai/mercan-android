package ai.mercan.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final int CTX_SIZE = 4096;

    // Thinking mode: the assistant turn is pre-filled with "Düşünce:" and asked to finish with "Cevap:".
    private static final String THINK_SUFFIX =
            "\n\n(Yanıtını iki bölümde ver: önce \"Düşünce:\" altında soruyu çözmek için en fazla 3 kısa madde, "
            + "sonra \"Cevap:\" altında son cevap.)";
    private static final String THINK_PREFILL = "Düşünce:\n";
    private static final Pattern ANSWER_MARK = Pattern.compile("(?iu)(^|\\n|\\.\\s+)\\s*\\**\\s*cevap\\s*\\**\\s*:\\s*\\**\\s*");
    private static final Pattern THINK_LABEL = Pattern.compile("(?iu)^\\s*\\**\\s*düşünce\\s*\\**\\s*:\\s*\\**\\s*");

    private final Engine eng = Engine.get();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int pendingRestores = 0;

    private DrawerLayout drawer;
    private View toolbar;
    private TextView title;
    private TextView status;
    private RecyclerView list;
    private RecyclerView convList;
    private View emptyState;
    private View emptyHistory;
    private View inputBar;
    private EditText input;
    private EditText search;
    private ImageButton send;
    private ImageButton stop;
    private ImageButton thinkToggle;
    private ImageButton mic;
    private View inputPill;
    private View recordBar;
    private WaveformView waveform;
    private TextView recordTimer;
    private View recordProgress;
    private VoiceInput voice;
    private ProgressBar progress;
    private TextView progressLabel;
    private View progressBox;

    private Prefs prefs;
    private ChatStore store;
    private Conversation current;
    private ChatAdapter adapter;
    private ConversationAdapter convAdapter;
    private boolean autoScroll = true;
    private Typewriter typewriter;

    private ActivityResultLauncher<String[]> pickModel;
    private ActivityResultLauncher<Intent> openSettings;

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        if (!prefs.onboarded) startActivity(new Intent(this, OnboardingActivity.class));

        drawer = findViewById(R.id.drawer);
        toolbar = findViewById(R.id.toolbar);
        title = findViewById(R.id.title);
        status = findViewById(R.id.status);
        list = findViewById(R.id.messages);
        emptyState = findViewById(R.id.empty_state);
        emptyHistory = findViewById(R.id.empty_history);
        inputBar = findViewById(R.id.input_bar);
        input = findViewById(R.id.input);
        search = findViewById(R.id.search);
        send = findViewById(R.id.send);
        stop = findViewById(R.id.stop);
        thinkToggle = findViewById(R.id.think_toggle);
        mic = findViewById(R.id.mic);
        inputPill = findViewById(R.id.input_pill);
        recordBar = findViewById(R.id.record_bar);
        waveform = findViewById(R.id.waveform);
        recordTimer = findViewById(R.id.record_timer);
        recordProgress = findViewById(R.id.record_progress);
        voice = new VoiceInput(this);
        progress = findViewById(R.id.progress);
        progressLabel = findViewById(R.id.progress_label);
        progressBox = findViewById(R.id.progress_box);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        list.setItemAnimator(null);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dx, int dy) {
                if (dy < 0) autoScroll = false;
                if (!rv.canScrollVertically(1)) autoScroll = true;
            }
        });

        findViewById(R.id.btn_menu).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.btn_new).setOnClickListener(v -> newChat());
        findViewById(R.id.drawer_new).setOnClickListener(v -> { drawer.closeDrawer(GravityCompat.START); newChat(); });
        findViewById(R.id.new_chat).setOnClickListener(v -> { drawer.closeDrawer(GravityCompat.START); newChat(); });
        findViewById(R.id.drawer_settings).setOnClickListener(v -> { drawer.closeDrawer(GravityCompat.START); showSettings(); });
        findViewById(R.id.drawer_about).setOnClickListener(v -> { drawer.closeDrawer(GravityCompat.START); showAbout(); });
        send.setOnClickListener(v -> onSend());
        mic.setOnClickListener(v -> startVoiceInput());
        findViewById(R.id.record_cancel).setOnClickListener(v -> { voice.cancel(); showRecording(false); });
        findViewById(R.id.record_confirm).setOnClickListener(v -> {
            recordProgress.setVisibility(View.VISIBLE);
            recordTimer.setVisibility(View.GONE);
            waveform.stop();
            voice.stop();
        });
        stop.setOnClickListener(v -> { if (eng.handle != 0) MercanNative.cancel(eng.handle); });
        thinkToggle.setOnClickListener(v -> {
            prefs.thinkingMode = !prefs.thinkingMode;
            prefs.save();
            updateThinkToggle();
            Toast.makeText(this, prefs.thinkingMode ? R.string.think_on : R.string.think_off, Toast.LENGTH_SHORT).show();
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { onSend(); return true; }
            return false;
        });
        for (int id : new int[]{R.id.suggest_1, R.id.suggest_2, R.id.suggest_3, R.id.suggest_4}) {
            TextView chip = findViewById(id);
            chip.setOnClickListener(v -> { input.setText(chip.getText()); onSend(); });
        }

        // Drawer list + search
        convList = findViewById(R.id.conversations);
        convList.setLayoutManager(new LinearLayoutManager(this));
        convAdapter = new ConversationAdapter(new ConversationAdapter.Listener() {
            @Override public void onOpen(Conversation c) { drawer.closeDrawer(GravityCompat.START); openConversation(c.id); }
            @Override public void onMenu(Conversation c, View anchor) { showConversationMenu(c, anchor); }
        });
        convList.setAdapter(convAdapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                convAdapter.setFilter(s.toString());
                emptyHistory.setVisibility(convAdapter.visibleCount() == 0 ? View.VISIBLE : View.GONE);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });

        applyInsets();
        pickModel = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importModel);
        openSettings = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            prefs.load();
            updateThinkToggle();
            if (r.getData() == null) return;
            Intent d = r.getData();
            if (d.getBooleanExtra(SettingsActivity.RESULT_CHATS_DELETED, false)) {
                current.messages.clear();
                bindConversation(Conversation.create());
                if (eng.modelReady && !eng.generating) eng.executor.execute(() -> MercanNative.reset(eng.handle));
                refreshConversationList();
            }
            if (d.getBooleanExtra(SettingsActivity.RESULT_IMPORT_MODEL, false)) {
                if (eng.generating) Toast.makeText(this, R.string.wait_generation, Toast.LENGTH_SHORT).show();
                else pickModel.launch(new String[]{"*/*"});
                return;
            }
            if (d.getBooleanExtra(SettingsActivity.RESULT_THREADS_CHANGED, false)) {
                if (!eng.generating) startModel(); // thread count lives in the native context
                return;
            }
            if (d.getBooleanExtra(SettingsActivity.RESULT_SYSTEM_CHANGED, false) && eng.modelReady && !eng.generating) {
                eng.executor.execute(() -> MercanNative.setSystemPrompt(eng.handle, prefs.systemPrompt));
                restoreCurrentAsync();
            }
        });

        updateThinkToggle();
        store = new ChatStore(this);
        Conversation initial = null;
        if (savedInstanceState != null) {
            String id = savedInstanceState.getString("conversation_id");
            if (id != null) initial = store.load(id);
        }
        bindConversation(initial != null ? initial : Conversation.create());
        refreshConversationList();
        setControls(false);

        eng.init(this);
        eng.setListener(engineListener);
        if (eng.modelReady) {
            hideProgress();
            if (current.isEmpty()) { setStatusReady(); setControls(true); }
            else restoreCurrentAsync();
        } else if (eng.loading) {
            showProgress(getString(R.string.loading), 0, true);
        } else {
            startModel();
        }
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (current != null) out.putString("conversation_id", current.id);
    }

    private final Engine.Listener engineListener = new Engine.Listener() {
        @Override public void onProgress(String label, int pct, boolean indeterminate) { showProgress(label, pct, indeterminate); }
        @Override public void onReady() {
            hideProgress();
            if (current.isEmpty()) { setStatusReady(); setControls(true); }
            else restoreCurrentAsync();
        }
        @Override public void onError(String message) {
            hideProgress();
            status.setText(getString(R.string.error, message));
            setControls(false);
            new AlertDialog.Builder(MainActivity.this)
                .setTitle(R.string.model_error_title)
                .setMessage(getString(R.string.model_error, message, ModelManager.MODEL_URL))
                .setPositiveButton(R.string.retry, (d, w) -> startModel())
                .setNeutralButton(R.string.import_model, (d, w) -> pickModel.launch(new String[]{"*/*"}))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrent();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        eng.setListener(null); // the model itself outlives the activity (see Engine)
    }

    /** Edge-to-edge: toolbar below the status bar, composer above the nav bar / keyboard. */
    private void applyInsets() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(!night);
        wic.setAppearanceLightNavigationBars(!night);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        final int barHeight = toolbar.getLayoutParams().height;
        final int padH = inputBar.getPaddingLeft(), padT = inputBar.getPaddingTop(), padB = inputBar.getPaddingBottom();
        final View drawerPanel = findViewById(R.id.drawer_panel);
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            ViewGroup.LayoutParams lp = toolbar.getLayoutParams();
            lp.height = barHeight + sb.top;
            toolbar.setLayoutParams(lp);
            toolbar.setPadding(toolbar.getPaddingLeft(), sb.top, toolbar.getPaddingRight(), 0);
            int bottom = Math.max(sb.bottom, ime.bottom);
            inputBar.setPadding(padH + sb.left, padT, padH + sb.right, padB + bottom);
            drawerPanel.setPadding(0, sb.top, 0, sb.bottom);
            if (ime.bottom > 0 && autoScroll) scrollToBottom();
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ------------------------------------------------------------------ model
    private void startModel() {
        setControls(false);
        eng.loadModel(getApplicationContext(), prefs.threads, CTX_SIZE, prefs.systemPrompt);
    }

    private void importModel(Uri uri) {
        if (uri == null) return;
        eng.modelReady = false;
        setControls(false);
        eng.executor.execute(() -> {
            try {
                MercanNative.unload(eng.handle);
                post(() -> showProgress(getString(R.string.importing), 0, true));
                ModelManager.importFrom(this, uri, (got, total) -> post(() ->
                        showProgress(getString(R.string.importing_mb, got >> 20), 0, true)));
                post(this::startModel);
            } catch (Exception e) {
                post(() -> { hideProgress(); status.setText(getString(R.string.error, e.getMessage())); startModel(); });
            }
        });
    }

    /** Engine thread only. Feeds the saved transcript back into the KV cache. */
    private String restoreEngine(List<Conversation.Message> msgs) {
        List<String> roles = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Conversation.Message m : msgs) {
            if (m.fromUser) {
                roles.add("user");
                texts.add(m.text.toString());
            } else {
                if (m.text.length() == 0 && m.thinking.length() == 0) continue;
                roles.add("assistant");
                texts.add(m.thinking.length() > 0
                        ? "Düşünce:\n" + m.thinking + "\n\nCevap: " + m.text
                        : m.text.toString());
            }
        }
        return MercanNative.restore(eng.handle, roles.toArray(new String[0]), texts.toArray(new String[0]), prefs.maxTokens);
    }

    /** Prefills the KV cache with the conversation on screen (UI thread entry point). */
    private void restoreCurrentAsync() {
        final Conversation c = current;
        final List<Conversation.Message> msgs = new ArrayList<>(c.messages);
        pendingRestores++;
        setControls(false);
        status.setText(R.string.restoring);
        eng.executor.execute(() -> {
            String err = restoreEngine(msgs);
            post(() -> {
                pendingRestores--;
                if (current != c || pendingRestores > 0) return;
                setControls(eng.modelReady);
                if (err != null) status.setText(getString(R.string.error, err));
                else setStatusReady();
            });
        });
    }

    // ------------------------------------------------------------------- chat
    // ---------------------------------------------------------------- voice input
    private static final int REQ_MIC = 71;

    private void startVoiceInput() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[] { android.Manifest.permission.RECORD_AUDIO }, REQ_MIC);
            return;
        }
        showRecording(true);
        voice.start(new VoiceInput.Listener() {
            @Override public void onLevel(float peak) { waveform.push(peak); }

            @Override public void onTick(int seconds) {
                recordTimer.setText(String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60));
            }

            @Override public void onResult(String text) {
                showRecording(false);
                if (text == null || text.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, R.string.stt_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                final String current = input.getText().toString();
                input.setText(current.isEmpty() ? text.trim() : current + " " + text.trim());
                input.setSelection(input.getText().length());
                input.requestFocus();
            }

            @Override public void onError(String message) {
                showRecording(false);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showRecording(boolean on) {
        recordBar.setVisibility(on ? View.VISIBLE : View.GONE);
        inputPill.setVisibility(on ? View.GONE : View.VISIBLE);
        recordProgress.setVisibility(View.GONE);
        recordTimer.setVisibility(View.VISIBLE);
        recordTimer.setText("0:00");
        if (on) waveform.start(); else waveform.stop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                Toast.makeText(this, R.string.mic_permission, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void onSend() {
        if (!eng.modelReady || eng.generating || pendingRestores > 0) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        Conversation.Message um = new Conversation.Message(true, text);
        current.messages.add(um);
        adapter.inserted(current.messages.size() - 1);
        if (current.title.isEmpty()) {
            current.title = Conversation.titleFrom(text);
            title.setText(current.title);
        }
        current.updated = System.currentTimeMillis();
        store.save(current);          // show up in the drawer right away
        refreshConversationList();
        startGeneration(text, false);
    }

    /** Appends an assistant bubble and streams the reply into it (typewriter-paced). */
    private void startGeneration(String userText, boolean restoreFirst) {
        final Conversation conv = current;
        final Conversation.Message reply = new Conversation.Message(false, "");
        reply.streaming = true;
        conv.messages.add(reply);
        final int replyIndex = conv.messages.size() - 1;
        adapter.inserted(replyIndex);
        updateEmptyState();
        autoScroll = true;
        scrollToBottom();
        eng.generating = true;
        setControls(true);
        status.setText(prefs.thinkingMode ? R.string.thinking_now : R.string.generating);

        final boolean think = prefs.thinkingMode;
        final String prompt = think ? userText + THINK_SUFFIX : userText;
        final String prefill = think ? THINK_PREFILL : "";
        final List<Conversation.Message> priorTurns = restoreFirst
                ? new ArrayList<>(conv.messages.subList(0, Math.max(0, replyIndex - 1))) : null;
        final String[] meta = {""};
        final String[] error = {null};

        typewriter = new Typewriter(ui, (visible, done) -> {
            if (conv != current && !done) return;
            applyRaw(reply, prefill + visible, think);
            if (done) finishTurn(conv, reply, replyIndex, error[0], meta[0]);
            else if (conv == current) {
                adapter.changed(replyIndex);
                if (autoScroll) scrollToBottom();
            }
        });
        typewriter.setEnabled(prefs.typewriter);
        final Typewriter tw = typewriter;

        eng.executor.execute(() -> {
            if (priorTurns != null) {
                String rerr = restoreEngine(priorTurns);
                if (rerr != null) { error[0] = rerr; tw.finish(); return; }
            }
            String err = MercanNative.chat(eng.handle, prompt, prefs.temperature, prefs.topP, prefs.topK, prefs.repeatPenalty,
                prefs.maxTokens, prefill, new MercanNative.Callback() {
                    @Override public boolean onPiece(String piece) { tw.append(piece); return true; }
                    @Override public void onDone(int genTokens, double tokPerSec, int promptTokens, double promptMs, int ctxUsed, int ctxSize) {
                        meta[0] = String.format(Locale.getDefault(), "%d token • %.1f tok/sn", genTokens, tokPerSec);
                    }
                });
            error[0] = err;
            tw.finish();
        });
    }

    /** Splits the raw model output into thinking/answer for the bubble. */
    private static void applyRaw(Conversation.Message reply, String raw, boolean think) {
        reply.text.setLength(0);
        reply.thinking.setLength(0);
        if (!think) { reply.text.append(raw.trim()); return; }
        Matcher m = ANSWER_MARK.matcher(raw);
        if (m.find()) {
            String thinking = THINK_LABEL.matcher(raw.substring(0, m.start())).replaceFirst("").trim();
            reply.thinking.append(thinking);
            reply.text.append(raw.substring(m.end()).trim());
        } else {
            reply.thinking.append(THINK_LABEL.matcher(raw).replaceFirst("").trim());
        }
    }

    private void finishTurn(Conversation conv, Conversation.Message reply, int replyIndex, String err, String meta) {
        reply.streaming = false;
        reply.meta = meta == null ? "" : meta;
        // No "Cevap:" marker: show everything as the answer instead of leaving it folded away.
        if (reply.text.length() == 0 && reply.thinking.length() > 0) {
            reply.text.append(reply.thinking);
            reply.thinking.setLength(0);
        }
        if (err != null) {
            if (reply.text.length() > 0) reply.text.append("\n\n");
            reply.text.append("[").append(getString(R.string.error, err)).append("]");
        }
        if (reply.text.length() == 0) reply.text.append(getString(R.string.empty_reply));
        conv.updated = System.currentTimeMillis();
        eng.generating = false;
        if (conv == current) {
            adapter.changed(replyIndex);
            setControls(eng.modelReady);
            status.setText(reply.meta.isEmpty() ? getString(R.string.ready_short, eng.backendLabel) : reply.meta);
            if (autoScroll) scrollToBottom();
        }
        store.save(conv);
        refreshConversationList();
    }

    private void regenerate(int index) {
        if (!eng.modelReady || eng.generating || pendingRestores > 0) return;
        if (index != current.messages.size() - 1 || index < 1) return;
        Conversation.Message userMsg = current.messages.get(index - 1);
        if (!userMsg.fromUser) return;
        current.messages.remove(index);
        adapter.removed(index);
        startGeneration(userMsg.text.toString(), true);
    }

    // ----------------------------------------------------------- conversations
    private void bindConversation(Conversation c) {
        current = c;
        adapter = new ChatAdapter(c.messages, this::showMessageMenu);
        list.setAdapter(adapter);
        title.setText(c.displayTitle(getString(R.string.app_name)));
        convAdapter.setSelected(c.id);
        updateEmptyState();
        autoScroll = true;
        scrollToBottom();
    }

    private void newChat() {
        if (eng.generating) { Toast.makeText(this, R.string.wait_generation, Toast.LENGTH_SHORT).show(); return; }
        saveCurrent();
        if (current.isEmpty()) { input.requestFocus(); return; }
        bindConversation(Conversation.create());
        if (eng.modelReady) eng.executor.execute(() -> MercanNative.reset(eng.handle));
        setStatusReady();
        input.requestFocus();
    }

    private void openConversation(String id) {
        if (eng.generating) { Toast.makeText(this, R.string.wait_generation, Toast.LENGTH_SHORT).show(); return; }
        if (id.equals(current.id)) return;
        saveCurrent();
        Conversation c = store.load(id);
        if (c == null) { refreshConversationList(); return; }
        bindConversation(c);
        if (eng.modelReady) restoreCurrentAsync();
    }

    private void saveCurrent() {
        if (current != null && !current.isEmpty()) store.save(current);
    }

    private void refreshConversationList() {
        convAdapter.setItems(store.list(), current == null ? "" : current.id);
        emptyHistory.setVisibility(convAdapter.visibleCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void deleteConversation(Conversation c) {
        if (eng.generating && c.id.equals(current.id)) { Toast.makeText(this, R.string.wait_generation, Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this)
            .setTitle(R.string.delete_chat)
            .setMessage(getString(R.string.delete_chat_confirm, c.displayTitle(getString(R.string.untitled_chat))))
            .setPositiveButton(R.string.delete, (d, w) -> {
                store.delete(c.id);
                if (c.id.equals(current.id)) {
                    current.messages.clear();
                    bindConversation(Conversation.create());
                    if (eng.modelReady && !eng.generating) eng.executor.execute(() -> MercanNative.reset(eng.handle));
                }
                refreshConversationList();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void renameConversation(Conversation c) {
        EditText e = new EditText(this);
        e.setText(c.displayTitle(""));
        e.setSingleLine(true);
        e.setSelection(e.getText().length());
        e.setBackgroundResource(R.drawable.bg_field);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        e.setPadding(pad, pad, pad, pad);
        LinearLayout box = new LinearLayout(this);
        int m = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(m, m / 2, m, 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle(R.string.rename_chat)
            .setView(box)
            .setPositiveButton(R.string.save, (d, w) -> {
                String t = e.getText().toString().trim();
                if (t.isEmpty()) return;
                c.title = t;
                if (c.id.equals(current.id)) { current.title = t; title.setText(t); store.save(current); }
                else store.save(c);
                refreshConversationList();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showConversationMenu(Conversation c, View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, R.string.rename_chat);
        pm.getMenu().add(0, 2, 1, R.string.delete_chat);
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) renameConversation(c);
            else deleteConversation(c);
            return true;
        });
        pm.show();
    }

    private void showMessageMenu(int index, Conversation.Message m, View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, R.string.copy);
        if (!m.fromUser && m.thinking.length() > 0) pm.getMenu().add(0, 3, 1, R.string.copy_thinking);
        if (!m.fromUser && index == current.messages.size() - 1 && !eng.generating) pm.getMenu().add(0, 2, 2, R.string.regenerate);
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) copy(m.text.toString());
            else if (item.getItemId() == 3) copy(m.thinking.toString());
            else regenerate(index);
            return true;
        });
        pm.show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Mercan", text));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    // --------------------------------------------------------------- settings
    private void showSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        i.putExtra(SettingsActivity.EXTRA_BACKEND, eng.backendLabel);
        i.putExtra(SettingsActivity.EXTRA_DEFAULT_THREADS, MercanNative.defaultThreads());
        openSettings.launch(i);
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_text, eng.backendLabel.isEmpty() ? "-" : eng.backendLabel))
            .setPositiveButton(android.R.string.ok, null).show();
    }

    // --------------------------------------------------------------- helpers
    private void setControls(boolean ready) {
        boolean gen = eng.generating;
        boolean canType = ready && !gen && pendingRestores == 0;
        input.setEnabled(canType);
        send.setEnabled(canType);
        send.setAlpha(canType ? 1f : 0.35f);
        send.setVisibility(gen ? View.GONE : View.VISIBLE);
        stop.setVisibility(gen ? View.VISIBLE : View.GONE);
        thinkToggle.setEnabled(!gen);
    }

    private void updateThinkToggle() {
        boolean on = prefs.thinkingMode;
        thinkToggle.setImageResource(on ? R.drawable.ic_bulb_on : R.drawable.ic_bulb);
        thinkToggle.setColorFilter(getResources().getColor(on ? R.color.brand : R.color.ink_faint, getTheme()));
        thinkToggle.setContentDescription(getString(on ? R.string.think_on : R.string.think_off));
    }

    private void updateEmptyState() {
        boolean empty = current == null || current.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
    }

    private void scrollToBottom() {
        if (adapter != null && adapter.getItemCount() > 0) list.scrollToPosition(adapter.getItemCount() - 1);
    }

    private void setStatusReady() {
        status.setText(getString(R.string.ready, MercanNative.threads(eng.handle), eng.backendLabel));
    }

    private void showProgress(String label, int pct, boolean indeterminate) {
        progressBox.setVisibility(View.VISIBLE);
        progressLabel.setText(label);
        progress.setIndeterminate(indeterminate);
        if (!indeterminate) progress.setProgress(pct);
        status.setText(label);
    }

    private void hideProgress() { progressBox.setVisibility(View.GONE); }

    private void post(Runnable r) { ui.post(r); }
}
