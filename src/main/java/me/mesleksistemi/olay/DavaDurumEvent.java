package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Dava durumu değiştiğinde ya da dava oluşturulduğunda (eskiDurum o zaman null). Sunucu açılışında dosyadan yükleme olay değildir.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class DavaDurumEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID davaId;
    private final String eskiDurum;
    private final String yeniDurum;
    private final String musteki;
    private final String sanik;
    private final String hakim;

    public DavaDurumEvent(UUID davaId, String eskiDurum, String yeniDurum, String musteki, String sanik, String hakim) {
        this.davaId = davaId;
        this.eskiDurum = eskiDurum;
        this.yeniDurum = yeniDurum;
        this.musteki = musteki;
        this.sanik = sanik;
        this.hakim = hakim;
    }

    public UUID getDavaId() { return davaId; }
    public String getEskiDurum() { return eskiDurum; }
    public String getYeniDurum() { return yeniDurum; }
    public String getMusteki() { return musteki; }
    public String getSanik() { return sanik; }
    public String getHakim() { return hakim; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
