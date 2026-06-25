package com.rfizzle.prosperity;

import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.command.ProsperityCommand;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.ContainerProtection;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.LootModifiers;
import com.rfizzle.prosperity.loot.MinecartLootInteraction;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Prosperity implements ModInitializer {
    public static final String MOD_ID = "prosperity";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile ProsperityConfig config;

    @Override
    public void onInitialize() {
        config = ProsperityConfig.load();
        ProsperityAttachments.init();
        ProsperityNetworking.register();
        InstancedLootInteraction.register();
        MinecartLootInteraction.register();
        ContainerProtection.register();
        LootModifiers.registerDefaults();
        LootInjectionManager.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ProsperityCommand.register(dispatcher));
        LOGGER.info("Prosperity initialized");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ProsperityConfig getConfig() {
        return config;
    }

    public static void reloadConfig() {
        config = ProsperityConfig.load();
    }
}
