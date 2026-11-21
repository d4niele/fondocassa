package it.fondocassa.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Nome della classe mostrato nel titolo, ad esempio «1ª C». */
public final class Classe {
    /** Lunghezza massima del testo, spazi compresi («1ª C» ne usa 4). */
    public static final int MAX_CARATTERI = 5;

    /** Anno e sezione, con o senza «ª» (o a, °, ^) in mezzo: «1C», «1 c», «1aC», «1°C»… */
    private static final Pattern ANNO_SEZIONE =
            Pattern.compile("(\\d)\\s*[aª°º^]?\\s*([a-z])", Pattern.CASE_INSENSITIVE);

    private Classe() {
    }

    /**
     * Scrive anno e sezione nella forma «1ª C», che sulla tastiera del telefono è scomoda da digitare.
     * Qualunque altro testo resta com'è, senza spazi ai bordi e al massimo di {@link #MAX_CARATTERI}.
     */
    public static String normalizza(String testo) {
        if (testo == null) {
            return "";
        }
        String pulito = testo.trim();
        Matcher m = ANNO_SEZIONE.matcher(pulito);
        if (m.matches()) {
            return m.group(1) + "ª " + m.group(2).toUpperCase(Locale.ITALIAN);
        }
        return pulito.length() > MAX_CARATTERI ? pulito.substring(0, MAX_CARATTERI).trim() : pulito;
    }
}
