package me.mesleksistemi.api;

import java.util.UUID;

/** Taraflar oyuncu ADI ile saklanır (dava verisi böyle tutuluyor). */
public record DavaOzeti(UUID id, String durum, String musteki, String sanik, String hakim,
                        String mustekiAvukati, String sanikAvukati, double talepEdilenMiktar, String sebep) { }
