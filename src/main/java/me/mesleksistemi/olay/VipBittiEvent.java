package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * VIP süresi dolduğunda (kademe = bitmiş paket adı).
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class VipBittiEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID uuid;
    private final String kademe;

    public VipBittiEvent(UUID uuid, String kademe) {
        this.uuid = uuid;
        this.kademe = kademe;
    }

    public UUID getUuid() { return uuid; }
    public String getKademe() { return kademe; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
