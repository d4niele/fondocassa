'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { carica } = require('./fake-google');

const TOKEN = 'token-di-prova';

function ambiente() {
  const env = carica();
  env.proprieta.set('API_TOKEN', TOKEN);
  return env;
}

const anna = { alunno_id: 'a1', nome: 'Anna Rossi', attivo: true, note: 'Genitori: Paola e Luca' };
const marco = { alunno_id: 'a2', nome: 'Marco Verdi', attivo: true, note: '' };
const quotaAvvio = { id_quota: 'q1', descrizione: 'Quota di avvio', importo_cent: 500, data: '2026-09-15' };
const entrata = {
  id_transazione: 'e1', alunno_id: 'a1', data: '2026-09-20', importo_cent: 500, metodo: 'Contante', note: '',
};
const spesa = {
  id_spesa: 's1', descrizione: 'Gessi', categoria: 'Materiale', data: '2026-10-01', importo_cent: 350,
  url_giustificativo: 'https://drive.google.com/file/d/0/view',
};

/** Come il foglio conserva una data: la mezzanotte di quel giorno a Roma (fuso del finto foglio). */
const giorno = (iso) => new Date(iso + (iso >= '2026-03-29' && iso < '2026-10-25' ? 'T00:00:00+02:00' : 'T00:00:00+01:00'));

test('senza token configurato ogni richiesta viene rifiutata', () => {
  const env = carica();
  const r = env.get({ action: 'ping', token: 'qualcosa' });
  assert.equal(r.ok, false);
  assert.match(r.error, /Token non configurato/);
});

test('token errato o mancante: nessuna lettura né scrittura', () => {
  const env = ambiente();
  assert.deepEqual(env.get({ action: 'export_all', token: 'sbagliato' }), { ok: false, error: 'Token non valido' });
  assert.deepEqual(env.get({ action: 'export_all' }), { ok: false, error: 'Token non valido' });
  assert.equal(env.post({ action: 'sync', token: 'sbagliato', alunni: [anna] }).ok, false);
  assert.equal(env.post({ action: 'sync', alunni: [anna] }).ok, false);
  assert.equal(env.ss.fogli.length, 0, 'non deve essere stato creato nessun foglio');
  assert.equal(env.lock.presi, 0);
});

test('ping con token corretto', () => {
  const env = ambiente();
  const risposta = env.gs.doGet({ parameter: { action: 'ping', token: TOKEN } });
  assert.equal(risposta.mime, 'application/json');
  assert.deepEqual(JSON.parse(risposta.testo), { ok: true });
});

test('azione sconosciuta e JSON non valido', () => {
  const env = ambiente();
  assert.match(env.get({ action: 'boh', token: TOKEN }).error, /Azione non riconosciuta/);
  assert.match(env.post({ action: 'boh', token: TOKEN }).error, /Azione non riconosciuta/);
  assert.match(env.post('non è json').error, /JSON atteso/);
});

test('sync inserisce i record nei fogli di servizio e conferma gli id', () => {
  const env = ambiente();
  const r = env.post({
    token: TOKEN, action: 'sync', alunni: [marco, anna], quote: [quotaAvvio], entrate: [entrata], spese: [spesa],
  });

  assert.equal(r.ok, true);
  assert.deepEqual(r.alunni, ['a2', 'a1']);
  assert.deepEqual(r.quote, ['q1']);
  assert.deepEqual(r.entrate, ['e1']);
  assert.deepEqual(r.spese, ['s1']);
  assert.match(r.timestamp_sync, /^\d{4}-\d{2}-\d{2}T/);

  // L'anagrafica è tenuta in ordine alfabetico; date e ora di sync sono date vere del foglio.
  const quando = new Date(r.timestamp_sync);
  assert.deepEqual(env.ss.getSheetByName('Sync_Alunni').dati(5), [
    ['a1', 'Anna Rossi', true, 'Genitori: Paola e Luca', quando],
    ['a2', 'Marco Verdi', true, '', quando],
  ]);
  // Nel foglio gli importi sono in euro.
  assert.deepEqual(env.ss.getSheetByName('Sync_Quote').dati(5), [
    ['q1', 'Quota di avvio', 5, giorno('2026-09-15'), quando],
  ]);
  assert.deepEqual(env.ss.getSheetByName('Sync_Entrate').dati(7), [
    ['e1', 'a1', giorno('2026-09-20'), 5, 'Contante', '', quando],
  ]);
  assert.deepEqual(env.ss.getSheetByName('Sync_Spese').dati(7), [
    ['s1', 'Gessi', 'Materiale', giorno('2026-10-01'), 3.5, 'https://drive.google.com/file/d/0/view', quando],
  ]);
  assert.equal(env.lock.presi, 1);
  assert.equal(env.lock.rilasciati, 1);
});

