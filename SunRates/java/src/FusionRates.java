import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * Energy generation rates of the proton-proton (pp) chain and the CNO cycle.
 *
 * Analytic fits from Carroll &amp; Ostlie, "An Introduction to Modern Astrophysics",
 * eqs. 10.46 and 10.58. Density in kg m^-3, rates in W kg^-1, T6 = T / 10^6 K.
 *
 *   eps_pp  = 0.241 rho X^2 f_pp psi_pp C_pp T6^(-2/3) exp(-33.80 T6^(-1/3))
 *   C_pp    = 1 + 0.0123 T6^(1/3) + 0.0109 T6^(2/3) + 0.0009 T6
 *
 *   eps_CNO = 8.67e20 rho X X_CNO C_CNO T6^(-2/3) exp(-152.28 T6^(-1/3))
 *   C_CNO   = 1 + 0.0027 T6^(1/3) - 0.00778 T6^(2/3) - 0.000149 T6
 *
 * Everything is evaluated in log space so very small rates never underflow.
 */
public final class FusionRates {

    public static final double MEV_J = 1.602176634e-13;   // J per MeV
    public static final double M_H = 1.67262192e-27;      // kg, proton mass used as m_H
    public static final double Q_PP = 26.2 * MEV_J;       // heat per He-4 from pp chain, after neutrino losses
    public static final double Q_CNO = 25.0 * MEV_J;      // heat per He-4 from CNO cycle, after neutrino losses
    public static final double YEAR_S = 3.15576e7;        // seconds per Julian year
    public static final double WKG_TO_CGS = 1e4;          // 1 W/kg = 10^4 erg g^-1 s^-1
    public static final double T_MIN = 1.0;               // MK, allowed input range
    public static final double T_MAX = 200.0;

    private FusionRates() { }

    /** Core conditions other than temperature. */
    public static final class Params {
        public double rho;   // kg m^-3
        public double x;     // hydrogen mass fraction
        public double xCno;  // C + N + O mass fraction
        public double fpp;   // pp screening factor
        public double psi;   // pp branch correction (PPII / PPIII)

        public Params(double rho, double x, double xCno, double fpp, double psi) {
            this.rho = rho;
            this.x = x;
            this.xCno = xCno;
            this.fpp = fpp;
            this.psi = psi;
        }

        /** Returns null when the parameters are usable, otherwise a message saying what to fix. */
        public String validate() {
            StringBuilder sb = new StringBuilder();
            if (!(rho > 0)) sb.append("Density must be greater than zero. ");
            if (!(x > 0 && x <= 1)) sb.append("X must be between 0 and 1. ");
            if (!(xCno >= 0 && xCno <= 1)) sb.append("X_CNO must be between 0 and 1. ");
            else if (x + xCno > 1) sb.append("X + X_CNO cannot exceed 1. ");
            if (!(fpp > 0) || !(psi > 0)) sb.append("Correction factors must be greater than zero. ");
            return sb.length() == 0 ? null : sb.toString().trim();
        }
    }

    // ---------------------------------------------------------------- physics

    public static double cPP(double t6) {
        double a = Math.cbrt(t6);
        return 1 + 0.0123 * a + 0.0109 * a * a + 0.0009 * t6;
    }

    public static double cCNO(double t6) {
        double a = Math.cbrt(t6);
        return 1 + 0.0027 * a - 0.00778 * a * a - 0.000149 * t6;
    }

    /** Natural log of eps_pp in W/kg. */
    public static double lnEpsPP(double t6, Params p) {
        if (!(p.rho > 0 && p.x > 0 && p.fpp > 0 && p.psi > 0 && t6 > 0)) return Double.NEGATIVE_INFINITY;
        return Math.log(0.241) + Math.log(p.rho) + 2 * Math.log(p.x) + Math.log(p.fpp) + Math.log(p.psi)
                + Math.log(cPP(t6)) - (2.0 / 3.0) * Math.log(t6) - 33.80 / Math.cbrt(t6);
    }

    /** Natural log of eps_CNO in W/kg. */
    public static double lnEpsCNO(double t6, Params p) {
        double c = cCNO(t6);
        if (!(p.rho > 0 && p.x > 0 && p.xCno > 0 && c > 0 && t6 > 0)) return Double.NEGATIVE_INFINITY;
        return Math.log(8.67e20) + Math.log(p.rho) + Math.log(p.x) + Math.log(p.xCno)
                + Math.log(c) - (2.0 / 3.0) * Math.log(t6) - 152.28 / Math.cbrt(t6);
    }

    public static double epsPP(double t6, Params p) { return Math.exp(lnEpsPP(t6, p)); }

    public static double epsCNO(double t6, Params p) { return Math.exp(lnEpsCNO(t6, p)); }

    /** Temperature exponent nu = d ln eps / d ln T for the pp chain. */
    public static double nuPP(double t6, Params p) { return nu(false, t6, p); }

