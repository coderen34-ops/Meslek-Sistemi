package me.mesleksistemi.api;

import java.util.UUID;

/**
 * Diğer eklentilerin (örn. Klan-Sistemi) MeslekSistemi ile güvenli bağlantı noktası.
 * Bukkit servis sistemine kayıtlıdır:
 *   MeslekAPI api = Bukkit.getServicesManager().load(MeslekAPI.class);
 * Tüm metotlar ana thread'den çağrılmalıdır.
 */
public interface MeslekAPI {

    /** Oyuncunun dijital banka bakiyesi. */
    double bankaBakiyesi(UUID oyuncu);

    /** Bankadan para çeker. Miktar geçersizse veya bakiye yetmiyorsa hiçbir şey yapmaz, false döner. */
    boolean bankadanCek(UUID oyuncu, double miktar);

    /** Bankaya para yatırır. Miktar geçersizse (NaN, sonsuz, sıfır/negatif) false döner. */
    boolean bankayaYatir(UUID oyuncu, double miktar);

    boolean hapisteMi(UUID oyuncu);

    /** Oyuncu şu an bir mahkeme duruşmasında (salona kilitli) mi? */
    boolean durusmadaMi(UUID oyuncu);

    /**
     * Sağlık sisteminden muafiyet (kanama, kırık, ağır yaralı durumu uygulanmaz; oyuncu normal ölür).
     * Örn. arena savaşındaki oyuncular için. Sunucu yeniden başlayınca sıfırlanır.
     * Muaf oyuncu polis copuyla hapse atılmaz, duruşmaya çekilmez; aldığı hapis cezası muafiyet kalkınca başlar.
     */
    void saglikMuafiyeti(UUID oyuncu, boolean muaf);

    boolean saglikMuafMi(UUID oyuncu);

    /** Oyuncu şu an ağır yaralı (baygın, doktor bekliyor) mı? Ölümcül vuruş alan oyuncu ölmez, bu duruma düşer. */
    boolean agirYaraliMi(UUID oyuncu);

    /**
     * Belediye kasasına (sandık) para koyar. Miktar geçersizse (NaN, sonsuz, sıfır/negatif),
     * kasa kurulu değilse ya da doluysa hiçbir şey yapmaz, false döner (para kaybolmasın diye çağıran iade etmeli).
     */
    boolean kasayaYatir(double miktar);

    /**
     * Banka dahil tüm MeslekSistemi verisini şimdi senkron olarak diske yazar (normalde ~1 sn gecikmeyle yazılır).
     * Para işleminden hemen sonra kendi kaydını yazmadan önce çağırın ki çökmede para kaybolmasın/çoğalmasın.
     * Ana thread'den çağrılmalı; her işlemde değil, kritik anlarda kullanın. Başarılıysa true.
     */
    boolean bankayiHemenKaydet();

    /** MeslekSistemi'nin ekonomi log'una satır yazar (plugins/MeslekSistemi/loglar/ekonomi-YYYY-AA.log). */
    void ekonomiLog(String kategori, String kim, String detay);
}
