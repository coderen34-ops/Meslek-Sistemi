package me.mesleksistemi.adliye;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.mesleksistemi.MeslekSistemi;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public class AdliyeListener implements Listener {

    private final MeslekSistemi plugin;
    private final AdliyeManager am;

    private static final java.util.Set<String> ADLIYE_MENULERI = java.util.Set.of(
            ChatColor.DARK_RED + "Adalet Sarayı",
            ChatColor.DARK_RED + "Kimi Şikayet Edeceksiniz?",
            ChatColor.GOLD + "Bekleyen Davalar (Avukat)",
            ChatColor.GOLD + "Sanık Savunması Üstlen",
            ChatColor.DARK_RED + "Hakim Kürsüsü",
            ChatColor.DARK_RED + "Karar Ver");

    public AdliyeListener(MeslekSistemi plugin, AdliyeManager am) {
        this.plugin = plugin;
        this.am = am;
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            if (npc.getPersistentDataContainer().has(am.adliyeNpcKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                openAdliyeAnaMenu(event.getPlayer());
            }
        }
    }

    private void openAdliyeAnaMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_RED + "Adalet Sarayı");
        ItemStack davaAc = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta davaMeta = davaAc.getItemMeta();
        davaMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Yeni Dava Aç");
        davaMeta.setLore(List.of(ChatColor.GRAY + "Bir oyuncudan şikayetçi olup", ChatColor.GRAY + "tazminat talep etmek için tıklayın."));
        davaAc.setItemMeta(davaMeta);

        ItemStack avukatMenu = new ItemStack(Material.PAPER);
        ItemMeta avukatMeta = avukatMenu.getItemMeta();
        avukatMeta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Avukat Portalı");
        avukatMeta.setLore(List.of(ChatColor.GRAY + "Sadece avukatlar erişebilir."));
        avukatMenu.setItemMeta(avukatMeta);

        ItemStack hakimMenu = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta hakimMeta = hakimMenu.getItemMeta();
        hakimMeta.setDisplayName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Hakim Kürsüsü");
        hakimMeta.setLore(List.of(ChatColor.GRAY + "Sadece hakimler erişebilir."));
        hakimMenu.setItemMeta(hakimMeta);

        gui.setItem(11, davaAc);
        gui.setItem(13, avukatMenu);
        gui.setItem(15, hakimMenu);
        player.openInventory(gui);
    }

    private void openSikayetEdileceklerMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Kimi Şikayet Edeceksiniz?");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(player.getUniqueId())) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(p);
            meta.setDisplayName(ChatColor.YELLOW + p.getName());
            meta.setLore(List.of(ChatColor.GRAY + "Dava açmak için tıklayın."));
            head.setItemMeta(meta);
            gui.addItem(head);
        }
        player.openInventory(gui);
    }

    private void openAvukatMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.GOLD + "Bekleyen Davalar (Avukat)");
        int slot = 0;
        for (DavaDosyasi dava : am.aktifDavalar.values()) {
            if (slot > 53) break;
            if (dava.durum == DavaDurumu.MUSTEKI_AVUKATI_BEKLIYOR || dava.durum == DavaDurumu.SANIK_AVUKATI_BEKLIYOR) {
                if (dava.musteki.equalsIgnoreCase(player.getName()) || dava.sanik.equalsIgnoreCase(player.getName())) continue;
                if (player.getName().equalsIgnoreCase(dava.mustekiAvukati)) continue;

                ItemStack item = new ItemStack(Material.PAPER);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.YELLOW + "Dava No: " + dava.id.toString().substring(0, 5));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Müşteki: " + ChatColor.RED + dava.musteki);
                lore.add(ChatColor.GRAY + "Sanık: " + ChatColor.RED + dava.sanik);
                lore.add(ChatColor.GRAY + "İstenen Tazminat: " + ChatColor.GOLD + "$" + dava.talepEdilenMiktar);
                lore.add("");
                lore.add(ChatColor.DARK_AQUA + "Olay Detayı: " + ChatColor.WHITE + dava.sebep);
                lore.add("");
                lore.add(dava.durum == DavaDurumu.MUSTEKI_AVUKATI_BEKLIYOR ? ChatColor.GREEN + "► Müşteki (Şikayetçi) Avukatı Aranıyor" : ChatColor.GREEN + "► Sanık Avukatı Aranıyor");
                lore.add(ChatColor.DARK_GRAY + "Üstlenmek için tıklayın.");
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "dava_id"), PersistentDataType.STRING, dava.id.toString());
                item.setItemMeta(meta);
                gui.setItem(slot, item);
                slot++;
            }
        }
        player.openInventory(gui);
    }

    private void openSanikAvukatiOnayMenu(Player player, UUID davaId) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "Sanık Savunması Üstlen");
        ItemStack kabul = new ItemStack(Material.LIME_DYE);
        ItemMeta kMeta = kabul.getItemMeta();
        kMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "DAVAYI ÜSTLEN");
        kMeta.setLore(List.of(ChatColor.GRAY + "Sanığı savunmayı kabul edersiniz.", ChatColor.GRAY + "Kazanırsanız devletten $2500 prim alırsınız."));
        kMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "dava_id"), PersistentDataType.STRING, davaId.toString());
        kabul.setItemMeta(kMeta);

        ItemStack ret = new ItemStack(Material.RED_DYE);
        ItemMeta rMeta = ret.getItemMeta();
        rMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "İPTAL");
        ret.setItemMeta(rMeta);

        gui.setItem(11, kabul);
        gui.setItem(15, ret);
        player.openInventory(gui);
    }

    private void openHakimMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Hakim Kürsüsü");
        int slot = 0;
        for (DavaDosyasi dava : am.aktifDavalar.values()) {
            if (slot > 53) break;
            if (dava.durum == DavaDurumu.HAKIM_BEKLIYOR || dava.durum == DavaDurumu.DURUSMADA) {
                if (davadaTarafMi(player.getName(), dava)) continue;
                // Süren duruşmayı sadece yöneten hakim görür
                if (dava.durum == DavaDurumu.DURUSMADA && dava.hakim != null && !dava.hakim.equalsIgnoreCase(player.getName())) continue;
                ItemStack item = new ItemStack(Material.BOOK);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.DARK_RED + "Dava No: " + dava.id.toString().substring(0, 5));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Müşteki: " + ChatColor.YELLOW + dava.musteki + ChatColor.GRAY + " (Av: " + dava.mustekiAvukati + ")");
                lore.add(ChatColor.GRAY + "Sanık: " + ChatColor.YELLOW + dava.sanik + ChatColor.GRAY + " (Av: " + dava.sanikAvukati + ")");
                lore.add(ChatColor.GRAY + "Talep: " + ChatColor.GOLD + "$" + dava.talepEdilenMiktar);
                lore.add("");
                lore.add(dava.durum == DavaDurumu.HAKIM_BEKLIYOR ? ChatColor.RED + "► Duruşmayı başlatmak için tıkla." : ChatColor.GREEN + "► Karar vermek için tıkla.");
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "dava_id"), PersistentDataType.STRING, dava.id.toString());
                item.setItemMeta(meta);
                gui.setItem(slot, item);
                slot++;
            }
        }
        player.openInventory(gui);
    }

    private void openKararMenu(Player player, UUID davaId) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_RED + "Karar Ver");
        ItemStack mustekiHakli = new ItemStack(Material.LIME_DYE);
        ItemMeta mMeta = mustekiHakli.getItemMeta();
        mMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Müşteki (Şikayetçi) Haklı");
        mMeta.setLore(List.of(ChatColor.GRAY + "Sanığı suçlu bulur ve tazminat cezası keser."));
        mMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "dava_id"), PersistentDataType.STRING, davaId.toString());
        mustekiHakli.setItemMeta(mMeta);

        ItemStack sanikHakli = new ItemStack(Material.RED_DYE);
        ItemMeta sMeta = sanikHakli.getItemMeta();
        sMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Sanık Haklı (Suçsuz)");
        sMeta.setLore(List.of(ChatColor.GRAY + "Davayı düşürür, sanığı beraat ettirir."));
        sMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "dava_id"), PersistentDataType.STRING, davaId.toString());
        sanikHakli.setItemMeta(sMeta);

        gui.setItem(11, mustekiHakli);
        gui.setItem(15, sanikHakli);
        player.openInventory(gui);
    }

    private boolean davadaTarafMi(String isim, DavaDosyasi dava) {
        return isim.equalsIgnoreCase(dava.musteki) || isim.equalsIgnoreCase(dava.sanik)
                || isim.equalsIgnoreCase(dava.mustekiAvukati) || isim.equalsIgnoreCase(dava.sanikAvukati);
    }

    // Konum mahkeme salonunun (pos1-pos2) içinde mi? Farklı dünya = dışarıda.
    private boolean salonIcindeMi(Location to) {
        if (to.getWorld() == null || am.mahkemePos1.getWorld() == null || !to.getWorld().equals(am.mahkemePos1.getWorld())) return false;
        double minX = Math.min(am.mahkemePos1.getX(), am.mahkemePos2.getX());
        double maxX = Math.max(am.mahkemePos1.getX(), am.mahkemePos2.getX());
        double minY = Math.min(am.mahkemePos1.getY(), am.mahkemePos2.getY());
        double maxY = Math.max(am.mahkemePos1.getY(), am.mahkemePos2.getY());
        double minZ = Math.min(am.mahkemePos1.getZ(), am.mahkemePos2.getZ());
        double maxZ = Math.max(am.mahkemePos1.getZ(), am.mahkemePos2.getZ());
        return to.getX() >= minX && to.getX() <= maxX && to.getY() >= minY && to.getY() <= maxY && to.getZ() >= minZ && to.getZ() <= maxZ;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!am.durusmadakiOyuncular.contains(event.getPlayer().getUniqueId())) return;
        if (am.mahkemePos1 == null || am.mahkemePos2 == null) return;
        
        Location to = event.getTo();
        if (to == null) return;
        // Sadece blok değiştiğinde kontrol et (kafa çevirmek her tick tetikler)
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        
        if (!salonIcindeMi(to)) {
            event.getPlayer().sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "DURUŞMA BİTMEDEN SALONDAN AYRILAMAZSIN!");
            Vector geri = from.toVector().subtract(to.toVector());
            if (geri.lengthSquared() > 0) event.getPlayer().setVelocity(geri.normalize().multiply(0.5));
            event.setTo(from);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!am.durusmadakiOyuncular.contains(event.getPlayer().getUniqueId())) return;
        if (am.mahkemePos1 == null || am.mahkemePos2 == null) return;
        
        Location to = event.getTo();
        if (to == null) return;
        
        if (!salonIcindeMi(to)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "DURUŞMA BİTMEDEN IŞINLANAMAZ VEYA İNCİ ATAMAZSIN!");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        am.hakimAyrildi(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();
        if (!ADLIYE_MENULERI.contains(title)) return;
        event.setCancelled(true);
        // Sadece menüdeki eşyalar işlenir, oyuncunun kendi envanteri değil
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) return;

        if (title.equals(ChatColor.DARK_RED + "Adalet Sarayı")) {
            event.setCancelled(true);
            if (clicked.getType() == Material.WRITABLE_BOOK) {
                openSikayetEdileceklerMenu(player);
            } else if (clicked.getType() == Material.PAPER) {
                if (!plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas").equalsIgnoreCase("avukat")) {
                    player.sendMessage(ChatColor.RED + "Bu menüye sadece cübbeli Avukatlar girebilir!");
                    return;
                }
                openAvukatMenu(player);
            } else if (clicked.getType() == Material.NETHERITE_AXE) {
                if (!plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas").equalsIgnoreCase("hakim")) {
                    player.sendMessage(ChatColor.RED + "Bu menüye sadece Hakimler girebilir!");
                    return;
                }
                openHakimMenu(player);
            }
        } else if (title.equals(ChatColor.DARK_RED + "Kimi Şikayet Edeceksiniz?")) {
            event.setCancelled(true);
            if (clicked.getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) clicked.getItemMeta();
                if (meta.getOwningPlayer() != null) {
                    String hedefAd = meta.getOwningPlayer().getName();
                    am.geciciDavaHedefi.put(player.getUniqueId(), hedefAd);
                    am.sohbetDurumu.put(player.getUniqueId(), "DAVA_MIKTAR_BEKLIYOR");
                    player.closeInventory();
                    player.sendMessage(ChatColor.YELLOW + "=============================");
                    player.sendMessage(ChatColor.GREEN + "Sanık seçildi: " + ChatColor.RED + hedefAd);
                    player.sendMessage(ChatColor.GREEN + "Lütfen talep ettiğiniz " + ChatColor.GOLD + "tazminat miktarını " + ChatColor.GREEN + "rakamla yazın. (Örn: 5000)");
                    player.sendMessage(ChatColor.GRAY + "(İptal etmek için 'iptal' yazın)");
                }
            }
        } else if (title.equals(ChatColor.GOLD + "Bekleyen Davalar (Avukat)")) {
            event.setCancelled(true);
            if (clicked.getType() != Material.PAPER) return;
            PersistentDataContainer data = clicked.getItemMeta().getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(plugin, "dava_id");
            if (data.has(key, PersistentDataType.STRING)) {
                UUID davaId = UUID.fromString(data.get(key, PersistentDataType.STRING));
                DavaDosyasi dava = am.aktifDavalar.get(davaId);
                if (dava != null) {
                    if (dava.durum == DavaDurumu.MUSTEKI_AVUKATI_BEKLIYOR) {
                        am.avukatTeklifAsamasi.put(player.getUniqueId(), davaId);
                        player.closeInventory();
                        player.sendMessage(ChatColor.YELLOW + "=============================");
                        player.sendMessage(ChatColor.GREEN + "Müştekiyi savunmak için talep ettiğiniz " + ChatColor.GOLD + "Avukatlık Ücretini " + ChatColor.GREEN + "yazın.");
                        player.sendMessage(ChatColor.GRAY + "Kurallar gereği rakam 500$ ile 5000$ arasında olmalıdır.");
                        player.sendMessage(ChatColor.GRAY + "(İptal etmek için 'iptal' yazın)");
                    } else if (dava.durum == DavaDurumu.SANIK_AVUKATI_BEKLIYOR) {
                        openSanikAvukatiOnayMenu(player, davaId);
                    }
                }
            }
        } else if (title.equals(ChatColor.GOLD + "Sanık Savunması Üstlen")) {
            event.setCancelled(true);
            if (clicked.getType() == Material.LIME_DYE) {
                PersistentDataContainer data = clicked.getItemMeta().getPersistentDataContainer();
                NamespacedKey key = new NamespacedKey(plugin, "dava_id");
                if (data.has(key, PersistentDataType.STRING)) {
                    UUID davaId = UUID.fromString(data.get(key, PersistentDataType.STRING));
                    DavaDosyasi dava = am.aktifDavalar.get(davaId);
                    if (dava != null && dava.durum == DavaDurumu.SANIK_AVUKATI_BEKLIYOR) {
                        dava.sanikAvukati = player.getName();
                        dava.sanikAvukatiUcreti = 0.0;
                        dava.durum = DavaDurumu.HAKIM_BEKLIYOR;
                        player.closeInventory();
                        player.sendMessage(ChatColor.GREEN + "Bu davayı başarıyla üstlendiniz!");
                        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.YELLOW + "Dava No: " + dava.id.toString().substring(0, 5) + " için tüm taraflar hazırdır. Duruşma için Hakim bekleniyor!");
                        am.veriKaydetAdliye();
                    }
                }
            } else if (clicked.getType() == Material.RED_DYE) {
                player.closeInventory();
                player.sendMessage(ChatColor.RED + "Dava üstlenmekten vazgeçtiniz.");
            }
        } else if (title.equals(ChatColor.DARK_RED + "Hakim Kürsüsü")) {
            event.setCancelled(true);
            if (clicked.getType() != Material.BOOK) return;
            PersistentDataContainer data = clicked.getItemMeta().getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(plugin, "dava_id");
            if (data.has(key, PersistentDataType.STRING)) {
                UUID davaId = UUID.fromString(data.get(key, PersistentDataType.STRING));
                DavaDosyasi dava = am.aktifDavalar.get(davaId);
                if (dava != null) {
                    if (dava.durum == DavaDurumu.HAKIM_BEKLIYOR) {
                        if (am.mahkemeSalonu == null || am.mahkemePos1 == null || am.mahkemePos2 == null) {
                            player.sendMessage(ChatColor.RED + "Mahkeme salonu kordinatları ve sınırları (pos1/pos2) tam ayarlanmamış! Duruşma başlatılamıyor.");
                            return;
                        }
                        dava.durum = DavaDurumu.DURUSMADA;
                        dava.hakim = player.getName();
                        am.veriKaydetAdliye();
                        player.teleport(am.mahkemeSalonu);
                        am.durusmadakiOyuncular.add(player.getUniqueId());
                        am.durusmayaCek(dava, player); 
                        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.GOLD + dava.musteki + ChatColor.YELLOW + " ve " + ChatColor.GOLD + dava.sanik + ChatColor.YELLOW + " arasındaki duruşma Hakim " + player.getName() + " yönetiminde başlamıştır!");
                        player.closeInventory();
                    } else if (dava.durum == DavaDurumu.DURUSMADA) {
                        openKararMenu(player, davaId);
                    }
                }
            }
        } else if (title.equals(ChatColor.DARK_RED + "Karar Ver")) {
            event.setCancelled(true);
            PersistentDataContainer data = clicked.getItemMeta().getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(plugin, "dava_id");
            if (data.has(key, PersistentDataType.STRING)) {
                UUID davaId = UUID.fromString(data.get(key, PersistentDataType.STRING));
                DavaDosyasi dava = am.aktifDavalar.get(davaId);
                if (dava != null && dava.durum == DavaDurumu.DURUSMADA
                        && (dava.hakim == null || dava.hakim.equalsIgnoreCase(player.getName()))) {
                    player.closeInventory();
                    if (clicked.getType() == Material.LIME_DYE) { 
                        am.hakimKararAsamasi.put(player.getUniqueId(), davaId);
                        player.sendMessage(ChatColor.YELLOW + "--- KARAR AŞAMASI (1/2) ---");
                        player.sendMessage(ChatColor.GREEN + "Talep edilen tazminat: " + ChatColor.GOLD + "$" + dava.talepEdilenMiktar);
                        player.sendMessage(ChatColor.GRAY + "Lütfen uygun gördüğünüz tazminat miktarını sohbete yazın.");
                        player.sendMessage(ChatColor.RED + "(Talep edilenden fazlasını yazamazsınız!)");
                    } else if (clicked.getType() == Material.RED_DYE) { 
                        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.YELLOW + "Karar açıklandı! Sanık " + ChatColor.GREEN + dava.sanik + ChatColor.YELLOW + " beraat etti, dava düşürüldü.");
                        avukatPrimiOde(dava, dava.sanikAvukati, 2500.0, "Zorlu bir savunmayı başardığınız için Devlet size $");
                        am.durusmayiBitir(dava); 
                        am.durusmadakiOyuncular.remove(player.getUniqueId()); 
                        am.aktifDavalar.remove(davaId);
                        am.veriKaydetAdliye();
                    }
                }
            }
        }
    }

    // Chat olayı ayrı thread'den gelir: bekleyen adliye işlemi varsa mesaj chat'e düşmez,
    // işlem (banka, dava, hapis) ana thread'de yapılır.
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID pId = player.getUniqueId();
        if (!am.sohbetDurumu.containsKey(pId) && !am.avukatTeklifAsamasi.containsKey(pId) && !am.hakimKararAsamasi.containsKey(pId)) return;
        event.setCancelled(true);
        String mesaj = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) sohbetIsle(player, mesaj);
        });
    }

    private void sohbetIsle(Player player, String msg) {
        UUID pId = player.getUniqueId();

        if (am.sohbetDurumu.containsKey(pId)) {
            String durum = am.sohbetDurumu.get(pId);

            if (msg.equalsIgnoreCase("iptal")) {
                am.sohbetDurumu.remove(pId); am.geciciDavaHedefi.remove(pId); am.geciciDavaMiktari.remove(pId);
                player.sendMessage(ChatColor.RED + "İşlem iptal edildi.");
                return;
            }

            if (durum.equals("DAVA_MIKTAR_BEKLIYOR")) {
                try {
                    double miktar = MeslekSistemi.parsePara(msg);
                    if (miktar <= 0 || miktar > 1000000) {
                        player.sendMessage(ChatColor.RED + "Lütfen geçerli bir tazminat miktarı girin.");
                        return;
                    }
                    am.geciciDavaMiktari.put(pId, miktar);
                    am.sohbetDurumu.remove(pId);
                    
                    ItemStack davaKitabi = new ItemStack(Material.WRITABLE_BOOK);
                    ItemMeta meta = davaKitabi.getItemMeta();
                    meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Dava Dilekçesi");
                    meta.getPersistentDataContainer().set(am.davaKitabiKey, PersistentDataType.BYTE, (byte) 1);
                    davaKitabi.setItemMeta(meta);
                    
                    player.getInventory().addItem(davaKitabi);
                    player.sendMessage(ChatColor.YELLOW + "=============================");
                    player.sendMessage(ChatColor.GREEN + "Dava dilekçesi envanterinize eklendi!");
                    player.sendMessage(ChatColor.GRAY + "Kitabı elinize alın, olayı detaylıca yazın.");
                    player.sendMessage(ChatColor.GRAY + "İşiniz bitince " + ChatColor.RED + "'İmzala ve Kapat'" + ChatColor.GRAY + " butonuna basarak mahkemeye sunun.");
                    
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Lütfen sadece rakam girin! (Örn: 5000)");
                }
            }
        } else if (am.avukatTeklifAsamasi.containsKey(pId)) {
            if (msg.equalsIgnoreCase("iptal")) {
                am.avukatTeklifAsamasi.remove(pId);
                player.sendMessage(ChatColor.RED + "Teklif verme iptal edildi.");
                return;
            }
            try {
                double ucret = MeslekSistemi.parsePara(msg);
                if (ucret < 500 || ucret > 5000) {
                    player.sendMessage(ChatColor.RED + "Yasalara göre avukatlık ücreti 500$ ile 5000$ arasında olmalıdır!");
                    return;
                }
                UUID davaId = am.avukatTeklifAsamasi.get(pId);
                DavaDosyasi dava = am.aktifDavalar.get(davaId);
                am.avukatTeklifAsamasi.remove(pId);
                if (dava == null) {
                    player.sendMessage(ChatColor.RED + "Bu dava dosyası artık geçerli değil.");
                    return;
                }
                boolean isMusteki = (dava.durum == DavaDurumu.MUSTEKI_AVUKATI_BEKLIYOR);
                String hedefMusteriAd = isMusteki ? dava.musteki : dava.sanik;
                Player hedefMusteri = Bukkit.getPlayerExact(hedefMusteriAd);
                
                if (hedefMusteri == null || !hedefMusteri.isOnline()) {
                    player.sendMessage(ChatColor.RED + hedefMusteriAd + " şu an aktif değil. Lütfen oyuncu aktif olduğunda tekrar deneyin.");
                    return;
                }
                
                AvukatTeklifi teklif = new AvukatTeklifi(davaId, pId, player.getName(), ucret, isMusteki);
                am.bekleyenTeklifler.put(hedefMusteri.getUniqueId(), teklif);
                
                player.sendMessage(ChatColor.GREEN + hedefMusteriAd + " adlı oyuncuya " + ucret + "$ tutarında sözleşme teklifi iletildi.");
                hedefMusteri.sendMessage(ChatColor.GOLD + "=============================");
                hedefMusteri.sendMessage(ChatColor.GREEN + "Avukat " + ChatColor.YELLOW + player.getName() + ChatColor.GREEN + " davanızı " + ChatColor.GOLD + "$" + ucret + ChatColor.GREEN + " karşılığında üstlenmek istiyor.");
                
                TextComponent kabul = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "[KABUL ET VE ÖDE] ");
                kabul.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/avukatkabul"));
                
                TextComponent ret = new TextComponent(ChatColor.RED + "" + ChatColor.BOLD + "[REDDET]");
                ret.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/avukatred"));
                
                hedefMusteri.spigot().sendMessage(kabul, ret);
                hedefMusteri.sendMessage(ChatColor.GOLD + "=============================");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Lütfen sadece rakam girin!");
            }
        } else if (am.hakimKararAsamasi.containsKey(pId)) {
            UUID davaId = am.hakimKararAsamasi.get(pId);
            DavaDosyasi dava = am.aktifDavalar.get(davaId);
            
            if (dava == null) {
                am.hakimKararAsamasi.remove(pId);
                am.geciciHakimTazminat.remove(pId);
                return;
            }
            
            if (msg.equalsIgnoreCase("iptal")) {
                am.hakimKararAsamasi.remove(pId);
                am.geciciHakimTazminat.remove(pId);
                player.sendMessage(ChatColor.RED + "Karar verme iptal edildi. Kürsüden tekrar karar verebilirsiniz.");
                return;
            }

            if (!am.geciciHakimTazminat.containsKey(pId)) {
                try {
                    double tazminat = MeslekSistemi.parsePara(msg);
                    if (tazminat > dava.talepEdilenMiktar) {
                        player.sendMessage(ChatColor.RED + "Talep edilen miktardan ($" + dava.talepEdilenMiktar + ") fazlasına hükmedemezsiniz! Tekrar yazın:");
                        return;
                    }
                    if (tazminat < 0) {
                        player.sendMessage(ChatColor.RED + "Geçersiz miktar girdiniz.");
                        return;
                    }
                    am.geciciHakimTazminat.put(pId, tazminat);
                    player.sendMessage(ChatColor.YELLOW + "--- KARAR AŞAMASI (2/2) ---");
                    player.sendMessage(ChatColor.GREEN + "Tazminat onaylandı: $" + tazminat);
                    player.sendMessage(ChatColor.GRAY + "Şimdi lütfen sanık için verilecek " + ChatColor.RED + "HAPİS CEZASINI" + ChatColor.GRAY + " (Dakika cinsinden) yazın.");
                    player.sendMessage(ChatColor.RED + "(Hapis istemiyorsanız 0 yazın)");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Lütfen sadece rakam girin! (Örn: 3000)");
                }
            } else {
                try {
                    int hapisSuresi = Integer.parseInt(msg);
                    if (hapisSuresi < 0) hapisSuresi = 0; 
                    hapisSuresi = Math.min(hapisSuresi, Integer.MAX_VALUE / 60); // saniyeye çevrilirken taşmasın
                    
                    double tazminat = am.geciciHakimTazminat.get(pId);
                    am.hakimKararAsamasi.remove(pId);
                    am.geciciHakimTazminat.remove(pId);
                    
                    @SuppressWarnings("deprecation")
                    OfflinePlayer sanikOp = Bukkit.getOfflinePlayer(dava.sanik);
                    @SuppressWarnings("deprecation")
                    OfflinePlayer mustekiOp = Bukkit.getOfflinePlayer(dava.musteki);
                    
                    double sanikPara = plugin.bankaHesaplari.getOrDefault(sanikOp.getUniqueId(), 0.0);
                    double mustekiPara = plugin.bankaHesaplari.getOrDefault(mustekiOp.getUniqueId(), 0.0);

                    // Sanığın bakiyesi eksiye düşmesin: tazminat en fazla bakiyesi kadar tahsil edilir
                    // (müştekiye giden tutar çekilenle birebir aynı, hiç para basılmaz).
                    if (sanikOp.getUniqueId().equals(mustekiOp.getUniqueId())) tazminat = 0.0;
                    tazminat = Math.max(0.0, Math.min(tazminat, Math.max(0.0, sanikPara)));
                    if (tazminat > 0.0) {
                        plugin.bankaHesaplari.put(sanikOp.getUniqueId(), sanikPara - tazminat);
                        plugin.bankaHesaplari.put(mustekiOp.getUniqueId(), mustekiPara + tazminat);
                    }

                    avukatPrimiOde(dava, dava.mustekiAvukati, 1500.0, "Hukuk mücadelesini kazandığınız için Devlet size $");
                    plugin.veriKaydet();
                    
                    Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.YELLOW + "Karar açıklandı! Sanık " + ChatColor.RED + dava.sanik + ChatColor.YELLOW + " suçlu bulundu ve Müştekiye $" + ChatColor.GOLD + tazminat + ChatColor.YELLOW + " tazminat ödemeye mahkum edildi.");
                    
                    // Önce duruşma kapanır (taraflar salondan serbest kalır), sonra sanık hücreye gönderilir
                    am.durusmayiBitir(dava); 
                    am.durusmadakiOyuncular.remove(player.getUniqueId()); 
                    am.aktifDavalar.remove(davaId);
                    am.veriKaydetAdliye();

                    if (hapisSuresi > 0) {
                        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.RED + "Ayrıca sanık " + hapisSuresi + " dakika hapis cezasına çarptırıldı!");
                        if (plugin.polisManager == null || !plugin.polisManager.hapseGonder(sanikOp.getUniqueId(), sanikOp.getName(), hapisSuresi * 60)) {
                            player.sendMessage(ChatColor.RED + "Hapis cezası uygulanamadı: karakolda hiç hücre ayarlanmamış! (/hucreolustur)");
                        }
                    }
                    
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Lütfen sadece tam sayı girin! (Örn: 15 veya 0)");
                }
            }
        }
    }

    // Avukat primi belediye kasasından ödenir (para basılmaz). Avukat davanın tarafı veya hakimi ise prim yok.
    private void avukatPrimiOde(DavaDosyasi dava, String avukatAdi, double miktar, String mesajBasi) {
        if (avukatAdi == null) return;
        if (avukatAdi.equalsIgnoreCase(dava.musteki) || avukatAdi.equalsIgnoreCase(dava.sanik)
                || (dava.hakim != null && avukatAdi.equalsIgnoreCase(dava.hakim))) return;
        if (!plugin.kasadanParaCek(miktar)) return; // kasa yok veya bakiye yetmiyor: prim ödenmez
        @SuppressWarnings("deprecation")
        OfflinePlayer avukat = Bukkit.getOfflinePlayer(avukatAdi);
        double bakiye = plugin.bankaHesaplari.getOrDefault(avukat.getUniqueId(), 0.0);
        plugin.bankaHesaplari.put(avukat.getUniqueId(), bakiye + miktar);
        plugin.veriKaydet();
        if (avukat.isOnline() && avukat.getPlayer() != null) {
            avukat.getPlayer().sendMessage(ChatColor.GREEN + mesajBasi + ChatColor.GOLD + (long) miktar + ChatColor.GREEN + " prim ödedi!");
        }
    }

    @EventHandler
    public void onBookSign(PlayerEditBookEvent event) {
        if (event.isSigning()) {
            BookMeta meta = event.getNewBookMeta();
            if (meta.getPersistentDataContainer().has(am.davaKitabiKey, PersistentDataType.BYTE)) {
                Player player = event.getPlayer();
                UUID pId = player.getUniqueId();
                
                if (!am.geciciDavaHedefi.containsKey(pId) || !am.geciciDavaMiktari.containsKey(pId)) {
                    player.sendMessage(ChatColor.RED + "Dava verileriniz bulunamadı. Lütfen NPC'den tekrar dava açın.");
                    return;
                }
                
                String sanik = am.geciciDavaHedefi.get(pId);
                double tazminat = am.geciciDavaMiktari.get(pId);
                
                StringBuilder sebepBuilder = new StringBuilder();
                for (String sayfa : meta.getPages()) {
                    sebepBuilder.append(sayfa).append(" ");
                }
                String sebep = sebepBuilder.toString().trim();
                
                DavaDosyasi yeniDava = new DavaDosyasi(UUID.randomUUID(), player.getName(), sanik, tazminat, sebep);
                am.aktifDavalar.put(yeniDava.id, yeniDava);
                am.veriKaydetAdliye();
                
                am.geciciDavaHedefi.remove(pId);
                am.geciciDavaMiktari.remove(pId);
                
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                }, 1L);
                
                player.sendMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.GREEN + "Dava dosyanız başarıyla sisteme kaydedildi!");
                player.sendMessage(ChatColor.YELLOW + "Dosyanız Müşteki Avukatları havuzuna düştü. Bir avukatın davanızı üstlenmesini bekleyin.");
                
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String meslek = plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas");
                    if (meslek.equalsIgnoreCase("avukat")) {
                        p.sendMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.YELLOW + "Sisteme yeni bir dava dosyası düştü! NPC üzerinden kontrol edin.");
                    }
                }
            }
        }
    }
}