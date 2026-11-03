package me.mesleksistemi.api;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import me.mesleksistemi.MeslekSistemi;
import me.mesleksistemi.adliye.DavaDosyasi;

public class MeslekAPIImpl implements MeslekAPI {

    private final MeslekSistemi plugin;

    // Kimlik anahtarları config.yml'e değil ayrı dosyaya yazılır (config.yml kapanışta yeniden yazılıyor).
    private static final int ANAHTAR_SINIRI = 1000;
    private final File anahtarDosyasi;
    private final Map<String, TransferSonucu> anahtarlar = new LinkedHashMap<>();

    public MeslekAPIImpl(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.anahtarDosyasi = new File(plugin.getDataFolder(), "mobil-kimlik.yml");
        anahtarlariYukle();
    }

    private void anahtarlariYukle() {
        if (!anahtarDosyasi.exists()) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(anahtarDosyasi);
            ConfigurationSection k = y.getConfigurationSection("anahtarlar");
            if (k == null) return;
            for (String ad : k.getKeys(false)) {
                try {
                    anahtarlar.put(ad, new TransferSonucu(TransferDurum.valueOf(k.getString(ad + ".durum")),
                            k.getDouble(ad + ".miktar"), k.getDouble(ad + ".bakiye"), false));
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("mobil-kimlik.yml okunamadı: " + e.getMessage());
        }
    }

    private void anahtarlariKaydet() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            int i = 0;
            for (Map.Entry<String, TransferSonucu> e : anahtarlar.entrySet()) {
                String yol = "anahtarlar." + e.getKey().replace(".", "_");
                y.set(yol + ".durum", e.getValue().durum().name());
                y.set(yol + ".miktar", e.getValue().miktar());
                y.set(yol + ".bakiye", e.getValue().gonderenBakiye());
                i++;
            }
            plugin.getDataFolder().mkdirs();
            y.save(anahtarDosyasi);
        } catch (Exception e) {
            plugin.getLogger().warning("mobil-kimlik.yml yazılamadı: " + e.getMessage());
        }
    }

    private static String anahtarTemizle(String a) { return a.trim().replace(".", "_"); }

    private static boolean gecerliMiktar(double miktar) {
        return Double.isFinite(miktar) && miktar > 0;
    }

    private static double kurus(double miktar) {
        return Math.round(miktar * 100.0) / 100.0;
    }

    @Override
    public double bankaBakiyesi(UUID oyuncu) {
        return plugin.bankaHesaplari.getOrDefault(oyuncu, 0.0);
    }

    @Override
    public boolean bankadanCek(UUID oyuncu, double miktar) {
        if (!gecerliMiktar(miktar)) return false;
        miktar = kurus(miktar);
        double bakiye = bankaBakiyesi(oyuncu);
        if (bakiye < miktar) return false;
        plugin.bankaHesaplari.put(oyuncu, kurus(bakiye - miktar));
        plugin.veriKaydet();
        return true;
    }

    @Override
    public boolean bankayaYatir(UUID oyuncu, double miktar) {
        if (!gecerliMiktar(miktar)) return false;
        plugin.bankaHesaplari.put(oyuncu, kurus(bankaBakiyesi(oyuncu) + kurus(miktar)));
        plugin.veriKaydet();
        return true;
    }

    @Override
    public boolean hapisteMi(UUID oyuncu) {
        return plugin.hapisteMi(oyuncu);
    }

    @Override
    public boolean durusmadaMi(UUID oyuncu) {
        return plugin.adliyeManager != null && plugin.adliyeManager.durusmadakiOyuncular.contains(oyuncu);
    }

    @Override
    public void saglikMuafiyeti(UUID oyuncu, boolean muaf) {
        if (plugin.saglikManager != null) plugin.saglikManager.muafiyetAyarla(oyuncu, muaf);
        // Savaş sırasında hapis cezası aldıysa muafiyet bitince hücreye alınır
        if (!muaf && plugin.polisManager != null) plugin.polisManager.savasBitti(oyuncu);
    }

    @Override
    public boolean agirYaraliMi(UUID oyuncu) {
        return plugin.saglikManager != null && plugin.saglikManager.agirYaraliMi(oyuncu);
    }

    @Override
    public boolean kasayaYatir(double miktar) {
        if (!gecerliMiktar(miktar)) return false;
        if (!plugin.kasayaParaEkle(kurus(miktar))) return false;
        plugin.veriKaydet();
        return true;
    }

    @Override
    public boolean bankayiHemenKaydet() {
        return plugin.hemenDiskeYaz();
    }

    @Override
    public void ekonomiLog(String kategori, String kim, String detay) {
        plugin.ekonomiLog(kategori, kim, detay);
    }

    // ================================================================ Koninski-Mobil genişlemesi (ana thread)

    @Override
    public TransferSonucu transfer(UUID gonderen, UUID alici, double miktar, String kimlikAnahtari) {
        double bak = plugin.bankaHesaplari.getOrDefault(gonderen, 0.0);
        if (gonderen == null || alici == null || kimlikAnahtari == null || kimlikAnahtari.isBlank())
            return new TransferSonucu(TransferDurum.GECERSIZ_ANAHTAR, miktar, bak, false);
        String anahtar = anahtarTemizle(kimlikAnahtari);
        TransferSonucu eski = anahtarlar.get(anahtar);
        if (eski != null) return new TransferSonucu(eski.durum(), eski.miktar(), eski.gonderenBakiye(), true);

        TransferSonucu sonuc;
        if (plugin.etkilesimManager == null) {
            sonuc = new TransferSonucu(TransferDurum.ALICI_BILINMIYOR, miktar, bak, false);
        } else if (!gonderen.equals(alici) && !plugin.etkilesimManager.oyuncuBilinir(alici)) {
            sonuc = new TransferSonucu(TransferDurum.ALICI_BILINMIYOR, miktar, bak, false);
        } else {
            sonuc = plugin.etkilesimManager.paraTasi(gonderen, alici, miktar);
            if (sonuc.basarili()) {
                if (plugin.alarmManager != null) {
                    plugin.alarmManager.transfer(gonderen, adi(gonderen), alici, adi(alici), sonuc.miktar(), "mobil-transfer");
                } else {
                    plugin.ekonomiLog("mobil-transfer", adi(gonderen), "-> " + adi(alici) + " $" + String.format(java.util.Locale.US, "%.2f", sonuc.miktar()));
                }
                plugin.hemenDiskeYaz();
            }
        }
        anahtarlar.put(anahtar, sonuc);
        while (anahtarlar.size() > ANAHTAR_SINIRI) {
            String ilk = anahtarlar.keySet().iterator().next();
            anahtarlar.remove(ilk);
        }
        anahtarlariKaydet();
        return sonuc;
    }

    private String adi(UUID id) {
        String ad = Bukkit.getOfflinePlayer(id).getName();
        return ad == null ? id.toString() : ad;
    }

    @Override
    public ProfilOzeti profilOzeti(UUID oyuncu) {
        return plugin.etkilesimManager == null ? null : plugin.etkilesimManager.profilOzetiUuid(oyuncu);
    }

    @Override
    public List<BasvuruBilgisi> basvurular(UUID oyuncu) {
        List<BasvuruBilgisi> r = new ArrayList<>();
        MeslekSistemi.Basvuru b = plugin.aktifBasvurular.get(oyuncu);
        if (b != null) r.add(new BasvuruBilgisi(oyuncu, b.oyuncuAdi, b.meslek, b.sebep));
        return r;
    }

    @Override
    public List<DavaOzeti> davalar(UUID oyuncu, DavaRolu rol) {
        List<DavaOzeti> r = new ArrayList<>();
        if (plugin.adliyeManager == null) return r;
        String ad = adi(oyuncu);
        for (DavaDosyasi d : plugin.adliyeManager.aktifDavalar.values()) {
            boolean m = ad.equalsIgnoreCase(d.musteki), s = ad.equalsIgnoreCase(d.sanik), h = ad.equalsIgnoreCase(d.hakim),
                    a = ad.equalsIgnoreCase(d.mustekiAvukati) || ad.equalsIgnoreCase(d.sanikAvukati);
            boolean uygun = switch (rol) {
                case MUSTEKI -> m;
                case SANIK -> s;
                case HAKIM -> h;
                case AVUKAT -> a;
                case HEPSI -> m || s || h || a;
            };
            if (uygun) r.add(new DavaOzeti(d.id, d.durum == null ? null : d.durum.name(), d.musteki, d.sanik, d.hakim,
                    d.mustekiAvukati, d.sanikAvukati, d.talepEdilenMiktar, d.sebep));
        }
        return r;
    }

    @Override
    public List<PazarIlani> pazarIlanlari() {
        return plugin.pazarManager == null ? new ArrayList<>() : plugin.pazarManager.ilanListesi();
    }

    @Override
    public GorevOzeti gorevOzeti(UUID oyuncu) {
        return plugin.gorevManager == null ? new GorevOzeti(oyuncu, java.util.Set.of(), Map.of()) : plugin.gorevManager.ozet(oyuncu);
    }

    @Override
    public BasarimOzeti basarimOzeti(UUID oyuncu) {
        if (plugin.basarimManager == null) return new BasarimOzeti(oyuncu, java.util.Set.of(), 0);
        return new BasarimOzeti(oyuncu, new java.util.HashSet<>(plugin.basarimManager.kazanilanlar(oyuncu)), plugin.basarimManager.toplamBasarim());
    }

    @Override
    public EtkilesimSonucu profilYorumEkle(UUID hedef, UUID yazan, String metin) {
        if (plugin.etkilesimManager == null || hedef == null || yazan == null || metin == null) return EtkilesimSonucu.HEDEF_BILINMIYOR;
        if (hedef.equals(yazan)) return EtkilesimSonucu.KENDINE;
        if (!plugin.etkilesimManager.oyuncuBilinir(hedef)) return EtkilesimSonucu.HEDEF_BILINMIYOR;
        return plugin.etkilesimManager.yorumKaydet(yazan, adi(yazan), hedef, metin).sonuc();
    }

    @Override
    public EtkilesimSonucu begeniVer(UUID hedef, UUID yazan, BegeniTipi tip) {
        if (plugin.etkilesimManager == null || hedef == null || yazan == null || tip == null) return EtkilesimSonucu.HEDEF_BILINMIYOR;
        if (hedef.equals(yazan)) return EtkilesimSonucu.KENDINE;
        if (!plugin.etkilesimManager.oyuncuBilinir(hedef)) return EtkilesimSonucu.HEDEF_BILINMIYOR;
        String neden = plugin.etkilesimManager.katilimNedeni(yazan);
        if (neden != null) return plugin.etkilesimManager.katilimSonucuDisari(neden);
        return plugin.etkilesimManager.oyVer(yazan, hedef, tip == BegeniTipi.BEGENI ? 1 : -1);
    }

    @Override
    public boolean saglikMuafMi(UUID oyuncu) {
        return plugin.saglikManager != null && plugin.saglikManager.muafMi(oyuncu);
    }
}
