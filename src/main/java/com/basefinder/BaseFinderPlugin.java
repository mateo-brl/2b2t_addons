package com.basefinder;

import com.basefinder.modules.AutoMendingModule;
import com.basefinder.modules.AutoTravelModule;
import com.basefinder.modules.BaseFinderModule;
import com.basefinder.modules.ElytraBotModule;
import com.basefinder.modules.PortalHunterModule;
import com.basefinder.hud.BaseFinderHud;
import com.basefinder.command.BaseFinderCommand;
import com.basefinder.command.PortalHunterCommand;
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
        this.getLogger().info("Plugin BaseFinder en chargement...");

        // Enregistrer les modules individuellement avec try/catch
        try {
            BaseFinderModule baseFinderModule = new BaseFinderModule();
            RusherHackAPI.getModuleManager().registerFeature(baseFinderModule);
            this.getLogger().info("Module BaseHunter enregistré (catégorie External)");
        } catch (Exception e) {
            this.getLogger().error("Échec enregistrement module BaseFinder : {}", e.getMessage(), e);
        }

        try {
            ElytraBotModule elytraBotModule = new ElytraBotModule();
            RusherHackAPI.getModuleManager().registerFeature(elytraBotModule);
            this.getLogger().info("Module ElytraBot enregistré (catégorie External)");
        } catch (Exception e) {
            this.getLogger().error("Échec enregistrement module ElytraBot : {}", e.getMessage(), e);
        }

        try {
            AutoTravelModule autoTravelModule = new AutoTravelModule();
            RusherHackAPI.getModuleManager().registerFeature(autoTravelModule);
            this.getLogger().info("Module AutoTravel enregistré (catégorie External)");
        } catch (Exception e) {
            this.getLogger().error("Échec enregistrement module AutoTravel : {}", e.getMessage(), e);
        }

        try {
            AutoMendingModule autoMendingModule = new AutoMendingModule();
            RusherHackAPI.getModuleManager().registerFeature(autoMendingModule);
            this.getLogger().info("Module AutoMending enregistré (catégorie External)");
        } catch (Exception e) {
            this.getLogger().error("Échec enregistrement module AutoMending : {}", e.getMessage(), e);
        }

        try {
            PortalHunterModule portalHunterModule = new PortalHunterModule();
            RusherHackAPI.getModuleManager().registerFeature(portalHunterModule);
            this.getLogger().info("Module PortalHunter enregistré (catégorie External)");
        } catch (Exception e) {
            this.getLogger().error("Échec enregistrement module PortalHunter : {}", e.getMessage(), e);
        }

        try {
            BaseFinderHud hud = new BaseFinderHud();
            RusherHackAPI.getHudManager().registerFeature(hud);
            this.getLogger().info("HUD BaseFinder enregistré");
        } catch (Exception e) {
            this.getLogger().error("Échec enregistrement HUD : {}", e.getMessage(), e);
        }

        try {
            BaseFinderCommand command = new BaseFinderCommand();
            RusherHackAPI.getCommandManager().registerFeature(command);
            this.getLogger().info("Commande BaseFinder enregistrée");
        } catch (Exception e) {
            this.getLogger().error("Échec enregistrement commande : {}", e.getMessage(), e);
        }

        try {
            PortalHunterCommand phCommand = new PortalHunterCommand();
            RusherHackAPI.getCommandManager().registerFeature(phCommand);
            this.getLogger().info("Commande PortalHunter enregistrée");
        } catch (Exception e) {
            this.getLogger().error("Échec enregistrement commande PortalHunter : {}", e.getMessage(), e);
        }

        this.getLogger().info("Plugin BaseFinder chargé !");
    }

    @Override
    public void onUnload() {
        this.getLogger().info("Plugin BaseFinder déchargé.");
    }

    public static BaseFinderPlugin getInstance() {
        return instance;
    }
}
