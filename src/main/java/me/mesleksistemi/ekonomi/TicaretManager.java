package me.mesleksistemi.ekonomi;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;


public class TicaretManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    // Eski sürümün köylü geneli bekleme anahtarı (sadece taşıma için okunur)
    private final NamespacedKey cooldownKey;
    // Her ürünün kendi stok yenileme zamanı (tarif sırasıyla, 0 = bekleme yok)
    private final NamespacedKey stokYenilemeKey;
    private final NamespacedKey ehliyetKey;
    private final NamespacedKey adliyeKey;
    private final NamespacedKey sikayetKey;
    // YENİ: SaglikManager'daki hastane NPC'sinin anahtarı (aynı isim = aynı anahtar)
    private final NamespacedKey hastaneKey;
    // Emlak Ofisi (KiraManager) ve Madenci/Oduncu Pazarı (ToptanciManager) NPC'leri
    private final NamespacedKey emlakciKey, madenciKey, oduncuKey;
    // YENİ: Meslek kilidi işareti (köylünün teklifleri sabitlendiğinde konur)
    private final NamespacedKey meslekKilitKey;
    
    private final double ZUMRUT_TABAN_KURU = 50.0; 
    private final int MAX_ALIM_SINIRI = 8; // YENİ: Kota 4'ten 8'e çıkarıldı
    private final long STOK_YENILEME_TICK = 48000L; // YENİ: 2 Minecraft Günü (Gerçek hayatta 40 dakika)

    // YENİ: Ticarette oyuncuya verilen tecrübe (vanilla ile aynı: 3-6 xp, köylü seviye atlarsa +5)
    private final int OYUNCU_XP_TABAN = 3;
    private final int OYUNCU_XP_RASTGELE = 4;      // 0-3 arası ek, yani toplam 3-6
    private final int OYUNCU_XP_SEVIYE_BONUSU = 5;
    
    // YENİ: Bu bloklardan biri kırılırsa o bloğa bağlı köylünün meslek kilidi açılır
    private static final Set<Material> MESLEK_BLOKLARI = EnumSet.of(
            Material.LECTERN, Material.BARREL, Material.SMOKER, Material.BLAST_FURNACE,
            Material.CARTOGRAPHY_TABLE, Material.BREWING_STAND, Material.COMPOSTER,
            Material.FLETCHING_TABLE, Material.GRINDSTONE, Material.LOOM,
            Material.SMITHING_TABLE, Material.STONECUTTER, Material.CAULDRON,
            Material.WATER_CAULDRON, Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON
    );
    
    // YENİ: Kampanya (/indirim). Oran 0-1 arası, bitiş gerçek zaman (ms). Config'de saklanır.
    private static final String KAMPANYA_YOLU = "ticaret_kampanya";
    private static final int VARSAYILAN_KAMPANYA_YUZDE = 20;
    private static final int VARSAYILAN_KAMPANYA_DAKIKA = 60;
    private static final int MAX_KAMPANYA_YUZDE = 90;
    private double kampanyaOrani = 0.0;
    private long kampanyaBitis = 0L;

    // YENİ: Artık Villager yerine AbstractVillager (köylü + gezgin tüccar)
    private final HashMap<UUID, AbstractVillager> islemdekiKoyluler = new HashMap<>();

    public TicaretManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.cooldownKey = new NamespacedKey(plugin, "villager_restock_cooldown");
        this.stokYenilemeKey = new NamespacedKey(plugin, "urun_stok_yenileme");
        this.ehliyetKey = new NamespacedKey(plugin, "ehliyet_npc");
        this.adliyeKey = new NamespacedKey(plugin, "adliye_npc");
        this.sikayetKey = new NamespacedKey(plugin, "sikayet_npc");
        this.hastaneKey = new NamespacedKey(plugin, "hastane_npc");
        this.emlakciKey = new NamespacedKey(plugin, "emlakci_npc");
        this.madenciKey = new NamespacedKey(plugin, "madenci_npc");
        this.oduncuKey = new NamespacedKey(plugin, "oduncu_npc");
        this.meslekKilitKey = new NamespacedKey(plugin, "meslek_kilit");
        this.kampanyaOrani = plugin.getConfig().getDouble(KAMPANYA_YOLU + ".oran", 0.0);
        this.kampanyaBitis = plugin.getConfig().getLong(KAMPANYA_YOLU + ".bitis", 0L);
    }

    // ------------------------------------------------------------------
    // YENİ: KAMPANYA (/indirim <durum|bitir|baslat [yüzde] [dakika]>)
    // ------------------------------------------------------------------
    private boolean kampanyaAktif() {
        return kampanyaOrani > 0 && System.currentTimeMillis() < kampanyaBitis;
    }

    private double getKampanyaIndirimi() {
        return kampanyaAktif() ? kampanyaOrani : 0.0;
    }

    // Sadakat ve kampanya indirimleri üst üste uygulanır (toplanmaz, %100'ü geçemez)
    private double getToplamIndirim(double sadakatIndirimOrani) {
        return 1.0 - (1.0 - sadakatIndirimOrani) * (1.0 - getKampanyaIndirimi());
    }

    private void kampanyaKaydet() {
        plugin.getConfig().set(KAMPANYA_YOLU + ".oran", kampanyaOrani);
        plugin.getConfig().set(KAMPANYA_YOLU + ".bitis", kampanyaBitis);
        plugin.saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ticaret.admin")) {
            sender.sendMessage(ChatColor.RED + "Bu komutu kullanmak için yetkiniz yok.");
            return true;
        }
        if (args.length == 0) return false;

        switch (args[0].toLowerCase()) {
            case "durum": {
                if (kampanyaAktif()) {
                    long kalanDakika = Math.max(1, (kampanyaBitis - System.currentTimeMillis()) / 60000L);
                    sender.sendMessage(ChatColor.GREEN + "Kampanya aktif: %" + Math.round(kampanyaOrani * 100)
                            + " indirim, kalan süre " + kalanDakika + " dakika.");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Şu anda aktif bir tüccar kampanyası yok.");
                }
                return true;
            }
            case "bitir": {
                if (!kampanyaAktif()) {
                    sender.sendMessage(ChatColor.YELLOW + "Zaten aktif bir kampanya yok.");
                    return true;
                }
                kampanyaOrani = 0.0;
                kampanyaBitis = 0L;
                kampanyaKaydet();
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Ticaret] " + ChatColor.YELLOW + "Tüccar kampanyası sona erdi.");
                return true;
            }
            case "baslat": {
                int yuzde = VARSAYILAN_KAMPANYA_YUZDE;
                int dakika = VARSAYILAN_KAMPANYA_DAKIKA;
                try {
                    if (args.length >= 2) yuzde = Integer.parseInt(args[1].replace("%", ""));
                    if (args.length >= 3) dakika = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Kullanım: /indirim baslat [yüzde] [dakika]");
                    return true;
                }
                if (yuzde < 1 || yuzde > MAX_KAMPANYA_YUZDE || dakika < 1) {
                    sender.sendMessage(ChatColor.RED + "Yüzde 1-" + MAX_KAMPANYA_YUZDE + " arası, süre en az 1 dakika olmalı.");
                    return true;
                }
                kampanyaOrani = yuzde / 100.0;
                kampanyaBitis = System.currentTimeMillis() + dakika * 60000L;
                kampanyaKaydet();
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Ticaret] " + ChatColor.GREEN + "Tüm tüccarlarda %" + yuzde
                        + " indirim kampanyası başladı! (" + dakika + " dakika)");
                return true;
            }
            default:
                return false;
        }
    }

    // YENİ: Oyuncunun bu köylü ile yaptığı toplam ticaret sayısı
    private int getToplamTicaret(Player player, AbstractVillager villager) {
        return plugin.getConfig().getInt("sadakat." + player.getUniqueId() + "." + villager.getUniqueId(), 0);
    }

    // YENİ: Sadakat Seviyesi Hesaplama (gezgin tüccarda sadakat yok, hep 1)
    private int getSadakatSeviyesi(Player player, AbstractVillager villager) {
        if (!(villager instanceof Villager)) return 1;
        int trades = getToplamTicaret(player, villager);
        if (trades >= 100) return 5;
        if (trades >= 50) return 4;
        if (trades >= 25) return 3;
        if (trades >= 10) return 2;
        return 1;
    }

    // YENİ: Sadakat İndirim Oranı
    private double getSadakatIndirimi(int seviye) {
        switch(seviye) {
            case 5: return 0.25; // %25 İndirim
            case 4: return 0.15; // %15 İndirim
            case 3: return 0.10; // %10 İndirim
            case 2: return 0.05; // %5 İndirim
            default: return 0.0;
        }
    }

    // YENİ: Bir sonraki seviye için gereken toplam ticaret (-1 = en üst seviye)
    private int getSonrakiEsik(int seviye) {
        switch(seviye) {
            case 1: return 10;
            case 2: return 25;
            case 3: return 50;
            case 4: return 100;
            default: return -1;
        }
    }

    // YENİ: Mevcut seviyenin başlangıç eşiği (ilerleme çubuğu için)
    private int getOncekiEsik(int seviye) {
        switch(seviye) {
            case 2: return 10;
            case 3: return 25;
            case 4: return 50;
            case 5: return 100;
            default: return 0;
        }
    }

    // YENİ: Köylü tecrübesine göre seviye (vanilla eşikleri: 10 / 70 / 150 / 250)
    private int xpdenSeviye(int xp) {
        if (xp >= 250) return 5;
        if (xp >= 150) return 4;
        if (xp >= 70) return 3;
        if (xp >= 10) return 2;
        return 1;
    }

    // YENİ: Köylü seviye adları
    private String getKoyluSeviyeAdi(int seviye) {
        switch(seviye) {
            case 5: return "Usta";
            case 4: return "Uzman";
            case 3: return "Kalfa";
            case 2: return "Çırak";
            default: return "Çaylak";
        }
    }

    // YENİ: Hastane/banka/nüfus/tapu/ehliyet/adliye/şikayet NPC'leri ve Citizens NPC'leri ticaret menüsünden muaf
    private boolean ozelNpcMi(AbstractVillager villager) {
        if (villager.hasMetadata("NPC")) return true;

        PersistentDataContainer data = villager.getPersistentDataContainer();
        if (data.has(plugin.npcKey, PersistentDataType.BYTE) ||
            data.has(plugin.bankaNpcKey, PersistentDataType.BYTE) ||
            data.has(plugin.nufusNpcKey, PersistentDataType.BYTE) ||
            data.has(plugin.tapuNpcKey, PersistentDataType.BYTE) ||
            data.has(ehliyetKey, PersistentDataType.BYTE) ||
            data.has(adliyeKey, PersistentDataType.BYTE) ||
            data.has(sikayetKey, PersistentDataType.BYTE) ||
            data.has(hastaneKey, PersistentDataType.BYTE) ||
            data.has(emlakciKey, PersistentDataType.BYTE) ||
            data.has(madenciKey, PersistentDataType.BYTE) ||
            data.has(oduncuKey, PersistentDataType.BYTE)) {
            return true;
        }

        // YENİ: Eski kurulmuş hastane NPC'lerinde anahtar yoksa isimden de yakala (SaglikManager ile aynı kontrol)
        String ad = villager.getCustomName();
        return ad != null && ChatColor.stripColor(ad).contains("Acil Servis");
    }

    // ------------------------------------------------------------------
    // YENİ: MESLEK KİLİDİ
    // Vanilla'da hiç ticaret yapılmamış (xp = 0, seviye 1) bir köylü iş bloğunu
    // kaybederse mesleğini sıfırlar ve yeni meslekle yeni teklifler çıkar.
    // Köylüye mesleği aldığı anda 1 xp vererek bu sıfırlanmayı engelliyoruz (iş bloğu
    // yerindeyken teklifler sabit kalır). İş bloğu herhangi bir şekilde yok olursa
    // (kırma, patlama, yanma...) köylü, ticaret yapılmış olsa bile anında işsiz kalır
    // (vanilla'dan farklı: ticaret yapılmış köylü de mesleğini, seviyesini ve tekliflerini kaybeder).
    // ------------------------------------------------------------------
    private void meslekKilitle(Villager villager) {
        meslekKilitle(villager, true);
    }

    /** isYeriKontrol: iş bloğu kaybolduysa kilitlemek yerine mesleği sıfırla. */
    private void meslekKilitle(Villager villager, boolean isYeriKontrol) {
        Villager.Profession meslek = villager.getProfession();
        if (meslek == Villager.Profession.NONE || meslek == Villager.Profession.NITWIT) return;
        if (ozelNpcMi(villager) || !villager.hasAI()) return; // NPC'ler (AI kapalı) meslek sisteminin dışında

        if (isYeriKontrol && !isYeriYerinde(villager)) {
            meslegiSifirla(villager);
            return;
        }
        if (villager.getVillagerExperience() > 0) return; // Ticaret yapılmış ya da zaten kilitli

        villager.getRecipes(); // Teklifleri şimdi oluşturtup sabitliyoruz
        villager.setVillagerExperience(1);
        villager.getPersistentDataContainer().set(meslekKilitKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** Köylünün kayıtlı iş bloğu hâlâ yerinde mi? Bloğun chunk'ı yüklü değilse yerinde sayılır (yüklenmez). */
    private boolean isYeriYerinde(Villager villager) {
        Location site = villager.getMemory(MemoryKey.JOB_SITE);
        if (site == null || site.getWorld() == null) return false;
        if (!site.getWorld().isChunkLoaded(site.getBlockX() >> 4, site.getBlockZ() >> 4)) return true;
        return MESLEK_BLOKLARI.contains(site.getBlock().getType());
    }

    /** Köylüyü işsiz bırakır (seviye ve teklifler dahil); yakında boş iş bloğu varsa vanilla'daki gibi yeni meslek alır. */
    private void meslegiSifirla(Villager villager) {
        villager.getPersistentDataContainer().remove(meslekKilitKey);
        villager.getPersistentDataContainer().remove(stokYenilemeKey);
        villager.setRecipes(new ArrayList<>());
        villager.setVillagerExperience(0);
        villager.setVillagerLevel(1);
        villager.setProfession(Villager.Profession.NONE);
    }

    // Yeni meslek edinildiği anda kilitle (meslek tam atandıktan sonra, 1 tick sonra).
    // İş bloğu hafızası bu anda yeni yazıldığı için burada iş yeri kontrolü yapılmaz.
    @EventHandler(ignoreCancelled = true)
    public void onMeslekEdinme(VillagerCareerChangeEvent event) {
        if (event.getReason() != VillagerCareerChangeEvent.ChangeReason.EMPLOYED) return;
        Villager villager = event.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (villager.isValid()) meslekKilitle(villager, false);
        });
    }

    // Eklentiden önce meslek edinmiş köylüler chunk yüklenince kilitlenir
    // (iş bloğu yokken kilitlenmez, mesleği sıfırlanır)
    @EventHandler
    public void onKoyluYuklendi(EntitiesLoadEvent event) {
        List<Villager> liste = new ArrayList<>();
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Villager) liste.add((Villager) entity);
        }
        if (liste.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Villager v : liste) {
                if (v.isValid()) meslekKilitle(v);
            }
        });
    }

    // İş bloğu (örn. kürsü) yok olunca ona bağlı köylüler (ticaret yapılmış olsa bile) anında işsiz kalır
    private void isBloguYokOldu(Block block) {
        if (!MESLEK_BLOKLARI.contains(block.getType())) return;
        Location loc = block.getLocation();
        for (Entity entity : block.getWorld().getNearbyEntities(loc, 48, 24, 48)) {
            if (!(entity instanceof Villager)) continue;
            Villager villager = (Villager) entity;

            Location site = villager.getMemory(MemoryKey.JOB_SITE);
            if (site == null || site.getWorld() == null || !site.getWorld().equals(loc.getWorld())) continue;
            if (site.getBlockX() == loc.getBlockX() && site.getBlockY() == loc.getBlockY() && site.getBlockZ() == loc.getBlockZ()) {
                if (ozelNpcMi(villager) || !villager.hasAI()) continue;
                meslegiSifirla(villager);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMeslekBloguKirildi(BlockBreakEvent event) {
        isBloguYokOldu(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMeslekBloguYandi(BlockBurnEvent event) {
        isBloguYokOldu(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMeslekBloguPatladi(EntityExplodeEvent event) {
        for (Block b : event.blockList()) isBloguYokOldu(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMeslekBloguPatladi(BlockExplodeEvent event) {
        for (Block b : event.blockList()) isBloguYokOldu(b);
    }

    @EventHandler
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; 
        
        // YENİ: Sadece köylü değil, gezgin tüccar (lamalı) da yakalanıyor
        if (event.getRightClicked() instanceof AbstractVillager) {
            AbstractVillager villager = (AbstractVillager) event.getRightClicked();
            
            // YENİ: Tüm özel NPC kontrolleri (hastane NPC'si dahil) tek yerde
            if (ozelNpcMi(villager)) return;

            if (!villager.isAdult() || villager.getRecipes().isEmpty()) return;

            event.setCancelled(true); 
            openTradeGUI(event.getPlayer(), villager);
        }
    }

    private void openTradeGUI(Player player, AbstractVillager villager) {
        // YENİ: Köylü bu menü ile ilk kez görüldüyse, o anki tekliflerle meslek kilidi devreye girer
        if (villager instanceof Villager) {
            meslekKilitle((Villager) villager);
        }

        // Stok her ürün için ayrı yenilenir: süresi dolan tükenmiş ürünlerin stoğu sıfırlanır
        long currentTime = villager.getWorld().getFullTime();
        List<MerchantRecipe> tarifler = new ArrayList<>(villager.getRecipes());
        long[] stokZamani = stokZamanlari(villager, tarifler.size());
        boolean tarifDegisti = false;
        for (int i = 0; i < tarifler.size(); i++) {
            MerchantRecipe r = tarifler.get(i);
            if (r.getUses() < MAX_ALIM_SINIRI) continue;
            if (stokZamani[i] == 0) {
                stokZamani[i] = currentTime + STOK_YENILEME_TICK; // Süresi kaydedilmemiş tükenmiş ürün
            } else if (currentTime >= stokZamani[i]) {
                r.setUses(0);
                stokZamani[i] = 0;
                tarifDegisti = true;
            }
        }
        if (tarifDegisti) villager.setRecipes(tarifler);
        stokZamanlariniKaydet(villager, stokZamani);

        List<MerchantRecipe> recipes = villager.getRecipes();
        int size = ((recipes.size() / 9) + 1) * 9;
        if (size < 9) size = 9;

        Inventory gui = Bukkit.createInventory(null, size, ChatColor.DARK_BLUE + "Tüccar Menüsü");

        // YENİ: Oyuncunun bu köylüdeki sadakatini çekiyoruz
        int sadakatSeviyesi = getSadakatSeviyesi(player, villager);
        double sadakatIndirimOrani = getSadakatIndirimi(sadakatSeviyesi);

        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            ItemStack result = recipe.getResult().clone();
            ItemMeta meta = result.getItemMeta();
            
            List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + "----------------------");
            
            if (recipe.getUses() >= MAX_ALIM_SINIRI) {
                lore.add(ChatColor.RED + "X Stok Tükendi!");
                if (i < stokZamani.length && stokZamani[i] > currentTime) {
                    long kalanGun = ((stokZamani[i] - currentTime) / 24000L) + 1;
                    lore.add(ChatColor.DARK_RED + "Tedarik icin " + kalanGun + " MC gunu lazim.");
                }
            } else {
                lore.add(ChatColor.GOLD + "Maliyet:");
                double zümrütDegeri = 0;

                for (ItemStack ingredient : recipe.getIngredients()) {
                    if (ingredient.getType() == Material.EMERALD) {
                        zümrütDegeri += ingredient.getAmount() * ZUMRUT_TABAN_KURU;
                    } else if (ingredient.getType() == Material.EMERALD_BLOCK) {
                        zümrütDegeri += (ingredient.getAmount() * 9) * ZUMRUT_TABAN_KURU;
                    } else {
                        lore.add(ChatColor.GRAY + "- " + ingredient.getAmount() + "x " + formatName(ingredient.getType().name()));
                    }
                }

                double basePrice = calculateDinamikFiyat(result, zümrütDegeri);
                double toplamIndirim = getToplamIndirim(sadakatIndirimOrani);
                double finalPrice = basePrice * (1.0 - toplamIndirim);

                if (finalPrice > 0) {
                    if (kampanyaAktif()) {
                        lore.add(ChatColor.LIGHT_PURPLE + "Kampanya: " + ChatColor.GREEN + "%" + Math.round(kampanyaOrani * 100) + " İndirim");
                    }
                    if (sadakatSeviyesi > 1 || kampanyaAktif()) {
                        if (sadakatSeviyesi > 1) {
                            lore.add(ChatColor.AQUA + "Sadakat Seviyesi: " + sadakatSeviyesi + " " + ChatColor.GREEN + "(%" + (int)(sadakatIndirimOrani * 100) + " İndirim)");
                        }
                        lore.add(ChatColor.GRAY + "Eski Fiyat: " + ChatColor.STRIKETHROUGH + "$" + formatAmount(basePrice));
                        lore.add(ChatColor.GREEN + "- $" + formatAmount(finalPrice) + " Nakit");
                    } else {
                        lore.add(ChatColor.GREEN + "- $" + formatAmount(finalPrice) + " Nakit");
                    }
                }
                lore.add("");
                lore.add(ChatColor.YELLOW + "► Satın Almak İçin Tıkla");
            }

            if (meta != null) {
                meta.setLore(lore);
                result.setItemMeta(meta);
            }
            gui.setItem(i, result);
        }

        // YENİ: Sadakat bilgi kartı. Son slota konur, tariflerle asla çakışmaz (tıklanınca bir şey olmaz).
        gui.setItem(size - 1, createSadakatBilgi(player, villager, sadakatSeviyesi, sadakatIndirimOrani));

        player.openInventory(gui);
        islemdekiKoyluler.put(player.getUniqueId(), villager);
    }

    // YENİ: Menüdeki sadakat bilgi kartı
    private ItemStack createSadakatBilgi(Player player, AbstractVillager villager, int seviye, double indirimOrani) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "----------------------");

        if (!(villager instanceof Villager)) {
            meta.setDisplayName(ChatColor.AQUA + "Gezgin Tüccar");
            lore.add(ChatColor.GRAY + "Sadakat sistemi gezgin");
            lore.add(ChatColor.GRAY + "tüccarlarda geçerli değildir.");
        } else {
            int toplam = getToplamTicaret(player, villager);
            meta.setDisplayName(ChatColor.AQUA + "Sadakat Seviyesi: " + ChatColor.GOLD + seviye + ChatColor.GRAY + "/5");
            lore.add(ChatColor.GRAY + "Bu tüccarla ticaret: " + ChatColor.WHITE + toplam);
            lore.add(ChatColor.GRAY + "Mevcut indirim: " + ChatColor.GREEN + "%" + (int) (indirimOrani * 100));
            int koyluSeviyesi = ((Villager) villager).getVillagerLevel();
            lore.add(ChatColor.GRAY + "Tüccar seviyesi: " + ChatColor.YELLOW + getKoyluSeviyeAdi(koyluSeviyesi)
                    + ChatColor.GRAY + " (" + koyluSeviyesi + "/5)");
            lore.add("");

            int sonraki = getSonrakiEsik(seviye);
            if (sonraki > 0) {
                int onceki = getOncekiEsik(seviye);
                double oran = (double) (toplam - onceki) / (sonraki - onceki);
                int dolu = Math.max(0, Math.min(10, (int) Math.round(oran * 10)));
                lore.add(ChatColor.GREEN + "■".repeat(dolu) + ChatColor.DARK_GRAY + "■".repeat(10 - dolu));
                lore.add(ChatColor.GRAY + "Sonraki seviye için " + ChatColor.YELLOW + (sonraki - toplam) + ChatColor.GRAY + " ticaret daha.");
            } else {
                lore.add(ChatColor.GOLD + "En üst seviyedesin!");
            }
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(ChatColor.DARK_BLUE + "Tüccar Menüsü")) return;
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        AbstractVillager villager = islemdekiKoyluler.get(player.getUniqueId());

        if (villager == null || slot < 0 || slot >= villager.getRecipes().size()) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        MerchantRecipe recipe = villager.getRecipes().get(slot);

        // Sadece bu ürünün stoğu tükendiyse alınamaz; diğer ürünler satılmaya devam eder
        if (recipe.getUses() >= MAX_ALIM_SINIRI) {
            player.sendMessage(ChatColor.RED + "Bu ürünün stoğu tükendi! Yeni kervan gelene kadar bu ürün satılmıyor. (2 Gün Kuralı)");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        double zumrutDegeri = 0;
        List<ItemStack> requiredItems = new ArrayList<>();

        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient.getType() == Material.EMERALD) {
                zumrutDegeri += ingredient.getAmount() * ZUMRUT_TABAN_KURU;
            } else if (ingredient.getType() == Material.EMERALD_BLOCK) {
                zumrutDegeri += (ingredient.getAmount() * 9) * ZUMRUT_TABAN_KURU;
            } else {
                requiredItems.add(ingredient);
            }
        }

        // YENİ: Sadakat indirimini satın alma işlemine de yansıtıyoruz
        int sadakatSeviyesi = getSadakatSeviyesi(player, villager);
        double sadakatIndirimOrani = getSadakatIndirimi(sadakatSeviyesi);
        double basePrice = calculateDinamikFiyat(recipe.getResult(), zumrutDegeri);
        double finalPrice = basePrice * (1.0 - getToplamIndirim(sadakatIndirimOrani));

        for (ItemStack req : requiredItems) {
            if (!player.getInventory().containsAtLeast(req, req.getAmount())) {
                player.sendMessage(ChatColor.RED + "Gerekli eşyaya sahip değilsin: " + req.getAmount() + "x " + formatName(req.getType().name()));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
        }

        if (finalPrice > 0) {
            if (!consumePlayerMoney(player, finalPrice)) {
                return; 
            }
        }

        for (ItemStack req : requiredItems) {
            plugin.removeItemFromInventory(player.getInventory(), req.getType(), req.getAmount());
        }

        player.getInventory().addItem(recipe.getResult().clone());

        recipe.setUses(recipe.getUses() + 1);
        // YENİ: Köylü tecrübesi sadece gerçek köylülerde var (gezgin tüccarda yok)
        if (villager instanceof Villager) {
            Villager gercekKoylu = (Villager) villager;
            gercekKoylu.setVillagerExperience(gercekKoylu.getVillagerExperience() + recipe.getVillagerExperience());
        }
        
        List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
        recipes.set(slot, recipe);
        villager.setRecipes(recipes);

        // YENİ: Köylü seviye atlama. Tarifler kaydedildikten sonra yapılır ki increaseLevel yeni tarifleri ekleyebilsin.
        // setVillagerLevel yeni tarifleri açmaz, increaseLevel açar.
        boolean seviyeAtladi = false;
        int yeniKoyluSeviyesi = 0;
        if (villager instanceof Villager) {
            Villager seviyeKoylusu = (Villager) villager;
            int mevcutSeviye = seviyeKoylusu.getVillagerLevel();
            int hedefSeviye = xpdenSeviye(seviyeKoylusu.getVillagerExperience());
            if (hedefSeviye > mevcutSeviye) {
                seviyeKoylusu.increaseLevel(hedefSeviye - mevcutSeviye);
                yeniKoyluSeviyesi = seviyeKoylusu.getVillagerLevel();
                seviyeAtladi = yeniKoyluSeviyesi > mevcutSeviye;
            }
        }

        // YENİ: Oyuncuya tecrübe (vanilla gibi 3-6 xp, köylü seviye atlarsa +5), tarif ödül veriyorsa
        if (recipe.hasExperienceReward()) {
            int oyuncuXp = OYUNCU_XP_TABAN + ThreadLocalRandom.current().nextInt(OYUNCU_XP_RASTGELE);
            if (seviyeAtladi) oyuncuXp += OYUNCU_XP_SEVIYE_BONUSU;
            final int verilecekXp = oyuncuXp;
            villager.getWorld().spawn(villager.getLocation().add(0, 0.5, 0), ExperienceOrb.class, orb -> orb.setExperience(verilecekXp));
        }

        // YENİ: Başarılı ticarette sadakat puanı artışı (gezgin tüccarda sadakat tutulmaz, config şişmesin)
        boolean sadakatGecerli = villager instanceof Villager;
        int newTrades = 0;
        if (sadakatGecerli) {
            int currentTrades = plugin.getConfig().getInt("sadakat." + player.getUniqueId() + "." + villager.getUniqueId(), 0);
            newTrades = currentTrades + 1;
            plugin.getConfig().set("sadakat." + player.getUniqueId() + "." + villager.getUniqueId(), newTrades);
            plugin.saveConfig();
        }

        if (recipe.getUses() >= MAX_ALIM_SINIRI) {
            long[] stokZamani = stokZamanlari(villager, villager.getRecipes().size());
            stokZamani[slot] = villager.getWorld().getFullTime() + STOK_YENILEME_TICK;
            stokZamanlariniKaydet(villager, stokZamani);
            player.sendMessage(ChatColor.DARK_RED + "Dikkat! Bu ürünün stoğu tükendi. Yeni kervan 2 oyun günü sonra gelecek.");
        } else {
            player.sendMessage(ChatColor.GREEN + "Ticaret başarılı!");
        }

        if (sadakatGecerli) {
            if (newTrades == 10) player.sendMessage(ChatColor.AQUA + "Tebrikler! Bu tüccarla Sadakat Seviyesi 2 oldun. (%5 İndirim)");
            else if (newTrades == 25) player.sendMessage(ChatColor.AQUA + "Tebrikler! Bu tüccarla Sadakat Seviyesi 3 oldun. (%10 İndirim)");
            else if (newTrades == 50) player.sendMessage(ChatColor.AQUA + "Tebrikler! Bu tüccarla Sadakat Seviyesi 4 oldun. (%15 İndirim)");
            else if (newTrades == 100) player.sendMessage(ChatColor.GOLD + "İnanılmaz! Bu tüccarla Sadakat Seviyesi 5 (VIP) oldun. (%25 İndirim)");
        }

        if (seviyeAtladi) {
            player.sendMessage(ChatColor.GOLD + "Tüccar seviye atladı! " + ChatColor.YELLOW + getKoyluSeviyeAdi(yeniKoyluSeviyesi)
                    + ChatColor.GRAY + " (Seviye " + yeniKoyluSeviyesi + ") " + ChatColor.GREEN + "Yeni ürünler açıldı.");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }

        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
        openTradeGUI(player, villager); 
    }

    // Köylünün ürün bazlı stok yenileme zamanları (dizi tarif sayısına göre büyütülür).
    // Eski sürümdeki köylü geneli bekleme varsa sadece tükenmiş ürünlere aktarılıp silinir.
    private long[] stokZamanlari(AbstractVillager villager, int tarifSayisi) {
        PersistentDataContainer data = villager.getPersistentDataContainer();
        long[] dizi = data.has(stokYenilemeKey, PersistentDataType.LONG_ARRAY)
                ? data.get(stokYenilemeKey, PersistentDataType.LONG_ARRAY) : new long[0];
        if (dizi.length < tarifSayisi) dizi = Arrays.copyOf(dizi, tarifSayisi);

        if (data.has(cooldownKey, PersistentDataType.LONG)) {
            long eskiBekleme = data.get(cooldownKey, PersistentDataType.LONG);
            List<MerchantRecipe> tarifler = villager.getRecipes();
            for (int i = 0; i < Math.min(tarifler.size(), dizi.length); i++) {
                if (tarifler.get(i).getUses() >= MAX_ALIM_SINIRI && dizi[i] == 0) dizi[i] = eskiBekleme;
            }
            data.remove(cooldownKey);
            data.set(stokYenilemeKey, PersistentDataType.LONG_ARRAY, dizi);
        }
        return dizi;
    }

    private void stokZamanlariniKaydet(AbstractVillager villager, long[] dizi) {
        villager.getPersistentDataContainer().set(stokYenilemeKey, PersistentDataType.LONG_ARRAY, dizi);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(ChatColor.DARK_BLUE + "Tüccar Menüsü")) {
            islemdekiKoyluler.remove(event.getPlayer().getUniqueId());
        }
    }

    private double calculateDinamikFiyat(ItemStack result, double baseZumrutDegeri) {
        if (baseZumrutDegeri <= 0) return 0;
        
        Material type = result.getType();
        double rawPrice = baseZumrutDegeri;
        
        if (type == Material.ENCHANTED_BOOK) {
            rawPrice = (baseZumrutDegeri * 3.0) + 2500.0;
        } 
        else if (type.name().contains("DIAMOND") || type.name().contains("NETHERITE")) {
            rawPrice = (baseZumrutDegeri * 2.0) + 1000.0;
        } 
        else if (type.isEdible()) {
            double minYemekFiyati = result.getAmount() * 75.0;
            rawPrice = Math.max(baseZumrutDegeri, minYemekFiyati); 
        }
        
        // YENİ: Tüm fiyatlara standart %40 genel indirim (Fiyatı 0.60 ile çarpıyoruz)
        return rawPrice * 0.60;
    }

    private boolean consumePlayerMoney(Player player, double price) {
        PlayerInventory inventory = player.getInventory();
        double totalMoney = 0.0;
        List<Integer> moneySlots = new ArrayList<>();

        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            Double itemValue = plugin.getMoneyValue(item);
            if (itemValue != null) {
                totalMoney += (itemValue * item.getAmount());
                moneySlots.add(i);
            }
        }

        if (totalMoney < price) {
            player.sendMessage(ChatColor.RED + "Bu ticaret için cüzdanında yeterli paran yok! Gereken: $" + formatAmount(price));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return false;
        }

        for (int slot : moneySlots) {
            inventory.setItem(slot, null);
        }

        double remaining = Math.round((totalMoney - price) * 100.0) / 100.0;
        if (remaining > 0) {
            inventory.addItem(plugin.createEconomyNote(remaining));
        }

        player.updateInventory();
        return true;
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    private String formatName(String materialName) {
        return materialName.replace("_", " ").toLowerCase();
    }
}