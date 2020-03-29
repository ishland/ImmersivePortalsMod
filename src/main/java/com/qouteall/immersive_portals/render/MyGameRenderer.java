package com.qouteall.immersive_portals.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.qouteall.immersive_portals.CGlobal;
import com.qouteall.immersive_portals.CHelper;
import com.qouteall.immersive_portals.Helper;
import com.qouteall.immersive_portals.McHelper;
import com.qouteall.immersive_portals.OFInterface;
import com.qouteall.immersive_portals.block_manipulation.BlockManipulationClient;
import com.qouteall.immersive_portals.ducks.IECamera;
import com.qouteall.immersive_portals.ducks.IEGameRenderer;
import com.qouteall.immersive_portals.ducks.IEMinecraftClient;
import com.qouteall.immersive_portals.ducks.IEParticleManager;
import com.qouteall.immersive_portals.ducks.IEPlayerListEntry;
import com.qouteall.immersive_portals.ducks.IEWorldRenderer;
import com.qouteall.immersive_portals.ducks.IEWorldRendererChunkInfo;
import com.qouteall.immersive_portals.far_scenery.FSRenderingContext;
import com.qouteall.immersive_portals.far_scenery.FaceRenderingTask;
import com.qouteall.immersive_portals.render.context_management.DimensionRenderHelper;
import com.qouteall.immersive_portals.render.context_management.FogRendererContext;
import com.qouteall.immersive_portals.render.context_management.RenderDimensionRedirect;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.dimension.DimensionType;
import org.lwjgl.opengl.GL11;

import java.util.function.Predicate;

public class MyGameRenderer {
    private static MinecraftClient client = MinecraftClient.getInstance();
    
    public MyGameRenderer() {
    
    }
    
    public static void doPruneVisibleChunks(ObjectList<?> visibleChunks) {
        if (CGlobal.renderer.isRendering()) {
            if (CGlobal.renderFewerInFastGraphic) {
                if (!MinecraftClient.getInstance().options.fancyGraphics) {
                    CGlobal.myGameRenderer.pruneVisibleChunksInFastGraphics(visibleChunks);
                }
            }
        }
        else if (FSRenderingContext.isFarSceneryEnabled) {
            CGlobal.myGameRenderer.pruneVisibleChunksForNearScenery(visibleChunks);
        }
    }
    
    private Camera getNewCamera() {
        IECamera oldCamera = (IECamera) client.gameRenderer.getCamera();
        Camera newCamera = new Camera();
        ((IECamera) newCamera).setCameraY(
            oldCamera.getCameraY(),
            oldCamera.getLastCameraY()
        );
        return newCamera;
    }
    
