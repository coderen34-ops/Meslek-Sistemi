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

    // ================================================================
    // Koninski-Mobil köprüsü için eklenenler. TÜM çağrılar ana thread'den yapılmalıdır.
    // Eski metotlar değişmemiştir.
    // ================================================================

    /** API sürümü; yeni metot eklendikçe artar. 1 = yalnızca ilk altı metot, 2 = bu genişleme. */
    default int apiSurum() { return 2; }

    /**
     * Oyuncudan oyuncuya banka transferi. Kurallar menüdeki transferle birebir aynıdır:
     * vergi yok, alt limit 1.0, üst limit ve bekleme yok, hapisteki (mahkum) oyuncu gönderemez, kendine gönderilemez.
     * Tek adımda çalışır, alıcı çevrimdışı olabilir (UUID yeter).
     * kimlikAnahtari: aynı anahtarla ikinci çağrı aynı sonucu döner ve para ikinci kez hareket etmez
     * (son 1000 anahtar plugins/MeslekSistemi/mobil-kimlik.yml içinde kalıcıdır). Başarılı transferden sonra
     * veri hemen diske yazılır; ekonomi log kategorisi "mobil-transfer".
     */
    TransferSonucu transfer(UUID gonderen, UUID alici, double miktar, String kimlikAnahtari);

    /** Oyuncunun profil özeti (çevrimdışı olabilir). Hiç görülmemiş UUID için null. */
    ProfilOzeti profilOzeti(UUID oyuncu);

    /** Oyuncunun bekleyen meslek başvuruları (en fazla bir tane). */
    java.util.List<BasvuruBilgisi> basvurular(UUID oyuncu);

    /** Oyuncunun taraf olduğu aktif davalar. Taraflar adla eşleştirilir. */
    java.util.List<DavaOzeti> davalar(UUID oyuncu, DavaRolu rol);

    /** Tüm pazar standlarındaki ilanlar (stoğu olanlar). */
    java.util.List<PazarIlani> pazarIlanlari();

    GorevOzeti gorevOzeti(UUID oyuncu);

    BasarimOzeti basarimOzeti(UUID oyuncu);

    /** Profil yorumu ekler; menüdeki kurallar (göçmen/mahkum yok, oynama süresi, uzunluk, aynı kişiye bekleme). Küfür filtresi YOKTUR (oyunda da yok). */
    EtkilesimSonucu profilYorumEkle(UUID hedef, UUID yazan, String metin);

    /** Beğeni/beğenmeme verir; aynı türden tekrar verilirse oy geri alınır (GERI_ALINDI). */
    EtkilesimSonucu begeniVer(UUID hedef, UUID yazan, BegeniTipi tip);
}
