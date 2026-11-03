package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Meslek başvurusu sonuçlandığında. sonuc: ONAY, RET, IPTAL.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class BasvuruSonucEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID uuid;
    private final String meslek;
    private final String sonuc;

    public BasvuruSonucEvent(UUID uuid, String meslek, String sonuc) {
        this.uuid = uuid;
        this.meslek = meslek;
        this.sonuc = sonuc;
    }

    public UUID getUuid() { return uuid; }
    public String getMeslek() { return meslek; }
    public String getSonuc() { return sonuc; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
