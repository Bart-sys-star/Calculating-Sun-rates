import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

/**
 * Desktop calculator for pp-chain and CNO-cycle energy generation rates in a stellar core.
 * Move the temperature slider (or type a value) and the readouts, chart and table update live.
 */
public class FusionRatesApp extends JFrame {

    // Palette (blue = pp chain, orange = CNO cycle)
    static final Color PP = new Color(0x2A78D6);
    static final Color CNO = new Color(0xEB6834);
    static final Color INK = new Color(0x111722);
    static final Color INK2 = new Color(0x4D5767);
    static final Color INK3 = new Color(0x6B7484);
    static final Color LINE = new Color(0xDCE1E8);
    static final Color GRID = new Color(0xE9EDF2);
    static final Color BG = new Color(0xF4F6F9);
    static final Color SURFACE = Color.WHITE;
    static final Color WARN_BG = new Color(0xFFF4E5);
    static final Color WARN_INK = new Color(0x8A4B00);

    static final class Preset {
        final String name;
        final double t, rho, x, xCno;

        Preset(String name, double t, double rho, double x, double xCno) {
            this.name = name; this.t = t; this.rho = rho; this.x = x; this.xCno = xCno;
        }

        @Override public String toString() { return name; }
    }

    static final Preset CUSTOM = new Preset("Custom", Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    static final Preset[] PRESETS = {
        new Preset("Sun (core)", 15.7, 1.527e5, 0.34, 0.01),
        new Preset("Red dwarf, 0.3 solar masses", 7.5, 1.0e5, 0.70, 0.01),
        new Preset("F star, 1.5 solar masses", 20, 8.0e4, 0.70, 0.01),
        new Preset("B star, 10 solar masses", 30, 1.0e4, 0.70, 0.01),
        new Preset("Metal-poor star", 18, 1.0e5, 0.75, 0.0005),
    };

    // ---------------------------------------------------------------- state
    double t = 15.7;
    final FusionRates.Params p = new FusionRates.Params(1.527e5, 0.34, 0.01, 1, 1);
    boolean cgs = false;
    boolean valid = true;
    private boolean syncing = false;

    // ---------------------------------------------------------------- widgets
    private final JComboBox<Preset> presetBox = new JComboBox<Preset>();
    private final JSlider slider = new JSlider(0, 1000);
    private final JTextField tField = field();
    private final JLabel tBig = new JLabel();
    private final JTextField rhoF = field(), xF = field(), xCnoF = field(), fppF = field(), psiF = field();
    private final JLabel rhoCgs = small("");
    private final JComboBox<String> units = new JComboBox<String>(new String[] {"W/kg (SI)", "erg/g/s (cgs)"});
    private final JLabel err = new JLabel();

    private final JLabel head = new JLabel();
    private final JLabel vPP = value(), vCNO = value(), vTot = value();
    private final JLabel uPP = small(""), uCNO = small(""), uTot = small("");
    private final JLabel domLabel = new JLabel();
    private final JLabel pctPP = small(""), pctCNO = small("");
    private final ShareBar bar = new ShareBar();
    private final JLabel nuPP = fact(), nuCNO = fact(), cross = fact(), perVol = fact(), hRate = fact(), life = fact();

    private final ChartPanel chart = new ChartPanel(this);
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[] {"T (million K)", "pp rate", "CNO rate", "Total", "CNO / pp", "Dominant"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextField tbFrom = field(), tbTo = field(), tbStep = field();

    public FusionRatesApp() {
        super("Hydrogen Burning Rates: pp chain vs CNO cycle");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(16, 16));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JScrollPane controls = new JScrollPane(buildControls());
        controls.setBorder(BorderFactory.createLineBorder(LINE));
        controls.setPreferredSize(new Dimension(320, 10));
        controls.getVerticalScrollBar().setUnitIncrement(16);
        root.add(controls, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout(0, 16));
        center.setOpaque(false);
        center.add(buildResults(), BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Chart: rate vs temperature", chart);
        tabs.addTab("Table: rates across temperatures", buildTable());
        center.add(tabs, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        setContentPane(root);
        setSize(1240, 860);
        setMinimumSize(new Dimension(980, 700));
        setLocationRelativeTo(null);

        syncInputs();
        update();
    }

    // ------------------------------------------------------------ builders

    private JPanel buildControls() {
        JPanel c = new JPanel(new GridBagLayout());
        c.setBackground(SURFACE);
        c.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = 0; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 6, 0);

        add(c, g, heading("Core conditions"));
        g.insets = new Insets(10, 0, 4, 0);
        add(c, g, label("Example stars (approximate)"));
        g.insets = new Insets(0, 0, 6, 0);
        presetBox.addItem(CUSTOM);
        for (Preset pr : PRESETS) presetBox.addItem(pr);
        presetBox.addActionListener(e -> {
            if (syncing) return;
            Preset pr = (Preset) presetBox.getSelectedItem();
            if (pr == null || pr == CUSTOM) return;
            t = pr.t; p.rho = pr.rho; p.x = pr.x; p.xCno = pr.xCno;
            syncInputs();
            update();
        });
        add(c, g, presetBox);

        g.insets = new Insets(14, 0, 2, 0);
        add(c, g, label("<html>Temperature (T<sub>6</sub> = T / 10<sup>6</sup> K)</html>"));
        g.insets = new Insets(0, 0, 2, 0);
        tBig.setFont(tBig.getFont().deriveFont(Font.BOLD, 30f));
        tBig.setForeground(INK);
        add(c, g, tBig);
        slider.setOpaque(false);
        slider.getAccessibleContext().setAccessibleName("Temperature, logarithmic scale from 1 to 200 million kelvin");
        slider.addChangeListener(e -> {
            if (syncing) return;
            setTemperature(Math.round(sliderToT(slider.getValue()) * 10) / 10.0, true);
        });
        add(c, g, slider);
        add(c, g, withUnit(tField, "<html>× 10<sup>6</sup> K (1 to 200)</html>"));
        tField.addActionListener(e -> commitTemperatureField());
        tField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitTemperatureField(); }
        });

