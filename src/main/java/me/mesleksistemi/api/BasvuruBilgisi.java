package me.mesleksistemi.api;

import java.util.UUID;

public record BasvuruBilgisi(UUID uuid, String ad, String meslek, String sebep) { }
