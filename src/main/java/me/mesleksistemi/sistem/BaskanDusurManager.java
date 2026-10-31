package me.mesleksistemi.sistem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitRunnable;

import me.mesleksistemi.MeslekSistemi;

/**
 * Belediye başkanı baygın düşüp {@code baskan.bayginlik-sure-saniye} (varsayılan 300 = 5 dk) içinde kurtarılmazsa
 * başkanlık düşer ve oyuncu vatandaşa döner. Sayaç başkan çevrimdışıyken de işler (baygınlık kaydı kalıcıdır).
 * Başkan ölürse de başkanlık anında düşer. Başlangıç zamanı config'te saklanır, restart'ta kaybolmaz.
 */
public class BaskanDusurManager implements Listener {

    private final MeslekSistemi plugin;
    private final Map<UUID, Long> baslangic = new HashMap<>();

    public BaskanDusurManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("baskan-bayginlik");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try { baslangic.put(UUID.fromString(k), sec.getLong(k)); } catch (IllegalArgumentException ignored) { }
            }
        }
        new BukkitRunnable() {
            @Override public void run() { kontrol(); }
        }.runTaskTimer(plugin, 100L, 100L);
    }

    private long sureMs() {
        long sn = plugin.getConfig().getLong("baskan.bayginlik-sure-saniye", 300L);
        if (sn < 10) sn = 300;
        return sn * 1000L;
    }

    private boolean baskanMi(UUID id) {
        String m = plugin.oyuncuMeslekCache.get(id);
        return m != null && m.equalsIgnoreCase("belediyebaskani");
    }

    private void kaydet() {
        plugin.getConfig().set("baskan-bayginlik", null);
        for (Map.Entry<UUID, Long> e : baslangic.entrySet()) plugin.getConfig().set("baskan-bayginlik." + e.getKey(), e.getValue());
        plugin.saveConfig();
    }

    private void kontrol() {
        long simdi = System.currentTimeMillis();
        boolean degisti = false;
        List<UUID> dusecek = new ArrayList<>();

        // Başkanlığı kalmamış kayıtları temizle
        for (UUID id : new ArrayList<>(baslangic.keySet())) {
            if (!baskanMi(id)) { baslangic.remove(id); degisti = true; }
        }

        for (Map.Entry<UUID, String> e : plugin.oyuncuMeslekCache.entrySet()) {
            if (!e.getValue().equalsIgnoreCase("belediyebaskani")) continue;
            UUID id = e.getKey();
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                boolean baygin = plugin.saglikManager != null && plugin.saglikManager.agirYaraliMi(id);
                if (baygin && !baslangic.containsKey(id)) {
                    baslangic.put(id, simdi); degisti = true;
                    p.sendMessage(ChatColor.RED + "Başkan baygın! " + (sureMs() / 60000.0 >= 1 ? (long) (sureMs() / 60000L) + " dakika" : (sureMs() / 1000) + " saniye")
                            + " içinde kurtarılmazsan başkanlığın düşecek!");
                    Bukkit.broadcastMessage(ChatColor.RED + "⚠ Belediye Başkanı " + p.getName() + " baygın düştü! Kurtarılmazsa başkanlık düşecek.");
                } else if (!baygin && baslangic.containsKey(id)) {
                    baslangic.remove(id); degisti = true;
                    p.sendMessage(ChatColor.GREEN + "Kurtarıldın, başkanlığın güvende.");
                }
            }
            // Çevrimdışıysa baygınlık kaydı olduğu gibi kalır; sayaç işlemeye devam eder.
            Long b = baslangic.get(id);
            if (b != null && simdi - b >= sureMs()) dusecek.add(id);
        }

        for (UUID id : dusecek) dusur(id, "baygın kalıp kurtarılamadı");
        if (degisti && dusecek.isEmpty()) kaydet();
    }

    @EventHandler
    public void onOlum(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (baskanMi(id)) dusur(id, "öldü");
    }

    private void dusur(UUID id, String sebep) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String isim = op.getName();
        baslangic.remove(id);
        if (isim != null) {
            if (op.isOnline()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "meslekata " + isim + " vatandas");
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + isim + " parent set vatandas");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + isim + " vatandas");
            }
        }
        plugin.oyuncuMeslekCache.put(id, "vatandas");
        Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.RED + (isim == null ? "Başkan" : isim)
                + ChatColor.YELLOW + " " + sebep + "; Belediye Başkanlığı düştü ve vatandaşa döndü.");
        plugin.ekonomiLog("baskan-dustu", isim == null ? id.toString() : isim, sebep);
        if (plugin.haberManager != null)
            plugin.haberManager.yaz("SİYASET", "Başkanlık düştü", (isim == null ? "Başkan" : isim) + " " + sebep + "; Belediye Başkanlığı düştü.");
        kaydet();
        plugin.veriKaydet();
    }
}
