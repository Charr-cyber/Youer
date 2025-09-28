package com.mohistmc.youer.bukkit.pluginfix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MythicDungeons Procedural Dungeon Teleportation Fix
 * Simplified version for Youer
 */
public class MythicDungeonsFix {
    
    private static final Map<String, Location> dungeonSpawns = new ConcurrentHashMap<>();
    
    /**
     * Fix teleport for procedural dungeons
     */
    public static void fixProceduralTeleport(Player player, String dungeonName) {
        if (player == null || dungeonName == null) return;
        
        System.out.println("[MythicDungeonsFix] Fixing teleport for " + player.getName() + " to dungeon: " + dungeonName);
        
        // Schedule teleport check
        Plugin plugin = getPlugin();
        if (plugin != null) {
            Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                int attempts = 0;
                
                @Override
                public void run() {
                    attempts++;
                    
                    World currentWorld = player.getWorld();
                    String worldName = currentWorld.getName();
                    
                    // Check if player is in dungeon world
                    if (worldName.toLowerCase().contains(dungeonName.toLowerCase()) || 
                        worldName.toLowerCase().contains("_" + dungeonName.toLowerCase())) {
                        
                        Location spawn = findDungeonSpawn(player, dungeonName, currentWorld);
                        
                        if (spawn != null) {
                            player.teleport(spawn);
                            System.out.println("[MythicDungeonsFix] Teleported " + player.getName() + " to spawn: " + 
                                spawn.getBlockX() + ", " + spawn.getBlockY() + ", " + spawn.getBlockZ());
                        } else if (attempts >= 10) {
                            // Fallback to world spawn
                            player.teleport(currentWorld.getSpawnLocation());
                            System.out.println("[MythicDungeonsFix] Using world spawn as fallback for " + player.getName());
                        }
                        
                        // Stop checking after success or timeout
                        if (spawn != null || attempts >= 10) {
                            Bukkit.getScheduler().cancelTasks(plugin);
                        }
                    } else if (attempts >= 20) {
                        System.out.println("[MythicDungeonsFix] Timeout waiting for dungeon world for " + player.getName());
                        Bukkit.getScheduler().cancelTasks(plugin);
                    }
                }
            }, 10L, 10L); // Check every 0.5 seconds
        }
    }
    
    private static Location findDungeonSpawn(Player player, String dungeonName, World world) {
        String cacheKey = world.getName() + "_" + dungeonName;
        
        // Check cache first
        Location cached = dungeonSpawns.get(cacheKey);
        if (cached != null) {
            return cached.clone();
        }
        
        try {
            // Try to get spawn from MythicDungeons
            Object mdPlugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
            if (mdPlugin != null) {
                // Try to get spawn location
                Location spawnLoc = tryGetSpawnFromDungeon(mdPlugin, dungeonName, world);
                if (spawnLoc != null) {
                    dungeonSpawns.put(cacheKey, spawnLoc);
                    return spawnLoc;
                }
                
                // Try alternative method
                spawnLoc = tryGetSpawnFromActiveInstance(mdPlugin, player, world);
                if (spawnLoc != null) {
                    dungeonSpawns.put(cacheKey, spawnLoc);
                    return spawnLoc;
                }
            }
        } catch (Exception e) {
            System.err.println("[MythicDungeonsFix] Error finding spawn: " + e.getMessage());
        }
        
        // Fallback: Common spawn points
        Location[] commonSpawns = {
            new Location(world, 0.5, 65, 0.5),
            new Location(world, 8.5, 65, 8.5),
            new Location(world, 0.5, 100, 0.5),
            world.getSpawnLocation()
        };
        
        for (Location loc : commonSpawns) {
            if (isSafeLocation(loc)) {
                dungeonSpawns.put(cacheKey, loc);
                return loc;
            }
        }
        
        return null;
    }
    
    private static Location tryGetSpawnFromDungeon(Object mdPlugin, String dungeonName, World world) {
        try {
            Method getDungeonMethod = mdPlugin.getClass().getMethod("getDungeon", String.class);
            Object dungeon = getDungeonMethod.invoke(mdPlugin, dungeonName);
            
            if (dungeon != null) {
                // Try different methods
                String[] methodNames = {"getSpawnLocation", "getSpawn", "getStartLocation"};
                
                for (String methodName : methodNames) {
                    try {
                        Method method = dungeon.getClass().getMethod(methodName);
                        Object result = method.invoke(dungeon);
                        
                        if (result instanceof Location) {
                            Location loc = (Location) result;
                            if (loc.getWorld() == null || !loc.getWorld().equals(world)) {
                                loc = new Location(world, loc.getX(), loc.getY(), loc.getZ());
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
                            
                            Method getLocationMethod = firstRoom.getClass().getMethod("getLocation");
                            Object locObj = getLocationMethod.invoke(firstRoom);
                            
                            if (locObj != null) {
                                double x = getDoubleField(locObj, "x");
                                double y = getDoubleField(locObj, "y");
                                double z = getDoubleField(locObj, "z");
                                
                                return new Location(world, x + 8, y + 1, z + 8);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Layout method not available
                }
            }
        } catch (Exception e) {
            // Silent fail
        }
        
        return null;
    }
    
    private static Location tryGetSpawnFromActiveInstance(Object mdPlugin, Player player, World world) {
        try {
            Method getInstanceMethod = mdPlugin.getClass().getMethod("getActiveInstance", Player.class);
            Object instance = getInstanceMethod.invoke(mdPlugin, player);
            
            if (instance != null) {
                Method getSpawnMethod = instance.getClass().getMethod("getSpawnLocation");
                Object spawnObj = getSpawnMethod.invoke(instance);
                
                if (spawnObj instanceof Location) {
                    Location loc = (Location) spawnObj;
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
    
    private static double getDoubleField(Object obj, String fieldName) {
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
    
    private static boolean isSafeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        
        try {
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                loc.getWorld().loadChunk(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            }
            
            return !loc.getBlock().getType().isSolid() && 
                   !loc.clone().add(0, 1, 0).getBlock().getType().isSolid();
        } catch (Exception e) {
            return false;
        }
    }
    
    private static Plugin getPlugin() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("Youer");
        }
        if (plugin == null) {
            // Get any plugin to use as scheduler
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            if (plugins.length > 0) {
                plugin = plugins[0];
            }
        }
        return plugin;
    }
    
    /**
     * Enhanced teleport method for compatibility
     */
    public static void enhancedTeleport(Entity entity, Location target) {
        if (entity == null || target == null) return;
        
        World world = target.getWorld();
        if (world == null) return;
        
        // For players in dungeon worlds, use special handling
        if (entity instanceof Player) {
            Player player = (Player) entity;
            String worldName = world.getName().toLowerCase();
            
            if (worldName.contains("dungeon") || worldName.contains("deneme") || worldName.contains("_")) {
                // Extract dungeon name from world name
                String dungeonName = worldName.replace("minecraft:", "").split("_")[0];
                
                // Use our fix
                fixProceduralTeleport(player, dungeonName);
                return;
            }
        }
        
        // Normal teleport for non-dungeon worlds
        loadChunksAround(target);
        
        if (Bukkit.isPrimaryThread()) {
            entity.teleport(target);
        } else {
            Plugin plugin = getPlugin();
            if (plugin != null) {
                Bukkit.getScheduler().runTask(plugin, () -> entity.teleport(target));
            }
        }
    }
    
    private static void loadChunksAround(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;
        
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (!world.isChunkLoaded(chunkX + x, chunkZ + z)) {
                    world.loadChunk(chunkX + x, chunkZ + z);
                }
            }
        }
    }
}
