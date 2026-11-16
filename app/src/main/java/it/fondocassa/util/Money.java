package it.fondocassa.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Importi in centesimi di euro: niente virgola mobile nei conti. */
public final class Money {
    private Money() {
    }

    /**
     * Converte il testo digitato dall'utente ("12,50", "12.5", "€ 1.234,56") in centesimi.
     *
     * @throws IllegalArgumentException se il testo non è un importo valido o è negativo
     */
    public static long parse(String testo) {
        if (testo == null) {
            throw new IllegalArgumentException("Importo mancante");
        }
        String s = testo.replace("€", "").replace(" ", "").replace(" ", "").trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Importo mancante");
        }
        int virgola = s.lastIndexOf(',');
        int punto = s.lastIndexOf('.');
        if (virgola >= 0 && punto >= 0) {
            // Entrambi presenti: l'ultimo è il separatore decimale, l'altro delle migliaia.
            if (virgola > punto) {
                s = s.replace(".", "").replace(',', '.');
            } else {
                s = s.replace(",", "");
            }
        } else if (virgola >= 0) {
            s = s.replace(',', '.');
        }
        BigDecimal valore;
        try {
            valore = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Importo non valido: " + testo);
        }
        if (valore.signum() < 0) {
            throw new IllegalArgumentException("L'importo non può essere negativo");
        }
        if (valore.scale() > 2) {
            throw new IllegalArgumentException("Massimo due decimali");
        }
        return valore.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** Formato italiano con simbolo: "€ 1.234,56". */
    public static String format(long centesimi) {
        return "€ " + formatSenzaSimbolo(centesimi, true);
    }

    /** Formato per i campi di input: "1234,56". */
    public static String formatInput(long centesimi) {
        return formatSenzaSimbolo(centesimi, false);
    }

    private static String formatSenzaSimbolo(long centesimi, boolean migliaia) {
        DecimalFormat df = new DecimalFormat(migliaia ? "#,##0.00" : "0.00",
                DecimalFormatSymbols.getInstance(Locale.ITALY));
        return df.format(BigDecimal.valueOf(centesimi, 2));
    }
}
