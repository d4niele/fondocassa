package it.fondocassa.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lettura dell'elenco della classe da CSV o JSON.
 *
 * <p>CSV: separatore {@code ;}, {@code ,} o tabulazione (riconosciuto da solo), campi tra virgolette
 * ammessi. Con intestazione le colonne sono riconosciute per nome ({@code nome}, {@code cognome},
 * {@code note}); senza intestazione l'ordine è nome, cognome, note. Una sola colonna = nome completo.
 *
 * <p>JSON: un array di oggetti {@code {"nome", "cognome", "note"}} o di semplici stringhe, anche
 * dentro un oggetto {@code {"alunni": [...]}}.
 */
public final class ImportAlunni {
    /** Alunno letto dal file: il nome è già nella forma "Cognome Nome" usata dal registro. */
    public static class Riga {
        public final String nome;
        public final String note;

        Riga(String nome, String note) {
            this.nome = nome;
            this.note = note;
        }
    }

    private ImportAlunni() {
    }

    /**
     * @throws IllegalArgumentException se il testo non è interpretabile o non contiene alunni
     */
    public static List<Riga> leggi(String testo) {
        if (testo == null) {
            throw new IllegalArgumentException("Nessun dato da importare");
        }
        String pulito = testo.replace(String.valueOf((char) 0xFEFF), "").trim();
        if (pulito.isEmpty()) {
            throw new IllegalArgumentException("Nessun dato da importare");
        }
        List<Riga> righe = pulito.startsWith("[") || pulito.startsWith("{") ? daJson(pulito) : daCsv(pulito);
        if (righe.isEmpty()) {
            throw new IllegalArgumentException("Nessun alunno trovato");
        }
        return righe;
    }

    // --- JSON ---

    private static List<Riga> daJson(String testo) {
        JsonElement radice;
        try {
            radice = JsonParser.parseString(testo);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("JSON non valido");
        }
        JsonArray elenco = null;
        if (radice.isJsonArray()) {
            elenco = radice.getAsJsonArray();
        } else if (radice.isJsonObject()) {
            for (Map.Entry<String, JsonElement> voce : radice.getAsJsonObject().entrySet()) {
                if (voce.getValue().isJsonArray()) {
                    elenco = voce.getValue().getAsJsonArray();
                    break;
                }
            }
        }
        if (elenco == null) {
            throw new IllegalArgumentException("JSON non valido: serve un elenco di alunni");
        }
        List<Riga> righe = new ArrayList<>();
        for (JsonElement elemento : elenco) {
            if (elemento.isJsonPrimitive()) {
                aggiungi(righe, elemento.getAsString(), "", "");
            } else if (elemento.isJsonObject()) {
                String nome = "";
                String cognome = "";
                String note = "";
                for (Map.Entry<String, JsonElement> campo : elemento.getAsJsonObject().entrySet()) {
                    if (!campo.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    String valore = campo.getValue().getAsString();
                    switch (tipoColonna(campo.getKey())) {
                        case 'n':
                            nome = valore;
                            break;
                        case 'c':
                            cognome = valore;
                            break;
                        case 't':
                            note = valore;
                            break;
                        default:
                            break;
                    }
                }
                aggiungi(righe, nome, cognome, note);
            }
        }
        return righe;
    }

    // --- CSV ---

    private static List<Riga> daCsv(String testo) {
        char separatore = separatore(testo);
        List<List<String>> tabella = analizzaCsv(testo, separatore);
        List<Riga> righe = new ArrayList<>();
        if (tabella.isEmpty()) {
            return righe;
        }
        int colNome = 0;
        int colCognome = 1;
        int colNote = 2;
        int prima = 0;
        List<String> intestazione = tabella.get(0);
        boolean haIntestazione = false;
        for (String cella : intestazione) {
            char tipo = tipoColonna(cella);
            haIntestazione |= tipo == 'n' || tipo == 'c';
        }
        if (haIntestazione) {
            colNome = colCognome = colNote = -1;
            for (int i = 0; i < intestazione.size(); i++) {
                switch (tipoColonna(intestazione.get(i))) {
                    case 'n':
                        colNome = colNome < 0 ? i : colNome;
                        break;
                    case 'c':
                        colCognome = colCognome < 0 ? i : colCognome;
                        break;
                    case 't':
                        colNote = colNote < 0 ? i : colNote;
                        break;
                    default:
                        break;
                }
            }
            prima = 1;
        }
        for (int r = prima; r < tabella.size(); r++) {
            List<String> riga = tabella.get(r);
            aggiungi(righe, cella(riga, colNome), cella(riga, colCognome), cella(riga, colNote));
        }
        return righe;
    }

    /** 'n' nome, 'c' cognome, 't' note, '?' altro. */
    private static char tipoColonna(String titolo) {
        String t = titolo == null ? "" : titolo.trim().toLowerCase(Locale.ITALIAN);
        if (t.equals("cognome") || t.equals("surname") || t.equals("last name") || t.equals("lastname")) {
            return 'c';
        }
        if (t.equals("nome") || t.equals("name") || t.equals("first name") || t.equals("firstname")
                || t.equals("alunno") || t.equals("nominativo") || t.equals("nome e cognome") || t.equals("cognome e nome")) {
            return 'n';
        }
        if (t.equals("note") || t.equals("nota") || t.equals("notes") || t.equals("genitori")) {
            return 't';
        }
        return '?';
    }

    private static char separatore(String testo) {
        int fine = testo.indexOf('\n');
        String primaRiga = fine < 0 ? testo : testo.substring(0, fine);
        char migliore = ';';
        int massimo = 0;
        for (char candidato : new char[]{';', '\t', ','}) {
            int quanti = 0;
            boolean traVirgolette = false;
            for (char c : primaRiga.toCharArray()) {
                if (c == '"') {
                    traVirgolette = !traVirgolette;
                } else if (c == candidato && !traVirgolette) {
                    quanti++;
                }
            }
            if (quanti > massimo) {
                massimo = quanti;
                migliore = candidato;
            }
        }
        return migliore;
    }

    private static List<List<String>> analizzaCsv(String testo, char separatore) {
        List<List<String>> tabella = new ArrayList<>();
        List<String> riga = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean traVirgolette = false;
        for (int i = 0; i < testo.length(); i++) {
            char c = testo.charAt(i);
            if (traVirgolette) {
                if (c == '"' && i + 1 < testo.length() && testo.charAt(i + 1) == '"') {
                    campo.append('"');
                    i++;
                } else if (c == '"') {
                    traVirgolette = false;
                } else {
                    campo.append(c);
                }
            } else if (c == '"') {
                traVirgolette = true;
            } else if (c == separatore) {
                riga.add(campo.toString());
                campo.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < testo.length() && testo.charAt(i + 1) == '\n') {
                    i++;
                }
                riga.add(campo.toString());
                campo.setLength(0);
                tabella.add(riga);
                riga = new ArrayList<>();
            } else {
                campo.append(c);
            }
        }
        riga.add(campo.toString());
        tabella.add(riga);
        return tabella;
    }

    private static String cella(List<String> riga, int indice) {
        return indice >= 0 && indice < riga.size() ? riga.get(indice) : "";
    }

    private static void aggiungi(List<Riga> righe, String nome, String cognome, String note) {
        String n = spazi(nome);
        String c = spazi(cognome);
        String completo = c.isEmpty() ? n : n.isEmpty() ? c : c + " " + n;
        if (!completo.isEmpty()) {
            righe.add(new Riga(completo, spazi(note)));
        }
    }

    private static String spazi(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}