        g.insets = new Insets(16, 0, 2, 0);
        add(c, g, label("Density ρ"));
        g.insets = new Insets(0, 0, 2, 0);
        add(c, g, withUnit(rhoF, "<html>kg m<sup>−3</sup></html>"));
        add(c, g, rhoCgs);
        g.insets = new Insets(10, 0, 2, 0);
        add(c, g, label("Hydrogen mass fraction X"));
        g.insets = new Insets(0, 0, 2, 0);
        add(c, g, xF);
        g.insets = new Insets(10, 0, 2, 0);
        add(c, g, label("<html>C + N + O mass fraction X<sub>CNO</sub></html>"));
        g.insets = new Insets(0, 0, 2, 0);
        add(c, g, xCnoF);
        g.insets = new Insets(10, 0, 2, 0);
        add(c, g, label("<html>Screening factor f<sub>pp</sub> (about 1)</html>"));
        g.insets = new Insets(0, 0, 2, 0);
        add(c, g, fppF);
        g.insets = new Insets(10, 0, 2, 0);
        add(c, g, label("<html>pp branch factor ψ<sub>pp</sub> (1 to 2)</html>"));
        g.insets = new Insets(0, 0, 2, 0);
        add(c, g, psiF);
        g.insets = new Insets(10, 0, 2, 0);
        add(c, g, label("Rate units"));
        g.insets = new Insets(0, 0, 2, 0);
        units.addActionListener(e -> { cgs = units.getSelectedIndex() == 1; update(); });
        add(c, g, units);

