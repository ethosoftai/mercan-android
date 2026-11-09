package ai.mercan.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A Hugging Face model reference typed or pasted by the user. Accepted forms (the same ones the
 * Mercan CLI accepts, plus web links):
 * <pre>
 *   https://huggingface.co/owner/repo/resolve/main/model.mercan[?download=true]
 *   https://huggingface.co/owner/repo/blob/main/model.mercan
 *   https://huggingface.co/owner/repo            (repo page: its .mercan files are listed)
 *   https://huggingface.co/owner/repo/tree/main  (same)
 *   owner/repo                                   (same)
 *   owner/repo:model.mercan
 * </pre>
 */
final class HfModelRef {
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    final String repo;      // owner/name
    final String revision;  // branch, tag or commit
    final String path;      // file inside the repo; null = list the repo's .mercan files

    static final class RemoteFile {
        final String path;
        final long size;    // -1 when unknown
        RemoteFile(String path, long size) { this.path = path; this.size = size; }
    }

    private HfModelRef(String repo, String revision, String path) {
        this.repo = repo;
        this.revision = revision;
        this.path = path;
    }

    HfModelRef withPath(String newPath) { return new HfModelRef(repo, revision, newPath); }

    /** @throws IllegalArgumentException with a user-facing (Turkish) message. */
    static HfModelRef parse(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Bir Hugging Face adresi girin.");
        if (s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8)) {
            return parseUrl(s);
        }
        // Short forms: owner/repo or owner/repo:file.mercan
        String file = null;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            file = s.substring(colon + 1).trim();
            s = s.substring(0, colon).trim();
        }
        String[] parts = s.split("/");
        if (parts.length != 2 || !COMPONENT.matcher(parts[0]).matches() || !COMPONENT.matcher(parts[1]).matches()) {
            throw new IllegalArgumentException("Adres anlaşılamadı. Örnek: MercanAI/Mercan-0.8B-SFT ya da huggingface.co bağlantısı.");
        }
        return new HfModelRef(parts[0] + "/" + parts[1], "main", file == null || file.isEmpty() ? null : checkMercan(file));
    }

    private static HfModelRef parseUrl(String s) {
        URL url;
        try {
            url = new URL(s);
        } catch (IOException e) {
            throw new IllegalArgumentException("Geçersiz bağlantı.");
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("huggingface.co") && !host.equals("www.huggingface.co") && !host.equals("hf.co")) {
            throw new IllegalArgumentException("Yalnızca huggingface.co bağlantıları destekleniyor.");
        }
        List<String> seg = new ArrayList<>();
        for (String p : url.getPath().split("/")) if (!p.isEmpty()) seg.add(decode(p));
        if (!seg.isEmpty() && (seg.get(0).equals("datasets") || seg.get(0).equals("spaces"))) {
            throw new IllegalArgumentException("Bu bir model deposu değil (datasets/spaces).");
        }
        if (seg.size() < 2 || !COMPONENT.matcher(seg.get(0)).matches() || !COMPONENT.matcher(seg.get(1)).matches()) {
            throw new IllegalArgumentException("Bağlantıda depo adı bulunamadı.");
        }
        String repo = seg.get(0) + "/" + seg.get(1);
        if (seg.size() == 2) return new HfModelRef(repo, "main", null);
        String kind = seg.get(2);
        if (kind.equals("tree") && seg.size() >= 4) return new HfModelRef(repo, seg.get(3), null);
        if ((kind.equals("resolve") || kind.equals("blob") || kind.equals("raw")) && seg.size() >= 5) {
            String path = String.join("/", seg.subList(4, seg.size()));
            return new HfModelRef(repo, seg.get(3), checkMercan(path));
        }
        throw new IllegalArgumentException("Bağlantı biçimi tanınmadı.");
    }

    private static String checkMercan(String path) {
        if (path.contains("..") || !path.toLowerCase(Locale.ROOT).endsWith(".mercan")) {
            throw new IllegalArgumentException("Yalnızca .mercan uzantılı model dosyaları indirilebilir.");
        }
        return path;
    }

    private static String decode(String p) {
        try {
            return URLDecoder.decode(p.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return p;
        }
    }

    /** Direct download URL for {@link #path}. */
    String resolveUrl() {
        if (path == null) throw new IllegalStateException("no file selected");
        StringBuilder enc = new StringBuilder();
        for (String part : path.split("/")) {
            if (enc.length() > 0) enc.append('/');
            enc.append(encode(part));
        }
        return "https://huggingface.co/" + repo + "/resolve/" + encode(revision) + "/" + enc + "?download=true";
    }

    /** Stable identity used for de-duplication and the local file name. */
    String key() { return "hf:" + repo + "@" + revision + ":" + path; }

    /** Human-readable name: the repo name, plus the file stem when it is not the default model.mercan. */
    String displayName() {
        String name = repo.substring(repo.indexOf('/') + 1);
        if (path == null) return name;
        String file = path.substring(path.lastIndexOf('/') + 1);
        String stem = file.substring(0, file.length() - ".mercan".length());
        if (stem.equalsIgnoreCase("model") || stem.equalsIgnoreCase(name)) return name;
        // "Mercan-0.8B-DPO-Q4" + "Mercan-0.8B-DPO-Q4_K_M" reads better as just the file stem.
        if (stem.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) return stem;
        return name + " · " + stem;
    }

    /** Lists the .mercan files of the repository (public repos; no token is sent). */
    List<RemoteFile> listMercanFiles() throws IOException {
        URL url = new URL("https://huggingface.co/api/models/" + repo + "/tree/" + encode(revision) + "?recursive=true");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mercan-Android/" + BuildConfig.VERSION_NAME);
            int code = conn.getResponseCode();
            if (code == 401 || code == 403 || code == 404) {
                throw new IOException("Depo bulunamadı ya da erişime kapalı (özel/gated): " + repo);
            }
            if (code != 200) throw new IOException("Hugging Face HTTP " + code);
            JSONArray arr = new JSONArray(readAll(conn.getInputStream()));
            List<RemoteFile> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String p = o.optString("path");
                if (!"file".equals(o.optString("type")) || !p.toLowerCase(Locale.ROOT).endsWith(".mercan")) continue;
                long size = o.optLong("size", -1);
                JSONObject lfs = o.optJSONObject("lfs");
                if (lfs != null) size = lfs.optLong("size", size);
                out.add(new RemoteFile(p, size));
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("Hugging Face yanıtı okunamadı");
        } finally {
            conn.disconnect();
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > (8 << 20)) throw new IOException("listing too large");
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
