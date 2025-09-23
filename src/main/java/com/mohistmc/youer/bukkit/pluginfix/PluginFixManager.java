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

    // -------------------- TELEPORT + STRUCTURE GENERATION + LAST VERSION --------------------

    /**
     * Oyuncuyu dungeon içine güvenli şekilde teleport eder ve yapı oluşturmayı bekler.
     */
    public static void teleportEntityToDungeon(Entity entity, Location target) {
        if (entity == null || target == null) return;
        
        if (!(entity instanceof Player player)) {
            return;
        }

        // YOUER OPTIMIZE: Chunk'ı önceden yükle ve yapı oluşturmayı bekle
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                () -> {
                    try {
                        // 1. Target chunk'ı force load et
                        if (!target.getChunk().isLoaded()) {
                            target.getChunk().load();
                        }
                        
                        // 2. Çevredeki chunk'ları da önceden yükle (structure için)
                        preloadSurroundingChunks(target, 2);
                        
                        // 3. Yapı oluşturma için kısa delay
                        Bukkit.getScheduler().runTaskLater(
                            Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                            () -> {
                                // 4. Güvenli teleport
                                player.teleport(target);
                                
                                System.out.println("[MythicDungeons Patch] Successfully teleported " + player.getName() + 
                                    " to dungeon at " + target.getWorld().getName() + " " + 
                                    target.getX() + "," + target.getY() + "," + target.getZ());
                            }, 
                            5L // 5 tick delay (250ms) - yapı oluşturma için
                        );
                        
                    } catch (Exception e) {
                        System.err.println("[MythicDungeons Patch] Teleport failed for player " + player.getName());
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
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = centerChunkX + x;
                int chunkZ = centerChunkZ + z;
                
                // Async chunk loading - non-blocking
                center.getWorld().getChunkAtAsync(chunkX, chunkZ, (chunk) -> {
                    // Chunk yüklendi, hiçbir şey yapmaya gerek yok
                });
            }
        }
    }

    /**
     * Layout generation timeout'larını düzelt
     */
    public static void patchLayoutGeneration(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("generate")) {
                // Timeout değerlerini artır
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LdcInsnNode ldcInsn) {
                        if (ldcInsn.cst instanceof Long timeout && timeout == 5000L) {
                            // 5 saniye → 30 saniye
                            ldcInsn.cst = 30000L;
                            System.out.println("[MythicDungeons Patch] Increased generation timeout to 30s");
                        }
                    }
                    if (insn instanceof IntInsnNode intInsn) {
                        if (intInsn.operand == 5) {
                            // 5 saniye → 30 saniye
                            intInsn.operand = 30;
                            System.out.println("[MythicDungeons Patch] Increased generation timeout to 30s");
                        }
                    }
                }
            }
        }
    }
    
    /**
     * CompletableFuture ve ExecutorService async call'larını sync'e çevir
     */
    public static void patchAsyncGeneration(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode mInsn) {
                    // ExecutorService.submit() -> sync execution
                    if (mInsn.owner.contains("ExecutorService") && mInsn.name.equals("submit")) {
                        // Async submit'i sync çağrıya çevir
                        mInsn.owner = "java/util/concurrent/Callable";
                        mInsn.name = "call";
                        System.out.println("[MythicDungeons Patch] Converted async generation to sync");
                    }
                    
                    // CompletableFuture.get() timeout'ları düzelt
                    if (mInsn.owner.equals("java/util/concurrent/CompletableFuture") && mInsn.name.equals("get")) {
                        System.out.println("[MythicDungeons Patch] Found CompletableFuture.get() call");
                    }
                }
            }
        }
    }

    /**
     * ASM patch: MythicDungeons Util ve Layout sınıflarını patch'ler.
     */
    public static void patchDungeonTeleport(ClassNode node) {
        boolean patchedAny = false;
        
        for (MethodNode method : node.methods) {
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
        }
    }
    
    /**
     * Method'u tamamen replacement ile patch'ler
     */
    private static void patchMethod(MethodNode method, String methodName) {
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
        
        System.out.println("[MythicDungeons Patch] Patched method: " + methodName);
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
            // MythicDungeons patch - TELEPORT FIX
            case "net.playavalon.mythicdungeons.utility.helpers.Util" -> {
                System.out.println("[MythicDungeons Patch] Patching Util class for teleport fixes...");
                return patch(clazz, PluginFixManager::patchDungeonTeleport);
            }
            // MythicDungeons patch - LAYOUT GENERATION FIX
            case "net.playavalon.mythicdungeons.api.generation.layout.Layout" -> {
                System.out.println("[MythicDungeons Patch] Patching Layout generation...");
                return patch(clazz, PluginFixManager::patchLayoutGeneration);
            }
            // MythicDungeons patch - ASYNC GENERATION FIX
            case "net.playavalon.mythicdungeons.api.generation.layout.LayoutBranching",
                 "net.playavalon.mythicdungeons.api.generation.layout.LayoutMinecrafty" -> {
                System.out.println("[MythicDungeons Patch] Patching async generation...");
                return patch(clazz, PluginFixManager::patchAsyncGeneration);
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
