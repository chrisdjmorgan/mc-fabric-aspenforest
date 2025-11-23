package com.aspenforest.system;

import com.aspenforest.AspenForestMod;
import com.aspenforest.feature.AspenTreeFeature;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.biome.Biome;

import java.util.*;

public class AspenSpreadSystem {
	private static final Map<ServerWorld, SpreadTracker> worldTrackers = new HashMap<>();
	
	private static class SpreadTracker {
		long lastCheckTick = 0;
		final Set<BlockPos> aspenTrees = new HashSet<>();
		final Queue<BlockPos> pendingSpreadAttempts = new LinkedList<>();
		int attemptsThisTick = 0;
	}
	
	public static void tick(ServerWorld world) {
		// Only process overworld
		if (!world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) {
			return;
		}
		
		SpreadTracker tracker = worldTrackers.computeIfAbsent(world, w -> new SpreadTracker());
		long currentTick = world.getTime();
		
		// If this is the first time we're ticking and we have no trees, do an initial scan
		// But only if there's a player in the world
		if (tracker.lastCheckTick == 0 && tracker.aspenTrees.isEmpty() && !world.getPlayers().isEmpty()) {
			performInitialScan(world, tracker);
			tracker.lastCheckTick = currentTick; // Set this so we don't scan every tick
		}
		
		// Reset attempt counter each tick
		tracker.attemptsThisTick = 0;
		
		// Process pending spread attempts (rate limited)
		while (!tracker.pendingSpreadAttempts.isEmpty() && 
			   tracker.attemptsThisTick < AspenForestMod.CONFIG.maxSpreadAttemptsPerTick) {
			BlockPos treePos = tracker.pendingSpreadAttempts.poll();
			attemptSpread(world, treePos, tracker);
			tracker.attemptsThisTick++;
		}
		
		// Check if it's time for a new spread check cycle
		if (currentTick - tracker.lastCheckTick >= AspenForestMod.CONFIG.spreadCheckInterval) {
			tracker.lastCheckTick = currentTick;
			
			// If we still have no trees, try scanning again
			if (tracker.aspenTrees.isEmpty() && !world.getPlayers().isEmpty()) {
				performInitialScan(world, tracker);
			}
			
			// Queue spread attempts for all found aspens
			for (BlockPos aspenPos : tracker.aspenTrees) {
				if (world.getRandom().nextDouble() < AspenForestMod.CONFIG.spreadChance) {
					tracker.pendingSpreadAttempts.add(aspenPos);
				}
			}
			
			AspenForestMod.LOGGER.info("Spread cycle: {} trees tracked, {} attempts queued", 
				tracker.aspenTrees.size(), tracker.pendingSpreadAttempts.size());
		}
	}
	
	private static void scanForAspenTrees(ServerWorld world, SpreadTracker tracker) {
		// We keep registered trees persistent - they're added when placed via registerAspenTree()
		// This avoids complex chunk scanning APIs while still tracking all aspen trees
		AspenForestMod.LOGGER.debug("Tracking {} registered aspen trees", tracker.aspenTrees.size());
	}
	
	private static void performInitialScan(ServerWorld world, SpreadTracker tracker) {
		// Scan a reasonable area for existing aspen trees
		// Get all players to find where to scan
		world.getPlayers().forEach(player -> {
			BlockPos playerPos = player.getBlockPos();
			int scanRadius = 128; // 8 chunks
			int foundBirchLogs = 0;
			int foundStrippedLogs = 0;
			
			AspenForestMod.LOGGER.info("Performing initial scan around player at {}", playerPos);
			
			// Scan in a grid pattern
			for (int x = -scanRadius; x <= scanRadius; x += 4) {
				for (int z = -scanRadius; z <= scanRadius; z += 4) {
					BlockPos checkPos = playerPos.add(x, 0, z);
					checkPos = world.getTopPosition(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, checkPos);
					
					// Check a small area around this position for birch logs, checking vertically too
					for (int dx = -2; dx <= 2; dx++) {
						for (int dz = -2; dz <= 2; dz++) {
							for (int dy = -5; dy <= 20; dy++) { // Check from 5 below surface to 20 above
								BlockPos scanPos = checkPos.add(dx, dy, dz);
								BlockState state = world.getBlockState(scanPos);
								
								if (state.isOf(Blocks.BIRCH_LOG)) {
									foundBirchLogs++;
									if (isAspenTree(world, scanPos)) {
										BlockPos base = findTreeBase(world, scanPos);
										tracker.aspenTrees.add(base);
									}
								} else if (state.isOf(Blocks.STRIPPED_BIRCH_LOG)) {
									foundStrippedLogs++;
								}
							}
						}
					}
				}
			}
			
			AspenForestMod.LOGGER.info("Scan found {} birch logs, {} stripped logs, identified {} aspen trees", 
				foundBirchLogs, foundStrippedLogs, tracker.aspenTrees.size());
		});
	}
	
