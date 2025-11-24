package com.aspenforest.config;

import com.aspenforest.AspenForestMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AspenForestConfig {
	// Tree Generation
	public int minTreeHeight = 9;
	public int maxTreeHeight = 14;
	public double tallVariantChance = 0.3; // 30% chance for extra tall trees
	
	// Spreading System
	public int spreadCheckInterval = 1200; // Ticks between spread attempts (60 seconds at 20 TPS)
	public double spreadChance = 0.15; // 15% chance per check
	public int minSpreadDistance = 3;
	public int maxSpreadDistance = 8;
	public int maxNearbyAspens = 11; // Max aspens in 32 block radius before stopping spread
	
	// Floor Generation
	public int strippedLogRadius = 1; // Stripped birch around trunk base
	public int rootNetworkRadius = 4; // How far the root network extends
	public double rootedDirtChance = 0.6;
	public double podzolChance = 0.25;
	public double hangingRootsChance = 0.15;
	
	// Decoration Weights (as percentages)
	public double deadBushChance = 0.001; // 0.5%
	public double brownMushroomChance = 0.01; // 3%
	public double redMushroomChance = 0.003; // 1%
	public double pinkPetalsChance = 0.005; // 0.5%
	public double fireflyChance = 0.01; // 0.5%
	public double fallenLeavesChance = 0.25; // 15% base chance
	
	// Biome Restrictions
	public boolean limitToPlainsOnly = true;
	public int plainsCheckRadius = 16; // Must be within this many blocks of plains
	public int waterMargin = 8; // Minimum distance from water bodies
	public int biomeEdgeMargin = 12; // Minimum distance from biome boundaries
	
	// Performance
	public int maxSpreadAttemptsPerTick = 5; // Limit concurrent spread calculations
	
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance()
		.getConfigDir()
		.resolve("aspenforest.json");
	
	public static AspenForestConfig load() {
		if (Files.exists(CONFIG_PATH)) {
			try {
				String json = Files.readString(CONFIG_PATH);
				AspenForestMod.LOGGER.info("Loaded config from {}", CONFIG_PATH);
				return GSON.fromJson(json, AspenForestConfig.class);
			} catch (IOException e) {
				AspenForestMod.LOGGER.error("Failed to load config, using defaults", e);
			}
		}
		
		AspenForestConfig config = new AspenForestConfig();
		config.save();
		return config;
	}
	
	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(CONFIG_PATH, GSON.toJson(this));
			AspenForestMod.LOGGER.info("Saved config to {}", CONFIG_PATH);
		} catch (IOException e) {
			AspenForestMod.LOGGER.error("Failed to save config", e);
		}
	}
}
