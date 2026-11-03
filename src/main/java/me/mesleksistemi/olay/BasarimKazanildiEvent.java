package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Başarım ilk kez kazanıldığında.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class BasarimKazanildiEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID uuid;
    private final String basarimId;

    public BasarimKazanildiEvent(UUID uuid, String basarimId) {
        this.uuid = uuid;
        this.basarimId = basarimId;
    }

    public UUID getUuid() { return uuid; }
    public String getBasarimId() { return basarimId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
