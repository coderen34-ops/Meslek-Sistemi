package me.mesleksistemi.sistem;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Sohbet mesajının metni, isim/tag kısmının rengini ve biçimini devralmasın (Adventure alt bileşenleri
 * üst bileşenin stilini miras alır). Mesaja açıkça beyaz renk ve "biçimsiz" stil verilir; oyuncunun
 * kendi yazdığı renk/biçimler (iç bileşenlerde) olduğu gibi kalır.
 */
public class SohbetRenkListener implements Listener {

    private static Component duzelt(Component mesaj) {
        return mesaj.colorIfAbsent(NamedTextColor.WHITE)
                .decorationIfAbsent(TextDecoration.BOLD, TextDecoration.State.FALSE)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                .decorationIfAbsent(TextDecoration.UNDERLINED, TextDecoration.State.FALSE)
                .decorationIfAbsent(TextDecoration.STRIKETHROUGH, TextDecoration.State.FALSE)
                .decorationIfAbsent(TextDecoration.OBFUSCATED, TextDecoration.State.FALSE);
    }

    /** TagPlugin (NORMAL) mesajı kendi yakalamadan önce stili sabitle. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSohbetErken(AsyncChatEvent e) {
        e.message(duzelt(e.message()));
    }

    /** TagPlugin renderer'ını kurduktan sonra mesaj argümanını da sabitle. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSohbet(AsyncChatEvent e) {
        ChatRenderer eski = e.renderer();
        e.renderer((source, ad, mesaj, izleyen) -> {
            Component ad2 = ad;
            try {
                me.mesleksistemi.MeslekSistemi pl = me.mesleksistemi.MeslekSistemi.getPlugin(me.mesleksistemi.MeslekSistemi.class);
                String es = pl.evlilikManager == null ? null : pl.evlilikManager.esAdi(source.getUniqueId());
                if (es != null) {
                    ad2 = ad.append(Component.text(" \u2665", NamedTextColor.RED)
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(es + " ile evli", NamedTextColor.GRAY))));
                }
            } catch (Throwable ignored) { }
            return eski.render(source, ad2, duzelt(mesaj), izleyen);
        });
    }
}
