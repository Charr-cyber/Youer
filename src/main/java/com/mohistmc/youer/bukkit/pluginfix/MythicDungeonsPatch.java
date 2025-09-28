package com.mohistmc.youer.bukkit.pluginfix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * MythicDungeons Procedural Dungeon Teleportation Fix
 * Version 6.0 - Complete rewrite for Youer compatibility
 */
public class MythicDungeonsPatch {
    
    // Cache for dungeon spawn locations
    private static final Map<String, Location> spawnCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> scanTimestamps = new ConcurrentHashMap<>();
    
    /**
     * Main patch method - replaces forceTeleport with working implementation
     */
    public static void patchForceTeleport(ClassNode node) {
        System.out.println("[MythicDungeons] Patching forceTeleport method for Youer compatibility");
        
        for (MethodNode method : node.methods) {
            if (method.name.equals("forceTeleport") && 
                method.desc.equals("(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)V")) {
                
                System.out.println("[MythicDungeons] Found forceTeleport method, replacing...");
                
                // Replace entire method body
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                
                InsnList newCode = new InsnList();
                
                // Call our enhanced teleport method
                newCode.add(new VarInsnNode(Opcodes.ALOAD, 0)); // Entity
                newCode.add(new VarInsnNode(Opcodes.ALOAD, 1)); // Location
                newCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MythicDungeonsPatch.class),
                    "enhancedTeleport",
                    "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)V",
                    false));
                newCode.add(new InsnNode(Opcodes.RETURN));
                
