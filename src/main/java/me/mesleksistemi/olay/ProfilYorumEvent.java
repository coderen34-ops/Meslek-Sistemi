package me.mesleksistemi.olay;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Profil yorumu kaydedildiğinde.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class ProfilYorumEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID hedef;
    private final UUID yazan;
    private final String metin;

    public ProfilYorumEvent(UUID hedef, UUID yazan, String metin) {
        this.hedef = hedef;
        this.yazan = yazan;
        this.metin = metin;
    }

    public UUID getHedef() { return hedef; }
    public UUID getYazan() { return yazan; }
    public String getMetin() { return metin; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
