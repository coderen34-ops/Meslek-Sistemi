package me.mesleksistemi.ekonomi;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class ToptanciManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    private final NamespacedKey madenciNpcKey;
    private final NamespacedKey oduncuNpcKey;

    private final HashMap<Material, Integer> stoklar = new HashMap<>();

    private final List<Material> madenciEsyalari = new ArrayList<>();
    private final List<Material> oduncuEsyalari = new ArrayList<>();

    public ToptanciManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.madenciNpcKey = new NamespacedKey(plugin, "madenci_npc");
        this.oduncuNpcKey = new NamespacedKey(plugin, "oduncu_npc");

        esyalariYukle();
        veriYukleStok();
    }

    private void esyalariYukle() {
        loadMaterialsSafely(madenciEsyalari,
                "STONE", "COBBLESTONE", "DIRT", "GRASS_BLOCK",
                "ANDESITE", "DIORITE", "GRANITE", "SAND", "GRAVEL",
                "DEEPSLATE", "COBBLED_DEEPSLATE", "TUFF",
                // YENI EKLENEN MADENCI BLOKLARI
                "AMETHYST_BLOCK", "CALCITE", 
                "BASALT", "POLISHED_BASALT", "SMOOTH_BASALT", 
                "BLACKSTONE", "POLISHED_BLACKSTONE"
        );

        loadMaterialsSafely(oduncuEsyalari,
                "OAK_LOG", "OAK_PLANKS",
                "SPRUCE_LOG", "SPRUCE_PLANKS",
                "BIRCH_LOG", "BIRCH_PLANKS",
                "JUNGLE_LOG", "JUNGLE_PLANKS",
                "ACACIA_LOG", "ACACIA_PLANKS",
                "DARK_OAK_LOG", "DARK_OAK_PLANKS",
                "MANGROVE_LOG", "MANGROVE_PLANKS",
                "CHERRY_LOG", "CHERRY_PLANKS",
                "CRIMSON_STEM", "CRIMSON_PLANKS",
                "WARPED_STEM", "WARPED_PLANKS",
                "BAMBOO_BLOCK", "BAMBOO_PLANKS",
                "PALE_OAK_LOG", "PALE_OAK_PLANKS"
        );
    }

    private void loadMaterialsSafely(List<Material> list, String... matNames) {
        for (String name : matNames) {
            try {
                list.add(Material.valueOf(name));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private double getAlisFiyati(Material mat) {
        String name = mat.name();

        // MADENCİ FİYATLARI
        if (name.contains("AMETHYST")) return 1.50; // En nadir
        if (name.contains("CALCITE")) return 1.20; // Jeot taşı
        if (name.contains("BLACKSTONE") || name.contains("BASALT")) return 1.00; // Nether tehlikesi
        if (name.contains("DEEPSLATE") || mat == Material.TUFF) return 0.80;
        if (name.contains("ANDESITE") || name.contains("DIORITE") || name.contains("GRANITE") || mat == Material.STONE) return 0.60;

        // ODUNCU FİYATLARI
        if (name.contains("PALE_OAK")) {
            if (name.contains("PLANKS")) return 0.85;
            return 3.40; 
        }
        
        if (name.contains("MANGROVE") || name.contains("CHERRY") || name.contains("CRIMSON") || name.contains("WARPED") || name.contains("BAMBOO")) {
            if (name.contains("PLANKS")) return 0.65;
            return 2.60; 
        }

        if (name.contains("LOG") || name.contains("WOOD") || name.contains("STEM")) return 2.00;
        if (name.contains("PLANKS")) return 0.50; 
        
        return 0.50;
    }

    private double getSatisFiyati(Material mat) {
        return getAlisFiyati(mat) / 2.0; 
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        if (!player.hasPermission("ticaret.admin")) {
            player.sendMessage(ChatColor.RED + "Bu komut için yetkiniz yok.");
            return true;
        }

        if (args.length == 0) return true;

        if (command.getName().equalsIgnoreCase("madencinpc")) {
            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
                npc.setCustomName(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Madenci Pazarı");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.MASON);
                npc.getPersistentDataContainer().set(madenciNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Madenci NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(madenciNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(ChatColor.GREEN + "" + silinen + " adet Madenci NPC silindi.");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("oduncunpc")) {
            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
                npc.setCustomName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Oduncu Pazarı");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.FLETCHER);
                npc.getPersistentDataContainer().set(oduncuNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Oduncu NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(oduncuNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(ChatColor.GREEN + "" + silinen + " adet Oduncu NPC silindi.");
            }
            return true;
        }

        return false;
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            Player player = event.getPlayer();

            if (npc.getPersistentDataContainer().has(madenciNpcKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                openMarketGUI(player, ChatColor.DARK_GRAY + "Madenci Pazarı", madenciEsyalari);
            } else if (npc.getPersistentDataContainer().has(oduncuNpcKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                openMarketGUI(player, ChatColor.DARK_GREEN + "Oduncu Pazarı", oduncuEsyalari);
            }
        }
    }

    private void openMarketGUI(Player player, String title, List<Material> materials) {
        int size = ((materials.size() / 9) + 1) * 9;
        if (size < 27) size = 27;
        
        Inventory gui = Bukkit.createInventory(null, size, title);
        for (int i = 0; i < materials.size() && i < size; i++) {
            gui.setItem(i, createGuiItem(materials.get(i)));
        }
        player.openInventory(gui);
    }

    private ItemStack createGuiItem(Material mat) {
        double alisFiyati = getAlisFiyati(mat);
        double satisFiyati = getSatisFiyati(mat);
        int mevcutStok = stoklar.getOrDefault(mat, 0);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + mat.name().replace("_", " "));
        
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "-------------------------");
        lore.add(ChatColor.GRAY + "Pazar Stoğu: " + (mevcutStok > 0 ? ChatColor.AQUA + "" + mevcutStok : ChatColor.RED + "Tükendi!"));
        lore.add("");
        lore.add(ChatColor.GREEN + "► Alış Fiyatı (Devletten Alırken): $" + String.format(Locale.US, "%.2f", alisFiyati));
        lore.add(ChatColor.RED + "◄ Satış Fiyatı (Devlete Satarken): $" + String.format(Locale.US, "%.2f", satisFiyati));
        lore.add(ChatColor.DARK_GRAY + "-------------------------");
        lore.add(ChatColor.YELLOW + "Sol Tık: " + ChatColor.WHITE + "1 Adet Al");
        lore.add(ChatColor.YELLOW + "Shift + Sol Tık: " + ChatColor.WHITE + "64 Adet Al");
        lore.add(ChatColor.YELLOW + "Sağ Tık: " + ChatColor.WHITE + "1 Adet Sat");
        lore.add(ChatColor.YELLOW + "Shift + Sağ Tık: " + ChatColor.WHITE + "Envanterdeki Hepsini Sat");
        meta.setLore(lore);
        
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.equals(ChatColor.DARK_GRAY + "Madenci Pazarı") && !title.equals(ChatColor.DARK_GREEN + "Oduncu Pazarı")) return;

        event.setCancelled(true);
        if (event.getClickedInventory() == null) return;

        Player player = (Player) event.getWhoClicked();
        
        if (event.getClickedInventory().equals(player.getInventory())) return; 

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        Material mat = clickedItem.getType();
        ClickType click = event.getClick();
        boolean isMadenciMenu = title.equals(ChatColor.DARK_GRAY + "Madenci Pazarı");
        String requiredJob = isMadenciMenu ? "madenci" : "oduncu";
        String jobDisplay = isMadenciMenu ? "Madenci" : "Oduncu";
        
        int mevcutStok = stoklar.getOrDefault(mat, 0);

        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            String ms = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!ms.equalsIgnoreCase(requiredJob)) {
                player.sendMessage(ChatColor.RED + "Bu pazara blok satmak (Tedarik sağlamak) için " + ChatColor.GOLD + jobDisplay + ChatColor.RED + " olmalısın!");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            int oyuncudakiMiktar = getAmountInInventory(player, mat);
            if (oyuncudakiMiktar <= 0) {
                player.sendMessage(ChatColor.RED + "Envanterinizde satacak " + mat.name().replace("_", " ") + " bulunmuyor.");
                return;
            }

            int satilacakMiktar = (click == ClickType.SHIFT_RIGHT) ? oyuncudakiMiktar : 1;
            double unitPrice = getSatisFiyati(mat);
            double totalPrice = satilacakMiktar * unitPrice;

            if (!deductFromKasa(totalPrice)) {
                player.sendMessage(ChatColor.RED + "Belediye Kasasında senin alacağını ödeyecek bütçe kalmamış! Başkan kasaya para koymalı.");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            plugin.removeItemFromInventory(player.getInventory(), mat, satilacakMiktar);
            
            double currentBank = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
            plugin.bankaHesaplari.put(player.getUniqueId(), currentBank + totalPrice);
            plugin.veriKaydet();
            
            stoklar.put(mat, mevcutStok + satilacakMiktar); 
            veriKaydetStok();

            String formatted = String.format(Locale.US, "%.2f", totalPrice);
            player.sendMessage(ChatColor.GREEN + "Devlete " + satilacakMiktar + " adet " + mat.name().replace("_", " ") + " sattın! Hesabına " + ChatColor.YELLOW + "$" + formatted + ChatColor.GREEN + " yattı.");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            
            openMarketGUI(player, title, isMadenciMenu ? madenciEsyalari : oduncuEsyalari);
            return;
        }

        if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
            int alinacakMiktar = (click == ClickType.SHIFT_LEFT) ? 64 : 1;
            
            if (mevcutStok < alinacakMiktar) alinacakMiktar = mevcutStok; 
            
            if (alinacakMiktar <= 0) {
                player.sendMessage(ChatColor.RED + "Bu ürünün stoğu tükenmiş! " + jobDisplay + "lerin devlete satış yapmasını beklemelisin.");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            int bosYer = 0;
            for (ItemStack i : player.getInventory().getStorageContents()) {
                if (i == null || i.getType() == Material.AIR) {
                    bosYer += mat.getMaxStackSize();
                } else if (i.getType() == mat && i.getAmount() < i.getMaxStackSize()) {
                    bosYer += (i.getMaxStackSize() - i.getAmount());
                }
            }

            if (bosYer < alinacakMiktar) {
                player.sendMessage(ChatColor.RED + "Envanterinizde " + alinacakMiktar + " adet için yeterli boş yer yok!");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            double unitPrice = getAlisFiyati(mat);
            double totalPrice = alinacakMiktar * unitPrice;

            if (plugin.processPaymentToKasa(player, totalPrice)) {
                player.getInventory().addItem(new ItemStack(mat, alinacakMiktar));

                stoklar.put(mat, mevcutStok - alinacakMiktar); 
                veriKaydetStok();

                String formatted = String.format(Locale.US, "%.2f", totalPrice);
                player.sendMessage(ChatColor.GREEN + "Pazardan " + alinacakMiktar + " adet blok satın aldın! Ödediğin " + ChatColor.YELLOW + "$" + formatted + ChatColor.GREEN + " doğrudan Belediye Kasasına eklendi.");
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                
                openMarketGUI(player, title, isMadenciMenu ? madenciEsyalari : oduncuEsyalari);
            }
        }
    }

    private int getAmountInInventory(Player player, Material mat) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == mat) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private boolean deductFromKasa(double amount) {
        if (plugin.kasaKonumu == null || !(plugin.kasaKonumu.getBlock().getState() instanceof Chest)) return false;
        Chest kasa = (Chest) plugin.kasaKonumu.getBlock().getState();
        
        double total = 0.0;
        for (ItemStack item : kasa.getInventory().getContents()) {
            Double val = plugin.getMoneyValue(item);
            if (val != null) total += (val * item.getAmount());
        }

        if (total < amount) return false;

        for (int i = 0; i < kasa.getInventory().getSize(); i++) {
            if (plugin.getMoneyValue(kasa.getInventory().getItem(i)) != null) kasa.getInventory().setItem(i, null);
        }
        
        double remaining = Math.round((total - amount) * 100.0) / 100.0;
        if (remaining > 0) {
            kasa.getInventory().addItem(plugin.createEconomyNote(remaining));
            plugin.mergeKasaMoney(kasa); 
        }
        return true;
    }

    public void veriKaydetStok() {
        for (Material mat : stoklar.keySet()) {
            plugin.getConfig().set("toptanci_stok." + mat.name(), stoklar.get(mat));
        }
        plugin.saveConfig();
    }

    public void veriYukleStok() {
        if (plugin.getConfig().contains("toptanci_stok")) {
            for (String key : plugin.getConfig().getConfigurationSection("toptanci_stok").getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key);
                    int miktar = plugin.getConfig().getInt("toptanci_stok." + key);
                    stoklar.put(mat, miktar);
                } catch (Exception e) {
                }
            }
        }
    }
}