test('le intestazioni dei fogli rispettano la specifica', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });
  const intestazione = (nome, n) => env.ss.getSheetByName(nome).getRange(1, 1, 1, n).getValues()[0];

  assert.deepEqual(intestazione('Sync_Entrate', 7),
    ['id_transazione', 'alunno_id', 'data', 'importo', 'metodo', 'note', 'timestamp_sync']);
  assert.deepEqual(intestazione('Sync_Spese', 7),
    ['id_spesa', 'descrizione', 'categoria', 'data', 'importo', 'url_giustificativo', 'timestamp_sync']);
  assert.deepEqual(intestazione('Sync_Quote', 5),
    ['id_quota', 'descrizione', 'importo', 'data', 'timestamp_sync']);
  assert.deepEqual(intestazione('Sync_Alunni', 5), ['alunno_id', 'nome', 'attivo', 'note', 'timestamp_sync']);
});

test('i fogli sono correlati: nome alunno in Sync_Entrate e Vista_Classe fatta di formule', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna], entrate: [entrata] });

  const entrate = env.ss.getSheetByName('Sync_Entrate');
  assert.equal(entrate.cella(1, 8), 'alunno');
  assert.match(entrate.formule['2,8'], /VLOOKUP\(B2:B,Sync_Alunni!A:B,2,FALSE\)/);

  const vista = env.ss.getSheetByName('Vista_Classe');
  assert.equal(env.ss.fogli[0], vista, 'la vista è il primo foglio');
  assert.equal(vista.cella(4, 1), 'In cassa adesso');
  // Ogni voce occupa due righe: il valore e, sotto, la spiegazione.
  assert.equal(vista.cella(5, 1), '↳ Fondo iniziale + versato − spese: i soldi che ci sono davvero');
  assert.equal(vista.formule['4,2'], '=B9+B11-B13', 'in cassa: fondo iniziale + versato - spese');
  assert.equal(vista.cella(6, 1), 'Cassa virtuale');
  assert.equal(vista.formule['6,2'], '=B4+B15', 'cassa virtuale: in cassa + da versare');
  assert.equal(vista.cella(9, 1), 'Fondo iniziale');
  assert.equal(vista.formule['9,2'], '=SUM(Sync_Fondi!C2:C)');
  assert.equal(vista.formule['11,2'], '=SUM(Sync_Entrate!D2:D)');
  assert.equal(vista.formule['13,2'], '=SUM(Sync_Spese!E2:E)');
  assert.match(vista.formule['15,2'], /^=SUMPRODUCT\(.*\(B18-SUMIF/);
  assert.equal(vista.formule['18,2'], '=SUM(Sync_Quote!C2:C)');
  assert.equal(vista.cella(21, 1), 'Previsto', 'gli alunni attivi non hanno spiegazione');
  assert.equal(vista.formule['21,2'], '=B18*B20');
  assert.match(vista.formule['23,2'], /^=SUMPRODUCT\(.*\(B18-SUMIF.*<=0\)\)&" su "&B20$/);
  // Il bilancio non contiene dati dei singoli: si può pubblicare da solo.
  assert.equal(vista.getLastRow(), 24);
  assert.ok(Object.values(vista.formule).every((f) => !/ARRAYFORMULA|Sync_Alunni!B/.test(f)), 'nessun nome di alunno');

  const alunni = env.ss.getSheetByName('Vista_Alunni');
  assert.equal(env.ss.fogli[1], alunni, 'gli alunni sono il secondo foglio');
  assert.equal(alunni.cella(2, 1), 'Manca: quanto deve ancora versare l\'alunno. ✓ vuol dire in regola.');
  assert.deepEqual(alunni.getRange(3, 1, 1, 3).getValues()[0], ['Alunno', 'Versato', 'Manca']);
  assert.match(alunni.formule['4,1'], /^=ARRAYFORMULA\(IF\(Sync_Alunni!A2:A="","",Sync_Alunni!B2:B\)\)$/);
  assert.match(alunni.formule['4,2'], /SUMIF\(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D\)/);
  assert.match(alunni.formule['4,3'], /"non attivo",IF\(SUM\(Sync_Quote!C2:C\)-SUMIF.*,"✓"\)/);
  // Nessuna voce senza spiegazione, e nessuna spiegazione senza valore accanto.
  for (let riga = 4; riga <= 24; riga += 1) {
    const etichetta = vista.cella(riga, 1);
    if (!etichetta) continue;
    const valore = vista.formule[`${riga},2`];
    if (valore) {
      if (etichetta !== 'Alunni attivi') assert.ok(vista.cella(riga + 1, 1), `manca la spiegazione di ${etichetta}`);
    } else assert.ok(vista.formule[`${riga - 1},2`], `la riga ${riga} non spiega nessuna voce`);
  }
  // Niente più tabelle affiancate: tutto sta in tre colonne.
  assert.equal(Object.keys(vista.formule).filter((k) => Number(k.split(',')[1]) > 3).length, 0);

  const spese = env.ss.getSheetByName('Vista_Spese');
  assert.equal(env.ss.fogli[2], spese, 'le spese sono il terzo foglio');
  assert.deepEqual(spese.getRange(1, 1, 1, 4).getValues()[0], ['Data', 'Descrizione', 'Importo', 'Ricevuta']);
  assert.match(spese.formule['2,1'], /QUERY\(Sync_Spese!A2:F,"select D, B, E, F where A is not null order by D desc",0\)/);
});

