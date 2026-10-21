package me.mesleksistemi.basarim;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Başarım (achievement) sistemi.
 *
 * Entry #4 tasarımı: 100+ başarımlık geniş bir havuz, kademeli (Bronz/Gümüş/Altın/Efsane)
 * eşikler, her sisteme yayılan kategoriler. Bu ilk sürüm (Faz 1) temel altyapıyı ve
 * Ekonomi kategorisindeki ilk kademeli seti kurar; diğer kategoriler (Savaş/Klan/Keşif/
 * Üretim/Zindan/Görev) ileride buraya BasarimTanim eklenerek büyütülecek - altyapı
 * (persist, idempotent ver(), bildirim) baştan genel/soyut tasarlandı.
 *
 * Veri güvenliği: PazarManager'daki standVeriYuklendi bug-fix kalıbı burada da uygulanıyor -
 * veriYukle() gerçekten tamamlanmadan veriKaydet() diske YAZMAZ (bkz. MeslekSistemi.java'daki
 * ana veriYuklendi alanı, aynı mantık).
 */
public class BasarimManager implements Listener, CommandExecutor {

    public static class BasarimTanim {
        public final String id;
        public final String ad;
        public final String aciklama;
        public final String kategori;
        public final String tier;
        public final Double ekonomiEsigi; // null ise otomatik/ekonomi-tabanlı kontrol edilmez, sadece ver() ile manuel verilir

        public BasarimTanim(String id, String ad, String aciklama, String kategori, String tier, Double ekonomiEsigi) {
            this.id = id;
            this.ad = ad;
            this.aciklama = aciklama;
            this.kategori = kategori;
            this.tier = tier;
            this.ekonomiEsigi = ekonomiEsigi;
        }
    }

    // İlk kategori seti: Ekonomi (bakiye kademeleri). Diğer kategoriler ileride eklenecek.
    public static final List<BasarimTanim> TUM_BASARIMLAR = List.of(
            new BasarimTanim("ekonomi_bronz", "İlk Adım", "Banka bakiyen $1.000'a ulaştı.", "Ekonomi", "Bronz", 1000.0),
            new BasarimTanim("ekonomi_gumus", "Yükseliş", "Banka bakiyen $10.000'a ulaştı.", "Ekonomi", "Gümüş", 10000.0),
            new BasarimTanim("ekonomi_altin", "Zengin", "Banka bakiyen $100.000'a ulaştı.", "Ekonomi", "Altın", 100000.0),
            new BasarimTanim("ekonomi_efsane", "Milyoner", "Banka bakiyen $1.000.000'a ulaştı.", "Ekonomi", "Efsane", 1000000.0)
    );

    private static final Map<String, BasarimTanim> TANIM_INDEX = new HashMap<>();
    static {
        for (BasarimTanim t : TUM_BASARIMLAR) TANIM_INDEX.put(t.id, t);
    }

    private final MeslekSistemi plugin;
    private boolean veriYuklendi = false;
    private final Map<UUID, Set<String>> kazanilan = new HashMap<>();

    public BasarimManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        veriYukle();
        // Ekonomi başarımları için ayrı event hook'u yok (bakiye birçok yerden doğrudan
        // bankaHesaplari.put ile değişiyor) - bunun yerine online oyuncular periyodik
        // kontrol edilir. Düşük risk: mevcut para hareketi kodlarına dokunulmuyor.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tumOnlineOyunculariKontrolEt, 200L, 6000L); // ~5 dakikada bir
    }

    public void tumOnlineOyunculariKontrolEt() {
        if (!veriYuklendi) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ekonomiKontrolEt(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> ekonomiKontrolEt(e.getPlayer()), 40L);
    }

    public synchronized void ekonomiKontrolEt(Player p) {
        if (!veriYuklendi) return;
        double bakiye = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
        for (BasarimTanim t : TUM_BASARIMLAR) {
            if (t.ekonomiEsigi != null && bakiye >= t.ekonomiEsigi) {
                ver(p, t.id);
            }
        }
    }

    /** Başarımı verir. Zaten kazanılmışsa idempotent (tekrar vermez, false döner). */
    public synchronized boolean ver(Player p, String basarimId) {
        if (!veriYuklendi) return false;
        Set<String> set = kazanilan.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (set.contains(basarimId)) return false; // idempotent - dupe/çift bildirim önleme
        set.add(basarimId);
        veriKaydet();

        BasarimTanim t = TANIM_INDEX.get(basarimId);
        String ad = t != null ? t.ad : basarimId;
        String tier = t != null ? t.tier : "";
        String aciklama = t != null ? t.aciklama : "";

        p.sendMessage("");
        p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "★ BAŞARIM KAZANILDI: " + ChatColor.YELLOW + ad + ChatColor.GOLD + " [" + tier + "]");
        if (!aciklama.isEmpty()) p.sendMessage(ChatColor.GRAY + aciklama);
        p.sendMessage("");
        try {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
        plugin.ekonomiLog("basarim-kazanildi", p.getName(), "Başarım kazanıldı: " + basarimId + " (" + ad + ", " + tier + ")");
        return true;
    }

    public Set<String> kazanilanlar(UUID uuid) {
        return kazanilan.getOrDefault(uuid, new HashSet<>());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("basarimlarim")) {
            Set<String> kazanilanlar = kazanilanlar(player.getUniqueId());
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== Başarımların (" + kazanilanlar.size() + "/" + TUM_BASARIMLAR.size() + ") ===");
            for (BasarimTanim t : TUM_BASARIMLAR) {
                boolean var = kazanilanlar.contains(t.id);
                ChatColor renk = var ? ChatColor.GREEN : ChatColor.DARK_GRAY;
                String isaret = var ? "✔" : "✘";
                player.sendMessage(renk + isaret + " " + t.ad + ChatColor.GRAY + " [" + t.kategori + "/" + t.tier + "] - " + t.aciklama);
            }
            return true;
        }
        return false;
    }

    // ---- Veri güvenliği: standVeriYuklendi kalıbının aynısı ----

    public void veriKaydet() {
        if (!veriYuklendi) {
            plugin.getLogger().warning("[Basarim] veriKaydet() veriYukle() tamamlanmadan çağrıldı, atlandı (veri kaybını önlemek için).");
            return;
        }
        for (Map.Entry<UUID, Set<String>> entry : kazanilan.entrySet()) {
            plugin.getConfig().set("basarimlar." + entry.getKey().toString(), new ArrayList<>(entry.getValue()));
        }
        plugin.saveConfig();
    }

    public void veriYukle() {
        try {
            kazanilan.clear();
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("basarimlar");
            if (sec != null) {
                for (String uuidStr : sec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        List<String> liste = plugin.getConfig().getStringList("basarimlar." + uuidStr);
                        kazanilan.put(uuid, new HashSet<>(liste));
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Basarim] " + uuidStr + " için başarım verisi okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }
            veriYuklendi = true;
        } catch (Exception e) {
            plugin.getLogger().severe("[Basarim] veriYukle() sırasında hata, veriYuklendi=false kalacak (kayıt atlanacak): " + e.getMessage());
            veriYuklendi = false;
        }
    }
}
