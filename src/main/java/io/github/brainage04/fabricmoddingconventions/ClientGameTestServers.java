package io.github.brainage04.fabricmoddingconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Objects;
import java.util.Properties;

public final class ClientGameTestServers {
    private static final int DEDICATED_SERVER_JOIN_TIMEOUT_TICKS = SharedConstants.TICKS_PER_MINUTE;

    private ClientGameTestServers() {
    }

    public static Properties flatServerProperties() {
        Properties serverProperties = new Properties();
        serverProperties.setProperty("server-port", Integer.toString(findAvailablePort()));
        serverProperties.setProperty("simulation-distance", "5");
        serverProperties.setProperty("view-distance", "5");
        serverProperties.setProperty("level-type", "minecraft:flat");
        serverProperties.setProperty("generate-structures", "false");
        serverProperties.setProperty("generator-settings", "{}");
        serverProperties.setProperty("spawn-protection", "0");
        return serverProperties;
    }

    public static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new AssertionError("Expected to find an available port for the client GameTest server.", exception);
        }
    }

    public static void connectToDedicatedServer(ClientGameTestContext context, TestDedicatedServerContext server) {
        connectToDedicatedServer(context, server, "Client GameTest");
    }

    public static void connectToDedicatedServer(ClientGameTestContext context, TestDedicatedServerContext server, String serverName) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(server, "server");
        String address = "localhost:" + server.computeOnServer(minecraftServer -> minecraftServer.getPort());
        String cleanServerName = serverName == null || serverName.isBlank() ? "Client GameTest" : serverName.strip();

        context.runOnClient(client -> {
            ServerData serverData = new ServerData(cleanServerName, address, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(
                    currentScreen(client),
                    client,
                    ServerAddress.parseString(address),
                    serverData,
                    false,
                    null
            );
        });

        waitForDedicatedServerJoin(context);
    }

    public static void waitForDedicatedServerJoin(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        for (int tick = 0; tick < DEDICATED_SERVER_JOIN_TIMEOUT_TICKS; tick++) {
            acceptServerResourcePackPrompt(context);

            if (context.computeOnClient(client ->
                    client.level != null
                            && client.player != null
                            && !(currentScreen(client) instanceof LevelLoadingScreen))) {
                return;
            }

            context.waitTick();
        }

        String screenName = context.computeOnClient(client -> {
            Screen screen = currentScreen(client);
            return screen == null ? "<none>" : screen.getClass().getName();
        });
        throw new AssertionError("Timed out joining the dedicated server; current screen is " + screenName + ".");
    }

    public static void assertClientWorldAndPlayerAvailable(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        context.runOnClient(client -> {
            if (client.level == null) {
                throw new AssertionError("Expected a client level after joining the dedicated server.");
            }
            if (client.player == null) {
                throw new AssertionError("Expected a local client player after joining the dedicated server.");
            }
        });
    }

    public static void acceptServerResourcePackPrompt(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.computeOnClient(client -> isServerResourcePackPrompt(currentScreen(client)))) {
            return;
        }

        if (context.tryClickScreenButton("gui.continue") || context.tryClickScreenButton("gui.yes")) {
            return;
        }

        throw new AssertionError("Detected a server resource-pack prompt, but could not find its accept button.");
    }

    public static void disconnectFromDedicatedServer(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        context.runOnClient(client -> {
            if (client.level == null) {
                return;
            }

            client.level.disconnect(Component.literal("Disconnecting"));
            client.disconnectWithSavingScreen();
        });

        context.waitFor(client -> client.level == null);
        context.waitTicks(2);
        context.setScreen(TitleScreen::new);
    }

    private static Screen currentScreen(Minecraft client) {
        try {
            return (Screen) Minecraft.class.getField("screen").get(client);
        } catch (NoSuchFieldException ignored) {
            return currentGuiScreen(client);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Expected to read Minecraft.screen for the client GameTest.", exception);
        }
    }

    private static Screen currentGuiScreen(Minecraft client) {
        try {
            Object gui = Minecraft.class.getField("gui").get(client);
            return (Screen) gui.getClass().getMethod("screen").invoke(gui);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Expected to read Minecraft GUI screen for the client GameTest.", exception);
        }
    }

    private static boolean isServerResourcePackPrompt(Screen screen) {
        if (!(screen instanceof ConfirmScreen)) {
            return false;
        }

        if (!(screen.getTitle().getContents() instanceof TranslatableContents contents)) {
            return false;
        }

        return "multiplayer.texturePrompt.line1".equals(contents.getKey())
                || "multiplayer.requiredTexturePrompt.line1".equals(contents.getKey());
    }
}
