package me.mesleksistemi.api;

import java.util.Set;
import java.util.UUID;

public record BasarimOzeti(UUID uuid, Set<String> kazanilanlar, int toplamBasarim) { }
