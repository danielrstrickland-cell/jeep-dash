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
import com.strickland.jeepdash.core.HoloFace;
import com.strickland.jeepdash.core.Layout;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.core.PorscheCatalog;
import com.strickland.jeepdash.core.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a gauge shows (and, for boost, how it is worked out), the unit it is shown in, its range and red zone,
 * how it looks, and its warning flash. Opened by tapping a gauge in edit mode. The limit, range and red zone are
 * typed in the unit shown and kept in the value's own unit.
 */
final class GaugeSettingsDialog {
    private GaugeSettingsDialog() { }

    static void show(Activity a, final Layout layout, final GaugeSpec g, String look, final Runnable onChanged) {
        final boolean holo = HoloFace.isHolo(look);
        final GaugeType[] types = HoloFace.styles(look);
        final String[] styleNames = HoloFace.styleNames(look);
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

        // boost and peak boost: how the reading is worked out (PorscheCatalog.BOOST_FROM)
        final TextView fromLabel = label(a, "Calculated from");
        box.addView(fromLabel);
        final Spinner from = new Spinner(a);
        from.setAdapter(adapter(a, PorscheCatalog.BOOST_FROM));
        box.addView(from);
        showFrom(fromLabel, from, params.get(current).id);

        // only for values that have other units (see Units); the base unit is always the first choice
        final TextView unitLabel = label(a, "Unit");
        box.addView(unitLabel);
        final Spinner unitPick = new Spinner(a);
        box.addView(unitPick);
        final List<String> units = new ArrayList<>(Units.choices(params.get(current).unit));
        // the unit the limit box is in right now
        final String[] shownUnit = {Units.offers(params.get(current).unit, g.unit) ? g.unit : params.get(current).unit};
        showUnits(a, unitLabel, unitPick, units, shownUnit[0]);

        // the owner's own scale and red zone; empty = the value's standard range, no red zone of its own
        final Param start = Units.display(params.get(current), shownUnit[0]);
        box.addView(label(a, "Range (empty = standard)"));
        LinearLayout rangeRow = new LinearLayout(a);
        rangeRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText lo = numberBox(a), hi = numberBox(a);
        lo.setHint(number(start.min));
        hi.setHint(number(start.max));
        if (g.rangeMin != null) lo.setText(number(Units.convert(g.rangeMin, params.get(current).unit, shownUnit[0])));
        if (g.rangeMax != null) hi.setText(number(Units.convert(g.rangeMax, params.get(current).unit, shownUnit[0])));
        rangeRow.addView(lo);
        TextView toWord = new TextView(a);
        toWord.setText("to");
        toWord.setPadding(pad / 2, 0, pad / 2, 0);
        rangeRow.addView(toWord);
        rangeRow.addView(hi);
        final TextView rangeUnit = new TextView(a);
        rangeUnit.setText(shownUnit[0]);
        rangeUnit.setPadding(pad / 2, 0, 0, 0);
        rangeRow.addView(rangeUnit);
        box.addView(rangeRow);

        box.addView(label(a, "Red zone from (empty = none; for boost, mark overboost)"));
        LinearLayout redRow = new LinearLayout(a);
        redRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText red = numberBox(a);
        if (g.redFrom != null) red.setText(number(Units.convert(g.redFrom, params.get(current).unit, shownUnit[0])));
        redRow.addView(red);
        final TextView redUnit = new TextView(a);
        redUnit.setText(shownUnit[0]);
        redUnit.setPadding(pad / 2, 0, 0, 0);
        redRow.addView(redUnit);
        box.addView(redRow);

        box.addView(label(a, "Style"));
        final Spinner style = new Spinner(a);
        style.setAdapter(adapter(a, styleNames));
        style.setSelection(Math.max(0, java.util.Arrays.asList(types).indexOf(g.type)));
        box.addView(style);

        // the Holo look: how far in front of or behind the truck the gauge floats
        final Spinner depth = new Spinner(a);
        if (holo) {
            box.addView(label(a, "Depth"));
            depth.setAdapter(adapter(a, HoloFace.DEPTH_NAMES));
            depth.setSelection(Math.max(0, Math.min(4, g.depth + 2)));
            box.addView(depth);
        }

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
                showFrom(fromLabel, from, p.id);
                if (!sameFamily(p.id, g.paramId)) {
                    lo.setText("");
                    hi.setText("");
                    red.setText("");
                }
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
                rangeUnit.setText(shownUnit[0]);
                redUnit.setText(shownUnit[0]);
                Param shownP = Units.display(p, shownUnit[0]);
                lo.setHint(number(shownP.min));
                hi.setHint(number(shownP.max));
                if (limit.getText().length() == 0 || !p.id.equals(g.paramId)) {
                    Param d = Units.display(p, shownUnit[0]);
                    limit.setText(number(Math.round(d.min + 0.85 * (d.max - d.min))));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        from.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String[] fam = PorscheCatalog.boostFamily(params.get(what.getSelectedItemPosition()).id);
                if (fam == null || pos < 0 || pos >= fam.length) return;
                for (int i = 0; i < params.size(); i++)
                    if (params.get(i).id.equals(fam[pos]) && i != what.getSelectedItemPosition()) what.setSelection(i);
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
                for (EditText e : new EditText[]{lo, hi, red}) {
                    Double x = parse(e.getText().toString());
                    if (x != null) e.setText(number(Units.convert(x, shownUnit[0], to)));
                }
                Param base = params.get(what.getSelectedItemPosition()), shownP = Units.display(base, to);
                lo.setHint(number(shownP.min));
                hi.setHint(number(shownP.max));
                shownUnit[0] = to;
                unit.setText(to);
                rangeUnit.setText(to);
                redUnit.setText(to);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        HoloDialogs.show(HoloDialogs.builder(a, holo)
                .setTitle("Gauge")
                .setView(scroll)
                .setPositiveButton("OK", (dlg, w) -> {
                    Param chosen = params.get(what.getSelectedItemPosition());
                    g.paramId = chosen.id;
                    g.unit = Units.offers(chosen.unit, shownUnit[0]) && !shownUnit[0].equals(chosen.unit) ? shownUnit[0] : null;
                    g.type = types[Math.max(0, style.getSelectedItemPosition())];
                    if (holo) g.depth = depth.getSelectedItemPosition() - 2;
                    g.warnAbove = dir.getSelectedItemPosition() == 0;
                    Double lim = parse(limit.getText().toString());
                    // typed in the unit shown; kept in the value's own unit
                    if (lim != null) lim = Units.offers(chosen.unit, shownUnit[0]) ? Units.convert(lim, shownUnit[0], chosen.unit) : lim;
                    if (lim != null) g.warnLimit = lim;
                    g.warnOn = warn.isChecked() && lim != null;
                    // range and red zone: typed in the unit shown, kept in the value's own; an upside-down range is dropped
                    Double rl = own(parse(lo.getText().toString()), chosen.unit, shownUnit[0]);
                    Double rh = own(parse(hi.getText().toString()), chosen.unit, shownUnit[0]);
                    if (rl != null && rh != null && rh <= rl) rl = rh = null;
                    g.rangeMin = rl;
                    g.rangeMax = rh;
                    g.redFrom = own(parse(red.getText().toString()), chosen.unit, shownUnit[0]);
                    onChanged.run();
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton(holo ? "Hide gauge" : "Delete gauge", (dlg, w) -> {
                    layout.remove(g.key);
                    onChanged.run();
                }), holo, true);
    }

    /** Shows the "Calculated from" picker on its choice for a boost value; hides it for everything else. */
    private static void showFrom(TextView label, Spinner pick, String id) {
        String[] fam = PorscheCatalog.boostFamily(id);
        int vis = fam == null ? View.GONE : View.VISIBLE;
        label.setVisibility(vis);
        pick.setVisibility(vis);
        if (fam == null) return;
        int at = java.util.Arrays.asList(fam).indexOf(id);
        if (at >= 0 && pick.getSelectedItemPosition() != at) pick.setSelection(at);
    }

    /** True for the same value, or two ways of working out the same boost gauge. */
    private static boolean sameFamily(String a, String b) {
        if (a.equals(b)) return true;
        String[] fa = PorscheCatalog.boostFamily(a);
        return fa != null && java.util.Arrays.asList(fa).contains(b);
    }

    /** A figure typed in the unit shown, in the value's own unit (left alone when the unit is not one of its). */
    private static Double own(Double typed, String ownUnit, String shown) {
        if (typed == null) return null;
        return Units.offers(ownUnit, shown) ? Units.convert(typed, shown, ownUnit) : typed;
    }

    private static EditText numberBox(Activity a) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setEms(5);
        return e;
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
