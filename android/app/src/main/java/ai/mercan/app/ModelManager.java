package ai.mercan.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installed models: downloads (built-in default or any Hugging Face .mercan), imports and the
 * active-model choice. Every model is kept encrypted at rest ({@link ModelCrypto}); a plaintext
 * copy exists only transiently while the native loader mmaps it, and is unlinked right after.
 */
public final class ModelManager {
    /** Built-in default model (overridable at build time: MODEL_URL / MODEL_AUTH_TOKEN). */
    public static final String MODEL_URL = BuildConfig.MODEL_URL;
    /** Identity of the built-in weights; when an app update changes it the old built-in model is dropped. */
    public static final String MODEL_ID = BuildConfig.MODEL_ID;

    /**
     * Architectures the bundled runtime can execute. The Android build pins libmercan at a
     * revision that only registers NedoLM; models of other families (e.g. lfm3) are rejected
     * before download instead of failing after a large transfer.
     */
    static final Set<String> SUPPORTED_ARCHITECTURES = Collections.unmodifiableSet(new HashSet<>(Collections.singletonList("nedolm")));

    private static final String LEGACY_PLAIN = "model.mercan";
    private static final String LEGACY_ENC = "model.mercan.enc";
    private static final String PREFS = "mercan_model";
    private static final String PREF_SHA = "plain_sha256";   // legacy single-model keys
    private static final String PREF_ID = "model_id";
    private static final String PREF_MODELS = "models_v2";
    private static final String PREF_ACTIVE = "active_v2";
    private static final long SPACE_MARGIN = 64L << 20;
    private static final Object LOCK = new Object();

    public interface Progress {
        void onProgress(long done, long total);
    }

    /** User-facing failure (message is shown as-is). */
    public static final class ModelException extends IOException {
        public ModelException(String message) { super(message); }
    }

    public static final class ModelInfo {
        public final String id;       // unique; built-in = MODEL_ID, HF = HfModelRef.key(), file = "file:<sha>"
        public final String name;
        public final String source;   // URL or "file:<name>"
        public final String file;     // encrypted file name inside modelDir()
        public final String sha;      // plaintext SHA-256
        public final long size;       // plaintext bytes
        public final boolean builtin;

        ModelInfo(String id, String name, String source, String file, String sha, long size, boolean builtin) {
            this.id = id; this.name = name; this.source = source; this.file = file;
            this.sha = sha; this.size = size; this.builtin = builtin;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("source", source).put("file", file)
                    .put("sha", sha).put("size", size).put("builtin", builtin);
        }

        static ModelInfo fromJson(JSONObject o) {
            return new ModelInfo(o.optString("id"), o.optString("name"), o.optString("source"), o.optString("file"),
                    o.optString("sha"), o.optLong("size"), o.optBoolean("builtin"));
        }
    }

    private ModelManager() {}

    // ------------------------------------------------------------------ registry

