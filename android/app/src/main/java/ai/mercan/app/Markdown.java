package ai.mercan.app;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/** Small markdown subset renderer: headings, bullets, **bold**, `code`, ```blocks```. */
public final class Markdown {
    private Markdown() {}

    public static CharSequence render(CharSequence src, int codeBg) {
        String s = src.toString();
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = s.split("\n", -1);
        boolean inCode = false;
        int codeStart = 0;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!inCode) { inCode = true; codeStart = out.length(); }
                else {
                    inCode = false;
                    if (out.length() > codeStart) {
                        out.setSpan(new TypefaceSpan("monospace"), codeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new BackgroundColorSpan(codeBg), codeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new RelativeSizeSpan(0.92f), codeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                continue;
            }
            if (inCode) {
                out.append(line);
                if (li < lines.length - 1) out.append('\n');
                continue;
            }
            int heading = 0;
            while (heading < line.length() && heading < 4 && line.charAt(heading) == '#') heading++;
            if (heading > 0 && heading < line.length() && line.charAt(heading) == ' ') {
                int start = out.length();
                appendInline(out, line.substring(heading + 1).trim(), codeBg);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(heading == 1 ? 1.18f : 1.08f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
                int indent = line.indexOf(trimmed.charAt(0));
                for (int i = 0; i < indent / 2; i++) out.append("    ");
                out.append("•  ");
                appendInline(out, trimmed.substring(2), codeBg);
            } else {
                appendInline(out, line, codeBg);
            }
            if (li < lines.length - 1) out.append('\n');
        }
        if (inCode && out.length() > codeStart) {
            out.setSpan(new TypefaceSpan("monospace"), codeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(codeBg), codeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** **bold** and `code` inside one line. */
    private static void appendInline(SpannableStringBuilder out, String line, int codeBg) {
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '`') {
                int end = line.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(line, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(codeBg), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            } else if (c == '*' && i + 1 < n && line.charAt(i + 1) == '*') {
                int end = line.indexOf("**", i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    appendInline(out, line.substring(i + 2, end), codeBg);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }
}
