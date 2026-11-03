package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Pazar standından satın alma tamamlandığında. tutar = alıcıdan düşülen toplam.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class PazarSatisEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID satici;
    private final UUID alici;
    private final String listingId;
    private final int adet;
    private final double tutar;

    public PazarSatisEvent(UUID satici, UUID alici, String listingId, int adet, double tutar) {
        this.satici = satici;
        this.alici = alici;
        this.listingId = listingId;
        this.adet = adet;
        this.tutar = tutar;
    }

    public UUID getSatici() { return satici; }
    public UUID getAlici() { return alici; }
    public String getListingId() { return listingId; }
    public int getAdet() { return adet; }
    public double getTutar() { return tutar; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
