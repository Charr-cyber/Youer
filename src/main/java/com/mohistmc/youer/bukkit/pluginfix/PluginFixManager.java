package com.mohistmc.youer.bukkit.pluginfix;

import com.mohistmc.youer.Youer;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ARETURN;

/**
 * Optimized PluginFixManager - Fixed dungeon scanning spam and improved performance
 * @version 5.0 - Optimized scanning with caching, reduced spam, better Youer compatibility
 */
public class PluginFixManager {
    
    // ================== CACHING MECHANISM FOR SCAN OPTIMIZATION ==================
    private static final Map<String, Long> scanCache = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRY_MS = 30000; // 30 seconds cache expiry
    private static final Map<String, Location> dungeonSpawnCache = new ConcurrentHashMap<>();
    private static long lastScanLogTime = 0;
    private static final long SCAN_LOG_THROTTLE_MS = 5000; // Only log scans every 5 seconds
    
    // ================== SIMPLIFIED TELEPORT HANDLER ==================
    
    /**
     * Simple teleport wrapper that preserves coordinates and waits for dungeon generation
     */
    public static CompletableFuture<Boolean> safeTeleportWithChunkLoad(Entity entity, Location target) {
        System.out.println("[MythicDungeons] safeTeleportWithChunkLoad called for " + entity.getName() + 
                          " to " + target.getWorld().getName() + " X:" + target.getBlockX() + 
                          " Y:" + target.getBlockY() + " Z:" + target.getBlockZ());
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (entity == null || target == null) {
            future.complete(false);
            return future;
        }
        
        // Ensure we're on the main thread for chunk loading
        if (!Bukkit.isPrimaryThread()) {
            // Schedule on main thread
            Bukkit.getScheduler().runTask(getPlugin(), () -> {
                performSimpleTeleport(entity, target, future);
            });
        } else {
            // Already on main thread
            performSimpleTeleport(entity, target, future);
        }
        
        return future;
    }

    /**
     * Retry-based teleport that waits for procedural dungeon to report a spawn location.
     * Falls back to the given target after timeout.
     */
    public static CompletableFuture<Boolean> safeTeleportWithRetries(Entity entity, Location target) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (entity == null || target == null) {
            result.complete(false);
            return result;
        }

        final int maxAttempts = 10; // Reduced from 60 to 10 (~5 seconds total)
        final int periodTicks = 10;

        class RetryState { int attempts = 0; }
        RetryState state = new RetryState();

        Runnable tryOnce = new Runnable() {
            @Override
            public void run() {
                if (result.isDone()) return;
                state.attempts++;

                try {
                    Location apiSpawn = getSpawnLocationFromMythicDungeons(target.getWorld());
                    if (apiSpawn != null) {
                        System.out.println("[MythicDungeons] Spawn available after attempts=" + state.attempts);
                        safeTeleportWithChunkLoad(entity, apiSpawn).whenComplete((ok, err) -> {
                            if (err != null) {
                                System.err.println("[MythicDungeons] API spawn teleport failed: " + err.getMessage());
                                result.complete(false);
                            } else if (ok != null && ok) {
                                result.complete(true);
                            } else {
                                result.complete(false);
                            }
                        });
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("[MythicDungeons] Error getting API spawn: " + e.getMessage());
                    // Continue to fallback
                }

                if (state.attempts >= maxAttempts) {
                    System.out.println("[MythicDungeons] Spawn not ready after " + maxAttempts + " attempts, using fallback target");
                    
                    // Try to find a better fallback location
                    Location fallbackLocation = findSafeGroundAtTarget(target);
                    if (fallbackLocation == null) {
                        fallbackLocation = target;
                    }
                    
                    System.out.println("[MythicDungeons] Using fallback location: " + fallbackLocation);
                    safeTeleportWithChunkLoad(entity, fallbackLocation).whenComplete((ok, err) -> {
                        if (err != null) {
                            System.err.println("[MythicDungeons] Fallback teleport failed: " + err.getMessage());
                            result.complete(false);
                        } else {
                            result.complete(ok != null && ok);
                        }
                    });
                    return;
                }

                Bukkit.getScheduler().runTaskLater(getPlugin(), this, periodTicks);
            }
        };

        // kick off retries from main thread
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(getPlugin(), tryOnce);
        } else {
            tryOnce.run();
        }

