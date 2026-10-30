package dev.aurora.lobby;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuroraLobby extends JavaPlugin implements Listener {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Map<UUID, String> queued = new HashMap<>();
    private final Set<UUID> pvpOptOut = new HashSet<>();
    private BedWarsManager bedWars;
    private IceBoatManager iceBoats;
    private static final String MENU_TITLE = "<dark_aqua><bold>AURORA <dark_gray>» <aqua>PLAY";
    private static final String RACE_MENU_TITLE = "<dark_aqua><bold>ICE RACING <dark_gray>» <aqua>FORMAT";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        bedWars = new BedWarsManager(this);
        iceBoats = new IceBoatManager(this);
        getLogger().info("Aurora lobby online — frozen-neon edition");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("play")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command is for players.");
                return true;
            }
            if (args.length == 0) {
                openPlayMenu(player);
            } else if (args.length == 1 && args[0].equalsIgnoreCase("leave")) {
                leaveQueue(player);
            } else if (args.length >= 2) {
                if (args[0].equalsIgnoreCase("iceboats") || args[0].equalsIgnoreCase("iceboat")) {
                    String format = args[1].equalsIgnoreCase("solo") ? "solo" : (args[1].equalsIgnoreCase("1v1") || args[1].equalsIgnoreCase("duel") ? "1v1" : "unknown");
                    if (format.equals("unknown")) player.sendMessage(mini.deserialize("<gray>Use <aqua>/play iceboats solo [easy|normal|hard]</aqua> or <aqua>/play iceboats 1v1 [easy|normal|hard]</aqua>."));
                    else iceBoats.queue(player, format, args.length >= 3 ? args[2].toLowerCase() : "normal");
                } else {
                    joinQueue(player, normalizeMode(args[0], args[1]));
                }
            } else {
                player.sendMessage(mini.deserialize("<gray>Usage: <aqua>/play [bedwars|iceboats] [classic|chill|rush|time_trial|circuit|derby] <dark_gray>or <aqua>/play leave"));
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("settings")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command is for players.");
                return true;
            }
            if (args.length != 2 || !args[0].equalsIgnoreCase("pvp") || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                player.sendMessage(mini.deserialize("<gray>Usage: <aqua>/settings pvp on|off"));
                return true;
            }
            if (args[1].equalsIgnoreCase("off")) pvpOptOut.add(player.getUniqueId());
            else pvpOptOut.remove(player.getUniqueId());
            player.sendMessage(mini.deserialize(args[1].equalsIgnoreCase("off")
                    ? "<green>PvP preference: <white>off<gray>. You will not be queued for PvP modes."
                    : "<green>PvP preference: <white>on<gray>. PvP modes are available."));
            if (args[1].equalsIgnoreCase("off") && isPvp(queued.get(player.getUniqueId()))) leaveQueue(player);
            return true;
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(mini.deserialize("<dark_gray>[<aqua>+</aqua>] <white>" + escape(player.getName())));
        World world = getServer().getWorld(getConfig().getString("spawn.world", "world"));
        if (world != null) {
            Location spawn = new Location(world,
                    getConfig().getDouble("spawn.x", 0.5), getConfig().getDouble("spawn.y", 80),
                    getConfig().getDouble("spawn.z", 0.5), (float) getConfig().getDouble("spawn.yaw", 0),
                    (float) getConfig().getDouble("spawn.pitch", 0));
            player.teleportAsync(spawn);
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20);
        player.setFoodLevel(20);
        player.sendMessage(mini.deserialize("<dark_aqua><bold>AURORA</bold></dark_aqua> <gray>Welcome. Open <aqua>/play</aqua> to browse modes."));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        queued.remove(event.getPlayer().getUniqueId());
        bedWars.leaveQueue(event.getPlayer());
        iceBoats.leave(event.getPlayer());
    }

    private void openPlayMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, mini.deserialize(MENU_TITLE));
        inventory.setItem(10, item(Material.RED_BED, "<red><bold>Bed Wars</bold>", "<gray>Classic, Chill Wars, and Rush", "<dark_gray>Click to view queue options"));
        inventory.setItem(13, item(Material.ICE, "<aqua><bold>Ice Boat Racing</bold>", "<gray>Time Trial, Circuit, and Derby", "<dark_gray>Click to view queue options"));
        inventory.setItem(16, item(Material.BARRIER, "<dark_gray>Leave queue", "<gray>Current: " + queued.getOrDefault(player.getUniqueId(), "<italic>none")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = mini.serialize(event.getView().title());
        if (title.equals(mini.serialize(mini.deserialize(RACE_MENU_TITLE)))) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot != 10 && slot != 12 && slot != 14 && slot != 19 && slot != 21 && slot != 23) return;
            String format = slot >= 19 ? "1v1" : "solo";
            String difficulty = (slot == 10 || slot == 19) ? "easy" : (slot == 14 || slot == 23) ? "hard" : "normal";
            player.closeInventory();
            iceBoats.queue(player, format, difficulty);
            return;
        }
        if (!title.equals(mini.serialize(mini.deserialize(MENU_TITLE)))) return;
        event.setCancelled(true);
        switch (event.getRawSlot()) {
            case 10 -> player.sendMessage(mini.deserialize("<gray>Bed Wars: <aqua>/play bedwars classic</aqua>, <aqua>/play bedwars chill</aqua>, <aqua>/play bedwars rush</a>."));
            case 13 -> openRaceMenu(player);
            case 16 -> leaveQueue(player);
            default -> { }
        }
    }

    private void openRaceMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, mini.deserialize(RACE_MENU_TITLE));
        inventory.setItem(10, item(Material.PACKED_ICE, "<green>Solo — Easy", "<gray>Wide course and relaxed turns"));
        inventory.setItem(12, item(Material.ICE, "<yellow>Solo — Normal", "<gray>Standard course width"));
        inventory.setItem(14, item(Material.BLUE_ICE, "<red>Solo — Hard", "<gray>Narrow course and sharper turns"));
        inventory.setItem(19, item(Material.PACKED_ICE, "<green>1v1 — Easy", "<gray>Race a queued opponent"));
        inventory.setItem(21, item(Material.ICE, "<yellow>1v1 — Normal", "<gray>Race a queued opponent"));
        inventory.setItem(23, item(Material.BLUE_ICE, "<red>1v1 — Hard", "<gray>Race a queued opponent"));
        player.openInventory(inventory);
    }

    private void joinQueue(Player player, String mode) {
        if (mode.equals("unknown")) {
            player.sendMessage(mini.deserialize("<red>Unknown mode/variant. Use <white>/play</white> to see available options."));
            return;
        }
        if (isPvp(mode) && pvpOptOut.contains(player.getUniqueId())) {
            player.sendMessage(mini.deserialize("<red>You opted out of PvP. Use <white>/settings pvp on</white> to join this mode."));
            return;
        }
        if (mode.startsWith("bedwars ")) {
            bedWars.queue(player, mode.substring("bedwars ".length()));
            return;
        }
        queued.put(player.getUniqueId(), mode);
        player.sendMessage(mini.deserialize("<aqua>Queue selected: <white>" + escape(mode) + "<gray>. This mode is not match-enabled yet."));
    }

    private void leaveQueue(Player player) {
        boolean removed = bedWars.leaveQueue(player) | iceBoats.leave(player);
        String previous = queued.remove(player.getUniqueId());
        if (previous != null) {
            player.sendMessage(mini.deserialize("<gray>Left the <aqua>" + escape(previous) + "</aqua> queue."));
        } else {
            player.sendMessage(mini.deserialize(removed ? "<gray>You left your queue." : "<gray>You are not in a queue."));
        }
    }

    private String normalizeMode(String game, String variant) {
        String key = (game + " " + variant).toLowerCase().replace('-', '_');
        return switch (key) {
            case "bedwars classic", "bedwars chill", "bedwars rush", "iceboats time_trial", "iceboats circuit", "iceboats derby" -> key;
            default -> "unknown";
        };
    }

    private boolean isPvp(String mode) {
        return mode != null && (mode.equals("bedwars classic") || mode.equals("bedwars rush") || mode.equals("iceboats derby"));
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(mini.deserialize(name));
        meta.lore(java.util.Arrays.stream(lore).map(mini::deserialize).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private String escape(String text) {
        return text.replace("<", "\\<").replace(">", "\\>");
    }
}
