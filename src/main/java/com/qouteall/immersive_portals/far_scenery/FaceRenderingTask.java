package com.qouteall.immersive_portals.far_scenery;

import com.google.common.collect.Streams;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qouteall.immersive_portals.ducks.IECamera;
import com.qouteall.immersive_portals.ducks.IEWorldRenderer;
import com.qouteall.immersive_portals.my_util.MyTaskList;
import com.qouteall.immersive_portals.render.MyBuiltChunkStorage;
import com.qouteall.immersive_portals.render.SecondaryFrameBuffer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.util.math.Matrix4f;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.dimension.DimensionType;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

public class FaceRenderingTask {
    //compose some small tasks into a big task
    //when running the composed task, firstly invoke preparation func
    //then invoke the small tasks
    //if a small task fails, invoke finish func and the composed task fails
    //when the composed task is invoked again it will rerun the unfinished small tasks
    public static MyTaskList.MyTask composeTask(
        Runnable taskPreparation,
        Runnable taskFinish,
        Iterator<MyTaskList.MyTask> subTasks,
        BooleanSupplier shouldCancelTask
    ) {
        PeekableIterator<MyTaskList.MyTask> subTaskIterator =
            new PeekableIterator<>(subTasks);
        
        return () -> {
            if (shouldCancelTask.getAsBoolean()) {
                return true;
            }
            
            taskPreparation.run();
            
            try {
                for (; ; ) {
                    if (!subTaskIterator.hasNext()) {
                        return true;
                    }
                    MyTaskList.MyTask nextTask = subTaskIterator.peek();
                    boolean result = nextTask.runAndGetIsSucceeded();
                    if (result) {
                        subTaskIterator.next();
                    }
                    else {
                        return false;
                    }
                }
            }
            finally {
                taskFinish.run();
            }
        };
    }
    
    private static MinecraftClient mc = MinecraftClient.getInstance();
    
    public static MyTaskList.MyTask createFarSceneryRenderingTask(
        Vec3d cameraPos,
        DimensionType cameraDimension,
        double nearPlaneDistance,
        int farDistanceChunks,
        SecondaryFrameBuffer[] frameBuffersByFace
    ) {
        RenderScheduler scheduler = new RenderScheduler();
        return composeTask(
            () -> {
            },
            () -> {
            },
            Arrays.stream(Direction.values())
                .map(direction -> createRenderFaceTask(
                    direction, frameBuffersByFace[direction.ordinal()],
                    cameraPos, nearPlaneDistance, farDistanceChunks,
                    scheduler
                )).iterator(),
            () -> mc.world.dimension.getType() != cameraDimension
        );
    }
    
