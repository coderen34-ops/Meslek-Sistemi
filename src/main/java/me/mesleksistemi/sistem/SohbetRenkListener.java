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

    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer DUZ =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();

    /** Bileşen ağacını, her parçası kendi etkin stiliyle (üst stiller birleştirilmiş) düz bir listeye açar. */
    private static void duzles(Component c, net.kyori.adventure.text.format.Style miras, java.util.List<Component> cikis) {
        net.kyori.adventure.text.format.Style etkin = c.style().merge(miras, net.kyori.adventure.text.format.Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        Component yaprak = c.children(java.util.Collections.emptyList()).style(etkin);
        if (!(yaprak instanceof net.kyori.adventure.text.TextComponent tc) || !tc.content().isEmpty()) cikis.add(yaprak);
        for (Component ch : c.children()) duzles(ch, etkin, cikis);
    }

    /** Düz metin aralığı [a,b) için parçaları (gerekirse bölerek) döndürür. */
    private static java.util.List<Component> dilim(java.util.List<Component> parcalar, int a, int b) {
        java.util.List<Component> r = new java.util.ArrayList<>();
        int pos = 0;
        for (Component p : parcalar) {
            String t = DUZ.serialize(p);
            int bas = pos, son = pos + t.length();
            pos = son;
            if (son <= a || bas >= b) continue;
            if (bas >= a && son <= b) { r.add(p); continue; }
            if (p instanceof net.kyori.adventure.text.TextComponent tc) {
                int x = Math.max(a, bas) - bas, y = Math.min(b, son) - bas;
                r.add(Component.text(tc.content().substring(x, y)).style(tc.style()));
            } else if (bas >= a) {
                r.add(p);
            }
        }
        return r;
    }

    /**
     * TagPlugin satırı mesaj/isim argümanlarını yok sayıp kendi yakaladığı metinle kurar; mesaj etiketin
     * rengini miras alır. Satır düzleştirilir, sondaki mesaj metni bulunur ve (beyaz, biçimsiz) gerçek mesajla
     * değiştirilir; isimden önce/sonra VIP etiketi ve kalp eklenir. Bulunamazsa null döner.
     */
    private static Component satiriYenidenKur(Component tam, String isim, String mesajDuz, Component m, Component onEk, Component sonEk) {
        if (mesajDuz == null || mesajDuz.isEmpty()) return null;
        java.util.List<Component> parcalar = new java.util.ArrayList<>();
        duzles(tam, net.kyori.adventure.text.format.Style.empty(), parcalar);
        StringBuilder sb = new StringBuilder();
        for (Component p : parcalar) sb.append(DUZ.serialize(p));
        String tum = sb.toString();
        if (!tum.endsWith(mesajDuz)) return null;
        int mBas = tum.length() - mesajDuz.length();
        java.util.List<Component> cikis = new java.util.ArrayList<>();
        String ek = isim + ": ";
        if (isim != null && tum.substring(0, mBas).endsWith(ek)) {
            int adBitis = mBas - 2, adBas = adBitis - isim.length();
            cikis.addAll(dilim(parcalar, 0, adBas));
            if (onEk != null) cikis.add(onEk);
            cikis.addAll(dilim(parcalar, adBas, adBitis));
            if (sonEk != null) cikis.add(sonEk);
            cikis.addAll(dilim(parcalar, adBitis, mBas));
        } else {
            cikis.addAll(dilim(parcalar, 0, mBas));
        }
        cikis.add(m);
        return Component.empty().children(cikis);
    }

    /** TagPlugin (NORMAL) mesajı kendi yakalamadan önce stili sabitle. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSohbetErken(AsyncChatEvent e) {
        e.message(duzelt(e.message()));
    }

    /** TagPlugin renderer'ını kurduktan sonra satırı yeniden kur. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSohbet(AsyncChatEvent e) {
        ChatRenderer eski = e.renderer();
        e.renderer((source, ad, mesaj, izleyen) -> {
            Component m = duzelt(mesaj);
            Component onEk = null, sonEk = null;
            try {
                me.mesleksistemi.MeslekSistemi pl = me.mesleksistemi.MeslekSistemi.getPlugin(me.mesleksistemi.MeslekSistemi.class);
                String es = pl.evlilikManager == null ? null : pl.evlilikManager.esAdi(source.getUniqueId());
                if (es != null) {
                    sonEk = Component.text(" \u2665", NamedTextColor.RED)
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(es + " ile evli", NamedTextColor.GRAY)));
                }
                if (pl.vipManager != null) onEk = pl.vipManager.etiket(source.getUniqueId());
            } catch (Throwable ignored) { }
            Component tam;
            try {
                tam = eski.render(source, ad, m, izleyen);
            } catch (Throwable t) {
                return eski.render(source, ad, m, izleyen);
            }
            try {
                Component sonuc = satiriYenidenKur(tam, source.getName(), DUZ.serialize(mesaj), m, onEk, sonEk);
                if (sonuc != null) return sonuc;
            } catch (Throwable ignored) { }
            return tam;
        });
    }
}