test('i fogli sono leggibili: date e importi formattati, colonne larghe quanto serve', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna], spese: [spesa] });

  const spese = env.ss.getSheetByName('Sync_Spese');
  assert.equal(spese.formati[1], '@');
  assert.equal(spese.formati[4], 'dd/mm/yyyy');
  assert.equal(spese.formati[5], '#,##0.00" €"');
  assert.equal(spese.formati[7], 'dd/mm/yyyy hh:mm');
  assert.equal(spese.righeBloccate, 1);
  assert.ok(spese.sfondi['1,1'], 'intestazione evidenziata');
  assert.ok(spese.larghezze[2] > spese.larghezze[1], 'la descrizione è più larga della chiave');
  assert.equal(Object.keys(spese.larghezze).length, 7);
  // Anche la colonna collegata (nome dell'alunno) ha la sua larghezza.
  assert.equal(Object.keys(env.ss.getSheetByName('Sync_Entrate').larghezze).length, 8);

  const vista = env.ss.getSheetByName('Vista_Classe');
  assert.equal(vista.formatiCella['4,2'], '#,##0.00" €"');
  assert.equal(vista.formatiCella['20,2'], '0', 'il numero di alunni non è un importo');
  const alunni = env.ss.getSheetByName('Vista_Alunni');
  assert.equal(alunni.formatiCella['4,3'], '[Red]#,##0.00" €"', 'quanto manca è in rosso');
  assert.equal(alunni.formatiCella['4,2'], '#,##0.00" €"');
  assert.equal(alunni.righeBloccate, 0);
  assert.ok(Object.values(alunni.larghezze).reduce((a, b) => a + b, 0) <= 380);
  assert.equal(vista.stili['4,2'].corpo, 14, 'la cassa reale è in evidenza');
  // Le spiegazioni sono piccole, grigie, su tutta la larghezza e vanno a capo.
  assert.ok(vista.unioni.some(([r, c, nr, nc]) => r === 5 && c === 1 && nr === 1 && nc === 3));
  assert.equal(vista.stili['5,1'].corpo, 9);
  assert.equal(vista.stili['5,1'].aCapo, true);
  assert.equal(vista.unioni.length, 9, 'una spiegazione per ogni voce tranne gli alunni attivi');
  // La spiegazione si lega alla voce sopra: corsivo e linea che chiude il blocco sotto di lei.
  assert.equal(vista.stili['5,1'].stile, 'italic');
  assert.equal(vista.stili['5,1'].bordoSotto, 'SOLID #dadce0');
  assert.equal(vista.stili['4,1'].bordoSotto, undefined, 'nessuna linea tra la voce e la sua spiegazione');
  assert.equal(vista.stili['20,1'].bordoSotto, 'SOLID #dadce0', 'senza spiegazione la linea va sotto il valore');
  // Pensata per il telefono: larga quanto uno schermo in verticale, senza righe bloccate.
  const larghezza = Object.values(vista.larghezze).reduce((a, b) => a + b, 0);
  assert.ok(larghezza <= 380, `Vista_Classe larga ${larghezza} px`);
  assert.equal(vista.righeBloccate, 0);
  assert.equal(vista.grigliaNascosta, true);
  const vistaSpese = env.ss.getSheetByName('Vista_Spese');
  assert.ok(Object.values(vistaSpese.larghezze).reduce((a, b) => a + b, 0) <= 380);
  assert.equal(vistaSpese.formatiCella['2,1'], 'dd/mm/yy');
  assert.equal(vistaSpese.stili['2,4'].aCapo, 'CLIP', 'il link della ricevuta non allarga la riga');
});

