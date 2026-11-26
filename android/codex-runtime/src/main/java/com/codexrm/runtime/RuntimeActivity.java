package com.codexrm.runtime;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class RuntimeActivity extends Activity {
    private TextView status;
    private Button enable;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        setContentView(column);
        status = new TextView(this);
        status.setTextSize(18);
        column.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        enable = new Button(this);
        enable.setText("打开所有文件访问权限设置");
        enable.setOnClickListener(v -> openFilesSettings());
        column.addView(enable);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean allowed = Environment.isExternalStorageManager();
        status.setText(allowed ? "文件权限已开启。请返回 CodexRM，选择「本机 Codex」。" :
            "本机 Codex 需要访问设备上的工作区文件。此权限不允许读取其他应用的私有数据，可以随时在系统设置撤销。");
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
