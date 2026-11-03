package me.mesleksistemi.olay;

import java.lang.reflect.Method;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hata ayıklama: her olayı konsola BİR satır yazar ("her olay tam bir kez tetikleniyor" doğrulaması için).
 * Varsayılan KAPALI. plugins/MeslekSistemi/mobil.yml içine "olay-log: true" yazılıp sunucu yeniden başlatılırsa açılır.
 */
public class OlayGunlukcusu implements Listener {
    private final JavaPlugin plugin;

    public OlayGunlukcusu(JavaPlugin plugin) { this.plugin = plugin; }

    private void yaz(Event e) {
        StringBuilder sb = new StringBuilder("[OLAY] ").append(e.getEventName());
        for (Method m : e.getClass().getDeclaredMethods()) {
            String ad = m.getName();
            if (!ad.startsWith("get") || m.getParameterCount() != 0 || ad.equals("getHandlers")) continue;
            try { sb.append(' ').append(ad.substring(3)).append('=').append(m.invoke(e)); } catch (Exception ignored) { }
        }
        plugin.getLogger().info(sb.toString());
    }

    @EventHandler public void o0(me.mesleksistemi.olay.EkonomiLogEvent e) { yaz(e); }
    @EventHandler public void o1(me.mesleksistemi.olay.ParaTransferEvent e) { yaz(e); }
    @EventHandler public void o2(me.mesleksistemi.olay.PazarSatisEvent e) { yaz(e); }
    @EventHandler public void o3(me.mesleksistemi.olay.DavaDurumEvent e) { yaz(e); }
    @EventHandler public void o4(me.mesleksistemi.olay.DavaKararEvent e) { yaz(e); }
    @EventHandler public void o5(me.mesleksistemi.olay.MeslekDegistiEvent e) { yaz(e); }
    @EventHandler public void o6(me.mesleksistemi.olay.BasvuruSonucEvent e) { yaz(e); }
    @EventHandler public void o7(me.mesleksistemi.olay.BaskanDegistiEvent e) { yaz(e); }
    @EventHandler public void o8(me.mesleksistemi.olay.ProfilYorumEvent e) { yaz(e); }
    @EventHandler public void o9(me.mesleksistemi.olay.BegeniEvent e) { yaz(e); }
    @EventHandler public void o10(me.mesleksistemi.olay.GorevTamamlandiEvent e) { yaz(e); }
    @EventHandler public void o11(me.mesleksistemi.olay.BasarimKazanildiEvent e) { yaz(e); }
    @EventHandler public void o12(me.mesleksistemi.olay.EvlilikEvent e) { yaz(e); }
    @EventHandler public void o13(me.mesleksistemi.olay.VipBittiEvent e) { yaz(e); }
    @EventHandler public void o14(me.mesleksistemi.olay.BuyuBittiEvent e) { yaz(e); }
}
