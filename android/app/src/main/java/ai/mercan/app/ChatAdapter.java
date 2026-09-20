package ai.mercan.app;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Message list: user bubbles on the right, assistant messages flat with a sender row. */
public final class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {
    public interface Listener {
        void onLongPress(int index, Conversation.Message message, View anchor);
    }

    private static final String CURSOR = "▍"; // ▍

    private final List<Conversation.Message> items;
    private final Listener listener;

    public ChatAdapter(List<Conversation.Message> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void changed(int index) { notifyItemChanged(index, Boolean.TRUE); }
    public void inserted(int index) { notifyItemInserted(index); }
    public void removed(int index) { notifyItemRemoved(index); }

    @Override public int getItemViewType(int position) { return items.get(position).fromUser ? 1 : 0; }
    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == 1 ? R.layout.item_user : R.layout.item_assistant;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Conversation.Message m = items.get(position);
        int codeBg = ContextCompat.getColor(h.itemView.getContext(), R.color.code_bg);
        int brand = ContextCompat.getColor(h.itemView.getContext(), R.color.brand);
        if (m.fromUser) {
            h.text.setText(m.text);
        } else {
            boolean hasThinking = m.thinking.length() > 0;
            boolean hasAnswer = m.text.length() > 0;
            boolean waiting = m.streaming && !hasAnswer;
            if (h.avatar != null) setAvatarBreathing(h.avatar, m.streaming);
            if (h.thinkingBox != null) {
                h.thinkingBox.setVisibility(hasThinking || waiting ? View.VISIBLE : View.GONE);
                boolean expanded = hasThinking && (m.thinkingExpanded || waiting);
                h.thinkingText.setVisibility(expanded ? View.VISIBLE : View.GONE);
                h.thinkingText.setText(waiting ? withCursor(m.thinking, brand) : m.thinking);
                h.thinkingSpinner.setVisibility(waiting ? View.VISIBLE : View.GONE);
                h.thinkingChevron.setVisibility(hasThinking ? View.VISIBLE : View.INVISIBLE);
                h.thinkingChevron.setRotation(expanded ? 180f : 0f);
                h.thinkingLabel.setText(waiting ? (hasThinking ? R.string.thinking_now : R.string.preparing) : R.string.thinking_done);
                h.thinkingHeader.setOnClickListener(v -> {
                    if (m.thinking.length() == 0) return;
                    m.thinkingExpanded = !m.thinkingExpanded;
                    notifyItemChanged(h.getBindingAdapterPosition(), Boolean.TRUE);
                });
            }
            h.text.setVisibility(hasAnswer || !m.streaming ? View.VISIBLE : View.GONE);
            if (hasAnswer) {
                CharSequence rendered = Markdown.render(m.text, codeBg);
                h.text.setText(m.streaming ? withCursor(rendered, brand) : rendered);
            } else {
                h.text.setText("");
            }
        }
        if (h.meta != null) {
            h.meta.setText(m.meta);
            h.meta.setVisibility(m.meta.isEmpty() ? View.GONE : View.VISIBLE);
        }
        View.OnLongClickListener lp = v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) listener.onLongPress(pos, items.get(pos), h.bubble);
            return true;
        };
        h.bubble.setOnLongClickListener(lp);
        h.text.setOnLongClickListener(lp);
    }

    /** Appends the typewriter caret in brand colour. */
    private static CharSequence withCursor(CharSequence text, int color) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int start = sb.length();
        sb.append(CURSOR);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Slow pulse on the mark while the model writes; calm, and stopped as soon as it is done. */
    private static void setAvatarBreathing(View avatar, boolean on) {
        Object running = avatar.getTag(R.id.avatar);
        if (on) {
            if (running instanceof android.animation.Animator && ((android.animation.Animator) running).isRunning()) return;
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(avatar, View.SCALE_X, 1f, 1.12f, 1f);
            android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(avatar, View.SCALE_Y, 1f, 1.12f, 1f);
            android.animation.ObjectAnimator al = android.animation.ObjectAnimator.ofFloat(avatar, View.ALPHA, 1f, 0.72f, 1f);
            for (android.animation.ObjectAnimator a : new android.animation.ObjectAnimator[] { sx, sy, al }) {
                a.setDuration(1900);
                a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            }
            set.playTogether(sx, sy, al);
            set.start();
            avatar.setTag(R.id.avatar, set);
        } else if (running instanceof android.animation.Animator) {
            ((android.animation.Animator) running).cancel();
            avatar.setTag(R.id.avatar, null);
            avatar.setScaleX(1f);
            avatar.setScaleY(1f);
            avatar.setAlpha(1f);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View bubble;
        final TextView text;
        final TextView meta;
        final View thinkingBox;
        final View thinkingHeader;
        final TextView thinkingLabel;
        final TextView thinkingText;
        final View thinkingChevron;
        final ProgressBar thinkingSpinner;
        final View avatar;

        Holder(View v) {
            super(v);
            bubble = v.findViewById(R.id.bubble);
            text = v.findViewById(R.id.message_text);
            meta = v.findViewById(R.id.message_meta);
            thinkingBox = v.findViewById(R.id.thinking_box);
            thinkingHeader = v.findViewById(R.id.thinking_header);
            thinkingLabel = v.findViewById(R.id.thinking_label);
            thinkingText = v.findViewById(R.id.thinking_text);
            thinkingChevron = v.findViewById(R.id.thinking_chevron);
            thinkingSpinner = v.findViewById(R.id.thinking_spinner);
            avatar = v.findViewById(R.id.avatar);
        }
    }
}
