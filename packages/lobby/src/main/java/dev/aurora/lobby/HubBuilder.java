package dev.aurora.lobby;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

/** One-time procedural frozen-neon spawn build. */
final class HubBuilder {
    private final JavaPlugin plugin;

    HubBuilder(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    Location build(World world) {
        int cx = (int) Math.floor(plugin.getConfig().getDouble("spawn.x", 0.5));
        int cz = (int) Math.floor(plugin.getConfig().getDouble("spawn.z", 0.5));
        int y = plugin.getConfig().getInt("hub.platform-y", 120);
        Location playerSpawn = new Location(world, cx + 0.5, y + 1.1, cz - 5.5, 0, 0);
        if (world.getBlockAt(cx, y, cz).getType() == Material.QUARTZ_BLOCK) {
            plugin.getConfig().set("spawn.y", y + 1.1);
            return playerSpawn;
        }

        // Clear the build volume above the floating island; terrain below it is untouched.
        for (int x = cx - 33; x <= cx + 33; x++) {
            for (int z = cz - 33; z <= cz + 33; z++) {
                for (int yy = y + 1; yy <= y + 24; yy++) {
                    Block block = world.getBlockAt(x, yy, z);
                    if (!block.getType().isAir()) block.setType(Material.AIR, false);
                }
            }
        }

        // Layered circular island, with a dark rim and a bright ice-stone plaza.
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                double radius = Math.sqrt(x * x + z * z);
                if (radius > 31) continue;
                Material floor = radius > 28 ? Material.POLISHED_DEEPSLATE
                        : radius > 26 ? Material.CYAN_CONCRETE
                        : ((Math.abs(x + z) % 11 == 0) ? Material.PACKED_ICE : Material.SNOW_BLOCK);
                world.getBlockAt(cx + x, y, cz + z).setType(floor, false);
                if (radius > 26 && radius <= 28) world.getBlockAt(cx + x, y + 1, cz + z).setType(Material.SEA_LANTERN, false);
                if (radius <= 14) world.getBlockAt(cx + x, y - 1, cz + z).setType(Material.DARK_PRISMARINE, false);
                else if (radius <= 21) world.getBlockAt(cx + x, y - 1, cz + z).setType(Material.PACKED_ICE, false);
            }
        }

        // A luminous central spire gives the plaza a readable landmark.
        for (int yy = y + 1; yy <= y + 16; yy++) {
            world.getBlockAt(cx, yy, cz).setType(yy % 3 == 0 ? Material.SEA_LANTERN : Material.LIGHT_BLUE_STAINED_GLASS, false);
            world.getBlockAt(cx + 1, yy, cz).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
            world.getBlockAt(cx - 1, yy, cz).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
            world.getBlockAt(cx, yy, cz + 1).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
            world.getBlockAt(cx, yy, cz - 1).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
        }
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) == 3) {
                world.getBlockAt(cx + dx, y + 17, cz + dz).setType(Material.QUARTZ_BLOCK, false);
                world.getBlockAt(cx + dx, y + 18, cz + dz).setType(Material.SEA_LANTERN, false);
            }
        }

        // Three gateway arches mark the game portal row. /play opens the mode selector.
        buildGateway(world, cx - 12, y, cz + 13, Material.LIGHT_BLUE_STAINED_GLASS, Material.PACKED_ICE);
        buildGateway(world, cx, y, cz + 13, Material.CYAN_STAINED_GLASS, Material.SEA_LANTERN);
        buildGateway(world, cx + 12, y, cz + 13, Material.MAGENTA_STAINED_GLASS, Material.AMETHYST_BLOCK);
        for (int z = cz - 4; z <= cz + 12; z++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (Math.abs(dx) == 2) world.getBlockAt(cx + dx, y + 1, z).setType(Material.SEA_LANTERN, false);
            }
        }

        // Low perimeter pylons and sheltered light posts make the edge legible at night.
        for (int angle = 0; angle < 360; angle += 30) {
            double radians = Math.toRadians(angle);
            int px = cx + (int) Math.round(24 * Math.cos(radians));
            int pz = cz + (int) Math.round(24 * Math.sin(radians));
            for (int yy = y + 1; yy <= y + 4; yy++) {
                world.getBlockAt(px, yy, pz).setType(yy == y + 4 ? Material.SEA_LANTERN : Material.QUARTZ_PILLAR, false);
            }
        }
        world.getBlockAt(cx, y, cz).setType(Material.QUARTZ_BLOCK, false); // persistent generated-hub marker
        plugin.getConfig().set("spawn.y", y + 1.1);
        plugin.getConfig().set("hub.generated", true);
        plugin.saveConfig();
        return playerSpawn;
    }

    private void buildGateway(World world, int x, int y, int z, Material glass, Material accent) {
        for (int yy = 1; yy <= 6; yy++) {
            world.getBlockAt(x - 4, y + yy, z).setType(Material.QUARTZ_PILLAR, false);
            world.getBlockAt(x + 4, y + yy, z).setType(Material.QUARTZ_PILLAR, false);
        }
        for (int dx = -4; dx <= 4; dx++) {
            world.getBlockAt(x + dx, y + 6, z).setType(dx == 0 ? accent : Material.QUARTZ_BLOCK, false);
            if (Math.abs(dx) <= 3) world.getBlockAt(x + dx, y + 1, z).setType(accent, false);
        }
        for (int dx = -3; dx <= 3; dx++) {
            world.getBlockAt(x + dx, y + 2, z).setType(glass, false);
            world.getBlockAt(x + dx, y + 3, z).setType(glass, false);
            world.getBlockAt(x + dx, y + 4, z).setType(glass, false);
            world.getBlockAt(x + dx, y + 5, z).setType(glass, false);
        }
        world.getBlockAt(x, y + 7, z).setType(Material.END_ROD, false);
    }
}
