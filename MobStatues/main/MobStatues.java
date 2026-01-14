package main;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class MobStatues extends JavaPlugin implements Listener, TabCompleter {
	private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<String, Entity>>> playerStatueMap = new Long2ObjectOpenHashMap<>();
	private final Object2ObjectOpenHashMap<String, StatueInfo> statueLookupMap = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectOpenHashMap<String, Location> statueHomeLocationMap = new Object2ObjectOpenHashMap<>();
	private boolean preventItemDrops = false;
	private static final char[] CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
	private static final ThreadLocal<StringBuilder> NAME_BUILDER = ThreadLocal.withInitial(() -> new StringBuilder(16));
	private NamespacedKey STATUE_ID_KEY;

	@Override
	public void onEnable() {
		STATUE_ID_KEY = new NamespacedKey(this, "statue_id");
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

	private static long getMsb(UUID uuid) {
		return uuid.getMostSignificantBits();
	}

	private static long getLsb(UUID uuid) {
		return uuid.getLeastSignificantBits();
	}

	private Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<String, Entity>> getOrCreateSecondMap(long msb) {
		return playerStatueMap.computeIfAbsent(msb, k -> new Long2ObjectOpenHashMap<>());
	}

	private Object2ObjectOpenHashMap<String, Entity> getOrCreatePlayerStatues(UUID playerId) {
		long msb = getMsb(playerId);
		long lsb = getLsb(playerId);
		Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<String, Entity>> secondMap = getOrCreateSecondMap(msb);
		return secondMap.computeIfAbsent(lsb, k -> new Object2ObjectOpenHashMap<>());
	}

	private Object2ObjectOpenHashMap<String, Entity> getPlayerStatues(UUID playerId) {
		long msb = getMsb(playerId);
		long lsb = getLsb(playerId);
		Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<String, Entity>> secondMap = playerStatueMap.get(msb);
		if (secondMap == null) return null;
		return secondMap.get(lsb);
	}

	private boolean isBaitStatueType(EntityType type) {
		return type == EntityType.ENDERMITE
				|| type == EntityType.IRON_GOLEM
				|| type == EntityType.SNOW_GOLEM
				|| type == EntityType.VILLAGER;
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
				removeStatueCommand(sender, statueName);
				return true;
			} else {
				sender.sendMessage("Usage: /msdel [statue name]");
				return true;
			}
		} else if (command.getName().equalsIgnoreCase("msadjust")) {
			if (args.length == 3) {
				if (sender instanceof Player player) {
					String statueName = args[0].toLowerCase();
					double yaw;
					double pitch;
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
		Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(player.getUniqueId());
		if (playerStatues != null) {
			Entity statue = playerStatues.get(statueName);
			if (statue instanceof LivingEntity) {
				String statueId = getStatueId(statue);
				removeStatue(player.getUniqueId(), statueName);

				Location entityLocation = statue.getLocation();
				removeOldEntity(statue);

				LivingEntity newEntity = (LivingEntity) entityLocation.getWorld().spawnEntity(entityLocation, statue.getType());
				setupStatueEntity(newEntity, statueId);

				newEntity.teleport(new Location(
						newEntity.getWorld(),
						entityLocation.getX(),
						entityLocation.getY(),
						entityLocation.getZ(),
						(float) yaw,
						(float) pitch
						));
				statueHomeLocationMap.put(statueId, newEntity.getLocation().clone());

				getOrCreatePlayerStatues(player.getUniqueId()).put(statueName, newEntity);
				statueLookupMap.put(statueId, new StatueInfo(player.getUniqueId(), statueName));

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
								getLogger().warning("Failed to save player data for player " + player.getUniqueId());
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
			for (Entity passenger : entity.getPassengers()) {
				passenger.remove();
			}
			entity.remove();
		}
	}

	private void createStatue(Player player, String statueName, String entityName) {
		removeStatue(player.getUniqueId(), statueName);
		EntityType entityType;
		try {
			entityType = EntityType.valueOf(entityName);
		} catch (IllegalArgumentException e) {
			player.sendMessage("Invalid entity name.");
			return;
		}

		if (!entityType.isAlive()) {
			player.sendMessage("Invalid entity name.");
			return;
		}

		Location location = player.getLocation();
		LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, entityType);
		String statueId = generateRandomName();
		setupStatueEntity(entity, statueId);

		getOrCreatePlayerStatues(player.getUniqueId()).put(statueName, entity);
		statueLookupMap.put(statueId, new StatueInfo(player.getUniqueId(), statueName));

		player.sendMessage("Statue '" + statueName + "' created successfully.");
		savePlayerStatuesData();
	}

	private void setupStatueEntity(LivingEntity entity, String statueId) {
		boolean bait = isBaitStatueType(entity.getType());

		entity.setPersistent(true);

		if (entity instanceof Mob mob) {
			mob.setRemoveWhenFarAway(false);
		}

		entity.setAI(false);
		entity.setCollidable(false);
		entity.setGravity(false);
		entity.setSilent(true);
		entity.setCustomNameVisible(false);

		if (bait) {
			entity.setInvulnerable(false);

			if (entity.getCustomName() == null || entity.getCustomName().isEmpty()) {
				entity.setCustomName("statue_" + statueId);
			}
		} else {
			entity.setInvulnerable(true);
			entity.setCustomName(null);
		}

		entity.getPersistentDataContainer().set(STATUE_ID_KEY, PersistentDataType.STRING, statueId);
		statueHomeLocationMap.put(statueId, entity.getLocation().clone());

		ArmorStand armorStand = entity.getWorld().spawn(entity.getLocation(), ArmorStand.class);
		armorStand.setInvisible(true);
		armorStand.setMarker(true);
		armorStand.setSilent(true);
		armorStand.setGravity(false);
		armorStand.setInvulnerable(true);
		armorStand.setPersistent(true);
		armorStand.setCollidable(false);
		entity.addPassenger(armorStand);
	}

	private String getStatueId(Entity entity) {
		if (entity == null) return null;
		PersistentDataContainer pdc = entity.getPersistentDataContainer();
		if (pdc.has(STATUE_ID_KEY, PersistentDataType.STRING)) {
			return pdc.get(STATUE_ID_KEY, PersistentDataType.STRING);
		}
		return null;
	}

	private void removeOldStatue(Entity statue) {
		if (statue != null) {
			preventItemDrops = true;
			for (Entity passenger : statue.getPassengers()) {
				passenger.remove();
			}
			statue.remove();
			preventItemDrops = false;
		}
	}

	@EventHandler
	public void onItemSpawn(ItemSpawnEvent event) {
		if (preventItemDrops) {
			event.setCancelled(true);
		}
	}

	private Entity unwrapDamager(EntityDamageByEntityEvent event) {
		Entity damager = event.getDamager();
		if (damager instanceof Projectile projectile) {
			Object shooter = projectile.getShooter();
			if (shooter instanceof Entity shooterEntity) {
				return shooterEntity;
			}
		}
		return damager;
	}

	@EventHandler(ignoreCancelled = true)
	public void onEntityDamage(EntityDamageEvent event) {
		Entity victim = event.getEntity();
		String statueId = getStatueId(victim);
		if (statueId == null) return;

		boolean bait = isBaitStatueType(victim.getType());

		if (!bait) {
			event.setCancelled(true);
			return;
		}

		if (event instanceof EntityDamageByEntityEvent byEntity) {
			Entity realDamager = unwrapDamager(byEntity);

			if (realDamager instanceof Player) {
				event.setCancelled(true);
				return;
			}

			event.setDamage(0.0);

			Location home = statueHomeLocationMap.get(statueId);
			if (home != null) {
				Bukkit.getScheduler().runTask(this, () -> {
					if (victim.isValid()) {
						victim.teleport(home);
						victim.setVelocity(new Vector(0, 0, 0));
					}
				});
			}
			return;
		}

		event.setCancelled(true);
	}

	@EventHandler
	public void onEntityDeath(EntityDeathEvent event) {
		Entity entity = event.getEntity();
		String statueId = getStatueId(entity);
		if (statueId != null && statueLookupMap.containsKey(statueId)) {
			StatueInfo info = statueLookupMap.get(statueId);
			UUID playerId = info.playerId();
			String statueName = info.statueName();
			removeStatueFromMemory(playerId, statueName);
			removeStatueFromConfig(playerId, statueName);
			statueLookupMap.remove(statueId);
			statueHomeLocationMap.remove(statueId);
		}
	}

	private void removeStatueFromMemory(UUID playerId, String statueName) {
		Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(playerId);
		if (playerStatues != null) {
			Entity statue = playerStatues.remove(statueName);
			if (statue != null) {
				String statueId = getStatueId(statue);
				if (statueId != null) {
					statueLookupMap.remove(statueId);
					statueHomeLocationMap.remove(statueId);
				}
			}
		}
	}

	private void removeStatueFromConfig(UUID playerId, String statueName) {
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
					getLogger().warning("Failed to save player data for player " + playerId);
					e.printStackTrace();
				}
			}
		}
	}

	private boolean moveStatue(Player player, String statueName) {
		Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(player.getUniqueId());
		if (playerStatues != null) {
			Entity statue = playerStatues.get(statueName);
			if (statue instanceof LivingEntity livingEntity) {
				String oldStatueId = getStatueId(livingEntity);
				Location location = player.getLocation();
				removeOldStatue(livingEntity);

				createStatue(player, statueName, livingEntity.getType().name());

				Entity newStatue = getPlayerStatues(player.getUniqueId()).get(statueName);
				if (newStatue instanceof LivingEntity newLivingEntity) {
					newLivingEntity.teleport(location);

					String newId = getStatueId(newLivingEntity);
					if (newId != null) {
						statueLookupMap.remove(newId);
						statueHomeLocationMap.remove(newId);
					}

					if (oldStatueId != null) {
						newLivingEntity.getPersistentDataContainer().set(STATUE_ID_KEY, PersistentDataType.STRING, oldStatueId);
						statueLookupMap.put(oldStatueId, new StatueInfo(player.getUniqueId(), statueName));
						statueHomeLocationMap.put(oldStatueId, newLivingEntity.getLocation().clone());
					}
					return true;
				}
			}
		}
		return false;
	}

	private void removeAllStatues() {
		for (Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<String, Entity>> secondMap : playerStatueMap.values()) {
			for (Object2ObjectOpenHashMap<String, Entity> playerStatues : secondMap.values()) {
				for (Entity statue : playerStatues.values()) {
					if (statue != null) {
						String statueId = getStatueId(statue);
						if (statueId != null) {
							statueLookupMap.remove(statueId);
							statueHomeLocationMap.remove(statueId);
						}
						for (Entity passenger : statue.getPassengers()) {
							passenger.remove();
						}
						statue.remove();
					}
				}
				playerStatues.clear();
			}
			secondMap.clear();
		}
		playerStatueMap.clear();
		statueHomeLocationMap.clear();
	}

	private void removeStatue(UUID playerId, String statueName) {
		Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(playerId);
		if (playerStatues != null) {
			Entity statue = playerStatues.remove(statueName);
			if (statue != null) {
				String statueId = getStatueId(statue);
				if (statueId != null) {
					statueLookupMap.remove(statueId);
					statueHomeLocationMap.remove(statueId);
				}
				removeOldStatue(statue);
			}
		}
	}

	private void removeStatueCommand(CommandSender sender, String statueName) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("This command can only be used by players.");
			return;
		}
		UUID playerId = player.getUniqueId();
		Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(playerId);
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
		String statueId = getStatueId(statue);
		if (statueId != null) {
			statueLookupMap.remove(statueId);
			statueHomeLocationMap.remove(statueId);
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
					getLogger().warning("Failed to save player data for player " + playerId);
					e.printStackTrace();
				}
			}
		}
		sender.sendMessage("Statue '" + statueName + "' removed.");
	}

	private void listPlayerStatues(UUID playerId) {
		Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(playerId);
		Player player = Bukkit.getPlayer(playerId);

		if (player == null) return;

		if (playerStatues != null && !playerStatues.isEmpty()) {
			player.sendMessage("Your statues:");
			for (String name : playerStatues.keySet()) {
				player.sendMessage("- " + name);
			}
		} else {
			player.sendMessage("You don't have any statues.");
		}
	}

	private void loadPlayerStatuesData() {
		File playersDataFolder = new File(getDataFolder(), "players");
		if (!playersDataFolder.exists()) {
			playersDataFolder.mkdirs();
		}
		File[] playerDataFiles = playersDataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
		if (playerDataFiles != null) {
			for (File playerDataFile : playerDataFiles) {
				String playerName = playerDataFile.getName().replace(".yml", "");
				UUID playerId;
				try {
					playerId = UUID.fromString(playerName);
				} catch (IllegalArgumentException e) {
					continue;
				}
				FileConfiguration playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
				ConfigurationSection statuesSection = playerDataConfig.getConfigurationSection("statues");
				if (statuesSection != null) {
					Object2ObjectOpenHashMap<String, Entity> playerStatues = new Object2ObjectOpenHashMap<>();
					for (String statueName : statuesSection.getKeys(false)) {
						Entity statue = loadStatueFromConfig(playerId, statueName, statuesSection.getConfigurationSection(statueName));
						if (statue != null) {
							playerStatues.put(statueName, statue);
							String statueId = getStatueId(statue);
							if (statueId != null) {
								statueLookupMap.put(statueId, new StatueInfo(playerId, statueName));
							}
						}
					}
					if (updateStatueData(playerDataConfig, playerStatues)) {
						File specificPlayerDataFile = new File(playersDataFolder, playerName + ".yml");
						savePlayerData(specificPlayerDataFile, playerDataConfig);
					}
					if (!playerStatues.isEmpty()) {
						long msb = getMsb(playerId);
						long lsb = getLsb(playerId);
						Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<String, Entity>> secondMap = getOrCreateSecondMap(msb);
						secondMap.put(lsb, playerStatues);
					}
				}
			}
		}
	}

	private boolean updateStatueData(FileConfiguration playerDataConfig, Object2ObjectOpenHashMap<String, Entity> playerStatues) {
		boolean hasUpdates = false;
		for (String statueName : playerStatues.keySet()) {
			ConfigurationSection statueSection = playerDataConfig.getConfigurationSection("statues." + statueName);
			if (statueSection != null && !statueSection.contains("entityName")) {
				String randomName = generateRandomName();
				statueSection.set("entityName", randomName);
				hasUpdates = true;
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
		if (statueSection == null) {
			return null;
		}

		String worldName = statueSection.getString("world");
		double x = statueSection.getDouble("x");
		double y = statueSection.getDouble("y");
		double z = statueSection.getDouble("z");
		float yaw = (float) statueSection.getDouble("yaw");
		float pitch = (float) statueSection.getDouble("pitch");

		if (Bukkit.getWorld(worldName) == null) {
			return null;
		}
		Location location = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);

		EntityType entityType;
		try {
			entityType = EntityType.valueOf(statueSection.getString("entityType"));
		} catch (IllegalArgumentException e) {
			getLogger().warning("Invalid entity type for statue '" + statueName + "'.");
			return null;
		}
		if (!entityType.isAlive()) {
			getLogger().warning("Invalid entity type for statue '" + statueName + "'.");
			return null;
		}

		String customNameKey = "entityName";
		String statueId = statueSection.getString(customNameKey);
		if (statueId == null || statueId.isEmpty()) {
			statueId = generateRandomName();
			statueSection.set(customNameKey, statueId);
			File playersDataFolder = new File(getDataFolder(), "players");
			File playerDataFile = new File(playersDataFolder, playerId + ".yml");
			if (playerDataFile.exists()) {
				FileConfiguration playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
				try {
					playerDataConfig.set("statues." + statueName + "." + customNameKey, statueId);
					playerDataConfig.save(playerDataFile);
				} catch (IOException e) {
					getLogger().warning("Failed to save player data for player " + playerId);
					e.printStackTrace();
				}
			}
		}

		LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, entityType);
		setupStatueEntity(entity, statueId);
		entity.teleport(location);
		statueHomeLocationMap.put(statueId, entity.getLocation().clone());

		return entity;
	}

	private void savePlayerStatuesData() {
		File playersDataFolder = new File(getDataFolder(), "players");
		if (!playersDataFolder.exists()) {
			playersDataFolder.mkdirs();
		}

		for (long msb : playerStatueMap.keySet()) {
			Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<String, Entity>> secondMap = playerStatueMap.get(msb);
			for (long lsb : secondMap.keySet()) {
				Object2ObjectOpenHashMap<String, Entity> playerStatues = secondMap.get(lsb);
				if (playerStatues == null) continue;

				UUID playerId = new UUID(msb, lsb);
				File playerDataFile = new File(playersDataFolder, playerId.toString() + ".yml");
				FileConfiguration playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
				ConfigurationSection statuesSection = playerDataConfig.createSection("statues");

				for (String name : playerStatues.keySet()) {
					Entity statue = playerStatues.get(name);
					if (statue instanceof LivingEntity livingEntity) {
						saveStatueToConfig(livingEntity, statuesSection.createSection(name));
					}
				}
				try {
					playerDataConfig.save(playerDataFile);
				} catch (IOException e) {
					getLogger().warning("Failed to save player data for player " + playerId);
					e.printStackTrace();
				}
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

		String statueId = getStatueId(entity);
		if (statueId != null) {
			statueSection.set("entityName", statueId);
		}
	}

	private String generateRandomName() {
		StringBuilder sb = NAME_BUILDER.get();
		sb.setLength(0);
		for (int i = 0; i < 16; i++) {
			int index = ThreadLocalRandom.current().nextInt(CHARACTERS.length);
			sb.append(CHARACTERS[index]);
		}
		return sb.toString();
	}

	@Override
	public ObjectList<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		ObjectList<String> completions = new ObjectArrayList<>();
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
					Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(player.getUniqueId());
					if (playerStatues != null) {
						completions.addAll(playerStatues.keySet());
					}
				}
			} else if (args.length == 1) {
				String partialName = args[0].toLowerCase();
				if (sender instanceof Player player) {
					Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(player.getUniqueId());
					if (playerStatues != null) {
						for (String statueName : playerStatues.keySet()) {
							if (statueName.startsWith(partialName)) {
								completions.add(statueName);
							}
						}
					}
				}
			}
		} else if (command.getName().equalsIgnoreCase("msmove")) {
			if (args.length == 1) {
				String partialName = args[0].toLowerCase();
				if (sender instanceof Player player) {
					Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(player.getUniqueId());
					if (playerStatues != null) {
						for (String statueName : playerStatues.keySet()) {
							if (statueName.startsWith(partialName)) {
								completions.add(statueName);
							}
						}
					}
				}
			}
		} else if (command.getName().equalsIgnoreCase("msadjust")) {
			if (args.length == 1) {
				if (sender instanceof Player player) {
					Object2ObjectOpenHashMap<String, Entity> playerStatues = getPlayerStatues(player.getUniqueId());
					if (playerStatues != null) {
						completions.addAll(playerStatues.keySet());
					}
				}
			}
		}
		return completions;
	}

	private static final class StatueInfo {
		private final UUID playerId;
		private final String statueName;

		StatueInfo(UUID playerId, String statueName) {
			this.playerId = playerId;
			this.statueName = statueName;
		}

		public UUID playerId() {
			return playerId;
		}

		public String statueName() {
			return statueName;
		}
	}
}
