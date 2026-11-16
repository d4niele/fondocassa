package it.fondocassa.sync;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

import it.fondocassa.data.Alunno;
import it.fondocassa.data.Entrata;
import it.fondocassa.data.Quota;
import it.fondocassa.data.Spesa;

/** Oggetti JSON scambiati con il Google Apps Script. */
public final class ApiModels {
    private ApiModels() {
    }

    public static class AlunnoDto {
        @SerializedName("alunno_id") public String id;
        public String nome;
        public String note;
        public boolean attivo = true;
        public boolean eliminato;
        @SerializedName("timestamp_sync") public String timestampSync;

        public static AlunnoDto da(Alunno a) {
            AlunnoDto d = new AlunnoDto();
            d.id = a.id;
            d.nome = a.nome;
            d.note = a.note == null ? "" : a.note;
            d.attivo = a.attivo;
            d.eliminato = a.eliminato;
            return d;
        }

        public Alunno inEntita() {
            Alunno a = new Alunno();
            a.id = id;
            a.nome = nome == null ? "" : nome;
            a.note = note == null ? "" : note;
            a.attivo = attivo;
            a.synced = true;
            a.timestampSync = timestampSync;
            return a;
        }
    }

    public static class QuotaDto {
        @SerializedName("id_quota") public String id;
        public String descrizione;
        @SerializedName("importo_cent") public long importoCent;
        public String data;
        public boolean eliminato;
        @SerializedName("timestamp_sync") public String timestampSync;

        public static QuotaDto da(Quota q) {
            QuotaDto d = new QuotaDto();
            d.id = q.id;
            d.descrizione = q.descrizione;
            d.importoCent = q.importo;
            d.data = q.data;
            d.eliminato = q.eliminato;
            return d;
        }

        public Quota inEntita() {
            Quota q = new Quota();
            q.id = id;
            q.descrizione = descrizione == null ? "" : descrizione;
            q.importo = importoCent;
            q.data = data == null ? "" : data;
            q.synced = true;
            q.timestampSync = timestampSync;
            return q;
        }
    }

    public static class EntrataDto {
        @SerializedName("id_transazione") public String id;
        @SerializedName("alunno_id") public String alunnoId;
        public String data;
        @SerializedName("importo_cent") public long importoCent;
        public String metodo;
        public String note;
        public boolean eliminato;
        @SerializedName("timestamp_sync") public String timestampSync;

        public static EntrataDto da(Entrata e) {
            EntrataDto d = new EntrataDto();
            d.id = e.id;
            d.alunnoId = e.alunnoId;
            d.data = e.data;
            d.importoCent = e.importo;
            d.metodo = e.metodo;
            d.note = e.note;
            d.eliminato = e.eliminato;
            return d;
        }

        public Entrata inEntita() {
            Entrata e = new Entrata();
            e.id = id;
            e.alunnoId = alunnoId == null ? "" : alunnoId;
            e.data = data == null ? "" : data;
            e.importo = importoCent;
            e.metodo = metodo == null ? "" : metodo;
            e.note = note == null ? "" : note;
            e.synced = true;
            e.timestampSync = timestampSync;
            return e;
        }
    }

    public static class SpesaDto {
        @SerializedName("id_spesa") public String id;
        public String descrizione;
        public String categoria;
        public String data;
        @SerializedName("importo_cent") public long importoCent;
        @SerializedName("url_giustificativo") public String urlGiustificativo;
        public boolean eliminato;
        @SerializedName("timestamp_sync") public String timestampSync;

        public static SpesaDto da(Spesa s) {
            SpesaDto d = new SpesaDto();
            d.id = s.id;
            d.descrizione = s.descrizione;
            d.categoria = s.categoria;
            d.data = s.data;
            d.importoCent = s.importo;
            d.urlGiustificativo = s.urlGiustificativo == null ? "" : s.urlGiustificativo;
            d.eliminato = s.eliminato;
            return d;
        }

        public Spesa inEntita() {
            Spesa s = new Spesa();
            s.id = id;
            s.descrizione = descrizione == null ? "" : descrizione;
            s.categoria = categoria == null ? "" : categoria;
            s.data = data == null ? "" : data;
            s.importo = importoCent;
            s.urlGiustificativo = urlGiustificativo == null ? "" : urlGiustificativo;
            s.synced = true;
            s.timestampSync = timestampSync;
            return s;
        }
    }

    public static class SyncRequest {
        public String token;
        public final String action = "sync";
        public List<AlunnoDto> alunni = new ArrayList<>();
        public List<QuotaDto> quote = new ArrayList<>();
        public List<EntrataDto> entrate = new ArrayList<>();
        public List<SpesaDto> spese = new ArrayList<>();
    }

    public static class BackupRequest {
        public String token;
        public final String action = "backup";
        @SerializedName("db_base64") public String dbBase64;
    }

    /** ZIP creato su Drive con il database e i giustificativi. */
    public static class BackupResponse {
        public String url;
        public String nome;
        public int giustificativi;
    }

    /** Lo script conferma gli id effettivamente scritti sul foglio. */
    public static class SyncResponse {
        @SerializedName("timestamp_sync") public String timestampSync;
        public List<String> alunni = new ArrayList<>();
        public List<String> quote = new ArrayList<>();
        public List<String> entrate = new ArrayList<>();
        public List<String> spese = new ArrayList<>();
    }

    public static class UploadRequest {
        public String token;
        public final String action = "upload_giustificativo";
        @SerializedName("id_spesa") public String idSpesa;
        public String filename;
        @SerializedName("mime_type") public String mimeType;
        @SerializedName("data_base64") public String dataBase64;
    }

    public static class ExportResponse {
        public List<AlunnoDto> alunni = new ArrayList<>();
        public List<QuotaDto> quote = new ArrayList<>();
        public List<EntrataDto> entrate = new ArrayList<>();
        public List<SpesaDto> spese = new ArrayList<>();
    }
}
