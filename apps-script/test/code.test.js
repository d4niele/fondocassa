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

  // L'anagrafica è tenuta in ordine alfabetico.
  assert.deepEqual(env.ss.getSheetByName('Sync_Alunni').dati(5), [
    ['a1', 'Anna Rossi', true, 'Genitori: Paola e Luca', r.timestamp_sync],
    ['a2', 'Marco Verdi', true, '', r.timestamp_sync],
  ]);
  // Nel foglio gli importi sono in euro.
  assert.deepEqual(env.ss.getSheetByName('Sync_Quote').dati(5), [
    ['q1', 'Quota di avvio', 5, '2026-09-15', r.timestamp_sync],
  ]);
  assert.deepEqual(env.ss.getSheetByName('Sync_Entrate').dati(7), [
    ['e1', 'a1', '2026-09-20', 5, 'Contante', '', r.timestamp_sync],
  ]);
  assert.deepEqual(env.ss.getSheetByName('Sync_Spese').dati(7), [
    ['s1', 'Gessi', 'Materiale', '2026-10-01', 3.5, 'https://drive.google.com/file/d/0/view', r.timestamp_sync],
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
  assert.equal(vista.formule['2,2'], '=SUM(Sync_Quote!C2:C)');
  assert.equal(vista.formule['5,2'], '=SUM(Sync_Entrate!D2:D)');
  assert.equal(vista.formule['7,2'], '=SUM(Sync_Spese!E2:E)');
  assert.equal(vista.formule['8,2'], '=B5-B7');
  assert.equal(vista.formule['9,2'], '=B8+B6');
  assert.match(vista.formule['13,2'], /SUMIF\(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D\)/);
  assert.match(vista.formule['13,4'], /"In sospeso","In regola"/);
  assert.match(vista.formule['13,6'], /QUERY\(Sync_Spese!A2:F/);
  assert.deepEqual(vista.getRange(12, 1, 1, 4).getValues()[0], ['Alunno', 'Totale versato', 'Da versare', 'Stato']);
  assert.deepEqual(vista.getRange(12, 6, 1, 5).getValues()[0],
    ['Data', 'Categoria', 'Descrizione', 'Importo', 'Link giustificativo']);
});

test('ogni sync aggiorna la data in Vista_Classe senza ricrearla', () => {
  const env = ambiente();
  env.post({ token: TOKEN, action: 'sync', alunni: [anna] });
  const vista = env.ss.getSheetByName('Vista_Classe');
  vista.scrivi(1, 4, 'nota del rappresentante');

  const r = env.post({ token: TOKEN, action: 'sync', alunni: [marco] });

  assert.equal(env.ss.getSheetByName('Vista_Classe'), vista);
  assert.equal(vista.cella(10, 2), r.timestamp_sync);
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
  assert.deepEqual(righe[0].slice(0, 5), ['e1', 'a1', '2026-09-20', 7.5, 'Bonifico']);
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
    { alunni: [], quote: [], entrate: [], spese: [], ok: true });
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
    ['Vista_Classe', 'Sync_Alunni', 'Sync_Quote', 'Sync_Entrate', 'Sync_Spese']);
  assert.equal(env.drive.cartelle.length, 1);
  assert.equal(env.get({ action: 'ping', token }).ok, true);

  assert.equal(env.gs.setup(), token, 'rieseguire setup non cambia il token');
  assert.equal(env.ss.fogli.length, 5);

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
