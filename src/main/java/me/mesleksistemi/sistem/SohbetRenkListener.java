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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSohbet(AsyncChatEvent e) {
        ChatRenderer eski = e.renderer();
        e.renderer((source, ad, mesaj, izleyen) -> {
            Component duz = duzelt(mesaj);
            return eski.render(source, ad, duz, izleyen);
        });
    }
}
