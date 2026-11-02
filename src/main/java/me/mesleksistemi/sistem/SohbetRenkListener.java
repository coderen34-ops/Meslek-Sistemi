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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSohbet(AsyncChatEvent e) {
        ChatRenderer eski = e.renderer();
        e.renderer((source, ad, mesaj, izleyen) -> {
            Component duz = mesaj.colorIfAbsent(NamedTextColor.WHITE)
                    .decorationIfAbsent(TextDecoration.BOLD, TextDecoration.State.FALSE)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                    .decorationIfAbsent(TextDecoration.UNDERLINED, TextDecoration.State.FALSE)
                    .decorationIfAbsent(TextDecoration.STRIKETHROUGH, TextDecoration.State.FALSE)
                    .decorationIfAbsent(TextDecoration.OBFUSCATED, TextDecoration.State.FALSE);
            return eski.render(source, ad, duz, izleyen);
        });
    }
}
