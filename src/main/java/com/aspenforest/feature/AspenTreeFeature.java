package com.aspenforest.feature;

import com.aspenforest.AspenForestMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

import java.util.HashSet;
import java.util.Set;

public class AspenTreeFeature extends Feature<AspenTreeFeature.AspenTreeConfig> {
	
	public AspenTreeFeature(Codec<AspenTreeConfig> configCodec) {
		super(configCodec);
	}

	@Override
	public boolean generate(FeatureContext<AspenTreeConfig> context) {
		StructureWorldAccess world = context.getWorld();
		BlockPos pos = context.getOrigin();
		Random random = context.getRandom();
		AspenTreeConfig config = context.getConfig();
		
		// Check if we can place the tree
		if (!canPlace(world, pos)) {
			return false;
		}
		
		// Determine tree height
		int height = AspenForestMod.CONFIG.minTreeHeight + 
			random.nextInt(AspenForestMod.CONFIG.maxTreeHeight - AspenForestMod.CONFIG.minTreeHeight + 1);
		
		if (random.nextDouble() < AspenForestMod.CONFIG.tallVariantChance) {
			height += random.nextInt(3) + 2; // Extra 2-4 blocks for tall variant
		}
		
		// Generate the tree
		generateTrunk(world, pos, height);
		generateCanopy(world, pos.up(height - 3), height, random);
		generateFloor(world, pos, random, config.withFloor);
		
		// Register this tree for spreading
		if (world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
			com.aspenforest.system.AspenSpreadSystem.registerAspenTree(serverWorld, pos);
		}
		
		return true;
	}
	
	private boolean canPlace(StructureWorldAccess world, BlockPos pos) {
		BlockPos below = pos.down();
		BlockState groundState = world.getBlockState(below);
		
		// Check if on valid ground
		return groundState.isIn(BlockTags.DIRT) || 
			   groundState.isOf(Blocks.GRASS_BLOCK) ||
			   groundState.isOf(Blocks.PODZOL) ||
			   groundState.isOf(Blocks.ROOTED_DIRT);
	}
	
	private void generateTrunk(StructureWorldAccess world, BlockPos pos, int height) {
		BlockState log = Blocks.BIRCH_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Y);
		
