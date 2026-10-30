package dev.aurora.lobby;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Procedural 20-circuit ice-boat racing for solo time runs and head-to-head duels. */
public final class IceBoatManager implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] TRACKS = {
            "Frostbite Sprint", "Aurora Loop", "Glacier Run", "Blue Comet", "Icefall Oval",
            "Polar Circuit", "Shiver Speedway", "Crystal Bend", "Winterglass", "Northwind Ring",
            "Snowblind", "Permafrost Dash", "Frozen Ribbon", "Arctic Current", "Moonlit Ice",
            "Tundra Twist", "Whiteout Run", "Neon Drift", "Boreal Rush", "Last Light Circuit"
    };
    private final JavaPlugin plugin;
    private final Map<String, List<UUID>> duelQueues = new HashMap<>();
    private final Map<UUID, Race> races = new HashMap<>();
    private final Set<Integer> occupiedSlots = new HashSet<>();
    private int nextSlot;

    public IceBoatManager(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean queue(Player player, String format, String difficulty) {
        if (!Set.of("easy", "normal", "hard").contains(difficulty)) {
            player.sendMessage(MM.deserialize("<gray>Choose difficulty: <green>easy</green>, <yellow>normal</yellow>, or <red>hard</red>."));
            return true;
        }
        if (races.containsKey(player.getUniqueId())) {
            player.sendMessage(MM.deserialize("<yellow>You are already racing."));
            return true;
        }
        if (format.equals("solo")) {
            int track = ThreadLocalRandom.current().nextInt(TRACKS.length);
            start(List.of(player.getUniqueId()), difficulty, track);
            return true;
        }
        if (!format.equals("1v1")) return false;
        String queueKey = difficulty;
        List<UUID> queue = duelQueues.computeIfAbsent(queueKey, ignored -> new ArrayList<>());
        if (queue.contains(player.getUniqueId())) {
            player.sendMessage(MM.deserialize("<yellow>You are already waiting for an opponent."));
            return true;
        }
        if (queue.isEmpty()) {
            queue.add(player.getUniqueId());
            player.sendMessage(MM.deserialize("<aqua>Queued for Ice Boat Racing 1v1 <white>" + difficulty + "<aqua>. Waiting for an opponent..."));
            return true;
        }
        UUID opponent = queue.remove(0);
        int track = ThreadLocalRandom.current().nextInt(TRACKS.length);
        start(List.of(opponent, player.getUniqueId()), difficulty, track);
        return true;
    }

    public boolean leave(Player player) {
        boolean removed = false;
        for (List<UUID> queue : duelQueues.values()) removed |= queue.remove(player.getUniqueId());
        return removed;
    }

    public boolean isRacing(UUID player) { return races.containsKey(player); }

    private void start(List<UUID> ids, String difficulty, int trackId) {
        World world = plugin.getServer().getWorld(plugin.getConfig().getString("spawn.world", "world"));
        if (world == null) {
            ids.forEach(id -> { Player player = Bukkit.getPlayer(id); if (player != null) player.sendMessage(MM.deserialize("<red>Race unavailable: configured world is not loaded.")); });
            return;
        }
        int slot = allocateSlot();
        if (slot < 0) {
            ids.forEach(id -> { Player player = Bukkit.getPlayer(id); if (player != null) player.sendMessage(MM.deserialize("<red>All race tracks are in use; try again shortly.")); });
            return;
        }
        int centerX = 4096 + slot * 256;
        int centerZ = 4096;
        int y = Math.max(100, Math.min(250, world.getHighestBlockYAt((int) plugin.getConfig().getDouble("spawn.x", 0), (int) plugin.getConfig().getDouble("spawn.z", 0)) + 60));
        int rx = 24 + (trackId * 7 % 17);
        int rz = 18 + (trackId * 11 % 13);
        int width = difficulty.equals("easy") ? 6 : difficulty.equals("hard") ? 3 : 4;
        buildTrack(world, centerX, centerZ, y, rx, rz, width, trackId);
        Location start = new Location(world, centerX + rx, y + 1, centerZ + .5, 90, 0);
        Race race = new Race(ids, world, trackId, slot, centerX, centerZ, y, rx, rz, difficulty, start);
        for (int i = 0; i < ids.size(); i++) {
            Player player = Bukkit.getPlayer(ids.get(i));
            if (player == null) continue;
            race.boats.add(null);
            races.put(player.getUniqueId(), race);
            player.closeInventory();
            player.getInventory().clear();
            player.setGameMode(GameMode.ADVENTURE);
            Location laneStart = start.clone().add(0, 0, i == 0 ? -1.2 : 1.2);
            player.teleport(laneStart);
            player.sendMessage(MM.deserialize("<dark_aqua><bold>ICE BOAT RACING</bold> <gray>» <aqua>" + TRACKS[trackId] + " <dark_gray>| <white>" + difficulty.toUpperCase()));
            player.sendMessage(MM.deserialize(ids.size() == 1 ? "<gray>Complete one lap for your time. The track is random each run." : "<gray>First to the finish wins! Checkpoints are sequential."));
        }
        race.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(race), 0L, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (race.finished) return;
            for (int i = 0; i < ids.size(); i++) {
                Player player = Bukkit.getPlayer(ids.get(i));
                if (player == null || !races.containsKey(player.getUniqueId())) continue;
                Location laneStart = start.clone().add(0, 0, i == 0 ? -1.2 : 1.2);
                Boat boat = world.spawn(laneStart, Boat.class);
                boat.setBoatType(Boat.Type.OAK);
                boat.addPassenger(player);
                race.boats.set(i, boat);
                player.sendMessage(MM.deserialize("<green>GO! <gray>Drive through the glowing checkpoint gates in order."));
            }
        }, 60L);
    }

    private int allocateSlot() {
        for (int i = 0; i < 20; i++) {
            int slot = (nextSlot + i) % 20;
            if (occupiedSlots.add(slot)) { nextSlot = (slot + 1) % 20; return slot; }
        }
        return -1;
    }

    private void buildTrack(World world, int cx, int cz, int y, int rx, int rz, int width, int trackId) {
        // Clear only this track's bounded volume before deterministic generation.
        for (int x = cx - rx - width - 3; x <= cx + rx + width + 3; x++) {
            for (int z = cz - rz - width - 3; z <= cz + rz + width + 3; z++) {
                for (int yy = y - 1; yy <= y + 3; yy++) world.getBlockAt(x, yy, z).setType(Material.AIR, false);
            }
        }
        for (int degree = 0; degree < 360; degree++) {
            double angle = Math.toRadians(degree);
            int px = cx + (int) Math.round(rx * Math.cos(angle));
            int pz = cz + (int) Math.round(rz * Math.sin(angle));
            double nx = Math.cos(angle), nz = Math.sin(angle);
            for (int offset = -width; offset <= width; offset++) {
                int x = px + (int) Math.round(nx * offset);
                int z = pz + (int) Math.round(nz * offset);
                Block surface = world.getBlockAt(x, y, z);
                surface.setType(offset == -width || offset == width ? Material.PACKED_ICE : (trackId % 3 == 0 ? Material.BLUE_ICE : Material.ICE), false);
                world.getBlockAt(x, y - 1, z).setType(Material.PACKED_ICE, false);
            }
        }
        // Four colored gates; the start gate doubles as the final checkpoint.
        for (int gate = 0; gate < 4; gate++) {
            double a = Math.toRadians(gate * 90);
            int gx = cx + (int) Math.round(rx * Math.cos(a));
            int gz = cz + (int) Math.round(rz * Math.sin(a));
            Material color = gate % 2 == 0 ? Material.SEA_LANTERN : Material.CYAN_GLAZED_TERRACOTTA;
            for (int side : new int[]{-1, 1}) {
                int x = gx + (int) Math.round(Math.cos(a) * width * side);
                int z = gz + (int) Math.round(Math.sin(a) * width * side);
                world.getBlockAt(x, y + 1, z).setType(color, false);
                world.getBlockAt(x, y + 2, z).setType(color, false);
            }
        }
    }

    private void tick(Race race) {
        if (race.finished) return;
        for (int i = 0; i < race.players.size(); i++) {
            UUID id = race.players.get(i);
            Player player = Bukkit.getPlayer(id);
            Boat boat = race.boats.get(i);
            if (player == null || boat == null || !boat.isValid()) continue;
            Location loc = boat.getLocation();
            if (loc.getY() < race.y - 12) {
                boat.teleport(race.start.clone().add(0, 0, i == 0 ? -1.2 : 1.2));
                player.sendMessage(MM.deserialize("<red>Reset to the start—stay on the ice!"));
                continue;
            }
            double angle = Math.toDegrees(Math.atan2((loc.getZ() - race.cz) / race.rz, (loc.getX() - race.cx) / race.rx));
            if (angle < 0) angle += 360;
            int sector = ((int) angle / 90) % 4;
            if (sector == race.nextSector[i] && isNearTrack(loc, race)) {
                race.nextSector[i] = (race.nextSector[i] + 1) % 4;
                if (race.nextSector[i] == 1) {
                    race.laps[i]++;
                    if (race.laps[i] >= 1) { finish(race, id, i); return; }
                }
                player.sendActionBar(MM.deserialize("<aqua>" + TRACKS[race.trackId] + " <dark_gray>» <white>Checkpoint " + (race.nextSector[i] + 1) + "/4"));
            }
            long elapsed = (System.currentTimeMillis() - race.startedAt) / 1000;
            player.sendActionBar(MM.deserialize("<aqua>" + TRACKS[race.trackId] + " <dark_gray>» <white>" + elapsed + "s <gray>Checkpoint " + race.nextSector[i] + "/4"));
        }
    }

    private boolean isNearTrack(Location loc, Race race) {
        double normalized = Math.sqrt(Math.pow((loc.getX() - race.cx) / race.rx, 2) + Math.pow((loc.getZ() - race.cz) / race.rz, 2));
        return Math.abs(normalized - 1.0) < 0.35;
    }

    private void finish(Race race, UUID winnerId, int winnerIndex) {
        if (race.finished) return;
        race.finished = true;
        race.task.cancel();
        long seconds = (System.currentTimeMillis() - race.startedAt) / 1000;
        for (int i = 0; i < race.players.size(); i++) {
            UUID id = race.players.get(i);
            Player player = Bukkit.getPlayer(id);
            races.remove(id);
            if (player == null) continue;
            if (race.boats.get(i) != null && race.boats.get(i).isValid()) race.boats.get(i).remove();
            player.getInventory().clear();
            player.setGameMode(GameMode.ADVENTURE);
            player.sendMessage(MM.deserialize(id.equals(winnerId) ? "<gold><bold>FINISH! <white>" + TRACKS[race.trackId] + " — " + seconds + "s" : "<gray>Race over. <white>" + Bukkit.getOfflinePlayer(winnerId).getName() + " finished first."));
            Location lobby = new Location(race.world, plugin.getConfig().getDouble("spawn.x", .5), plugin.getConfig().getDouble("spawn.y", 80), plugin.getConfig().getDouble("spawn.z", .5));
            player.teleport(lobby);
        }
        occupiedSlots.remove(race.slot);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        leave(event.getPlayer());
        Race race = races.get(id);
        if (race != null) {
            races.remove(id);
            if (race.players.size() == 1) finish(race, id, 0);
            else {
                UUID other = race.players.get(0).equals(id) ? race.players.get(1) : race.players.get(0);
                finish(race, other, race.players.indexOf(other));
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && races.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    private static final class Race {
        final List<UUID> players;
        final List<Boat> boats = new ArrayList<>();
        final int[] nextSector;
        final int[] laps;
        final World world;
        final int trackId, slot, cx, cz, y, rx, rz;
        final String difficulty;
        final Location start;
        long startedAt = System.currentTimeMillis() + 3000;
        boolean finished;
        BukkitTask task;
        Race(List<UUID> players, World world, int trackId, int slot, int cx, int cz, int y, int rx, int rz, String difficulty, Location start) {
            this.players = players; this.world = world; this.trackId = trackId; this.slot = slot; this.cx = cx; this.cz = cz; this.y = y; this.rx = rx; this.rz = rz; this.difficulty = difficulty; this.start = start;
            nextSector = new int[players.size()];
            laps = new int[players.size()];
            java.util.Arrays.fill(nextSector, 1);
        }
    }
}
