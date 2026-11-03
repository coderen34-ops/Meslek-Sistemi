package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Belediye başkanı değiştiğinde ya da başkanlık düştüğünde (yeniUuid null olabilir). sebep: SECIM, DUSURME, ISTIFA.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class BaskanDegistiEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID eskiUuid;
    private final UUID yeniUuid;
    private final String sebep;

    public BaskanDegistiEvent(UUID eskiUuid, UUID yeniUuid, String sebep) {
        this.eskiUuid = eskiUuid;
        this.yeniUuid = yeniUuid;
        this.sebep = sebep;
    }

    public UUID getEskiUuid() { return eskiUuid; }
    public UUID getYeniUuid() { return yeniUuid; }
    public String getSebep() { return sebep; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
