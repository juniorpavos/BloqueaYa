package com.celso.bloqueaya;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status, counter;
    private Button toggle;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(18, 38, 74));
        root.setPadding(60, 60, 60, 60);

        TextView title = new TextView(this);
        title.setText("BloqueaYa");
        title.setTextSize(36); title.setTextColor(Color.WHITE); title.setGravity(Gravity.CENTER);
        status = new TextView(this);
        status.setTextSize(20); status.setGravity(Gravity.CENTER); status.setPadding(0, 40, 0, 20);
        counter = new TextView(this);
        counter.setTextSize(16); counter.setTextColor(Color.rgb(200, 210, 230)); counter.setGravity(Gravity.CENTER);
        counter.setPadding(0, 0, 0, 60);

        toggle = new Button(this);
        toggle.setTextSize(22); toggle.setTextColor(Color.WHITE); toggle.setAllCaps(false);
        toggle.setPadding(80, 40, 80, 40);
        toggle.setOnClickListener(v -> {
            if (BlockVpnService.running) {
                startService(new Intent(this, BlockVpnService.class).setAction("STOP"));
                h.postDelayed(this::refresh, 400);
            } else {
                Intent p = VpnService.prepare(this);
                if (p != null) startActivityForResult(p, 7); else onActivityResult(7, RESULT_OK, null);
            }
        });
        root.addView(title); root.addView(status); root.addView(counter); root.addView(toggle);
        setContentView(root);
        tick();
    }

    private void tick() { refresh(); h.postDelayed(this::tick, 1000); }

    private void refresh() {
        boolean on = BlockVpnService.running;
        status.setText(on ? "Protección ACTIVA" : "Protección apagada");
        status.setTextColor(on ? Color.rgb(0, 200, 170) : Color.rgb(255, 120, 120));
        counter.setText("Anuncios bloqueados: " + BlockVpnService.blocked);
        toggle.setText(on ? "Apagar" : "Activar");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(60); g.setColor(on ? Color.rgb(200, 60, 60) : Color.rgb(0, 168, 150));
        toggle.setBackground(g);
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == 7 && res == RESULT_OK) {
            Intent i = new Intent(this, BlockVpnService.class).setAction("START");
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            h.postDelayed(this::refresh, 600);
        }
    }
}