		for (int i = 0; i < height; i++) {
			BlockPos trunkPos = pos.up(i);
			if (world.getBlockState(trunkPos).isReplaceable() || 
				world.getBlockState(trunkPos).isIn(BlockTags.LEAVES)) {
				world.setBlockState(trunkPos, log, 3);
			}
		}
	}
	
	private void generateCanopy(StructureWorldAccess world, BlockPos start, int treeHeight, Random random) {
		BlockState leaves = Blocks.BIRCH_LEAVES.getDefaultState();
		
		// Canopy starts 3 blocks from top and extends upward
		int canopyHeight = 5;
		
		for (int y = 0; y < canopyHeight; y++) {
			BlockPos layerCenter = start.up(y);
			int radius;
			
			// Shape the canopy - wider in middle, narrower at top and bottom
			if (y == 0) {
				radius = 2;
			} else if (y == canopyHeight - 1) {
				radius = 1;
			} else {
				radius = 3;
			}
			
			for (int x = -radius; x <= radius; x++) {
				for (int z = -radius; z <= radius; z++) {
					if (x == 0 && z == 0) continue; // Skip center (trunk)
					
					double distance = Math.sqrt(x * x + z * z);
					if (distance <= radius) {
						BlockPos leafPos = layerCenter.add(x, 0, z);
						
						// Add some randomness to leaf placement for natural look
						if (distance < radius || random.nextFloat() < 0.7) {
							if (world.getBlockState(leafPos).isReplaceable()) {
								world.setBlockState(leafPos, leaves, 3);
							}
						}
					}
				}
			}
		}
		
		// Add top leaf
		BlockPos top = start.up(canopyHeight);
		if (world.getBlockState(top).isReplaceable()) {
			world.setBlockState(top, leaves, 3);
		}
	}
	
	private void generateFloor(StructureWorldAccess world, BlockPos treeBase, Random random, boolean generateFloor) {
		if (!generateFloor) {
			return;
		}
		
		Set<BlockPos> processedPositions = new HashSet<>();
		Set<BlockPos> groundPositions = new HashSet<>();
		
		// First, place stripped logs around the trunk base
		int strippedRadius = AspenForestMod.CONFIG.strippedLogRadius;
		for (int x = -strippedRadius; x <= strippedRadius; x++) {
			for (int z = -strippedRadius; z <= strippedRadius; z++) {
				if (x == 0 && z == 0) continue; // Skip center (trunk itself)
				
				double distance = Math.sqrt(x * x + z * z);
				if (distance <= strippedRadius) {
					BlockPos checkPos = treeBase.add(x, 0, z);
					
					// Find the actual ground level (check a few blocks up/down)
					BlockPos groundPos = findGroundLevel(world, checkPos);
					if (groundPos == null) continue;
					
					// Replace dirt blocks with stripped birch log
					BlockState groundState = world.getBlockState(groundPos);
					if (groundState.isIn(BlockTags.DIRT) || groundState.isOf(Blocks.GRASS_BLOCK)) {
						Direction.Axis axis = Math.abs(x) > Math.abs(z) ? Direction.Axis.X : Direction.Axis.Z;
						world.setBlockState(groundPos, 
							Blocks.STRIPPED_BIRCH_LOG.getDefaultState().with(PillarBlock.AXIS, axis), 3);
						processedPositions.add(groundPos);
						groundPositions.add(groundPos);
					}
				}
			}
		}
		
		// Then spread the root network outward
		int networkRadius = AspenForestMod.CONFIG.rootNetworkRadius;
		for (int x = -networkRadius; x <= networkRadius; x++) {
			for (int z = -networkRadius; z <= networkRadius; z++) {
				BlockPos checkPos = treeBase.add(x, 0, z);
				
				// Find the actual ground level
				BlockPos groundPos = findGroundLevel(world, checkPos);
				if (groundPos == null || processedPositions.contains(groundPos)) continue;
				
				double distance = Math.sqrt(x * x + z * z);
				if (distance <= networkRadius && distance > strippedRadius) {
					boolean placed = placeRootBlock(world, groundPos, random, distance, networkRadius);
					if (placed) {
						groundPositions.add(groundPos);
					}
				}
			}
		}
		
		// Add hanging roots under some exposed stripped logs
		for (BlockPos pos : processedPositions) {
			if (random.nextDouble() < AspenForestMod.CONFIG.hangingRootsChance) {
				BlockPos below = pos.down();
				if (world.getBlockState(below).isAir()) {
					world.setBlockState(below, Blocks.HANGING_ROOTS.getDefaultState(), 3);
				}
			}
		}
		
		// Add decorative elements on top of ground blocks
		addDecorations(world, groundPositions, random);
	}
	
	private boolean placeRootBlock(StructureWorldAccess world, BlockPos pos, Random random, 
								double distance, int maxRadius) {
		BlockState currentState = world.getBlockState(pos);
		
		// Only replace dirt-like blocks
		if (!currentState.isIn(BlockTags.DIRT) && !currentState.isOf(Blocks.GRASS_BLOCK)) {
			return false;
		}
		
		// Probability decreases with distance
		double placementChance = 1.0 - (distance / maxRadius) * 0.5;
		if (random.nextDouble() > placementChance) {
			return false;
		}
		
		// Choose block type based on config chances and additional variety
		double roll = random.nextDouble();
		BlockState blockToPlace;
		
		if (roll < AspenForestMod.CONFIG.rootedDirtChance) {
			blockToPlace = Blocks.ROOTED_DIRT.getDefaultState();
		} else if (roll < AspenForestMod.CONFIG.rootedDirtChance + AspenForestMod.CONFIG.podzolChance) {
			blockToPlace = Blocks.PODZOL.getDefaultState();
		} else if (roll < AspenForestMod.CONFIG.rootedDirtChance + AspenForestMod.CONFIG.podzolChance + 0.1) {
			// 10% chance for coarse dirt
			blockToPlace = Blocks.COARSE_DIRT.getDefaultState();
		} else if (roll < AspenForestMod.CONFIG.rootedDirtChance + AspenForestMod.CONFIG.podzolChance + 0.15) {
			// 5% chance for dirt path
			blockToPlace = Blocks.DIRT_PATH.getDefaultState();
		} else {
			// Keep as grass or existing block
			return false;
		}
		
		world.setBlockState(pos, blockToPlace, 3);
		return true;
	}
	
	private BlockPos findGroundLevel(StructureWorldAccess world, BlockPos startPos) {
		// Check up to 3 blocks up and down to find the surface
		BlockPos foundPos = null;
		
		for (int dy = 3; dy >= -3; dy--) {
			BlockPos checkPos = startPos.add(0, dy, 0);
			BlockState state = world.getBlockState(checkPos);
			BlockPos above = checkPos.up();
			BlockState aboveState = world.getBlockState(above);
			
			// Look for dirt/grass that has air or replaceable block above (surface layer)
			if ((state.isIn(BlockTags.DIRT) || state.isOf(Blocks.GRASS_BLOCK)) &&
				(aboveState.isAir() || aboveState.isIn(BlockTags.REPLACEABLE))) {
				
				// Make sure there's something solid below
				BlockPos below = checkPos.down();
				BlockState belowState = world.getBlockState(below);
				if (belowState.isSolidBlock(world, below) || belowState.isIn(BlockTags.DIRT)) {
					foundPos = checkPos;
					break; // Found the top layer, stop searching
				}
			}
		}
		
		return foundPos;
	}
	
	private void oldFillAirHoles_REMOVED(StructureWorldAccess world, BlockPos treeBase, int radius, Set<BlockPos> groundPositions) {
		// Look for single-block air gaps surrounded by solid blocks
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				BlockPos checkPos = treeBase.add(x, 0, z);
				
				// Skip if already processed or not air
				if (groundPositions.contains(checkPos) || !world.getBlockState(checkPos).isAir()) {
					continue;
				}
				
				// Check if solid blocks on all 4 sides
				boolean northSolid = world.getBlockState(checkPos.north()).isSolidBlock(world, checkPos.north());
				boolean southSolid = world.getBlockState(checkPos.south()).isSolidBlock(world, checkPos.south());
				boolean eastSolid = world.getBlockState(checkPos.east()).isSolidBlock(world, checkPos.east());
				boolean westSolid = world.getBlockState(checkPos.west()).isSolidBlock(world, checkPos.west());
				
				// Also need solid ground below
				BlockPos below = checkPos.down();
				boolean groundBelow = world.getBlockState(below).isSolidBlock(world, below);
				
				if (northSolid && southSolid && eastSolid && westSolid && groundBelow) {
					// Fill with podzol to match forest floor
					world.setBlockState(checkPos, Blocks.PODZOL.getDefaultState(), 3);
					groundPositions.add(checkPos);
				}
			}
		}
	}
	
	private void addDecorations(StructureWorldAccess world, Set<BlockPos> groundPositions, Random random) {
		for (BlockPos groundPos : groundPositions) {
			BlockPos above = groundPos.up();
			
			// Skip if position already occupied
			if (!world.getBlockState(above).isAir()) {
				continue;
			}
			
			// 15% chance for dead bush (leaf litter)
			if (random.nextDouble() < 0.15) {
				world.setBlockState(above, Blocks.DEAD_BUSH.getDefaultState(), 3);
			}
			// 5% chance for brown mushroom
			else if (random.nextDouble() < 0.05) {
				world.setBlockState(above, Blocks.BROWN_MUSHROOM.getDefaultState(), 3);
			}
		}
	}
	
	public static class AspenTreeConfig implements net.minecraft.world.gen.feature.FeatureConfig {
		public static final Codec<AspenTreeConfig> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
				Codec.BOOL.fieldOf("with_floor").forGetter(config -> config.withFloor)
			).apply(instance, AspenTreeConfig::new)
		);
		
		public final boolean withFloor;
		
		public AspenTreeConfig(boolean withFloor) {
			this.withFloor = withFloor;
		}
		
		public static AspenTreeConfig withFloor() {
			return new AspenTreeConfig(true);
		}
		
		public static AspenTreeConfig withoutFloor() {
			return new AspenTreeConfig(false);
		}
	}
}
