package com.rfizzle.prosperity.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

public final class ProsperityCommand {
    private ProsperityCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        /* TODO: /prosperity command tree per SPEC §15 */
    }
}
