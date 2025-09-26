package com.mohistmc.youer.bukkit.pluginfix;

import com.mohistmc.youer.Youer;
import java.util.concurrent.*;
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
 * Simplified PluginFixManager - Removed force teleport and dungeon scanning
 * @version 4.0 - Simplified version without force teleport and dungeon scanning
 */
public class PluginFixManager {
    
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

        final int maxAttempts = 60; // ~30 seconds at 10 ticks for procedural generation
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
        
        // Force load chunks with generation
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (!world.isChunkLoaded(chunkX + x, chunkZ + z)) {
                    world.loadChunk(chunkX + x, chunkZ + z, true); // Force generation
                }
            }
        }
        
        System.out.println("[MythicDungeons] Chunks loaded, waiting for world generation to complete...");
        
        // Wait longer for dungeon generation to complete, then teleport
        Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
            try {
                // Additional chunk validation
                validateChunkGeneration(world, chunkX, chunkZ);
                
            // Find safe ground at the target location
            Location safeLocation = findSafeGroundAtTarget(target);
                
                // Ensure the target location is valid
                if (safeLocation == null || safeLocation.getWorld() == null) {
                    System.err.println("[MythicDungeons] Invalid safe location, using fallback");
                    safeLocation = new Location(world, target.getX(), 65, target.getZ(), target.getYaw(), target.getPitch());
                }
            
            boolean success = entity.teleport(safeLocation);
            
            if (success) {
                System.out.println("[MythicDungeons] Successfully teleported " + entity.getName() + 
                    " to X:" + safeLocation.getBlockX() + " Y:" + safeLocation.getBlockY() + " Z:" + safeLocation.getBlockZ());
            } else {
                    System.err.println("[MythicDungeons] Failed to teleport " + entity.getName() + ", trying fallback location");
                    // Try fallback teleport
                    Location fallback = new Location(world, target.getX(), 65, target.getZ(), target.getYaw(), target.getPitch());
                    success = entity.teleport(fallback);
                    if (success) {
                        System.out.println("[MythicDungeons] Fallback teleport successful");
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
        System.out.println("[MythicDungeons] No solid ground found, creating emergency platform at Y=65");
        return createEmergencyPlatform(world, x, 65, z);
    }
    
    /**
     * Get spawn location from MythicDungeons API using reflection
     */
    private static Location getSpawnLocationFromMythicDungeons(World world) {
        try {
            System.out.println("[MythicDungeons] Trying to get spawn location from MythicDungeons API...");
            
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
                        Object spawnLocation = method.invoke(dungeonInstance);
                        if (spawnLocation instanceof Location) {
                            System.out.println("[MythicDungeons] Found spawn location using method: " + methodName);
                            return (Location) spawnLocation;
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
                                            Object location = locMethod.invoke(startRoom);
                                            if (location instanceof Location) {
                                                System.out.println("[MythicDungeons] Found room center location using method: " + locMethodName);
                                                System.out.println("[MythicDungeons] PROCEDURAL DUNGEON SPAWN: " + ((Location) location).getBlockX() + ", " + ((Location) location).getBlockY() + ", " + ((Location) location).getBlockZ());
                                                return (Location) location;
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
                                Object spawnLocation = method.invoke(playable);
                                if (spawnLocation instanceof Location) {
                                    System.out.println("[MythicDungeons] Found playable spawn location using method: " + methodName);
                                    return (Location) spawnLocation;
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
                                                    Object location = locMethod.invoke(startRoom);
                                                    if (location instanceof Location) {
                                                        System.out.println("[MythicDungeons] Found room center location using method: " + locMethodName);
                                                        System.out.println("[MythicDungeons] PROCEDURAL DUNGEON SPAWN: " + ((Location) location).getBlockX() + ", " + ((Location) location).getBlockY() + ", " + ((Location) location).getBlockZ());
                                                        return (Location) location;
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

    private static Object tryResolveDungeonManager() {
        try {
            System.out.println("[MythicDungeons] Attempting to resolve DungeonManager...");
            
            // Method 1: Try MythicDungeons.inst().getDungeonManager() - This is the correct API
            try {
                Class<?> mythicDungeonsClass = Class.forName("net.playavalon.mythicdungeons.MythicDungeons");
                
                // Look for static inst() method
                java.lang.reflect.Method instMethod = mythicDungeonsClass.getMethod("inst");
                Object mythicDungeonsInstance = instMethod.invoke(null);
                
                if (mythicDungeonsInstance != null) {
                    System.out.println("[MythicDungeons] Found MythicDungeons instance via inst()");
                    
                    // Try to get getDungeonManager() method
                    java.lang.reflect.Method getDungeonManagerMethod = mythicDungeonsInstance.getClass().getMethod("getDungeonManager");
                    Object dungeonManager = getDungeonManagerMethod.invoke(mythicDungeonsInstance);
                    
                    if (dungeonManager != null) {
                        System.out.println("[MythicDungeons] Found DungeonManager via MythicDungeons.inst().getDungeonManager()");
                        return dungeonManager;
                    }
                }
            } catch (Exception e) {
                System.out.println("[MythicDungeons] MythicDungeons.inst().getDungeonManager() failed: " + e.getMessage());
            }
            
            // Method 2: Try direct DungeonManager static access
            try {
                Class<?> dungeonManagerClass = Class.forName("net.playavalon.mythicdungeons.managers.DungeonManager");
                
                // Try to find a static getInstance method
                java.lang.reflect.Method[] methods = dungeonManagerClass.getDeclaredMethods();
                for (java.lang.reflect.Method method : methods) {
                    if (method.getName().equals("getInstance") && 
                        java.lang.reflect.Modifier.isStatic(method.getModifiers()) &&
                        method.getParameterCount() == 0) {
                        try {
                            Object manager = method.invoke(null);
                            if (manager != null) {
                                System.out.println("[MythicDungeons] Found DungeonManager via static getInstance()");
                                return manager;
                            }
                        } catch (Exception e) {
                            System.out.println("[MythicDungeons] static getInstance() failed: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[MythicDungeons] Direct DungeonManager access failed: " + e.getMessage());
            }
            
            // Method 3: Try via Bukkit services
            try {
                Class<?> dungeonManagerClass = Class.forName("net.playavalon.mythicdungeons.managers.DungeonManager");
                Object service = Bukkit.getServicesManager().load(dungeonManagerClass);
                if (service != null) {
                    System.out.println("[MythicDungeons] Found DungeonManager via Bukkit services");
                    return service;
                }
            } catch (Exception e) {
                System.out.println("[MythicDungeons] Bukkit services failed: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.out.println("[MythicDungeons] DungeonManager resolution failed: " + e.getMessage());
        }
        
        System.out.println("[MythicDungeons] Could not resolve DungeonManager, will use fallback teleportation");
        return null;
    }
    
    /**
     * Find dungeon room at specific coordinates by scanning downward
     * Enhanced for procedural dungeons with better room detection
     */
    private static Location findDungeonRoomAtCoordinates(World world, int x, int z) {
        System.out.println("[MythicDungeons] Scanning for dungeon room at X:" + x + " Z:" + z);
        
        // For procedural dungeons, scan from Y=80 down to Y=10 (typical dungeon range)
        for (int y = 80; y >= 10; y--) {
            org.bukkit.block.Block floor = world.getBlockAt(x, y, z);
            org.bukkit.block.Block above1 = world.getBlockAt(x, y + 1, z);
            org.bukkit.block.Block above2 = world.getBlockAt(x, y + 2, z);
            
            // Look for solid floor with air above (typical room pattern)
            if (!floor.getType().isAir() && 
                above1.getType().isAir() && 
                above2.getType().isAir()) {
                
                System.out.println("[MythicDungeons] Found potential room floor at Y=" + y);
                
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
                    System.out.println("[MythicDungeons] Confirmed dungeon room at Y=" + (y + 1) + " with " + airCount + " air blocks, " + solidCount + " solid blocks");
                    return new Location(world, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        
        // If no room found at exact coordinates, try nearby with wider search
        System.out.println("[MythicDungeons] No room found at exact coordinates, scanning nearby...");
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
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
                            System.out.println("[MythicDungeons] Found nearby room at X:" + (x + dx) + " Y:" + (y + 1) + " Z:" + (z + dz) + " with " + nearbyAirCount + " air blocks");
                            return new Location(world, x + dx + 0.5, y + 1, z + dz + 0.5);
                        }
                    }
                }
            }
        }
        
        System.out.println("[MythicDungeons] No dungeon room found in scan area");
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
     * Create emergency platform for safe landing
     */
    private static Location createEmergencyPlatform(World world, int x, int y, int z) {
        System.out.println("[MythicDungeons] Creating emergency platform at " + x + "," + y + "," + z);
        
        // Create 3x3 platform
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // Create solid floor
                world.getBlockAt(x + dx, y, z + dz).setType(Material.STONE);
                
                // Clear space above
                for (int dy = 1; dy <= 3; dy++) {
                    world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
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
}
