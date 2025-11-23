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
		if (!groundState.isIn(BlockTags.DIRT) && 
			!groundState.isOf(Blocks.GRASS_BLOCK) &&
			!groundState.isOf(Blocks.PODZOL) &&
			!groundState.isOf(Blocks.ROOTED_DIRT)) {
			return false;
		}
		
		// Check for structures nearby (5 block radius)
		if (isNearStructure(world, pos)) {
			return false;
		}
		
		return true;
	}
	
	private void generateTrunk(StructureWorldAccess world, BlockPos pos, int height) {
		BlockState log = Blocks.BIRCH_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Y);
		
		// Start one block down to replace the ground block
		for (int i = -1; i < height; i++) {
			BlockPos trunkPos = pos.up(i);
			BlockState currentState = world.getBlockState(trunkPos);
			
			// Replace ground block, air, and leaves
			if (i == -1) {
				// Replace the ground block itself
				if (currentState.isIn(BlockTags.DIRT) || currentState.isOf(Blocks.GRASS_BLOCK)) {
					world.setBlockState(trunkPos, log, 3);
				}
			} else {
				// Replace air and leaves above ground
				if (currentState.isReplaceable() || currentState.isIn(BlockTags.LEAVES)) {
					world.setBlockState(trunkPos, log, 3);
				}
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
		
		// Add fallen leaves in a larger radius
		addFallenLeaves(world, treeBase, random);
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
			// 5% chance for extra podzol (was dirt path, but that blocks spreading)
			blockToPlace = Blocks.PODZOL.getDefaultState();
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
		// Add decorations on ground blocks
		for (BlockPos groundPos : groundPositions) {
			BlockPos above = groundPos.up();
			
			// Skip if position already occupied
			if (!world.getBlockState(above).isAir()) {
				continue;
			}
			
			double roll = random.nextDouble();
			
			// 10% chance for dead bush
			if (roll < 0.10) {
				world.setBlockState(above, Blocks.DEAD_BUSH.getDefaultState(), 3);
			}
			// 3% chance for brown mushroom
			else if (roll < 0.13) {
				world.setBlockState(above, Blocks.BROWN_MUSHROOM.getDefaultState(), 3);
			}
			// 1% chance for red mushroom
			else if (roll < 0.14) {
				world.setBlockState(above, Blocks.RED_MUSHROOM.getDefaultState(), 3);
			}
			// 0.5% chance for flowering azalea (firefly bush substitute)
			else if (roll < 0.145) {
				world.setBlockState(above, Blocks.FLOWERING_AZALEA.getDefaultState(), 3);
			}
		}
	}
	
	private void addFallenLeaves(StructureWorldAccess world, BlockPos treeBase, Random random) {
		// Add fallen leaves in a radius slightly larger than terrain generation
		int leafRadius = AspenForestMod.CONFIG.rootNetworkRadius + 2; // 6 blocks for default config
		
		for (int x = -leafRadius; x <= leafRadius; x++) {
			for (int z = -leafRadius; z <= leafRadius; z++) {
				double distance = Math.sqrt(x * x + z * z);
				if (distance <= leafRadius) {
					BlockPos checkPos = treeBase.add(x, 0, z);
					BlockPos groundPos = findGroundLevel(world, checkPos);
					
					if (groundPos == null) continue;
					
					BlockPos above = groundPos.up();
					BlockState aboveState = world.getBlockState(above);
					
					// Only place on air, with decreasing probability by distance
					if (aboveState.isAir()) {
						double placementChance = 0.15 * (1.0 - (distance / leafRadius) * 0.5);
						if (random.nextDouble() < placementChance) {
							// Use brown carpet as fallen leaves
							world.setBlockState(above, Blocks.BROWN_CARPET.getDefaultState(), 3);
						}
					}
				}
			}
		}
	}
	
	private boolean isNearStructure(StructureWorldAccess world, BlockPos pos) {
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
						return true;
					}
				}
			}
		}
		return false;
	}
	
	private boolean isStructureBlock(BlockState state) {
		// Natural blocks that are allowed
		if (state.isIn(BlockTags.DIRT) ||
			state.isIn(BlockTags.LOGS) ||
			state.isIn(BlockTags.LEAVES) ||
			state.isIn(BlockTags.FLOWERS) ||
			state.isIn(BlockTags.SMALL_FLOWERS) ||
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
