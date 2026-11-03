package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Meslek değiştiğinde. sebep: BASVURU_ONAY, ISTIFA, DUSURME, YONETICI, SECIM, HAPIS, TAHLIYE, POLIS_ATAMA, GOCMEN.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class MeslekDegistiEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID uuid;
    private final String eskiMeslek;
    private final String yeniMeslek;
    private final String sebep;

    public MeslekDegistiEvent(UUID uuid, String eskiMeslek, String yeniMeslek, String sebep) {
        this.uuid = uuid;
        this.eskiMeslek = eskiMeslek;
        this.yeniMeslek = yeniMeslek;
        this.sebep = sebep;
    }

    public UUID getUuid() { return uuid; }
    public String getEskiMeslek() { return eskiMeslek; }
    public String getYeniMeslek() { return yeniMeslek; }
    public String getSebep() { return sebep; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