test('un foglio creato da una versione precedente viene riformattato una volta sola', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', entrate: [entrata], spese: [spesa] });
  // Stato lasciato dalla versione precedente: date e timestamp come testo, nessuna larghezza.
  const entrate = env.ss.getSheetByName('Sync_Entrate');
  entrate.scrivi(2, 3, '2026-09-20');
  entrate.scrivi(2, 7, '2026-11-16T09:00:00.000Z');
  entrate.formati = {};
  entrate.larghezze = {};
  env.proprieta.delete('FORMATO_FOGLI');

  // Una sync che non tocca le entrate deve comunque sistemarle.
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });

  assert.deepEqual(entrate.cella(2, 3), giorno('2026-09-20'));
  assert.deepEqual(entrate.cella(2, 7), new Date('2026-11-16T09:00:00.000Z'));
  assert.equal(entrate.formati[3], 'dd/mm/yyyy');
  assert.equal(Object.keys(entrate.larghezze).length, 8);
  const r = env.get({ action: 'export_all', token: TOKEN });
  assert.equal(r.entrate[0].data, '2026-09-20');
  assert.equal(r.entrate[0].timestamp_sync, '2026-11-16T09:00:00.000Z');

  // Dalla volta successiva non si riformatta più: le larghezze scelte a mano restano.
  entrate.larghezze[2] = 42;
  env.post({ token: TOKEN, action: 'sync', alunni: [marco] });
  assert.equal(entrate.larghezze[2], 42);
});

test('un errore nella formattazione non impedisce di salvare i dati', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });
  env.proprieta.delete('FORMATO_FOGLI');
  env.ss.getSheetByName('Sync_Alunni').setColumnWidth = () => {
    throw new Error('Servizio non disponibile');
  };

  const r = env.post({ token: TOKEN, action: 'sync', alunni: [marco] });

  assert.equal(r.ok, true);
  assert.deepEqual(r.alunni, ['a2']);
  assert.equal(env.ss.getSheetByName('Sync_Alunni').dati(5).length, 2);
});

test('una data non valida resta testo invece di far fallire la sync', () => {
  const env = ambiente();
  const r = env.post({ token: TOKEN, action: 'sync', entrate: [{ ...entrata, data: 'ieri' }, { ...entrata, id_transazione: 'e2', data: '' }] });
  assert.equal(r.ok, true);
  const righe = env.ss.getSheetByName('Sync_Entrate').dati(7);
  assert.equal(righe[0][2], 'ieri');
  assert.equal(righe[1][2], '');
});

test('in un foglio in italiano le formule usano il punto e virgola', () => {
  const env = ambiente();
  env.ss.virgolaDecimale = true;
  env.post({ token: TOKEN, action: 'sync', alunni: [anna], entrate: [entrata] });

  const entrate = env.ss.getSheetByName('Sync_Entrate');
  assert.equal(entrate.formule['2,8'],
    '=ARRAYFORMULA(IF(B2:B="";"";IFERROR(VLOOKUP(B2:B;Sync_Alunni!A:B;2;FALSE);"?")))');
  const vista = env.ss.getSheetByName('Vista_Classe');
  assert.equal(vista.formule['20,2'], '=COUNTIFS(Sync_Alunni!A2:A;"<>";Sync_Alunni!C2:C;TRUE)');
  assert.match(vista.formule['23,2'], /<=0\)\)&" su "&B20$/);
  assert.match(env.ss.getSheetByName('Vista_Alunni').formule['4,3'], /"non attivo";IF\(/);
  // Le virgole dentro le stringhe (il linguaggio di QUERY) non vanno toccate.
  const spese = env.ss.getSheetByName('Vista_Spese');
  assert.match(spese.formule['2,1'], /"select D, B, E, F where A is not null order by D desc";0\);""\)$/);
  const tutte = Object.values(vista.formule).concat(Object.values(entrate.formule), Object.values(spese.formule));
  assert.ok(tutte.every((f) => !/,/.test(f.replace(/"[^"]*"/g, ''))), 'nessuna virgola tra gli argomenti');
  // La cella usata per la prova del separatore viene ripulita.
  const alunni = env.ss.getSheetByName('Sync_Alunni');
  assert.equal(alunni.formule[`${alunni.getMaxRows()},3`], undefined);
});

test('le formule con #ERROR! di una versione precedente vengono riscritte', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna], entrate: [entrata] });
  // Foglio in italiano creato dalla versione precedente: formule con la virgola.
  env.ss.virgolaDecimale = true;
  env.proprieta.delete('FORMATO_FOGLI');
  const vista = env.ss.getSheetByName('Vista_Classe');
  vista.scrivi(1, 12, 'nota del rappresentante');

  env.post({ token: TOKEN, action: 'sync', alunni: [marco] });

  assert.match(env.ss.getSheetByName('Sync_Entrate').formule['2,8'], /^=ARRAYFORMULA\(IF\(B2:B="";"";/);
  assert.match(env.ss.getSheetByName('Vista_Alunni').formule['4,1'], /^=ARRAYFORMULA\(IF\(Sync_Alunni!A2:A="";"";/);
  assert.equal(vista.cella(1, 12), 'nota del rappresentante', 'oltre la colonna J la vista non tocca nulla');
});

test('la colonna attivo è una casella di spunta, solo sulle righe con dati', () => {
  const env = ambiente();
  const inattivo = { alunno_id: 'a3', nome: 'Zoe Bianchi', attivo: false, note: '' };
  env.post({ token: TOKEN, action: 'sync', alunni: [anna, marco, inattivo] });
  const alunni = env.ss.getSheetByName('Sync_Alunni');

  assert.deepEqual(Object.keys(alunni.convalide).sort(), ['2,3', '3,3', '4,3']);
  assert.equal(alunni.convalide['2,3'].tipo, 'casella di spunta');
  // I valori restano veri booleani: le formule di Vista_Classe e l'export non cambiano.
  assert.deepEqual(alunni.dati(3).map((riga) => riga[2]), [true, true, false]);
  assert.deepEqual(env.get({ action: 'export_all', token: TOKEN }).alunni.map((a) => a.attivo), [true, true, false]);

  // Eliminando un alunno la casella della riga rimasta vuota sparisce.
  env.post({ token: TOKEN, action: 'sync', alunni: [{ alunno_id: 'a3', eliminato: true }] });
  assert.deepEqual(Object.keys(alunni.convalide).sort(), ['2,3', '3,3']);
});

test('ogni sync aggiorna la data in Vista_Classe senza ricrearla', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });
  const vista = env.ss.getSheetByName('Vista_Classe');
  vista.scrivi(1, 4, 'nota del rappresentante');

  const r = env.post({ token: TOKEN, action: 'sync', alunni: [marco] });

  assert.equal(env.ss.getSheetByName('Vista_Classe'), vista);
  const atteso = new Intl.DateTimeFormat('it-IT', { timeZone: 'Europe/Rome', day: '2-digit', month: '2-digit',
    year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(r.timestamp_sync)).replace(',', '');
  assert.equal(vista.cella(2, 1), 'Aggiornato al ' + atteso);
  assert.equal(vista.cella(1, 4), 'nota del rappresentante');
});

