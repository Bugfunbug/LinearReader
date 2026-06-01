package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearTestSupport;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LinearCommandRegistrarTest {

    @BeforeEach
    void setUp() {
        LinearTestSupport.resetState();
    }

    @Test
    void omitsManualVoxyCompatCommands() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        LinearCommandRegistrar.register(dispatcher, source -> true);

        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("linearreader");
        assertNotNull(root);

        assertNull(root.getChild("voxy-compat"));
        assertNull(root.getChild("voxy-mca"));
    }
}
