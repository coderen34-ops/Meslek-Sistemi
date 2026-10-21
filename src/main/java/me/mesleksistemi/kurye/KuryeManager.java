package me.mesleksistemi.kurye;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Kurye meslek/görev sistemi - Entry #4 / Faz 2, madde 6. Changelog tasarımı:
 * "bu bir pazar değil, görev tabanlı: oyuncuya NPC'den X noktasına Y eşyasını götür
 * görevi veriliyor, teslim noktasında NPC item'i kontrol edip mesafe/risk bazlı ücret
 * ödüyor". Görev sistemi (GorevManager) üzerine kurulması tasarımda önerilmişti, ama
 * Kurye'nin nokta-A'dan nokta-B'ye taşıma mekaniği (iki farklı NPC, mesafeye göre ücret,
 * taşınan paketin kendisi) TOPLA/OLDUR/KAZAN modeline uymadığı için BasarimManager/
 * BountyManager gibi ayrı, kendi başına bir Listener+CommandExecutor olarak kuruldu -
 * GorevManager'ın idare ettiği görev listesine bir giriş eklemek yerine.
 *
 * İptal/vazgeçme koruması (risk analizinde flaglenen "kurye kayıp eşya önleme"):
 * - Oyuncudan paket almak için hiçbir zaman PARA ÇEKİLMEZ (ücret sadece teslimde ödenir),
 *   bu yüzden iptal/vazgeçmede dupe veya kayıp PARA riski yoktur.
 * - Paket itemının (PersistentDataContainer ile işaretli kağıt) yere atılması engellenir
 *   (PlayerDropItemEvent iptal edilir) - çöp kutusuna atma gibi trivial kayıplar önlenir.
 * - Oyuncu ölürse paket (sunucu hardcore olduğu için ölüm = spectator, envanter genelde
 *   korunur ama garanti değil) kaybolabilir; bu sadece oyuncunun o teslimatı kaybetmesine
 *   yol açar - sisteme hiçbir ekonomik kayıp/dupe riski getirmez (ücret zaten ödenmemişti).
 * - /kuryevazgec ile oyuncu istediği an aktif teslimatını iptal edebilir (paket silinir,
 *   hiçbir para hareketi yoktur).
 *
 * Veri güvenliği: diğer Faz 1/2 modülleriyle aynı veriYuklendi boolean-guard kalıbı.
 */
public class KuryeManager implements Listener, CommandExecutor {

    private static class KuryeGorevi {
        UUID paketId;
        UUID alimNpcUuid;
        UUID hedefNpcUuid;
        String hedefAd;
        double mesafe;
        double ucret;
    }

    private final MeslekSistemi plugin;
    private final NamespacedKey kuryeNpcKey;
    private final NamespacedKey paketKey;
    private boolean veriYuklendi = false;

    private final Map<UUID, KuryeGorevi> aktifGorevler = new HashMap<>();