    public static File modelDir(Context ctx) {
        // Internal storage: mmap page faults through FUSE-backed external storage are much slower.
        File dir = new File(ctx.getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File fileOf(Context ctx, ModelInfo m) { return new File(modelDir(ctx), m.file); }

    public static List<ModelInfo> list(Context ctx) {
        synchronized (LOCK) {
            upgradeLegacyRegistry(ctx);
            List<ModelInfo> out = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(prefs(ctx).getString(PREF_MODELS, "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    ModelInfo m = ModelInfo.fromJson(arr.getJSONObject(i));
                    if (fileOf(ctx, m).isFile()) out.add(m);
                }
            } catch (JSONException ignored) {
                // A corrupt registry behaves like an empty one; files are re-registered on next install.
            }
            return out;
        }
    }

    /** The model the engine should load, or null when none is installed. */
    public static ModelInfo active(Context ctx) {
        synchronized (LOCK) {
            List<ModelInfo> all = list(ctx);
            if (all.isEmpty()) return null;
            String id = prefs(ctx).getString(PREF_ACTIVE, null);
            for (ModelInfo m : all) if (m.id.equals(id)) return m;
            ModelInfo first = all.get(0);
            prefs(ctx).edit().putString(PREF_ACTIVE, first.id).apply();
            return first;
        }
    }

    public static void setActive(Context ctx, String id) {
        synchronized (LOCK) { prefs(ctx).edit().putString(PREF_ACTIVE, id).apply(); }
    }

    public static boolean isReady(Context ctx) { return active(ctx) != null; }

    private static void register(Context ctx, ModelInfo info, boolean makeActive) throws IOException {
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray(prefs(ctx).getString(PREF_MODELS, "[]"));
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    ModelInfo m = ModelInfo.fromJson(arr.getJSONObject(i));
                    if (m.id.equals(info.id)) {
                        if (!m.file.equals(info.file)) fileOf(ctx, m).delete();
                        continue;
                    }
                    next.put(arr.getJSONObject(i));
                }
                next.put(info.toJson());
                SharedPreferences.Editor e = prefs(ctx).edit().putString(PREF_MODELS, next.toString());
                if (makeActive) e.putString(PREF_ACTIVE, info.id);
                e.apply();
            } catch (JSONException e) {
                throw new IOException("model registry: " + e.getMessage());
            }
        }
    }

    /** Deletes one installed model. When it was active, another installed model becomes active. */
    public static void delete(Context ctx, String id) {
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray(prefs(ctx).getString(PREF_MODELS, "[]"));
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    ModelInfo m = ModelInfo.fromJson(arr.getJSONObject(i));
                    if (m.id.equals(id)) fileOf(ctx, m).delete();
                    else next.put(arr.getJSONObject(i));
                }
                SharedPreferences.Editor e = prefs(ctx).edit().putString(PREF_MODELS, next.toString());
                if (id.equals(prefs(ctx).getString(PREF_ACTIVE, null))) e.remove(PREF_ACTIVE);
                e.apply();
                if (next.length() == 0) {
                    cleanTemp(ctx);
                    ModelCrypto.resetKeys(ctx); // nothing left that the data key protects
                }
            } catch (JSONException ignored) {
            }
        }
    }

    /**
     * One-time move from the pre-0.8 single-model layout (model.mercan.enc + plain_sha256/model_id)
     * into the registry, and removal of a built-in model whose weights an app update replaced.
     */
    private static void upgradeLegacyRegistry(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        File legacy = new File(modelDir(ctx), LEGACY_ENC);
        if (!sp.contains(PREF_MODELS)) {
            JSONArray arr = new JSONArray();
            String storedId = sp.getString(PREF_ID, null);
            String sha = sp.getString(PREF_SHA, null);
            if (legacy.isFile() && legacy.length() > 0 && sha != null && (storedId == null || storedId.equals(MODEL_ID))) {
                try {
                    arr.put(new ModelInfo(MODEL_ID, builtinName(), MODEL_URL, LEGACY_ENC, sha, legacy.length(), true).toJson());
                } catch (JSONException ignored) {
                }
            } else {
                legacy.delete();
            }
            sp.edit().putString(PREF_MODELS, arr.toString()).remove(PREF_SHA).remove(PREF_ID).apply();
        }
        try {
            JSONArray arr = new JSONArray(sp.getString(PREF_MODELS, "[]"));
            JSONArray next = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                ModelInfo m = ModelInfo.fromJson(arr.getJSONObject(i));
                if (m.builtin && !m.id.equals(MODEL_ID)) {
                    fileOf(ctx, m).delete();
                    changed = true;
                } else {
                    next.put(arr.getJSONObject(i));
                }
            }
            if (changed) sp.edit().putString(PREF_MODELS, next.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static String builtinName() {
        try {
            return HfModelRef.parse(MODEL_URL).displayName();
        } catch (IllegalArgumentException e) {
            return "Mercan";
        }
    }

    // ------------------------------------------------------------------ installing

    /** Seals a plaintext model.mercan left by a pre-0.4 install (or dropped in by hand). */
    public static boolean migrateLegacy(Context ctx, Progress progress) throws IOException {
        File legacy = new File(modelDir(ctx), LEGACY_PLAIN);
        if (!legacy.isFile() || legacy.length() == 0) return false;
        try (InputStream in = new FileInputStream(legacy)) {
            install(ctx, in, legacy.length(), MODEL_ID, builtinName(), MODEL_URL, true, true, progress, null);
        }
        legacy.delete();
        return true;
    }

    /** Downloads the built-in default model (first launch). The build's auth token is sent only here. */
    public static ModelInfo downloadDefault(Context ctx, Progress progress, AtomicBoolean cancel) throws IOException {
        return downloadUrl(ctx, MODEL_URL, true, MODEL_ID, builtinName(), true, progress, cancel);
    }

    /** Downloads a user-chosen Hugging Face model; it becomes the active model on success. */
    public static ModelInfo downloadFromHf(Context ctx, HfModelRef ref, Progress progress, AtomicBoolean cancel) throws IOException {
        return downloadUrl(ctx, ref.resolveUrl(), false, ref.key(), ref.displayName(), false, progress, cancel);
    }

    private static ModelInfo downloadUrl(Context ctx, String url, boolean sendToken, String id, String name,
                                         boolean builtin, Progress progress, AtomicBoolean cancel) throws IOException {
        // 1) Probe the first bytes so an unsupported or non-Mercan file is rejected before a large transfer.
        HttpURLConnection probe = connect(url, sendToken, "bytes=0-" + (GgufHeader.PROBE_BYTES - 1));
        long total;
        try {
            int code = probe.getResponseCode();
            if (code != 200 && code != 206) throw httpError(code);
            total = code == 206 ? totalFromContentRange(probe.getHeaderField("Content-Range")) : probe.getContentLengthLong();
            byte[] head = new byte[GgufHeader.PROBE_BYTES];
            int n = readUpTo(probe.getInputStream(), head);
            checkHeader(head, n);
        } finally {
            probe.disconnect();
        }
        checkSpace(ctx, total);
        // 2) Full download, encrypted while streaming.
        HttpURLConnection conn = connect(url, sendToken, null);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw httpError(code);
            if (total <= 0) total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream()) {
                return install(ctx, in, total, id, name, url, builtin, true, progress, cancel);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Copies a user-picked .mercan file (SAF Uri) into the store; it becomes the active model. */
    public static ModelInfo importFrom(Context ctx, Uri uri, Progress progress, AtomicBoolean cancel) throws IOException {
        String display = displayNameOf(ctx, uri);
        long size = sizeOf(ctx, uri);
        InputStream raw = ctx.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("cannot open file");
        try (InputStream in = raw) {
            byte[] head = new byte[GgufHeader.PROBE_BYTES];
            int n = readUpTo(in, head);
            checkHeader(head, n);
            checkSpace(ctx, size);
            String stem = display.toLowerCase(Locale.ROOT).endsWith(".mercan") ? display.substring(0, display.length() - 7) : display;
            // id == null: install() derives it from the content hash, so re-importing the same file replaces it.
            InputStream whole = new SequenceInputStream(new ByteArrayInputStream(head, 0, n), in);
            return install(ctx, whole, size, null, stem, "file:" + display, false, true, progress, cancel);
        }
    }

    /**
     * Streams {@code in} into a new encrypted file, then registers it.
     * @param id null = derive from the content hash (imports).
     */
    private static ModelInfo install(Context ctx, InputStream in, long total, String id, String name, String source,
                                     boolean builtin, boolean makeActive, Progress progress, AtomicBoolean cancel) throws IOException {
        String fileName = "m-" + Long.toHexString(System.nanoTime()) + ".mercan.enc";
        File dst = new File(modelDir(ctx), fileName);
        File tmp = new File(dst.getPath() + ".part");
        long got = 0;
        String sha;
        try {
            ModelCrypto.EncryptingOutputStream out = ModelCrypto.encrypt(ctx, tmp);
            try {
                byte[] buf = new byte[1 << 18];
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.get()) throw new ModelException("İndirme iptal edildi.");
                    out.write(buf, 0, n);
                    got += n;
                    if (progress != null && got - lastReport > (1 << 20)) {
                        lastReport = got;
                        progress.onProgress(got, total);
                    }
                }
            } finally {
                out.close();
            }
            sha = out.digestHex();
            if (progress != null) progress.onProgress(got, total);
            if (got == 0) throw new IOException("empty model");
            if (total > 0 && got != total) throw new IOException("incomplete download (" + got + "/" + total + " bytes)");
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("could not move model into place");
        }
        ModelInfo info = new ModelInfo(id != null ? id : "file:" + sha, name, source, fileName, sha, got, builtin);
        register(ctx, info, makeActive);
        return info;
    }

    // ------------------------------------------------------------------ loading

    /**
     * Decrypts the active model into a private temp file for the native loader. The caller must
     * {@link File#delete()} it as soon as the model is loaded (mmap keeps the data alive).
     */
    public static File prepareForLoad(Context ctx, ModelInfo model, Progress progress) throws IOException {
        cleanTemp(ctx);
        File src = fileOf(ctx, model);
        File tmp = new File(tempDir(ctx), "m-" + System.nanoTime() + ".mercan");
        long total = src.length();
        long got = 0;
        String sha;
        try (ModelCrypto.DecryptingInputStream in = ModelCrypto.decrypt(ctx, src);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 18];
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (progress != null && got - lastReport > (8 << 20)) {
                    lastReport = got;
                    progress.onProgress(got, total);
                }
            }
            sha = in.digestHex();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (model.sha != null && !model.sha.isEmpty() && !model.sha.equals(sha)) {
            tmp.delete();
            throw new IOException("model integrity check failed");
        }
        return tmp;
    }

    private static File tempDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "load");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Removes plaintext temp files left behind by a crash. */
    public static void cleanTemp(Context ctx) {
        File[] files = tempDir(ctx).listFiles();
        if (files != null) for (File f : files) f.delete();
        File[] parts = modelDir(ctx).listFiles((d, n) -> n.endsWith(".part"));
        if (parts != null) for (File f : parts) f.delete();
    }

    // ------------------------------------------------------------------ helpers

    private static HttpURLConnection connect(String spec, boolean sendToken, String range) throws IOException {
        URL url = new URL(spec);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new ModelException("Yalnızca https bağlantıları destekleniyor.");
        HttpURLConnection conn = open(url, sendToken, range);
        int code = conn.getResponseCode();
        // HttpURLConnection does not follow cross-host redirects (HF -> CDN) automatically.
        int hops = 0;
        while ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops++ < 5) {
            URL next = new URL(url, conn.getHeaderField("Location"));
            conn.disconnect();
            if (!"https".equalsIgnoreCase(next.getProtocol())) throw new ModelException("Güvensiz yönlendirme reddedildi.");
            // The token is only meant for the configured host, never for redirect targets.
            boolean sameHost = next.getHost().equalsIgnoreCase(url.getHost());
            conn = open(next, sendToken && sameHost, range);
            url = next;
            code = conn.getResponseCode();
        }
        return conn;
    }

    private static HttpURLConnection open(URL url, boolean sendToken, String range) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mercan-Android/" + BuildConfig.VERSION_NAME);
        if (range != null) conn.setRequestProperty("Range", range);
        if (sendToken && !BuildConfig.MODEL_AUTH_TOKEN.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.MODEL_AUTH_TOKEN);
        }
        return conn;
    }

    private static IOException httpError(int code) {
        if (code == 401 || code == 403) return new ModelException("Bu model özel ya da erişim izni (gated) istiyor (HTTP " + code + ").");
        if (code == 404) return new ModelException("Dosya bulunamadı (HTTP 404). Adresi ve dosya adını kontrol edin.");
        return new IOException("HTTP " + code);
    }

    private static long totalFromContentRange(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Rejects files that are not Mercan models or need an architecture this runtime lacks. */
    static void checkHeader(byte[] head, int n) throws ModelException {
        GgufHeader.Info info;
        try {
            info = GgufHeader.parse(head, n);
        } catch (IllegalArgumentException e) {
            throw new ModelException("Bu dosya bir Mercan modeli (.mercan) değil.");
        }
        if (info.mercanFormat != null && !"mercan".equals(info.mercanFormat)) {
            throw new ModelException("Bu dosya bir Mercan modeli (.mercan) değil.");
        }
        if (info.architecture != null && !SUPPORTED_ARCHITECTURES.contains(info.architecture)) {
            throw new ModelException("Bu model '" + info.architecture + "' mimarisini kullanıyor; Android sürümündeki "
                    + "Mercan çalışma zamanı şu an yalnızca Mercan/NedoLM modellerini çalıştırabiliyor.");
        }
    }

    /** Needs room for the encrypted copy plus the transient plaintext copy made at load time. */
    private static void checkSpace(Context ctx, long size) throws ModelException {
        if (size <= 0) return;
        long free = new StatFs(ctx.getFilesDir().getAbsolutePath()).getAvailableBytes();
        long need = size * 2 + SPACE_MARGIN;
        if (free < need) {
            throw new ModelException(String.format(Locale.getDefault(),
                    "Yetersiz depolama alanı: bu model için yaklaşık %d MB boş alan gerekiyor (şu an %d MB).",
                    need >> 20, free >> 20));
        }
    }

    private static int readUpTo(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static String displayNameOf(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "model.mercan" : last;
    }

    private static long sizeOf(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
