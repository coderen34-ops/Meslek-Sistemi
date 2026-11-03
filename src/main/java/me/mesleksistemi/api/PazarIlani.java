package me.mesleksistemi.api;

import java.util.UUID;

public record PazarIlani(String standId, UUID satici, String listingId, String esya, String esyaAdi,
                         double birimFiyat, int stok) { }
