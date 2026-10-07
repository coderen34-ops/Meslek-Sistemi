package me.mesleksistemi.sistem;

import me.mesleksistemi.MeslekSistemi;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MenuManager implements Listener {

    private final MeslekSistemi plugin;

    public MenuManager(MeslekSistemi plugin) {
        this.plugin = plugin;
    }

    public void openMeslekMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Meslek Basvurusu");
        int slot = 0;
        for (Map.Entry<String, Double> entry : plugin.meslekFiyatlari.entrySet()) {
            if(slot > 24) break; 
            ItemStack item = new ItemStack(Material.PAPER); 
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + plugin.meslekGorunumAdlari.get(entry.getKey()));
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "Basvuru Ucreti: " + ChatColor.GREEN + "$" + entry.getValue(),
                    ChatColor.GRAY + "Basvurmak icin tikla."
            ));
            item.setItemMeta(meta); gui.setItem(slot, item); slot++;
        }
        
        ItemStack iptal = new ItemStack(Material.REDSTONE_TORCH); ItemMeta iptalMeta = iptal.getItemMeta();
        iptalMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Basvuruyu Iptal Et");
        iptalMeta.setLore(Arrays.asList(ChatColor.GRAY + "Bekleyen basvurunuzu aninda iptal eder."));
        iptal.setItemMeta(iptalMeta); gui.setItem(25, iptal);
        
        ItemStack istifa = new ItemStack(Material.BARRIER); ItemMeta iMeta = istifa.getItemMeta();
        iMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Meslekten Istifa Et");
        iMeta.setLore(Arrays.asList(ChatColor.GRAY + "Vatandas statusune donersiniz."));
        istifa.setItemMeta(iMeta); gui.setItem(26, istifa);
        
        player.openInventory(gui);
    }

    public void openBasvuruMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Bekleyen Basvurular");
        int slot = 0;
        for (Map.Entry<UUID, MeslekSistemi.Basvuru> entry : plugin.aktifBasvurular.entrySet()) {
            if(slot > 53) break;
            MeslekSistemi.Basvuru b = entry.getValue();
            ItemStack head = new ItemStack(Material.PLAYER_HEAD); SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.getKey()));
            meta.setDisplayName(ChatColor.YELLOW + b.oyuncuAdi);
            meta.setLore(Arrays.asList(
                    ChatColor.AQUA + "Istenen Meslek: " + ChatColor.WHITE + plugin.meslekGorunumAdlari.get(b.meslek),
                    ChatColor.GRAY + "Okumak icin tikla."
            ));
            head.setItemMeta(meta); gui.setItem(slot, head); slot++;
        }
        player.openInventory(gui);
    }

    public void openBankaMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_AQUA + "Banka Islemleri");
        UUID pId = player.getUniqueId(); double bakiye = plugin.bankaHesaplari.getOrDefault(pId, 0.0);

        ItemStack head = new ItemStack(Material.PLAYER_HEAD); SkullMeta hMeta = (SkullMeta) head.getItemMeta();
        hMeta.setOwningPlayer(player); hMeta.setDisplayName(ChatColor.GOLD + "Dijital Bakiyen:");
        hMeta.setLore(Arrays.asList(ChatColor.GREEN + "$" + bakiye)); head.setItemMeta(hMeta);
        gui.setItem(4, head);

        ItemStack yatir = new ItemStack(Material.PAPER); ItemMeta yMeta = yatir.getItemMeta();
        yMeta.setDisplayName(ChatColor.GREEN + "Bankaya Para YATIR"); yatir.setItemMeta(yMeta); gui.setItem(11, yatir);

        ItemStack cek = new ItemStack(Material.GOLD_NUGGET); ItemMeta cMeta = cek.getItemMeta();
        cMeta.setDisplayName(ChatColor.RED + "Bankadan Para CEK"); cek.setItemMeta(cMeta); gui.setItem(15, cek);

        gui.setItem(18, getBorsaItem(Material.DIAMOND, "Elmas (Belediye)", 300, 50));
        gui.setItem(19, getBorsaItem(Material.EMERALD, "Zumrut (Belediye)", 250, 40));
        gui.setItem(20, getBorsaItem(Material.GOLD_INGOT, "Altin (Belediye)", 150, 20));
        gui.setItem(21, getBorsaItem(Material.IRON_INGOT, "Demir (Belediye)", 100, 5));

        player.openInventory(gui);
    }

    public void openTapuMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "Tapu Mudurlugu");
        boolean ilkAlimYaptiMi = plugin.tapuSahipleri.contains(player.getUniqueId());

        if (!ilkAlimYaptiMi) {
            ItemStack ilkTapu = new ItemStack(Material.MAP);
            ItemMeta ilkMeta = ilkTapu.getItemMeta();
            ilkMeta.setDisplayName(ChatColor.AQUA + "Ilk Arsa Tapusunu Al");
            ilkMeta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Blok Basi Fiyat: " + ChatColor.GREEN + "$" + plugin.ilkTapuBlokFiyati,
                ChatColor.GRAY + "Tiklayarak miktar belirleyin."
            ));
            ilkTapu.setItemMeta(ilkMeta);
            gui.setItem(11, ilkTapu);
        } else {
            ItemStack genisletme = new ItemStack(Material.FILLED_MAP);
            ItemMeta genMeta = genisletme.getItemMeta();
            genMeta.setDisplayName(ChatColor.GOLD + "Arsa Genislet (Blok Al)");
            genMeta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Blok Basi Fiyat: " + ChatColor.GREEN + "$" + plugin.genisletmeBlokFiyati,
                ChatColor.GRAY + "Tiklayarak miktar belirleyin."
            ));
            genisletme.setItemMeta(genMeta);
            gui.setItem(11, genisletme); 
        }

        ItemStack devir = new ItemStack(Material.BOOK);
        ItemMeta devirMeta = devir.getItemMeta();
        devirMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Arsa Satis / Devir Islemleri");
        devirMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Arsanizi baska bir oyuncuya satmak",
            ChatColor.GRAY + "veya devretmek icin buraya tiklayin."
        ));
        devir.setItemMeta(devirMeta);
        gui.setItem(15, devir);

        player.openInventory(gui);
    }

    // Oyuncunun envanterine bu malzemeden kaç adet daha sığar
    private int bosYer(Player player, Material mat) {
        int yer = 0;
        for (ItemStack i : player.getInventory().getStorageContents()) {
            if (i == null || i.getType() == Material.AIR) yer += mat.getMaxStackSize();
            else if (i.getType() == mat && !i.hasItemMeta()) yer += Math.max(0, i.getMaxStackSize() - i.getAmount());
        }
        return yer;
    }

    private ItemStack getBorsaItem(Material mat, String name, int alis, int satis) {
        ItemStack item = new ItemStack(mat); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + name);
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Sol Tik: 1 Al ($" + alis + ")", ChatColor.GRAY + "Shift + Sol Tik: 64 Al", ChatColor.GRAY + "Sag Tik: 1 Sat (+$" + satis + ")", ChatColor.GRAY + "Shift + Sag Tik: Hepsini Sat", ChatColor.DARK_GRAY + "Blok sıkıştırma aktiftir."));
        item.setItemMeta(meta); return item;
    }

    public void openBasvuruKitabi(Player baskan, UUID basvuranUUID, MeslekSistemi.Basvuru basvuru) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK); BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Basvuru: " + basvuru.oyuncuAdi); meta.setAuthor(basvuru.oyuncuAdi);

        List<BaseComponent[]> pages = new ArrayList<>();
        String sebep = basvuru.sebep; List<String> chunks = new ArrayList<>();
        int index = 0;
        while (index < sebep.length()) {
            int end = Math.min(index + 150, sebep.length());
            if (end < sebep.length()) { int ls = sebep.lastIndexOf(' ', end); if (ls > index) end = ls; }
            chunks.add(sebep.substring(index, end).trim()); index = end;
        }

        TextComponent s1 = new TextComponent(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + "BASVURU\n\n");
        s1.addExtra(ChatColor.BLACK + "Meslek: " + ChatColor.DARK_GRAY + plugin.meslekGorunumAdlari.get(basvuru.meslek) + "\n\n");
        if (!chunks.isEmpty()) s1.addExtra(ChatColor.BLACK + chunks.get(0));
        pages.add(new BaseComponent[]{s1});
        for (int i = 1; i < chunks.size(); i++) pages.add(new BaseComponent[]{new TextComponent(ChatColor.BLACK + chunks.get(i))});

        TextComponent son = new TextComponent(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + "KARAR\n\n");
        TextComponent kabul = new TextComponent(ChatColor.DARK_GREEN + ChatColor.BOLD.toString() + "[KABUL ET]\n\n");
        kabul.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/basvurucevapla " + basvuranUUID.toString() + " kabulet"));
        TextComponent red = new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "[REDDET]");
        red.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/basvurucevapla " + basvuranUUID.toString() + " reddet"));
        
        son.addExtra(kabul); son.addExtra(red); pages.add(new BaseComponent[]{son});
        for (BaseComponent[] p : pages) meta.spigot().addPage(p);
        book.setItemMeta(meta); baskan.openBook(book);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked(); String title = event.getView().getTitle();
        UUID pId = player.getUniqueId();

        // TAPU MÜDÜRLÜĞÜ MENÜSÜ
        if (title.equals(ChatColor.GOLD + "Tapu Mudurlugu")) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta() || clicked.getType() == Material.BARRIER) return;

            if (event.getRawSlot() == 15 && clicked.getType() == Material.BOOK) {
                player.closeInventory();
                player.sendMessage(ChatColor.AQUA + "--- ARSA DEVİR İŞLEMLERİ ---");
                player.sendMessage(ChatColor.YELLOW + "Arsanızı başka birine devretmek için arsanın içinde durarak şu komutu yazın:");
                player.sendMessage(ChatColor.WHITE + "/TransferClaim <OyuncuAdı>");
                return;
            }

            if (plugin.getKasa() == null) {
                player.sendMessage(ChatColor.RED + "Belediye kasasi aktif degil, islem yapilamiyor! (Baskan once /kasaayarla yapmali)");
                player.closeInventory();
                return;
            }

            if (event.getRawSlot() == 11) {
                if (clicked.getType() == Material.MAP) { 
                    plugin.tapuIslemBekleyenler.put(pId, "ILK");
                    player.closeInventory();
                    player.sendMessage(ChatColor.GOLD + "Kac blok arsa almak istiyorsunuz? (Chat'e rakamla yazin veya 'iptal' deyin)");
                } 
                else if (clicked.getType() == Material.FILLED_MAP) { 
                    plugin.tapuIslemBekleyenler.put(pId, "GENISLETME");
                    player.closeInventory();
                    player.sendMessage(ChatColor.GOLD + "Kac blok genisletmek istiyorsunuz? (Chat'e rakamla yazin veya 'iptal' deyin)");
                }
            }
            return;
        }

        // MESLEK BAŞVURUSU MENÜSÜ
        if (title.equals(ChatColor.DARK_GREEN + "Meslek Basvurusu")) {
            event.setCancelled(true);
            // Sadece menüdeki eşyalar: oyuncunun kendi envanterindeki benzer isimli eşyalar sayılmaz
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String tiklananIsim = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());

            if (clicked.getType() == Material.REDSTONE_TORCH && tiklananIsim.equals("Basvuruyu Iptal Et")) {
                if (plugin.aktifBasvurular.containsKey(pId) || plugin.basvuruBekleyenler.containsKey(pId)) {
                    plugin.aktifBasvurular.remove(pId); plugin.basvuruBekleyenler.remove(pId); plugin.veriKaydet();
                    player.sendMessage(ChatColor.GREEN + "Basvurunuz iptal edildi.");
                }
                player.closeInventory(); return;
            }

            if (clicked.getType() == Material.BARRIER && tiklananIsim.equals("Meslekten Istifa Et")) {
                String mevcutMeslek = plugin.oyuncuMeslekCache.getOrDefault(pId, "vatandas");
                if (mevcutMeslek.equals("vatandas") || mevcutMeslek.equals("gocmen")) {
                    player.sendMessage(ChatColor.RED + "Istifa edilecek durumda degilsiniz.");
                } else {
                    plugin.oyuncuMeslekCache.put(pId, "vatandas");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent set vatandas");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + player.getName() + " vatandas");
                    player.sendMessage(ChatColor.GREEN + "Istifa ettiniz."); 
                    plugin.veriKaydet();

                    if (mevcutMeslek.equals("belediyebaskani")) {
                        Bukkit.broadcastMessage("");
                        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "★★★ " + ChatColor.RED + "SON DAKİKA HABERİ " + ChatColor.DARK_RED + ChatColor.BOLD + "★★★");
                        Bukkit.broadcastMessage(ChatColor.GOLD + "Belediye Başkanı " + ChatColor.YELLOW + player.getName() + ChatColor.GOLD + " görevinden " + ChatColor.RED + "İSTİFA ETTİ!");
                        Bukkit.broadcastMessage(ChatColor.GRAY + "Şehir yeni liderini bekliyor...");
                        Bukkit.broadcastMessage("");

                        // EKRANIN ORTASINA ÇIKAN DEV YAZI
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            online.sendTitle(
                                ChatColor.DARK_RED + "" + ChatColor.BOLD + "BAŞKAN İSTİFA ETTİ", 
                                ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " görevi bıraktı!", 
                                10, 70, 20
                            );
                        }
                    }
                }
                player.closeInventory(); return;
            }

            String secilenKodu = "";
            for(Map.Entry<String, String> entry : plugin.meslekGorunumAdlari.entrySet()){
                if(entry.getValue().equals(tiklananIsim)){ secilenKodu = entry.getKey(); break; }
            }
            if(secilenKodu.isEmpty()) return;

            if (plugin.processPaymentToKasa(player, plugin.meslekFiyatlari.get(secilenKodu))) {
                plugin.basvuruBekleyenler.put(pId, secilenKodu); player.closeInventory();
                player.sendMessage(ChatColor.AQUA + "Lutfen chate " + plugin.meslekGorunumAdlari.get(secilenKodu) + " olma sebebinizi yazin:");
            } else { player.closeInventory(); }
        }

        // BANKA İŞLEMLERİ MENÜSÜ
        if (title.equals(ChatColor.DARK_AQUA + "Banka Islemleri")) {
            event.setCancelled(true);
            
            // Eğer oyuncu alt envanterine (kendi çantasına) tıkladıysa işlem yapma
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) return;
            
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            
            if (event.getRawSlot() == 11) { 
                plugin.bankaIslemBekleyenler.put(pId, "YATIR"); player.closeInventory();
                player.sendMessage(ChatColor.GOLD + "Yatirmak istediginiz miktari yazin:"); return;
            }
            if (event.getRawSlot() == 15) {
                plugin.bankaIslemBekleyenler.put(pId, "CEK"); player.closeInventory();
                player.sendMessage(ChatColor.GOLD + "Cekmek istediginiz miktari yazin:"); return;
            }

            // BELEDİYE BORSASI: Ürün stoğu da para da Belediye Kasası'ndadır.
            // Oyuncu alırken ödediği para kasaya girer; satarken parası kasadan ödenir.
            if (event.getRawSlot() >= 18 && event.getRawSlot() <= 21) {
                Material mat = clicked.getType(); 
                Material blockMat = plugin.getBlockMaterial(mat);
                if (blockMat == null) return;
                int alis = (mat == Material.DIAMOND) ? 300 : (mat == Material.EMERALD) ? 250 : (mat == Material.GOLD_INGOT) ? 150 : 100;
                int satis = (mat == Material.DIAMOND) ? 50 : (mat == Material.EMERALD) ? 40 : (mat == Material.GOLD_INGOT) ? 20 : 5;

                Chest kasa = plugin.getKasa();
                if (kasa == null) {
                    player.sendMessage(ChatColor.RED + "Kasa bulunamadi!"); player.closeInventory(); return;
                }
                double bakiye = plugin.bankaHesaplari.getOrDefault(pId, 0.0);
                ClickType click = event.getClick();

                // DURUM 1: OYUNCU BORSADAN ALIYOR (SOL / SHIFT SOL)
                if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
                    int alinacakMiktar = (click == ClickType.SHIFT_LEFT) ? 64 : 1;
                    int stok = plugin.getChestStock(kasa.getInventory(), mat, blockMat);
                    
                    if (stok < alinacakMiktar) alinacakMiktar = stok; // Stok yetmiyorsa yettiği kadar al
                    
                    if (alinacakMiktar <= 0) {
                        player.sendMessage(ChatColor.RED + "Belediye kasasında " + mat.name() + " stoku tükenmiş!");
                        player.closeInventory();
                        return;
                    }
                    
                    double toplamTutar = alinacakMiktar * alis;
                    if (bakiye < toplamTutar) {
                        player.sendMessage(ChatColor.RED + "Yeterli paranız yok! Gerekli: $" + toplamTutar);
                        return;
                    }
                    if (bosYer(player, mat) < alinacakMiktar) {
                        player.sendMessage(ChatColor.RED + "Envanterinizde yeterli boş yer yok!");
                        return;
                    }

                    // Önce stok kasadan düşülür (yer açılır), sonra para kasaya girer
                    plugin.setChestStock(kasa.getInventory(), mat, blockMat, stok - alinacakMiktar);
                    if (!plugin.kasayaParaEkle(toplamTutar)) {
                        plugin.setChestStock(kasa.getInventory(), mat, blockMat, stok);
                        player.sendMessage(ChatColor.RED + "Belediye kasası dolu, işlem yapılamadı!");
                        return;
                    }
                    plugin.bankaHesaplari.put(pId, bakiye - toplamTutar);
                    player.getInventory().addItem(new ItemStack(mat, alinacakMiktar));
                    plugin.veriKaydet();
                    
                    player.sendMessage(ChatColor.GREEN + "" + alinacakMiktar + " adet satın aldınız. Ödenen: $" + toplamTutar); 
                    openBankaMenu(player); 
                } 
                // DURUM 2: OYUNCU BORSAYA SATIYOR (SAĞ / SHIFT SAĞ)
                else if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                    int oyuncudakiMiktar = 0;
                    for (ItemStack item : player.getInventory().getContents()) {
                        if (item != null && item.getType() == mat) {
                            oyuncudakiMiktar += item.getAmount();
                        }
                    }

                    if (oyuncudakiMiktar <= 0) {
                        player.sendMessage(ChatColor.RED + "Envanterinizde satacak " + mat.name() + " bulunmuyor!");
                        return;
                    }

                    int satilacakMiktar = (click == ClickType.SHIFT_RIGHT) ? oyuncudakiMiktar : 1;
                    int stok = plugin.getChestStock(kasa.getInventory(), mat, blockMat);
                    double kazanilanPara = satilacakMiktar * satis;

                    if (!plugin.stokSigarMi(kasa.getInventory(), mat, blockMat, stok + satilacakMiktar)) {
                        player.sendMessage(ChatColor.RED + "Belediye kasası tamamen dolu!"); 
                        return;
                    }
                    if (!plugin.kasadanParaCek(kazanilanPara)) {
                        player.sendMessage(ChatColor.RED + "Belediye kasasında bu alımı karşılayacak nakit yok! Başkan kasaya para koymalı.");
                        return;
                    }

                    plugin.removeItemFromInventory(player.getInventory(), mat, satilacakMiktar);
                    plugin.setChestStock(kasa.getInventory(), mat, blockMat, stok + satilacakMiktar);
                    plugin.bankaHesaplari.put(pId, bakiye + kazanilanPara);
                    plugin.veriKaydet();
                    
                    player.sendMessage(ChatColor.GREEN + "" + satilacakMiktar + " adet sattınız. Kazanılan: $" + kazanilanPara); 
                    openBankaMenu(player); 
                }
            }
            return;
        }

        // BEKLEYEN BAŞVURULAR (BAŞKAN) MENÜSÜ
        if (title.equals(ChatColor.DARK_RED + "Bekleyen Basvurular")) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
            SkullMeta meta = (SkullMeta) clicked.getItemMeta();
            if (meta.getOwningPlayer() == null) return;
            UUID basvuranUUID = meta.getOwningPlayer().getUniqueId();
            if (plugin.aktifBasvurular.containsKey(basvuranUUID)) openBasvuruKitabi(player, basvuranUUID, plugin.aktifBasvurular.get(basvuranUUID));
        }
    }
}