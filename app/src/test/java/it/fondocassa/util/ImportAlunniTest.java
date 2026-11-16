package it.fondocassa.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.List;

public class ImportAlunniTest {
    private static void verifica(List<ImportAlunni.Riga> righe, String... attesi) {
        assertEquals(attesi.length / 2, righe.size());
        for (int i = 0; i < righe.size(); i++) {
            assertEquals(attesi[i * 2], righe.get(i).nome);
            assertEquals(attesi[i * 2 + 1], righe.get(i).note);
        }
    }

    @Test
    public void csvConIntestazioneEPuntoEVirgola() {
        verifica(ImportAlunni.leggi("nome;cognome;note\nAnna;Rossi;Genitori: Paola e Luca\nMarco;Verdi;\n"),
                "Rossi Anna", "Genitori: Paola e Luca",
                "Verdi Marco", "");
    }

    @Test
    public void csvSenzaIntestazioneUsaLOrdineNomeCognomeNote() {
        verifica(ImportAlunni.leggi("Anna,Rossi,mamma Paola\nMarco,Verdi"),
                "Rossi Anna", "mamma Paola",
                "Verdi Marco", "");
    }

    @Test
    public void leColonneSonoRiconosciuteDalNomeInQualsiasiOrdine() {
        verifica(ImportAlunni.leggi("Cognome\tNote\tClasse\tNome\r\nRossi\tallergia\t2B\tAnna\r\n"),
                "Rossi Anna", "allergia");
    }

    @Test
    public void campiTraVirgoletteConSeparatoriVirgoletteEACapo() {
        verifica(ImportAlunni.leggi("nome,cognome,note\n\"Anna\",\"De Rossi\",\"mamma \"\"Paola\"\", papà Luca\ntel. 333\"\n"),
                "De Rossi Anna", "mamma \"Paola\", papà Luca tel. 333");
    }

    @Test
    public void unaSolaColonnaEIlNomeCompleto() {
        verifica(ImportAlunni.leggi((char) 0xFEFF + "Rossi Anna\n\n  Verdi   Marco  \n"),
                "Rossi Anna", "",
                "Verdi Marco", "");
        verifica(ImportAlunni.leggi("alunno\nRossi Anna"), "Rossi Anna", "");
    }

    @Test
    public void jsonConOggetti() {
        verifica(ImportAlunni.leggi("[{\"nome\":\"Anna\",\"cognome\":\"Rossi\",\"note\":\"Genitori: Paola e Luca\"},"
                        + "{\"Nome\":\"Marco\",\"Cognome\":\"Verdi\"},{\"nome\":\"\"}]"),
                "Rossi Anna", "Genitori: Paola e Luca",
                "Verdi Marco", "");
    }

    @Test
    public void jsonConStringheODentroUnOggetto() {
        verifica(ImportAlunni.leggi("[\"Rossi Anna\", \"Verdi Marco\"]"), "Rossi Anna", "", "Verdi Marco", "");
        verifica(ImportAlunni.leggi("{\"classe\":\"2B\",\"alunni\":[{\"nome\":\"Anna\",\"cognome\":\"Rossi\"}]}"),
                "Rossi Anna", "");
    }

    @Test
    public void datiNonValidi() {
        assertThrows(IllegalArgumentException.class, () -> ImportAlunni.leggi(null));
        assertThrows(IllegalArgumentException.class, () -> ImportAlunni.leggi("   "));
        assertThrows(IllegalArgumentException.class, () -> ImportAlunni.leggi("[{\"nome\":"));
        assertThrows(IllegalArgumentException.class, () -> ImportAlunni.leggi("{\"classe\":\"2B\"}"));
        assertThrows(IllegalArgumentException.class, () -> ImportAlunni.leggi("nome;cognome;note\n;;\n"));
    }
}