	private static boolean isAspenTree(ServerWorld world, BlockPos logPos) {
		// Find the base of the tree
		BlockPos base = findTreeBase(world, logPos);
		
		// Check for stripped birch logs nearby (our signature)
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				if (x == 0 && z == 0) continue;
				BlockPos checkPos = base.add(x, 0, z);
				if (world.getBlockState(checkPos).isOf(Blocks.STRIPPED_BIRCH_LOG)) {
					return true;
				}
			}
		}
		return false;
	}
	
	private static BlockPos findTreeBase(ServerWorld world, BlockPos logPos) {
		BlockPos current = logPos;
		while (world.getBlockState(current.down()).isOf(Blocks.BIRCH_LOG)) {
			current = current.down();
		}
		return current;
	}
	
	private static void attemptSpread(ServerWorld world, BlockPos treePos, SpreadTracker tracker) {
		Random random = world.getRandom();
		
		// Count how many trees are nearby the SOURCE tree
		int sourceNearbyCount = countNearbyAspens(world, treePos, tracker);
		
		// Find a random position to spread to
		int distance = AspenForestMod.CONFIG.minSpreadDistance + 
			random.nextInt(AspenForestMod.CONFIG.maxSpreadDistance - AspenForestMod.CONFIG.minSpreadDistance + 1);
		
		double angle = random.nextDouble() * Math.PI * 2;
		int offsetX = (int) (Math.cos(angle) * distance);
		int offsetZ = (int) (Math.sin(angle) * distance);
		
		BlockPos targetPos = treePos.add(offsetX, 0, offsetZ);
		
		// Find the surface at the target position
		targetPos = world.getTopPosition(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, targetPos);
		
		// Check elevation change - don't spread up steep cliffs (max 4 block difference)
		int elevationDiff = Math.abs(targetPos.getY() - treePos.getY());
		if (elevationDiff > 4) {
			return; // Too steep
		}
		
		// Check for water nearby - avoid spreading right next to water
		if (isNearWater(world, targetPos)) {
			return;
		}
		
		// Check if there's already a tree very close to this spot (within 3 blocks)
		for (BlockPos aspenPos : tracker.aspenTrees) {
			double distanceSq = aspenPos.getSquaredDistance(targetPos);
			if (distanceSq < 9) { // 3 blocks squared
				return; // Too close to an existing tree
			}
		}
		
		// Check density at the TARGET location
		int targetNearbyCount = countNearbyAspens(world, targetPos, tracker);
		
		// Only allow spreading if:
		// 1. Target has fewer neighbors than source (spreading toward less dense area), OR
		// 2. Both source and target are below the density limit
		boolean spreadingOutward = targetNearbyCount < sourceNearbyCount;
		boolean belowDensityLimit = targetNearbyCount < AspenForestMod.CONFIG.maxNearbyAspens;
		
		if (!spreadingOutward && !belowDensityLimit) {
			AspenForestMod.LOGGER.debug("Spread blocked: source={} neighbors, target={} neighbors, limit={}", 
				sourceNearbyCount, targetNearbyCount, AspenForestMod.CONFIG.maxNearbyAspens);
			return;
		}
		
		// Check biome restriction
		if (AspenForestMod.CONFIG.limitToPlainsOnly) {
			if (!isInOrNearPlains(world, targetPos)) {
				return;
			}
		}
		
		// Try to generate a new tree
		AspenTreeFeature.AspenTreeConfig config = AspenTreeFeature.AspenTreeConfig.withFloor();
		boolean success = AspenForestMod.ASPEN_TREE_FEATURE.generate(
			new net.minecraft.world.gen.feature.util.FeatureContext<>(
				java.util.Optional.empty(),
				world,
				world.getChunkManager().getChunkGenerator(),
				random,
				targetPos,
				config
			)
		);
		
		if (success) {
			tracker.aspenTrees.add(targetPos);
			AspenForestMod.LOGGER.debug("Aspen tree spread from {} to {}", treePos, targetPos);
		}
	}
	
	private static int countNearbyAspens(ServerWorld world, BlockPos center, SpreadTracker tracker) {
		int count = 0;
		int radius = 16; // Reduced from 32 to allow spreading from clusters
		
		for (BlockPos aspenPos : tracker.aspenTrees) {
			double distanceSq = aspenPos.getSquaredDistance(center);
			if (distanceSq <= radius * radius) {
				count++;
			}
		}
		
		return count;
	}
	
	private static boolean isInOrNearPlains(ServerWorld world, BlockPos pos) {
		int radius = AspenForestMod.CONFIG.plainsCheckRadius;
		
		// Check center and a few points around it
		for (int x = -radius; x <= radius; x += radius / 2) {
			for (int z = -radius; z <= radius; z += radius / 2) {
				BlockPos checkPos = pos.add(x, 0, z);
				// Check if biome is plains-like by checking registry key
				String biomeName = world.getBiome(checkPos).getKey().map(key -> key.getValue().getPath()).orElse("");
				if (biomeName.contains("plains")) {
					return true;
				}
			}
		}
		
		return false;
	}
	
	private static boolean isNearWater(ServerWorld world, BlockPos pos) {
		// Check in a 3-block radius for water
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				BlockPos checkPos = pos.add(x, 0, z);
				// Check this position and a few blocks down for water
				for (int y = 0; y >= -3; y--) {
					BlockState state = world.getBlockState(checkPos.add(0, y, 0));
					if (state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
						return true;
					}
				}
			}
		}
		return false;
	}
	
	public static void registerAspenTree(ServerWorld world, BlockPos pos) {
		SpreadTracker tracker = worldTrackers.computeIfAbsent(world, w -> new SpreadTracker());
		tracker.aspenTrees.add(pos);
	}
	
	public static void forceScan(ServerWorld world) {
		SpreadTracker tracker = worldTrackers.computeIfAbsent(world, w -> new SpreadTracker());
		performInitialScan(world, tracker);
	}
}
