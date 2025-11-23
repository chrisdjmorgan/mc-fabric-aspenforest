package com.aspenforest;

import com.aspenforest.config.AspenForestConfig;
import com.aspenforest.feature.AspenTreeFeature;
import com.aspenforest.system.AspenSpreadSystem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.FeatureConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AspenForestMod implements ModInitializer {
	public static final String MOD_ID = "aspenforest";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	
	public static AspenForestConfig CONFIG;
	public static Feature<AspenTreeFeature.AspenTreeConfig> ASPEN_TREE_FEATURE;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Aspen Forest Mod");
		
		// Load configuration
		CONFIG = AspenForestConfig.load();
		
		// Register aspen tree feature
		ASPEN_TREE_FEATURE = Registry.register(
			Registries.FEATURE,
			Identifier.of(MOD_ID, "aspen_tree"),
			new AspenTreeFeature(AspenTreeFeature.AspenTreeConfig.CODEC)
		);
		
		// Register spreading system
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			AspenSpreadSystem.tick(world);
		});
		
		// Register scan command
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("aspenscan")
				.executes(context -> {
					AspenSpreadSystem.forceScan(context.getSource().getWorld());
					context.getSource().sendFeedback(() -> Text.literal("Scanning for aspen trees..."), true);
					return 1;
				}));
		});
		
		LOGGER.info("Aspen Forest Mod initialized!");
	}
	
	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
