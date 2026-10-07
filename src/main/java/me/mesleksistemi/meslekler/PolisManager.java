package me.mesleksistemi.meslekler;

import me.mesleksistemi.MeslekSistemi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public class PolisManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    
    private final NamespacedKey copKey;
    private final NamespacedKey modKey;
    private final NamespacedKey sikayetNpcKey;
    private final NamespacedKey davaDosyasiKey;
    
    public HashMap<String, Location> jailCells = new HashMap<>();
    public HashMap<UUID, Integer> jailedPlayers = new HashMap<>();
    private final HashMap<UUID, BukkitRunnable> jailTasks = new HashMap<>();
    public HashMap<UUID, Location> preJailLocations = new HashMap<>();
    
    // İŞTE YENİ HAFIZAMIZ: Oyuncu hapse girmeden önceki mesleğini burada tutuyoruz.
    public HashMap<UUID, String> preJailRoles = new HashMap<>();
    
    private final HashMap<UUID, Long> dropConfirmations = new HashMap<>();
    
    public Location amirKursuKonumu = null;
    public HashMap<UUID, Sikayet> aktifSikayetler = new HashMap<>();
    public HashSet<String> arananOyuncular = new HashSet<>();
    
    private final HashMap<UUID, Integer> sikayetAdimi = new HashMap<>(); 
    private final HashMap<UUID, String> geciciSikayetHedef = new HashMap<>(); 
    
    private final Random random = new Random();

    public PolisManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.copKey = new NamespacedKey(plugin, "polis_copu");
        this.modKey = new NamespacedKey(plugin, "cop_modu");
        this.sikayetNpcKey = new NamespacedKey(plugin, "sikayet_npc");
        this.davaDosyasiKey = new NamespacedKey(plugin, "dava_hedefi");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        String commandName = command.getName();

        if (commandName.equalsIgnoreCase("hucreolustur")) {
            if (!player.hasPermission("polis.admin")) return true;
            if (args.length == 0) { player.sendMessage(ChatColor.RED + "Kullanım: /hucreolustur <hücre_adı>"); return true; }
            String cellName = args[0].toLowerCase();
            jailCells.put(cellName, player.getLocation());
            player.sendMessage(ChatColor.GREEN + "Hücre '" + cellName + "' oluşturuldu!");
            return true;
        }

        if (commandName.equalsIgnoreCase("hucresil")) {
            if (!player.hasPermission("polis.admin")) return true;
            if (args.length == 0) return true;
            String cellName = args[0].toLowerCase();
            if (jailCells.remove(cellName) != null) {
                player.sendMessage(ChatColor.GREEN + "Hücre silindi!");
            }
            return true;
        }

        if (commandName.equalsIgnoreCase("copal")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!meslek.equalsIgnoreCase("polis")) {
                player.sendMessage(ChatColor.RED + "Sadece emniyet mensupları cop alabilir!");
                return true;
            }
            player.getInventory().addItem(createCop(1)); 
            player.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.GREEN + "Polis copu verildi.");
            return true;
        }
        
        if (commandName.equalsIgnoreCase("serbestbirak")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!meslek.equalsIgnoreCase("polis")) return true;
            if (args.length == 0) return true;
            Player target = Bukkit.getPlayer(args[0]);
            if (target != null && jailedPlayers.containsKey(target.getUniqueId())) {
                releasePlayer(target);
                player.sendMessage(ChatColor.GREEN + target.getName() + " serbest bırakıldı.");
            }
            return true;
        }
        
        if (commandName.equalsIgnoreCase("amirkursu")) {
            if (!player.hasPermission("polis.admin")) return true;
            Block targetBlock = player.getTargetBlockExact(5);
            if (targetBlock != null && targetBlock.getType() == Material.LECTERN) {
                amirKursuKonumu = targetBlock.getLocation();
                player.sendMessage(ChatColor.GREEN + "Amir Masası başarıyla ayarlandı!");
            } else {
                player.sendMessage(ChatColor.RED + "Lütfen bir kürsüye bakarak komutu girin.");
            }
            return true;
        }
        
        if (commandName.equalsIgnoreCase("sikayetnpc")) {
            if (!player.hasPermission("polis.admin")) return true;
            if (args.length == 0) return true;
            
            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false);
                npc.setInvulnerable(true);
                npc.setCollidable(false);
                npc.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Şikayet / İhbar Merkezi");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.CLERIC);
                npc.getPersistentDataContainer().set(sikayetNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Şikayet NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(sikayetNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(ChatColor.GREEN + "" + silinen + " adet NPC silindi.");
            }
            return true;
        }

        if (commandName.equalsIgnoreCase("sikayetkarar")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!meslek.equalsIgnoreCase("polis")) return true;
            if (args.length < 2) return true;
            UUID sikayetId = UUID.fromString(args[0]);
            String karar = args[1];
            
            if (!aktifSikayetler.containsKey(sikayetId)) {
                player.sendMessage(ChatColor.RED + "Bu şikayet dosyası kapanmış veya bulunamadı.");
                return true;
            }
            
            Sikayet sikayet = aktifSikayetler.get(sikayetId);
            aktifSikayetler.remove(sikayetId);
            
            if (karar.equalsIgnoreCase("kapat")) {
                player.sendMessage(ChatColor.YELLOW + "Şikayet dosyası kapatıldı ve arşivlendi.");
            } else if (karar.equalsIgnoreCase("sorusturma")) {
                arananOyuncular.add(sikayet.sikayetEdilen.toLowerCase());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag setsuffix " + sikayet.sikayetEdilen + " Aranıyor");
                
                ItemStack davaDosyasi = new ItemStack(Material.WRITTEN_BOOK);
                BookMeta bMeta = (BookMeta) davaDosyasi.getItemMeta();
                bMeta.setTitle("Dava Dosyası: " + sikayet.sikayetEdilen);
                bMeta.setAuthor("Emniyet Müdürlüğü");
                bMeta.addPage(ChatColor.DARK_RED + "ARAMA EMRİ & DAVA\n\n" + ChatColor.BLACK + "Şüpheli: " + ChatColor.DARK_GRAY + sikayet.sikayetEdilen + "\n\n" + ChatColor.BLACK + "Müşteki: " + ChatColor.DARK_GRAY + sikayet.sikayetEden + "\n\n" + ChatColor.DARK_RED + "Olay:\n" + ChatColor.BLACK + sikayet.sebep);
                bMeta.getPersistentDataContainer().set(davaDosyasiKey, PersistentDataType.STRING, sikayet.sikayetEdilen);
                davaDosyasi.setItemMeta(bMeta);
                
                player.getInventory().addItem(davaDosyasi);
                
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + "DİKKAT: " + ChatColor.RED + sikayet.sikayetEdilen + ChatColor.YELLOW + " hakkında soruşturma başlatılmıştır!");
                player.sendMessage(ChatColor.GREEN + "Soruşturma başlatıldı. Dava dosyası envanterinize eklendi!");
            }
            player.closeInventory();
            return true;
        }
        return false;
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            if (npc.getPersistentDataContainer().has(sikayetNpcKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                openSikayetSecimMenu(player);
                return;
            }
        }
        
        if (event.getRightClicked() instanceof Player) {
            Player target = (Player) event.getRightClicked();
            ItemStack item = player.getInventory().getItemInMainHand();
            
            if (item.getType() == Material.WRITTEN_BOOK && item.hasItemMeta()) {
                PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
                if (data.has(davaDosyasiKey, PersistentDataType.STRING)) {
                    String arananHedef = data.get(davaDosyasiKey, PersistentDataType.STRING);
                    if (target.getName().equalsIgnoreCase(arananHedef)) {
                        event.setCancelled(true);
                        openDavaKararMenu(player, target);
                    } else {
                        player.sendMessage(ChatColor.RED + "Bu dava dosyası " + target.getName() + " için değil, " + arananHedef + " için!");
                    }
                }
            }
        }
    }
    
    private void openDavaKararMenu(Player cop, Player target) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_RED + "Soruşturma: " + target.getName());
        
        ItemStack hapisHafif = new ItemStack(Material.IRON_BARS);
        ItemMeta hafifMeta = hapisHafif.getItemMeta();
        hafifMeta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Nezarete At (5 Dakika)");
        hafifMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Hafif suçlar için."));
        hapisHafif.setItemMeta(hafifMeta);

        ItemStack hapisOrta = new ItemStack(Material.COBWEB);
        ItemMeta ortaMeta = hapisOrta.getItemMeta();
        ortaMeta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Nezarete At (15 Dakika)");
        ortaMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Orta düzey suçlar için."));
        hapisOrta.setItemMeta(ortaMeta);

        ItemStack hapisAgir = new ItemStack(Material.NETHERITE_BLOCK);
        ItemMeta agirMeta = hapisAgir.getItemMeta();
        agirMeta.setDisplayName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Nezarete At (30 Dakika)");
        agirMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Ağır suçlar için."));
        hapisAgir.setItemMeta(agirMeta);
        
        ItemStack beraat = new ItemStack(Material.PAPER);
        ItemMeta bMeta = beraat.getItemMeta();
        bMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Serbest Bırak / Dosyayı Kapat");
        bMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Şüpheliyi aklayarak soruşturmayı sonlandırır."));
        beraat.setItemMeta(bMeta);
        
        gui.setItem(10, hapisHafif);
        gui.setItem(12, hapisOrta);
        gui.setItem(14, hapisAgir);
        gui.setItem(16, beraat);
        cop.openInventory(gui);
    }
    
    private void openSikayetSecimMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Şüpheli Seçimi");
        int slot = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(player.getUniqueId())) continue; 
            if (slot > 53) break;
            
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.YELLOW + target.getName());
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Şikayet etmek için tıkla."));
            head.setItemMeta(meta);
            
            gui.setItem(slot, head);
            slot++;
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onLecternInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null && clickedBlock.getType() == Material.LECTERN) {
                if (amirKursuKonumu != null && clickedBlock.getLocation().equals(amirKursuKonumu)) {
                    event.setCancelled(true); 
                    Player player = event.getPlayer();
                    String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
                    if (meslek.equalsIgnoreCase("polis")) {
                        openAmirMenu(player);
                    } else {
                        player.sendMessage(ChatColor.RED + "Bu dosyalara sadece emniyet mensupları erişebilir!");
                    }
                }
            }
        }
    }

    private void openAmirMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_BLUE + "Bekleyen Şikayetler");
        int slot = 0;
        for (Sikayet s : aktifSikayetler.values()) {
            if(slot > 53) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(s.sikayetEdilen));
            meta.setDisplayName(ChatColor.RED + "Şüpheli: " + s.sikayetEdilen);
            
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Şikayet Eden: " + ChatColor.YELLOW + s.sikayetEden);
            lore.add(ChatColor.DARK_GRAY + "Tıklayarak detaylı ifadeyi oku.");
            meta.setLore(lore);
            
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(new NamespacedKey(plugin, "sikayet_id"), PersistentDataType.STRING, s.id.toString());
            
            head.setItemMeta(meta);
            gui.setItem(slot, head);
            slot++;
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();
        
        if (title.equals(ChatColor.DARK_RED + "Şüpheli Seçimi")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
            
            String targetName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
            player.closeInventory();
            
            geciciSikayetHedef.put(player.getUniqueId(), targetName);
            sikayetAdimi.put(player.getUniqueId(), 2); 
            
            player.sendMessage(ChatColor.YELLOW + "--- EMNİYET MÜDÜRLÜĞÜ ---");
            player.sendMessage(ChatColor.GREEN + "Şüpheli: " + ChatColor.RED + targetName);
            player.sendMessage(ChatColor.GREEN + "Lütfen olayın detayını tek bir mesaj halinde anlatın:");
            player.sendMessage(ChatColor.GRAY + "(İptal etmek için 'iptal' yazın)");
            return;
        }
        
        if (title.equals(ChatColor.DARK_BLUE + "Bekleyen Şikayetler")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
            
            PersistentDataContainer data = clicked.getItemMeta().getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(plugin, "sikayet_id");
            if (data.has(key, PersistentDataType.STRING)) {
                UUID sId = UUID.fromString(data.get(key, PersistentDataType.STRING));
                if (aktifSikayetler.containsKey(sId)) {
                    openSikayetKitabi(player, aktifSikayetler.get(sId));
                }
            }
            return;
        }
        
        if (title.startsWith(ChatColor.DARK_RED + "Soruşturma: ")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            
            String targetName = title.replace(ChatColor.DARK_RED + "Soruşturma: ", "").trim();
            Player target = Bukkit.getPlayer(targetName);
            
            if (target == null || !target.isOnline()) {
                player.sendMessage(ChatColor.RED + "Şüpheli oyunda değil veya kaçmış!");
                player.closeInventory();
                return;
            }
            
            Material clickedMat = clicked.getType();
            
            if (clickedMat == Material.IRON_BARS || clickedMat == Material.COBWEB || clickedMat == Material.NETHERITE_BLOCK) {
                if (jailCells.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Hücre ayarlanmamış!");
                } else {
                    int sure = 0;
                    String mesajSeviyesi = "";
                    
                    if (clickedMat == Material.IRON_BARS) { sure = 300; mesajSeviyesi = "Hafif Suç (5 Dk)"; }
                    else if (clickedMat == Material.COBWEB) { sure = 900; mesajSeviyesi = "Orta Suç (15 Dk)"; }
                    else if (clickedMat == Material.NETHERITE_BLOCK) { sure = 1800; mesajSeviyesi = "Ağır Suç (30 Dk)"; }

                    arananOyuncular.remove(target.getName().toLowerCase());
                    preJailLocations.put(target.getUniqueId(), target.getLocation());
                    
                    jailPlayer(target, sure); 
                    
                    Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + target.getName() + ChatColor.GREEN + " tutuklanarak nezarete gönderildi! [" + mesajSeviyesi + "]");
                    elindekiDosyayiSil(player);
                }
            } else if (clickedMat == Material.PAPER) {
                arananOyuncular.remove(target.getName().toLowerCase());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removesuffix " + target.getName()); 
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + target.getName() + ChatColor.GREEN + " hakkındaki suçlamalar düşürüldü ve dosya kapatıldı.");
                elindekiDosyayiSil(player);
            }
            player.closeInventory();
        }
    }
    
    private void elindekiDosyayiSil(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item != null && item.getType() == Material.WRITTEN_BOOK && item.hasItemMeta()) {
            if (item.getItemMeta().getPersistentDataContainer().has(davaDosyasiKey, PersistentDataType.STRING)) {
                player.getInventory().setItemInMainHand(null);
            }
        }
    }

    private void openSikayetKitabi(Player amir, Sikayet sikayet) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Dosya No: " + sikayet.id.toString().substring(0, 5));
        meta.setAuthor("Emniyet");

        List<BaseComponent[]> pages = new ArrayList<>();
        String sebep = sikayet.sebep;
        List<String> chunks = new ArrayList<>();
        int chunkSize = 150; 
        int index = 0;
        
        while (index < sebep.length()) {
            int end = Math.min(index + chunkSize, sebep.length());
            if (end < sebep.length()) {
                int lastSpace = sebep.lastIndexOf(' ', end);
                if (lastSpace > index) end = lastSpace;
            }
            chunks.add(sebep.substring(index, end).trim());
            index = end;
        }

        TextComponent sayfa1 = new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "ŞİKAYET DOSYASI\n\n");
        sayfa1.addExtra(new TextComponent(ChatColor.BLACK + "Şikayet Eden:\n" + ChatColor.DARK_GRAY + sikayet.sikayetEden + "\n\n"));
        sayfa1.addExtra(new TextComponent(ChatColor.BLACK + "Şüpheli:\n" + ChatColor.DARK_GRAY + sikayet.sikayetEdilen + "\n\n"));
        sayfa1.addExtra(new TextComponent(ChatColor.DARK_RED + "İfade Detayı:\n"));
        
        if (!chunks.isEmpty()) { sayfa1.addExtra(new TextComponent(ChatColor.BLACK + chunks.get(0))); }
        pages.add(new BaseComponent[]{sayfa1});

        for (int i = 1; i < chunks.size(); i++) {
            pages.add(new BaseComponent[]{new TextComponent(ChatColor.BLACK + chunks.get(i))});
        }

        TextComponent sonSayfa = new TextComponent(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + "AMİR KARARI\n\n\n");
        TextComponent redBtn = new TextComponent(ChatColor.DARK_GRAY + ChatColor.BOLD.toString() + "[DOSYAYI KAPAT]\n\n\n");
        redBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sikayetkarar " + sikayet.id.toString() + " kapat"));
        
        TextComponent kabulBtn = new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "[SORUŞTURMA BAŞLAT]");
        kabulBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sikayetkarar " + sikayet.id.toString() + " sorusturma"));
        
        sonSayfa.addExtra(redBtn); sonSayfa.addExtra(kabulBtn);
        pages.add(new BaseComponent[]{sonSayfa});

        for (BaseComponent[] page : pages) { meta.spigot().addPage(page); }
        book.setItemMeta(meta); amir.openBook(book);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID pId = player.getUniqueId();
        
        if (sikayetAdimi.containsKey(pId)) {
            event.setCancelled(true);
            String msg = event.getMessage().trim();
            
            if (msg.equalsIgnoreCase("iptal")) {
                sikayetAdimi.remove(pId);
                geciciSikayetHedef.remove(pId);
                player.sendMessage(ChatColor.YELLOW + "Şikayet işlemi iptal edildi.");
                return;
            }
            
            int adim = sikayetAdimi.get(pId);
            if (adim == 2) {
                String hedef = geciciSikayetHedef.get(pId);
                String sebep = msg;
                Sikayet yeniSikayet = new Sikayet(UUID.randomUUID(), player.getName(), hedef, sebep);
                aktifSikayetler.put(yeniSikayet.id, yeniSikayet);
                
                sikayetAdimi.remove(pId);
                geciciSikayetHedef.remove(pId);
                
                player.sendMessage(ChatColor.GREEN + "Tutanak başarıyla karakola iletildi.");
                
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String pMeslek = plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas");
                    if (pMeslek.equalsIgnoreCase("polis")) {
                        p.sendMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + "Karakola yeni bir ihbar/şikayet ulaştı! Amir Masasını kontrol edin.");
                    }
                }
            }
        }
    }

    @EventHandler
    public void onCopInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.isSneaking() && (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK)) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (isCop(item)) {
                int currentMod = getCopMod(item);
                int nextMod = currentMod == 3 ? 1 : currentMod + 1; 
                
                ItemStack newCop = createCop(nextMod);
                player.getInventory().setItemInMainHand(newCop);
                
                String modName = nextMod == 1 ? "5 Dakika (Hafif Suç)" : nextMod == 2 ? "10 Dakika (Orta Suç)" : "30 Dakika (Ağır Suç)";
                player.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.YELLOW + "Cop modu değiştirildi: " + ChatColor.RED + modName);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemDrop().getItemStack();

        if (isCop(item)) {
            UUID uuid = player.getUniqueId();
            long currentTime = System.currentTimeMillis();

            if (dropConfirmations.containsKey(uuid) && (currentTime - dropConfirmations.get(uuid)) < 5000) {
                dropConfirmations.remove(uuid); 
                event.getItemDrop().remove(); 
                
                for (int i = 0; i < player.getInventory().getSize(); i++) {
                    ItemStack invItem = player.getInventory().getItem(i);
                    if (isCop(invItem)) player.getInventory().setItem(i, null);
                }
                
                player.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.RED + "İstifa ettiniz! Polislik yetkileriniz alındı.");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "meslekata " + player.getName() + " vatandas");
                
            } else {
                event.setCancelled(true); 
                dropConfirmations.put(uuid, currentTime);
                player.sendMessage(ChatColor.YELLOW + "Polis mesleğinden ayrılmak istediğine emin misin?");
                player.sendMessage(ChatColor.YELLOW + "Eğer eminsen " + ChatColor.RED + "5 saniye içinde copu tekrar yere at (Q).");
            }
        }
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Player) {
            Player copPlayer = (Player) event.getDamager();
            Player targetPlayer = (Player) event.getEntity();
            ItemStack item = copPlayer.getInventory().getItemInMainHand();

            if (isCop(item)) {
                String meslek = plugin.oyuncuMeslekCache.getOrDefault(copPlayer.getUniqueId(), "vatandas");
                if (!meslek.equalsIgnoreCase("polis")) return; 
                event.setCancelled(true); 
                
                if (jailCells.isEmpty()) { copPlayer.sendMessage(ChatColor.RED + "Hiç hücre ayarlanmamış!"); return; }
                if (jailedPlayers.containsKey(targetPlayer.getUniqueId())) { copPlayer.sendMessage(ChatColor.RED + "Bu oyuncu zaten hapiste!"); return; }

                if (arananOyuncular.contains(targetPlayer.getName().toLowerCase())) {
                    arananOyuncular.remove(targetPlayer.getName().toLowerCase());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removesuffix " + targetPlayer.getName()); 
                    Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.GREEN + "Aranan şüpheli " + ChatColor.YELLOW + targetPlayer.getName() + ChatColor.GREEN + ", Memur " + copPlayer.getName() + " tarafından yakalanarak paketlendi!");
                }

                preJailLocations.put(targetPlayer.getUniqueId(), targetPlayer.getLocation());
                int mod = getCopMod(item);
                int durationSeconds = mod == 1 ? 300 : mod == 2 ? 600 : 1800; 
                
                jailPlayer(targetPlayer, durationSeconds);
                copPlayer.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.GREEN + targetPlayer.getName() + " başarıyla hapse atıldı!");
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (jailedPlayers.containsKey(player.getUniqueId())) {
            int timeLeft = jailedPlayers.get(player.getUniqueId());
            startJailTimer(player, timeLeft); 
            
            if (!jailCells.isEmpty()) {
                boolean isNearCell = false;
                for (Location cellLoc : jailCells.values()) {
                    if (player.getWorld().equals(cellLoc.getWorld()) && player.getLocation().distance(cellLoc) < 15) {
                        isNearCell = true; break;
                    }
                }
                if (!isNearCell) player.teleport(getBestCell());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (jailTasks.containsKey(player.getUniqueId())) {
            jailTasks.get(player.getUniqueId()).cancel(); 
            jailTasks.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (jailedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Hapisteyken blok kıramazsınız!");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (jailedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Hapisteyken blok koyamazsınız!");
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (jailedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            String msg = event.getMessage().toLowerCase();
            if (msg.startsWith("/tp") || msg.startsWith("/spawn") || msg.startsWith("/home") || msg.startsWith("/warp") || msg.startsWith("/tpa") || msg.startsWith("/meslekata") || msg.startsWith("/istifa")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Hapisteyken bu komutları kullanamazsınız!");
            }
        }
    }
    
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (jailedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            if (event.getCause() == TeleportCause.ENDER_PEARL || event.getCause() == TeleportCause.CHORUS_FRUIT) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Hapisten bu şekilde kaçamazsınız!");
            }
        }
    }

    private void jailPlayer(Player player, int seconds) {
        // İŞTE BURASI: Oyuncu hapse girmeden hemen önce eski mesleğini hafızaya alıyoruz.
        String eskiMeslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
        if (!eskiMeslek.equalsIgnoreCase("mahkum")) {
            preJailRoles.put(player.getUniqueId(), eskiMeslek);
        }

        jailedPlayers.put(player.getUniqueId(), seconds);
        Location cell = getBestCell();
        if (cell != null) player.teleport(cell);
        
        plugin.oyuncuMeslekCache.put(player.getUniqueId(), "mahkum");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent clear");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent add mahkum");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag remove " + player.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + player.getName() + " Mahkum");
        
        player.sendMessage(ChatColor.DARK_RED + "Hapse atıldınız! Yeni Statünüz: Mahkum. Ceza süreniz: " + (seconds / 60) + " dakika.");
        startJailTimer(player, seconds);
    }

    private void releasePlayer(Player player) {
        jailedPlayers.remove(player.getUniqueId());
        if (jailTasks.containsKey(player.getUniqueId())) {
            jailTasks.get(player.getUniqueId()).cancel();
            jailTasks.remove(player.getUniqueId());
        }
        
        // İŞTE BURASI: Oyuncu çıkarken eski mesleğini geri alıyor
        String iadeEdilecekMeslek = preJailRoles.getOrDefault(player.getUniqueId(), "vatandas");
        preJailRoles.remove(player.getUniqueId()); // İade ettikten sonra hafızadan siliyoruz
        
        plugin.oyuncuMeslekCache.put(player.getUniqueId(), iadeEdilecekMeslek);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent clear");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent add " + iadeEdilecekMeslek);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag remove " + player.getName());
        
        // Ana sınıftaki görünüm adını ("Doktor", "Polis" gibi) çekip başının üstüne onu yazdırıyoruz
        String tagAd = plugin.meslekGorunumAdlari.get(iadeEdilecekMeslek);
        if (tagAd == null) {
            if (iadeEdilecekMeslek.equalsIgnoreCase("vatandas")) tagAd = "Vatandaş";
            else if (iadeEdilecekMeslek.equalsIgnoreCase("belediyecalisani")) tagAd = "Memur";
            else tagAd = iadeEdilecekMeslek.substring(0, 1).toUpperCase() + iadeEdilecekMeslek.substring(1).toLowerCase();
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + player.getName() + " " + tagAd);
        
        player.sendMessage(ChatColor.GREEN + "Cezanız doldu! Eski sivil hayatınıza ve [" + tagAd + "] unvanınıza geri döndünüz!");
        
        if (preJailLocations.containsKey(player.getUniqueId())) {
            Location returnLoc = preJailLocations.get(player.getUniqueId());
            player.teleport(returnLoc);
            preJailLocations.remove(player.getUniqueId()); 
        } else {
            player.teleport(player.getWorld().getSpawnLocation()); 
        }
    }

    private void startJailTimer(Player player, int initialSeconds) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) { this.cancel(); return; }
                int currentLeft = jailedPlayers.get(player.getUniqueId());
                if (currentLeft <= 0) {
                    releasePlayer(player);
                    this.cancel();
                } else {
                    currentLeft--;
                    jailedPlayers.put(player.getUniqueId(), currentLeft);
                    if (currentLeft > 0 && currentLeft <= 5) {
                         player.sendMessage(ChatColor.YELLOW + "Tahliyenize " + currentLeft + " saniye kaldı...");
                    }
                }
            }
        };
        task.runTaskTimer(plugin, 20L, 20L); 
        jailTasks.put(player.getUniqueId(), task);
    }
    
    private Location getBestCell() {
        if (jailCells.isEmpty()) return null;
        List<Location> emptyCells = new ArrayList<>();
        for (Location cell : jailCells.values()) {
            boolean isOccupied = false;
            for (UUID uuid : jailedPlayers.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline() && p.getWorld().equals(cell.getWorld()) && p.getLocation().distance(cell) < 5.0) {
                    isOccupied = true; break;
                }
            }
            if (!isOccupied) emptyCells.add(cell);
        }
        if (!emptyCells.isEmpty()) return emptyCells.get(random.nextInt(emptyCells.size()));
        List<Location> allCells = new ArrayList<>(jailCells.values());
        return allCells.get(random.nextInt(allCells.size()));
    }

    private ItemStack createCop(int mod) {
        ItemStack cop = new ItemStack(Material.STICK);
        ItemMeta meta = cop.getItemMeta();
        String modName = mod == 1 ? "5 Dakika (Hafif Suç)" : mod == 2 ? "10 Dakika (Orta Suç)" : "30 Dakika (Ağır Suç)";
        meta.setDisplayName(ChatColor.BLUE + "" + ChatColor.BOLD + "Polis Copu");
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Mod: " + ChatColor.RED + modName));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(this.copKey, PersistentDataType.BYTE, (byte) 1); 
        data.set(this.modKey, PersistentDataType.INTEGER, mod); 
        cop.setItemMeta(meta);
        return cop;
    }

    private boolean isCop(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(this.copKey, PersistentDataType.BYTE);
    }
    
    private int getCopMod(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 1;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return data.has(this.modKey, PersistentDataType.INTEGER) ? data.get(this.modKey, PersistentDataType.INTEGER) : 1;
    }

    public void veriKaydet() {
        plugin.getConfig().set("jailed_players", null); 
        for (UUID uuid : jailedPlayers.keySet()) {
            plugin.getConfig().set("jailed_players." + uuid.toString(), jailedPlayers.get(uuid));
        }
        
        plugin.getConfig().set("pre_jail_locations", null);
        for (UUID uuid : preJailLocations.keySet()) {
            Location loc = preJailLocations.get(uuid);
            plugin.getConfig().set("pre_jail_locations." + uuid.toString() + ".world", loc.getWorld().getName());
            plugin.getConfig().set("pre_jail_locations." + uuid.toString() + ".x", loc.getX());
            plugin.getConfig().set("pre_jail_locations." + uuid.toString() + ".y", loc.getY());
            plugin.getConfig().set("pre_jail_locations." + uuid.toString() + ".z", loc.getZ());
        }

        // ESKİ MESLEK HAFIZASINI DA KAYDEDİYORUZ
        plugin.getConfig().set("pre_jail_roles", null);
        for (UUID uuid : preJailRoles.keySet()) {
            plugin.getConfig().set("pre_jail_roles." + uuid.toString(), preJailRoles.get(uuid));
        }

        plugin.getConfig().set("jail.cells", null);
        for (String cellName : jailCells.keySet()) {
            Location loc = jailCells.get(cellName);
            plugin.getConfig().set("jail.cells." + cellName + ".world", loc.getWorld().getName());
            plugin.getConfig().set("jail.cells." + cellName + ".x", loc.getX());
            plugin.getConfig().set("jail.cells." + cellName + ".y", loc.getY());
            plugin.getConfig().set("jail.cells." + cellName + ".z", loc.getZ());
            plugin.getConfig().set("jail.cells." + cellName + ".yaw", loc.getYaw());
            plugin.getConfig().set("jail.cells." + cellName + ".pitch", loc.getPitch());
        }

        if (amirKursuKonumu != null) {
            plugin.getConfig().set("amir_kursusu.world", amirKursuKonumu.getWorld().getName());
            plugin.getConfig().set("amir_kursusu.x", amirKursuKonumu.getX());
            plugin.getConfig().set("amir_kursusu.y", amirKursuKonumu.getY());
            plugin.getConfig().set("amir_kursusu.z", amirKursuKonumu.getZ());
        }

        plugin.getConfig().set("sikayetler", null);
        for (Sikayet s : aktifSikayetler.values()) {
            String sid = s.id.toString();
            plugin.getConfig().set("sikayetler." + sid + ".eden", s.sikayetEden);
            plugin.getConfig().set("sikayetler." + sid + ".edilen", s.sikayetEdilen);
            plugin.getConfig().set("sikayetler." + sid + ".sebep", s.sebep);
        }

        plugin.getConfig().set("arananlar", new ArrayList<>(arananOyuncular));
        plugin.saveConfig();
    }

    public void veriYukle() {
        if (plugin.getConfig().contains("jail.cells")) {
            for (String cellName : plugin.getConfig().getConfigurationSection("jail.cells").getKeys(false)) {
                Location loc = new Location(
                    Bukkit.getWorld(plugin.getConfig().getString("jail.cells." + cellName + ".world")),
                    plugin.getConfig().getDouble("jail.cells." + cellName + ".x"),
                    plugin.getConfig().getDouble("jail.cells." + cellName + ".y"),
                    plugin.getConfig().getDouble("jail.cells." + cellName + ".z"),
                    (float) plugin.getConfig().getDouble("jail.cells." + cellName + ".yaw"),
                    (float) plugin.getConfig().getDouble("jail.cells." + cellName + ".pitch")
                );
                jailCells.put(cellName, loc);
            }
        }
        
        if (plugin.getConfig().contains("jailed_players")) {
            for (String uuidStr : plugin.getConfig().getConfigurationSection("jailed_players").getKeys(false)) {
                jailedPlayers.put(UUID.fromString(uuidStr), plugin.getConfig().getInt("jailed_players." + uuidStr));
            }
        }
        
        if (plugin.getConfig().contains("pre_jail_locations")) {
             for (String uuidStr : plugin.getConfig().getConfigurationSection("pre_jail_locations").getKeys(false)) {
                 Location loc = new Location(
                     Bukkit.getWorld(plugin.getConfig().getString("pre_jail_locations." + uuidStr + ".world")),
                     plugin.getConfig().getDouble("pre_jail_locations." + uuidStr + ".x"),
                     plugin.getConfig().getDouble("pre_jail_locations." + uuidStr + ".y"),
                     plugin.getConfig().getDouble("pre_jail_locations." + uuidStr + ".z")
                 );
                 preJailLocations.put(UUID.fromString(uuidStr), loc);
             }
        }

        // ESKİ MESLEK HAFIZASINI SUNUCU AÇILIRKEN YÜKLÜYORUZ
        if (plugin.getConfig().contains("pre_jail_roles")) {
            for (String uuidStr : plugin.getConfig().getConfigurationSection("pre_jail_roles").getKeys(false)) {
                preJailRoles.put(UUID.fromString(uuidStr), plugin.getConfig().getString("pre_jail_roles." + uuidStr));
            }
        }

        if (plugin.getConfig().contains("amir_kursusu.world")) {
            org.bukkit.World w = Bukkit.getWorld(plugin.getConfig().getString("amir_kursusu.world"));
            if (w != null) {
                amirKursuKonumu = new Location(w, plugin.getConfig().getDouble("amir_kursusu.x"), plugin.getConfig().getDouble("amir_kursusu.y"), plugin.getConfig().getDouble("amir_kursusu.z"));
            }
        }

        if (plugin.getConfig().contains("sikayetler")) {
            for (String sid : plugin.getConfig().getConfigurationSection("sikayetler").getKeys(false)) {
                UUID id = UUID.fromString(sid);
                String eden = plugin.getConfig().getString("sikayetler." + sid + ".eden");
                String edilen = plugin.getConfig().getString("sikayetler." + sid + ".edilen");
                String sebep = plugin.getConfig().getString("sikayetler." + sid + ".sebep");
                aktifSikayetler.put(id, new Sikayet(id, eden, edilen, sebep));
            }
        }

        if (plugin.getConfig().contains("arananlar")) {
            arananOyuncular.addAll(plugin.getConfig().getStringList("arananlar"));
        }
    }

    public static class Sikayet {
        UUID id; String sikayetEden; String sikayetEdilen; String sebep;
        public Sikayet(UUID id, String eden, String edilen, String sebep) {
            this.id = id; this.sikayetEden = eden; this.sikayetEdilen = edilen; this.sebep = sebep;
        }
    }
}