test('upsert: un id già presente viene aggiornato, non duplicato', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna], entrate: [entrata] });
  env.post({
    token: TOKEN,
    action: 'sync',
    entrate: [{ ...entrata, importo_cent: 750, metodo: 'Bonifico' }, { ...entrata, id_transazione: 'e2' }],
  });

  const righe = env.ss.getSheetByName('Sync_Entrate').dati(7);
  assert.equal(righe.length, 2);
  assert.deepEqual(righe[0].slice(0, 5), ['e1', 'a1', giorno('2026-09-20'), 7.5, 'Bonifico']);
  assert.equal(righe[1][0], 'e2');
});

test('i record eliminati vengono rimossi dal foglio senza lasciare righe sporche', () => {
  const env = ambiente();
  env.post({
    token: TOKEN,
    action: 'sync',
    entrate: [entrata, { ...entrata, id_transazione: 'e2' }, { ...entrata, id_transazione: 'e3' }],
  });

  const r = env.post({
    token: TOKEN,
    action: 'sync',
    entrate: [{ id_transazione: 'e2', eliminato: true }, { id_transazione: 'mai-esistito', eliminato: true }],
  });

  assert.deepEqual(r.entrate, ['e2', 'mai-esistito']);
  const foglio = env.ss.getSheetByName('Sync_Entrate');
  assert.deepEqual(foglio.dati(7).map((riga) => riga[0]), ['e1', 'e3']);
  assert.equal(foglio.getLastRow(), 3);
});

test('i record senza chiave non vengono confermati', () => {
  const env = ambiente();
  const r = env.post({ token: TOKEN, action: 'sync', alunni: [{ nome: 'Senza id' }, anna] });
  assert.deepEqual(r.alunni, ['a1']);
  assert.equal(env.ss.getSheetByName('Sync_Alunni').dati(5).length, 1);
});

test('export_all restituisce esattamente ciò che è stato sincronizzato', () => {
  const env = ambiente();
  const inattivo = { alunno_id: 'a3', nome: 'Zoe Bianchi', attivo: false, note: '' };
  const s = env.post({
    token: TOKEN, action: 'sync', alunni: [anna, inattivo], quote: [quotaAvvio], entrate: [entrata], spese: [spesa],
  });

  const r = env.get({ action: 'export_all', token: TOKEN });

  assert.equal(r.ok, true);
  assert.deepEqual(r.alunni, [
    { ...anna, timestamp_sync: s.timestamp_sync },
    { ...inattivo, timestamp_sync: s.timestamp_sync },
  ]);
  assert.deepEqual(r.quote, [{ ...quotaAvvio, timestamp_sync: s.timestamp_sync }]);
  assert.deepEqual(r.entrate, [{ ...entrata, timestamp_sync: s.timestamp_sync }]);
  assert.deepEqual(r.spese, [{ ...spesa, timestamp_sync: s.timestamp_sync }]);
});

