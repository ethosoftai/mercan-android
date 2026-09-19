package ai.mercan.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Locates, downloads or imports the model into app-private storage. The model is kept
 * encrypted at rest ({@link ModelCrypto}); a plaintext copy exists only transiently while the
 * native loader mmaps it, and is unlinked right after (the mapping stays valid).
 */
public final class ModelManager {
    /** Overridable at build time (see build.gradle: MODEL_URL / MODEL_AUTH_TOKEN). */
    public static final String MODEL_URL = BuildConfig.MODEL_URL;
    public static final String MODEL_FILE = "model.mercan.enc";
    private static final String LEGACY_FILE = "model.mercan";
    private static final String PREFS = "mercan_model";
    private static final String PREF_SHA = "plain_sha256";

    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    private ModelManager() {}

    public static File modelDir(Context ctx) {
        // Internal storage: mmap page faults through FUSE-backed external storage are much slower.
        File dir = new File(ctx.getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File modelFile(Context ctx) {
        return new File(modelDir(ctx), MODEL_FILE);
    }

    public static boolean isReady(Context ctx) {
        File f = modelFile(ctx);
        return f.isFile() && f.length() > 0;
    }

    /** Installs from a pre-0.4 plaintext model (or a file dropped in by hand) and removes the plaintext. */
    public static boolean migrateLegacy(Context ctx, Progress progress) throws IOException {
        File legacy = new File(modelDir(ctx), LEGACY_FILE);
        if (!legacy.isFile() || legacy.length() == 0) return false;
        try (InputStream in = new FileInputStream(legacy)) {
            encryptInto(ctx, in, progress, legacy.length());
        }
        legacy.delete();
        return true;
    }

    public static void download(Context ctx, Progress progress) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(MODEL_URL);
            conn = open(url);
            int code = conn.getResponseCode();
            // HttpURLConnection does not follow http->https or cross-host redirects automatically.
            int hops = 0;
            while ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops++ < 5) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                conn = open(new URL(url, loc));
                code = conn.getResponseCode();
            }
            if (code != 200) throw new IOException("HTTP " + code);
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream()) {
                long got = encryptInto(ctx, in, progress, total);
                if (total > 0 && got != total) throw new IOException("incomplete download");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mercan-Android/" + BuildConfig.VERSION_NAME);
        if (!BuildConfig.MODEL_AUTH_TOKEN.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.MODEL_AUTH_TOKEN);
        }
        return conn;
    }

    /** Copies a user-picked .mercan file (SAF Uri) into the encrypted store. */
    public static void importFrom(Context ctx, Uri uri, Progress progress) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open file");
            encryptInto(ctx, in, progress, -1);
        }
    }

    /** Streams {@code in} into the encrypted model file atomically; returns plaintext byte count. */
    private static long encryptInto(Context ctx, InputStream in, Progress progress, long total) throws IOException {
        File dst = modelFile(ctx);
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
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("could not move model into place");
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_SHA, sha).apply();
        return got;
    }

    /**
     * Decrypts the model into a private temp file for the native loader. The caller must
     * {@link File#delete()} it as soon as the model is loaded (mmap keeps the data alive).
     */
    public static File prepareForLoad(Context ctx, Progress progress) throws IOException {
        cleanTemp(ctx);
        File src = modelFile(ctx);
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
        String expected = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_SHA, null);
        if (expected != null && !expected.equals(sha)) {
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
    }

    /** Wipes the model and the key material that protected it. */
    public static void deleteModel(Context ctx) {
        modelFile(ctx).delete();
        new File(modelDir(ctx), LEGACY_FILE).delete();
        cleanTemp(ctx);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_SHA).apply();
        ModelCrypto.resetKeys(ctx);
    }

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
