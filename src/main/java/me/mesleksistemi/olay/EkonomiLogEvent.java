package me.mesleksistemi.olay;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * MeslekSistemi.ekonomiLog() her çağrıldığında. Yeniden giriş korumalıdır: dinleyici içinden ekonomiLog çağrılırsa ikinci olay yayınlanmaz.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class EkonomiLogEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String kategori;
    private final String kim;
    private final String detay;

    public EkonomiLogEvent(String kategori, String kim, String detay) {
        this.kategori = kategori;
        this.kim = kim;
        this.detay = detay;
    }

    public String getKategori() { return kategori; }
    public String getKim() { return kim; }
    public String getDetay() { return detay; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