    public KuryeManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.kuryeNpcKey = new NamespacedKey(plugin, "kurye_npc");
        this.paketKey = new NamespacedKey(plugin, "kurye_paket");
        veriYukle();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("kuryenpc")) {
            if (!player.hasPermission("meslek.admin") && !player.isOp()) {
                player.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkin yok.");
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Kullanım: /kuryenpc kur | sil");
                return true;
            }
            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
                npc.setCustomName(ChatColor.AQUA + "" + ChatColor.BOLD + "Kurye Noktası");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.CLERIC);
                npc.getPersistentDataContainer().set(kuryeNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Kurye Noktası NPC'si kuruldu! (En az 2 tane kurulmalı ki teslimat üretilebilsin.)");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(kuryeNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(silinen > 0 ? ChatColor.GREEN + "" + silinen + " Kurye Noktası NPC'si silindi." : ChatColor.RED + "Yakında Kurye Noktası NPC'si bulunamadı.");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("kuryevazgec")) {
            if (!veriYuklendi) {
                player.sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
                return true;
            }
            KuryeGorevi gorev = aktifGorevler.remove(player.getUniqueId());
            if (gorev == null) {
                player.sendMessage(ChatColor.RED + "Aktif bir teslimatın yok.");
                return true;
            }
            esyaCikarPaket(player, gorev.paketId);
            veriKaydet();
            player.sendMessage(ChatColor.YELLOW + "Teslimattan vazgeçtin. (Hiçbir ücret ödenmedi - zaten peşin alınmamıştı.)");
            return true;
        }
        return false;
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager)) return;
        Villager npc = (Villager) event.getRightClicked();
        if (!npc.getPersistentDataContainer().has(kuryeNpcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!veriYuklendi) {
            player.sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
            return;
        }

        KuryeGorevi mevcut = aktifGorevler.get(player.getUniqueId());
        if (mevcut == null) {
            // Yeni teslimat teklif et: bu NPC alım noktası olacak, hedef rastgele BAŞKA bir Kurye NPC'si
            teklifEt(player, npc);
            return;
        }

        if (mevcut.hedefNpcUuid.equals(npc.getUniqueId())) {
            teslimEt(player, mevcut, npc);
        } else if (mevcut.alimNpcUuid.equals(npc.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Bu senin alım noktan. Teslimat için hedefin: " + mevcut.hedefAd);
        } else {
            player.sendMessage(ChatColor.RED + "Zaten aktif bir teslimatın var! Hedefin: " + mevcut.hedefAd + " (vazgeçmek için /kuryevazgec)");
        }
    }

    private List<Villager> tumKuryeNpcleri(World world) {
        List<Villager> liste = new ArrayList<>();
        for (Entity e : world.getEntitiesByClass(Villager.class)) {
            if (e.getPersistentDataContainer().has(kuryeNpcKey, PersistentDataType.BYTE)) liste.add((Villager) e);
        }
        return liste;
    }

    private void teklifEt(Player player, Villager alimNpc) {
        List<Villager> adaylar = new ArrayList<>();
        for (Villager v : tumKuryeNpcleri(alimNpc.getWorld())) {
            if (!v.getUniqueId().equals(alimNpc.getUniqueId())) adaylar.add(v);
        }
        if (adaylar.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Şu an teslimat üretilecek başka bir Kurye Noktası yok.");
            return;
        }
        Villager hedef = adaylar.get(new java.util.Random().nextInt(adaylar.size()));
        double mesafe = alimNpc.getLocation().distance(hedef.getLocation());
        double ucret = Math.round((50 + mesafe * 0.8) * 100.0) / 100.0;
        String hedefAd = ChatColor.stripColor(hedef.getCustomName() != null ? hedef.getCustomName() : "Kurye Noktası") + " #" + hedef.getUniqueId().toString().substring(0, 4);

        UUID paketId = UUID.randomUUID();
        ItemStack paket = new ItemStack(Material.PAPER);
        ItemMeta meta = paket.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Kurye Paketi");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Teslimat noktası: " + ChatColor.YELLOW + hedefAd);
        lore.add(ChatColor.GRAY + "Dikkat: Bu paket yere atılamaz.");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(paketKey, PersistentDataType.STRING, paketId.toString());
        paket.setItemMeta(meta);

        PlayerInventory inv = player.getInventory();
        if (inv.firstEmpty() == -1) {
            player.sendMessage(ChatColor.RED + "Envanterin dolu! Paketi alabilmek için yer açman gerek.");
            return;
        }
        inv.addItem(paket);

        KuryeGorevi gorev = new KuryeGorevi();
        gorev.paketId = paketId;
        gorev.alimNpcUuid = alimNpc.getUniqueId();
        gorev.hedefNpcUuid = hedef.getUniqueId();
        gorev.hedefAd = hedefAd;
        gorev.mesafe = mesafe;
        gorev.ucret = ucret;
        aktifGorevler.put(player.getUniqueId(), gorev);
        veriKaydet();

        player.sendMessage(ChatColor.GREEN + "Yeni teslimat: paketi " + ChatColor.YELLOW + hedefAd + ChatColor.GREEN + " noktasına götür!");
        player.sendMessage(ChatColor.GRAY + "Mesafe: ~" + Math.round(mesafe) + " blok, ücret: $" + fmt(ucret) + " (vazgeçmek için /kuryevazgec)");
    }

    private void teslimEt(Player player, KuryeGorevi gorev, Villager hedefNpc) {
        if (!paketVarMi(player, gorev.paketId)) {
            player.sendMessage(ChatColor.RED + "Paket envanterinde bulunamadı! (Düşürdün ya da ölürken kaybettin mi?)");
            aktifGorevler.remove(player.getUniqueId());
            veriKaydet();
            return;
        }

        esyaCikarPaket(player, gorev.paketId);
        // Dupe/çift ödeme önleme: kayıttan HEMEN kaldırılıyor, ödeme tek seferlik ve senkron
        // (ana thread'de çalışan PlayerInteractEntityEvent) - ikinci bir tıklama artık
        // aktifGorevler'de kayıt bulamaz.
        aktifGorevler.remove(player.getUniqueId());

        double bakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
        plugin.bankaHesaplari.put(player.getUniqueId(), bakiye + gorev.ucret);
        plugin.veriKaydet();
        veriKaydet();

        plugin.ekonomiLog("kurye-teslimat", player.getName(), "Teslimat tamamlandı (" + Math.round(gorev.mesafe) + " blok) - ödül $" + fmt(gorev.ucret));

        player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "✔ Teslimat tamamlandı! " + ChatColor.GREEN + "$" + fmt(gorev.ucret) + " hesabına yatırıldı.");
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
    }

    private boolean paketVarMi(Player player, UUID paketId) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (paketIdOku(it) != null && paketIdOku(it).equals(paketId)) return true;
        }
        return false;
    }

    private UUID paketIdOku(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(paketKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void esyaCikarPaket(Player player, UUID paketId) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            UUID id = paketIdOku(it);
            if (id != null && id.equals(paketId)) {
                inv.setItem(i, null);
                return;
            }
        }
    }

    // Paket yere atılamaz - trivial kayıp/çöpe atma önlenir (bkz. sınıf üstü yorum).
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (paketIdOku(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Kurye paketini yere atamazsın! (/kuryevazgec ile vazgeçebilirsin)");
        }
    }

    private String fmt(double d) { return String.format(Locale.US, "%.2f", d); }

    // ---- Veri güvenliği: standVeriYuklendi kalıbının aynısı ----

    public void veriKaydet() {
        if (!veriYuklendi) {
            plugin.getLogger().warning("[Kurye] veriKaydet() veriYukle() tamamlanmadan çağrıldı, atlandı (veri kaybını önlemek için).");
            return;
        }
        plugin.getConfig().set("kurye_aktif", null);
        for (Map.Entry<UUID, KuryeGorevi> giris : aktifGorevler.entrySet()) {
            String yol = "kurye_aktif." + giris.getKey();
            KuryeGorevi g = giris.getValue();
            plugin.getConfig().set(yol + ".paketId", g.paketId.toString());
            plugin.getConfig().set(yol + ".alimNpcUuid", g.alimNpcUuid.toString());
            plugin.getConfig().set(yol + ".hedefNpcUuid", g.hedefNpcUuid.toString());
            plugin.getConfig().set(yol + ".hedefAd", g.hedefAd);
            plugin.getConfig().set(yol + ".mesafe", g.mesafe);
            plugin.getConfig().set(yol + ".ucret", g.ucret);
        }
        plugin.saveConfig();
    }

    public void veriYukle() {
        try {
            aktifGorevler.clear();
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("kurye_aktif");
            if (sec != null) {
                for (String uuidStr : sec.getKeys(false)) {
                    try {
                        ConfigurationSection c = sec.getConfigurationSection(uuidStr);
                        KuryeGorevi g = new KuryeGorevi();
                        g.paketId = UUID.fromString(c.getString("paketId"));
                        g.alimNpcUuid = UUID.fromString(c.getString("alimNpcUuid"));
                        g.hedefNpcUuid = UUID.fromString(c.getString("hedefNpcUuid"));
                        g.hedefAd = c.getString("hedefAd", "?");
                        g.mesafe = c.getDouble("mesafe");
                        g.ucret = c.getDouble("ucret");
                        if (!Double.isFinite(g.ucret) || g.ucret < 0) continue;
                        aktifGorevler.put(UUID.fromString(uuidStr), g);
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Kurye] '" + uuidStr + "' yüklenemedi, atlandı: " + innerEx.getMessage());
                    }
                }
            }
            veriYuklendi = true;
        } catch (Exception e) {
            plugin.getLogger().severe("[Kurye] veriYukle() sırasında hata, veriYuklendi=false kalacak (kayıt atlanacak): " + e.getMessage());
            veriYuklendi = false;
        }
    }
}