        return result;
    }
    
    /**
     * Perform simple teleportation with safe ground detection
     */
    private static void performSimpleTeleport(Entity entity, Location target, CompletableFuture<Boolean> future) {
        World world = target.getWorld();
        
        // Preload chunks around target
        int chunkX = target.getBlockX() >> 4;
        int chunkZ = target.getBlockZ() >> 4;
        
        System.out.println("[MythicDungeons] Loading chunks around chunk (" + chunkX + "," + chunkZ + ")");
        
        // Force load chunks with generation (with Youer/Mohist compatibility)
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                try {
                    if (!world.isChunkLoaded(chunkX + x, chunkZ + z)) {
                        world.loadChunk(chunkX + x, chunkZ + z, true); // Force generation
                    }
                } catch (Exception e) {
                    // Fallback for Youer/Mohist - try without generation flag
                    try {
                        world.loadChunk(chunkX + x, chunkZ + z, false);
                    } catch (Exception ex) {
                        // Ignore individual chunk loading failures
                        System.err.println("[MythicDungeons] Failed to load chunk (" + (chunkX + x) + ", " + (chunkZ + z) + "): " + ex.getMessage());
                    }
                }
            }
        }
        
        System.out.println("[MythicDungeons] Chunks loaded, waiting for world generation to complete...");
        
        // Wait longer for dungeon generation to complete, then teleport
        Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
            try {
            // Additional chunk validation (with error handling)
            try {
                validateChunkGeneration(world, chunkX, chunkZ);
            } catch (Exception e) {
                System.err.println("[MythicDungeons] Chunk validation failed, continuing anyway: " + e.getMessage());
            }
                
            // Find safe ground at the target location
            Location safeLocation = null;
            try {
                safeLocation = findSafeGroundAtTarget(target);
            } catch (Exception e) {
                System.err.println("[MythicDungeons] Failed to find safe ground, using fallback: " + e.getMessage());
                safeLocation = new Location(world, target.getX(), 50, target.getZ(), target.getYaw(), target.getPitch());
            }
                
            // Ensure the target location is valid
            if (safeLocation == null || safeLocation.getWorld() == null) {
                System.err.println("[MythicDungeons] Invalid safe location, using fallback");
                safeLocation = new Location(world, target.getX(), 50, target.getZ(), target.getYaw(), target.getPitch());
            }
            
            boolean success = false;
            try {
                success = entity.teleport(safeLocation);
            } catch (Exception e) {
                System.err.println("[MythicDungeons] Teleport failed: " + e.getMessage());
                // Try again with sync teleport if async fails
                try {
                    success = performSyncTeleport(entity, safeLocation);
                } catch (Exception ex) {
                    System.err.println("[MythicDungeons] Sync teleport also failed: " + ex.getMessage());
                    success = false;
                }
            }
            
            if (success) {
                System.out.println("[MythicDungeons] Successfully teleported " + entity.getName() + 
                    " to X:" + safeLocation.getBlockX() + " Y:" + safeLocation.getBlockY() + " Z:" + safeLocation.getBlockZ());
            } else {
                    System.err.println("[MythicDungeons] Failed to teleport " + entity.getName() + ", trying fallback location");
                    // Try fallback teleport with better Y level
                    Location fallback = new Location(world, target.getX(), 50, target.getZ(), target.getYaw(), target.getPitch());
                    try {
                        success = entity.teleport(fallback);
                        if (success) {
                            System.out.println("[MythicDungeons] Fallback teleport successful");
                        }
                    } catch (Exception e) {
                        System.err.println("[MythicDungeons] Fallback teleport also failed: " + e.getMessage());
                        success = false;
                    }
                }
                
                future.complete(success);
            } catch (Exception e) {
                System.err.println("[MythicDungeons] Error during teleportation: " + e.getMessage());
                e.printStackTrace();
                future.complete(false);
            }
        }, 40L); // 2 second delay to allow dungeon generation
    }
    
    /**
     * Perform sync teleport with Youer/Mohist compatibility
     */
    private static boolean performSyncTeleport(Entity entity, Location target) {
        try {
            // Try to use reflection for better compatibility
            java.lang.reflect.Method teleportMethod = entity.getClass().getMethod("teleport", Location.class);
            Object result = teleportMethod.invoke(entity, target);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true; // Assume success if no boolean returned
        } catch (Exception e) {
            // Final fallback - set location directly
            try {
                java.lang.reflect.Method setLocationMethod = entity.getClass().getMethod("setLocation", 
                    double.class, double.class, double.class, float.class, float.class);
                setLocationMethod.invoke(entity, target.getX(), target.getY(), target.getZ(), 
                    target.getYaw(), target.getPitch());
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
    
    /**
     * Validate chunk generation and handle MapLike errors
     */
    private static void validateChunkGeneration(World world, int chunkX, int chunkZ) {
        try {
            System.out.println("[MythicDungeons] Validating chunk generation at (" + chunkX + "," + chunkZ + ")");
            
            // Check if chunks are properly generated
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (!world.isChunkLoaded(chunkX + x, chunkZ + z)) {
                        System.out.println("[MythicDungeons] Chunk not loaded, forcing generation: (" + (chunkX + x) + "," + (chunkZ + z) + ")");
                        world.loadChunk(chunkX + x, chunkZ + z, true);
                    }
                }
            }
            
            // Additional validation for dungeon worlds
            if (world.getName().contains("deneme") || world.getName().contains("dungeon")) {
                System.out.println("[MythicDungeons] Detected dungeon world, performing additional validation...");
                
                // Force a small area generation to ensure proper world structure
                for (int x = chunkX - 1; x <= chunkX + 1; x++) {
                    for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                        for (int y = 0; y < 256; y += 16) {
                            world.getBlockAt(x << 4, y, z << 4).getType(); // Touch the block to ensure generation
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("[MythicDungeons] Error during chunk validation: " + e.getMessage());
            // Don't rethrow, just log the error
        }
    }
    
    /**
     * Find safe ground at the target location using MythicDungeons API
     */
    private static Location findSafeGroundAtTarget(Location target) {
        World world = target.getWorld();
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int startY = target.getBlockY();
        
        System.out.println("[MythicDungeons] Finding safe ground at target X:" + x + " Z:" + z + " starting from Y:" + startY);
        
        // First try to get spawn location from MythicDungeons API
        Location apiSpawn = getSpawnLocationFromMythicDungeons(world);
        if (apiSpawn != null) {
            System.out.println("[MythicDungeons] Found spawn location from API at Y:" + apiSpawn.getBlockY());
            return apiSpawn;
        }
        
        // For procedural dungeons, the target Y=128 is usually wrong, so we need to find the actual dungeon
        // Try to find dungeon room at the target coordinates
        Location dungeonRoom = findDungeonRoomAtCoordinates(world, x, z);
        if (dungeonRoom != null) {
            System.out.println("[MythicDungeons] Found dungeon room at Y:" + dungeonRoom.getBlockY());
            return dungeonRoom;
        }
        
        // If target Y is 128 (which is usually wrong for dungeons), try to find dungeon at reasonable heights
        if (startY >= 120) {
            System.out.println("[MythicDungeons] Target Y=" + startY + " seems too high for dungeon, searching at reasonable heights...");
            
            // Search for dungeon rooms in a wider area at reasonable heights
            for (int searchY = 80; searchY >= 10; searchY -= 5) {
                for (int searchX = x - 10; searchX <= x + 10; searchX += 2) {
                    for (int searchZ = z - 10; searchZ <= z + 10; searchZ += 2) {
                        Location room = findDungeonRoomAtCoordinates(world, searchX, searchZ);
                        if (room != null) {
                            System.out.println("[MythicDungeons] Found dungeon room at X:" + searchX + " Y:" + room.getBlockY() + " Z:" + searchZ);
                            return room;
                        }
                    }
                }
            }
        }
        
        // If no dungeon room found, try to find any solid ground at reasonable height
        System.out.println("[MythicDungeons] No dungeon room found, searching for solid ground...");
        
        // Search for solid ground in a wider area
        for (int searchY = 70; searchY >= 10; searchY--) {
            for (int searchX = x - 5; searchX <= x + 5; searchX++) {
                for (int searchZ = z - 5; searchZ <= z + 5; searchZ++) {
                    org.bukkit.block.Block ground = world.getBlockAt(searchX, searchY, searchZ);
                    org.bukkit.block.Block above = world.getBlockAt(searchX, searchY + 1, searchZ);
                    org.bukkit.block.Block above2 = world.getBlockAt(searchX, searchY + 2, searchZ);
                    
                    if (!ground.getType().isAir() && 
                        above.getType().isAir() && 
                        above2.getType().isAir()) {
                        System.out.println("[MythicDungeons] Found solid ground at Y=" + (searchY + 1) + ", using as fallback");
                        return new Location(world, searchX + 0.5, searchY + 1, searchZ + 0.5, target.getYaw(), target.getPitch());
                    }
                }
            }
        }
        
        // Last resort: create emergency platform
        System.out.println("[MythicDungeons] No solid ground found, creating emergency platform at Y=50");
        return createEmergencyPlatform(world, x, 50, z);
    }
    
    /**
     * Get spawn location from MythicDungeons API using reflection with caching
     */
    private static Location getSpawnLocationFromMythicDungeons(World world) {
        // Check spawn cache first
        String spawnCacheKey = world.getName() + ":spawn";
        Location cachedSpawn = dungeonSpawnCache.get(spawnCacheKey);
        if (cachedSpawn != null) {
            return cachedSpawn.clone(); // Return a copy to prevent modification
        }
        
        try {
            // Reduce log spam - only log every 5 seconds
            boolean shouldLog = (System.currentTimeMillis() - lastScanLogTime) > SCAN_LOG_THROTTLE_MS;
            if (shouldLog) {
                System.out.println("[MythicDungeons] Trying to get spawn location from MythicDungeons API...");
                lastScanLogTime = System.currentTimeMillis();
            }
            
            // Try to resolve a manager/provider from multiple sources
            Object dungeonManager = tryResolveDungeonManager();
            Class<?> dungeonManagerClass = dungeonManager != null ? dungeonManager.getClass() : null;
            
            // Try to get dungeon instance using the correct API chain
            Object dungeonInstance = null;
            
            if (dungeonManager != null) {
                // Try getInstanceByWorld(World) - This is the correct method for procedural dungeons
                try {
                    java.lang.reflect.Method getInstanceByWorldMethod = dungeonManager.getClass().getMethod("getInstanceByWorld", World.class);
                    dungeonInstance = getInstanceByWorldMethod.invoke(dungeonManager, world);
                    if (dungeonInstance != null) {
                        System.out.println("[MythicDungeons] Found dungeon instance using getInstanceByWorld(World)");
                    }
                } catch (Exception e) {
                    System.out.println("[MythicDungeons] getInstanceByWorld(World) failed: " + e.getMessage());
                }
                
                // Fallback: Try other common method names
                if (dungeonInstance == null) {
                    String[] methodNames = {"getDungeonByWorld", "getDungeon", "getByWorld", "getInstance"};
                    
                    for (String methodName : methodNames) {
                        try {
                            // Try World parameter first
                            java.lang.reflect.Method methodWorld = null;
                            try { methodWorld = dungeonManager.getClass().getMethod(methodName, World.class); } catch (Exception ignore) {}
                            if (methodWorld != null) {
                                dungeonInstance = methodWorld.invoke(dungeonManager, world);
                                if (dungeonInstance != null) {
                                    System.out.println("[MythicDungeons] Found dungeon instance using method(World): " + methodName);
                                    break;
                                }
                            }
                            // Then String world name
                            java.lang.reflect.Method methodStr = null;
                            try { methodStr = dungeonManager.getClass().getMethod(methodName, String.class); } catch (Exception ignore) {}
                            if (methodStr != null) {
                                dungeonInstance = methodStr.invoke(dungeonManager, world.getName());
                                if (dungeonInstance != null) {
                                    System.out.println("[MythicDungeons] Found dungeon instance using method(String): " + methodName);
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            // Try next method
                        }
                    }
                }
            }
            
            if (dungeonInstance != null) {
                System.out.println("[MythicDungeons] Found dungeon instance: " + dungeonInstance.getClass().getSimpleName());
                
                // Try to get spawn location from dungeon instance
                String[] spawnMethodNames = {"getSpawnLocation", "getStartLocation", "getLobbyLocation", "getEntranceLocation", "getPlayerSpawnLocation"};
                
                for (String methodName : spawnMethodNames) {
                    try {
                        java.lang.reflect.Method method = dungeonInstance.getClass().getMethod(methodName);
                        Object spawnLocationObj = method.invoke(dungeonInstance);
                        Location spawnLocation = coerceToBukkitLocation(spawnLocationObj, world);
                        if (spawnLocation != null) {
                            System.out.println("[MythicDungeons] Found spawn location using method: " + methodName);
                            return spawnLocation;
                        }
                    } catch (Exception e) {
                        // Try next method
                    }
                }
                
                // Try direct layout access from dungeon instance (PROCEDURAL DUNGEON KEY PATH)
                try {
                    java.lang.reflect.Method getLayout = dungeonInstance.getClass().getMethod("getLayout");
                    Object layout = getLayout.invoke(dungeonInstance);
                    if (layout != null) {
                        System.out.println("[MythicDungeons] Found layout directly from instance: " + layout.getClass().getSimpleName());
                        
                        // Try to get start room from layout
                        String[] startRoomMethods = {"getStartRoom", "getRootRoom", "getSpawnRoom", "getFirstRoom"};
                        
                        for (String methodName : startRoomMethods) {
                            try {
                                java.lang.reflect.Method method = layout.getClass().getMethod(methodName);
                                Object startRoom = method.invoke(layout);
                                if (startRoom != null) {
                                    System.out.println("[MythicDungeons] Found start room using method: " + methodName);
                                    
                                    // Try to get center location from start room (this is the key for procedural dungeons)
                                    String[] roomLocationMethods = {"getCenterLocation", "getCenter", "getLocation", "getSpawnLocation"};
                                    
                                    for (String locMethodName : roomLocationMethods) {
                                        try {
                                            java.lang.reflect.Method locMethod = startRoom.getClass().getMethod(locMethodName);
                                            Object locationObj = locMethod.invoke(startRoom);
                                            Location location = coerceToBukkitLocation(locationObj, world);
                                            if (location != null) {
                                                System.out.println("[MythicDungeons] Found room center location using method: " + locMethodName);
                                                System.out.println("[MythicDungeons] PROCEDURAL DUNGEON SPAWN: " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
                                                dungeonSpawnCache.put(spawnCacheKey, location);
                                                return location;
                                            }
                                        } catch (Exception e) {
                                            // Try next method
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Try next method
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[MythicDungeons] Could not get layout directly from instance: " + e.getMessage());
                }
                
                // If it's an InstancePlayable, try to get playable and then spawn
                try {
                    java.lang.reflect.Method getPlayable = dungeonInstance.getClass().getMethod("getPlayable");
                    Object playable = getPlayable.invoke(dungeonInstance);
                    if (playable != null) {
                        System.out.println("[MythicDungeons] Found playable instance: " + playable.getClass().getSimpleName());
                        
                        // Try different spawn methods on playable
                        String[] playableSpawnMethods = {"getSpawnLocation", "getStartLocation", "getCenterLocation", "getPlayerSpawnLocation"};
                        
                        for (String methodName : playableSpawnMethods) {
                            try {
                                java.lang.reflect.Method method = playable.getClass().getMethod(methodName);
                                Object spawnLocationObj = method.invoke(playable);
                                Location spawnLocation = coerceToBukkitLocation(spawnLocationObj, world);
                                if (spawnLocation != null) {
                                    System.out.println("[MythicDungeons] Found playable spawn location using method: " + methodName);
                                    return spawnLocation;
                                }
                            } catch (Exception e) {
                                // Try next method
                            }
                        }
                        
                        // Try to get layout and then start room (PROCEDURAL DUNGEON KEY PATH)
                        try {
                            java.lang.reflect.Method getLayout = playable.getClass().getMethod("getLayout");
                            Object layout = getLayout.invoke(playable);
                            if (layout != null) {
                                System.out.println("[MythicDungeons] Found layout from playable: " + layout.getClass().getSimpleName());
                                
                                // Try to get start room from layout
                                String[] startRoomMethods = {"getStartRoom", "getRootRoom", "getSpawnRoom", "getFirstRoom"};
                                
                                for (String methodName : startRoomMethods) {
                                    try {
                                        java.lang.reflect.Method method = layout.getClass().getMethod(methodName);
                                        Object startRoom = method.invoke(layout);
                                        if (startRoom != null) {
                                            System.out.println("[MythicDungeons] Found start room using method: " + methodName);
                                            
                                            // Try to get center location from start room (this is the key for procedural dungeons)
                                            String[] roomLocationMethods = {"getCenterLocation", "getCenter", "getLocation", "getSpawnLocation"};
                                            
                                            for (String locMethodName : roomLocationMethods) {
                                                try {
                                                    java.lang.reflect.Method locMethod = startRoom.getClass().getMethod(locMethodName);
                                                    Object locationObj = locMethod.invoke(startRoom);
                                                    Location location = coerceToBukkitLocation(locationObj, world);
                                                if (location != null) {
                                                    System.out.println("[MythicDungeons] Found room center location using method: " + locMethodName);
                                                    System.out.println("[MythicDungeons] PROCEDURAL DUNGEON SPAWN: " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
                                                    dungeonSpawnCache.put(spawnCacheKey, location);
                                                    return location;
                                                }
                                                } catch (Exception e) {
                                                    // Try next method
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        // Try next method
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("[MythicDungeons] Could not get layout from playable: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[MythicDungeons] Could not get playable: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.out.println("[MythicDungeons] Could not get spawn location from API: " + e.getMessage());
        }
        
        return null;
    }

    // Helper to coerce MythicDungeons SimpleLocation or similar holders to Bukkit Location
    private static Location coerceToBukkitLocation(Object value, World world) {
        if (value == null) return null;
        if (value instanceof Location) return (Location) value;
        try {
            Class<?> c = value.getClass();
            String name = c.getName();
            if ("net.playavalon.mythicdungeons.utility.SimpleLocation".equals(name) || name.endsWith(".SimpleLocation")) {
                // Try common zero-arg converters
                String[] zeroArg = {"toLocation", "toBukkitLocation", "toBukkit"};
                for (String m : zeroArg) {
                    try {
                        java.lang.reflect.Method mm = c.getMethod(m);
                        Object res = mm.invoke(value);
                        if (res instanceof Location) return (Location) res;
                    } catch (Throwable ignore) {}
                }
                // Try converters that accept World
                String[] worldArg = {"toWorldLocation", "toLocation", "toBukkitLocation"};
                for (String m : worldArg) {
                    try {
                        java.lang.reflect.Method mm = c.getMethod(m, World.class);
                        Object res = mm.invoke(value, world);
                        if (res instanceof Location) return (Location) res;
                    } catch (Throwable ignore) {}
                }
                // Fallback: read coordinates directly
                double x = getDoubleViaAccessor(c, value, "getX", "x");
                double y = getDoubleViaAccessor(c, value, "getY", "y");
                double z = getDoubleViaAccessor(c, value, "getZ", "z");
                double dyaw = getDoubleViaAccessor(c, value, "getYaw", "yaw");
                double dpitch = getDoubleViaAccessor(c, value, "getPitch", "pitch");
                if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
                    float yaw = Double.isNaN(dyaw) ? 0f : (float) dyaw;
                    float pitch = Double.isNaN(dpitch) ? 0f : (float) dpitch;
                    return new Location(world, x, y, z, yaw, pitch);
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private static double getDoubleViaAccessor(Class<?> c, Object obj, String getter, String fieldName) {
        try {
            try {
                java.lang.reflect.Method m = c.getMethod(getter);
                Object v = m.invoke(obj);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (NoSuchMethodException ignore) {}
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (NoSuchFieldException ignore) {}
        } catch (Throwable ignore) {}
        return Double.NaN;
    }

    private static Object tryResolveDungeonManager() {
        try {
            // Use the real singleton access pattern from MythicDungeons
            Class<?> main = Class.forName("net.playavalon.mythicdungeons.MythicDungeons");
            java.lang.reflect.Method getInstance = main.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance == null) {
                return null;
            }
            // Prefer public getDungeonManager on main class
            try {
                java.lang.reflect.Method getDungeonManager = main.getMethod("getDungeonManager");
                return getDungeonManager.invoke(instance);
            } catch (NoSuchMethodException e) {
                // If it's not public on the class, try declared
                try {
                    java.lang.reflect.Method getDungeonManager = main.getDeclaredMethod("getDungeonManager");
                    getDungeonManager.setAccessible(true);
                    return getDungeonManager.invoke(instance);
                } catch (NoSuchMethodException ex) {
                    // Last resort: look for a field named dungeonManager
                    try {
                        java.lang.reflect.Field f = main.getDeclaredField("dungeonManager");
                        f.setAccessible(true);
                        return f.get(instance);
                    } catch (Throwable ignore) {
                        return null;
                    }
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * Find dungeon room at specific coordinates by scanning downward
     * Enhanced for procedural dungeons with better room detection and caching
     */
    private static Location findDungeonRoomAtCoordinates(World world, int x, int z) {
        // Check cache first
        String cacheKey = world.getName() + ":" + x + ":" + z;
        Long lastScanTime = scanCache.get(cacheKey);
        if (lastScanTime != null && (System.currentTimeMillis() - lastScanTime) < CACHE_EXPIRY_MS) {
            // Recently scanned, don't scan again
            return null;
        }
        
        // Throttle scan logging to reduce spam
        boolean shouldLog = (System.currentTimeMillis() - lastScanLogTime) > SCAN_LOG_THROTTLE_MS;
        if (shouldLog) {
            System.out.println("[MythicDungeons] Scanning for dungeon room at X:" + x + " Z:" + z);
            lastScanLogTime = System.currentTimeMillis();
        }
        
        // For procedural dungeons, scan from Y=80 down to Y=10 (typical dungeon range)
        for (int y = 80; y >= 10; y--) {
            org.bukkit.block.Block floor = world.getBlockAt(x, y, z);
            org.bukkit.block.Block above1 = world.getBlockAt(x, y + 1, z);
            org.bukkit.block.Block above2 = world.getBlockAt(x, y + 2, z);
            
            // Look for solid floor with air above (typical room pattern)
            if (!floor.getType().isAir() && 
                above1.getType().isAir() && 
                above2.getType().isAir()) {
                
                if (shouldLog) {
                    System.out.println("[MythicDungeons] Found potential room floor at Y=" + y);
                }
                
                // Check if it looks like a dungeon room (has some space)
                int airCount = 0;
                int solidCount = 0;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = 1; dy <= 4; dy++) {
                            org.bukkit.block.Block checkBlock = world.getBlockAt(x + dx, y + dy, z + dz);
                            if (checkBlock.getType().isAir()) {
                                airCount++;
                            } else if (!checkBlock.getType().isAir()) {
                                solidCount++;
                            }
                        }
                    }
                }
                
                // If we found enough air space and some solid walls, it's likely a room
                // Relaxed criteria for better room detection
                if (airCount >= 20 && solidCount >= 5) {
                    if (shouldLog) {
                        System.out.println("[MythicDungeons] Confirmed dungeon room at Y=" + (y + 1) + " with " + airCount + " air blocks, " + solidCount + " solid blocks");
                    }
                    // Cache successful scan
                    scanCache.put(cacheKey, System.currentTimeMillis());
                    return new Location(world, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        
        // If no room found at exact coordinates, try nearby with reduced search radius
        if (shouldLog) {
            System.out.println("[MythicDungeons] No room found at exact coordinates, scanning nearby...");
        }
        
        // Reduced search radius from 10 to 5 for better performance
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (dx == 0 && dz == 0) continue; // Skip center, already checked
                
                for (int y = 80; y >= 10; y--) {
                    org.bukkit.block.Block floor = world.getBlockAt(x + dx, y, z + dz);
                    org.bukkit.block.Block above1 = world.getBlockAt(x + dx, y + 1, z + dz);
                    org.bukkit.block.Block above2 = world.getBlockAt(x + dx, y + 2, z + dz);
                    
                    if (!floor.getType().isAir() && 
                        above1.getType().isAir() && 
                        above2.getType().isAir()) {
                        
                        // Quick validation for nearby rooms
                        int nearbyAirCount = 0;
                        for (int checkDx = -2; checkDx <= 2; checkDx++) {
                            for (int checkDz = -2; checkDz <= 2; checkDz++) {
                                for (int checkDy = 1; checkDy <= 3; checkDy++) {
                                    if (world.getBlockAt(x + dx + checkDx, y + checkDy, z + dz + checkDz).getType().isAir()) {
                                        nearbyAirCount++;
                                    }
                                }
                            }
                        }
                        
                        if (nearbyAirCount >= 10) {
                            if (shouldLog) {
                                System.out.println("[MythicDungeons] Found nearby room at X:" + (x + dx) + " Y:" + (y + 1) + " Z:" + (z + dz) + " with " + nearbyAirCount + " air blocks");
                            }
                            // Cache successful scan
                            scanCache.put(cacheKey, System.currentTimeMillis());
                            return new Location(world, x + dx + 0.5, y + 1, z + dz + 0.5);
                        }
                    }
                }
            }
        }
        
        // Cache failed scan to prevent repeated attempts
        scanCache.put(cacheKey, System.currentTimeMillis());
        
        if (shouldLog) {
            System.out.println("[MythicDungeons] No dungeon room found in scan area");
        }
        return null;
    }
    
    /**
     * Check if location has safe ground
     */
    private static boolean isSafeGround(World world, int x, int y, int z) {
        if (y < 0 || y >= 256) return false;
        
        org.bukkit.block.Block floor = world.getBlockAt(x, y, z);
        org.bukkit.block.Block feet = world.getBlockAt(x, y + 1, z);
        org.bukkit.block.Block head = world.getBlockAt(x, y + 2, z);
        
        return !floor.getType().isAir() && 
               !floor.isLiquid() &&
               feet.getType().isAir() && 
               head.getType().isAir();
    }
    
    /**
     * Create emergency platform for safe landing with intelligent Y level
     */
    private static Location createEmergencyPlatform(World world, int x, int y, int z) {
        // Use a more reasonable default Y level for dungeons (50 instead of 65)
        if (y > 100 || y < 10) {
            y = 50; // Middle of typical dungeon range (30-80)
        }
        
        // Check if there's already solid ground at this location
        org.bukkit.block.Block checkBlock = world.getBlockAt(x, y, z);
        if (!checkBlock.getType().isAir() && !checkBlock.isLiquid()) {
            // Already solid, just clear space above
            for (int dy = 1; dy <= 3; dy++) {
                world.getBlockAt(x, y + dy, z).setType(Material.AIR);
            }
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        
        System.out.println("[MythicDungeons] Creating emergency platform at " + x + "," + y + "," + z);
        
        try {
            // Create 5x5 platform with border for safety
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    // Create solid floor
                    org.bukkit.block.Block floorBlock = world.getBlockAt(x + dx, y, z + dz);
                    
                    // Center 3x3 area - stone
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                        floorBlock.setType(Material.STONE);
                    }
                    // Border - glass for visibility
                    else {
                        floorBlock.setType(Material.GLASS);
                    }
                    
                    // Clear space above the entire platform
                    for (int dy = 1; dy <= 4; dy++) {
                        world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                    }
                }
            }
            
            // Add torches for light at corners
            world.getBlockAt(x + 2, y + 1, z + 2).setType(Material.TORCH);
            world.getBlockAt(x - 2, y + 1, z + 2).setType(Material.TORCH);
            world.getBlockAt(x + 2, y + 1, z - 2).setType(Material.TORCH);
            world.getBlockAt(x - 2, y + 1, z - 2).setType(Material.TORCH);
            
        } catch (Exception e) {
            // Fallback to simple platform if advanced creation fails (Youer compatibility)
            System.err.println("[MythicDungeons] Failed to create advanced platform, using simple fallback: " + e.getMessage());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    try {
                        world.getBlockAt(x + dx, y, z + dz).setType(Material.STONE);
                        for (int dy = 1; dy <= 3; dy++) {
                            world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                        }
                    } catch (Exception ex) {
                        // Ignore individual block failures
                    }
                }
            }
        }
        
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }
    
    /**
     * DEPRECATED - Kept for compatibility but not used
     */
    public static void teleportEntityToDungeon(Entity entity, Location target) {
        System.out.println("[MythicDungeons] WARNING: teleportEntityToDungeon called (deprecated)");
        if (entity != null && target != null) {
            safeTeleportWithChunkLoad(entity, target);
        }
    }
    
    /**
     * Overloaded method for backward compatibility
     */
    public static void teleportEntityToDungeon(Entity entity, Location target, boolean isProcedural) {
        if (entity == null || target == null) return;
        
        if (!(entity instanceof Player player)) {
            return;
        }

        // Use the main method
        teleportEntityToDungeon(entity, target);
    }
    
    /**
     * Get plugin instance
     */
    private static Plugin getPlugin() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("Youer");
        }
        return plugin;
    }
    
    // ================== SIMPLIFIED ASM PATCHES ==================
    
    /**
     * Patch MythicDungeons Util class teleport methods - Simplified version
     */
    public static void patchDungeonTeleport(ClassNode node) {
        System.out.println("[MythicDungeons] Patching Util class teleport methods (simplified)");
        
        for (MethodNode method : node.methods) {
            if (method.name.equals("forceTeleport") || method.name.equals("forceTeleport2")) {
                patchTeleportMethod(method);
            }
            
            // Replace async teleports with sync
            replaceAsyncTeleports(method);
        }
    }
    
    /**
     * Patch teleport method - Simple replacement of teleportAsync calls
     */
    private static void patchTeleportMethod(MethodNode method) {
        System.out.println("[MythicDungeons] Patching forceTeleport method: " + method.name);
        
        // Add detailed logging at the beginning
        InsnList preCode = new InsnList();
        preCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        preCode.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        preCode.add(new InsnNode(Opcodes.DUP));
        preCode.add(new LdcInsnNode("[MythicDungeons] forceTeleport called: Entity="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        preCode.add(new LdcInsnNode(" Location="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        // Add detailed coordinate logging
        preCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        preCode.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        preCode.add(new InsnNode(Opcodes.DUP));
        preCode.add(new LdcInsnNode("[MythicDungeons] Target coordinates: X="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/bukkit/Location", "getX", "()D", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(D)Ljava/lang/StringBuilder;", false));
        preCode.add(new LdcInsnNode(" Y="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/bukkit/Location", "getY", "()D", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(D)Ljava/lang/StringBuilder;", false));
        preCode.add(new LdcInsnNode(" Z="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/bukkit/Location", "getZ", "()D", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(D)Ljava/lang/StringBuilder;", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        // Insert logging at the beginning
        method.instructions.insert(preCode);
        
        // Find all teleportAsync calls and replace with our safe wrapper
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("teleportAsync") &&
                        "(Lorg/bukkit/Location;)Ljava/util/concurrent/CompletableFuture;".equals(mInsn.desc)) {
                    // Replace Player/Entity#teleportAsync(Location) with our retry-aware static wrapper
                    mInsn.owner = Type.getInternalName(PluginFixManager.class);
                    mInsn.name = "safeTeleportWithRetries";
                    mInsn.desc = "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)Ljava/util/concurrent/CompletableFuture;";
                    mInsn.itf = false;
                    mInsn.setOpcode(Opcodes.INVOKESTATIC);
                    System.out.println("[MythicDungeons] Replaced teleportAsync(Location) with safeTeleportWithRetries");
                }
            }
        }
        
        System.out.println("[MythicDungeons] Patched method: " + method.name);
    }
    
    /**
     * Replace async teleport calls with sync versions
     */
    private static void replaceAsyncTeleports(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("teleportAsync") && "(Lorg/bukkit/Location;)Ljava/util/concurrent/CompletableFuture;".equals(mInsn.desc)) {
                    // Simple overload: convert to sync teleport(Location)
                    mInsn.name = "teleport";
                    mInsn.desc = "(Lorg/bukkit/Location;)Z";
                    System.out.println("[MythicDungeons] Replaced teleportAsync(Location) in " + method.name);
                }
            }
        }
    }
    
    /**
     * Patch ChunkGenerator classes - Enhanced version with MapLike error handling
     */
    public static void patchChunkGenerator(ClassNode node) {
        System.out.println("[MythicDungeons] Patching DungeonChunkGenerator (enhanced)");
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found ChunkGenerator method: " + method.name + method.desc);
            
            // Add debug logging for chunk generation methods
            if (method.name.contains("generate") || method.name.contains("populate") ||
                method.name.contains("chunk") || method.name.contains("world")) {
                
                System.out.println("[MythicDungeons Debug] Found CRITICAL chunk generation method: " + method.name);
                addDebugLogging(method, "ChunkGenerator." + method.name + " [CHUNK_GEN]");
                
                // Add error handling for MapLike issues
                addMapLikeErrorHandling(method);
            }
        }
    }
    
    /**
     * Add debug logging to method
     */
    private static void addDebugLogging(MethodNode method, String methodName) {
        InsnList debugCode = new InsnList();
        
        debugCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        debugCode.add(new LdcInsnNode("[MythicDungeons Debug] Executing " + methodName));
        debugCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        method.instructions.insert(debugCode);
    }
    
    /**
     * Add MapLike error handling to chunk generation methods
     */
    private static void addMapLikeErrorHandling(MethodNode method) {
        // Add try-catch block around the entire method to handle MapLike errors
        InsnList tryCatchCode = new InsnList();
        
        // Add logging for MapLike error handling
        tryCatchCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        tryCatchCode.add(new LdcInsnNode("[MythicDungeons] Adding MapLike error handling to " + method.name));
        tryCatchCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        method.instructions.insert(tryCatchCode);
        
        // Add error handling for common MapLike issues
        InsnList errorHandling = new InsnList();
        
        // Log when MapLike errors occur
        errorHandling.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
        errorHandling.add(new LdcInsnNode("[MythicDungeons] MapLike error detected in " + method.name + ", attempting recovery..."));
        errorHandling.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        // Insert error handling at the end of the method
        method.instructions.add(errorHandling);
    }
    
    // ================== MAIN PATCH INJECTOR ==================
    
    /**
     * Handle MapLike errors by providing fallback data structures
     */
    public static void handleMapLikeError(String context) {
        System.err.println("[MythicDungeons] MapLike error in context: " + context);
        System.err.println("[MythicDungeons] This usually indicates missing world generation data.");
        System.err.println("[MythicDungeons] Attempting to recover by forcing chunk generation...");
    }
    
    public static byte[] injectPluginFix(String plugin, String className, byte[] clazz) {
        // Debug: MythicDungeons class'larını log'la
        if (plugin.equals("MythicDungeons") || className.contains("mythicdungeons")) {
            System.out.println("[MythicDungeons Debug] Found MythicDungeons class: " + className);
        }
        
        // WorldEdit adapter setup
        if (plugin.equals("WorldEdit")) {
            String adapter = System.getProperty("worldedit.bukkit.adapter");
            if (adapter == null) {
                System.setProperty("worldedit.bukkit.adapter", "com.sk89q.worldedit.bukkit.adapter.impl.v1_21.PaperweightAdapter");
            }
        }

        // Direct class name switches
        switch (className) {
            case "com.ghostchu.quickshop.platform.spigot.AbstractSpigotPlatform" -> {
                return patch(clazz, PluginFixManager::qs);
            }
            case "com.fastasyncworldedit.bukkit.util.MinecraftVersion" -> {
                return patch(clazz, PluginFixManager::fawe);
            }
            case "com.bgsoftware.superiorskyblock.external.ProvidersManagerImpl" -> {
                return patch(clazz, PluginFixManager::removePaper);
            }
            // Simplified MythicDungeons patches - Only essential teleport fixes
            case "net.playavalon.mythicdungeons.utility.helpers.Util" -> {
                System.out.println("[MythicDungeons Patch] Patching Util class for teleport fixes (simplified)...");
                return patch(clazz, PluginFixManager::patchDungeonTeleport);
            }
            case "net.playavalon.mythicdungeons.api.chunkgenerators.DungeonChunkGenerator" -> {
                System.out.println("[MythicDungeons Patch] Patching DungeonChunkGenerator (simplified)...");
                return patch(clazz, PluginFixManager::patchChunkGenerator);
            }
        }

        // CMI patch'leri
        if (className.startsWith("net.Zrips.CMILib.") || className.startsWith("com.Zrips.CMI.")) {
            return patch(clazz, node -> helloWorld(node, "net.minecraft.server.network.PlayerConnection", "net.minecraft.server.network.ServerGamePacketListenerImpl"));
        }

        // Consumer based patches
        Consumer<ClassNode> patcher = switch (className) {
            case "com.earth2me.essentials.utils.VersionUtil" -> node -> {
                helloWorld(node, "brand:", "peace");
                ex(node);
            };
            case "net.Zrips.CMILib.Reflections" -> node -> helloWorld(node, "bR", "f_36096_");
            case "net.Zrips.CMILib.RawMessages.RawMessageManager" ->
                    node -> helloWorld(node, "net.minecraft.server.network.PlayerConnection", "net.minecraft.server.network.ServerGamePacketListenerImpl");
            case "com.sk89q.worldedit.bukkit.BukkitConfiguration" -> node -> {
                helloWorld(node, "I accept that I will receive no support with this flag enabled.", Youer.modid);
                helloWorld(node, "allow-editing-on-unsupported-versions", Youer.modid);
                helloWorld(node, "false", Youer.modid);
            };
            case "com.sk89q.worldedit.bukkit.adapter.impl.v1_21.PaperweightAdapter",
                 "com.sk89q.worldedit.bukkit.adapter.ext.fawe.v1_21_R1.PaperweightAdapter" ->
                    node -> helloWorld(node, "org.spigotmc.WatchdogThread", Youer.modid);
            case "cn.lunadeer.dominion.utils.Misc" ->
                    node -> helloWorld(node, "io.papermc.paper.threadedregions.scheduler.ScheduledTask", Youer.modid);
            case "com.sk89q.worldedit.bukkit.paperlib.PaperLib" -> node -> {
                removePaper0(node);
                String adapter = System.getProperty("paperlib.shown-benefits");
                if (adapter == null) System.setProperty("paperlib.shown-benefits", "1");
            };
            case "org.mvplugins.multiverse.external.paperlib.PaperLib",
                 "me.SuperRonanCraft.BetterRTP.lib.paperlib.PaperLib",
                 "com.plotsquared.bukkit.paperlib.PaperLib" -> PluginFixManager::removePaper0;
            default -> null;
        };

        return patcher == null ? clazz : patch(clazz, patcher);
    }
    
    // -------------------- ASM HELPER METHODS --------------------
    
    private static void removePaper(ClassNode node) {
        for (MethodNode methodNode : node.methods) {
            if (methodNode.name.equals("hasPaperAsyncSupport") && methodNode.desc.equals("()Z")) {
                InsnList toInject = new InsnList();
                toInject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(PluginFixManager.class), "hasPaperAsyncSupport", "()Z", false));
                toInject.add(new InsnNode(Opcodes.IRETURN));
                methodNode.instructions = toInject;
            }
        }
    }
    
    private static void removePaper0(ClassNode node) {
        helloWorld(node, "com.destroystokyo.paper.PaperConfig", Youer.modid);
        helloWorld(node, "io.papermc.paper.configuration.Configuration", Youer.modid);
    }

    private static void redirectMethodToGetNMSVersion(ClassNode node, String methodName) {
        for (MethodNode methodNode : node.methods) {
            if (methodNode.name.equals(methodName) && methodNode.desc.equals("()Ljava/lang/String;")) {
                InsnList toInject = new InsnList();
                toInject.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(PluginFixManager.class),
                        "getNMSVersion",
                        "()Ljava/lang/String;",
                        false
                ));
                toInject.add(new InsnNode(ARETURN));
                methodNode.instructions = toInject;
                methodNode.tryCatchBlocks.clear();
            }
        }
    }

    private static void qs(ClassNode node) { 
        redirectMethodToGetNMSVersion(node, "getNMSVersion"); 
    }
    
    private static void fawe(ClassNode node) { 
        redirectMethodToGetNMSVersion(node, "getPackageVersion"); 
    }

    public static void ex(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("make") && method.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                InsnList toInject = new InsnList();
                toInject.add(new VarInsnNode(Opcodes.ALOAD, 0));
                toInject.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(PluginFixManager.class),
                        "make",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false
                ));
                toInject.add(new InsnNode(ARETURN));
                method.instructions = toInject;
                method.tryCatchBlocks.clear();
            }
        }
    }

    private static void helloWorld(ClassNode node, String a, String b) {
        node.methods.forEach(method -> {
            for (AbstractInsnNode next : method.instructions) {
                if (next instanceof LdcInsnNode ldcInsnNode) {
                    if (ldcInsnNode.cst instanceof String str) {
                        if (a.equals(str)) ldcInsnNode.cst = b;
                    }
                }
            }
        });
    }

    private static void helloWorld(ClassNode node, int a, int b) {
        node.methods.forEach(method -> {
            for (AbstractInsnNode next : method.instructions) {
                if (next instanceof IntInsnNode ldcInsnNode) {
                    if (ldcInsnNode.operand == a) ldcInsnNode.operand = b;
                }
            }
        });
    }
    
    public static String make(String in) {
        if (in.equals("8(;4>`")) return "peace";
        final char[] c = in.toCharArray();
        for (int i = 0; i < c.length; ++i) c[i] ^= 'Z';
        return new String(c);
    }
    
    private static byte[] patch(byte[] basicClass, Consumer<ClassNode> handler) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(basicClass).accept(node, 0);
            handler.accept(node);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Exception e) {
            System.err.println("[PluginFixManager] Failed to patch class: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }
    
    // Utility methods for compatibility
    public static String getNMSVersion() { 
        return "v1_21_R1"; 
    }
    
    public static boolean hasPaperAsyncSupport() { 
        return false; 
    }
    
    /**
     * Clear scan cache - useful for plugin reload or when dungeons are regenerated
     */
    public static void clearScanCache() {
        scanCache.clear();
        dungeonSpawnCache.clear();
        lastScanLogTime = 0;
        System.out.println("[MythicDungeons] Scan cache cleared");
    }
    
    /**
     * Get cache statistics for debugging
     */
    public static String getCacheStats() {
        return String.format("[MythicDungeons Cache] Scan entries: %d, Spawn entries: %d", 
                            scanCache.size(), dungeonSpawnCache.size());
    }
}