    /** Temperature exponent nu = d ln eps / d ln T for the CNO cycle. */
    public static double nuCNO(double t6, Params p) { return nu(true, t6, p); }

    /** nu depends only on temperature, so it is evaluated with unit density and composition. */
    private static final Params NU_REF = new Params(1, 1, 1, 1, 1);

    private static double nu(boolean cno, double t6, Params ignored) {
        Params p = NU_REF;
        double h = 1e-4;
        double up = t6 * Math.exp(h), dn = t6 * Math.exp(-h);
        double a = cno ? lnEpsCNO(up, p) : lnEpsPP(up, p);
        double b = cno ? lnEpsCNO(dn, p) : lnEpsPP(dn, p);
        if (Double.isInfinite(a) || Double.isInfinite(b)) return Double.NaN;
        return (a - b) / (2 * h);
    }

    /**
     * Temperature (MK) at which eps_pp = eps_CNO for this composition.
     * Returns NaN when there is no CNO material, NEGATIVE_INFINITY when CNO already wins at T_MIN,
     * and POSITIVE_INFINITY when pp still wins at T_MAX.
     */
    public static double crossover(Params p) {
        double lo = T_MIN, hi = T_MAX;
        double glo = lnEpsCNO(lo, p) - lnEpsPP(lo, p);
        double ghi = lnEpsCNO(hi, p) - lnEpsPP(hi, p);
        if (Double.isNaN(glo) || Double.isNaN(ghi) || Double.isInfinite(ghi)) return Double.NaN;
        if (glo > 0) return Double.NEGATIVE_INFINITY;
        if (ghi < 0) return Double.POSITIVE_INFINITY;
        for (int i = 0; i < 80; i++) {
            double m = 0.5 * (lo + hi);
            if (lnEpsCNO(m, p) - lnEpsPP(m, p) < 0) lo = m; else hi = m;
        }
        return 0.5 * (lo + hi);
    }

    /** kg of hydrogen consumed per kg of gas per second (4 m_H per helium-4 nucleus made). */
    public static double hydrogenBurnRate(double t6, Params p) {
        return 4 * M_H * (epsPP(t6, p) / Q_PP + epsCNO(t6, p) / Q_CNO);
    }

    /** Years needed to use up all the hydrogen if the rate stayed constant. */
    public static double hydrogenLifetimeYears(double t6, Params p) {
        double r = hydrogenBurnRate(t6, p);
        return r > 0 ? p.x / r / YEAR_S : Double.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------- formatting

    /**
     * Splits v into a mantissa string and a power of ten. The exponent is null when the number
     * reads better written out in full (between 0.01 and 9999).
     */
    public static Object[] sciParts(double v, int digits) {
        if (Double.isNaN(v)) return new Object[] {"–", null};
        if (Double.isInfinite(v)) return new Object[] {v > 0 ? "∞" : "−∞", null};
        if (v == 0) return new Object[] {"0", null};
        int e = (int) Math.floor(Math.log10(Math.abs(v)));
        if (e >= -2 && e <= 3) {
            BigDecimal bd = new BigDecimal(v).round(new MathContext(digits));
            String s = bd.stripTrailingZeros().toPlainString();
            return new Object[] {s.replace('-', '−'), null};
        }
        String ms = String.format(Locale.ROOT, "%." + (digits - 1) + "f", v / Math.pow(10, e));
        if (Math.abs(Double.parseDouble(ms)) >= 10) {   // 9.999 rounded up to 10.00
            e += 1;
            ms = String.format(Locale.ROOT, "%." + (digits - 1) + "f", v / Math.pow(10, e));
        }
        return new Object[] {ms.replace('-', '−'), Integer.valueOf(e)};
    }

    /** Scientific notation as Swing HTML, e.g. 1.03 × 10<sup>−3</sup>. */
    public static String sciHtml(double v) {
        Object[] s = sciParts(v, 3);
        if (s[1] == null) return (String) s[0];
        return s[0] + " × 10<sup>" + String.valueOf(s[1]).replace('-', '−') + "</sup>";
    }

    /** Temperature in MK with sensible precision. */
    public static String fmtT(double t) {
        if (t >= 100) return String.format(Locale.ROOT, "%.0f", t);
        if (t >= 10) return String.format(Locale.ROOT, "%.1f", t);
        return String.format(Locale.ROOT, "%.2f", t);
    }

    public static String fmtYearsHtml(double y) {
        if (Double.isInfinite(y) || Double.isNaN(y)) return "never at this rate";
        if (y >= 1e9) return sciHtml(y / 1e9) + " billion years";
        if (y >= 1e6) return sciHtml(y / 1e6) + " million years";
        if (y >= 1e3) return sciHtml(y / 1e3) + " thousand years";
        if (y >= 1) return sciHtml(y) + " years";
        return sciHtml(y * 365.25) + " days";
    }
}