test('export_all su un foglio vuoto', () => {
  const env = ambiente();
  assert.deepEqual(env.get({ action: 'export_all', token: TOKEN }),
    { alunni: [], quote: [], entrate: [], spese: [], fondi: [], ok: true });
});

test('export_all normalizza date e importi modificati a mano nel foglio', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', entrate: [entrata] });
  const foglio = env.ss.getSheetByName('Sync_Entrate');
  foglio.scrivi(2, 3, new Date('2026-09-20T10:00:00Z')); // Sheets ha convertito la cella in data
  foglio.scrivi(2, 4, 12.345); // importo con troppi decimali

  const r = env.get({ action: 'export_all', token: TOKEN });

  assert.equal(r.entrate[0].data, '2026-09-20');
  assert.equal(r.entrate[0].importo_cent, 1235);
});

test('upload_giustificativo salva il file su Drive in sola lettura e restituisce il link', () => {
  const env = ambiente();
  const r = env.post({
    token: TOKEN,
    action: 'upload_giustificativo',
    id_spesa: 's1',
    filename: 'scontrino del 1° ottobre.jpg',
    mime_type: 'image/jpeg',
    data_base64: Buffer.from([9, 8, 7]).toString('base64'),
  });

  assert.equal(r.ok, true);
  assert.match(r.url, /^https:\/\/drive\.google\.com\/file\/d\//);
  assert.equal(env.drive.cartelle.length, 1);
  assert.equal(env.drive.cartelle[0].nome, 'Fondocassa_Giustificativi');
  const file = env.drive.file[0];
  assert.deepEqual(file.byte, [9, 8, 7]);
  assert.equal(file.mime, 'image/jpeg');
  assert.equal(file.nome, 's1_scontrino_del_1__ottobre.jpg');
  assert.deepEqual(file.condivisione, ['ANYONE_WITH_LINK', 'VIEW']);

  // La cartella viene riutilizzata agli upload successivi.
  env.post({ token: TOKEN, action: 'upload_giustificativo', id_spesa: 's2', data_base64: 'AA==' });
  assert.equal(env.drive.cartelle.length, 1);
  assert.equal(env.drive.file.length, 2);
});

test('upload_giustificativo riesce anche se l\'account vieta la condivisione pubblica', () => {
  const env = ambiente();
  const carica = (id) => env.post({ token: TOKEN, action: 'upload_giustificativo', id_spesa: id, data_base64: 'AA==' });

  env.drive.accessiVietati = ['ANYONE_WITH_LINK'];
  const nelDominio = carica('s1');
  assert.equal(nelDominio.ok, true);
  assert.equal(nelDominio.condivisione, 'dominio');
  assert.deepEqual(env.drive.file[0].condivisione, ['DOMAIN_WITH_LINK', 'VIEW']);

  env.drive.accessiVietati = ['ANYONE_WITH_LINK', 'DOMAIN_WITH_LINK'];
  const privato = carica('s2');
  assert.equal(privato.ok, true);
  assert.equal(privato.condivisione, 'privato');
  assert.match(privato.url, /^https:/);
  assert.equal(env.drive.file[1].condivisione, null);
});

test('upload_giustificativo ricrea la cartella se è stata eliminata o cestinata', () => {
  const env = ambiente();
  const carica = (id) => env.post({ token: TOKEN, action: 'upload_giustificativo', id_spesa: id, data_base64: 'AA==' });
  assert.equal(carica('s1').ok, true);

  env.drive.cartelle[0].eliminata = true;
  assert.equal(carica('s2').ok, true);
  assert.equal(env.drive.cartelle.length, 2);

  env.drive.cartelle[1].cestinata = true;
  assert.equal(carica('s3').ok, true);
  assert.equal(env.drive.cartelle.length, 3);
});

test('upload_giustificativo spiega cosa fare se Drive non è autorizzato', () => {
  const env = ambiente();
  env.drive.erroreCreazione = 'You do not have permission to call DriveApp.Folder.createFile.';
  const r = env.post({ token: TOKEN, action: 'upload_giustificativo', id_spesa: 's1', data_base64: 'AA==' });
  assert.equal(r.ok, false);
  assert.match(r.error, /esegui la funzione setup/);
  assert.match(r.error, /You do not have permission to call DriveApp/);
});

test('upload_giustificativo senza dati viene rifiutato', () => {
  const env = ambiente();
  const r = env.post({ token: TOKEN, action: 'upload_giustificativo', id_spesa: 's1' });
  assert.equal(r.ok, false);
  assert.equal(env.drive.file.length, 0);
  assert.equal(env.lock.rilasciati, 1, 'il lock va rilasciato anche in caso di errore');
});

test('setup crea fogli, cartella e un token che resta stabile', () => {
  const env = carica();
  const token = env.gs.setup();

  assert.equal(token.length, 64);
  assert.deepEqual(env.ss.fogli.map((f) => f.nome),
    ['Vista_Classe', 'Vista_Alunni', 'Vista_Spese', 'Sync_Alunni', 'Sync_Quote', 'Sync_Entrate', 'Sync_Spese', 'Sync_Fondi']);
  assert.equal(env.drive.cartelle.length, 1);
  assert.equal(env.get({ action: 'ping', token }).ok, true);

  assert.equal(env.gs.setup(), token, 'rieseguire setup non cambia il token');
  assert.equal(env.ss.fogli.length, 8);

  const nuovo = env.gs.rigeneraToken();
  assert.notEqual(nuovo, token);
  assert.equal(env.get({ action: 'ping', token }).ok, false);
  assert.equal(env.get({ action: 'ping', token: nuovo }).ok, true);
});

test('backup crea uno ZIP privato con il database e tutti i giustificativi', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });
  const carica = (id, nome, byte) => env.post({
    token: TOKEN, action: 'upload_giustificativo', id_spesa: id, filename: nome, mime_type: 'image/jpeg',
    data_base64: Buffer.from(byte).toString('base64'),
  });
  carica('s1', 'scontrino.jpg', [1, 2]);
  carica('s2', 'ricevuta.pdf', [3]);
  const db = Buffer.from('SQLite format 3\0dati');

  const r = env.post({ token: TOKEN, action: 'backup', db_base64: db.toString('base64') });

  assert.equal(r.ok, true);
  assert.equal(r.giustificativi, 2);
  assert.match(r.nome, /^fondocassa-backup-\d{8}-\d{6}\.zip$/);
  assert.match(r.url, /^https:\/\/drive\.google\.com\/file\/d\//);
  assert.deepEqual(env.drive.cartelle.map((c) => c.nome), ['Fondocassa_Giustificativi', 'Fondocassa_Backup']);
  const zip = env.drive.file[2];
  assert.equal(zip.cartella, 'Fondocassa_Backup');
  assert.equal(zip.nome, r.nome);
  assert.equal(zip.condivisione, null, 'il backup contiene dati personali: non va condiviso');
  assert.deepEqual(Array.from(zip.voci, (v) => v.nome),
    ['fondocassa.db', 'giustificativi/s1_scontrino.jpg', 'giustificativi/s2_ricevuta.pdf']);
  assert.deepEqual(zip.voci[0].byte, Array.from(db));
  assert.deepEqual(zip.voci[1].byte, [1, 2]);

  // Un secondo backup non include gli ZIP precedenti.
  const secondo = env.post({ token: TOKEN, action: 'backup', db_base64: db.toString('base64') });
  assert.equal(secondo.giustificativi, 2);
});