                method.instructions = newCode;
                System.out.println("[MythicDungeons] forceTeleport method patched successfully");
            }
        }
    }
    
    /**
     * Enhanced teleport that works with procedural dungeons
     */
    public static void enhancedTeleport(Entity entity, Location target) {
        if (entity == null || target == null) return;
        
        World world = target.getWorld();
        if (world == null) return;
        
        // Check if this is a dungeon world
        String worldName = world.getName().toLowerCase();
        if (worldName.contains("dungeon") || worldName.contains("deneme") || worldName.contains("_")) {
            System.out.println("[MythicDungeons] Detected dungeon world: " + world.getName());
            
            // Try to get the actual spawn location
            Location actualSpawn = getProceduralDungeonSpawn(world, target);
            if (actualSpawn != null) {
                target = actualSpawn;
                System.out.println("[MythicDungeons] Found procedural dungeon spawn at: " + 
                    actualSpawn.getBlockX() + ", " + actualSpawn.getBlockY() + ", " + actualSpawn.getBlockZ());
            }
        }
        
        // Ensure chunks are loaded
        loadChunksAround(target);
        
        // Perform the teleport
        final Location finalTarget = target;
        if (Bukkit.isPrimaryThread()) {
            entity.teleport(finalTarget);
        } else {
            Bukkit.getScheduler().runTask(getPlugin(), () -> entity.teleport(finalTarget));
        }
    }
    
    /**
     * Get the actual spawn location for a procedural dungeon
     */
    private static Location getProceduralDungeonSpawn(World world, Location fallback) {
        String cacheKey = world.getName();
        
        // Check cache first
        if (spawnCache.containsKey(cacheKey)) {
            return spawnCache.get(cacheKey).clone();
        }
        
        try {
            // Method 1: Try via InstanceProcedural
            Location spawn = getSpawnViaInstance(world);
            if (spawn != null) {
                spawnCache.put(cacheKey, spawn);
                return spawn;
            }
            
            // Method 2: Try via Layout.getFirst().getSpawn()
            spawn = getSpawnViaLayout(world);
            if (spawn != null) {
                spawnCache.put(cacheKey, spawn);
                return spawn;
            }
            
            // Method 3: Find spawn by scanning for rooms
            spawn = findSpawnByScanning(world, fallback);
            if (spawn != null) {
                spawnCache.put(cacheKey, spawn);
                return spawn;
            }
            
        } catch (Exception e) {
            System.err.println("[MythicDungeons] Error getting spawn: " + e.getMessage());
        }
        
        // Fallback: Create a safe spawn point
        return createSafeSpawn(world, fallback);
    }
    
    /**
     * Method 1: Get spawn via InstanceProcedural.startLoc
     */
    private static Location getSpawnViaInstance(World world) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
            if (plugin == null) return null;
            
            // Get instance manager
            Method getInstanceManager = plugin.getClass().getDeclaredMethod("getInstanceManager");
            getInstanceManager.setAccessible(true);
            Object instanceManager = getInstanceManager.invoke(plugin);
            if (instanceManager == null) return null;
            
            // Get instance by world
            Method getInstanceByWorld = instanceManager.getClass().getDeclaredMethod("getInstanceByWorld", World.class);
            getInstanceByWorld.setAccessible(true);
            Object instance = getInstanceByWorld.invoke(instanceManager, world);
            if (instance == null) return null;
            
            // Check if it's InstanceProcedural
            if (instance.getClass().getSimpleName().contains("InstanceProcedural")) {
                // Get startLoc field
                Field startLocField = instance.getClass().getDeclaredField("startLoc");
                startLocField.setAccessible(true);
                Object startLoc = startLocField.get(instance);
                
                if (startLoc instanceof Location) {
                    Location spawn = (Location) startLoc;
                    if (spawn.getY() > 10 && spawn.getY() < 100) { // Sanity check
                        return spawn;
                    }
                }
                
                // Try calling prepValidStartPoint if startLoc is null
                if (startLoc == null) {
                    Method prepMethod = instance.getClass().getDeclaredMethod("prepValidStartPoint");
                    prepMethod.setAccessible(true);
                    prepMethod.invoke(instance);
                    
                    // Try getting startLoc again
                    startLoc = startLocField.get(instance);
                    if (startLoc instanceof Location) {
                        return (Location) startLoc;
                    }
                }
            }
            
        } catch (Exception e) {
            // Silent fail, try next method
        }
        
        return null;
    }
    
    /**
     * Method 2: Get spawn via Layout
     */
    private static Location getSpawnViaLayout(World world) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
            if (plugin == null) return null;
            
            // Get instance
            Object instance = getInstanceForWorld(world);
            if (instance == null) return null;
            
            // Get builder/layout field
            Field builderField = null;
            for (Field field : instance.getClass().getDeclaredFields()) {
                if (field.getType().getSimpleName().contains("Layout")) {
                    builderField = field;
                    break;
                }
            }
            
            if (builderField != null) {
                builderField.setAccessible(true);
                Object layout = builderField.get(instance);
                
                if (layout != null) {
                    // Call getFirst()
                    Method getFirst = layout.getClass().getDeclaredMethod("getFirst");
                    getFirst.setAccessible(true);
                    Object firstRoom = getFirst.invoke(layout);
                    
                    if (firstRoom != null) {
                        // Call getSpawn()
                        Method getSpawn = firstRoom.getClass().getDeclaredMethod("getSpawn");
                        getSpawn.setAccessible(true);
                        Object spawn = getSpawn.invoke(firstRoom);
                        
                        if (spawn instanceof Location) {
                            Location loc = (Location) spawn;
                            loc.setWorld(world); // Ensure correct world
                            return loc;
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            // Silent fail, try next method
        }
        
        return null;
    }
    
    /**
     * Method 3: Find spawn by scanning world
     */
    private static Location findSpawnByScanning(World world, Location hint) {
        String scanKey = world.getName() + ":scan";
        Long lastScan = scanTimestamps.get(scanKey);
        
        // Don't scan too frequently
        if (lastScan != null && (System.currentTimeMillis() - lastScan) < 5000) {
            return null;
        }
        
        scanTimestamps.put(scanKey, System.currentTimeMillis());
        
        int centerX = hint != null ? hint.getBlockX() : 0;
        int centerZ = hint != null ? hint.getBlockZ() : 0;
        
        // Scan for dungeon rooms (typically they have specific patterns)
        for (int y = 60; y >= 20; y -= 5) {
            for (int x = centerX - 20; x <= centerX + 20; x += 5) {
                for (int z = centerZ - 20; z <= centerZ + 20; z += 5) {
                    if (isDungeonRoom(world, x, y, z)) {
                        return new Location(world, x + 0.5, y + 1, z + 0.5);
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Check if location is a dungeon room
     */
    private static boolean isDungeonRoom(World world, int x, int y, int z) {
        // Check for floor
        if (world.getBlockAt(x, y, z).getType() == Material.AIR) {
            return false;
        }
        
        // Check for air space above
        int airCount = 0;
        for (int dy = 1; dy <= 4; dy++) {
            if (world.getBlockAt(x, y + dy, z).getType() == Material.AIR) {
                airCount++;
            }
        }
        
        // Check surrounding area
        if (airCount >= 3) {
            int roomBlocks = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (!world.getBlockAt(x + dx, y, z + dz).getType().isAir()) {
                        roomBlocks++;
                    }
                }
            }
            
            // If we have a decent floor area, it's likely a room
            return roomBlocks >= 20;
        }
        
        return false;
    }
    
    /**
     * Create a safe spawn point
     */
    private static Location createSafeSpawn(World world, Location hint) {
        int x = hint != null ? hint.getBlockX() : 0;
        int z = hint != null ? hint.getBlockZ() : 0;
        int y = 50; // Default dungeon height
        
        // Find ground level
        for (int testY = 70; testY >= 20; testY--) {
            if (!world.getBlockAt(x, testY, z).getType().isAir()) {
                y = testY + 1;
                break;
            }
        }
        
        // Create platform if needed
        if (world.getBlockAt(x, y - 1, z).getType().isAir()) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    world.getBlockAt(x + dx, y - 1, z + dz).setType(Material.STONE);
                    // Clear space above
                    for (int dy = 0; dy <= 3; dy++) {
                        world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                    }
                }
            }
            
            // Add torches for light
            world.getBlockAt(x + 2, y, z + 2).setType(Material.TORCH);
            world.getBlockAt(x - 2, y, z + 2).setType(Material.TORCH);
            world.getBlockAt(x + 2, y, z - 2).setType(Material.TORCH);
            world.getBlockAt(x - 2, y, z - 2).setType(Material.TORCH);
            
            System.out.println("[MythicDungeons] Created emergency platform at " + x + ", " + y + ", " + z);
        }
        
        return new Location(world, x + 0.5, y, z + 0.5);
    }
    
    /**
     * Load chunks around a location
     */
    private static void loadChunksAround(Location loc) {
        World world = loc.getWorld();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (!world.isChunkLoaded(chunkX + x, chunkZ + z)) {
                    world.loadChunk(chunkX + x, chunkZ + z, true);
                }
            }
        }
    }
    
    /**
     * Get instance for a world
     */
    private static Object getInstanceForWorld(World world) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
            if (plugin == null) return null;
            
            Method getInstanceManager = plugin.getClass().getDeclaredMethod("getInstanceManager");
            getInstanceManager.setAccessible(true);
            Object instanceManager = getInstanceManager.invoke(plugin);
            
            if (instanceManager != null) {
                Method getInstanceByWorld = instanceManager.getClass().getDeclaredMethod("getInstanceByWorld", World.class);
                getInstanceByWorld.setAccessible(true);
                return getInstanceByWorld.invoke(instanceManager, world);
            }
        } catch (Exception e) {
            // Silent fail
        }
        return null;
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
    
    /**
     * Clear cache for a world
     */
    public static void clearCache(String worldName) {
        spawnCache.remove(worldName);
        scanTimestamps.remove(worldName + ":scan");
    }
    
    /**
     * Apply the patch to a class
     */
    public static byte[] applyPatch(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            
            patchForceTeleport(node);
            
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);
            return writer.toByteArray();
            
        } catch (Exception e) {
            System.err.println("[MythicDungeons] Failed to apply patch: " + e.getMessage());
            return classBytes;
        }
    }
}
