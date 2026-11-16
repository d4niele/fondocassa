package it.fondocassa.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.fondocassa.data.Alunno;
import it.fondocassa.data.AlunnoConSaldo;

public class BilancioTest {
    private static AlunnoConSaldo alunno(long versato, boolean attivo) {
        AlunnoConSaldo a = new AlunnoConSaldo();
        a.alunno = new Alunno();
        a.alunno.attivo = attivo;
        a.totaleVersato = versato;
        return a;
    }

    @Test
    public void quotaDiAvvioDaCinqueEuro() {
        // 4 alunni, quota di 5 €: due hanno pagato tutto, uno metà, uno niente. Spese per 3 €.
        List<AlunnoConSaldo> classe = Arrays.asList(
                alunno(500, true), alunno(500, true), alunno(250, true), alunno(0, true));
        Bilancio b = Bilancio.calcola(classe, 500, 1250, 300);

        assertEquals(2000, b.previsto);
        assertEquals(1250, b.versato);
        assertEquals(750, b.daVersare);
        assertEquals(950, b.cassaReale);
        assertEquals(1700, b.cassaVirtuale);
        assertEquals(4, b.alunniAttivi);
        assertEquals(2, b.alunniInRegola);
    }

    @Test
    public void chiVersaInPiuNonCopreIlDebitoDegliAltri() {
        List<AlunnoConSaldo> classe = Arrays.asList(alunno(1000, true), alunno(0, true));
        Bilancio b = Bilancio.calcola(classe, 500, 1000, 0);

        assertEquals(1000, b.previsto);
        assertEquals(500, b.daVersare);
        assertEquals(1000, b.cassaReale);
        assertEquals(1500, b.cassaVirtuale);
        assertEquals(1, b.alunniInRegola);
    }

    @Test
    public void gliAlunniNonAttiviNonDevonoNullaMaILoroVersamentiRestanoInCassa() {
        List<AlunnoConSaldo> classe = Arrays.asList(alunno(500, true), alunno(200, false));
        Bilancio b = Bilancio.calcola(classe, 500, 700, 0);

        assertEquals(500, b.previsto);
        assertEquals(0, b.daVersare);
        assertEquals(700, b.cassaReale);
        assertEquals(700, b.cassaVirtuale);
        assertEquals(1, b.alunniAttivi);
    }

    @Test
    public void senzaQuoteTuttiSonoInRegola() {
        Bilancio b = Bilancio.calcola(Arrays.asList(alunno(0, true), alunno(0, true)), 0, 0, 0);
        assertEquals(0, b.previsto);
        assertEquals(0, b.daVersare);
        assertEquals(2, b.alunniInRegola);
    }

    @Test
    public void classeVuota() {
        Bilancio b = Bilancio.calcola(new ArrayList<>(), 500, 0, 250);
        assertEquals(0, b.previsto);
        assertEquals(-250, b.cassaReale);
        assertEquals(-250, b.cassaVirtuale);
    }

    @Test
    public void statoPagamento() {
        assertEquals(StatoPagamento.IN_REGOLA, StatoPagamento.calcola(500, 500));
        assertEquals(StatoPagamento.IN_REGOLA, StatoPagamento.calcola(600, 500));
        assertEquals(StatoPagamento.IN_SOSPESO, StatoPagamento.calcola(499, 500));
        assertEquals(1, StatoPagamento.residuo(499, 500));
        assertEquals(0, StatoPagamento.residuo(600, 500));
        assertEquals("In regola", StatoPagamento.IN_REGOLA.etichetta);
        assertEquals("In sospeso", StatoPagamento.IN_SOSPESO.etichetta);
    }
}
