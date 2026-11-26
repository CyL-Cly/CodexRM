package com.codexrm.runtime;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class RuntimeActivity extends Activity {
    private TextView status;
    private Button enable;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The activity owns its title and insets so the platform ActionBar cannot cover the content.
        getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.runtime_title);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Title);
        title.setTextSize(24);
        title.setAccessibilityHeading(true);
        column.addView(title, fullWidth(0));

        status = new TextView(this);
        status.setTextAppearance(android.R.style.TextAppearance_Material_Body2);
        status.setTextSize(18);
        status.setTypeface(status.getTypeface(), Typeface.BOLD);
        column.addView(status, fullWidth(24));

        TextView description = new TextView(this);
        description.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        description.setTextSize(16);
        description.setText(R.string.runtime_permission_description);
        column.addView(description, fullWidth(8));

        enable = new Button(this);
        enable.setText(R.string.runtime_permission_settings);
        enable.setMinHeight(dp(48));
        enable.setOnClickListener(v -> openFilesSettings());
        column.addView(enable, fullWidth(16));
        setContentView(scroll);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams fullWidth(int top) {
        var params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    @Override protected void onResume() {
        super.onResume();
        boolean allowed = Environment.isExternalStorageManager();
        status.setText(allowed ? R.string.runtime_permission_granted : R.string.runtime_permission_required);
        enable.setEnabled(!allowed);
    }

    private void openFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (android.content.ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }
}
