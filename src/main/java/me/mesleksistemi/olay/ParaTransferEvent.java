package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Oyuncudan oyuncuya banka transferi tamamlandığında (menü ve API ortak çekirdeği). Vergi şu an 0.0'dır (oyunda vergi yok).
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class ParaTransferEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID gonderen;
    private final UUID alici;
    private final double miktar;
    private final double vergi;

    public ParaTransferEvent(UUID gonderen, UUID alici, double miktar, double vergi) {
        this.gonderen = gonderen;
        this.alici = alici;
        this.miktar = miktar;
        this.vergi = vergi;
    }

    public UUID getGonderen() { return gonderen; }
    public UUID getAlici() { return alici; }
    public double getMiktar() { return miktar; }
    public double getVergi() { return vergi; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
