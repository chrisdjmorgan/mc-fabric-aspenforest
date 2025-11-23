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
		long lastRescanTick = 0; // Track when we last did a full rescan
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
			
			// Periodic rescan every 5 minutes (6000 ticks) to clear unloaded trees and find new ones
			if (currentTick - tracker.lastRescanTick >= 6000) {
				tracker.lastRescanTick = currentTick;
				AspenForestMod.LOGGER.info("Performing periodic rescan - clearing {} old tree entries", tracker.aspenTrees.size());
				tracker.aspenTrees.clear();
				tracker.pendingSpreadAttempts.clear();
				if (!world.getPlayers().isEmpty()) {
					performInitialScan(world, tracker);
				}
			}
			
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
		
		// Find a random position to spread to with weighted distribution toward middle values
		// Use triangular distribution: pick two random values and average them
		// This clusters results toward the center (4-5 blocks for range 3-8)
		int range = AspenForestMod.CONFIG.maxSpreadDistance - AspenForestMod.CONFIG.minSpreadDistance + 1;
		int distance1 = AspenForestMod.CONFIG.minSpreadDistance + random.nextInt(range);
		int distance2 = AspenForestMod.CONFIG.minSpreadDistance + random.nextInt(range);
		int distance = (distance1 + distance2) / 2; // Average creates bell curve
		
		double angle = random.nextDouble() * Math.PI * 2;
		int offsetX = (int) (Math.cos(angle) * distance);
		int offsetZ = (int) (Math.sin(angle) * distance);
		
		BlockPos targetPos = treePos.add(offsetX, 0, offsetZ);
		
		// Find the surface at the target position
		targetPos = world.getTopPosition(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, targetPos);
		
		// Validate the position is within world bounds and not void
		if (targetPos.getY() < world.getBottomY() || targetPos.getY() > world.getTopY()) {
			return; // Position outside world bounds
		}
		
		// Check distance to source tree with actual positions (accounting for elevation)
		double actualDistanceToSource = Math.sqrt(
			Math.pow(targetPos.getX() - treePos.getX(), 2) + 
			Math.pow(targetPos.getZ() - treePos.getZ(), 2)
		);
		if (actualDistanceToSource < AspenForestMod.CONFIG.minSpreadDistance) {
			return; // Too close to source tree after finding actual surface position
		}
		
		// Early check: make sure no existing tree is very close (before expensive checks)
		int minDistanceSq = AspenForestMod.CONFIG.minSpreadDistance * AspenForestMod.CONFIG.minSpreadDistance;
		for (BlockPos aspenPos : tracker.aspenTrees) {
			// Use 2D distance (ignore Y) to check horizontal spacing
			double distanceSq = Math.pow(aspenPos.getX() - targetPos.getX(), 2) + 
			                    Math.pow(aspenPos.getZ() - targetPos.getZ(), 2);
			if (distanceSq < minDistanceSq) {
				return; // Too close to an existing tree
			}
		}
		
		// Check elevation change - don't spread up steep cliffs (max 4 block difference)
		int elevationDiff = Math.abs(targetPos.getY() - treePos.getY());
		if (elevationDiff > 4) {
			AspenForestMod.LOGGER.info("Spread blocked at {} - elevation diff: {}", targetPos, elevationDiff);
			return; // Too steep
		}
		
		// Check for water nearby - avoid spreading right next to water
		if (isNearWater(world, targetPos)) {
			AspenForestMod.LOGGER.info("Spread blocked at {} - near water", targetPos);
			return;
		}
		
		// Check if target location has valid dirt-type ground for spreading
		BlockPos groundPos = targetPos.down();
		BlockState groundState = world.getBlockState(groundPos);
		if (!isValidSpreadGround(groundState)) {
			AspenForestMod.LOGGER.info("Spread blocked at {} - invalid ground: {}", targetPos, groundState.getBlock().getName().getString());
			return; // Can't spread to non-dirt blocks like gravel, paths, farmland, etc.
		}
		
		// Check for structures/buildings nearby - avoid spreading near non-natural blocks
		if (isNearStructure(world, targetPos)) {
			return; // Avoid villages, player structures, etc.
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
	
	private static boolean isValidSpreadGround(BlockState state) {
		// Only allow spreading to natural dirt variants
		// Include: DIRT, GRASS_BLOCK, PODZOL, COARSE_DIRT, ROOTED_DIRT
		// Exclude: GRAVEL, DIRT_PATH, FARMLAND, and any other non-dirt blocks
		return state.isOf(Blocks.DIRT) || 
		       state.isOf(Blocks.GRASS_BLOCK) ||
		       state.isOf(Blocks.PODZOL) ||
		       state.isOf(Blocks.COARSE_DIRT) ||
		       state.isOf(Blocks.ROOTED_DIRT);
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
	
	private static boolean isNearStructure(ServerWorld world, BlockPos pos) {
		// Check in a 5-block radius for non-natural blocks that indicate structures
		int radius = 5;
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				// Check from ground level up a few blocks
				for (int y = -1; y <= 3; y++) {
					BlockPos checkPos = pos.add(x, y, z);
					BlockState state = world.getBlockState(checkPos);
					
					// Skip air, natural blocks, and our own aspen trees
					if (state.isAir() || state.isOf(Blocks.BIRCH_LOG) || 
						state.isOf(Blocks.BIRCH_LEAVES) || state.isOf(Blocks.STRIPPED_BIRCH_LOG)) {
						continue;
					}
					
					// Check if this is a crafted/structure block
					if (isStructureBlock(state)) {
						AspenForestMod.LOGGER.info("Spread blocked at {} - found structure block: {} at {}", 
							pos, state.getBlock().getName().getString(), checkPos);
						return true;
					}
				}
			}
		}
		return false;
	}
	
	private static boolean isStructureBlock(BlockState state) {
		// Natural blocks that are allowed
		if (state.isIn(net.minecraft.registry.tag.BlockTags.DIRT) ||
			state.isIn(net.minecraft.registry.tag.BlockTags.LOGS) ||
			state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) ||
			state.isIn(net.minecraft.registry.tag.BlockTags.FLOWERS) ||
			state.isIn(net.minecraft.registry.tag.BlockTags.SMALL_FLOWERS) ||
			state.isOf(Blocks.STONE) ||
			state.isOf(Blocks.DEEPSLATE) ||
			state.isOf(Blocks.GRAVEL) ||
			state.isOf(Blocks.SAND) ||
			state.isOf(Blocks.SANDSTONE) ||
			state.isOf(Blocks.GRASS_BLOCK) ||
			state.isOf(Blocks.TALL_GRASS) ||
			state.isOf(Blocks.SHORT_GRASS) ||
			state.isOf(Blocks.FERN) ||
			state.isOf(Blocks.LARGE_FERN) ||
			state.isOf(Blocks.DEAD_BUSH) ||
			state.isOf(Blocks.BROWN_MUSHROOM) ||
			state.isOf(Blocks.RED_MUSHROOM) ||
			state.isOf(Blocks.HANGING_ROOTS) ||
			state.isOf(Blocks.ROOTED_DIRT) ||
			state.isOf(Blocks.DANDELION) ||
			state.isOf(Blocks.POPPY) ||
			state.isOf(Blocks.AZURE_BLUET) ||
			state.isOf(Blocks.CORNFLOWER) ||
			state.isOf(Blocks.LILY_OF_THE_VALLEY) ||
			state.isOf(Blocks.OXEYE_DAISY) ||
			state.isOf(Blocks.SUNFLOWER) ||
			state.isOf(Blocks.LILAC) ||
			state.isOf(Blocks.ROSE_BUSH) ||
			state.isOf(Blocks.PEONY) ||
			state.isOf(Blocks.ANDESITE) ||
			state.isOf(Blocks.DIORITE) ||
			state.isOf(Blocks.GRANITE) ||
			state.isOf(Blocks.CALCITE) ||
			state.isOf(Blocks.TUFF) ||
			state.isOf(Blocks.MOSS_BLOCK) ||
			state.isOf(Blocks.MOSS_CARPET)) {
			return false;
		}
		
		// Everything else is considered a structure block
		// This includes: planks, cobblestone, bricks, glass, doors, chests, etc.
		return true;
	}
	
	public static void registerAspenTree(ServerWorld world, BlockPos pos) {
		SpreadTracker tracker = worldTrackers.computeIfAbsent(world, w -> new SpreadTracker());
		tracker.aspenTrees.add(pos);
		AspenForestMod.LOGGER.info("Registered aspen tree at {} - total trees: {}", pos, tracker.aspenTrees.size());
	}
	
	public static void forceScan(ServerWorld world) {
		SpreadTracker tracker = worldTrackers.computeIfAbsent(world, w -> new SpreadTracker());
		performInitialScan(world, tracker);
	}
}
