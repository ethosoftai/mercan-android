package ai.mercan.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists conversations as one JSON file each under filesDir/chats. */
public final class ChatStore {
    private final File dir;

    public ChatStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "chats");
        if (!dir.exists()) dir.mkdirs();
    }

    private File fileFor(String id) { return new File(dir, id + ".json"); }

    /** Summaries (without messages loaded) sorted newest first. */
    public List<Conversation> list() {
        List<Conversation> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            try {
                Conversation c = load(f);
                if (c != null && !c.isEmpty()) out.add(c);
            } catch (Exception ignored) {
                // A corrupt file must not take the whole list down.
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    public Conversation load(String id) {
        try { return load(fileFor(id)); } catch (Exception e) { return null; }
    }

    private Conversation load(File f) throws Exception {
        if (!f.isFile()) return null;
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return Conversation.fromJson(new JSONObject(json));
    }

    public void save(Conversation c) {
        if (c.isEmpty()) { delete(c.id); return; }
        File tmp = new File(dir, c.id + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(c.toJson().toString());
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        File dst = fileFor(c.id);
        if (!tmp.renameTo(dst)) {
            dst.delete();
            tmp.renameTo(dst);
        }
    }

    public void delete(String id) {
        fileFor(id).delete();
    }

    public void deleteAll() throws IOException {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}
