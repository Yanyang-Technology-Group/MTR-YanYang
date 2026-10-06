package org.mtr;

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.mtr.block.BlockStationNameTallBase;
import org.mtr.block.BlockStationNameTallStanding;
import org.mtr.block.BlockTactileMap;
import org.mtr.block.BlockTrainAnnouncer;
import org.mtr.client.CustomResourceLoader;
import org.mtr.client.DynamicTextureCache;
import org.mtr.client.IDrawing;
import org.mtr.client.MinecraftClientData;
import org.mtr.config.Config;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.core.operation.DataRequest;
import org.mtr.core.servlet.HttpResponseStatus;
import org.mtr.core.servlet.ServletBase;
import org.mtr.core.servlet.Webserver;
import org.mtr.data.IGui;
import org.mtr.generated.WebserverResources;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.libraries.jakarta.servlet.AsyncContext;
import org.mtr.libraries.jakarta.servlet.MultipartConfigElement;
import org.mtr.libraries.jakarta.servlet.http.HttpServlet;
import org.mtr.libraries.jakarta.servlet.http.HttpServletRequest;
import org.mtr.libraries.jakarta.servlet.http.HttpServletResponse;
import org.mtr.libraries.org.eclipse.jetty.servlet.ServletHolder;
import org.mtr.map.MapTileProvider;
import org.mtr.packet.PacketGetUniqueWorldId;
import org.mtr.packet.PacketRequestData;
import org.mtr.registry.*;
import org.mtr.render.*;
import org.mtr.screen.BetaWarningScreen;
import org.mtr.servlet.ClientServlet;
import org.mtr.servlet.ResourcePackCreatorOperationServlet;
import org.mtr.servlet.ResourcePackCreatorUploadServlet;
import org.mtr.sound.LoopingSoundInstance;
import org.mtr.sound.ScheduledSound;
import org.mtr.tool.Drawing;
import org.mtr.tool.ReleasedDynamicTextureRegistry;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client-side mod coordinator and webserver host.
 * Initialises client-side rendering, GUI screens, sound systems, and local webserver for map/tools.
 * Bridges player interactions with the server and backend simulator.
 */
public final class MTRClient {

        @Nullable
        private static Webserver webserver;
        /**
         * Port of the webserver used by the client.
         * In multiplayer this is the local client-side bridge server; in singleplayer it can be the integrated backend server.
         * {@code 0} means the webserver is not running.
         */
        @Getter
        private static int serverPort;
        private static long lastMillis = 0;
        @Getter
        private static long gameMillis = 0;
        private static long lastPlayedTrainSoundsMillis = 0;
        private static long lastUpdatePacketMillis = 0;
        @Nullable
        private static Runnable movePlayer;
        @Nullable
        private static ClientLevel lastClientWorld;
        @Nullable
        private static MapTileProvider mapTileProvider;

        public static final ObjectOpenHashSet<UUID> HIDDEN_PLAYERS = new ObjectOpenHashSet<>();
        public static final int MILLIS_PER_SPEED_SOUND = 200;
        public static final LoopingSoundInstance TACTILE_MAP_SOUND_INSTANCE = new LoopingSoundInstance("tactile_map_music");

        static {
                MTR.createWebserverSetup(MTRClient::setupWebserver);
        }

