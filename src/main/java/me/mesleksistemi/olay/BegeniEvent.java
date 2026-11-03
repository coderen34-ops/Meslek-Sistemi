package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Beğeni/beğenmeme kaydedildiğinde. tip: BEGENI, BEGENMEME veya GERI_ALINDI (aynı oy ikinci kez verilince geri alınır).
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class BegeniEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID hedef;
    private final UUID yazan;
    private final String tip;

    public BegeniEvent(UUID hedef, UUID yazan, String tip) {
        this.hedef = hedef;
        this.yazan = yazan;
        this.tip = tip;
    }

    public UUID getHedef() { return hedef; }
    public UUID getYazan() { return yazan; }
    public String getTip() { return tip; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