        for (JTextField f : new JTextField[] {rhoF, xF, xCnoF, fppF, psiF}) {
            f.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { paramsEdited(); }
                public void removeUpdate(DocumentEvent e) { paramsEdited(); }
                public void changedUpdate(DocumentEvent e) { paramsEdited(); }
            });
        }

        g.insets = new Insets(14, 0, 0, 0);
        err.setOpaque(true);
        err.setBackground(WARN_BG);
        err.setForeground(WARN_INK);
        err.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        err.setVisible(false);
        add(c, g, err);

        g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        add(c, g, Box.createGlue());
        return c;
    }

    private JPanel buildResults() {
        JPanel r = card(new BorderLayout(0, 12));
        head.setFont(head.getFont().deriveFont(Font.BOLD, 17f));
        head.setForeground(INK);
        r.add(head, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JPanel tiles = new JPanel(new GridLayout(1, 3, 12, 0));
        tiles.setOpaque(false);
        tiles.add(tile("pp chain", PP, vPP, uPP));
        tiles.add(tile("CNO cycle", CNO, vCNO, uCNO));
        tiles.add(tile("Total", INK2, vTot, uTot));
        tiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(tiles);
        body.add(Box.createVerticalStrut(14));

        JPanel shareHead = new JPanel(new BorderLayout());
        shareHead.setOpaque(false);
        shareHead.add(label("Share of energy output"), BorderLayout.WEST);
        domLabel.setFont(domLabel.getFont().deriveFont(Font.BOLD, 12f));
        shareHead.add(domLabel, BorderLayout.EAST);
        shareHead.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(shareHead);
        body.add(Box.createVerticalStrut(6));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(bar);
        JPanel pct = new JPanel(new BorderLayout());
        pct.setOpaque(false);
        pct.add(pctPP, BorderLayout.WEST);
        pct.add(pctCNO, BorderLayout.EAST);
        pct.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(pct);
        body.add(Box.createVerticalStrut(12));

        JPanel facts = new JPanel(new GridLayout(2, 3, 20, 10));
        facts.setOpaque(false);
        facts.add(factBox("<html>pp temperature sensitivity, ε ∝ T<sup>ν</sup></html>", nuPP));
        facts.add(factBox("<html>CNO temperature sensitivity, ε ∝ T<sup>ν</sup></html>", nuCNO));
        facts.add(factBox("pp = CNO crossover (this composition)", cross));
        facts.add(factBox("Power per cubic metre, ρε", perVol));
        facts.add(factBox("Hydrogen burned per kg of gas", hRate));
        facts.add(factBox("Time to burn all hydrogen at this rate", life));
        facts.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(facts);

        r.add(body, BorderLayout.CENTER);
        return r;
    }

    private JPanel buildTable() {
        JPanel t = new JPanel(new BorderLayout(0, 8));
        t.setBackground(SURFACE);
        t.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel tools = new JPanel(new GridLayout(1, 7, 8, 0));
        tools.setOpaque(false);
        tools.add(label("From (MK)")); tools.add(tbFrom);
        tools.add(label("To (MK)")); tools.add(tbTo);
        tools.add(label("Step (MK)")); tools.add(tbStep);
        tools.add(small("Uses ρ, X and X_CNO from the left panel"));
        for (JTextField f : new JTextField[] {tbFrom, tbTo, tbStep}) {
            f.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { if (!syncing) refreshTable(); }
                public void removeUpdate(DocumentEvent e) { if (!syncing) refreshTable(); }
                public void changedUpdate(DocumentEvent e) { if (!syncing) refreshTable(); }
            });
        }
        t.add(tools, BorderLayout.NORTH);

        table.setRowHeight(24);
        table.setShowVerticalLines(false);
        table.setGridColor(LINE);
        table.setFillsViewportHeight(true);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foc, int row, int col) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, sel, foc, row, col);
                l.setHorizontalAlignment(col == 0 || col == 5 ? SwingConstants.LEFT : SwingConstants.RIGHT);
                l.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                return l;
            }
        };
        for (int i = 0; i < table.getColumnCount(); i++) table.getColumnModel().getColumn(i).setCellRenderer(right);
        t.add(new JScrollPane(table), BorderLayout.CENTER);
        return t;
    }

    // ------------------------------------------------------------ behaviour

    static final double LOGSPAN = Math.log(FusionRates.T_MAX / FusionRates.T_MIN);
    static double sliderToT(int v) { return FusionRates.T_MIN * Math.exp(LOGSPAN * v / 1000.0); }
    static int tToSlider(double t) { return (int) Math.round(1000 * Math.log(t / FusionRates.T_MIN) / LOGSPAN); }

    void setTemperature(double newT, boolean fromSlider) {
        t = Math.min(FusionRates.T_MAX, Math.max(FusionRates.T_MIN, newT));
        syncing = true;
        if (!fromSlider) slider.setValue(tToSlider(t));
        tField.setText(plain(t));
        syncing = false;
        update();
    }

    private void commitTemperatureField() {
        try {
            double v = Double.parseDouble(tField.getText().trim());
            if (Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException();
            if (v != t) setTemperature(v, false);
            else tField.setText(plain(t));
        } catch (NumberFormatException ex) {
            tField.setText(plain(t));
        }
    }

    private void paramsEdited() {
        if (syncing) return;
        p.rho = parse(rhoF);
        p.x = parse(xF);
        p.xCno = parse(xCnoF);
        p.fpp = parse(fppF);
        p.psi = parse(psiF);
        update();
    }

    private void syncInputs() {
        syncing = true;
        slider.setValue(tToSlider(t));
        tField.setText(plain(t));
        rhoF.setText(plain(p.rho));
        xF.setText(plain(p.x));
        xCnoF.setText(plain(p.xCno));
        fppF.setText(plain(p.fpp));
        psiF.setText(plain(p.psi));
        if (tbFrom.getText().isEmpty()) {
            tbFrom.setText("5"); tbTo.setText("40"); tbStep.setText("2.5");
        }
        syncing = false;
    }

    private void markPreset() {
        Preset match = CUSTOM;
        for (Preset pr : PRESETS) {
            if (pr.t == t && pr.rho == p.rho && pr.x == p.x && pr.xCno == p.xCno) { match = pr; break; }
        }
        syncing = true;
        presetBox.setSelectedItem(match);
        syncing = false;
    }

    /** Recomputes every readout, the chart and the table. */
    void update() {
        markPreset();
        tBig.setText("<html>" + FusionRates.fmtT(t) + " <span style='font-size:12px;color:#6b7484'>million K</span></html>");
        rhoCgs.setText(p.rho > 0 ? "<html>= " + FusionRates.sciHtml(p.rho / 1000) + " g cm<sup>−3</sup></html>" : " ");
        head.setText("Energy generation rate ε at " + FusionRates.fmtT(t) + " million K");

        String msg = p.validate();
        valid = msg == null;
        err.setText(valid ? "" : "<html><body style='width:240px'>" + msg + " Results are hidden until this is fixed.</body></html>");
        err.setVisible(!valid);

        if (!valid) {
            for (JLabel l : new JLabel[] {vPP, vCNO, vTot, nuPP, nuCNO, cross, perVol, hRate, life}) l.setText("–");
            domLabel.setText(" ");
            domLabel.setIcon(null);
            pctPP.setText(" ");
            pctCNO.setText(" ");
            bar.set(0, 0);
            tableModel.setRowCount(0);
            chart.repaint();
            return;
        }

        double mul = cgs ? FusionRates.WKG_TO_CGS : 1;
        String u = unitHtml();
        double pp = FusionRates.epsPP(t, p), cno = FusionRates.epsCNO(t, p), tot = pp + cno;
        vPP.setText(html(FusionRates.sciHtml(pp * mul)));
        vCNO.setText(html(FusionRates.sciHtml(cno * mul)));
        vTot.setText(html(FusionRates.sciHtml(tot * mul)));
        uPP.setText(html(u)); uCNO.setText(html(u)); uTot.setText(html(u));

        double fPP = tot > 0 ? pp / tot : 0, fC = tot > 0 ? cno / tot : 0;
        bar.set(fPP, fC);
        pctPP.setText("pp " + pct(fPP) + "%");
        pctCNO.setText("CNO " + pct(fC) + "%");
        boolean ppWins = pp >= cno;
        domLabel.setForeground(INK);
        domLabel.setIcon(new Swatch(ppWins ? PP : CNO));
        domLabel.setText(ppWins ? "pp chain dominates" : "CNO cycle dominates");

        double nP = FusionRates.nuPP(t, p), nC = FusionRates.nuCNO(t, p);
        nuPP.setText(Double.isNaN(nP) ? "–" : String.format(Locale.ROOT, "ν ≈ %.1f", nP));
        nuCNO.setText(Double.isNaN(nC) ? "–" : String.format(Locale.ROOT, "ν ≈ %.1f", nC));

        double c = FusionRates.crossover(p);
        if (Double.isNaN(c)) cross.setText("none (X_CNO = 0)");
        else if (c == Double.NEGATIVE_INFINITY) cross.setText("below 1 million K");
        else if (c == Double.POSITIVE_INFINITY) cross.setText("above 200 million K");
        else cross.setText(FusionRates.fmtT(c) + " million K");

        perVol.setText(html(FusionRates.sciHtml(tot * p.rho) + " W m<sup>−3</sup>"));
        double dXdt = FusionRates.hydrogenBurnRate(t, p);
        hRate.setText(html(FusionRates.sciHtml(dXdt) + " kg s<sup>−1</sup> (" + FusionRates.sciHtml(dXdt * FusionRates.YEAR_S) + " per year)"));
        life.setText(html(FusionRates.fmtYearsHtml(FusionRates.hydrogenLifetimeYears(t, p))));

        chart.repaint();
        refreshTable();
    }

    private void refreshTable() {
        String u = cgs ? "erg/g/s" : "W/kg";
        table.getColumnModel().getColumn(1).setHeaderValue("pp rate (" + u + ")");
        table.getColumnModel().getColumn(2).setHeaderValue("CNO rate (" + u + ")");
        table.getColumnModel().getColumn(3).setHeaderValue("Total (" + u + ")");
        table.getTableHeader().repaint();
        tableModel.setRowCount(0);
        if (!valid) return;

        double from = parse(tbFrom), to = parse(tbTo), step = parse(tbStep);
        if (Double.isNaN(from) || Double.isNaN(to)) return;
        from = Math.max(FusionRates.T_MIN, Math.min(FusionRates.T_MAX, from));
        to = Math.max(FusionRates.T_MIN, Math.min(FusionRates.T_MAX, to));
        if (to < from) { double s = from; from = to; to = s; }
        if (!(step > 0)) step = 1;
        if ((to - from) / step > 400) step = (to - from) / 400;   // keep the table a sensible size

        double mul = cgs ? FusionRates.WKG_TO_CGS : 1;
        int n = (int) Math.floor((to - from) / step + 1e-9);
        int nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= n; i++) {
            double tt = from + i * step;       // multiply instead of accumulating, so no drift
            double pp = FusionRates.epsPP(tt, p), cno = FusionRates.epsCNO(tt, p);
            tableModel.addRow(new Object[] {
                FusionRates.fmtT(tt),
                html(FusionRates.sciHtml(pp * mul)),
                html(FusionRates.sciHtml(cno * mul)),
                html(FusionRates.sciHtml((pp + cno) * mul)),
                pp > 0 ? html(FusionRates.sciHtml(cno / pp)) : "–",
                pp >= cno ? "pp chain" : "CNO cycle"
            });
            if (Math.abs(tt - t) < best) { best = Math.abs(tt - t); nearest = i; }
        }
        if (nearest >= 0 && best < step / 2) {
            table.setRowSelectionInterval(nearest, nearest);
        }
    }

    // ------------------------------------------------------------ helpers

    String unitHtml() { return cgs ? "erg g<sup>−1</sup> s<sup>−1</sup>" : "W kg<sup>−1</sup>"; }

    static String html(String s) { return "<html>" + s + "</html>"; }

    static String pct(double f) {
        if (f >= 1) return "100";
        if (f <= 0) return "0";
        if (f >= 0.9995) return ">99.9";
        if (f < 0.0005) return "<0.1";
        return String.format(Locale.ROOT, "%.1f", f * 100);
    }

    /** Short, exact text for an input box (no needless trailing zeros). */
    static String plain(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.format(Locale.ROOT, "%.0f", v);
        String s = Double.toString(v);
        return s.contains("E") ? String.format(Locale.ROOT, "%.6g", v) : s;
    }

    static double parse(JTextField f) {
        try {
            return Double.parseDouble(f.getText().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static JTextField field() {
        JTextField f = new JTextField(10);
        f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        return f;
    }

    private static JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setForeground(INK);
        return l;
    }

    private static JLabel heading(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 17f));
        l.setForeground(INK);
        return l;
    }

    private static JLabel small(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(11.5f));
        l.setForeground(INK3);
        return l;
    }

    private static JLabel value() {
        JLabel l = new JLabel("–");
        l.setFont(l.getFont().deriveFont(Font.BOLD, 22f));
        l.setForeground(INK);
        return l;
    }

    private static JLabel fact() {
        JLabel l = new JLabel("–");
        l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        l.setForeground(INK);
        return l;
    }

    private static JPanel withUnit(JComponent f, String unit) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(f, BorderLayout.CENTER);
        row.add(small(unit), BorderLayout.EAST);
        return row;
    }

    private static JPanel card(java.awt.LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setBackground(SURFACE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        return p;
    }

    private static JPanel tile(String name, Color swatch, JLabel v, JLabel u) {
        JPanel t = new JPanel();
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.setBackground(BG);
        t.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel k = new JLabel(name, new Swatch(swatch), SwingConstants.LEFT);
        k.setIconTextGap(6);
        k.setFont(k.getFont().deriveFont(Font.BOLD, 12.5f));
        k.setForeground(INK2);
        t.add(k);
        t.add(Box.createVerticalStrut(4));
        t.add(v);
        t.add(u);
        return t;
    }

    private static JPanel factBox(String title, JLabel v) {
        JPanel f = new JPanel(new BorderLayout(0, 2));
        f.setOpaque(false);
        f.add(small(title), BorderLayout.NORTH);
        f.add(v, BorderLayout.CENTER);
        return f;
    }

    private static void add(JPanel c, GridBagConstraints g, Component comp) {
        c.add(comp, g);
        g.gridy++;
    }

    // ------------------------------------------------------------ colour swatch icon

    static final class Swatch implements javax.swing.Icon {
        private final Color c;
        Swatch(Color c) { this.c = c; }
        public int getIconWidth() { return 10; }
        public int getIconHeight() { return 10; }
        public void paintIcon(Component comp, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(c);
            g.fill(new RoundRectangle2D.Double(x, y, 10, 10, 4, 4));
            g.dispose();
        }
    }

    // ------------------------------------------------------------ share bar

    static final class ShareBar extends JComponent {
        private double a, b;

        ShareBar() {
            setPreferredSize(new Dimension(100, 14));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
        }

        void set(double a, double b) { this.a = a; this.b = b; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Shape clip = new RoundRectangle2D.Double(0, 0, w, h, 8, 8);
            g.setColor(GRID);
            g.fill(clip);
            g.clip(clip);
            double wa = w * a;
            g.setColor(PP);
            g.fill(new Rectangle2D.Double(0, 0, wa, h));
            if (b > 0) {
                g.setColor(CNO);
                double gap = a > 0 ? 2 : 0;
                g.fill(new Rectangle2D.Double(wa + gap, 0, Math.max(0, w - wa - gap), h));
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------ chart

    static final class ChartPanel extends JPanel {
        private final FusionRatesApp app;
        private double hoverT = Double.NaN;
        // last-drawn geometry, used to map the mouse back to a temperature
        private double t0, t1;
        private int ml = 74, mr = 64, mt = 22, mb = 50;

        ChartPanel(FusionRatesApp app) {
            this.app = app;
            setBackground(SURFACE);
            setToolTipText(null);
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { hoverT = toT(e.getX()); repaint(); }
                @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
                @Override public void mouseExited(MouseEvent e) { hoverT = Double.NaN; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    double tt = toT(e.getX());
                    if (!Double.isNaN(tt)) app.setTemperature(Math.round(tt * 10) / 10.0, false);
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private double toT(int px) {
            int pw = getWidth() - ml - mr;
            if (pw <= 0 || px < ml || px > ml + pw) return Double.NaN;
            return t0 + (px - ml) / (double) pw * (t1 - t0);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int w = getWidth(), h = getHeight();
            final int pw = w - ml - mr, ph = h - mt - mb;
            Font base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            g.setFont(base);

            if (!app.valid) {
                g.setColor(INK3);
                g.drawString("Fix the highlighted input on the left to draw the chart.", ml, mt + 30);
                g.dispose();
                return;
            }
            if (pw < 80 || ph < 80) { g.dispose(); return; }

            final FusionRates.Params p = app.p;
            t0 = Math.min(4, app.t * 0.8);
            t1 = Math.max(50, app.t * 1.15);
            final double mul = app.cgs ? 4 : 0;   // log10 of the unit factor

            int n = Math.max(200, pw);
            double[] ts = new double[n + 1], la = new double[n + 1], lb = new double[n + 1], lt = new double[n + 1];
            double maxL = Double.NEGATIVE_INFINITY;
            for (int i = 0; i <= n; i++) {
                double tt = t0 + (t1 - t0) * i / n;
                ts[i] = tt;
                la[i] = FusionRates.lnEpsPP(tt, p) / Math.log(10) + mul;
                lb[i] = FusionRates.lnEpsCNO(tt, p) / Math.log(10) + mul;
                double mx = Math.max(la[i], lb[i]);
                lt[i] = Double.isInfinite(mx) ? mx : mx + Math.log10(Math.pow(10, la[i] - mx) + Math.pow(10, lb[i] - mx));
                maxL = Math.max(maxL, mx);
            }
            final int yTop = (int) Math.ceil(maxL + 0.5);
            final int yBot = yTop - 16;
            final double ft0 = t0, ft1 = t1;

            // coordinate transforms
            java.util.function.DoubleUnaryOperator X = tt -> ml + (tt - ft0) / (ft1 - ft0) * pw;
            java.util.function.DoubleUnaryOperator Y = l -> mt + (yTop - l) / (double) (yTop - yBot) * ph;

            // grid and y axis (every 2 decades)
            for (int d = yBot; d <= yTop; d += 2) {
                double y = Y.applyAsDouble(d);
                g.setColor(GRID);
                g.draw(new Line2D.Double(ml, y, ml + pw, y));
                g.setColor(INK3);
                drawPow10(g, base, d, ml - 8, (float) y + 4);
            }
            // x axis
            double span = t1 - t0;
            int step = span > 120 ? 20 : span > 60 ? 10 : 5;
            g.setColor(INK3);
            g.draw(new Line2D.Double(ml, mt + ph, ml + pw, mt + ph));
            FontMetrics fm = g.getFontMetrics();
            for (double tt = Math.ceil(t0 / step) * step; tt <= t1 + 1e-9; tt += step) {
                double x = X.applyAsDouble(tt);
                g.draw(new Line2D.Double(x, mt + ph, x, mt + ph + 5));
                String s = String.valueOf((int) tt);
                g.drawString(s, (float) (x - fm.stringWidth(s) / 2.0), mt + ph + 20);
            }
            String xl = "Temperature (million K)";
            g.drawString(xl, ml + pw / 2 - fm.stringWidth(xl) / 2, h - 10);
            String yl = "ε (" + (app.cgs ? "erg/g/s" : "W/kg") + ", log scale)";
            AffineTransform old = g.getTransform();
            g.rotate(-Math.PI / 2);
            g.drawString(yl, -(mt + ph / 2) - fm.stringWidth(yl) / 2, 16);
            g.setTransform(old);

            // curves
            Shape oldClip = g.getClip();
            g.clip(new Rectangle2D.Double(ml, mt, pw, ph));
            drawCurve(g, ts, lt, yBot, X, Y, INK2, new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {5f, 4f}, 0f));
            drawCurve(g, ts, la, yBot, X, Y, PP, new BasicStroke(2.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawCurve(g, ts, lb, yBot, X, Y, CNO, new BasicStroke(2.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setClip(oldClip);

            // direct labels at the right edge
            Font bold = base.deriveFont(Font.BOLD);
            g.setFont(bold);
            double ya = Y.applyAsDouble(clamp(la[n], yBot, yTop)), yb = Y.applyAsDouble(clamp(lb[n], yBot, yTop));
            if (Math.abs(ya - yb) < 14) {
                double mid = (ya + yb) / 2;
                if (ya <= yb) { ya = mid - 7; yb = mid + 7; } else { ya = mid + 7; yb = mid - 7; }
            }
            g.setColor(INK);
            if (!Double.isInfinite(la[n])) g.drawString("pp", ml + pw + 8, (float) ya + 4);
            if (!Double.isInfinite(lb[n])) g.drawString("CNO", ml + pw + 8, (float) yb + 4);
            g.setFont(base);

            // crossover marker
            double c = FusionRates.crossover(p);
            if (!Double.isNaN(c) && !Double.isInfinite(c) && c >= t0 && c <= t1) {
                double lc = FusionRates.lnEpsPP(c, p) / Math.log(10) + mul;
                if (lc >= yBot && lc <= yTop) {
                    double cx = X.applyAsDouble(c), cy = Y.applyAsDouble(lc);
                    g.setColor(SURFACE);
                    g.fill(new Ellipse2D.Double(cx - 5, cy - 5, 10, 10));
                    g.setColor(INK);
                    g.setStroke(new BasicStroke(1.5f));
                    g.draw(new Ellipse2D.Double(cx - 5, cy - 5, 10, 10));
                    g.setColor(INK2);
                    g.drawString("equal at " + FusionRates.fmtT(c) + " MK", (float) cx + 9, (float) cy + 18);
                }
            }

            // current temperature marker
            double xt = X.applyAsDouble(app.t);
            g.setColor(new Color(INK.getRed(), INK.getGreen(), INK.getBlue(), 170));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {2f, 3f}, 0f));
            g.draw(new Line2D.Double(xt, mt, xt, mt + ph));
            g.setStroke(new BasicStroke(1f));
            dot(g, xt, FusionRates.lnEpsPP(app.t, p) / Math.log(10) + mul, yBot, yTop, Y, PP, 5);
            dot(g, xt, FusionRates.lnEpsCNO(app.t, p) / Math.log(10) + mul, yBot, yTop, Y, CNO, 5);
            g.setColor(INK);
            String ct = FusionRates.fmtT(app.t) + " MK";
            boolean nearRight = xt > ml + pw - 70;
            g.drawString(ct, (float) (nearRight ? xt - 6 - fm.stringWidth(ct) : xt + 6), mt + 12);

            // hover crosshair and readout
            if (!Double.isNaN(hoverT)) {
                double hx = X.applyAsDouble(hoverT);
                g.setColor(INK3);
                g.draw(new Line2D.Double(hx, mt, hx, mt + ph));
                double a = FusionRates.lnEpsPP(hoverT, p) / Math.log(10) + mul;
                double b = FusionRates.lnEpsCNO(hoverT, p) / Math.log(10) + mul;
                dot(g, hx, a, yBot, yTop, Y, PP, 4);
                dot(g, hx, b, yBot, yTop, Y, CNO, 4);
                drawTooltip(g, base, bold, hx, mt + 30, ml + pw, hoverT, a, b);
            }
            g.dispose();
        }

        private static double clamp(double v, double lo, double hi) {
            if (Double.isInfinite(v) || Double.isNaN(v)) return lo;
            return Math.max(lo, Math.min(hi, v));
        }

        private static void drawCurve(Graphics2D g, double[] ts, double[] l, int yBot,
                                      java.util.function.DoubleUnaryOperator X, java.util.function.DoubleUnaryOperator Y,
                                      Color col, BasicStroke stroke) {
            Path2D.Double path = new Path2D.Double();
            boolean pen = false;
            for (int i = 0; i < ts.length; i++) {
                if (Double.isInfinite(l[i]) || Double.isNaN(l[i])) { pen = false; continue; }
                double x = X.applyAsDouble(ts[i]), y = Y.applyAsDouble(Math.max(l[i], yBot - 2));
                if (pen) path.lineTo(x, y); else path.moveTo(x, y);
                pen = true;
            }
            g.setColor(col);
            g.setStroke(stroke);
            g.draw(path);
            g.setStroke(new BasicStroke(1f));
        }

        private static void dot(Graphics2D g, double x, double l, int yBot, int yTop,
                                java.util.function.DoubleUnaryOperator Y, Color col, double r) {
            if (Double.isInfinite(l) || Double.isNaN(l) || l < yBot || l > yTop) return;
            double y = Y.applyAsDouble(l);
            g.setColor(SURFACE);
            g.fill(new Ellipse2D.Double(x - r - 2, y - r - 2, 2 * r + 4, 2 * r + 4));
            g.setColor(col);
            g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        }

        /** Draws "10" with a raised exponent, right-aligned at xRight. */
        private static void drawPow10(Graphics2D g, Font base, int exp, float xRight, float y) {
            Font supF = base.deriveFont(base.getSize2D() * 0.75f);
            String e = String.valueOf(exp).replace('-', '−');
            float wBase = g.getFontMetrics(base).stringWidth("10");
            float wSup = g.getFontMetrics(supF).stringWidth(e);
            float x = xRight - wBase - wSup - 1;
            g.setFont(base);
            g.drawString("10", x, y);
            g.setFont(supF);
            g.drawString(e, x + wBase + 1, y - base.getSize2D() * 0.45f);
            g.setFont(base);
        }

        /** Draws a value like 1.03 × 10⁻³ (exponent raised) and returns its width. */
        private static float drawSci(Graphics2D g, Font base, double v, float x, float y, boolean measureOnly) {
            Object[] s = FusionRates.sciParts(v, 3);
            Font supF = base.deriveFont(base.getSize2D() * 0.75f);
            FontMetrics fb = g.getFontMetrics(base), fs = g.getFontMetrics(supF);
            String m = s[1] == null ? (String) s[0] : s[0] + " × 10";
            float w = fb.stringWidth(m);
            String e = s[1] == null ? "" : String.valueOf(s[1]).replace('-', '−');
            float total = w + (e.isEmpty() ? 0 : 1 + fs.stringWidth(e));
            if (measureOnly) return total;
            g.setFont(base);
            g.drawString(m, x, y);
            if (!e.isEmpty()) {
                g.setFont(supF);
                g.drawString(e, x + w + 1, y - base.getSize2D() * 0.45f);
                g.setFont(base);
            }
            return total;
        }

        private void drawTooltip(Graphics2D g, Font base, Font bold, double hx, int top, int rightEdge,
                                 double tt, double la, double lb) {
            String[] names = {"pp", "CNO", "total"};
            Color[] cols = {PP, CNO, INK2};
            double ep = Math.pow(10, la), ec = Math.pow(10, lb);
            double[] vals = {ep, ec, ep + ec};
            String title = FusionRates.fmtT(tt) + " million K";
            FontMetrics fm = g.getFontMetrics(base);
            float maxW = g.getFontMetrics(bold).stringWidth(title);
            for (int i = 0; i < 3; i++) {
                maxW = Math.max(maxW, 18 + fm.stringWidth(names[i] + "  ") + drawSci(g, base, vals[i], 0, 0, true));
            }
            int bw = (int) maxW + 20, bh = 4 * 18 + 12;
            double bx = hx + 14;
            if (bx + bw > rightEdge) bx = hx - 14 - bw;
            g.setColor(new Color(0, 0, 0, 30));
            g.fill(new RoundRectangle2D.Double(bx + 1, top + 2, bw, bh, 10, 10));
            g.setColor(SURFACE);
            g.fill(new RoundRectangle2D.Double(bx, top, bw, bh, 10, 10));
            g.setColor(LINE);
            g.draw(new RoundRectangle2D.Double(bx, top, bw, bh, 10, 10));
            g.setColor(INK);
            g.setFont(bold);
            g.drawString(title, (float) bx + 10, top + 20);
            g.setFont(base);
            for (int i = 0; i < 3; i++) {
                float y = top + 20 + 18 * (i + 1);
                g.setColor(cols[i]);
                g.fill(new RoundRectangle2D.Double(bx + 10, y - 9, 10, 10, 3, 3));
                g.setColor(INK);
                String lbl = names[i] + "  ";
                g.drawString(lbl, (float) bx + 28, y);
                drawSci(g, base, vals[i], (float) bx + 28 + fm.stringWidth(lbl), y, false);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--cli") || args[0].equals("-c"))) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            FusionRatesCli.main(rest);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default look and feel works too
            }
            new FusionRatesApp().setVisible(true);
        });
    }
}
