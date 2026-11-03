package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Nikah kıyıldığında.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class EvlilikEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID es1;
    private final UUID es2;
    private final String rahip;

    public EvlilikEvent(UUID es1, UUID es2, String rahip) {
        this.es1 = es1;
        this.es2 = es2;
        this.rahip = rahip;
    }

    public UUID getEs1() { return es1; }
    public UUID getEs2() { return es2; }
    public String getRahip() { return rahip; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
