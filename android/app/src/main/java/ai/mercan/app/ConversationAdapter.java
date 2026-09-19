package ai.mercan.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Drawer list of saved conversations, grouped by day (Bugün / Dün / Önceki 7 gün / Daha eski). */
public final class ConversationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onOpen(Conversation c);
        void onMenu(Conversation c, View anchor);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private static final class Row {
        final String header;
        final Conversation conv;
        Row(String header, Conversation conv) { this.header = header; this.conv = conv; }
    }

    private final List<Conversation> all = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Listener listener;
    private String selectedId = "";
    private String filter = "";

    public ConversationAdapter(Listener listener) { this.listener = listener; }

    public void setItems(List<Conversation> list, String selected) {
        all.clear();
        all.addAll(list);
        selectedId = selected == null ? "" : selected;
        rebuild();
    }

    public void setSelected(String id) {
        selectedId = id == null ? "" : id;
        notifyDataSetChanged();
    }

    public void setFilter(String query) {
        filter = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        rebuild();
    }

    public int visibleCount() { return rows.size(); }

    private void rebuild() {
        rows.clear();
        String lastHeader = null;
        for (Conversation c : all) {
            if (!filter.isEmpty() && !c.displayTitle("").toLowerCase(Locale.getDefault()).contains(filter)) continue;
            String h = groupOf(c.updated);
            if (!h.equals(lastHeader)) { rows.add(new Row(h, null)); lastHeader = h; }
            rows.add(new Row(null, c));
        }
        notifyDataSetChanged();
    }

    private static String groupOf(long when) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(when);
        long dayNow = now.get(Calendar.YEAR) * 400L + now.get(Calendar.DAY_OF_YEAR);
        long dayT = t.get(Calendar.YEAR) * 400L + t.get(Calendar.DAY_OF_YEAR);
        long diffDays = (now.getTimeInMillis() - when) / (24L * 3600L * 1000L);
        if (dayNow == dayT) return "today";
        if (dayNow - dayT == 1 || (diffDays < 2 && dayNow != dayT)) return "yesterday";
        if (diffDays < 7) return "week";
        return "older";
    }

    @Override public int getItemViewType(int position) { return rows.get(position).conv == null ? TYPE_HEADER : TYPE_ITEM; }
    @Override public int getItemCount() { return rows.size(); }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater li = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) return new HeaderHolder(li.inflate(R.layout.item_conversation_header, parent, false));
        return new ItemHolder(li.inflate(R.layout.item_conversation, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row r = rows.get(position);
        if (holder instanceof HeaderHolder) {
            int res;
            switch (r.header) {
                case "today": res = R.string.group_today; break;
                case "yesterday": res = R.string.group_yesterday; break;
                case "week": res = R.string.group_week; break;
                default: res = R.string.group_older;
            }
            ((HeaderHolder) holder).text.setText(res);
            return;
        }
        ItemHolder h = (ItemHolder) holder;
        Conversation c = r.conv;
        h.title.setText(c.displayTitle(h.itemView.getContext().getString(R.string.untitled_chat)));
        h.itemView.setSelected(c.id.equals(selectedId));
        h.itemView.setOnClickListener(v -> listener.onOpen(c));
        h.itemView.setOnLongClickListener(v -> { listener.onMenu(c, v); return true; });
        h.more.setOnClickListener(v -> listener.onMenu(c, v));
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderHolder(View v) { super(v); text = v.findViewById(R.id.header); }
    }

    static final class ItemHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final View more;
        ItemHolder(View v) {
            super(v);
            title = v.findViewById(R.id.conv_title);
            more = v.findViewById(R.id.conv_more);
        }
    }
}
