package com.aspenforest.feature;

import com.aspenforest.AspenForestMod;
import net.minecraft.registry.Registerable;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.*;
import net.minecraft.world.gen.placementmodifier.*;

import java.util.List;

public class AspenTreeConfiguredFeatures {
	
	public static final RegistryKey<ConfiguredFeature<?, ?>> ASPEN_TREE_KEY = 
		registerKey("aspen_tree");
	
	public static final RegistryKey<PlacedFeature> ASPEN_TREE_PLACED_KEY = 
		registerPlacedKey("aspen_tree");
	
	private static RegistryKey<ConfiguredFeature<?, ?>> registerKey(String name) {
		return RegistryKey.of(RegistryKeys.CONFIGURED_FEATURE, 
			Identifier.of(AspenForestMod.MOD_ID, name));
	}
	
	private static RegistryKey<PlacedFeature> registerPlacedKey(String name) {
		return RegistryKey.of(RegistryKeys.PLACED_FEATURE, 
			Identifier.of(AspenForestMod.MOD_ID, name));
	}
	
	public static void bootstrap(Registerable<ConfiguredFeature<?, ?>> context) {
		register(context, ASPEN_TREE_KEY, AspenForestMod.ASPEN_TREE_FEATURE, 
			AspenTreeFeature.AspenTreeConfig.withFloor());
	}
	
	public static void bootstrapPlaced(Registerable<PlacedFeature> context) {
		var configuredFeatureRegistryEntryLookup = context.getRegistryLookup(RegistryKeys.CONFIGURED_FEATURE);
		
		register(context, ASPEN_TREE_PLACED_KEY,
			configuredFeatureRegistryEntryLookup.getOrThrow(ASPEN_TREE_KEY),
			List.of());
	}
	
	private static <FC extends FeatureConfig, F extends Feature<FC>> void register(
		Registerable<ConfiguredFeature<?, ?>> context,
		RegistryKey<ConfiguredFeature<?, ?>> key,
		F feature,
		FC config) {
		context.register(key, new ConfiguredFeature<>(feature, config));
	}
	
	private static void register(
		Registerable<PlacedFeature> context,
		RegistryKey<PlacedFeature> key,
		RegistryEntry<ConfiguredFeature<?, ?>> configuration,
		List<PlacementModifier> modifiers) {
		context.register(key, new PlacedFeature(configuration, List.copyOf(modifiers)));
	}
}
