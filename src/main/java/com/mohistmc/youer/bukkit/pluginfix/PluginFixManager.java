package com.mohistmc.youer.bukkit.pluginfix;

import com.mohistmc.youer.Youer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ARETURN;

public class PluginFixManager {

    // -------------------- TELEPORT + STRUCTURE GENERATION + ENHANCED VERSION -------------------

    /**
     * Procedural dungeon için generation-aware teleporte
     */
    public static void teleportEntityToProceduralDungeon(Entity entity, Location target, Object instanceObj) {
        if (entity == null || target == null) return;
        
        if (!(entity instanceof Player player)) {
            return;
        }

        System.out.println("[MythicDungeons Debug] Starting PROCEDURAL teleport for " + player.getName() + 
                          " to " + target.getWorld().getName() + " (waiting for generation)");

        // PROCEDURAL DUNGEON: Generation tamamlanana kadar bekle
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                () -> {
                    // Generation check loop - procedural dungeon'lar için kritik!
                    checkGenerationAndTeleport(player, target, instanceObj, 0);
                }
        );
    }
    
    /**
     * Generation check ile recursive teleport attempt
     */
    private static void checkGenerationAndTeleport(Player player, Location target, Object instanceObj, int attempts) {
        final int MAX_ATTEMPTS = 100; // 50 saniye max (500ms * 100)
        
        if (attempts >= MAX_ATTEMPTS) {
            System.err.println("[MythicDungeons Patch] Generation timeout for " + player.getName() + 
                              " after " + (MAX_ATTEMPTS * 500) + "ms");
            // Timeout olsa bile teleport dene
            doFinalTeleport(player, target);
            return;
        }
        
        try {
            // Instance generation durumunu reflection ile check et
            boolean isReady = checkInstanceReady(instanceObj);
            
            if (isReady) {
                System.out.println("[MythicDungeons Debug] Generation complete after " + attempts + " attempts, teleporting " + player.getName());
                doFinalTeleport(player, target);
            } else {
                // Henüz hazır değil, 500ms bekle ve tekrar dene
                System.out.println("[MythicDungeons Debug] Generation not ready (attempt " + (attempts + 1) + "/" + MAX_ATTEMPTS + "), waiting...");
                
                Bukkit.getScheduler().runTaskLater(
                    Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                    () -> checkGenerationAndTeleport(player, target, instanceObj, attempts + 1),
                    10L // 500ms delay
                );
            }
        } catch (Exception e) {
            System.err.println("[MythicDungeons Patch] Error checking generation status: " + e.getMessage());
            // Hata durumunda normal teleport yap
            doFinalTeleport(player, target);
        }
    }
    
    /**
     * Instance'ın generation durumunu check et
     */
    private static boolean checkInstanceReady(Object instanceObj) {
        if (instanceObj == null) return true; // Instance yoksa teleport et
        
        try {
            // Reflection ile instance durumu check et
            Class<?> instanceClass = instanceObj.getClass();
            
            // Yaygın method isimleri dene
            String[] checkMethods = {"isReady", "isGenerated", "isComplete", "isFinished", "hasGenerated"};
            
            for (String methodName : checkMethods) {
                try {
                    java.lang.reflect.Method method = instanceClass.getMethod(methodName);
                    Object result = method.invoke(instanceObj);
                    if (result instanceof Boolean) {
                        boolean ready = (Boolean) result;
                        System.out.println("[MythicDungeons Debug] Instance." + methodName + "() = " + ready);
                        if (!ready) return false; // Herhangi biri false ise bekle
                    }
                } catch (NoSuchMethodException ignored) {
                    // Method yoksa devam et
                }
            }
            
            return true; // Tüm check'ler geçtiyse veya method bulunamadıysa ready
            
        } catch (Exception e) {
            System.err.println("[MythicDungeons Debug] Reflection error: " + e.getMessage());
            return true; // Hata durumunda teleport et
        }
    }
    
    /**
     * Final teleport işlemi
     */
    private static void doFinalTeleport(Player player, Location target) {
        try {
            // Chunk'ları hazırla
            if (!target.getChunk().isLoaded()) {
                System.out.println("[MythicDungeons Debug] Loading target chunk for final teleport...");
                target.getChunk().load();
            }
            
            preloadSurroundingChunks(target, 2);
            
            // Final teleport
            boolean success = player.teleport(target);
            
            if (success) {
                System.out.println("[MythicDungeons Patch] PROCEDURAL teleport SUCCESS for " + player.getName() + 
                    " to " + target.getWorld().getName() + " " + 
                    target.getX() + "," + target.getY() + "," + target.getZ());
            } else {
                System.err.println("[MythicDungeons Patch] PROCEDURAL teleport FAILED for " + player.getName());
            }
        } catch (Exception e) {
            System.err.println("[MythicDungeons Patch] Final teleport error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Oyuncuyu dungeon içine güvenli şekilde teleport eder ve yapı oluşturmayı bekler.
     */
    public static void teleportEntityToDungeon(Entity entity, Location target) {
        if (entity == null || target == null) return;
        
        if (!(entity instanceof Player player)) {
            return;
        }

        System.out.println("[MythicDungeons Debug] Starting teleport for " + player.getName() + 
                          " to " + target.getWorld().getName());

        // YOUER OPTIMIZE: Chunk'ı önceden yükle ve yapı oluşturmayı bekle
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                () -> {
                    try {
                        // 1. Target chunk'ı force load et
                        if (!target.getChunk().isLoaded()) {
                            System.out.println("[MythicDungeons Debug] Loading target chunk...");
                            target.getChunk().load();
                        }
                        
                        // 2. Çevredeki chunk'ları da önceden yükle (structure için)
                        preloadSurroundingChunks(target, 3); // Radius artırıldı
                        
                        // 3. Yapı oluşturma için daha uzun delay
                        Bukkit.getScheduler().runTaskLater(
                            Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                            () -> {
                                try {
                                    // 4. Final chunk check
                                    if (!target.getChunk().isLoaded()) {
                                        target.getChunk().load();
                                    }
                                    
                                    // 5. Güvenli teleport
                                    boolean success = player.teleport(target);
                                    
                                    if (success) {
                                        System.out.println("[MythicDungeons Patch] Successfully teleported " + player.getName() + 
                                            " to dungeon at " + target.getWorld().getName() + " " + 
                                            target.getX() + "," + target.getY() + "," + target.getZ());
                                    } else {
                                        System.err.println("[MythicDungeons Patch] Teleport returned false for " + player.getName());
                                    }
                                } catch (Exception e) {
                                    System.err.println("[MythicDungeons Patch] Teleport execution failed: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            }, 
                            10L // 10 tick delay (500ms) - yapı oluşturma için daha uzun
                        );
                        
                    } catch (Exception e) {
                        System.err.println("[MythicDungeons Patch] Teleport setup failed for player " + player.getName());
                        e.printStackTrace();
                    }
                }
        );
    }
    
    /**
     * Çevredeki chunk'ları önceden yükler (yapı generation için)
     */
    private static void preloadSurroundingChunks(Location center, int radius) {
        int centerChunkX = center.getChunk().getX();
        int centerChunkZ = center.getChunk().getZ();
        int loadedCount = 0;
        
        System.out.println("[MythicDungeons Debug] Preloading chunks around " + 
                          centerChunkX + "," + centerChunkZ + " with radius " + radius);
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = centerChunkX + x;
                int chunkZ = centerChunkZ + z;
                
                // Sync chunk loading - daha güvenli
                if (!center.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                    center.getWorld().loadChunk(chunkX, chunkZ);
                    loadedCount++;
                }
            }
        }
        
        System.out.println("[MythicDungeons Debug] Preloaded " + loadedCount + " chunks");
    }

    /**
     * teleportAsync için replacement
     */
    public static CompletableFuture<Boolean> teleportAsyncReplacement(Entity entity, Location location) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (!(entity instanceof Player)) {
            future.complete(false);
            return future;
        }
        
        Bukkit.getScheduler().runTask(
            Bukkit.getPluginManager().getPlugin("MythicDungeons"),
            () -> {
                try {
                    teleportEntityToDungeon(entity, location);
                    future.complete(true);
                } catch (Exception e) {
                    future.complete(false);
                }
            }
        );
        
        return future;
    }

    /**
     * Layout generation timeout'larını ve async calls'ları düzelt
     */
    public static void patchLayoutGeneration(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching Layout generation class...");
        
        for (MethodNode method : node.methods) {
            // Generation method'larını patch'le
            if (method.name.equals("generate") || method.name.equals("build") || 
                method.name.equals("paste") || method.name.contains("async")) {
                
                System.out.println("[MythicDungeons Debug] Patching method: " + method.name + method.desc);
                
                // Timeout değerlerini artır
                patchTimeoutValues(method);
                
                // Async calls'ları sync'e çevir
                patchAsyncCalls(method);
                
                // Debug logging ekle
                addMethodDebugLogging(method, "Layout." + method.name);
            }
        }
    }
    
    /**
     * Timeout değerlerini artırır
     */
    private static void patchTimeoutValues(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            // LDC constant pool'dan timeout değerleri
            if (insn instanceof LdcInsnNode ldcInsn) {
                if (ldcInsn.cst instanceof Long timeout) {
                    if (timeout >= 1000L && timeout <= 10000L) { // 1-10 saniye arası
                        long oldValue = timeout;
                        ldcInsn.cst = timeout * 5; // 5x artır
                        System.out.println("[MythicDungeons Patch] Increased timeout from " + oldValue + "ms to " + ldcInsn.cst + "ms");
                    }
                }
                if (ldcInsn.cst instanceof Integer timeout) {
                    if (timeout >= 1 && timeout <= 10) { // 1-10 saniye arası
                        int oldValue = timeout;
                        ldcInsn.cst = timeout * 5; // 5x artır
                        System.out.println("[MythicDungeons Patch] Increased timeout from " + oldValue + "s to " + ldcInsn.cst + "s");
                    }
                }
            }
            
            // Integer instructions
            if (insn instanceof IntInsnNode intInsn) {
                if (intInsn.operand >= 1 && intInsn.operand <= 30) {
                    int oldValue = intInsn.operand;
                    intInsn.operand *= 5;
                    System.out.println("[MythicDungeons Patch] Increased int timeout from " + oldValue + " to " + intInsn.operand);
                }
            }
        }
    }
    
    /**
     * Async method calls'ları sync'e çevirir
     */
    private static void patchAsyncCalls(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                // Scheduler async calls
                if (mInsn.name.equals("runTaskAsynchronously")) {
                    mInsn.name = "runTask";
                    System.out.println("[MythicDungeons Patch] Converted runTaskAsynchronously to runTask");
                }
                
                // CompletableFuture async calls
                if (mInsn.owner.equals("java/util/concurrent/CompletableFuture")) {
                    if (mInsn.name.equals("supplyAsync")) {
                        System.out.println("[MythicDungeons Debug] Found CompletableFuture.supplyAsync in " + method.name);
                        // Burada daha karmaşık patch yapılabilir
                    }
                }
                
                // ExecutorService calls
                if (mInsn.owner.contains("ExecutorService") && mInsn.name.equals("submit")) {
                    System.out.println("[MythicDungeons Debug] Found ExecutorService.submit in " + method.name);
                }
                
                // Chunk async loading
                if (mInsn.owner.equals("org/bukkit/World")) {
                    if (mInsn.name.equals("getChunkAtAsync")) {
                        mInsn.name = "getChunkAt";
                        mInsn.desc = "(II)Lorg/bukkit/Chunk;";
                        System.out.println("[MythicDungeons Patch] Converted getChunkAtAsync to sync getChunkAt");
                    }
                    if (mInsn.name.equals("loadChunkAsync")) {
                        mInsn.name = "loadChunk";
                        mInsn.desc = "(II)Z";
                        System.out.println("[MythicDungeons Patch] Converted loadChunkAsync to sync loadChunk");
                    }
                }
            }
        }
    }

    /**
     * Method başına debug logging ekler
     */
    private static void addMethodDebugLogging(MethodNode method, String methodName) {
        InsnList debugCode = new InsnList();
        
        // System.out.println("[MythicDungeons Debug] Executing " + methodName);
        debugCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        debugCode.add(new LdcInsnNode("[MythicDungeons Debug] Executing " + methodName));
        debugCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        method.instructions.insert(debugCode);
    }

    /**
     * ASM patch: MythicDungeons Util sınıfındaki teleport metodlarını patch'ler.
     */
    public static void patchDungeonTeleport(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching Util class methods...");
        boolean patchedAny = false;
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found method: " + method.name + method.desc);
            
            // forceTeleport2 metodunu patch'le
            if (method.name.equals("forceTeleport2") && 
                method.desc.equals("(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)V")) {
                
                patchMethod(method, "forceTeleport2");
                patchedAny = true;
            }
            
            // forceTeleport metodunu da patch'le (varsa)
            else if (method.name.equals("forceTeleport") && 
                     method.desc.contains("Lorg/bukkit/entity/Entity;") &&
                     method.desc.contains("Lorg/bukkit/Location;")) {
                
                patchMethod(method, "forceTeleport");
                patchedAny = true;
            }
            
            // teleportAsync çağrılarını patch'le
            patchTeleportAsyncCalls(method);
        }
        
        if (patchedAny) {
            System.out.println("[MythicDungeons Patch] Successfully patched Util teleport methods");
        } else {
            System.out.println("[MythicDungeons Debug] No teleport methods found to patch in Util class");
        }
    }
    
    /**
     * Method'u tamamen replacement ile patch'ler
     */
    private static void patchMethod(MethodNode method, String methodName) {
        System.out.println("[MythicDungeons Debug] Completely replacing method: " + methodName);
        
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        
        InsnList newCode = new InsnList();
        
        // Parametreleri stack'e yükle
        newCode.add(new VarInsnNode(Opcodes.ALOAD, 0)); // Entity entity
        newCode.add(new VarInsnNode(Opcodes.ALOAD, 1)); // Location location
        
        // Kendi metodumuzu çağır
        newCode.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(PluginFixManager.class),
            "teleportEntityToDungeon",
            "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)V",
            false
        ));
        
        newCode.add(new InsnNode(Opcodes.RETURN));
        method.instructions = newCode;
        
        System.out.println("[MythicDungeons Patch] Successfully patched method: " + methodName);
    }
    
    /**
     * Method içindeki teleportAsync çağrılarını sync teleport ile değiştirir
     */
    private static void patchTeleportAsyncCalls(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("teleportAsync") && 
                    (mInsn.owner.equals("org/bukkit/entity/Entity") || 
                     mInsn.owner.equals("org/bukkit/entity/Player"))) {
                    
                    mInsn.name = "teleport";
                    mInsn.desc = "(Lorg/bukkit/Location;)Z";
                    
                    System.out.println("[MythicDungeons Patch] Replaced teleportAsync call with teleport");
                }
            }
        }
    }

    // -------------------- PLUGIN PATCH --------------------

    public static byte[] injectPluginFix(String plugin, String className, byte[] clazz) {
        if (plugin.equals("WorldEdit")) {
            String adapter = System.getProperty("worldedit.bukkit.adapter");
            if (adapter == null) {
                System.setProperty("worldedit.bukkit.adapter", "com.sk89q.worldedit.bukkit.adapter.impl.v1_21.PaperweightAdapter");
            }
        }

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
            case "com.onarandombox.MultiverseCore.utils.WorldManager" -> {
                return patch(clazz, MultiverseCore::fix);
            }
            // MythicDungeons patch - TELEPORT FIX (DOĞRU CLASS PATH)
            case "net.playavalon.mythicdungeons.utility.helpers.Util" -> {
                System.out.println("[MythicDungeons Patch] Patching Util class for teleport fixes...");
                return patch(clazz, PluginFixManager::patchDungeonTeleport);
            }
            // MythicDungeons patch - LAYOUT GENERATION FIX (DÜZELTILMIŞ PATH)
            case "net.playavalon.mythicdungeons.dungeons.layout.Layout" -> {
                System.out.println("[MythicDungeons Patch] Patching Layout generation...");
                return patch(clazz, PluginFixManager::patchLayoutGeneration);
            }
            // MythicDungeons patch - ASYNC GENERATION FIX
            case "net.playavalon.mythicdungeons.api.generation.layout.LayoutBranching",
                 "net.playavalon.mythicdungeons.api.generation.layout.LayoutMinecrafty" -> {
                System.out.println("[MythicDungeons Patch] Patching async generation...");
                return patch(clazz, PluginFixManager::patchLayoutGeneration);
            }
            
            // MythicDungeons procedural dungeon patches - ÖNEMLI!
            case "net.playavalon.mythicdungeons.api.parents.instances.InstancePlayable" -> {
                System.out.println("[MythicDungeons Patch] Patching procedural InstancePlayable...");
                return patch(clazz, PluginFixManager::patchProceduralInstance);
            }
            
            case "net.playavalon.mythicdungeons.api.parents.instances.InstanceClassic" -> {
                System.out.println("[MythicDungeons Patch] Patching classic InstanceClassic (for comparison)...");
                return patch(clazz, PluginFixManager::patchClassicInstance);
            }
            
            // Procedural generation engine
            case "net.playavalon.mythicdungeons.dungeons.procedural.ProceduralDungeon" -> {
                System.out.println("[MythicDungeons Patch] Patching ProceduralDungeon generation...");
                return patch(clazz, PluginFixManager::patchProceduralGeneration);
            }
        }

        // CMI patch'leri
        if (className.startsWith("net.Zrips.CMILib.") || className.startsWith("com.Zrips.CMI.")) {
            return patch(clazz, node -> helloWorld(node, "net.minecraft.server.network.PlayerConnection", "net.minecraft.server.network.ServerGamePacketListenerImpl"));
        }

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

    // -------------------- ASM HELPER --------------------

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
    }
    
    /**
     * Procedural Instance'larda teleport sorunlarını düzelt
     * Procedural dungeon'larda oyuncu ışınlanama sorunu burada!
     */
    public static void patchProceduralInstance(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching InstancePlayable for procedural dungeons...");
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found InstancePlayable method: " + method.name + method.desc);
            
            // Teleport method'larını özel olarak patch'le
            if (method.name.equals("teleport") || method.name.equals("addPlayer") ||
                method.name.equals("joinPlayer") || method.name.contains("Teleport")) {
                
                System.out.println("[MythicDungeons Debug] Patching procedural teleport method: " + method.name);
                
                // Async calls'ları sync'e çevir
                patchAsyncCalls(method);
                
                // Teleport async calls'ları özel olarak patch'le
                patchTeleportAsyncCalls(method);
                
                // Debug logging ekle
                addMethodDebugLogging(method, "InstancePlayable." + method.name);
                
                // Eğer bu addPlayer ise, daha agresif patch yap
                if (method.name.equals("addPlayer") && 
                    method.desc.contains("MythicPlayer")) {
                    patchAddPlayerMethod(method);
                }
            }
            
            // Generation completion check method'ları
            if (method.name.contains("generate") || method.name.contains("ready") ||
                method.name.contains("complete") || method.name.contains("finish")) {
                
                System.out.println("[MythicDungeons Debug] Patching generation check: " + method.name);
                addMethodDebugLogging(method, "InstancePlayable.generation." + method.name);
                patchAsyncCalls(method);
            }
        }
    }
    
    /**
     * Classic Instance patch (karşılaştırma için)
     */
    public static void patchClassicInstance(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching InstanceClassic (working reference)...");
        
        for (MethodNode method : node.methods) {
            if (method.name.equals("addPlayer") || method.name.equals("teleport")) {
                System.out.println("[MythicDungeons Debug] Classic instance method: " + method.name + method.desc);
                addMethodDebugLogging(method, "InstanceClassic." + method.name);
            }
        }
    }
    
    /**
     * Procedural Generation Engine patch
     */
    public static void patchProceduralGeneration(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching ProceduralDungeon generation engine...");
        
        for (MethodNode method : node.methods) {
            // Generation tamamlanma callback'leri
            if (method.name.equals("onGenerationComplete") || 
                method.name.equals("onGenerationFinished") ||
                method.name.contains("callback") || method.name.contains("Complete")) {
                
                System.out.println("[MythicDungeons Debug] Found generation callback: " + method.name);
                addMethodDebugLogging(method, "ProceduralDungeon." + method.name);
                
                // Burada teleport trigger ediliyor olabilir
                patchAsyncCalls(method);
                patchTeleportAsyncCalls(method);
            }
            
            // Generation method'ları
            if (method.name.equals("generate") || method.name.equals("build") ||
                method.name.equals("construct")) {
                
                System.out.println("[MythicDungeons Debug] Found generation method: " + method.name);
                addMethodDebugLogging(method, "ProceduralDungeon." + method.name);
                patchAsyncCalls(method);
                patchTimeoutValues(method);
            }
        }
    }
    
    /**
     * addPlayer method'unu özel olarak patch'le - procedural dungeon sorununda kritik!
     */
    private static void patchAddPlayerMethod(MethodNode method) {
        System.out.println("[MythicDungeons Debug] Applying special addPlayer patch for procedural dungeons");
        
        // addPlayer method'unun içinde teleport çağrısı geciktirilmeli
        // Çünkü procedural generation henüz tamamlanmamış olabilir
        
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                // forceTeleport çağrılarını tespit et
                if ((mInsn.name.equals("forceTeleport") || mInsn.name.equals("forceTeleport2")) &&
                    mInsn.owner.contains("Util")) {
                    
                    System.out.println("[MythicDungeons Debug] Found forceTeleport call in addPlayer - this may need delay!");
                    // Burada daha karmaşık patch yapılabilir - teleport'ı geciktirmek için
                }
                
                // Generation check çağrıları
                if (mInsn.name.contains("isReady") || mInsn.name.contains("isGenerated") ||
                    mInsn.name.contains("isComplete")) {
                    
                    System.out.println("[MythicDungeons Debug] Found generation check in addPlayer: " + mInsn.name);
                }
            }
        }
    }

    public static boolean hasPaperAsyncSupport() { return false; }

    private static byte[] patch(byte[] basicClass, Consumer<ClassNode> handler) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(basicClass).accept(node, 0);
            handler.accept(node);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Exception e) {
            System.err.println("[PluginFixManager] Failed to patch class: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
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

    private static void qs(ClassNode node) { redirectMethodToGetNMSVersion(node, "getNMSVersion"); }
    private static void fawe(ClassNode node) { redirectMethodToGetNMSVersion(node, "getPackageVersion"); }

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

    public static String getNMSVersion() { return "v1_21_R1"; }

    public static String make(String in) {
        if (in.equals("8(;4>`")) return "peace";
        final char[] c = in.toCharArray();
        for (int i = 0; i < c.length; ++i) c[i] ^= 'Z';
        return new String(c);
    }
}