    public void renderWorld(
        float partialTicks,
        WorldRenderer newWorldRenderer,
        ClientWorld newWorld,
        Vec3d oldCameraPos,
        ClientWorld oldWorld
    ) {
        if (CGlobal.useHackedChunkRenderDispatcher) {
            ((IEWorldRenderer) newWorldRenderer).getBuiltChunkStorage().updateCameraPosition(
                client.cameraEntity.getX(),
                client.cameraEntity.getZ()
            );
        }
        
        IEGameRenderer ieGameRenderer = (IEGameRenderer) client.gameRenderer;
        DimensionRenderHelper helper =
            CGlobal.clientWorldLoader.getDimensionRenderHelper(
                RenderDimensionRedirect.getRedirectedDimension(newWorld.dimension.getType())
            );
        PlayerListEntry playerListEntry = CHelper.getClientPlayerListEntry();
        Camera newCamera = getNewCamera();
        
        //store old state
        WorldRenderer oldWorldRenderer = client.worldRenderer;
        LightmapTextureManager oldLightmap = client.gameRenderer.getLightmapTextureManager();
        GameMode oldGameMode = playerListEntry.getGameMode();
        boolean oldNoClip = client.player.noClip;
        boolean oldDoRenderHand = ieGameRenderer.getDoRenderHand();
        OFInterface.createNewRenderInfosNormal.accept(newWorldRenderer);
        ObjectList oldVisibleChunks = ((IEWorldRenderer) oldWorldRenderer).getVisibleChunks();
        HitResult oldCrosshairTarget = client.crosshairTarget;
        Camera oldCamera = client.gameRenderer.getCamera();
        
        ((IEWorldRenderer) oldWorldRenderer).setVisibleChunks(new ObjectArrayList());
        
        //switch
        ((IEMinecraftClient) client).setWorldRenderer(newWorldRenderer);
        client.world = newWorld;
        ieGameRenderer.setLightmapTextureManager(helper.lightmapTexture);
        helper.lightmapTexture.update(0);
        helper.lightmapTexture.enable();
        BlockEntityRenderDispatcher.INSTANCE.world = newWorld;
        ((IEPlayerListEntry) playerListEntry).setGameMode(GameMode.SPECTATOR);
        client.player.noClip = true;
        ieGameRenderer.setDoRenderHand(false);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        FogRendererContext.swappingManager.pushSwapping(
            RenderDimensionRedirect.getRedirectedDimension(newWorld.dimension.getType())
        );
        ((IEParticleManager) client.particleManager).mySetWorld(newWorld);
        if (BlockManipulationClient.remotePointedDim == newWorld.dimension.getType()) {
            client.crosshairTarget = BlockManipulationClient.remoteHitResult;
        }
        ieGameRenderer.setCamera(newCamera);
        
        client.getProfiler().push("render_portal_content");
        
        //invoke it!
        OFInterface.beforeRenderCenter.accept(partialTicks);
        client.gameRenderer.renderWorld(
            partialTicks, getChunkUpdateFinishTime(),
            new MatrixStack()
        );
        OFInterface.afterRenderCenter.run();
        
        client.getProfiler().pop();
        
        //recover
        ((IEMinecraftClient) client).setWorldRenderer(oldWorldRenderer);
        client.world = oldWorld;
        ieGameRenderer.setLightmapTextureManager(oldLightmap);
        BlockEntityRenderDispatcher.INSTANCE.world = oldWorld;
        ((IEPlayerListEntry) playerListEntry).setGameMode(oldGameMode);
        client.player.noClip = oldNoClip;
        ieGameRenderer.setDoRenderHand(oldDoRenderHand);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        ((IEParticleManager) client.particleManager).mySetWorld(oldWorld);
        client.crosshairTarget = oldCrosshairTarget;
        ieGameRenderer.setCamera(oldCamera);
        
        FogRendererContext.swappingManager.popSwapping();
        
        ((IEWorldRenderer) oldWorldRenderer).setVisibleChunks(oldVisibleChunks);
        //((IECamera) mc.gameRenderer.getCamera()).resetState(oldCameraPos, oldWorld);
    }
    
    private long getChunkUpdateFinishTime() {
        return 0;
    }
    
    public void renderPlayerItself(Runnable doRenderEntity) {
        EntityRenderDispatcher entityRenderDispatcher =
            ((IEWorldRenderer) client.worldRenderer).getEntityRenderDispatcher();
        PlayerListEntry playerListEntry = CHelper.getClientPlayerListEntry();
        GameMode originalGameMode = MyRenderHelper.originalGameMode;
        
        Entity player = client.cameraEntity;
        assert player != null;
        
        Vec3d oldPos = player.getPos();
        Vec3d oldLastTickPos = McHelper.lastTickPosOf(player);
        GameMode oldGameMode = playerListEntry.getGameMode();
        
        McHelper.setPosAndLastTickPos(
            player, MyRenderHelper.originalPlayerPos, MyRenderHelper.originalPlayerLastTickPos
        );
        ((IEPlayerListEntry) playerListEntry).setGameMode(originalGameMode);
        
        doRenderEntity.run();
        
        McHelper.setPosAndLastTickPos(
            player, oldPos, oldLastTickPos
        );
        ((IEPlayerListEntry) playerListEntry).setGameMode(oldGameMode);
    }
    
