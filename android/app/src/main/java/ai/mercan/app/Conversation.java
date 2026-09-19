package ai.mercan.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One saved chat: a title plus an ordered list of user/assistant messages. */
public final class Conversation {
    public static final class Message {
        public final boolean fromUser;
        public final StringBuilder text = new StringBuilder();
        public final StringBuilder thinking = new StringBuilder();
        public String meta = "";
        /** True while the reply is still streaming (drives the "thinking…" spinner). */
        public boolean streaming = false;
        /** Whether the thinking section is expanded in the UI. */
        public boolean thinkingExpanded = false;

        public Message(boolean fromUser, String initial) {
            this.fromUser = fromUser;
            text.append(initial);
        }
    }

    public final String id;
    public String title;
    public final long created;
    public long updated;
    public final List<Message> messages = new ArrayList<>();

    public Conversation(String id, String title, long created, long updated) {
        this.id = id;
        this.title = title;
        this.created = created;
        this.updated = updated;
    }

    public static Conversation create() {
        long now = System.currentTimeMillis();
        return new Conversation(Long.toString(now), "", now, now);
    }

    public boolean isEmpty() { return messages.isEmpty(); }

    public String displayTitle(String fallback) {
        return title == null || title.trim().isEmpty() ? fallback : title;
    }

    /** First user message, shortened, used as the automatic title. */
    public static String titleFrom(String userText) {
        String t = userText.trim().replaceAll("\\s+", " ");
        return t.length() > 42 ? t.substring(0, 42).trim() + "…" : t;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("created", created);
        o.put("updated", updated);
        JSONArray arr = new JSONArray();
        for (Message m : messages) {
            JSONObject mo = new JSONObject();
            mo.put("role", m.fromUser ? "user" : "assistant");
            mo.put("text", m.text.toString());
            if (m.thinking.length() > 0) mo.put("thinking", m.thinking.toString());
            if (!m.meta.isEmpty()) mo.put("meta", m.meta);
            arr.put(mo);
        }
        o.put("messages", arr);
        return o;
    }

    public static Conversation fromJson(JSONObject o) throws JSONException {
        Conversation c = new Conversation(o.getString("id"), o.optString("title", ""),
                o.optLong("created", 0), o.optLong("updated", 0));
        JSONArray arr = o.optJSONArray("messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject mo = arr.getJSONObject(i);
                Message m = new Message("user".equals(mo.optString("role")), mo.optString("text", ""));
                m.thinking.append(mo.optString("thinking", ""));
                m.meta = mo.optString("meta", "");
                c.messages.add(m);
            }
        }
        return c;
    }
}
