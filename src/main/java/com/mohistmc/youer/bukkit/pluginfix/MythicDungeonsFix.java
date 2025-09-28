package com.mythicdungeons.fix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MythicDungeonsFix extends JavaPlugin implements Listener {
    
    private final Map<UUID, String> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<String, Location> dungeonSpawns = new ConcurrentHashMap<>();
    
    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MythicDungeonsFix enabled - Fixing procedural dungeon teleports");
        
        // Check for MythicDungeons
        if (Bukkit.getPluginManager().getPlugin("MythicDungeons") == null) {
            getLogger().warning("MythicDungeons not found! This fix requires MythicDungeons.");
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().toLowerCase();
        
        // Intercept /md play command
        if (cmd.startsWith("/md play ") || cmd.startsWith("/mythicdungeons:md play ")) {
            Player player = event.getPlayer();
            String[] parts = cmd.split(" ");
            
            if (parts.length >= 3) {
                String dungeonName = parts[2];
                pendingTeleports.put(player.getUniqueId(), dungeonName);
                
                // Schedule teleport check
                new BukkitRunnable() {
                    int attempts = 0;
                    
                    @Override
                    public void run() {
                        attempts++;
                        
                        // Check if player changed world (dungeon loaded)
                        World currentWorld = player.getWorld();
                        String worldName = currentWorld.getName();
                        
                        if (worldName.toLowerCase().contains(dungeonName.toLowerCase())) {
                            // Try to find spawn location
                            Location spawn = findDungeonSpawn(player, dungeonName, currentWorld);
                            
                            if (spawn != null) {
                                // Teleport player
                                player.teleport(spawn);
                                getLogger().info("Teleported " + player.getName() + " to dungeon spawn: " + spawn);
                                pendingTeleports.remove(player.getUniqueId());
                                this.cancel();
                            } else if (attempts >= 20) { // 10 seconds timeout
                                // Fallback: teleport to world spawn
                                Location worldSpawn = currentWorld.getSpawnLocation();
                                player.teleport(worldSpawn);
                                getLogger().warning("Could not find dungeon spawn, using world spawn for " + player.getName());
                                pendingTeleports.remove(player.getUniqueId());
                                this.cancel();
                            }
                        } else if (attempts >= 40) { // 20 seconds timeout
                            getLogger().warning("Timeout waiting for dungeon world for " + player.getName());
                            pendingTeleports.remove(player.getUniqueId());
                            this.cancel();
                        }
                    }
                }.runTaskTimer(this, 10L, 10L); // Check every 0.5 seconds
            }
        }
    }
    
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        String dungeonName = pendingTeleports.get(player.getUniqueId());
        
        if (dungeonName != null) {
            World newWorld = player.getWorld();
            String worldName = newWorld.getName();
            
            // Check if this is the dungeon world
            if (worldName.toLowerCase().contains(dungeonName.toLowerCase())) {
                // Schedule teleport for next tick
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Location spawn = findDungeonSpawn(player, dungeonName, newWorld);
                        if (spawn != null) {
                            player.teleport(spawn);
                            getLogger().info("Teleported " + player.getName() + " to dungeon spawn on world change: " + spawn);
                        } else {
                            // Try a few more times
                            scheduleDelayedTeleport(player, dungeonName, newWorld, 5);
                        }
                        pendingTeleports.remove(player.getUniqueId());
                    }
                }.runTaskLater(this, 1L);
            }
        }
    }
    
    private void scheduleDelayedTeleport(Player player, String dungeonName, World world, int attempts) {
        if (attempts <= 0) {
            Location worldSpawn = world.getSpawnLocation();
            player.teleport(worldSpawn);
            getLogger().warning("Using world spawn as fallback for " + player.getName());
            return;
        }
        
        new BukkitRunnable() {
            @Override
            public void run() {
                Location spawn = findDungeonSpawn(player, dungeonName, world);
                if (spawn != null) {
                    player.teleport(spawn);
                    getLogger().info("Successfully teleported " + player.getName() + " after delay");
                } else {
                    scheduleDelayedTeleport(player, dungeonName, world, attempts - 1);
                }
            }
        }.runTaskLater(this, 20L); // Wait 1 second
    }
    
    private Location findDungeonSpawn(Player player, String dungeonName, World world) {
        // Check cache first
        String cacheKey = world.getName() + "_" + dungeonName;
        Location cached = dungeonSpawns.get(cacheKey);
        if (cached != null) {
            return cached.clone();
        }
        
        try {
            // Try to get spawn from MythicDungeons API via reflection
            Object mdPlugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
            if (mdPlugin != null) {
                // Try to get the dungeon instance
                Method getDungeonMethod = mdPlugin.getClass().getMethod("getDungeon", String.class);
                Object dungeon = getDungeonMethod.invoke(mdPlugin, dungeonName);
                
                if (dungeon != null) {
                    // Try to get spawn location from dungeon
                    Location spawn = tryGetSpawnFromDungeon(dungeon, world);
                    if (spawn != null) {
                        dungeonSpawns.put(cacheKey, spawn);
                        return spawn;
                    }
                }
                
                // Alternative: Try to get from active instances
                spawn = tryGetSpawnFromActiveInstance(mdPlugin, player, world);
                if (spawn != null) {
                    dungeonSpawns.put(cacheKey, spawn);
                    return spawn;
                }
            }
        } catch (Exception e) {
            getLogger().warning("Error finding dungeon spawn via reflection: " + e.getMessage());
        }
        
        // Fallback: Look for specific coordinates
        // Procedural dungeons often spawn at specific coordinates
        Location[] commonSpawns = {
            new Location(world, 0.5, 65, 0.5), // Common spawn point
            new Location(world, 8.5, 65, 8.5), // Alternative spawn
            new Location(world, 0.5, 100, 0.5), // High spawn
            world.getSpawnLocation() // World spawn as last resort
        };
        
        for (Location loc : commonSpawns) {
            if (isSafeLocation(loc)) {
                dungeonSpawns.put(cacheKey, loc);
                return loc;
            }
        }
        
        return null;
    }
    
    private Location tryGetSpawnFromDungeon(Object dungeon, World world) {
        try {
            // Try different methods to get spawn
            String[] methodNames = {"getSpawnLocation", "getSpawn", "getStartLocation", "getEntryPoint"};
            
            for (String methodName : methodNames) {
                try {
                    Method method = dungeon.getClass().getMethod(methodName);
                    Object result = method.invoke(dungeon);
                    
                    if (result instanceof Location) {
                        Location loc = (Location) result;
                        // Update world if needed
                        if (loc.getWorld() == null || !loc.getWorld().equals(world)) {
                            loc = new Location(world, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
                        }
                        return loc;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try next method
                }
            }
            
            // Try to get from layout
            try {
                Method getLayoutMethod = dungeon.getClass().getMethod("getLayout");
                Object layout = getLayoutMethod.invoke(dungeon);
                
                if (layout != null) {
                    Method getRoomsMethod = layout.getClass().getMethod("getRooms");
                    Object rooms = getRoomsMethod.invoke(layout);
                    
                    if (rooms instanceof java.util.List && !((java.util.List<?>) rooms).isEmpty()) {
                        Object firstRoom = ((java.util.List<?>) rooms).get(0);
                        
                        // Get room location
                        Method getLocationMethod = firstRoom.getClass().getMethod("getLocation");
                        Object locObj = getLocationMethod.invoke(firstRoom);
                        
                        if (locObj != null) {
                            // Convert to Bukkit Location
                            double x = getDoubleField(locObj, "x");
                            double y = getDoubleField(locObj, "y");
                            double z = getDoubleField(locObj, "z");
                            
                            return new Location(world, x + 8, y + 1, z + 8); // Center of room + offset
                        }
                    }
                }
            } catch (Exception ignored) {
                // Layout method not available
            }
            
        } catch (Exception e) {
            // Silent fail, try other methods
        }
        
        return null;
    }
    
    private Location tryGetSpawnFromActiveInstance(Object mdPlugin, Player player, World world) {
        try {
            // Try to get active instance for player
            Method getInstanceMethod = mdPlugin.getClass().getMethod("getActiveInstance", Player.class);
            Object instance = getInstanceMethod.invoke(mdPlugin, player);
            
            if (instance != null) {
                // Try to get spawn from instance
                Method getSpawnMethod = instance.getClass().getMethod("getSpawnLocation");
                Object spawn = getSpawnMethod.invoke(instance);
                
                if (spawn instanceof Location) {
                    Location loc = (Location) spawn;
                    if (loc.getWorld() == null || !loc.getWorld().equals(world)) {
                        loc = new Location(world, loc.getX(), loc.getY(), loc.getZ());
                    }
                    return loc;
                }
            }
        } catch (Exception ignored) {
            // Method not available
        }
        
        return null;
    }
    
    private double getDoubleField(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(obj);
            
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Exception ignored) {
        }
        
        return 0.0;
    }
    
    private boolean isSafeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        
        try {
            // Check if chunk is loaded
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                loc.getWorld().loadChunk(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            }
            
            // Check if location is safe (not in solid block)
            return !loc.getBlock().getType().isSolid() && 
                   !loc.clone().add(0, 1, 0).getBlock().getType().isSolid();
        } catch (Exception e) {
            return false;
        }
    }
}
