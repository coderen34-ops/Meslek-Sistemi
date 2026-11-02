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

    private static final String ISARET = "\u0001KMSG\u0001";

    /**
     * TagPlugin satırı ön ek (tag/ek etiketler) + isim + mesaj olarak kurar; mesaj ön ekin son rengini/stilini
     * miras alabilir (polis tag'i koyu mavi, vs.). Satır, mesaj yerine bir işaretle üretilir; sonra her parça kendi
     * etkin stiliyle düz bir listeye açılır ve işaret yerine (beyaz, biçimsiz) gerçek mesaj konur. Böylece mesaj
     * hiçbir tag/etiket rengini miras alamaz. İşaret bulunamazsa null döner (eski yol kullanılır).
     */
    private static Component mesajiSerbestBirak(Component kok, Component mesaj) {
        java.util.List<Component> parcalar = new java.util.ArrayList<>();
        boolean[] bulundu = {false};
        acDuzlestir(kok, net.kyori.adventure.text.format.Style.empty(), parcalar, mesaj, bulundu);
        if (!bulundu[0]) return null;
        return Component.empty().children(parcalar);
    }

    private static void acDuzlestir(Component c, net.kyori.adventure.text.format.Style miras, java.util.List<Component> cikis,
                                    Component mesaj, boolean[] bulundu) {
        net.kyori.adventure.text.format.Style etkin = c.style().merge(miras, net.kyori.adventure.text.format.Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (c instanceof net.kyori.adventure.text.TextComponent tc && tc.content().contains(ISARET)) {
            String icerik = tc.content();
            int i = icerik.indexOf(ISARET);
            String once = icerik.substring(0, i), sonra = icerik.substring(i + ISARET.length());
            if (!once.isEmpty()) cikis.add(Component.text(once).style(etkin));
            cikis.add(mesaj);
            if (!sonra.isEmpty()) cikis.add(Component.text(sonra).style(etkin));
            bulundu[0] = true;
        } else {
            cikis.add(c.children(java.util.Collections.emptyList()).style(etkin));
        }
        for (Component ch : c.children()) acDuzlestir(ch, etkin, cikis, mesaj, bulundu);
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
            Component m = duzelt(mesaj);
            boolean dbg = new java.io.File(me.mesleksistemi.MeslekSistemi.getPlugin(me.mesleksistemi.MeslekSistemi.class).getDataFolder(), "sohbetdebug").exists();
            try {
                Component tam = eski.render(source, ad2, Component.text(ISARET), izleyen);
                Component sonuc = mesajiSerbestBirak(tam, m);
                if (dbg) {
                    var g = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
                    org.bukkit.Bukkit.getLogger().info("[SohbetDebug] renderer=" + eski.getClass().getName() + " marker=" + (sonuc != null)
                            + " tam=" + g.serialize(tam) + " sonuc=" + (sonuc == null ? "null" : g.serialize(sonuc)));
                }
                if (sonuc != null) return sonuc;
            } catch (Throwable t) {
                if (dbg) org.bukkit.Bukkit.getLogger().info("[SohbetDebug] hata " + t);
            }
            return eski.render(source, ad2, m, izleyen);
        });
    }
}