    public static MyTaskList.MyTask createRenderFaceTask(
        Direction direction,
        SecondaryFrameBuffer frameBuffer,
        Vec3d cameraPos,
        double nearPlaneDistance,
        int farDistanceChunks,
        RenderScheduler scheduler
    ) {
        MatrixStack projectionMatrix = getPanoramaProjectionMatrix(farDistanceChunks * 16);
        MatrixStack modelViewMatrix = new MatrixStack();
        
        Camera camera = createCamera(direction, cameraPos);
        
        modelViewMatrix.multiply(Vector3f.POSITIVE_X.getDegreesQuaternion(camera.getPitch()));
        modelViewMatrix.multiply(Vector3f.POSITIVE_Y.getDegreesQuaternion(camera.getYaw() + 180.0F));
        
        Frustum frustum = new Frustum(
            modelViewMatrix.peek().getModel(),
            projectionMatrix.peek().getModel()
        );
        
        frameBuffer.fb.beginWrite(true);
        
        RenderSystem.clearColor(0, 1, 1, 0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, true);
        
        mc.getFramebuffer().beginWrite(true);
        
        List<ChunkBuilder.BuiltChunk> sectionRenderList = SectionRenderListPropagator.getRenderSectionList(
            ((MyBuiltChunkStorage) ((IEWorldRenderer) mc.worldRenderer).getBuiltChunkStorage()),
            new BlockPos(cameraPos),
            farDistanceChunks,
            builtChunk -> frustum.isVisible(builtChunk.boundingBox),
            direction.ordinal()
        );
        
        return composeTask(
            () -> {
                frameBuffer.fb.beginWrite(true);
                pushProjectionMatrix(projectionMatrix);
            },
            () -> {
                mc.getFramebuffer().beginWrite(true);
                popProjectionMatrix();
            },
            Arrays.stream(new RenderLayer[]{
                RenderLayer.getSolid(), RenderLayer.getCutoutMipped(),
                RenderLayer.getCutout(), RenderLayer.getTranslucent()
            }).map(renderLayer -> composeTask(
                () -> beginRenderLayer(renderLayer),
                () -> endRenderLayer(renderLayer),
                Streams.stream(
                    renderLayer == RenderLayer.getTranslucent() ?
                        new ReverseListIterator<>(sectionRenderList) : sectionRenderList.iterator()
                ).map(
                    builtChunk -> scheduler.limitTaskTime(() -> {
                        renderBuiltChunk(
                            builtChunk, renderLayer, cameraPos, modelViewMatrix
                        );
                        return true;
                    })
                ).iterator(),
                () -> false
            )).iterator(),
            () -> false
        );
        
    }
    
    private static void pushProjectionMatrix(MatrixStack matrixStack) {
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        RenderSystem.multMatrix(matrixStack.peek().getModel());
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
    }
    
    private static void popProjectionMatrix() {
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.popMatrix();
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
    }
    
    private static Camera createCamera(Direction direction, Vec3d cameraPos) {
        ClientPlayerEntity player = mc.player;
        float oldYaw = player.yaw;
        float oldPitch = player.pitch;
        Camera camera = new Camera();
        FarSceneryRenderer.setPlayerRotation(direction, player);
        camera.update(
            player.world, player, false, false, 1
        );
        player.yaw = oldYaw;
        player.pitch = oldPitch;
        
        ((IECamera) camera).resetState(cameraPos, mc.world);
        
        return camera;
    }
    
    private static MatrixStack getPanoramaProjectionMatrix(float viewDistance) {
        MatrixStack matrixStack = new MatrixStack();
        matrixStack.peek().getModel().loadIdentity();
        
        matrixStack.peek().getModel().multiply(
            Matrix4f.viewboxMatrix(
                90,
                1,
                0.05F,
                viewDistance
            )
        );
        return matrixStack;
    }
    
    private static VertexFormat getBlockVertexFormat() {
        return VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL;
    }
    
    private static void beginRenderLayer(RenderLayer renderLayer) {
        //TODO translucent sort
    }
    
    private static void endRenderLayer(RenderLayer renderLayer) {
        VertexBuffer.unbind();
        RenderSystem.clearCurrentColor();
        getBlockVertexFormat().endDrawing();
        renderLayer.endDrawing();
    }
    
    private static void renderBuiltChunk(
        ChunkBuilder.BuiltChunk builtChunk,
        RenderLayer renderLayer,
        Vec3d cameraPos,
        MatrixStack matrixStack
    ) {
        if (builtChunk.getData().isEmpty(renderLayer)) {
            return;
        }
        VertexBuffer vertexBuffer = builtChunk.getBuffer(renderLayer);
        matrixStack.push();
        BlockPos blockPos = builtChunk.getOrigin();
        matrixStack.translate(
            (double) blockPos.getX() - cameraPos.x,
            (double) blockPos.getY() - cameraPos.y,
            (double) blockPos.getZ() - cameraPos.z
        );
        vertexBuffer.bind();
        getBlockVertexFormat().startDrawing(0L);
        vertexBuffer.draw(matrixStack.peek().getModel(), 7);
        matrixStack.pop();
    }
    
}