package me.mesleksistemi.ekonomi;

import org.bukkit.Location;
import java.util.UUID;

public class KiralikEv {
    public String id;
    public String isim;
    public Location loc;
    // Dünya yüklenmemiş olsa bile kayıtta kaybolmasın diye adı ayrıca tutulur
    public String dunya;
    public double fiyat;
    // Evi kiraya veren oyuncu. null ise ev belediyenindir ve kira belediye kasasına gider.
    public UUID sahip;
    public UUID kiraci;
    // Ödenen kira döneminden kalan süre (ms). Sadece kiracı oyundayken azalır.
    public long kalanSureMs;
    // Sahibi /kirasil dedi ama ev kirada: mevcut süre bitince kira yenilenmez, ilan silinir
    public boolean kaldirilacak;

    public KiralikEv(String id, String isim, Location loc, double fiyat) {
        this.id = id;
        this.isim = isim;
        this.loc = loc;
        this.dunya = loc.getWorld() != null ? loc.getWorld().getName() : null;
        this.fiyat = fiyat;
        this.kiraci = null;
        this.kalanSureMs = 0L;
    }
}
