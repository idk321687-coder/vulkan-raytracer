package dev.aurora.lobby;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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

/** Small, self-contained two-team Bed Wars match runner for early playtesting. */
public final class BedWarsManager implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final List<UUID> waiting = new ArrayList<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    private final Map<UUID, Location> respawns = new HashMap<>();
    private BukkitTask countdown;
    private String activeQueueVariant;

    public BedWarsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean queue(Player player, String variant) {
        if (!variant.equals("classic") && !variant.equals("chill") && !variant.equals("rush")) return false;
        if (matches.containsKey(player.getUniqueId()) || waiting.contains(player.getUniqueId())) {
            player.sendMessage(MM.deserialize("<yellow>You are already queued or in a Bed Wars match."));
            return true;
        }
        if (!waiting.isEmpty() && activeQueueVariant != null && !activeQueueVariant.equals(variant)) {
            player.sendMessage(MM.deserialize("<red>The Bed Wars queue is currently set to <white>" + activeQueueVariant + "<red>. Try again after it starts."));
            return true;
        }
        activeQueueVariant = variant;
        waiting.add(player.getUniqueId());
        player.sendMessage(MM.deserialize("<aqua>Queued for Bed Wars " + variant + ". <gray>Waiting for one more player..."));
        if (waiting.size() >= 2 && countdown == null) {
            final int[] seconds = {5};
            countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    waiting.removeIf(id -> Bukkit.getPlayer(id) == null);
                    if (waiting.size() < 2) {
                    if (seconds[0] < 5) waiting.forEach(id -> Bukkit.getPlayer(id).sendMessage(MM.deserialize("<gray>Matchmaking countdown cancelled.")));
                    countdown.cancel();
                    countdown = null;
                    if (waiting.isEmpty()) activeQueueVariant = null;
                    return;
                }
                if (seconds[0] <= 0) {
                    List<UUID> players = new ArrayList<>(waiting.subList(0, Math.min(waiting.size(), 8)));
                    waiting.removeAll(players);
                    if (waiting.isEmpty()) activeQueueVariant = null;
                    start(players, variant);
                    countdown.cancel();
                    countdown = null;
                    return;
                }
                waiting.forEach(id -> Bukkit.getPlayer(id).sendMessage(MM.deserialize("<aqua>Bed Wars starts in <white>" + seconds[0] + "<aqua>s.")));
                seconds[0]--;
            }, 0L, 20L);
        }
        return true;
    }

    public boolean leaveQueue(Player player) {
        boolean removed = waiting.remove(player.getUniqueId());
        if (waiting.isEmpty() && countdown == null) activeQueueVariant = null;
        return removed;
    }

    public boolean isPlaying(UUID id) { return matches.containsKey(id); }
    public boolean isChill(UUID id) { Match match = matches.get(id); return match != null && match.variant.equals("chill"); }

    private void start(List<UUID> ids, String variant) {
        World world = plugin.getServer().getWorld(plugin.getConfig().getString("spawn.world", "world"));
        if (world == null) {
            ids.forEach(id -> { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage(MM.deserialize("<red>Match cancelled: arena world is unavailable.")); });
            return;
        }
        int arenaIndex = plugin.getConfig().getInt("bedwars.next-arena", 0);
        plugin.getConfig().set("bedwars.next-arena", (arenaIndex + 1) % 16);
        plugin.saveConfig();
        int centerX = 512 + (arenaIndex % 4) * 256;
        int centerZ = 512 + (arenaIndex / 4) * 256;
        int y = Math.max(100, world.getHighestBlockYAt((int) plugin.getConfig().getDouble("spawn.x", 0), (int) plugin.getConfig().getDouble("spawn.z", 0)) + 80);
        Location redSpawn = new Location(world, centerX - 24.5, y + 1, centerZ + .5);
        Location blueSpawn = new Location(world, centerX + 24.5, y + 1, centerZ + .5, 180, 0);
        buildIsland(world, centerX - 24, y - 1, centerZ, Material.RED_WOOL, Material.RED_BED);
        buildIsland(world, centerX + 24, y - 1, centerZ, Material.BLUE_WOOL, Material.BLUE_BED);
        Location redBed = new Location(world, centerX - 24, y, centerZ);
        Location blueBed = new Location(world, centerX + 24, y, centerZ);
        Match match = new Match(variant, world, redBed, blueBed, redSpawn, blueSpawn);
        for (int i = 0; i < ids.size(); i++) {
            Player player = Bukkit.getPlayer(ids.get(i));
            if (player == null) continue;
            boolean red = i % 2 == 0;
            match.teams.put(player.getUniqueId(), red ? 0 : 1);
            match.alive.add(player.getUniqueId());
            matches.put(player.getUniqueId(), match);
            player.getInventory().clear();
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            player.getInventory().addItem(new ItemStack(Material.WOODEN_SWORD));
            player.getInventory().addItem(new ItemStack(Material.SHEARS));
            player.getInventory().addItem(new ItemStack(Material.WHITE_WOOL, 64));
            player.teleport(red ? redSpawn : blueSpawn);
            player.sendMessage(MM.deserialize("<dark_aqua><bold>BED WARS</bold> <gray>— " + (variant.equals("chill") ? "<aqua>Chill Wars (PvP off)" : "<red>" + variant.toUpperCase()) + "<gray>. Break the enemy bed!"));
        }
        world.setGameRule(org.bukkit.GameRule.DO_IMMEDIATE_RESPAWN, false);
        Bukkit.broadcast(MM.deserialize("<dark_gray>[<aqua>Aurora</aqua>] <gray>Bed Wars match started with " + ids.size() + " players."));
    }

    private void buildIsland(World world, int x, int y, int z, Material teamWool, Material bedMaterial) {
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            Block block = world.getBlockAt(x + dx, y, z + dz);
            block.setType(Math.abs(dx) == 4 || Math.abs(dz) == 4 ? teamWool : Material.SNOW_BLOCK, false);
        }
        Block foot = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 1, z + 1);
        foot.setType(bedMaterial, false);
        head.setType(bedMaterial, false);
        Bed footData = (Bed) foot.getBlockData();
        footData.setPart(Bed.Part.FOOT);
        footData.setFacing(BlockFace.SOUTH);
        foot.setBlockData(footData, false);
        Bed headData = (Bed) head.getBlockData();
        headData.setPart(Bed.Part.HEAD);
        headData.setFacing(BlockFace.SOUTH);
        head.setBlockData(headData, false);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player attacker) {
            Match vm = matches.get(victim.getUniqueId());
            Match am = matches.get(attacker.getUniqueId());
            if (vm != null || am != null) {
                if (vm == null || vm != am || vm.variant.equals("chill") || vm.teams.get(victim.getUniqueId()).equals(vm.teams.get(attacker.getUniqueId()))) event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Match match = matches.get(event.getPlayer().getUniqueId());
        if (match == null) return;
        Location block = event.getBlockPlaced().getLocation();
        if (block.getWorld() != match.world || Math.abs(block.getX() - (match.redSpawn.getX() + match.blueSpawn.getX()) / 2) > 38
                || Math.abs(block.getZ() - (match.redSpawn.getZ() + match.blueSpawn.getZ()) / 2) > 14
                || block.getY() < match.redSpawn.getY() - 8 || block.getY() > match.redSpawn.getY() + 30) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(MM.deserialize("<red>Building is limited to the arena."));
        }
    }

    @EventHandler
    public void onBedBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Match match = matches.get(player.getUniqueId());
        if (match == null) return;
        Location blockLocation = event.getBlock().getLocation();
        if (Math.abs(blockLocation.getX() - (match.redSpawn.getX() + match.blueSpawn.getX()) / 2) > 38
                || Math.abs(blockLocation.getZ() - (match.redSpawn.getZ() + match.blueSpawn.getZ()) / 2) > 14) {
            event.setCancelled(true);
            return;
        }
        if (event.getBlock().getType() == Material.RED_BED || event.getBlock().getType() == Material.BLUE_BED) {
            int bedTeam = event.getBlock().getType() == Material.RED_BED ? 0 : 1;
            if (match.teams.get(player.getUniqueId()) == bedTeam) {
                event.setCancelled(true);
                player.sendMessage(MM.deserialize("<red>You cannot break your own bed."));
                return;
            }
            match.bedsAlive.remove(bedTeam);
            Bukkit.broadcast(MM.deserialize("<red><bold>BED DESTROYED! <white>" + (bedTeam == 0 ? "Red" : "Blue") + " team can no longer respawn."));
            event.setDropItems(false);
        } else if (!event.getBlock().getType().toString().endsWith("WOOL") && event.getBlock().getType() != Material.SNOW_BLOCK) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Match match = matches.get(player.getUniqueId());
        if (match == null) return;
        event.getDrops().clear();
        int team = match.teams.get(player.getUniqueId());
        if (match.bedsAlive.contains(team)) {
            Location spawn = team == 0 ? match.redSpawn : match.blueSpawn;
            respawns.put(player.getUniqueId(), spawn);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online != null && matches.containsKey(online.getUniqueId())) {
                    online.spigot().respawn();
                    online.getInventory().clear();
                    online.getInventory().addItem(new ItemStack(Material.WOODEN_SWORD));
                }
            }, 40L);
        } else {
            match.alive.remove(player.getUniqueId());
            Bukkit.broadcast(MM.deserialize("<gray>" + player.getName() + " was eliminated."));
            checkWinner(match);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location spawn = respawns.remove(event.getPlayer().getUniqueId());
        if (spawn != null) event.setRespawnLocation(spawn);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        waiting.remove(id);
        Match match = matches.get(id);
        if (match != null) { match.alive.remove(id); checkWinner(match); }
    }

    private void checkWinner(Match match) {
        Set<Integer> teamsAlive = new HashSet<>();
        for (UUID id : match.alive) if (matches.containsKey(id)) teamsAlive.add(match.teams.get(id));
        if (teamsAlive.size() > 1) return;
        String winner = teamsAlive.isEmpty() ? "No team" : (teamsAlive.contains(0) ? "Red" : "Blue");
        for (UUID id : new ArrayList<>(match.teams.keySet())) {
            Player player = Bukkit.getPlayer(id);
            matches.remove(id);
            respawns.remove(id);
            if (player != null) {
                player.sendMessage(MM.deserialize("<gold><bold>" + winner + " team wins Bed Wars!"));
                player.getInventory().clear();
                player.setGameMode(org.bukkit.GameMode.ADVENTURE);
                World lobbyWorld = plugin.getServer().getWorld(plugin.getConfig().getString("spawn.world", "world"));
                Location lobby = lobbyWorld == null ? player.getWorld().getSpawnLocation() : new Location(lobbyWorld,
                        plugin.getConfig().getDouble("spawn.x", .5), plugin.getConfig().getDouble("spawn.y", 80),
                        plugin.getConfig().getDouble("spawn.z", .5));
                player.teleport(lobby);
            }
        }
    }

    private static final class Match {
        final String variant;
        final World world;
        final Location redBed, blueBed, redSpawn, blueSpawn;
        final Map<UUID, Integer> teams = new HashMap<>();
        final Set<UUID> alive = new HashSet<>();
        final Set<Integer> bedsAlive = new HashSet<>(Set.of(0, 1));
        Match(String variant, World world, Location redBed, Location blueBed, Location redSpawn, Location blueSpawn) {
            this.variant = variant; this.world = world; this.redBed = redBed; this.blueBed = blueBed; this.redSpawn = redSpawn; this.blueSpawn = blueSpawn;
        }
    }
}