    public void resetFog() {
        if (OFInterface.isFogDisabled.getAsBoolean()) {
            return;
        }
        
        Camera camera = client.gameRenderer.getCamera();
        float g = client.gameRenderer.getViewDistance();
        
        Vec3d cameraPos = camera.getPos();
        double d = cameraPos.getX();
        double e = cameraPos.getY();
        double f = cameraPos.getZ();
        
        boolean bl2 = client.world.dimension.isFogThick(
            MathHelper.floor(d),
            MathHelper.floor(e)
        ) || client.inGameHud.getBossBarHud().shouldThickenFog();
        
        BackgroundRenderer.applyFog(
            camera,
            BackgroundRenderer.FogType.FOG_TERRAIN,
            Math.max(g - 16.0F, 32.0F),
            bl2
        );
        BackgroundRenderer.setFogBlack();
    }
    
    public void resetDiffuseLighting(MatrixStack matrixStack) {
        DiffuseLighting.enableForLevel(matrixStack.peek().getModel());
    }
    
    //render fewer chunks when rendering portal
    //only active when graphic option is not fancy
    //NOTE we should not prune these chunks in setupTerrain()
    //because if it's pruned there these chunks will be rebuilt
    //then it will generate lag when player cross the portal by building chunks
    //we want the far chunks to be built but not rendered
    public void pruneVisibleChunksInFastGraphics(ObjectList<?> visibleChunks) {
        int renderDistance = client.options.viewDistance;
        Vec3d cameraPos = client.gameRenderer.getCamera().getPos();
        double range = ((renderDistance * 16) / 3) * ((renderDistance * 16) / 3);
        
        Predicate<ChunkBuilder.BuiltChunk> builtChunkPredicate = (builtChunk) -> {
            Vec3d center = builtChunk.boundingBox.getCenter();
            return center.squaredDistanceTo(cameraPos) > range;
        };
        
        pruneVisibleChunks(
            (ObjectList<Object>) visibleChunks,
            builtChunkPredicate
        );
    }
    
    @Deprecated
    public void pruneVisibleChunksForNearScenery(ObjectList<?> visibleChunks) {
        pruneVisibleChunks(
            ((ObjectList<Object>) visibleChunks),
            builtChunk -> !FaceRenderingTask.shouldRenderInNearScenery(builtChunk)
        );
    }
    
    private static void pruneVisibleChunks(
        ObjectList<Object> visibleChunks,
        Predicate<ChunkBuilder.BuiltChunk> builtChunkPredicate
    ) {
        Helper.removeIf(
            visibleChunks,
            obj -> builtChunkPredicate.test(((IEWorldRendererChunkInfo) obj).getBuiltChunk())
        );
    }
    
    public static void renderSkyFor(
        DimensionType dimension,
        MatrixStack matrixStack,
        float partialTicks
    ) {
        ClientWorld newWorld = CGlobal.clientWorldLoader.getWorld(dimension);
        WorldRenderer newWorldRenderer = CGlobal.clientWorldLoader.getWorldRenderer(dimension);
    
        ClientWorld oldWorld = client.world;
        WorldRenderer oldWorldRenderer = client.worldRenderer;
        FogRendererContext.swappingManager.pushSwapping(dimension);
        CGlobal.myGameRenderer.resetFog();

        client.world = newWorld;
        ((IEMinecraftClient)client).setWorldRenderer(newWorldRenderer);

        newWorldRenderer.renderSky(matrixStack, partialTicks);

        client.world = oldWorld;
        ((IEMinecraftClient) client).setWorldRenderer(oldWorldRenderer);
        FogRendererContext.swappingManager.popSwapping();
        CGlobal.myGameRenderer.resetFog();
        
//        client.worldRenderer.renderSky(matrixStack,partialTicks);
    }
    
}
