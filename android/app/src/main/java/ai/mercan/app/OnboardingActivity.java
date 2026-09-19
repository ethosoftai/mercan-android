package ai.mercan.app;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** First-launch introduction (three short pages). Shown once; "Atla" skips it. */
public class OnboardingActivity extends AppCompatActivity {
    private static final int[] TITLES = {R.string.onb_title_1, R.string.onb_title_2, R.string.onb_title_3};
    private static final int[] BODIES = {R.string.onb_body_1, R.string.onb_body_2, R.string.onb_body_3};
    private static final int[] ICONS = {R.drawable.ic_mercan_mark, R.drawable.ic_bulb_on, R.drawable.ic_history};

    private int page = 0;
    private View pageView;
    private View iconBox;
    private ImageView icon;
    private TextView title;
    private TextView body;
    private TextView next;
    private LinearLayout dots;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_onboarding);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(!night);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(!night);
        if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);

        pageView = findViewById(R.id.page);
        iconBox = findViewById(R.id.icon_box);
        icon = findViewById(R.id.icon);
        title = findViewById(R.id.page_title);
        body = findViewById(R.id.page_body);
        next = findViewById(R.id.next);
        dots = findViewById(R.id.dots);
        findViewById(R.id.skip).setOnClickListener(v -> finishOnboarding());
        next.setOnClickListener(v -> {
            if (page < TITLES.length - 1) show(page + 1, true);
            else finishOnboarding();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (page > 0) show(page - 1, true);
                else finishOnboarding();
            }
        });
        show(0, false);
    }

    private void show(int index, boolean animate) {
        page = index;
        Runnable apply = () -> {
            icon.setImageResource(ICONS[index]);
            icon.setColorFilter(getResources().getColor(R.color.brand, getTheme()));
            // Page 1: the mark itself, big and bare. Other pages: a smaller icon in a white disc.
            float dp = getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams ip = icon.getLayoutParams();
            if (index == 0) {
                iconBox.setBackground(null);
                ip.width = (int) (180 * dp); ip.height = (int) (200 * dp);
            } else {
                iconBox.setBackgroundResource(R.drawable.bg_disc);
                ip.width = (int) (72 * dp); ip.height = (int) (72 * dp);
            }
            icon.setLayoutParams(ip);
            title.setText(TITLES[index]);
            body.setText(BODIES[index]);
            next.setText(index == TITLES.length - 1 ? R.string.start : R.string.next);
            dots.removeAllViews();
            int gap = (int) (6 * dp), h = (int) (8 * dp);
            for (int i = 0; i < TITLES.length; i++) {
                View d = new View(this);
                boolean active = i == index;
                d.setBackgroundResource(active ? R.drawable.dot_active : R.drawable.dot_inactive);
                // A bare View measures to the whole available space, so size the dots explicitly.
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) ((active ? 22 : 8) * dp), h);
                lp.setMarginEnd(gap);
                dots.addView(d, lp);
            }
        };
        if (!animate) { apply.run(); return; }
        pageView.animate().alpha(0f).translationX(-24f).setDuration(120).withEndAction(() -> {
            apply.run();
            pageView.setTranslationX(24f);
            pageView.animate().alpha(1f).translationX(0f).setDuration(180).start();
        }).start();
    }

    private void finishOnboarding() {
        Prefs p = new Prefs(this);
        p.onboarded = true;
        p.save();
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
