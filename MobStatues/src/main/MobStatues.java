package main;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MobStatues extends JavaPlugin implements TabCompleter {

    private final Map<UUID, Map<String, Entity>> playerStatueMap = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("MobStatues has been enabled!");
        getCommand("ms").setTabCompleter(this);
        getCommand("msmove").setTabCompleter(this);
        loadPlayerStatuesData();
    }

    @Override
    public void onDisable() {
        getLogger().info("MobStatues has been disabled!");
        removeStatues();
        savePlayerStatuesData();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ms")) {
            if (args.length >= 2) {
                if (sender instanceof Player player) {
                    String statueName = args[0].toLowerCase();
                    String entityName = args[1].toUpperCase();

                    createStatue(player, statueName, entityName);
                } else {
                    sender.sendMessage("This command can only be used by players.");
                }
                return true;
            } else {
                sender.sendMessage("Usage: /ms <name> <entity name>");
                return true;
            }
        } else if (command.getName().equalsIgnoreCase("msmove")) {
            if (args.length == 1) {
                if (sender instanceof Player player) {
                    String statueName = args[0].toLowerCase();

                    moveStatue(player, statueName);
                } else {
                    sender.sendMessage("This command can only be used by players.");
                }
                return true;
            } else {
                sender.sendMessage("Usage: /msmove <name>");
                return true;
            }
        } else if (command.getName().equalsIgnoreCase("msdel")) {
            if (args.length == 0 || (args.length == 1 && args[0].isEmpty())) {
                if (sender instanceof Player player) {
                    listPlayerStatues(player.getUniqueId());
                } else {
                    sender.sendMessage("This command can only be used by players.");
                }
                return true;
            } else if (args.length == 1) {
                String statueName = args[0].toLowerCase();

                removeStatue(sender, statueName);
                return true;
            } else {
                sender.sendMessage("Usage: /msdel [statue name]");
                return true;
            }
        }
        return false;
    }

    private void createStatue(Player player, String statueName, String entityName) {
        // Remove the player's existing statue with the same name, if any
        removeStatue(player.getUniqueId(), statueName);

        // Get the entity type based on the given name
        EntityType entityType = EntityType.valueOf(entityName);
        if (entityType == null || !entityType.isAlive()) {
            player.sendMessage("Invalid entity name.");
            return;
        }

        // Spawn the entity at the player's location
        Location location = player.getLocation();
        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, entityType);

        // Set the statue properties
        entity.setPersistent(true);
        entity.setInvulnerable(true);
        entity.setAI(false);
        entity.setCollidable(false);
        entity.setGravity(false);
        entity.setSilent(true);

        // Store the statue in the map
        storeStatue(player.getUniqueId(), statueName, entity);

        player.sendMessage("Statue '" + statueName + "' created successfully.");

        // Save the player's statues data to file
        savePlayerStatuesData();
    }


    private void removeOldStatue(Entity statue) {
        Location statueLocation = statue.getLocation();

        // Remove the old statue using kill commands
        String killCommand = "minecraft:kill @e[type=" + statue.getType().name().toLowerCase() + ",x=" + statueLocation.getX() + ",y=" + statueLocation.getY() + ",z=" + statueLocation.getZ() + ",distance=0]";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), killCommand);

        // Remove the dropped items continuously for 1 tick
        AtomicInteger duration = new AtomicInteger(1);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            CommandSender consoleSender = Bukkit.getConsoleSender();
            String command = "minecraft:kill @e[type=item,x=" + statueLocation.getX() + ",y=" + statueLocation.getY() + ",z=" + statueLocation.getZ() + ",distance=..3]";
            consoleSender.getServer().dispatchCommand(consoleSender, command);
            int remainingTicks = duration.decrementAndGet();
            if (remainingTicks <= 0) {
                // Cancel the task after the desired duration
                Bukkit.getScheduler().cancelTasks(this);
            }
        }, 0L, 1L); // Run every tick (0L), with an initial delay of 0 ticks and a period of 1 tick
    }

    private boolean moveStatue(Player player, String statueName) {
        Map<String, Entity> playerStatues = playerStatueMap.get(player.getUniqueId());
        if (playerStatues != null) {
            Entity statue = playerStatues.get(statueName);
            if (statue instanceof LivingEntity) {
                LivingEntity livingEntity = (LivingEntity) statue;
                Location location = player.getLocation();

                // Remove the old statue
                removeOldStatue(livingEntity);

                // Create a new statue at the player's location
                createStatue(player, statueName, livingEntity.getType().name());
                Entity newStatue = playerStatues.get(statueName);
                if (newStatue != null && newStatue instanceof LivingEntity) {
                    LivingEntity newLivingEntity = (LivingEntity) newStatue;
                    newLivingEntity.teleport(location);
                    return true;
                }
            }
        }
        return false;
    }

    private void storeStatue(UUID playerId, String statueName, Entity entity) {
        playerStatueMap.computeIfAbsent(playerId, k -> new HashMap<>()).put(statueName, entity);
    }

    private boolean removeStatue(UUID playerId, String statueName) {
        Map<String, Entity> playerStatues = playerStatueMap.get(playerId);
        if (playerStatues != null) {
            Entity statue = playerStatues.remove(statueName);
            if (statue != null) {
                statue.remove();
                // Save the player's statues data to file
                savePlayerStatuesData();
                return true;
            }
        }
        return false;
    }

    private void removeStatue(CommandSender sender, String statueName) {
        if (sender instanceof Player player) {
            UUID playerId = player.getUniqueId();
            Map<String, Entity> playerStatues = playerStatueMap.get(playerId);
            if (playerStatues != null) {
                Entity statue = playerStatues.remove(statueName);
                if (statue != null) {
                    removeOldStatue(statue); // Use the new removeOldStatue method

                    // Remove the statue entry from the player's YAML file
                    File playersDataFolder = new File(getDataFolder(), "players");
                    File playerDataFile = new File(playersDataFolder, playerId.toString() + ".yml");
                    if (playerDataFile.exists()) {
                        FileConfiguration playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
                        ConfigurationSection statuesSection = playerDataConfig.getConfigurationSection("statues");
                        if (statuesSection != null) {
                            statuesSection.set(statueName, null);
                            try {
                                playerDataConfig.save(playerDataFile);
                            } catch (IOException e) {
                                getLogger().warning("Failed to save player data for player " + playerId.toString());
                                e.printStackTrace();
                            }
                        }
                    }
                    sender.sendMessage("Statue '" + statueName + "' removed.");
                } else {
                    sender.sendMessage("You don't have a statue named '" + statueName + "'.");
                }
            } else {
                sender.sendMessage("You don't have any statues.");
            }
        } else {
            sender.sendMessage("This command can only be used by players.");
        }
    }


    private void listPlayerStatues(UUID playerId) {
        Map<String, Entity> playerStatues = playerStatueMap.get(playerId);
        if (playerStatues != null && !playerStatues.isEmpty()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage("Your statues:");
                for (String statueName : playerStatues.keySet()) {
                    player.sendMessage("- " + statueName);
                }
            }
        } else {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage("You don't have any statues.");
            }
        }
    }

    private void removeStatues() {
        for (Map<String, Entity> playerStatues : playerStatueMap.values()) {
            for (Entity statue : playerStatues.values()) {
                statue.remove();
            }
        }
        playerStatueMap.clear();
    }

    private void loadPlayerStatuesData() {
        File playersDataFolder = new File(getDataFolder(), "players");
        if (!playersDataFolder.exists()) {
            playersDataFolder.mkdirs();
        }

        // Load statues data for each player
        File[] playerDataFiles = playersDataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (playerDataFiles != null) {
            for (File playerDataFile : playerDataFiles) {
                String playerName = playerDataFile.getName().replace(".yml", "");
                UUID playerId = UUID.fromString(playerName);
                FileConfiguration playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
                ConfigurationSection statuesSection = playerDataConfig.getConfigurationSection("statues");
                if (statuesSection != null) {
                    Map<String, Entity> playerStatues = new HashMap<>();
                    for (String statueName : statuesSection.getKeys(false)) {
                        Entity statue = loadStatueFromConfig(playerId, statueName, statuesSection.getConfigurationSection(statueName));
                        if (statue != null) {
                            playerStatues.put(statueName, statue);
                        }
                    }
                    playerStatueMap.put(playerId, playerStatues);
                }
            }
        }
    }

    private Entity loadStatueFromConfig(UUID playerId, String statueName, ConfigurationSection statueSection) {
        String worldName = statueSection.getString("world");
        double x = statueSection.getDouble("x");
        double y = statueSection.getDouble("y");
        double z = statueSection.getDouble("z");
        float yaw = (float) statueSection.getDouble("yaw");
        float pitch = (float) statueSection.getDouble("pitch");

        Location location = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
        EntityType entityType = EntityType.valueOf(statueSection.getString("entityType"));
        if (entityType == null || !entityType.isAlive()) {
            getLogger().warning("Invalid entity type for statue '" + statueName + "'.");
            return null;
        }

        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, entityType);
        if (entity != null) {
            entity.setInvulnerable(true);
            entity.setAI(false);
            entity.setCollidable(false);
            entity.setGravity(false);
            entity.setSilent(true);
        }

        return entity;
    }

    private void savePlayerStatuesData() {
        File playersDataFolder = new File(getDataFolder(), "players");
        if (!playersDataFolder.exists()) {
            playersDataFolder.mkdirs();
        }

        // Save statues data for each player
        for (UUID playerId : playerStatueMap.keySet()) {
            Map<String, Entity> playerStatues = playerStatueMap.get(playerId);

            File playerDataFile = new File(playersDataFolder, playerId.toString() + ".yml");
            FileConfiguration playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);

            ConfigurationSection statuesSection = playerDataConfig.createSection("statues");
            for (String statueName : playerStatues.keySet()) {
                Entity statue = playerStatues.get(statueName);
                if (statue != null && statue instanceof LivingEntity livingEntity) {
                    saveStatueToConfig(livingEntity, statuesSection.createSection(statueName));
                }
            }

            try {
                playerDataConfig.save(playerDataFile);
            } catch (IOException e) {
                getLogger().warning("Failed to save player data for player " + playerId.toString());
                e.printStackTrace();
            }
        }
    }

    private void saveStatueToConfig(LivingEntity entity, ConfigurationSection statueSection) {
        statueSection.set("world", entity.getWorld().getName());
        statueSection.set("x", entity.getLocation().getX());
        statueSection.set("y", entity.getLocation().getY());
        statueSection.set("z", entity.getLocation().getZ());
        statueSection.set("yaw", entity.getLocation().getYaw());
        statueSection.set("pitch", entity.getLocation().getPitch());
        statueSection.set("entityType", entity.getType().name());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("ms")) {
            if (args.length >= 2) {
                StringBuilder statueNameBuilder = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if (i > 1) {
                        statueNameBuilder.append(" ");
                    }
                    statueNameBuilder.append(args[i]);
                }
                String statueName = statueNameBuilder.toString();

                if (args[args.length - 1].isEmpty()) {
                    for (EntityType entityType : EntityType.values()) {
                        if (entityType.isAlive() && entityType.name().toLowerCase().startsWith(statueName)) {
                            completions.add(entityType.name().toLowerCase());
                        }
                    }
                } else {
                    String partialName = args[args.length - 1].toLowerCase();
                    for (EntityType entityType : EntityType.values()) {
                        if (entityType.isAlive() && entityType.name().toLowerCase().startsWith(partialName)) {
                            completions.add(entityType.name().toLowerCase());
                        }
                    }
                }
            }
        } else if (command.getName().equalsIgnoreCase("msdel")) {
            if (args.length == 0 || (args.length == 1 && args[0].isEmpty())) {
                if (sender instanceof Player player) {
                    UUID playerId = player.getUniqueId();
                    Map<String, Entity> playerStatues = playerStatueMap.get(playerId);
                    if (playerStatues != null) {
                        completions.addAll(playerStatues.keySet());
                    }
                }
            } else if (args.length == 1) {
                String partialName = args[0].toLowerCase();
                Player player = (Player) sender;
                UUID playerId = player.getUniqueId();
                Map<String, Entity> playerStatues = playerStatueMap.get(playerId);
                if (playerStatues != null) {
                    for (String statueName : playerStatues.keySet()) {
                        if (statueName.startsWith(partialName)) {
                            completions.add(statueName);
                        }
                    }
                }
            }
        } else if (command.getName().equalsIgnoreCase("msmove")) {
            if (args.length == 1) {
                String partialName = args[0].toLowerCase();
                Player player = (Player) sender;
                UUID playerId = player.getUniqueId();
                Map<String, Entity> playerStatues = playerStatueMap.get(playerId);
                if (playerStatues != null) {
                    for (String statueName : playerStatues.keySet()) {
                        if (statueName.startsWith(partialName)) {
                            completions.add(statueName);
                        }
                    }
                }
            }
        }

        return completions;
    }
}