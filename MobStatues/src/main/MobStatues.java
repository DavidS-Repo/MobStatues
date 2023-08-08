package main;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;


public class MobStatues extends JavaPlugin implements Listener, TabCompleter {

	private final Map<UUID, Map<String, Entity>> playerStatueMap = new HashMap<>();
	private boolean preventItemDrops = false;

	@Override
	public void onEnable() {
		getServer().getPluginManager().registerEvents(this, this);
		getLogger().info("MobStatues has been enabled!");
		getCommand("ms").setTabCompleter(this);
		getCommand("msmove").setTabCompleter(this);
		getCommand("msdel").setTabCompleter(this);
		getCommand("msadjust").setTabCompleter(this);
		loadPlayerStatuesData();
	}

	@Override
	public void onDisable() {
		getLogger().info("MobStatues has been disabled!");
		removeAllStatues();
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
		} else if (command.getName().equalsIgnoreCase("msadjust")) {
			if (args.length == 3) {
				if (sender instanceof Player player) {
					String statueName = args[0].toLowerCase();
					double yaw, pitch;
					try {
						yaw = Double.parseDouble(args[1]);
						pitch = Double.parseDouble(args[2]);
					} catch (NumberFormatException e) {
						player.sendMessage("Invalid yaw or pitch value. Please provide valid numbers.");
						return true;
					}
					adjustStatue(player, statueName, yaw, pitch);
				} else {
					sender.sendMessage("This command can only be used by players.");
				}
				return true;
			} else {
				sender.sendMessage("Usage: /msadjust <name> <rotation> <pitch>");
				return true;
			}
		}
		return false;
	}

	private void adjustStatue(Player player, String statueName, double yaw, double pitch) {
		Map<String, Entity> playerStatues = playerStatueMap.get(player.getUniqueId());
		if (playerStatues != null) {
			Entity statue = playerStatues.get(statueName);
			if (statue instanceof LivingEntity) {
				// Remove the existing statue using the removeStatue method
				removeStatue(player.getUniqueId(), statueName);

				Location entityLocation = statue.getLocation();

				// Remove the old entity and its passengers
				removeOldEntity(statue);

				// Spawn a new entity with the updated pitch and yaw
				LivingEntity newEntity = (LivingEntity) entityLocation.getWorld().spawnEntity(entityLocation, statue.getType());
				newEntity.setAI(false);
				newEntity.setCollidable(false);
				newEntity.setGravity(false);
				newEntity.setInvulnerable(true);
				newEntity.setSilent(true);

				// Set the new pitch and yaw
				newEntity.teleport(new Location(newEntity.getWorld(), entityLocation.getX(), entityLocation.getY(), entityLocation.getZ(), (float) yaw, (float) pitch));

				// Set the custom name of the new entity to match the old entity's custom name
				newEntity.setCustomName(statue.getCustomName());
				newEntity.setCustomNameVisible(true);

				// Create an invisible armor stand as a passenger to the new entity
				ArmorStand newArmorStand = entityLocation.getWorld().spawn(entityLocation, ArmorStand.class);
				newArmorStand.setInvisible(true);
				newArmorStand.setMarker(true);
				newEntity.addPassenger(newArmorStand);

				// Update the entity in the map
				playerStatues.put(statueName, newEntity);

				// Update the pitch and yaw in the configuration file
				File playersDataFolder = new File(getDataFolder(), "players");
				File playerDataFile = new File(playersDataFolder, player.getUniqueId().toString() + ".yml");
				if (playerDataFile.exists()) {
					FileConfiguration playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
					ConfigurationSection statuesSection = playerDataConfig.getConfigurationSection("statues");
					if (statuesSection != null) {
						ConfigurationSection statueData = statuesSection.getConfigurationSection(statueName);
						if (statueData != null) {
							statueData.set("yaw", yaw);
							statueData.set("pitch", pitch);
							try {
								playerDataConfig.save(playerDataFile);
							} catch (IOException e) {
								getLogger().warning("Failed to save player data for player " + player.getUniqueId().toString());
								e.printStackTrace();
							}

							player.sendMessage("Statue '" + statueName + "' yaw and pitch adjusted successfully.");
							return;
						}
					}
				}

				player.sendMessage("Statue data not found for '" + statueName + "'.");
			} else {
				player.sendMessage("You don't have a statue named '" + statueName + "'.");
			}
		} else {
			player.sendMessage("You don't have any statues.");
		}
	}

	private void removeOldEntity(Entity entity) {
		if (entity != null) {
			// Remove the entity and its passengers
			for (Entity passenger : entity.getPassengers()) {
				passenger.remove();
			}
			entity.remove();
		}
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

		// Generate and set the custom name for the entity
		String customName = generateRandomName();
		entity.setCustomName(customName);
		entity.setCustomNameVisible(false); // Hide the custom name by default

		// Create an invisible armor stand as a passenger to the entity
		ArmorStand armorStand = location.getWorld().spawn(location, ArmorStand.class);
		armorStand.setInvisible(true);
		armorStand.setMarker(true);
		entity.addPassenger(armorStand);

		// Store the statue in the map
		storeStatue(player.getUniqueId(), statueName, entity);

		player.sendMessage("Statue '" + statueName + "' created successfully.");

		// Save the player's statues data to file
		savePlayerStatuesData();
	}



	private void removeOldStatue(Entity statue) {
		if (statue != null) {
			// Set the flag to prevent item drops
			preventItemDrops = true;

			// Remove the statue using kill command
			String customName = statue.getCustomName();
			String killCommand = "minecraft:kill @e[name=" + customName + "]";
			Bukkit.dispatchCommand(Bukkit.getConsoleSender(), killCommand);

			// Reset the flag to allow item drops
			preventItemDrops = false;
		}
	}

	@EventHandler
	public void onItemSpawn(ItemSpawnEvent event) {
		// Prevent the dropped item from spawning if the flag is true
		if (preventItemDrops) {
			event.setCancelled(true);
		}
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

					// Set the custom name of the new entity to match the old entity's custom name
					newLivingEntity.setCustomName(livingEntity.getCustomName());
					newLivingEntity.setCustomNameVisible(true);

					return true;
				}
			}
		}
		return false;
	}

	private void removeAllStatues() {
		for (Map<String, Entity> playerStatues : playerStatueMap.values()) {
			for (Entity statue : playerStatues.values()) {
				statue.remove();
			}
		}
		playerStatueMap.clear();
	}

	private void storeStatue(UUID playerId, String statueName, Entity entity) {
		playerStatueMap.computeIfAbsent(playerId, k -> new HashMap<>()).put(statueName, entity);
	}

	private void removeStatue(UUID playerId, String statueName) {
		Map<String, Entity> playerStatues = playerStatueMap.get(playerId);
		if (playerStatues != null) {
			Entity statue = playerStatues.remove(statueName);
			if (statue != null) {
				removeOldStatue(statue);
			}
		}
	}

	private void removeStatue(CommandSender sender, String statueName) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("This command can only be used by players.");
			return;
		}

		UUID playerId = player.getUniqueId();
		Map<String, Entity> playerStatues = playerStatueMap.get(playerId);

		if (playerStatues == null) {
			sender.sendMessage("You don't have any statues.");
			return;
		}

		Entity statue = playerStatues.remove(statueName);
		if (statue == null) {
			sender.sendMessage("You don't have a statue named '" + statueName + "'.");
			return;
		}

		Location statueLocation = statue.getLocation();
		Chunk chunk = statueLocation.getChunk();

		if (!chunk.isLoaded()) {
			sender.sendMessage("Warning: The chunk containing the statue is not loaded. The statue will not be removed.");
			return;
		}

		removeOldStatue(statue);

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

					// Check if the entityName field is missing and generate a random name if needed
					if (updateStatueData(playerDataConfig, playerStatues)) {
						File specificPlayerDataFile = new File(playersDataFolder, playerName + ".yml");
						savePlayerData(specificPlayerDataFile, playerDataConfig);
					}

					playerStatueMap.put(playerId, playerStatues);
				}
			}
		}
	}

	private boolean updateStatueData(FileConfiguration playerDataConfig, Map<String, Entity> playerStatues) {
		boolean hasUpdates = false;
		for (String statueName : playerStatues.keySet()) {
			ConfigurationSection statueSection = playerDataConfig.getConfigurationSection("statues." + statueName);
			if (statueSection != null && !statueSection.contains("entityName")) {
				// Generate a random name (16 characters long) for the statue and save it in the config
				String randomName = generateRandomName();
				statueSection.set("entityName", randomName);
				hasUpdates = true;

				// Add debugging output to check the process
				getLogger().info("Added random name '" + randomName + "' for statue: " + statueName);
			}
		}
		return hasUpdates;
	}

	private void savePlayerData(File playerDataFile, FileConfiguration playerDataConfig) {
		try {
			playerDataConfig.save(playerDataFile);
		} catch (IOException e) {
			getLogger().warning("Failed to save player data for file: " + playerDataFile.getName());
			e.printStackTrace();
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

			// Load and set the custom name from the file
			String customNameKey = "entityName";
			String customName = statueSection.getString(customNameKey);
			if (customName == null || customName.isEmpty()) {
				customName = generateRandomName();
				statueSection.set(customNameKey, customName);

				// Save the changes back to the specific player's data file
				File playersDataFolder = new File(getDataFolder(), "players");
				File playerDataFile = new File(playersDataFolder, playerId + ".yml");
				if (playerDataFile.exists()) {
					FileConfiguration playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
					try {
						playerDataConfig.set("statues." + statueName + "." + customNameKey, customName);
						playerDataConfig.save(playerDataFile);
					} catch (IOException e) {
						getLogger().warning("Failed to save player data for player " + playerId.toString());
						e.printStackTrace();
					}
				}
			}

			entity.setCustomName(customName);
			entity.setCustomNameVisible(true);

			// Create an invisible armor stand as a passenger to the entity
			ArmorStand armorStand = location.getWorld().spawn(location, ArmorStand.class);
			armorStand.setInvisible(true);
			armorStand.setMarker(true);
			entity.addPassenger(armorStand);

			return entity;
		}

		return null;
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

		// Save the custom name to the file
		if (entity.getCustomName() != null) {
			statueSection.set("entityName", entity.getCustomName());
		}
	}

	private String generateRandomName() {
		String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		Random random = new Random();
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 16; i++) {
			int index = random.nextInt(characters.length());
			builder.append(characters.charAt(index));
		}
		return builder.toString();
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
		} else if (command.getName().equalsIgnoreCase("msadjust")) {
			if (args.length == 1) {
				if (sender instanceof Player player) {
					UUID playerId = player.getUniqueId();
					Map<String, Entity> playerStatues = playerStatueMap.get(playerId);
					if (playerStatues != null) {
						completions.addAll(playerStatues.keySet());
					}
				}
			} else if (args.length == 2 || args.length == 3) {
				// No specific completions for yaw and pitch, allowing any number values
			}
		}

		return completions;
	}
}

