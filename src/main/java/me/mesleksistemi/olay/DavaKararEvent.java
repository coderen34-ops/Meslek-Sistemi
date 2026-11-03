package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Hakim karar verip dava kapandığında. sonuc: SUCLU veya BERAAT (tazminat BERAAT'ta 0).
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class DavaKararEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID davaId;
    private final String sonuc;
    private final double tazminat;

    public DavaKararEvent(UUID davaId, String sonuc, double tazminat) {
        this.davaId = davaId;
        this.sonuc = sonuc;
        this.tazminat = tazminat;
    }

    public UUID getDavaId() { return davaId; }
    public String getSonuc() { return sonuc; }
    public double getTazminat() { return tazminat; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