test('backup senza database o senza token viene rifiutato', () => {
  const env = ambiente();
  assert.match(env.post({ token: TOKEN, action: 'backup' }).error, /manca il database/);
  assert.equal(env.post({ token: 'sbagliato', action: 'backup', db_base64: 'AA==' }).ok, false);
  assert.equal(env.drive.file.length, 0);
});

test('il fondo iniziale va in Sync_Fondi, entra nella cassa reale e torna con export_all', () => {
  const env = ambiente();
  const fondo = { id_fondo: 'fondo_iniziale', descrizione: 'Avanzo a.s. 2025/26', importo_cent: 3750,
    data: '2026-09-01' };
  const r = env.post({ token: TOKEN, action: 'sync', fondi: [fondo] });
  assert.deepEqual(r.fondi, ['fondo_iniziale']);

  const foglio = env.ss.getSheetByName('Sync_Fondi');
  assert.deepEqual(foglio.getRange(1, 1, 1, 5).getValues()[0],
    ['id_fondo', 'descrizione', 'importo', 'data', 'timestamp_sync']);
  assert.equal(foglio.cella(2, 3), 37.5);

  const vista = env.ss.getSheetByName('Vista_Classe');
  assert.equal(vista.cella(9, 1), 'Fondo iniziale');
  assert.equal(vista.cella(10, 1), '↳ Avanzo degli anni passati, con cui la cassa è partita');
  assert.equal(vista.formule['9,2'], '=SUM(Sync_Fondi!C2:C)');

  const e = env.get({ action: 'export_all', token: TOKEN });
  assert.deepEqual(e.fondi, [{ ...fondo, timestamp_sync: r.timestamp_sync }]);
});

