package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Görev ödülü verilip görev tamamlandığında.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class GorevTamamlandiEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID uuid;
    private final String gorevId;

    public GorevTamamlandiEvent(UUID uuid, String gorevId) {
        this.uuid = uuid;
        this.gorevId = gorevId;
    }

    public UUID getUuid() { return uuid; }
    public String getGorevId() { return gorevId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
