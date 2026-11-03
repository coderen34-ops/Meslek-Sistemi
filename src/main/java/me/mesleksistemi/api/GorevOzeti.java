package me.mesleksistemi.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** tamamlananlar: tamamlanmış görev id'leri. aktifSayac: aktif görev id -> ilerleme sayacı. */
public record GorevOzeti(UUID uuid, Set<String> tamamlananlar, Map<String, Integer> aktifSayac) { }
