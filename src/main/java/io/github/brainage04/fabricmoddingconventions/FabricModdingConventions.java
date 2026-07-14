package io.github.brainage04.fabricmoddingconventions;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FabricModdingConventions implements ClientModInitializer {
    public static final String MOD_ID = "fabricmoddingconventions";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientGameTestRecordingHud.initialize();
    }
}
