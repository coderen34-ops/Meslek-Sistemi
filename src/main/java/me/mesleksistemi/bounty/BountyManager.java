package me.mesleksistemi.bounty;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounty (ödüllü avcılık) sistemi.
 *
 * Entry #4 tasarımı: /bounty koy <oyuncu> <miktar>. Kötüye kullanım koruması
 * (kendine/arkadaşına bounty koyup para aktarma) bu sürümde TASARLANMADI -
 * changelog'da "ertelendi" olarak işaretli, ileride ayrı ele alınacak.
 *
 * Dupe/kayıp-önleme: bounty parası /bounty koy anında oyuncunun bakiyesinden
 * düşülüp ayrı, kilitli bir "bounty havuzu" haritasına (bu sınıfın kendi
 * aktifBountyler map'i) aktarılır - oyuncu bakiyesinde "askıda" bekletilmez.
 * Hedef öldüğünde o hedefe ait TÜM aktif bounty'ler TEK seferde toplanıp
 * öldürene ödenir ve hemen silinir (PlayerDeathEvent iki kez tetiklenirse
 * ikinci kontrolde liste boş bulunur, çift ödeme olmaz).
 */
public class BountyManager implements Listener, CommandExecutor {

    public static class BountyKaydi {
        public final UUID korkoyanUuid;
        public final String koyanAd;
        public final double miktar;

        public BountyKaydi(UUID korkoyanUuid, String koyanAd, double miktar) {
            this.korkoyanUuid = korkoyanUuid;
            this.koyanAd = koyanAd;
            this.miktar = miktar;
        }
    }

    private final MeslekSistemi plugin;
    private boolean veriYuklendi = false;
    private final Map<UUID, List<BountyKaydi>> aktifBountyler = new ConcurrentHashMap<>();

    public BountyManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        veriYukle();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Bu komut sadece oyuncular tarafından kullanılabilir.");
            return true;
        }
        Player player = (Player) sender;

        if (!command.getName().equalsIgnoreCase("bounty")) return false;

        if (args.length == 0) {
            listele(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("koy")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /bounty koy <oyuncu> <miktar>");
                return true;
            }
            return bountyKoy(player, args[1], args[2]);
        }

        if (args[0].equalsIgnoreCase("liste")) {
            listele(player);
            return true;
        }

        player.sendMessage(ChatColor.RED + "Kullanım: /bounty koy <oyuncu> <miktar> | /bounty liste");
        return true;
    }

    private boolean bountyKoy(Player kaynak, String hedefAd, String miktarStr) {
        if (!veriYuklendi) {
            kaynak.sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
            return true;
        }

        OfflinePlayer hedef = Bukkit.getOfflinePlayer(hedefAd);
        if (hedef == null || (hedef.getName() == null)) {
            kaynak.sendMessage(ChatColor.RED + "Böyle bir oyuncu bulunamadı: " + hedefAd);
            return true;
        }
        if (hedef.getUniqueId().equals(kaynak.getUniqueId())) {
            kaynak.sendMessage(ChatColor.RED + "Kendi başına bounty koyamazsın.");
            return true;
        }

        double miktar;
        try {
            miktar = Double.parseDouble(miktarStr.replace(",", "."));
        } catch (NumberFormatException e) {
            kaynak.sendMessage(ChatColor.RED + "Geçersiz miktar: " + miktarStr);
            return true;
        }
        if (miktar < 100) {
            kaynak.sendMessage(ChatColor.RED + "Minimum bounty miktarı $100.");
            return true;
        }

        double bakiye = plugin.bankaHesaplari.getOrDefault(kaynak.getUniqueId(), 0.0);
        if (bakiye < miktar) {
            kaynak.sendMessage(ChatColor.RED + "Yeterli bakiyen yok. Mevcut bakiye: $" + fmt(bakiye));
            return true;
        }

        // Para hemen oyuncunun bakiyesinden düşülüp kilitli bounty havuzuna aktarılır (dupe/kayıp önleme).
        plugin.bankaHesaplari.put(kaynak.getUniqueId(), bakiye - miktar);
        plugin.veriKaydet();

        aktifBountyler.computeIfAbsent(hedef.getUniqueId(), k -> new ArrayList<>())
                .add(new BountyKaydi(kaynak.getUniqueId(), kaynak.getName(), miktar));
        veriKaydet();

        plugin.ekonomiLog("bounty-koy", kaynak.getName(), hedef.getName() + " için $" + fmt(miktar) + " bounty konuldu.");

        kaynak.sendMessage(ChatColor.GREEN + hedef.getName() + ChatColor.GREEN + " için $" + fmt(miktar) + " bounty koydun!");
        if (hedef.isOnline() && hedef.getPlayer() != null) {
            hedef.getPlayer().sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "DİKKAT: " + ChatColor.RED + "Başına $" + fmt(miktar) + " bounty kondu!");
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[BOUNTY] " + ChatColor.YELLOW + kaynak.getName() +
                ChatColor.GRAY + " -> " + ChatColor.YELLOW + hedef.getName() + ChatColor.GRAY + " için $" + fmt(miktar) + " ödül kondu!");
        return true;
    }

    private void listele(Player player) {
        List<BountyKaydi> kendi = aktifBountyler.get(player.getUniqueId());
        double toplam = kendi == null ? 0 : kendi.stream().mapToDouble(b -> b.miktar).sum();
        if (toplam > 0) {
            player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Başında toplam $" + fmt(toplam) + " bounty var!");
        }
        player.sendMessage(ChatColor.GOLD + "=== Sunucudaki Aktif Bounty'ler ===");
        boolean varMi = false;
        for (Map.Entry<UUID, List<BountyKaydi>> entry : aktifBountyler.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            double toplamHedef = entry.getValue().stream().mapToDouble(b -> b.miktar).sum();
            OfflinePlayer hedef = Bukkit.getOfflinePlayer(entry.getKey());
            String ad = hedef.getName() != null ? hedef.getName() : entry.getKey().toString();
            player.sendMessage(ChatColor.YELLOW + ad + ChatColor.GRAY + " - $" + fmt(toplamHedef) + " (" + entry.getValue().size() + " bounty)");
            varMi = true;
        }
        if (!varMi) player.sendMessage(ChatColor.GRAY + "Şu an aktif bounty yok.");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!veriYuklendi) return;
        Player olen = event.getEntity();
        Player oldurenOyuncu = olen.getKiller();
        if (oldurenOyuncu == null) return; // doğal ölüm / mob öldürmesi - bounty ödenmez

        // Hedefe ait TÜM aktif bounty'leri TEK seferde al ve sil (idempotent -
        // event ikinci kez tetiklenirse remove() null/boş liste döner, çift ödeme olmaz).
        List<BountyKaydi> kayitlar = aktifBountyler.remove(olen.getUniqueId());
        if (kayitlar == null || kayitlar.isEmpty()) return;

        double toplam = kayitlar.stream().mapToDouble(b -> b.miktar).sum();
        double mevcut = plugin.bankaHesaplari.getOrDefault(oldurenOyuncu.getUniqueId(), 0.0);
        plugin.bankaHesaplari.put(oldurenOyuncu.getUniqueId(), mevcut + toplam);
        plugin.veriKaydet();
        veriKaydet();

        plugin.ekonomiLog("bounty-ode", oldurenOyuncu.getName(), olen.getName() + " öldürülerek toplam $" + fmt(toplam) +
                " bounty ödemesi alındı (" + kayitlar.size() + " bounty).");

        oldurenOyuncu.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + olen.getName() + "'i öldürerek $" + fmt(toplam) + " bounty kazandın!");
        try {
            oldurenOyuncu.playSound(oldurenOyuncu.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[BOUNTY] " + ChatColor.YELLOW + oldurenOyuncu.getName() +
                ChatColor.GRAY + " " + ChatColor.YELLOW + olen.getName() + ChatColor.GRAY + "'i öldürüp $" + fmt(toplam) + " bounty aldı!");
    }

    private String fmt(double d) {
        return String.format(Locale.US, "%.2f", d);
    }

    // ---- Veri güvenliği: standVeriYuklendi kalıbının aynısı ----

    public void veriKaydet() {
        if (!veriYuklendi) {
            plugin.getLogger().warning("[Bounty] veriKaydet() veriYukle() tamamlanmadan çağrıldı, atlandı (veri kaybını önlemek için).");
            return;
        }
        List<Map<String, Object>> liste = new ArrayList<>();
        for (Map.Entry<UUID, List<BountyKaydi>> entry : aktifBountyler.entrySet()) {
            for (BountyKaydi k : entry.getValue()) {
                Map<String, Object> satir = new java.util.HashMap<>();
                satir.put("hedef", entry.getKey().toString());
                satir.put("koyan", k.korkoyanUuid.toString());
                satir.put("koyanAd", k.koyanAd);
                satir.put("miktar", k.miktar);
                liste.add(satir);
            }
        }
        plugin.getConfig().set("bounty_aktif", liste);
        plugin.saveConfig();
    }

    @SuppressWarnings("unchecked")
    public void veriYukle() {
        try {
            aktifBountyler.clear();
            List<?> liste = plugin.getConfig().getList("bounty_aktif");
            if (liste != null) {
                for (Object o : liste) {
                    try {
                        Map<String, Object> satir = (Map<String, Object>) o;
                        UUID hedef = UUID.fromString(String.valueOf(satir.get("hedef")));
                        UUID koyan = UUID.fromString(String.valueOf(satir.get("koyan")));
                        String koyanAd = String.valueOf(satir.get("koyanAd"));
                        double miktar = Double.parseDouble(String.valueOf(satir.get("miktar")));
                        aktifBountyler.computeIfAbsent(hedef, k -> new ArrayList<>())
                                .add(new BountyKaydi(koyan, koyanAd, miktar));
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Bounty] bir bounty kaydı okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }
            veriYuklendi = true;
        } catch (Exception e) {
            plugin.getLogger().severe("[Bounty] veriYukle() sırasında hata, veriYuklendi=false kalacak (kayıt atlanacak): " + e.getMessage());
            veriYuklendi = false;
        }
    }
}