test('una Vista_Classe della versione precedente viene ricomposta per il telefono', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna], spese: [spesa] });
  // Stato della versione precedente: niente Sync_Fondi né Vista_Spese, tabelle affiancate dalla
  // riga 13 con 12 righe bloccate, registro uscite in F:J.
  env.ss.fogli = env.ss.fogli.filter((f) => !['Sync_Fondi', 'Vista_Spese'].includes(f.getName()));
  const vista = env.ss.getSheetByName('Vista_Classe');
  vista.colonne = 26;
  vista.getRange(1, 1, 1000, 10).clear();
  vista.scrivi(2, 1, 'Quota per alunno');
  vista.formule['2,2'] = '=SUM(Sync_Quote!C2:C)';
  vista.formule['13,6'] = '=IFERROR(QUERY(Sync_Spese!A2:F,"select D, C, B, E, F",0),"")';
  vista.scrivi(12, 6, 'Data');
  vista.setFrozenRows(12);
  vista.scrivi(1, 12, 'nota del rappresentante');
  env.proprieta.set('FORMATO_FOGLI', '5');

  env.post({ token: TOKEN, action: 'sync', alunni: [marco] });

  assert.ok(env.ss.getSheetByName('Sync_Fondi'), 'il foglio dei fondi viene creato');
  assert.ok(env.ss.getSheetByName('Vista_Spese'), 'il registro delle uscite ha il suo foglio');
  assert.equal(env.ss.getSheetByName('Vista_Classe'), vista, 'stesso foglio: i link verso la vista restano validi');
  assert.equal(vista.cella(4, 1), 'In cassa adesso');
  assert.equal(vista.formule['4,2'], '=B9+B11-B13');
  assert.equal(vista.formule['2,2'], undefined);
  assert.equal(vista.formule['13,6'], undefined, 'il vecchio registro uscite è stato tolto');
  assert.equal(vista.cella(12, 6), '');
  assert.equal(vista.righeBloccate, 0);
  assert.equal(vista.cella(1, 12), 'nota del rappresentante');
  assert.equal(vista.getMaxColumns(), 12, 'le colonne vuote dopo la nota vengono eliminate');
  assert.match(vista.cella(2, 1), /^Aggiornato al /);
});

test('ogni foglio ha solo le colonne che usa', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna], entrate: [entrata], spese: [spesa] });

  const colonne = Object.fromEntries(env.ss.fogli.map((f) => [f.getName(), f.getMaxColumns()]));
  assert.deepEqual(colonne, {
    Vista_Classe: 3, Vista_Alunni: 3, Vista_Spese: 4, Sync_Alunni: 5, Sync_Quote: 5, Sync_Entrate: 8, Sync_Spese: 7, Sync_Fondi: 5,
  });
  // Le sincronizzazioni successive continuano a funzionare con le colonne ridotte.
  const r = env.post({ token: TOKEN, action: 'sync', alunni: [marco], entrate: [{ ...entrata, importo_cent: 300 }] });
  assert.equal(r.ok, true);
  assert.equal(env.ss.getSheetByName('Sync_Alunni').formule['1000,3'], undefined, 'la cella di prova viene ripulita');
});

test('un foglio della versione precedente perde le colonne vuote una volta sola', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });
  const alunni = env.ss.getSheetByName('Sync_Alunni');
  alunni.colonne = 26;
  alunni.scrivi(1, 9, 'appunto a mano');
  env.proprieta.set('FORMATO_FOGLI', '6');

  env.post({ token: TOKEN, action: 'sync', alunni: [marco] });

  assert.equal(alunni.getMaxColumns(), 9, 'la colonna con l\'appunto resta');
  assert.equal(alunni.cella(1, 9), 'appunto a mano');
});

test('ricomporre la vista non accumula celle unite', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });
  const vista = env.ss.getSheetByName('Vista_Classe');
  const prima = vista.unioni.length;
  env.proprieta.set('FORMATO_FOGLI', '7');

  env.post({ token: TOKEN, action: 'sync', alunni: [marco] });

  assert.equal(vista.unioni.length, prima);
});

test('la tabella degli alunni lascia Vista_Classe e va nella sua scheda', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });
  // Stato della versione precedente: tabella degli alunni in Vista_Classe dalla riga 29.
  env.ss.fogli = env.ss.fogli.filter((f) => f.getName() !== 'Vista_Alunni');
  const vista = env.ss.getSheetByName('Vista_Classe');
  vista.formule['29,1'] = '=ARRAYFORMULA(IF(Sync_Alunni!A2:A="","",Sync_Alunni!B2:B))';
  vista.scrivi(28, 1, 'Alunno');
  env.proprieta.set('FORMATO_FOGLI', '8');

  env.post({ token: TOKEN, action: 'sync', alunni: [marco] });

  assert.equal(vista.formule['29,1'], undefined);
  assert.equal(vista.cella(28, 1), '');
  assert.deepEqual(env.ss.fogli.slice(0, 3).map((f) => f.getName()), ['Vista_Classe', 'Vista_Alunni', 'Vista_Spese']);
  assert.match(env.ss.getSheetByName('Vista_Alunni').formule['4,1'], /Sync_Alunni!B2:B/);
});
