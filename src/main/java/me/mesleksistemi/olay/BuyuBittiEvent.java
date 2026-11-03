package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Süreli büyü süresi bittiğinde.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class BuyuBittiEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID uuid;
    private final String buyuAdi;

    public BuyuBittiEvent(UUID uuid, String buyuAdi) {
        this.uuid = uuid;
        this.buyuAdi = buyuAdi;
    }

    public UUID getUuid() { return uuid; }
    public String getBuyuAdi() { return buyuAdi; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
