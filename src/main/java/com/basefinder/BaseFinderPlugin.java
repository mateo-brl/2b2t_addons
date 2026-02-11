package com.basefinder;

import com.basefinder.modules.BaseFinderModule;
import com.basefinder.modules.ElytraBotModule;
import com.basefinder.modules.NewChunksModule;
import com.basefinder.hud.BaseFinderHud;
import com.basefinder.command.BaseFinderCommand;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

/**
 * BaseFinder - Automated base hunting plugin for 2b2t
 */
public class BaseFinderPlugin extends Plugin {

    private static BaseFinderPlugin instance;

    @Override
    public void onLoad() {
        instance = this;
        this.getLogger().info("BaseFinder plugin loading...");

        // Register modules individually with try/catch so one failure doesn't block others
        try {
            NewChunksModule newChunksModule = new NewChunksModule();
            RusherHackAPI.getModuleManager().registerFeature(newChunksModule);
            this.getLogger().info("ChunkHistory module registered (External category)");
        } catch (Exception e) {
            this.getLogger().error("Failed to register NewChunks module: {}", e.getMessage());
            e.printStackTrace();
        }

        try {
            BaseFinderModule baseFinderModule = new BaseFinderModule();
            RusherHackAPI.getModuleManager().registerFeature(baseFinderModule);
            this.getLogger().info("BaseHunter module registered (External category)");
        } catch (Exception e) {
            this.getLogger().error("Failed to register BaseFinder module: {}", e.getMessage());
            e.printStackTrace();
        }

        try {
            ElytraBotModule elytraBotModule = new ElytraBotModule();
            RusherHackAPI.getModuleManager().registerFeature(elytraBotModule);
            this.getLogger().info("ElytraBot module registered (External category)");
        } catch (Exception e) {
            this.getLogger().error("Failed to register ElytraBot module: {}", e.getMessage());
            e.printStackTrace();
        }

        try {
            BaseFinderHud hud = new BaseFinderHud();
            RusherHackAPI.getHudManager().registerFeature(hud);
            this.getLogger().info("BaseFinder HUD registered");
        } catch (Exception e) {
            this.getLogger().error("Failed to register HUD: {}", e.getMessage());
            e.printStackTrace();
        }

        try {
            BaseFinderCommand command = new BaseFinderCommand();
            RusherHackAPI.getCommandManager().registerFeature(command);
            this.getLogger().info("BaseFinder command registered");
        } catch (Exception e) {
            this.getLogger().error("Failed to register command: {}", e.getMessage());
            e.printStackTrace();
        }

        this.getLogger().info("BaseFinder plugin loaded!");
    }

    @Override
    public void onUnload() {
        this.getLogger().info("BaseFinder plugin unloaded.");
    }

    public static BaseFinderPlugin getInstance() {
        return instance;
    }
}
