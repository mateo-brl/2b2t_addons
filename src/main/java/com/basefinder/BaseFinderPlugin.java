package com.basefinder;

import com.basefinder.modules.BaseFinderModule;
import com.basefinder.modules.ElytraBotModule;
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

        // Register modules
        BaseFinderModule baseFinderModule = new BaseFinderModule();
        RusherHackAPI.getModuleManager().registerFeature(baseFinderModule);

        ElytraBotModule elytraBotModule = new ElytraBotModule();
        RusherHackAPI.getModuleManager().registerFeature(elytraBotModule);

        // Register HUD
        BaseFinderHud hud = new BaseFinderHud();
        RusherHackAPI.getHudManager().registerFeature(hud);

        // Register commands
        BaseFinderCommand command = new BaseFinderCommand();
        RusherHackAPI.getCommandManager().registerFeature(command);

        this.getLogger().info("BaseFinder plugin loaded successfully!");
    }

    @Override
    public void onUnload() {
        this.getLogger().info("BaseFinder plugin unloaded.");
    }

    public static BaseFinderPlugin getInstance() {
        return instance;
    }
}
