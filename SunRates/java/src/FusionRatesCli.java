import java.util.Locale;
import java.util.Scanner;

/**
 * Text version of the calculator.
 *
 *   java -cp out FusionRatesCli                 interactive: type temperatures one at a time
 *   java -cp out FusionRatesCli 10 15.7 20 30   print rates at the listed temperatures (MK)
 *
 * Interactive commands:
 *   15.7               rates at 15.7 million K
 *   table 5 40 2.5     table from 5 to 40 MK in steps of 2.5
 *   rho 1.527e5        set density (kg/m^3);  also: x 0.34, xcno 0.01, fpp 1, psi 1
 *   sun                reset to the Sun's core values
 *   show               print current settings
 *   help, quit
 */
public final class FusionRatesCli {

    private static final FusionRates.Params P = sun();

    private static FusionRates.Params sun() {
        return new FusionRates.Params(1.527e5, 0.34, 0.01, 1, 1);
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            printHeader();
            for (String a : args) {
                try {
                    printRow(Double.parseDouble(a));
                } catch (NumberFormatException e) {
                    System.out.println("Skipped \"" + a + "\": not a number.");
                }
            }
            return;
        }
        interactive();
    }

    private static void interactive() {
        System.out.println("Hydrogen burning rates: pp chain vs CNO cycle");
        System.out.println("Starting with the Sun's core. Type a temperature in million K, or 'help'.");
        showSettings();
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("\nT (MK) > ");
            if (!in.hasNextLine()) break;
            // drop a byte-order mark or other junk some shells put in front of piped input
            String line = in.nextLine().replaceAll("^[^\\x20-\\x7E]+", "").trim();
            if (line.isEmpty()) continue;
            String[] w = line.toLowerCase(Locale.ROOT).split("\\s+");
            try {
                switch (w[0]) {
                    case "q": case "quit": case "exit":
                        return;
                    case "help": case "?":
                        help();
                        break;
                    case "show":
                        showSettings();
                        break;
                    case "sun":
                        FusionRates.Params s = sun();
                        P.rho = s.rho; P.x = s.x; P.xCno = s.xCno; P.fpp = s.fpp; P.psi = s.psi;
                        showSettings();
                        break;
                    case "rho": P.rho = num(w, 1); checked(); break;
                    case "x": P.x = num(w, 1); checked(); break;
                    case "xcno": P.xCno = num(w, 1); checked(); break;
                    case "fpp": P.fpp = num(w, 1); checked(); break;
                    case "psi": P.psi = num(w, 1); checked(); break;
                    case "table":
                        table(num(w, 1), num(w, 2), w.length > 3 ? num(w, 3) : 5);
                        break;
                    default:
                        detail(Double.parseDouble(w[0]));
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                System.out.println("Didn't understand \"" + line + "\". Type a number such as 15.7, or 'help'.");
            }
        }
    }

    private static double num(String[] w, int i) { return Double.parseDouble(w[i]); }

    private static void checked() {
        String m = P.validate();
        if (m != null) System.out.println("Warning: " + m);
        else showSettings();
    }

    private static boolean ok() {
        String m = P.validate();
        if (m != null) {
            System.out.println("Can't calculate: " + m);
            return false;
        }
        return true;
    }

    private static double clampT(double t) {
        if (t < FusionRates.T_MIN || t > FusionRates.T_MAX) {
            double c = Math.max(FusionRates.T_MIN, Math.min(FusionRates.T_MAX, t));
            System.out.printf(Locale.ROOT, "Temperature limited to %s MK (allowed 1 to 200).%n", FusionRates.fmtT(c));
            return c;
        }
        return t;
    }

    private static void detail(double t) {
        if (!ok()) return;
        t = clampT(t);
        double pp = FusionRates.epsPP(t, P), cno = FusionRates.epsCNO(t, P), tot = pp + cno;
        System.out.printf(Locale.ROOT, "%n  At T = %s million K:%n", FusionRates.fmtT(t));
        System.out.printf(Locale.ROOT, "    pp chain   eps = %10.3e W/kg  (%10.3e erg/g/s)  %5s%%%n", pp, pp * 1e4, pct(pp / tot));
        System.out.printf(Locale.ROOT, "    CNO cycle  eps = %10.3e W/kg  (%10.3e erg/g/s)  %5s%%%n", cno, cno * 1e4, pct(cno / tot));
        System.out.printf(Locale.ROOT, "    total      eps = %10.3e W/kg  (%10.3e erg/g/s)%n", tot, tot * 1e4);
        System.out.println("    Dominant: " + (pp >= cno ? "pp chain" : "CNO cycle"));
        System.out.printf(Locale.ROOT, "    Temperature sensitivity: pp ~ T^%.1f, CNO ~ T^%.1f%n",
                FusionRates.nuPP(t, P), FusionRates.nuCNO(t, P));
        double c = FusionRates.crossover(P);
        String cs = Double.isNaN(c) ? "none (X_CNO = 0)"
                : c == Double.NEGATIVE_INFINITY ? "below 1 MK"
                : c == Double.POSITIVE_INFINITY ? "above 200 MK"
                : FusionRates.fmtT(c) + " MK";
        System.out.println("    pp = CNO crossover: " + cs);
        System.out.printf(Locale.ROOT, "    Power per volume: %.3e W/m^3%n", tot * P.rho);
        double r = FusionRates.hydrogenBurnRate(t, P);
        System.out.printf(Locale.ROOT, "    Hydrogen burned: %.3e kg/s per kg of gas%n", r);
        double y = FusionRates.hydrogenLifetimeYears(t, P);
        System.out.println("    Time to burn all hydrogen at this rate: " + years(y));
    }

    private static void table(double from, double to, double step) {
        if (!ok()) return;
        from = clampT(from);
        to = clampT(to);
        if (to < from) { double s = from; from = to; to = s; }
        if (!(step > 0)) step = 1;
        if ((to - from) / step > 400) {
            step = (to - from) / 400;
            System.out.printf(Locale.ROOT, "Step widened to %.3g MK to keep the table at 400 rows.%n", step);
        }
        printHeader();
        int n = (int) Math.floor((to - from) / step + 1e-9);
        for (int i = 0; i <= n; i++) printRow(from + i * step);
    }

    private static void printHeader() {
        System.out.printf(Locale.ROOT, "%n%9s  %12s  %12s  %12s  %10s  %s%n",
                "T (MK)", "pp (W/kg)", "CNO (W/kg)", "total (W/kg)", "CNO/pp", "dominant");
        System.out.println("---------  ------------  ------------  ------------  ----------  ---------");
    }

    private static void printRow(double t) {
        if (P.validate() != null) { ok(); return; }
        t = Math.max(FusionRates.T_MIN, Math.min(FusionRates.T_MAX, t));
        double pp = FusionRates.epsPP(t, P), cno = FusionRates.epsCNO(t, P);
        System.out.printf(Locale.ROOT, "%9s  %12.3e  %12.3e  %12.3e  %10.3e  %s%n",
                FusionRates.fmtT(t), pp, cno, pp + cno, cno / pp, pp >= cno ? "pp chain" : "CNO cycle");
    }

    private static void showSettings() {
        System.out.printf(Locale.ROOT, "  Settings: rho = %.4g kg/m^3, X = %.4g, X_CNO = %.4g, f_pp = %.3g, psi_pp = %.3g%n",
                P.rho, P.x, P.xCno, P.fpp, P.psi);
    }

    private static String pct(double f) {
        if (f >= 1) return "100";
        if (f <= 0 || Double.isNaN(f)) return "0";
        if (f >= 0.9995) return ">99.9";
        if (f < 0.0005) return "<0.1";
        return String.format(Locale.ROOT, "%.1f", f * 100);
    }

    private static String years(double y) {
        if (Double.isInfinite(y) || Double.isNaN(y)) return "never at this rate";
        if (y >= 1e9) return String.format(Locale.ROOT, "%.3g billion years", y / 1e9);
        if (y >= 1e6) return String.format(Locale.ROOT, "%.3g million years", y / 1e6);
        if (y >= 1e3) return String.format(Locale.ROOT, "%.3g thousand years", y / 1e3);
        if (y >= 1) return String.format(Locale.ROOT, "%.3g years", y);
        return String.format(Locale.ROOT, "%.3g days", y * 365.25);
    }

    private static void help() {
        System.out.println("  15.7               rates at 15.7 million K");
        System.out.println("  table 5 40 2.5     table from 5 to 40 MK in steps of 2.5");
        System.out.println("  rho 1.527e5        set density in kg/m^3");
        System.out.println("  x 0.34             set hydrogen mass fraction");
        System.out.println("  xcno 0.01          set C+N+O mass fraction");
        System.out.println("  fpp 1 / psi 1      set correction factors");
        System.out.println("  sun                reset to the Sun's core");
        System.out.println("  show               print current settings");
        System.out.println("  quit               exit");
    }
}
