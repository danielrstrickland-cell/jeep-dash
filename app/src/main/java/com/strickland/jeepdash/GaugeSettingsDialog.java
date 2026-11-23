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
import com.strickland.jeepdash.core.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a gauge shows, the unit it is shown in, how it looks, and its warning flash. Opened by tapping a gauge
 * in edit mode. The warning limit is typed in the unit shown and kept in the value's own unit.
 */
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
            String line = Catalog.porsche() ? "" : p.tablet ? "  ·  from the tablet"
                    : p.pid == null ? "  ·  Jeep line only" : p.sciIds == null ? "  ·  generic line" : "";
            names[i] = p.name + " (" + p.unit + ")" + line;
            if (p.id.equals(g.paramId)) current = i;
        }
        what.setAdapter(adapter(a, names));
        what.setSelection(current);
        box.addView(what);

        // only for values that have other units (see Units); the base unit is always the first choice
        final TextView unitLabel = label(a, "Unit");
        box.addView(unitLabel);
        final Spinner unitPick = new Spinner(a);
        box.addView(unitPick);
        final List<String> units = new ArrayList<>(Units.choices(params.get(current).unit));
        // the unit the limit box is in right now
        final String[] shownUnit = {Units.offers(params.get(current).unit, g.unit) ? g.unit : params.get(current).unit};
        showUnits(a, unitLabel, unitPick, units, shownUnit[0]);

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
        limit.setText(g.warnOn || g.warnLimit != 0
                ? number(Units.convert(g.warnLimit, params.get(current).unit, shownUnit[0])) : "");
        row.addView(limit);
        final TextView unit = new TextView(a);
        unit.setText(shownUnit[0]);
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
                List<String> choices = Units.choices(p.unit);
                String keep = p.id.equals(g.paramId) && Units.offers(p.unit, g.unit) ? g.unit : p.unit;
                if (!choices.equals(units) || !keep.equals(shownUnit[0])) {
                    // a different value: back to its own unit (or the gauge's saved one), limit and all
                    units.clear();
                    units.addAll(choices);
                    if (limit.getText().length() > 0 && Units.offers(p.unit, shownUnit[0])) {
                        Double lim = parse(limit.getText().toString());
                        if (lim != null) limit.setText(number(Units.convert(lim, shownUnit[0], keep)));
                    }
                    shownUnit[0] = keep;
                    showUnits(a, unitLabel, unitPick, choices, keep);
                }
                unit.setText(shownUnit[0]);
                if (limit.getText().length() == 0 || !p.id.equals(g.paramId)) {
                    Param d = Units.display(p, shownUnit[0]);
                    limit.setText(number(Math.round(d.min + 0.85 * (d.max - d.min))));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        unitPick.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= units.size()) return;
                String to = units.get(pos);
                if (to.equals(shownUnit[0])) return;
                Double lim = parse(limit.getText().toString());
                if (lim != null) limit.setText(number(Units.convert(lim, shownUnit[0], to)));
                shownUnit[0] = to;
                unit.setText(to);
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
                    Param chosen = params.get(what.getSelectedItemPosition());
                    g.paramId = chosen.id;
                    g.unit = Units.offers(chosen.unit, shownUnit[0]) && !shownUnit[0].equals(chosen.unit) ? shownUnit[0] : null;
                    int s = style.getSelectedItemPosition();
                    g.type = s == 0 ? GaugeType.DIAL : s == 1 ? GaugeType.DIGITAL : GaugeType.BAR;
                    g.warnAbove = dir.getSelectedItemPosition() == 0;
                    Double lim = parse(limit.getText().toString());
                    // typed in the unit shown; kept in the value's own unit
                    if (lim != null) lim = Units.offers(chosen.unit, shownUnit[0]) ? Units.convert(lim, shownUnit[0], chosen.unit) : lim;
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

    /** Fills the unit picker, or hides it when the value has no other units. */
    private static void showUnits(Activity a, TextView label, Spinner pick, List<String> choices, String selected) {
        int vis = choices.isEmpty() ? View.GONE : View.VISIBLE;
        label.setVisibility(vis);
        pick.setVisibility(vis);
        if (choices.isEmpty()) return;
        pick.setAdapter(adapter(a, choices.toArray(new String[0])));
        pick.setSelection(Math.max(0, choices.indexOf(selected)));
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