        public static void init() {
                MTR.writeFromClient();
                KeyBindings.init();

                // Passenger skin pool (vanilla defaults + optional online skins via config)
                PassengerSkinManager.init();

                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.APG_DOOR);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.APG_GLASS);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.APG_GLASS_END);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.CABLE_CAR_NODE_LOWER);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.CABLE_CAR_NODE_UPPER);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.CLOCK);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_CIO);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_CKT);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_HEO);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_MOS);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_PLAIN);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_SHM);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_STAINED);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_STW);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_TSH);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.GLASS_FENCE_WKS);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.LOGO);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_INDENTED);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_SLAB);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_NA_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_NA_1_INDENTED);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_NA_1_SLAB);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_NA_2);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_NA_2_INDENTED);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_NA_2_SLAB);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_UK_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_UK_1_INDENTED);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PLATFORM_UK_1_SLAB);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PSD_DOOR_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PSD_GLASS_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PSD_GLASS_END_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PSD_DOOR_2);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PSD_GLASS_2);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.PSD_GLASS_END_2);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.RUBBISH_BIN_1);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.STATION_COLOR_STAINED_GLASS);
                RegistryClient.registerBlockRenderType(RenderType.translucent(), Blocks.STATION_COLOR_STAINED_GLASS_SLAB);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.STATION_NAME_TALL_BLOCK);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.STATION_NAME_TALL_BLOCK_DOUBLE_SIDED);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.STATION_NAME_TALL_WALL);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.STATION_NAME_TALL_STANDING);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TICKET_BARRIER_ENTRANCE_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TICKET_BARRIER_EXIT_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TICKET_MACHINE);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TICKET_PROCESSOR);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TICKET_PROCESSOR_ENTRANCE);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TICKET_PROCESSOR_EXIT);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TICKET_PROCESSOR_ENQUIRY);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TRAIN_ANNOUNCER);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TRAIN_CARGO_LOADER);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TRAIN_CARGO_UNLOADER);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TRAIN_REDSTONE_SENSOR);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.TRAIN_SCHEDULE_SENSOR);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.LIFT_DOOR_EVEN_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.LIFT_DOOR_ODD_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.LIFT_PANEL_EVEN_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.LIFT_PANEL_ODD_1);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.LIFT_PANEL_EVEN_2);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.LIFT_PANEL_ODD_2);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.ESCALATOR_STEP);
                RegistryClient.registerBlockRenderType(RenderType.cutout(), Blocks.ESCALATOR_SIDE);

                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.LIFT_BUTTONS_1, context -> new RenderLiftButtons());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.LIFT_PANEL_EVEN_1, dispatcher -> new RenderLiftPanel<>(false, false));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.LIFT_PANEL_ODD_1, dispatcher -> new RenderLiftPanel<>(true, false));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.LIFT_PANEL_EVEN_2, dispatcher -> new RenderLiftPanel<>(false, true));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.LIFT_PANEL_ODD_2, dispatcher -> new RenderLiftPanel<>(true, true));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.LIFT_DOOR_EVEN_1, dispatcher -> new RenderPSDAPGDoor<>(3));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.LIFT_DOOR_ODD_1, dispatcher -> new RenderPSDAPGDoor<>(4));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.ARRIVAL_PROJECTOR_1_SMALL, dispatcher -> new RenderPIDS<>(1, 15, 16, 14, 14, false, 1));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.ARRIVAL_PROJECTOR_1_MEDIUM, dispatcher -> new RenderPIDS<>(-15, 15, 16, 30, 46, false, 1));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.ARRIVAL_PROJECTOR_1_LARGE, dispatcher -> new RenderPIDS<>(-15, 15, 16, 46, 46, false, 1));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.CLOCK, context -> new RenderClock());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.DRIVER_KEY_DISPENSER, context -> new RenderDriverKeyDispenser());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.PSD_DOOR_1, dispatcher -> new RenderPSDAPGDoor<>(0));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.PSD_DOOR_2, dispatcher -> new RenderPSDAPGDoor<>(1));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.PSD_TOP, context -> new RenderPSDTop());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.APG_GLASS, context -> new RenderAPGGlass());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.APG_DOOR, dispatcher -> new RenderPSDAPGDoor<>(2));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.PIDS_HORIZONTAL_1, dispatcher -> new RenderPIDS<>(1, 3.25F, 6, 2.5F, 30, true, 1));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.PIDS_HORIZONTAL_2, dispatcher -> new RenderPIDS<>(1.5F, 7.5F, 6, 6.5F, 29, true, 1));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.PIDS_HORIZONTAL_3, dispatcher -> new RenderPIDS<>(2.5F, 7.5F, 6, 6.5F, 27, true, 1.25F));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.PIDS_VERTICAL_1, dispatcher -> new RenderPIDS<>(2F, 14F, 15, 28F, 12, false, 1));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.PIDS_VERTICAL_SINGLE_ARRIVAL_1, dispatcher -> new RenderPIDS<>(2F, 14F, 15, 28F, 12, false, 1));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_2_EVEN, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_2_ODD, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_3_EVEN, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_3_ODD, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_4_EVEN, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_4_ODD, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_5_EVEN, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_5_ODD, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_6_EVEN, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_6_ODD, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_7_EVEN, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.RAILWAY_SIGN_7_ODD, context -> new RenderRailwaySign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.ROUTE_SIGN_STANDING_LIGHT, context -> new RenderRouteSign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.ROUTE_SIGN_STANDING_METAL, context -> new RenderRouteSign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.ROUTE_SIGN_WALL_LIGHT, context -> new RenderRouteSign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.ROUTE_SIGN_WALL_METAL, context -> new RenderRouteSign<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_LIGHT_2_ASPECT_1, dispatcher -> new RenderSignalLight2Aspect<>(false, 0xFF0000FF));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_LIGHT_2_ASPECT_2, dispatcher -> new RenderSignalLight2Aspect<>(false, 0xFF0000FF));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_LIGHT_2_ASPECT_3, dispatcher -> new RenderSignalLight2Aspect<>(true, 0xFF00FF00));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_LIGHT_2_ASPECT_4, dispatcher -> new RenderSignalLight2Aspect<>(true, 0xFF00FF00));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_LIGHT_3_ASPECT_1, context -> new RenderSignalLight3Aspect<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_LIGHT_3_ASPECT_2, context -> new RenderSignalLight3Aspect<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_LIGHT_4_ASPECT_1, context -> new RenderSignalLight4Aspect<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_LIGHT_4_ASPECT_2, context -> new RenderSignalLight4Aspect<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_SEMAPHORE_1, context -> new RenderSignalSemaphore<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.SIGNAL_SEMAPHORE_2, context -> new RenderSignalSemaphore<>());
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.STATION_NAME_ENTRANCE, dispatcher -> new RenderStationNameTiled<>(true));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.STATION_NAME_TALL_BLOCK, dispatcher -> new RenderStationNameTall<>(BlockStationNameTallBase.WIDTH, BlockStationNameTallBase.HEIGHT, 0));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.STATION_NAME_TALL_BLOCK_DOUBLE_SIDED, dispatcher -> new RenderStationNameTall<>(BlockStationNameTallBase.WIDTH, BlockStationNameTallBase.HEIGHT, 0));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.STATION_NAME_TALL_WALL, dispatcher -> new RenderStationNameTall<>(BlockStationNameTallBase.WIDTH, BlockStationNameTallBase.HEIGHT, 0));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.STATION_NAME_TALL_STANDING, dispatcher -> new RenderStationNameTall<>(BlockStationNameTallStanding.WIDTH, BlockStationNameTallStanding.HEIGHT, BlockStationNameTallStanding.OFFSET_Y));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.STATION_NAME_WALL_WHITE, dispatcher -> new RenderStationNameTiled<>(false));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.STATION_NAME_WALL_GRAY, dispatcher -> new RenderStationNameTiled<>(false));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.STATION_NAME_WALL_BLACK, dispatcher -> new RenderStationNameTiled<>(false));
                RegistryClient.registerBlockEntityRenderer(BlockEntityTypes.EYE_CANDY, context -> new RenderEyeCandy());

                RegistryClient.registerBlockColors((blockState, blockRenderView, blockPos, tintIndex) -> getStationColor(blockPos),
                        Blocks.STATION_COLOR_ANDESITE,
                        Blocks.STATION_COLOR_BEDROCK,
                        Blocks.STATION_COLOR_BIRCH_WOOD,
                        Blocks.STATION_COLOR_BONE_BLOCK,
                        Blocks.STATION_COLOR_CHISELED_QUARTZ_BLOCK,
                        Blocks.STATION_COLOR_CHISELED_STONE_BRICKS,
                        Blocks.STATION_COLOR_CLAY,
                        Blocks.STATION_COLOR_COAL_ORE,
                        Blocks.STATION_COLOR_COBBLESTONE,
                        Blocks.STATION_COLOR_CONCRETE,
                        Blocks.STATION_COLOR_CONCRETE_POWDER,
                        Blocks.STATION_COLOR_CRACKED_STONE_BRICKS,
                        Blocks.STATION_COLOR_DARK_PRISMARINE,
                        Blocks.STATION_COLOR_DIORITE,
                        Blocks.STATION_COLOR_GRAVEL,
                        Blocks.STATION_COLOR_IRON_BLOCK,
                        Blocks.STATION_COLOR_METAL,
                        Blocks.STATION_COLOR_MOSAIC_TILE,
                        Blocks.STATION_COLOR_PLANKS,
                        Blocks.STATION_COLOR_POLISHED_ANDESITE,
                        Blocks.STATION_COLOR_POLISHED_DIORITE,
                        Blocks.STATION_COLOR_PURPUR_BLOCK,
                        Blocks.STATION_COLOR_PURPUR_PILLAR,
                        Blocks.STATION_COLOR_QUARTZ_BLOCK,
                        Blocks.STATION_COLOR_QUARTZ_BRICKS,
                        Blocks.STATION_COLOR_QUARTZ_PILLAR,
                        Blocks.STATION_COLOR_SMOOTH_QUARTZ,
                        Blocks.STATION_COLOR_SMOOTH_STONE,
                        Blocks.STATION_COLOR_SNOW_BLOCK,
                        Blocks.STATION_COLOR_STAINED_GLASS,
                        Blocks.STATION_COLOR_STONE,
                        Blocks.STATION_COLOR_STONE_BRICKS,
                        Blocks.STATION_COLOR_WOOL,

                        Blocks.STATION_COLOR_ANDESITE_SLAB,
                        Blocks.STATION_COLOR_BEDROCK_SLAB,
                        Blocks.STATION_COLOR_BIRCH_WOOD_SLAB,
                        Blocks.STATION_COLOR_BONE_BLOCK_SLAB,
                        Blocks.STATION_COLOR_CHISELED_QUARTZ_BLOCK_SLAB,
                        Blocks.STATION_COLOR_CHISELED_STONE_BRICKS_SLAB,
                        Blocks.STATION_COLOR_CLAY_SLAB,
                        Blocks.STATION_COLOR_COAL_ORE_SLAB,
                        Blocks.STATION_COLOR_COBBLESTONE_SLAB,
                        Blocks.STATION_COLOR_CONCRETE_SLAB,
                        Blocks.STATION_COLOR_CONCRETE_POWDER_SLAB,
                        Blocks.STATION_COLOR_CRACKED_STONE_BRICKS_SLAB,
                        Blocks.STATION_COLOR_DARK_PRISMARINE_SLAB,
                        Blocks.STATION_COLOR_DIORITE_SLAB,
                        Blocks.STATION_COLOR_GRAVEL_SLAB,
                        Blocks.STATION_COLOR_IRON_BLOCK_SLAB,
                        Blocks.STATION_COLOR_METAL_SLAB,
                        Blocks.STATION_COLOR_MOSAIC_TILE_SLAB,
                        Blocks.STATION_COLOR_PLANKS_SLAB,
                        Blocks.STATION_COLOR_POLISHED_ANDESITE_SLAB,
                        Blocks.STATION_COLOR_POLISHED_DIORITE_SLAB,
                        Blocks.STATION_COLOR_PURPUR_BLOCK_SLAB,
                        Blocks.STATION_COLOR_PURPUR_PILLAR_SLAB,
                        Blocks.STATION_COLOR_QUARTZ_BLOCK_SLAB,
                        Blocks.STATION_COLOR_QUARTZ_BRICKS_SLAB,
                        Blocks.STATION_COLOR_QUARTZ_PILLAR_SLAB,
                        Blocks.STATION_COLOR_SMOOTH_QUARTZ_SLAB,
                        Blocks.STATION_COLOR_SMOOTH_STONE_SLAB,
                        Blocks.STATION_COLOR_SNOW_BLOCK_SLAB,
                        Blocks.STATION_COLOR_STAINED_GLASS_SLAB,
                        Blocks.STATION_COLOR_STONE_SLAB,
                        Blocks.STATION_COLOR_STONE_BRICKS_SLAB,
                        Blocks.STATION_COLOR_WOOL_SLAB,

                        Blocks.STATION_COLOR_POLE,
                        Blocks.STATION_NAME_TALL_BLOCK,
                        Blocks.STATION_NAME_TALL_BLOCK_DOUBLE_SIDED,
                        Blocks.STATION_NAME_TALL_WALL,
                        Blocks.STATION_NAME_TALL_STANDING
                );

                RegistryClient.setupPackets();

                EventRegistryClient.registerClientJoin(() -> {
                        MinecraftClientData.reset();
                        DynamicTextureCache.instance = new DynamicTextureCache();
                        lastMillis = System.currentTimeMillis();
                        gameMillis = 0;
                        lastUpdatePacketMillis = 0;
                        DynamicTextureCache.instance.refresh();

                        // Clientside webserver for locally hosting the online system map
                        // Only start clientside webserver if not in singleplayer
                        if (MTR.getServerPort() == 0) {
                                serverPort = MTR.findFreePort(0);
                                webserver = new Webserver(serverPort);
                                webserver.addServlet(new ServletHolder(new ClientServlet()), "/");
                                setupWebserver(webserver);
                                webserver.start();
                        } else {
                                serverPort = Math.max(MTR.getServerPort(), 0);
                        }
                        if (serverPort > 0) {
                                MTR.LOGGER.info("Open the Transport System Map at http://localhost:{}", serverPort);
                                MTR.LOGGER.info("Open the Resource Pack Creator at http://localhost:{}/creator/", serverPort);
                        } else {
                                MTR.LOGGER.info("Transport System Map and Resource Pack Creator disabled");
                        }
                });

                EventRegistryClient.registerClientDisconnect(() -> {
                        if (webserver != null) {
                                webserver.stop();
                                webserver = null;
                        }
                        serverPort = 0;
                });

                EventRegistryClient.registerStartClientTick(() -> {
                        final long currentMillis = System.currentTimeMillis();
                        final long millisElapsed = currentMillis - lastMillis;
                        lastMillis = currentMillis;
                        gameMillis += millisElapsed;
                        BetaWarningScreen.handle();

                        if (mapTileProvider != null) {
                                mapTileProvider.tick();
                        }

                        final ClientLevel clientWorld = Minecraft.getInstance().level;
                        // If world or dimension changed, reset the data
                        if (clientWorld != null && (lastClientWorld == null || !lastClientWorld.equals(clientWorld))) {
                                lastClientWorld = clientWorld;
                                MinecraftClientData.reset();
                                RegistryClient.sendPacketToServer(new PacketGetUniqueWorldId());
                        }

                        BlockTrainAnnouncer.processQueue();
                        ResourcePackCreatorOperationServlet.tick(millisElapsed);

                        // If player is moving, send a request every 0.5 seconds to the server to fetch any new nearby data
                        final LocalPlayer clientPlayerEntity = Minecraft.getInstance().player;
                        if (clientPlayerEntity != null && lastUpdatePacketMillis > 0 && getGameMillis() > lastUpdatePacketMillis) {
                                final DataRequest dataRequest = new DataRequest(clientPlayerEntity.getUUID(), MTR.blockPosToPosition(Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition()), (int) Minecraft.getInstance().levelRenderer.getLastViewDistance() * 16L);
                                dataRequest.writeExistingIds(MinecraftClientData.getInstance());
                                RegistryClient.sendPacketToServer(new PacketRequestData(dataRequest));
                                lastUpdatePacketMillis = 0;
                        }
                });

                EventRegistryClient.registerEndClientTick(() -> {
                        if (movePlayer != null) {
                                movePlayer.run();
                                movePlayer = null;
                        }
                        ScheduledSound.playScheduledSounds();
                });

                EventRegistryClient.registerChunkLoad((clientWorld, chunk) -> {
                        if (lastUpdatePacketMillis == 0) {
                                lastUpdatePacketMillis = getGameMillis() + 500;
                        }
                        if (mapTileProvider != null) {
                                mapTileProvider.getTile(chunk.getPos().getWorldPosition());
                        }
                });

                EventRegistryClient.registerResourceReloadEvent(() -> {
                        CustomResourceLoader.reload();
                        ReleasedDynamicTextureRegistry.INSTANCE.reload();
                        PassengerSkinManager.reload();
                });
                EventRegistryClient.registerWorldRenderEvent(MainRenderer::render);
                EventRegistryClient.registerWorldRenderEndEvent(BlockEntityRenderCulling::end);
                EventRegistryClient.registerHudLayerRenderEvent(context -> DrivingGuiRenderer.render(context));

                Config.init(Minecraft.getInstance().gameDirectory.toPath());

                BlockTactileMap.TactileMapBlockEntity.updateSoundSource = TACTILE_MAP_SOUND_INSTANCE::setPos;
                BlockTactileMap.TactileMapBlockEntity.onUse = blockPos -> {
                        final Station station = findStation(blockPos);
                        if (station != null) {
                                final String text = IGui.formatStationName(IGui.insertTranslation(TranslationProvider.GUI_MTR_WELCOME_STATION_CJK, TranslationProvider.GUI_MTR_WELCOME_STATION, 1, IGui.textOrUntitled(station.getName())));
                                IDrawing.narrateOrAnnounce(text, ObjectArrayList.of(Component.literal(text)));
                        }
                };
        }

        public static int getStationColor(@Nullable BlockPos blockPos) {
                final int defaultColor = 0x7F7F7F;
                if (blockPos == null) {
                        return defaultColor;
                } else {
                        final Station station = findStation(blockPos);
                        return station == null ? defaultColor : station.getColor();
                }
        }

        public static void scheduleMovePlayer(Runnable movePlayer) {
                MTRClient.movePlayer = movePlayer;
        }

        public static boolean canPlaySound() {
                if (getGameMillis() - lastPlayedTrainSoundsMillis >= MILLIS_PER_SPEED_SOUND) {
                        lastPlayedTrainSoundsMillis = getGameMillis();
                }
                return getGameMillis() == lastPlayedTrainSoundsMillis && !Minecraft.getInstance().isPaused();
        }

        @Nullable
        public static Station findStation(BlockPos blockPos) {
                return MinecraftClientData.getInstance().spatialIndex.findStation(MTR.blockPosToPosition(blockPos));
        }

        public static void findClosePlatform(BlockPos blockPos, int radius, Consumer<Platform> consumer) {
                final Platform platform = MinecraftClientData.getInstance().spatialIndex.findClosePlatform(MTR.blockPosToPosition(blockPos), radius);
                if (platform != null) {
                        consumer.accept(platform);
                }
        }

        @Nullable
        public static Depot findDepot(BlockPos blockPos) {
                final Position position = MTR.blockPosToPosition(blockPos);
                for (final Depot depot : MinecraftClientData.getInstance().depots) {
                        if (depot.inArea(position)) {
                                return depot;
                        }
                }
                return null;
        }

        public static void transformToFacePlayer(PoseStack matrixStack, double x, double y, double z) {
                final Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                final double differenceX = cameraPosition.x - x;
                final double differenceY = cameraPosition.y - y;
                final double differenceZ = cameraPosition.z - z;
                Drawing.rotateYRadians(matrixStack, (float) (Math.atan2(differenceX, differenceZ) + Math.PI));
                Drawing.rotateXRadians(matrixStack, (float) Math.atan2(differenceY, Math.sqrt(differenceZ * differenceZ + differenceX * differenceX)));
        }

        public static String getShiftText() {
                return Minecraft.getInstance().options.keyShift.getTranslatedKeyMessage().getString();
        }

        public static float getGameTick() {
                return gameMillis / 50F;
        }

        public static float getGameTimeDeltaTicks() {
//? if >= 1.21.4 {
                return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaTicks();
//? } else {
                /*return Minecraft.getInstance().getTimer().getGameTimeDeltaTicks();
//
*///? }
        }

        public static void processUniqueWorldId(String uniqueWorldId) {
                final ClientLevel clientWorld = Minecraft.getInstance().level;
                if (clientWorld != null) {
                        mapTileProvider = new MapTileProvider(clientWorld, uniqueWorldId, MapTileProvider.MapType.SATELLITE);
                }
        }

        @Nullable
        public static MapTileProvider getMapTileProvider() {
                return mapTileProvider;
        }

        private static void setupWebserver(Webserver webserver) {
                webserver.addServlet(new ServletHolder(new ResourcePackCreatorWebServlet(WebserverResources::get, "/creator/")), "/creator/*");
                webserver.addServlet(new ServletHolder(new ResourcePackCreatorOperationServlet()), "/mtr/api/creator/operation/*");
                final ServletHolder resourcePackCreatorUploadServletHolder = new ServletHolder(new ResourcePackCreatorUploadServlet());
                resourcePackCreatorUploadServletHolder.getRegistration().setMultipartConfig(new MultipartConfigElement((String) null));
                webserver.addServlet(resourcePackCreatorUploadServletHolder, "/mtr/api/creator/upload/*");
        }

        @FunctionalInterface
        public interface WorldRenderCallback {
                void accept(PoseStack matrixStack, MultiBufferSource vertexConsumerProvider, Vec3 offset);
        }

        /**
         * Serves the Resource Pack Creator website. Unlike the upstream {@code WebServlet}, this implementation does not
         * redirect to the parent path ({@code ..}) when a resource is missing. Previously, if the mod was built without the
         * website build output embedded into {@code WebserverResources} (empty generated class), opening the Resource Pack
         * Creator redirected to {@code /}, which is the Transport System Map page. Now a clear error page is shown instead.
         */
        private static class ResourcePackCreatorWebServlet extends HttpServlet {

                private static final String CREATOR_UNAVAILABLE_PAGE = "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\"><title>Resource Pack Creator Unavailable / 资源包创建器不可用</title>" +
                                "<style>body{font-family:system-ui,sans-serif;background:#f5f5f5;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;color:#333}" +
                                ".card{background:#fff;border-radius:12px;box-shadow:0 2px 16px rgba(0,0,0,.12);max-width:640px;padding:32px 40px;margin:16px}" +
                                "h1{font-size:22px;margin:0 0 6px}h2{font-size:16px;font-weight:400;color:#666;margin:0 0 18px;border-bottom:1px solid #eee;padding-bottom:12px}" +
                                "p,li{font-size:14px;line-height:1.7}code{background:#eee;border-radius:4px;padding:1px 6px;font-size:13px}ol{padding-left:20px}</style></head>" +
                                "<body><div class=\"card\"><h1>⚠️ 资源包创建器不可用</h1><h2>Resource Pack Creator Unavailable</h2>" +
                                "<p>此模组构建中未包含资源包创建器的网页资源（构建模组时未嵌入 <code>website/dist</code> 构建产物），因此无法显示创建器页面。</p>" +
                                "<p>This build of the mod is missing the Resource Pack Creator web assets (the <code>website/dist</code> build output was not embedded when the mod jar was compiled), so the creator cannot be displayed.</p>" +
                                "<p><b>解决方法 / How to fix:</b></p>" +
                                "<ol><li>请从发布页重新下载包含完整资源的构建版本。</li>" +
                                "<li>自行构建时，请先在仓库的 <code>website</code> 目录执行 <code>npm install</code> 和 <code>npm run build</code>，再执行 <code>./gradlew setupFiles</code> 与 <code>./gradlew build</code>。</li>" +
                                "<li>If you built the jar yourself, run <code>npm install</code> and <code>npm run build</code> inside the <code>website</code> directory before <code>./gradlew setupFiles</code> and <code>./gradlew build</code>, then rebuild.</li></ol>" +
                                "</div></body></html>";

                private final Function<String, @Nullable String> contentProvider;
                private final String expectedPath;

                public ResourcePackCreatorWebServlet(Function<String, @Nullable String> contentProvider, String expectedPath) {
                        this.contentProvider = contentProvider;
                        this.expectedPath = expectedPath;
                }

                @Override
                protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
                        final AsyncContext asyncContext = httpServletRequest.startAsync();
                        asyncContext.setTimeout(0);

                        final String requestUri = httpServletRequest.getRequestURI();
                        if (requestUri.startsWith(expectedPath)) {
                                final String path = removeLastSlash(requestUri.replace(expectedPath, ""));
                                final String newPath = path.isEmpty() ? "index.html" : path;
                                final String content = contentProvider.apply(newPath);
                                if (content == null) {
                                        // Show a clear error page instead of redirecting to ".." (the Transport System Map)
                                        ServletBase.sendResponse(httpServletResponse, asyncContext, CREATOR_UNAVAILABLE_PAGE, ServletBase.getMimeType("index.html"), HttpResponseStatus.OK);
                                } else {
                                        ServletBase.sendResponse(httpServletResponse, asyncContext, content, ServletBase.getMimeType(newPath), HttpResponseStatus.OK);
                                }
                        } else {
                                // Redirect "/creator" (no trailing slash) to "/creator/" instead of ".." (which went to the Transport System Map)
                                ServletBase.sendResponse(httpServletResponse, asyncContext, expectedPath, "", HttpResponseStatus.REDIRECT);
                        }
                }

                private static String removeLastSlash(String text) {
                        return text.isEmpty() || text.charAt(text.length() - 1) != '/' ? text : text.substring(0, text.length() - 1);
                }
        }
}
