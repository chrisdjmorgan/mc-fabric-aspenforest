# Aspen Forest Mod

A Minecraft Fabric mod that adds naturally spreading aspen groves with interconnected root systems.

## Features

- **Aspen Trees**: Taller birch trees (9-17 blocks) that look like aspens
- **Root Networks**: Stripped birch logs around trunk bases connect trees with rooted dirt, podzol, and hanging roots
- **Natural Spreading**: Trees spread from their roots over time, creating organic groves
- **Biome-Aware**: Growth is limited to plains biomes to prevent endless expansion
- **Fully Configurable**: Tune all parameters via `config/aspenforest.json`

## Usage

### Planting Your First Grove

1. Find a plains biome
2. Run the command: `/place feature aspenforest:aspen_tree`
3. Wait! The grove will naturally expand over time

### Configuration

Edit `config/aspenforest.json` to customize:

- **Tree Height**: `minTreeHeight`, `maxTreeHeight`, `tallVariantChance`
- **Spreading**: `spreadCheckInterval` (ticks), `spreadChance`, `minSpreadDistance`, `maxSpreadDistance`
- **Root Network**: `strippedLogRadius`, `rootNetworkRadius`, block type chances
- **Performance**: `maxSpreadAttemptsPerTick`, `maxNearbyAspens`
- **Biome Limits**: `limitToPlainsOnly`, `plainsCheckRadius`

Default spread check is every 60 seconds with a 15% chance per tree.

## Building

```bash
./gradlew build
```

The compiled mod will be in `build/libs/`.

## Setup

For development setup, see the [Fabric wiki](https://fabricmc.net/wiki/tutorial:setup).

## License

CC0-1.0
