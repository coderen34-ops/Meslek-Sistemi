package me.mesleksistemi.api;

import java.util.UUID;

import me.mesleksistemi.MeslekSistemi;

public class MeslekAPIImpl implements MeslekAPI {

    private final MeslekSistemi plugin;

    public MeslekAPIImpl(MeslekSistemi plugin) {
        this.plugin = plugin;
    }

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
    public void ekonomiLog(String kategori, String kim, String detay) {
        plugin.ekonomiLog(kategori, kim, detay);
    }

    @Override
    public boolean saglikMuafMi(UUID oyuncu) {
        return plugin.saglikManager != null && plugin.saglikManager.muafMi(oyuncu);
    }
}
