package me.mesleksistemi.meslekler;

import me.mesleksistemi.MeslekSistemi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Projectile;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class SaglikManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    private final NamespacedKey medicalKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey hastaneNpcKey;

    private final HashMap<UUID, Boolean> brokenLegs = new HashMap<>();
    private final HashMap<UUID, Boolean> bleedingPlayers = new HashMap<>();
    private final HashMap<UUID, Integer> downedPlayers = new HashMap<>(); 
    private final HashMap<String, UUID> ambulansCagrilari = new HashMap<>();
    // Hastanede tedavi gören oyuncular -> kalan saniye (tedavi bitene kadar ölüm sayacı durur)
    private final HashMap<UUID, Integer> ambulansTedavisi = new HashMap<>();

    private final List<Location> hastaneYataklari = new ArrayList<>();
    // Dünyası yüklü olmayan yatak kayıtları: kaydederken silinmesinler diye ham haliyle tutulur
    private final List<String> yuklenemeyenYataklar = new ArrayList<>();

    // Yarayı kimin açtığı (doktor kendi açtığı yarayı sarınca prim almaz)
    private final HashMap<UUID, UUID> yaralayanlar = new HashMap<>();
    // Aynı hasta için devlet primi en fazla bu aralıkla ödenir
    private final HashMap<UUID, Long> sonPrimZamani = new HashMap<>();
    private static final long PRIM_BEKLEME_MS = 10 * 60 * 1000L;

    // Sağlık sisteminden muaf oyuncular (örn. arena savaşı). MeslekAPI üzerinden ayarlanır.
    private final java.util.Set<UUID> muafOyuncular = new java.util.HashSet<>();

    public void muafiyetAyarla(UUID oyuncu, boolean muaf) {
        if (muaf) muafOyuncular.add(oyuncu);
        else muafOyuncular.remove(oyuncu);
    }

    public boolean muafMi(UUID oyuncu) {
        return muafOyuncular.contains(oyuncu);
    }

    /** Hapse giren oyuncunun baygınlığı/yarası geçer; hiç bayılmamış gibi devam eder. */
    public void hapseGirdiIyilestir(Player player) {
        UUID id = player.getUniqueId();
        ambulansTedavisi.remove(id);
        yaralayanlar.remove(id);
        tamTedavi(player);
        veriKaydetSaglik();
    }

    public boolean agirYaraliMi(UUID oyuncu) {
        return downedPlayers.containsKey(oyuncu);
    }

    // Ağır yaralıyken kullanılamayan ışınlanma komutları
    private static final Set<String> YARALIYKEN_YASAK_KOMUTLAR = Set.of(
            "tpa", "call", "tpaccept", "tpdeny", "tpahere", "warp", "spawn", "home");
    
    private final Random random = new Random();

    private double cfg(String yol, double varsayilan) { return plugin.getConfig().getDouble("saglik." + yol, varsayilan); }

    private boolean doktorOnlineMi() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "").equalsIgnoreCase("doktor")) continue;
            // Kendisi baygın/hastanede olan doktor müdahale edemez; tek doktor yaralıysa sunucuda doktor yok sayılır
            if (downedPlayers.containsKey(p.getUniqueId()) || ambulansTedavisi.containsKey(p.getUniqueId())) continue;
            return true;
        }
        return false;
    }

    /** Tedavi ücreti: önce üstündeki nakitten, yetmezse banka hesabından alınıp belediye kasasına yatar. */
    private boolean tedaviUcretiOde(Player p, double ucret) {
        double nakit = 0.0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null) continue;
            Double v = plugin.getMoneyValue(it);
            if (v != null) nakit += v * it.getAmount();
        }
        if (nakit >= ucret) return plugin.processPaymentToKasa(p, ucret);
        double banka = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
        if (banka >= ucret) {
            if (!plugin.kasayaParaEkle(ucret)) {
                p.sendMessage(ChatColor.RED + "Belediye kasası aktif değil ya da dolu, ödeme alınamıyor! Yetkililere bildirin.");
                return false;
            }
            plugin.bankaHesaplari.put(p.getUniqueId(), banka - ucret);
            plugin.veriKaydet();
            p.sendMessage(ChatColor.GRAY + "$" + ucret + " banka hesabından çekildi.");
            return true;
        }
        p.sendMessage(ChatColor.RED + "Tedavi ücreti: $" + ucret + ". Nakit ve banka hesabınız yetersiz.");
        return false;
    }

    /** Oyuncuyu tamamen iyileştirir: kırık, kanama, baygınlık ve etkiler kalkar, can dolar. */
    private void tamTedavi(Player player) {
        UUID id = player.getUniqueId();
        brokenLegs.remove(id);
        bleedingPlayers.remove(id);
        if (downedPlayers.containsKey(id)) {
            downedPlayers.remove(id);
            Bukkit.dispatchCommand(player, "sit");
        }
        AttributeInstance maxCan = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxCan != null ? maxCan.getValue() : 20.0);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    /** Hastanedeki tedaviyi başlatır (ya da girişte kalan süreyle devam ettirir). */
    private void ambulansTedavisiBaslat(Player player, int sureSaniye) {
        ambulansTedavisi.put(player.getUniqueId(), sureSaniye);
        ambulansTedavisiTakip(player);
    }

    private void ambulansTedavisiTakip(Player player) {
        UUID id = player.getUniqueId();
        new BukkitRunnable() {
            @Override
            public void run() {
                // Çevrimdışıysa kayıt kalır, oyuncu girince kalan süreyle devam eder
                if (!player.isOnline()) { this.cancel(); return; }
                Integer kalan = ambulansTedavisi.get(id);
                if (kalan == null) { this.cancel(); return; }
                // Ölmüş ya da başka biri (adrenalin) ayağa kaldırmışsa tedavi sona erer
                if (!downedPlayers.containsKey(id)) {
                    ambulansTedavisi.remove(id);
                    this.cancel();
                    return;
                }
                kalan--;
                if (kalan <= 0) {
                    ambulansTedavisi.remove(id);
                    tamTedavi(player);
                    player.sendMessage(ChatColor.GREEN + "Tedavin tamamlandı, tamamen iyileştin. Geçmiş olsun!");
                    veriKaydetSaglik();
                    this.cancel();
                    return;
                }
                ambulansTedavisi.put(id, kalan);
                if (kalan % 30 == 0 || kalan == 10 || kalan <= 3) {
                    String sureYazi = kalan >= 60 ? (kalan / 60) + " dk" + (kalan % 60 != 0 ? " " + (kalan % 60) + " sn" : "") : kalan + " sn";
                    player.sendMessage(ChatColor.AQUA + "Tedavin sürüyor, " + sureYazi + " kaldı...");
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public SaglikManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.medicalKey = new NamespacedKey(plugin, "medical_item");
        this.typeKey = new NamespacedKey(plugin, "medical_type");
        this.hastaneNpcKey = new NamespacedKey(plugin, "hastane_npc");

        veriYukleSaglik();
        startBleedingTask();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("hastaneyatak")) {
            if (!player.hasPermission("saglik.admin")) return true;
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Kullanım: /hastaneyatak <ekle|sil>");
                return true;
            }
            if (args[0].equalsIgnoreCase("ekle")) {
                hastaneYataklari.add(player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Bulunduğun konum acil servis yatağı olarak kaydedildi!");
                veriKaydetSaglik();
            } else if (args[0].equalsIgnoreCase("sil")) {
                hastaneYataklari.clear();
                yuklenemeyenYataklar.clear();
                player.sendMessage(ChatColor.GREEN + "Tüm hastane yatakları başarıyla silindi!");
                veriKaydetSaglik();
            }
            return true;
        }

        if (cmd.equals("ambulanscagir")) {
            if (!downedPlayers.containsKey(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Sadece ağır yaralıyken ambulans çağırabilirsin!");
                return true;
            }
            if (ambulansTedavisi.containsKey(player.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + "Zaten hastanede tedavi görüyorsun, " + ambulansTedavisi.get(player.getUniqueId()) + " saniye kaldı.");
                return true;
            }
            boolean doktorVar = doktorOnlineMi();
            double ucret = doktorVar ? cfg("ambulans-ucret-doktorlu", 1250.0) : cfg("ambulans-ucret-doktorsuz", 1000.0);
            double bakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
            if (bakiye < ucret) {
                player.sendMessage(ChatColor.RED + "Ambulans çağırmak için banka hesabında en az $" + (long) ucret + " olmalı!");
                return true;
            }
            if (!doktorVar) {
                // Doktor yok: ambulans hastayı hastaneye götürür, tedavi süresi (varsayılan 3 dk) dolunca iyileşir
                if (plugin.hapisteMi(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Hapisteyken ambulans çağıramazsın.");
                    return true;
                }
                if (!plugin.kasayaParaEkle(ucret)) {
                    player.sendMessage(ChatColor.RED + "Belediye kasası aktif değil ya da dolu, ambulans gönderilemiyor. Yetkililere bildirin.");
                    return true;
                }
                plugin.bankaHesaplari.put(player.getUniqueId(), bakiye - ucret);
                plugin.veriKaydet();
                if (player.getVehicle() != null) player.getVehicle().removePassenger(player);
                if (!player.getPassengers().isEmpty()) player.eject();
                boolean hastanede = !hastaneYataklari.isEmpty();
                if (hastanede) {
                    player.teleport(hastaneYataklari.get(random.nextInt(hastaneYataklari.size())));
                }
                int sure = (int) Math.max(1, cfg("ambulans-tedavi-suresi-saniye", 180.0));
                ambulansTedavisiBaslat(player, sure);
                veriKaydetSaglik();
                player.sendMessage(ChatColor.GREEN + (hastanede ? "Ambulans seni hastaneye getirdi." : "Ambulans sana müdahale ediyor.")
                        + " Tedavin " + (sure >= 60 ? (sure / 60) + " dakika" : sure + " saniye") + " sürecek, bekle. Bankandan $" + (long) ucret + " kesildi.");
                return true;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                String meslek = plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "");
                if (meslek.equalsIgnoreCase("doktor") && !downedPlayers.containsKey(p.getUniqueId()) && !ambulansTedavisi.containsKey(p.getUniqueId())) {
                    p.sendMessage(ChatColor.DARK_RED + "[ACİL SERVİS] " + ChatColor.YELLOW + player.getName() + " ağır yaralı! " + ChatColor.RED + "Müdahale için: /ambulanskabul " + player.getName());
                }
            }
            ambulansCagrilari.put(player.getName().toLowerCase(), player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "Ambulans çağrısı doktorlara iletildi. Biri kabul ettiğinde bankandan $" + (long) ucret + " kesilecek.");
            return true;
        }

        if (cmd.equals("ambulanskabul")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!meslek.equalsIgnoreCase("doktor")) {
                player.sendMessage(ChatColor.RED + "Bu komutu sadece doktorlar kullanabilir.");
                return true;
            }
            if (downedPlayers.containsKey(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Sen de yaralısın, çağrıyı kabul edemezsin.");
                return true;
            }
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Kullanım: /ambulanskabul <OyuncuAdı>");
                return true;
            }
            String targetName = args[0].toLowerCase();
            if (!ambulansCagrilari.containsKey(targetName)) {
                player.sendMessage(ChatColor.RED + "Bu oyuncuya ait aktif bir ambulans çağrısı bulunamadı.");
                return true;
            }
            
            UUID targetUUID = ambulansCagrilari.get(targetName);
            Player target = Bukkit.getPlayer(targetUUID);
            
            if (target == null || !target.isOnline() || !downedPlayers.containsKey(targetUUID)) {
                player.sendMessage(ChatColor.RED + "Hasta şu an oyunda değil veya iyileşmiş.");
                ambulansCagrilari.remove(targetName);
                return true;
            }
            // Ambulans hapisteki hastayı hücreden çıkaramaz; doktor hücreye gidip müdahale etmeli
            if (plugin.hapisteMi(targetUUID)) {
                player.sendMessage(ChatColor.RED + "Hasta şu an hapiste! Ambulans gönderilemez, hücresine gidip müdahale etmelisin.");
                return true;
            }
            
            double ucret = cfg("ambulans-ucret-doktorlu", 1250.0);
            double hastaBakiye = plugin.bankaHesaplari.getOrDefault(targetUUID, 0.0);
            if (hastaBakiye < ucret) {
                player.sendMessage(ChatColor.RED + "Hastanın banka hesabında yeterli para kalmamış.");
                ambulansCagrilari.remove(targetName);
                return true;
            }
            
            plugin.bankaHesaplari.put(targetUUID, hastaBakiye - ucret);
            double docBakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
            plugin.bankaHesaplari.put(player.getUniqueId(), docBakiye + ucret);
            plugin.veriKaydet();
            
            ambulansCagrilari.remove(targetName);
            
            if (target.getVehicle() != null) target.getVehicle().removePassenger(target);
            if (!target.getPassengers().isEmpty()) target.eject();

            if (!hastaneYataklari.isEmpty()) {
                Location yatak = hastaneYataklari.get(random.nextInt(hastaneYataklari.size()));
                target.teleport(yatak);
                player.sendMessage(ChatColor.GREEN + "Hasta ambulans ile Acil Servis yatağına çekildi! $" + (long) ucret + " hesabına yattı.");
                target.sendMessage(ChatColor.GREEN + "Ambulans çağrın kabul edildi ve hastaneye kaldırıldın. Bankandan $" + (long) ucret + " kesildi.");
            } else {
                target.teleport(player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Hastayı başarıyla yanına çektin! $" + (long) ucret + " hesabına yattı.");
                target.sendMessage(ChatColor.GREEN + "Doktor " + player.getName() + " seni acil müdahale için yanına çekti. Bankandan $" + (long) ucret + " kesildi.");
            }
            
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (target.isOnline() && downedPlayers.containsKey(targetUUID)) {
                    Bukkit.dispatchCommand(target, "lay");
                }
            }, 5L);

            return true;
        }

        if (cmd.equals("doktormarket")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!meslek.equalsIgnoreCase("doktor")) {
                player.sendMessage(ChatColor.RED + "Bu marketi sadece sağlık personeli kullanabilir.");
                return true;
            }
            openMedicalGUI(player);
            return true;
        }

        if (cmd.equals("hastanenpc")) {
            if (!player.hasPermission("saglik.admin")) return true;
            if (args.length == 0) return true;

            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
                npc.setCustomName(ChatColor.RED + "" + ChatColor.BOLD + "Devlet Hastanesi (Acil Servis)");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.CLERIC);
                npc.getPersistentDataContainer().set(hastaneNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Hastane NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(hastaneNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(ChatColor.GREEN + "" + silinen + " adet NPC silindi.");
            }
            return true;
        }
        return false;
    }

    private void openMedicalGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.DARK_RED + "Medikal Depo");
        gui.setItem(2, createDisplayItem(createBandaj(), 15.0));
        gui.setItem(4, createDisplayItem(createAtel(), 50.0));
        gui.setItem(6, createDisplayItem(createAdrenalin(), 250.0));
        player.openInventory(gui);
    }

    private ItemStack createDisplayItem(ItemStack medicalItem, double price) {
        ItemStack displayItem = medicalItem.clone();
        ItemMeta meta = displayItem.getItemMeta();
        List<String> lore = meta.getLore();
        if (lore == null) lore = new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.YELLOW + "Maliyet: " + ChatColor.GREEN + "$" + price);
        lore.add(ChatColor.GRAY + "Satın almak için tıkla.");
        meta.setLore(lore);
        displayItem.setItemMeta(meta);
        return displayItem;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(ChatColor.DARK_RED + "Medikal Depo")) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            ItemStack clickedItem = event.getCurrentItem();

            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            if (clickedItem == null || !clickedItem.hasItemMeta()) return;

            double price = 0.0;
            ItemStack itemToGive = null;

            if (clickedItem.getType() == Material.PAPER && clickedItem.getItemMeta().getDisplayName().contains("Bandaj")) {
                price = 15.0; itemToGive = createBandaj();
            } else if (clickedItem.getType() == Material.STICK && clickedItem.getItemMeta().getDisplayName().contains("Atel")) {
                price = 50.0; itemToGive = createAtel();
            } else if (clickedItem.getType() == Material.SPECTRAL_ARROW && clickedItem.getItemMeta().getDisplayName().contains("Adrenalin İğnesi")) {
                price = 250.0; itemToGive = createAdrenalin();
            }

            if (itemToGive != null) {
                if (plugin.processPaymentToKasa(player, price)) {
                    player.getInventory().addItem(itemToGive);
                    player.sendMessage(ChatColor.GREEN + "Satın alındı! Para Belediye Kasasına aktarıldı.");
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();
        if (muafOyuncular.contains(uuid)) return; // Muaf oyuncu normal hasar alır ve ölebilir

        // Boşluğa düşmek ve /kill baygınlıkla engellenmez (oyuncu sonsuza dek düşmesin)
        EntityDamageEvent.DamageCause sebep = event.getCause();
        if (sebep == EntityDamageEvent.DamageCause.VOID || sebep == EntityDamageEvent.DamageCause.KILL) {
            if (downedPlayers.remove(uuid) != null) ayilt(player);
            return;
        }

        if (downedPlayers.containsKey(uuid)) {
            event.setCancelled(true);
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (event.getDamage() > cfg("kirik-hasar-esigi", 6.0) && !brokenLegs.containsKey(uuid)) {
                double kirikIhtimali = cfg("kirik-ihtimal", 20.0); 
                ItemStack boots = player.getInventory().getBoots();
                if (boots != null) {
                    if (boots.getType() == Material.IRON_BOOTS) kirikIhtimali -= 5.0;
                    else if (boots.getType() == Material.DIAMOND_BOOTS) kirikIhtimali -= 10.0;
                    else if (boots.getType() == Material.NETHERITE_BOOTS) kirikIhtimali -= 15.0;
                    
                    if (boots.containsEnchantment(Enchantment.FEATHER_FALLING)) {
                        int level = boots.getEnchantmentLevel(Enchantment.FEATHER_FALLING);
                        kirikIhtimali -= (level * 5.0); 
                    }
                }
                if (kirikIhtimali < 5.0) kirikIhtimali = 5.0; 

                if (random.nextDouble() * 100 < kirikIhtimali) { 
                    brokenLegs.put(uuid, true);
                    player.sendMessage(ChatColor.RED + "Sert düştün ve bacağın kırıldı! Acilen atel sarmalısın.");
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 2, false, false));
                }
            }
        }

        if (player.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            UUID saldiran = saldiranOyuncu(event);
            if (saldiran != null) yaralayanlar.put(uuid, saldiran);
            applyDownedState(player, 1200); // 20 Dakika
            player.sendMessage(ChatColor.DARK_RED + "Ağır yaralandın ve bilincini kaybettin!");
            player.sendMessage(ChatColor.RED + "Bir doktor gelmezse 20 dakika içinde öleceksin.");
            player.sendMessage(ChatColor.YELLOW + "Ambulans çağırmak için " + ChatColor.GOLD + "/ambulanscagir" + ChatColor.YELLOW + " yazabilirsin. (Ücret: $" + (long) cfg("ambulans-ucret-doktorsuz", 1000.0) + ", aktif doktor varsa $" + (long) cfg("ambulans-ucret-doktorlu", 1250.0) + ")");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();
        if (muafOyuncular.contains(victim.getUniqueId())) return;
        if (victim.isBlocking()) return; // Kalkanla savunulursa kanama olmaz!

        boolean canBleed = false;
        if (event.getDamager() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            Material weapon = attacker.getInventory().getItemInMainHand().getType();
            if (weapon.name().contains("SWORD") || weapon.name().contains("AXE")) canBleed = true;
        } else if (event.getDamager() instanceof Arrow) {
            canBleed = true;
        }

        if (canBleed && !bleedingPlayers.containsKey(victim.getUniqueId())) {
            double kanamaIhtimali = cfg("kanama-ihtimal", 12.0); 
            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (armor != null) {
                    if (armor.getType().name().contains("IRON")) kanamaIhtimali -= 1.5;
                    else if (armor.getType().name().contains("DIAMOND")) kanamaIhtimali -= 3.0;
                    else if (armor.getType().name().contains("NETHERITE")) kanamaIhtimali -= 4.0;
                    
                    if (armor.containsEnchantment(Enchantment.PROTECTION)) {
                        kanamaIhtimali -= (armor.getEnchantmentLevel(Enchantment.PROTECTION) * 1.5);
                    }
                    if (event.getDamager() instanceof Arrow && armor.containsEnchantment(Enchantment.PROJECTILE_PROTECTION)) {
                        kanamaIhtimali -= (armor.getEnchantmentLevel(Enchantment.PROJECTILE_PROTECTION) * 2.0);
                    }
                }
            }
            if (kanamaIhtimali < 2.0) kanamaIhtimali = 2.0;

            if (random.nextDouble() * 100 < kanamaIhtimali) {
                bleedingPlayers.put(victim.getUniqueId(), true);
                UUID saldiran = saldiranOyuncu(event);
                if (saldiran != null) yaralayanlar.put(victim.getUniqueId(), saldiran);
                victim.sendMessage(ChatColor.DARK_RED + "Derin bir yara aldın ve kanaman başladı! Bandaj bulmalısın.");
            }
        }
    }

    // Hasarı veren oyuncu (doğrudan vuruş ya da attığı ok)
    private UUID saldiranOyuncu(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent)) return null;
        Entity hasarVeren = ((EntityDamageByEntityEvent) event).getDamager();
        if (hasarVeren instanceof Player) return hasarVeren.getUniqueId();
        if (hasarVeren instanceof Projectile && ((Projectile) hasarVeren).getShooter() instanceof Player) {
            return ((Player) ((Projectile) hasarVeren).getShooter()).getUniqueId();
        }
        return null;
    }

    // Baygınlık etkilerini kaldırır
    private void ayilt(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
    }

    private void startBleedingTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : new ArrayList<>(bleedingPlayers.keySet())) {
                    Player p = Bukkit.getPlayer(uuid);
                    // Çevrimdışı oyuncular listeden silinmiyor, girdiklerinde devam edecek
                    if (p != null && p.isOnline() && !downedPlayers.containsKey(uuid) && !muafOyuncular.contains(uuid)) {
                        // Kanama öldürmez: can yarım kalbe inecekse kanama kendiliğinden durur
                        if (p.getHealth() - 1.0 < 1.0) {
                            bleedingPlayers.remove(uuid);
                            p.sendMessage(ChatColor.YELLOW + "Kanaman kendiliğinden durdu ama durumun çok kritik! Acilen tedavi ol.");
                        } else {
                            p.damage(1.0);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 100L); 
    }

    private void applyDownedState(Player player, int time) {
        UUID uuid = player.getUniqueId();
        downedPlayers.put(uuid, time); 
        player.setHealth(2.0); 
        
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 255, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 250, false, false));

        Bukkit.dispatchCommand(player, "lay");

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !downedPlayers.containsKey(uuid)) {
                    this.cancel(); return;
                }
                
                // Hastanede tedavi görürken kan kaybı sayacı durur
                if (ambulansTedavisi.containsKey(uuid)) return;

                int currentTime = downedPlayers.get(uuid);
                if (currentTime <= 0) {
                    downedPlayers.remove(uuid);
                    player.setHealth(0.0); 
                    this.cancel();
                } else {
                    downedPlayers.put(uuid, currentTime - 1);
                    if (currentTime % 60 == 0) { 
                        player.sendMessage(ChatColor.RED + "Kan kaybından ölmene " + (currentTime / 60) + " dakika kaldı...");
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (downedPlayers.containsKey(player.getUniqueId())) {
            // "/essentials:home" gibi önekli yazımlar da yakalanır
            if (YARALIYKEN_YASAK_KOMUTLAR.contains(MeslekSistemi.komutAdi(event.getMessage()))) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Ağır yaralıyken ışınlanma komutlarını kullanamazsın!");
            }
        }
    }

    private String getMedicalType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        
        if (meta.getPersistentDataContainer().has(medicalKey, PersistentDataType.BYTE)) {
            return meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        }
        
        // Eski sürümde etiketsiz satılmış eşyalar: isim + eklentinin yazdığı açıklama satırı birlikte aranır.
        // Örs sadece isim değiştirebildiği için bu kontrol taklit eşyaları dışarıda bırakır.
        if (meta.hasDisplayName() && meta.hasLore() && meta.getLore() != null && !meta.getLore().isEmpty()) {
            String name = ChatColor.stripColor(meta.getDisplayName()).toLowerCase();
            String aciklama = ChatColor.stripColor(meta.getLore().get(0));
            if (item.getType() == Material.PAPER && name.contains("bandaj") && aciklama.equals("Kanamayı durdurur.")) return "bandaj";
            if (item.getType() == Material.STICK && name.contains("atel") && aciklama.equals("Kırık bacağı onarır.")) return "atel";
            if (item.getType() == Material.SPECTRAL_ARROW && name.contains("adrenalin") && aciklama.equals("Baygın hastayı hayata döndürür.")) return "adrenalin";
        }
        return null;
    }

    @EventHandler
    public void onMedicalItemUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; 
        
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        String type = getMedicalType(item);
        
        if (type == null) return;
        UUID uuid = player.getUniqueId();

        if ("bandaj".equals(type)) {
            if (bleedingPlayers.containsKey(uuid)) {
                bleedingPlayers.remove(uuid);
                player.sendMessage(ChatColor.GREEN + "Bandaj sardın ve kanamanı durdurdun.");
                consumeItem(player, item);
            } else {
                player.sendMessage(ChatColor.YELLOW + "Şu an kanaman yok, bandaj kullanmana gerek yok.");
            }
        } else if ("atel".equals(type)) {
            if (brokenLegs.containsKey(uuid)) {
                brokenLegs.remove(uuid);
                player.removePotionEffect(PotionEffectType.SLOWNESS); 
                player.sendMessage(ChatColor.GREEN + "Kendi bacağına atel sardın ve kırığı onardın.");
                consumeItem(player, item);
            } else {
                player.sendMessage(ChatColor.YELLOW + "Bacağın kırık değil, atel sarmaya gerek yok.");
            }
        } else if ("adrenalin".equals(type)) {
            player.sendMessage(ChatColor.RED + "Adrenalin iğnesini kendi kendine uygulayamazsın!");
        }
    }
    
    // YENİ: Öncelik LOWEST yapıldı. Olayı ilk SaglikManager yakalar ve TicaretManager'ın menü açmasını engeller.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMedicalEntityUse(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            if (npc.getPersistentDataContainer().has(hastaneNpcKey, PersistentDataType.BYTE) ||
                (npc.getCustomName() != null && ChatColor.stripColor(npc.getCustomName()).contains("Acil Servis"))) {
                
                event.setCancelled(true);
                handleHospitalNPC(event.getPlayer());
                return;
            }
        }

        if (!(event.getRightClicked() instanceof Player)) return;
        Player doctor = event.getPlayer();
        Player target = (Player) event.getRightClicked();
        ItemStack item = doctor.getInventory().getItemInMainHand();

        String type = getMedicalType(item);

        if (type == null) {
            if (doctor.isSneaking() && downedPlayers.containsKey(target.getUniqueId()) && doctor.getPassengers().isEmpty()) {
                doctor.addPassenger(target);
                doctor.sendMessage(ChatColor.GREEN + "Yaralıyı sırtına aldın! Yere bırakmak için " + ChatColor.YELLOW + "Shift'e (Eğilme)" + ChatColor.GREEN + " bas.");
                target.sendMessage(ChatColor.YELLOW + doctor.getName() + " seni taşıyor.");
            }
            return;
        }

        boolean tedaviEdildi = false;
        // Prim kontrolü için tedaviden önce yarayı kimin açtığını al
        UUID yaralayan = yaralayanlar.get(target.getUniqueId());

        if ("atel".equals(type) && brokenLegs.containsKey(target.getUniqueId())) {
            brokenLegs.remove(target.getUniqueId());
            target.removePotionEffect(PotionEffectType.SLOWNESS); 
            target.sendMessage(ChatColor.GREEN + doctor.getName() + " bacağına atel sardı!");
            doctor.sendMessage(ChatColor.GREEN + "Hastanın kırığını onardın.");
            consumeItem(doctor, item);
            tedaviEdildi = true;
        } 
        else if ("bandaj".equals(type) && bleedingPlayers.containsKey(target.getUniqueId())) {
            bleedingPlayers.remove(target.getUniqueId());
            target.sendMessage(ChatColor.GREEN + doctor.getName() + " yarana bandaj sardı ve kanamanı durdurdu!");
            doctor.sendMessage(ChatColor.GREEN + "Hastanın kanamasını durdurdun.");
            consumeItem(doctor, item);
            tedaviEdildi = true;
        } 
        else if ("adrenalin".equals(type) && downedPlayers.containsKey(target.getUniqueId())) {
            downedPlayers.remove(target.getUniqueId());
            ambulansTedavisi.remove(target.getUniqueId());
            bleedingPlayers.remove(target.getUniqueId()); 
            
            target.removePotionEffect(PotionEffectType.BLINDNESS);
            target.removePotionEffect(PotionEffectType.SLOWNESS); 
            target.removePotionEffect(PotionEffectType.JUMP_BOOST); 
            target.setHealth(10.0); 
            
            if (target.getVehicle() != null) target.getVehicle().removePassenger(target);
            Bukkit.dispatchCommand(target, "sit"); 

            target.sendMessage(ChatColor.GREEN + "Adrenalin iğnesiyle hayata döndürüldün!");
            doctor.sendMessage(ChatColor.GREEN + "Hastayı başarıyla hayata döndürdün.");
            consumeItem(doctor, item);
            tedaviEdildi = true;
        }

        if (tedaviEdildi && !bleedingPlayers.containsKey(target.getUniqueId()) && !downedPlayers.containsKey(target.getUniqueId())) {
            yaralayanlar.remove(target.getUniqueId());
        }

        String meslek = plugin.oyuncuMeslekCache.getOrDefault(doctor.getUniqueId(), "vatandas");
        if (tedaviEdildi && meslek.equalsIgnoreCase("doktor")) {
            long simdi = System.currentTimeMillis();
            boolean kendiYarasi = doctor.getUniqueId().equals(yaralayan);
            boolean beklemede = simdi - sonPrimZamani.getOrDefault(target.getUniqueId(), 0L) < PRIM_BEKLEME_MS;
            if (kendiYarasi) {
                doctor.sendMessage(ChatColor.GRAY + "Kendi açtığınız yarayı tedavi ettiğiniz için devlet primi ödenmedi.");
            } else if (!beklemede) {
                double primMiktari = type.equals("adrenalin") ? 300.0 : 50.0;
                
                if (plugin.kasadanParaCek(primMiktari)) {
                    sonPrimZamani.put(target.getUniqueId(), simdi);
                    double docHesap = plugin.bankaHesaplari.getOrDefault(doctor.getUniqueId(), 0.0);
                    plugin.bankaHesaplari.put(doctor.getUniqueId(), docHesap + primMiktari);
                    plugin.veriKaydet();
                    
                    doctor.sendMessage(ChatColor.AQUA + "Devlet, acil müdahaleniz için banka hesabınıza $" + primMiktari + " prim yatırdı!");
                }
            }
        }
    }

    private void handleHospitalNPC(Player player) {
        boolean doktorVarMi = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String m = plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "");
            if (m.equalsIgnoreCase("doktor")) {
                doktorVarMi = true; break;
            }
        }

        if (doktorVarMi) {
            player.sendMessage(ChatColor.RED + "Şu an şehirde aktif bir doktor bulunuyor! Lütfen tedavi için ona başvurun.");
            return;
        }

        // Tedavi edilecek bir sorun yoksa ücret alınmaz
        UUID hastaId = player.getUniqueId();
        AttributeInstance maxCanBilgisi = player.getAttribute(Attribute.MAX_HEALTH);
        double maxCanDegeri = maxCanBilgisi != null ? maxCanBilgisi.getValue() : 20.0;
        boolean sorunVar = brokenLegs.containsKey(hastaId) || bleedingPlayers.containsKey(hastaId)
                || downedPlayers.containsKey(hastaId) || player.getHealth() < maxCanDegeri;
        if (!sorunVar) {
            player.sendMessage(ChatColor.GREEN + "Acil Servis: Muayene edildiniz, herhangi bir sağlık sorununuz yok. Ücret alınmadı.");
            return;
        }

        double tedaviUcreti = 350.0;
        if (!tedaviUcretiOde(player, tedaviUcreti)) return;

        tamTedavi(player);
        
        player.sendMessage(ChatColor.GREEN + "Paranızı ödediniz ve Acil Serviste tamamen tedavi edildiniz!");
    }

    private void consumeItem(Player player, ItemStack item) {
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (downedPlayers.containsKey(uuid)) {
            event.setCancelled(true);
            return;
        }

        if (event.getItem().getType() == Material.MILK_BUCKET && brokenLegs.containsKey(uuid)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Bacağın kırıkken süt içmek seni iyileştirmez!");
        }
    }

    // Yaralı yerde yatarken eğilip (shift) ayağa kalkamasın; sadece tedaviyle kalkar
    @EventHandler(priority = EventPriority.LOWEST)
    public void onYaraliEgilme(PlayerToggleSneakEvent event) {
        Player p = event.getPlayer();
        if (event.isSneaking() && downedPlayers.containsKey(p.getUniqueId()) && p.getVehicle() == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking() && !player.getPassengers().isEmpty()) {
            player.eject();
            player.sendMessage(ChatColor.YELLOW + "Sırtındaki yaralıyı yere indirdin.");
        }
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player && event.getDismounted() instanceof Player) {
            Player passenger = (Player) event.getEntity();
            
            if (downedPlayers.containsKey(passenger.getUniqueId())) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (passenger.isOnline() && downedPlayers.containsKey(passenger.getUniqueId())) {
                        Bukkit.dispatchCommand(passenger, "lay");
                    }
                }, 5L); 
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.getVehicle() != null) player.getVehicle().removePassenger(player);
        if (!player.getPassengers().isEmpty()) player.eject();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (brokenLegs.containsKey(uuid)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 2, false, false));
            player.sendMessage(ChatColor.RED + "Bacağın hâlâ kırık! Tedavi olana kadar yavaş yürümek zorundasın.");
        }
        
        if (downedPlayers.containsKey(uuid)) {
            player.sendMessage(ChatColor.DARK_RED + "Ağır yaralı durumun devam ediyor! Acilen doktor bulmalısın.");
            applyDownedState(player, downedPlayers.get(uuid));
            if (ambulansTedavisi.containsKey(uuid)) {
                player.sendMessage(ChatColor.AQUA + "Hastanedeki tedavin devam ediyor, " + ambulansTedavisi.get(uuid) + " saniye kaldı.");
                ambulansTedavisiTakip(player);
            }
        }
        
        // Ağır yaralı olmayan oyuncuda artık geçerli bir tedavi kaydı kalmaz
        if (!downedPlayers.containsKey(uuid)) ambulansTedavisi.remove(uuid);

        if (bleedingPlayers.containsKey(uuid)) {
            player.sendMessage(ChatColor.RED + "Kanaman durmamış! Hızla kan kaybetmeye devam ediyorsun.");
        }
    }

    public void veriKaydetSaglik() {
        List<String> locs = new ArrayList<>(yuklenemeyenYataklar);
        for (Location loc : hastaneYataklari) {
            if (loc.getWorld() == null) continue;
            locs.add(loc.getWorld().getName() + ";" + loc.getX() + ";" + loc.getY() + ";" + loc.getZ() + ";" + loc.getYaw() + ";" + loc.getPitch());
        }
        plugin.getConfig().set("hastaneyataklari", locs);
        
        List<String> bleedingList = new ArrayList<>();
        for (UUID u : bleedingPlayers.keySet()) bleedingList.add(u.toString());
        plugin.getConfig().set("saglik.bleeding", bleedingList);
        
        List<String> brokenList = new ArrayList<>();
        for (UUID u : brokenLegs.keySet()) brokenList.add(u.toString());
        plugin.getConfig().set("saglik.broken", brokenList);
        
        plugin.getConfig().set("saglik.downed", null);
        for (UUID u : downedPlayers.keySet()) {
            plugin.getConfig().set("saglik.downed." + u.toString(), downedPlayers.get(u));
        }

        plugin.getConfig().set("saglik.tedavide", null);
        for (UUID u : ambulansTedavisi.keySet()) {
            plugin.getConfig().set("saglik.tedavide." + u.toString(), ambulansTedavisi.get(u));
        }

        plugin.saveConfig();
    }

    public void veriYukleSaglik() {
        if (plugin.getConfig().contains("hastaneyataklari")) {
            List<String> locs = plugin.getConfig().getStringList("hastaneyataklari");
            for (String s : locs) {
                String[] split = s.split(";");
                if (split.length == 6) {
                    org.bukkit.World w = Bukkit.getWorld(split[0]);
                    if (w != null) {
                        hastaneYataklari.add(new Location(w, Double.parseDouble(split[1]), Double.parseDouble(split[2]), Double.parseDouble(split[3]), Float.parseFloat(split[4]), Float.parseFloat(split[5])));
                    } else {
                        yuklenemeyenYataklar.add(s);
                    }
                }
            }
        }
        
        if (plugin.getConfig().contains("saglik.bleeding")) {
            for (String s : plugin.getConfig().getStringList("saglik.bleeding")) {
                bleedingPlayers.put(UUID.fromString(s), true);
            }
        }
        if (plugin.getConfig().contains("saglik.broken")) {
            for (String s : plugin.getConfig().getStringList("saglik.broken")) {
                brokenLegs.put(UUID.fromString(s), true);
            }
        }
        if (plugin.getConfig().contains("saglik.downed")) {
            for (String s : plugin.getConfig().getConfigurationSection("saglik.downed").getKeys(false)) {
                downedPlayers.put(UUID.fromString(s), plugin.getConfig().getInt("saglik.downed." + s));
            }
        }
        if (plugin.getConfig().contains("saglik.tedavide")) {
            for (String s : plugin.getConfig().getConfigurationSection("saglik.tedavide").getKeys(false)) {
                ambulansTedavisi.put(UUID.fromString(s), plugin.getConfig().getInt("saglik.tedavide." + s));
            }
        }
    }

    private ItemStack createBandaj() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "Bandaj");
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Kanamayı durdurur."));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(medicalKey, PersistentDataType.BYTE, (byte) 1);
        data.set(typeKey, PersistentDataType.STRING, "bandaj");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createAtel() {
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Atel");
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Kırık bacağı onarır."));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(medicalKey, PersistentDataType.BYTE, (byte) 1);
        data.set(typeKey, PersistentDataType.STRING, "atel");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createAdrenalin() {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "Adrenalin İğnesi");
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Baygın hastayı hayata döndürür."));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(medicalKey, PersistentDataType.BYTE, (byte) 1);
        data.set(typeKey, PersistentDataType.STRING, "adrenalin");
        item.setItemMeta(meta);
        return item;
    }
}