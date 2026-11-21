package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.Layout;
import com.strickland.jeepdash.core.Param;

import java.util.List;
import java.util.Locale;

/** What a gauge shows, how it looks, and its warning flash. Opened by tapping a gauge in edit mode. */
final class GaugeSettingsDialog {
    private GaugeSettingsDialog() { }

    private static final String[] STYLES = {"Dial", "Digital", "Bar"};

    static void show(Activity a, final Layout layout, final GaugeSpec g, final Runnable onChanged) {
        final List<Param> params = Catalog.all();
        float d = a.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * d);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        box.addView(label(a, "Shows"));
        final Spinner what = new Spinner(a);
        String[] names = new String[params.size()];
        int current = 0;
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            String line = p.pid == null ? "  ·  Jeep line only" : p.sciIds == null ? "  ·  generic line" : "";
            names[i] = p.name + " (" + p.unit + ")" + line;
            if (p.id.equals(g.paramId)) current = i;
        }
        what.setAdapter(adapter(a, names));
        what.setSelection(current);
        box.addView(what);

        box.addView(label(a, "Style"));
        final Spinner style = new Spinner(a);
        style.setAdapter(adapter(a, STYLES));
        style.setSelection(g.type == GaugeType.DIAL ? 0 : g.type == GaugeType.DIGITAL ? 1 : 2);
        box.addView(style);

        final CheckBox warn = new CheckBox(a);
        warn.setText("Flash a warning when the reading goes");
        warn.setChecked(g.warnOn);
        warn.setPadding(0, pad / 2, 0, 0);
        box.addView(warn);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Spinner dir = new Spinner(a);
        dir.setAdapter(adapter(a, new String[]{"above", "below"}));
        dir.setSelection(g.warnAbove ? 0 : 1);
        row.addView(dir);
        final EditText limit = new EditText(a);
        limit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        limit.setEms(6);
        limit.setText(g.warnOn || g.warnLimit != 0 ? number(g.warnLimit) : "");
        row.addView(limit);
        final TextView unit = new TextView(a);
        unit.setText(params.get(current).unit);
        unit.setPadding(pad / 2, 0, 0, 0);
        row.addView(unit);
        box.addView(row);

        TextView hint = new TextView(a);
        hint.setText("Gauges update fastest when they all come from one line. Next to a generic-line gauge, "
                + "a Jeep-line-only value updates about once a minute.");
        hint.setPadding(0, pad / 2, 0, 0);
        hint.setTextSize(12);
        box.addView(hint);

        what.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                Param p = params.get(pos);
                unit.setText(p.unit);
                if (limit.getText().length() == 0 || !p.id.equals(g.paramId))
                    limit.setText(number(Math.round(p.min + 0.85 * (p.max - p.min))));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("Gauge")
                .setView(scroll)
                .setPositiveButton("OK", (dlg, w) -> {
                    g.paramId = params.get(what.getSelectedItemPosition()).id;
                    int s = style.getSelectedItemPosition();
                    g.type = s == 0 ? GaugeType.DIAL : s == 1 ? GaugeType.DIGITAL : GaugeType.BAR;
                    g.warnAbove = dir.getSelectedItemPosition() == 0;
                    Double lim = parse(limit.getText().toString());
                    if (lim != null) g.warnLimit = lim;
                    g.warnOn = warn.isChecked() && lim != null;
                    onChanged.run();
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Delete gauge", (dlg, w) -> {
                    layout.remove(g.key);
                    onChanged.run();
                })
                .show();
    }

    private static TextView label(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(13);
        t.setPadding(0, Math.round(10 * a.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static ArrayAdapter<String> adapter(Activity a, String[] items) {
        ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return ad;
    }

    private static String number(double v) {
        if (Math.abs(v - Math.round(v)) < 0.0001) return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.2f", v);
    }

    private static Double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
