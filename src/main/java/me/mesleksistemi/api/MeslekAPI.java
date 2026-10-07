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
}
