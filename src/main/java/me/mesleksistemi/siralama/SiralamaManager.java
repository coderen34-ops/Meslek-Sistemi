package me.mesleksistemi.siralama;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import me.mesleksistemi.MeslekSistemi;

/**
 * Sitedeki "Sıralama" sayfası için veri üretir: siralama.json (plugin klasörü) ve ayarlıysa GitHub deposuna yükler.
 * Ayarlar (config.yml):  siralama.aralik-dk (10),  siralama.github.token / repo / dal / dosya
 * İstatistikler dünya klasöründeki stats/*.json dosyalarından okunur (çevrimdışı oyuncular dahil).
 */
public class SiralamaManager {

    private static final int SATIR = 10;
    private final MeslekSistemi plugin;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private BukkitTask gorev;
    private volatile String sonYuklenenHash = "";

    public SiralamaManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        long aralik = Math.max(1, plugin.getConfig().getLong("siralama.aralik-dk", 10)) * 60L * 20L;
        this.gorev = Bukkit.getScheduler().runTaskTimer(plugin, this::guncelle, 20L * 60, aralik);
    }

    public void kapat() { if (gorev != null) gorev.cancel(); }

    /** Ana thread: bellekteki verileri al, dosya okumayı async yap. */
    public void guncelle() {
        final Map<UUID, Double> zengin = new HashMap<>(plugin.bankaHesaplari);
        final Map<UUID, Integer> sabika = new HashMap<>();
        ConfigurationSection sc = plugin.getConfig().getConfigurationSection("sabika");
        if (sc != null) {
            for (String k : sc.getKeys(false)) {
                try { int v = sc.getInt(k); if (v > 0) sabika.put(UUID.fromString(k), v); } catch (Exception ignored) { }
            }
        }
        final File statKlasor = statKlasoru();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, long[]> stat = statOku(statKlasor);
            Bukkit.getScheduler().runTask(plugin, () -> olustur(zengin, sabika, stat));
        });
    }

    private File statKlasoru() {
        if (Bukkit.getWorlds().isEmpty()) return null;
        File w = Bukkit.getWorlds().get(0).getWorldFolder();
        File[] adaylar = { new File(w, "players/stats"), new File(w, "stats"), new File(w, "players/statistics") };
        File enDolu = null; int enCok = 0;
        for (File f : adaylar) {
            if (!f.isDirectory()) continue;
            String[] liste = f.list((d, n) -> n.endsWith(".json"));
            int n = liste == null ? 0 : liste.length;
            if (n > enCok) { enCok = n; enDolu = f; }   // boş klasörleri atla (Paper'da world/stats boş kalabiliyor)
        }
        return enDolu;
    }

    // [0]=oynama tick, [1]=oyuncu öldürme, [2]=ölüm, [3]=mob, [4]=kırılan blok
    private Map<UUID, long[]> statOku(File klasor) {
        Map<UUID, long[]> sonuc = new HashMap<>();
        if (klasor == null) return sonuc;
        File[] dosyalar = klasor.listFiles((d, n) -> n.endsWith(".json"));
        if (dosyalar == null) return sonuc;
        for (File f : dosyalar) {
            try {
                UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 5));
                JsonObject kok = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), JsonObject.class);
                JsonObject st = kok.has("stats") ? kok.getAsJsonObject("stats") : kok;
                long[] v = new long[5];
                JsonObject c = st.has("minecraft:custom") ? st.getAsJsonObject("minecraft:custom") : new JsonObject();
                v[0] = sayi(c, "minecraft:play_time", sayi(c, "minecraft:play_one_minute", 0));
                v[1] = sayi(c, "minecraft:player_kills", 0);
                v[2] = sayi(c, "minecraft:deaths", 0);
                v[3] = sayi(c, "minecraft:mob_kills", 0);
                if (st.has("minecraft:mined")) {
                    for (Map.Entry<String, JsonElement> e : st.getAsJsonObject("minecraft:mined").entrySet()) v[4] += e.getValue().getAsLong();
                }
                sonuc.put(id, v);
            } catch (Exception ignored) { }
        }
        return sonuc;
    }

    private static long sayi(JsonObject o, String k, long varsayilan) {
        try { return o.has(k) ? o.get(k).getAsLong() : varsayilan; } catch (Exception e) { return varsayilan; }
    }

    private String ad(UUID id) {
        String a = Bukkit.getOfflinePlayer(id).getName();
        return a != null ? a : id.toString().substring(0, 8);
    }

    private interface Deger { String bicim(double v); }

    private Map<String, Object> kategori(String id, String baslik, String aciklama, Map<UUID, ? extends Number> veri, Deger bicim) {
        List<Map.Entry<UUID, ? extends Number>> liste = new ArrayList<>();
        for (Map.Entry<UUID, ? extends Number> e : veri.entrySet()) if (e.getValue().doubleValue() > 0) liste.add(e);
        liste.sort((a, b) -> Double.compare(b.getValue().doubleValue(), a.getValue().doubleValue()));
        List<Map<String, Object>> satirlar = new ArrayList<>();
        int sira = 1;
        for (Map.Entry<UUID, ? extends Number> e : liste) {
            if (sira > SATIR) break;
            Map<String, Object> s = new HashMap<>();
            s.put("sira", sira++);
            s.put("ad", ad(e.getKey()));
            s.put("deger", bicim.bicim(e.getValue().doubleValue()));
            satirlar.add(s);
        }
        Map<String, Object> k = new java.util.LinkedHashMap<>();
        k.put("id", id); k.put("baslik", baslik); k.put("aciklama", aciklama); k.put("satirlar", satirlar);
        return k;
    }

    private static String sayiYaz(double v) {
        if (v >= 1_000_000_000) return String.format(java.util.Locale.US, "%.1fb", v / 1_000_000_000);
        if (v >= 1_000_000) return String.format(java.util.Locale.US, "%.1fm", v / 1_000_000);
        if (v >= 10_000) return String.format(java.util.Locale.US, "%.1fk", v / 1_000);
        return String.valueOf((long) v);
    }

    private void olustur(Map<UUID, Double> zengin, Map<UUID, Integer> sabika, Map<UUID, long[]> stat) {
        Map<UUID, Long> oyna = new HashMap<>(), oldur = new HashMap<>(), olum = new HashMap<>(), mob = new HashMap<>(), kir = new HashMap<>();
        for (Map.Entry<UUID, long[]> e : stat.entrySet()) {
            long[] v = e.getValue();
            oyna.put(e.getKey(), v[0] / 20L); oldur.put(e.getKey(), v[1]); olum.put(e.getKey(), v[2]);
            mob.put(e.getKey(), v[3]); kir.put(e.getKey(), v[4]);
        }
        List<Map<String, Object>> kat = new ArrayList<>();
        kat.add(kategori("zengin", "En Zenginler", "Banka bakiyesi en yüksek oyuncular.", zengin, v -> "$" + String.format(java.util.Locale.US, "%,.0f", v)));
        kat.add(kategori("sabika", "En Çok Sabıkası Olanlar", "Sabıka kaydı en kabarık oyuncular.", sabika, v -> (long) v + " sabıka"));
        kat.add(kategori("oynama", "En Çok Oynayanlar", "Toplam oynama süresi.", oyna, v -> { long s = (long) v; return (s / 86400) + " gün " + ((s % 86400) / 3600) + " saat"; }));
        kat.add(kategori("oldurme", "En Çok Öldürenler", "Diğer oyuncuları en çok öldürenler.", oldur, v -> sayiYaz(v) + " kill"));
        kat.add(kategori("olum", "En Çok Ölenler", "En çok ölen oyuncular.", olum, v -> sayiYaz(v) + " ölüm"));
        kat.add(kategori("mob", "En Çok Mob Avlayanlar", "Öldürülen canavar sayısı.", mob, v -> sayiYaz(v) + " mob"));
        kat.add(kategori("kirma", "En Çok Blok Kıranlar", "Kırılan toplam blok sayısı.", kir, v -> sayiYaz(v) + " blok"));
        Map<String, Object> kok = new java.util.LinkedHashMap<>();
        kok.put("guncelleme", ZonedDateTime.now(ZoneId.of("Europe/Istanbul")).format(DateTimeFormatter.ofPattern("d.M.yyyy HH:mm")));
        kok.put("kategoriler", kat);
        final String json = gson.toJson(kok);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> yaz(json));
    }

    private void yaz(String json) {
        try { Files.writeString(new File(plugin.getDataFolder(), "siralama.json").toPath(), json, StandardCharsets.UTF_8); }
        catch (Exception e) { plugin.getLogger().warning("siralama.json yazılamadı: " + e); }
        // Ayarlar ayrı dosyada (siralama.yml): config.yml sunucu kapanırken yeniden yazıldığı için elle eklenen satırlar silinebiliyor.
        YamlConfiguration ayar = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "siralama.yml"));
        String token = ayar.getString("github.token", plugin.getConfig().getString("siralama.github.token", ""));
        if (token == null || token.isBlank()) return;
        try {
            // Saat değiştikçe her seferinde commit atmamak için içerik (saat hariç) aynıysa yükleme.
            String hash = Integer.toHexString(json.replaceAll("\"guncelleme\": ?\"[^\"]*\"", "").hashCode());
            if (hash.equals(sonYuklenenHash)) return;
            String repo = ayar.getString("github.repo", plugin.getConfig().getString("siralama.github.repo", "coderen34-ops/Meslek-Sistemi"));
            String dal = ayar.getString("github.dal", plugin.getConfig().getString("siralama.github.dal", "siralama-veri"));
            String yol = ayar.getString("github.dosya", plugin.getConfig().getString("siralama.github.dosya", "siralama.json"));
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            String url = "https://api.github.com/repos/" + repo + "/contents/" + yol;
            HttpRequest get = HttpRequest.newBuilder(URI.create(url + "?ref=" + dal)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json").GET().build();
            HttpResponse<String> gr = http.send(get, HttpResponse.BodyHandlers.ofString());
            String sha = null;
            if (gr.statusCode() == 200) sha = gson.fromJson(gr.body(), JsonObject.class).get("sha").getAsString();
            else if (gr.statusCode() != 404) { plugin.getLogger().warning("Sıralama GitHub okuma hatası: HTTP " + gr.statusCode()); return; }
            JsonObject govde = new JsonObject();
            govde.addProperty("message", "siralama guncelle");
            govde.addProperty("content", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
            govde.addProperty("branch", dal);
            if (sha != null) govde.addProperty("sha", sha);
            HttpRequest put = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json")
                    .PUT(HttpRequest.BodyPublishers.ofString(govde.toString())).build();
            HttpResponse<String> pr = http.send(put, HttpResponse.BodyHandlers.ofString());
            if (pr.statusCode() / 100 == 2) sonYuklenenHash = hash;
            else plugin.getLogger().warning("Sıralama GitHub yükleme hatası: HTTP " + pr.statusCode());
        } catch (Exception e) {
            plugin.getLogger().warning("Sıralama GitHub yüklenemedi: " + e);
        }
    }
}
