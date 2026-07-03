package com.bugfunbug.linearreader.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;

public class ModMenuSupport implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parentScreen -> {
            try {
                File tomlFile = FabricLoader.getInstance().getConfigDir().resolve("linearreader-server.toml").toFile();

                // Fallback: If the file doesn't exist yet, open the config folder instead
                File target = tomlFile.exists() ? tomlFile : FabricLoader.getInstance().getConfigDir().toFile();

                // Detect the OS and execute the native file-opening shell command
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("cmd.exe", "/c", "start", "", target.getAbsolutePath()).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", target.getAbsolutePath()).start();
                } else {
                    // Linux / Unix standard handler
                    new ProcessBuilder("xdg-open", target.getAbsolutePath()).start();
                }
            } catch (Exception e) {
                // Catches IOExceptions if a platform lacks a standard shell handler
                e.printStackTrace();
            }

            // Return parent screen so Mod Menu handles game UI focus seamlessly
            return parentScreen;
        };
    }
}