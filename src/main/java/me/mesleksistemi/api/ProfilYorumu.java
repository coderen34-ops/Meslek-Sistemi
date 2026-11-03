package me.mesleksistemi.api;

import java.util.UUID;

public record ProfilYorumu(UUID yazan, String yazanAd, String metin, long zaman) { }
