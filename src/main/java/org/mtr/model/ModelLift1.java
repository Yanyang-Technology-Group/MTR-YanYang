package org.mtr.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.mtr.data.IGui;
import org.mtr.render.MainRenderer;
import org.mtr.render.QueuedRenderLayer;
import org.mtr.render.StoredMatrixTransformations;
import org.mtr.resource.RenderStage;

/**
 * Ported from the YanYang 4.0.5 {@code org.mtr.mod.model.ModelLift1} (mapping-layer version)
 * to the official 4.1.0 mappings and render pipeline.
 *
 * <p>Parameterised procedural lift car model: the floor plan is tiled by {@code width} x
 * {@code depth} blocks, walls are stacked {@code heightCount} segments high and the doors
 * slide along the Z axis (constant-speed {@code DoorAnimationType.CONSTANT} equivalent,
 * {@code doorZ = doorMax * clamp(value, 0, 1) / duration}).</p>
 *
 * <p>Render stages:</p>
 * <ul>
 *   <li>{@link RenderStage#LIGHT} — interior ceiling light, full brightness.</li>
 *   <li>{@link RenderStage#INTERIOR} — interior shell, walls, handrails and doors.</li>
 *   <li>{@link RenderStage#EXTERIOR} — exterior shell and doors.</li>
 * </ul>
 */
public final class ModelLift1 implements IGui {

        private static final float TEXTURE_WIDTH = 128;
        private static final float TEXTURE_HEIGHT = 128;
        private static final float DOOR_DURATION = 0.5F;
        private static final int DOOR_MAX = 24 / 4;

        private final ModelPart main;
        private final ModelPart mainCeiling;
        private final ModelPart mainEdge;
        private final ModelPart mainEdgeWall;
        private final ModelPart mainEdgeCeiling;
        private final ModelPart mainCorner;
        private final ModelPart mainCornerWall;
        private final ModelPart mainCornerCeiling;
        private final ModelPart mainExterior;
        private final ModelPart mainExteriorCeiling;
        private final ModelPart mainExteriorEdge;
        private final ModelPart mainExteriorEdgeCeiling;
        private final ModelPart mainExteriorCorner;
        private final ModelPart mainExteriorCornerWall;
        private final ModelPart mainExteriorCornerCeiling;
        private final ModelPart mainLight;
        private final ModelPart door;
        private final ModelPart doorLeft;
        private final ModelPart doorRight;
        private final ModelPart doorWall;
        private final ModelPart doorCeiling;
        private final ModelPart doorExterior;
        private final ModelPart doorLeftExterior;
        private final ModelPart doorRightExterior;
        private final ModelPart doorWallExterior;
        private final ModelPart doorCeilingExterior;
        private final ModelPart wallPatch;
        private final ModelPart wallPatchWall;

        private final int heightCount;
        private final int heightOffset;
        private final int width;
        private final int depth;
        private final boolean isDoubleSided;

        public ModelLift1(int height, int width, int depth, boolean isDoubleSided) {
                heightCount = height - 4;
                heightOffset = -heightCount * 8;
                this.width = width;
                this.depth = depth;
                this.isDoubleSided = isDoubleSided;

                final MeshDefinition mesh = new MeshDefinition();
                final PartDefinition root = mesh.getRoot();

                final PartDefinition mainDef = root.addOrReplaceChild("main", CubeListBuilder.create()
                                .texOffs(0, 34).addBox(-8, 0, -8, 16, 0, 16), PartPose.offset(0, 24, 0));

                final PartDefinition mainCeilingDef = root.addOrReplaceChild("main_ceiling", CubeListBuilder.create()
                                .texOffs(79, 44).addBox(-8, -32, -8, 16, 0, 16), PartPose.offset(0, 24, 0));

                final PartDefinition mainEdgeDef = root.addOrReplaceChild("main_edge", CubeListBuilder.create()
                                .texOffs(18, 44).addBox(-4, 0, -8, 8, 0, 6)
                                .texOffs(76, 33).addBox(-4, -32, -3, 8, 32, 1)
                                .texOffs(28, 52).addBox(-4, -13, -4, 8, 1, 1)
                                .texOffs(26, 50).addBox(-4, -2, -4, 8, 1, 1), PartPose.offset(0, 24, 0));

                final PartDefinition mainEdgeWallDef = root.addOrReplaceChild("main_edge_wall", CubeListBuilder.create()
                                .texOffs(76, 33).addBox(-4, -40, -3, 8, 8, 1), PartPose.offset(0, 24, 0));

                final PartDefinition mainEdgeCeilingDef = root.addOrReplaceChild("main_edge_ceiling", CubeListBuilder.create()
                                .texOffs(97, 44).addBox(-4, -32, -8, 8, 0, 6), PartPose.offset(0, 24, 0));

                final PartDefinition mainCornerDef = root.addOrReplaceChild("main_corner", CubeListBuilder.create()
                                .texOffs(20, 44).addBox(2, 0, -8, 6, 0, 6)
                                .texOffs(112, 62).addBox(3, -32, -3, 5, 32, 1)
                                .texOffs(29, 52).addBox(5, -13, -4, 3, 1, 1)
                                .texOffs(27, 50).addBox(5, -2, -4, 3, 1, 1)
                                .texOffs(104, 68).addBox(2, -32, -3, 1, 32, 1), PartPose.offset(0, 24, 0));
                mainCornerDef.addOrReplaceChild("handrail_bottom", CubeListBuilder.create()
                                .texOffs(36, 50).addBox(-8, -2, -4, 3, 1, 1)
                                .texOffs(38, 52).addBox(-8, -13, -4, 3, 1, 1)
                                .texOffs(112, 62).addBox(-8, -32, -3, 5, 32, 1), PartPose.offsetAndRotation(0, 0, 0, 0, -1.5708F, 0));

                final PartDefinition mainCornerWallDef = root.addOrReplaceChild("main_corner_wall", CubeListBuilder.create()
                                .texOffs(112, 62).addBox(3, -40, -3, 5, 8, 1)
                                .texOffs(104, 68).addBox(2, -40, -3, 1, 8, 1), PartPose.offset(0, 24, 0));
                mainCornerWallDef.addOrReplaceChild("wall", CubeListBuilder.create()
                                .texOffs(112, 62).addBox(-8, -40, -3, 5, 8, 1), PartPose.offsetAndRotation(0, 0, 0, 0, -1.5708F, 0));

                final PartDefinition mainCornerCeilingDef = root.addOrReplaceChild("main_corner_ceiling", CubeListBuilder.create()
                                .texOffs(99, 44).addBox(2, -32, -8, 6, 0, 6), PartPose.offset(0, 24, 0));

                final PartDefinition mainExteriorDef = root.addOrReplaceChild("main_exterior", CubeListBuilder.create()
                                .texOffs(0, 17).addBox(-8, 0, -8, 16, 1, 16), PartPose.offset(0, 24, 0));

                final PartDefinition mainExteriorCeilingDef = root.addOrReplaceChild("main_exterior_ceiling", CubeListBuilder.create()
                                .texOffs(0, 0).addBox(-8, -33, -8, 16, 1, 16), PartPose.offset(0, 24, 0));

                final PartDefinition mainExteriorEdgeDef = root.addOrReplaceChild("main_exterior_edge", CubeListBuilder.create()
                                .texOffs(18, 27).addBox(-4, 0, -8, 8, 1, 6), PartPose.offset(0, 24, 0));

                final PartDefinition mainExteriorEdgeCeilingDef = root.addOrReplaceChild("main_exterior_edge_ceiling", CubeListBuilder.create()
                                .texOffs(18, 10).addBox(-4, -33, -8, 8, 1, 6), PartPose.offset(0, 24, 0));

                final PartDefinition mainExteriorCornerDef = root.addOrReplaceChild("main_exterior_corner", CubeListBuilder.create()
                                .texOffs(20, 27).addBox(2, 0, -8, 6, 1, 6), PartPose.offset(0, 24, 0));

                final PartDefinition mainExteriorCornerWallDef = root.addOrReplaceChild("main_exterior_corner_wall", CubeListBuilder.create()
                                .texOffs(108, 68).addBox(2, -40, -3, 1, 8, 1), PartPose.offset(0, 24, 0));

                final PartDefinition mainExteriorCornerCeilingDef = root.addOrReplaceChild("main_exterior_corner_ceiling", CubeListBuilder.create()
                                .texOffs(20, 10).addBox(2, -33, -8, 6, 1, 6), PartPose.offset(0, 24, 0));

                final PartDefinition mainLightDef = root.addOrReplaceChild("main_light", CubeListBuilder.create()
                                .texOffs(79, 28).addBox(-8, -32.5F, -8, 16, 0, 16), PartPose.offset(0, 24, 0));

                final PartDefinition doorDef = root.addOrReplaceChild("door", CubeListBuilder.create()
                                .texOffs(90, 66).addBox(-16, -32, 4, 4, 32, 3)
                                .texOffs(14, 84).addBox(12, -32, 4, 4, 32, 3)
                                .texOffs(20, 101).addBox(-16, 0, 0, 32, 0, 8), PartPose.offset(0, 24, 0));

                final PartDefinition doorLeftDef = root.addOrReplaceChild("door_left", CubeListBuilder.create()
                                .texOffs(52, 68).addBox(-12, -32, 6, 12, 32, 0), PartPose.offset(0, 24, 0));

                final PartDefinition doorRightDef = root.addOrReplaceChild("door_right", CubeListBuilder.create()
                                .texOffs(28, 68).addBox(0, -32, 6, 12, 32, 0), PartPose.offset(0, 24, 0));

                final PartDefinition doorWallDef = root.addOrReplaceChild("door_wall", CubeListBuilder.create()
                                .texOffs(48, 0).addBox(-16, -40, 4, 32, 8, 3), PartPose.offset(0, 24, 0));

                final PartDefinition doorCeilingDef = root.addOrReplaceChild("door_ceiling", CubeListBuilder.create()
                                .texOffs(20, 101).addBox(-16, -32, 0, 32, 0, 8), PartPose.offset(0, 24, 0));

                final PartDefinition doorExteriorDef = root.addOrReplaceChild("door_exterior", CubeListBuilder.create()
                                .texOffs(0, 84).addBox(-16, -32, 4, 4, 32, 3)
                                .texOffs(76, 66).addBox(12, -32, 4, 4, 32, 3)
                                .texOffs(28, 109).addBox(-16, 0, 0, 32, 1, 8), PartPose.offset(0, 24, 0));

                final PartDefinition doorLeftExteriorDef = root.addOrReplaceChild("door_left_exterior", CubeListBuilder.create()
                                .texOffs(0, 50).addBox(-12, -32, 6, 12, 32, 2), PartPose.offset(0, 24, 0));

                final PartDefinition doorRightExteriorDef = root.addOrReplaceChild("door_right_exterior", CubeListBuilder.create()
                                .texOffs(48, 34).addBox(0, -32, 6, 12, 32, 2), PartPose.offset(0, 24, 0));

                final PartDefinition doorWallExteriorDef = root.addOrReplaceChild("door_wall_exterior", CubeListBuilder.create()
                                .texOffs(48, 17).addBox(-16, -40, 5, 32, 8, 3), PartPose.offset(0, 24, 0));

                final PartDefinition doorCeilingExteriorDef = root.addOrReplaceChild("door_ceiling_exterior", CubeListBuilder.create()
                                .texOffs(0, 119).addBox(-16, -33, 0, 32, 1, 8), PartPose.offset(0, 24, 0));

                final PartDefinition wallPatchDef = root.addOrReplaceChild("wall_patch", CubeListBuilder.create(), PartPose.offset(0, 24, 0));
                wallPatchDef.addOrReplaceChild("wall_1", CubeListBuilder.create()
                                .texOffs(108, 95).addBox(0, -32, 13, 4, 32, 1)
                                .texOffs(30, 50).addBox(0, -2, 12, 4, 1, 1)
                                .texOffs(32, 52).addBox(0, -13, 12, 4, 1, 1), PartPose.offsetAndRotation(0, 0, 0, 0, -1.5708F, 0));
                wallPatchDef.addOrReplaceChild("wall_2", CubeListBuilder.create()
                                .texOffs(108, 95).addBox(-4, -32, 13, 4, 32, 1)
                                .texOffs(30, 50).addBox(-4, -2, 12, 4, 1, 1)
                                .texOffs(32, 52).addBox(-4, -13, 12, 4, 1, 1), PartPose.offsetAndRotation(0, 0, 0, 0, 1.5708F, 0));

                final PartDefinition wallPatchWallDef = root.addOrReplaceChild("wall_patch_wall", CubeListBuilder.create(), PartPose.offset(0, 24, 0));
                wallPatchWallDef.addOrReplaceChild("wall_3", CubeListBuilder.create()
                                .texOffs(108, 95).addBox(0, -40, 13, 4, 8, 1), PartPose.offsetAndRotation(0, 0, 0, 0, -1.5708F, 0));
                wallPatchWallDef.addOrReplaceChild("wall_4", CubeListBuilder.create()
                                .texOffs(108, 95).addBox(-4, -40, 13, 4, 8, 1), PartPose.offsetAndRotation(0, 0, 0, 0, 1.5708F, 0));

                final ModelPart rootPart = root.bake((int) TEXTURE_WIDTH, (int) TEXTURE_HEIGHT);
                main = rootPart.getChild("main");
                mainCeiling = rootPart.getChild("main_ceiling");
                mainEdge = rootPart.getChild("main_edge");
                mainEdgeWall = rootPart.getChild("main_edge_wall");
                mainEdgeCeiling = rootPart.getChild("main_edge_ceiling");
                mainCorner = rootPart.getChild("main_corner");
                mainCornerWall = rootPart.getChild("main_corner_wall");
                mainCornerCeiling = rootPart.getChild("main_corner_ceiling");
                mainExterior = rootPart.getChild("main_exterior");
                mainExteriorCeiling = rootPart.getChild("main_exterior_ceiling");
                mainExteriorEdge = rootPart.getChild("main_exterior_edge");
                mainExteriorEdgeCeiling = rootPart.getChild("main_exterior_edge_ceiling");
                mainExteriorCorner = rootPart.getChild("main_exterior_corner");
                mainExteriorCornerWall = rootPart.getChild("main_exterior_corner_wall");
                mainExteriorCornerCeiling = rootPart.getChild("main_exterior_corner_ceiling");
                mainLight = rootPart.getChild("main_light");
                door = rootPart.getChild("door");
                doorLeft = rootPart.getChild("door_left");
                doorRight = rootPart.getChild("door_right");
                doorWall = rootPart.getChild("door_wall");
                doorCeiling = rootPart.getChild("door_ceiling");
                doorExterior = rootPart.getChild("door_exterior");
                doorLeftExterior = rootPart.getChild("door_left_exterior");
                doorRightExterior = rootPart.getChild("door_right_exterior");
                doorWallExterior = rootPart.getChild("door_wall_exterior");
                doorCeilingExterior = rootPart.getChild("door_ceiling_exterior");
                wallPatch = rootPart.getChild("wall_patch");
                wallPatchWall = rootPart.getChild("wall_patch_wall");
        }

        /**
         * Schedules the lift car onto the MTR render queue (light / interior / exterior passes),
         * mirroring the {@code ModelTrainBase.render} flow with {@code lightsOn = true} and
         * {@code isTranslucent = false}.
         *
         * @param storedMatrixTransformations the lift transformation
         * @param texture                     the lift texture
         * @param light                       the packed light at the lift position
         * @param doorLeftValue               normalised (0-1) left door opening progress
         * @param doorRightValue              normalised (0-1) right door opening progress
         */
        public void render(StoredMatrixTransformations storedMatrixTransformations, ResourceLocation texture, int light, float doorLeftValue, float doorRightValue) {
                final float doorLeftZ = DOOR_MAX * Math.max(0, Math.min(1, doorLeftValue)) / DOOR_DURATION;
                final float doorRightZ = DOOR_MAX * Math.max(0, Math.min(1, doorRightValue)) / DOOR_DURATION;

                final StoredMatrixTransformations storedMatrixTransformationsNew = storedMatrixTransformations.copy();
                storedMatrixTransformationsNew.add(matrixStack -> matrixStack.translate(0, -1.5, 0));

                MainRenderer.scheduleRender(texture, false, QueuedRenderLayer.LIGHT, (matrixStack, vertexConsumer, offset) -> {
                        storedMatrixTransformationsNew.transform(matrixStack, offset);
                        render(matrixStack, vertexConsumer, RenderStage.LIGHT, DEFAULT_LIGHT, doorLeftZ, doorRightZ);
                        matrixStack.popPose();
                });
                MainRenderer.scheduleRender(texture, false, QueuedRenderLayer.INTERIOR, (matrixStack, vertexConsumer, offset) -> {
                        storedMatrixTransformationsNew.transform(matrixStack, offset);
                        render(matrixStack, vertexConsumer, RenderStage.INTERIOR, MAX_LIGHT_INTERIOR, doorLeftZ, doorRightZ);
                        matrixStack.popPose();
                });
                MainRenderer.scheduleRender(texture, false, QueuedRenderLayer.EXTERIOR, (matrixStack, vertexConsumer, offset) -> {
                        storedMatrixTransformationsNew.transform(matrixStack, offset);
                        render(matrixStack, vertexConsumer, RenderStage.EXTERIOR, light, doorLeftZ, doorRightZ);
                        matrixStack.popPose();
                });
        }

        private void render(PoseStack matrixStack, VertexConsumer vertexConsumer, RenderStage renderStage, int light, float doorLeftZ, float doorRightZ) {
                for (int i = 0; i <= width; i++) {
                        for (int j = 0; j <= depth; j++) {
                                final float x = (i - width / 2F) * 16;
                                final float z = (j - depth / 2F) * 16;

                                final boolean edge1X = i == 0;
                                final boolean edge2X = i == width;
                                final boolean edge1Z = j == 0;
                                final boolean edge2Z = j == depth;

                                switch (renderStage) {
                                        case LIGHT:
                                                if (!edge1X && !edge2X && !edge1Z && !edge2Z) {
                                                        renderPart(matrixStack, vertexConsumer, mainLight, light, x, heightOffset, z, 0);
                                                }
                                                break;
                                        case INTERIOR:
                                        case EXTERIOR:
                                                final ModelPart mainPiece = renderStage == RenderStage.INTERIOR ? main : mainExterior;
                                                final ModelPart mainCeilingPiece = renderStage == RenderStage.INTERIOR ? mainCeiling : mainExteriorCeiling;
                                                final ModelPart mainEdgePiece = renderStage == RenderStage.INTERIOR ? mainEdge : mainExteriorEdge;
                                                final ModelPart mainEdgeCeilingPiece = renderStage == RenderStage.INTERIOR ? mainEdgeCeiling : mainExteriorEdgeCeiling;
                                                final ModelPart mainCornerPiece = renderStage == RenderStage.INTERIOR ? mainCorner : mainExteriorCorner;
                                                final ModelPart mainCornerWallPiece = renderStage == RenderStage.INTERIOR ? mainCornerWall : mainExteriorCornerWall;
                                                final ModelPart mainCornerCeilingPiece = renderStage == RenderStage.INTERIOR ? mainCornerCeiling : mainExteriorCornerCeiling;

                                                if (!edge1X && !edge2X && !edge1Z && !edge2Z) {
                                                        renderPart(matrixStack, vertexConsumer, mainPiece, light, x, 0, z, 0);
                                                        renderPart(matrixStack, vertexConsumer, mainCeilingPiece, light, x, heightOffset, z, 0);
                                                }

                                                if (edge1X && !edge2X && !edge1Z && !edge2Z) {
                                                        renderPart(matrixStack, vertexConsumer, mainEdgePiece, light, x, 0, z - 4, (float) -Math.PI / 2);
                                                        renderPart(matrixStack, vertexConsumer, mainEdgePiece, light, x, 0, z + 4, (float) -Math.PI / 2);
                                                        if (renderStage == RenderStage.INTERIOR) {
                                                                renderWall(matrixStack, vertexConsumer, mainEdgeWall, light, x, z - 4, (float) -Math.PI / 2);
                                                                renderWall(matrixStack, vertexConsumer, mainEdgeWall, light, x, z + 4, (float) -Math.PI / 2);
                                                        }
                                                        renderPart(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, x, heightOffset, z - 4, (float) -Math.PI / 2);
                                                        renderPart(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, x, heightOffset, z + 4, (float) -Math.PI / 2);
                                                }
                                                if (!edge1X && edge2X && !edge1Z && !edge2Z) {
                                                        renderPart(matrixStack, vertexConsumer, mainEdgePiece, light, x, 0, z - 4, (float) Math.PI / 2);
                                                        renderPart(matrixStack, vertexConsumer, mainEdgePiece, light, x, 0, z + 4, (float) Math.PI / 2);
                                                        if (renderStage == RenderStage.INTERIOR) {
                                                                renderWall(matrixStack, vertexConsumer, mainEdgeWall, light, x, z - 4, (float) Math.PI / 2);
                                                                renderWall(matrixStack, vertexConsumer, mainEdgeWall, light, x, z + 4, (float) Math.PI / 2);
                                                        }
                                                        renderPart(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, x, heightOffset, z - 4, (float) Math.PI / 2);
                                                        renderPart(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, x, heightOffset, z + 4, (float) Math.PI / 2);
                                                }
                                                if (!edge1X && !edge2X && !edge1Z && edge2Z && !isDoubleSided) {
                                                        renderPart(matrixStack, vertexConsumer, mainEdgePiece, light, x - 4, 0, z, 0);
                                                        renderPart(matrixStack, vertexConsumer, mainEdgePiece, light, x + 4, 0, z, 0);
                                                        if (renderStage == RenderStage.INTERIOR) {
                                                                renderWallOnce(matrixStack, vertexConsumer, mainEdgeWall, light, x - 4, z);
                                                                renderWallOnce(matrixStack, vertexConsumer, mainEdgeWall, light, x + 4, z);
                                                        }
                                                        renderPart(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, x - 4, heightOffset, z, 0);
                                                        renderPart(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, x + 4, heightOffset, z, 0);
                                                }

                                                if (edge1X && !edge2X && !edge1Z && edge2Z && (width > 2 || !isDoubleSided)) {
                                                        renderPart(matrixStack, vertexConsumer, mainCornerPiece, light, x, 0, z, 0);
                                                        if (renderStage == RenderStage.INTERIOR) {
                                                                renderWall(matrixStack, vertexConsumer, mainCornerWallPiece, light, x, z, 0);
                                                        }
                                                        renderPart(matrixStack, vertexConsumer, mainCornerCeilingPiece, light, x, heightOffset, z, 0);
                                                }
                                                if (!edge1X && edge2X && !edge1Z && edge2Z && (width > 2 || !isDoubleSided)) {
                                                        renderPart(matrixStack, vertexConsumer, mainCornerPiece, light, x, 0, z, (float) Math.PI / 2);
                                                        if (renderStage == RenderStage.INTERIOR) {
                                                                renderWall(matrixStack, vertexConsumer, mainCornerWallPiece, light, x, z, (float) Math.PI / 2);
                                                        }
                                                        renderPart(matrixStack, vertexConsumer, mainCornerCeilingPiece, light, x, heightOffset, z, (float) Math.PI / 2);
                                                }
                                                if (edge1X && !edge2X && edge1Z && !edge2Z && width > 2) {
                                                        renderPart(matrixStack, vertexConsumer, mainCornerPiece, light, x, 0, z, (float) -Math.PI / 2);
                                                        if (renderStage == RenderStage.INTERIOR) {
                                                                renderWall(matrixStack, vertexConsumer, mainCornerWallPiece, light, x, z, (float) -Math.PI / 2);
                                                        }
                                                        renderPart(matrixStack, vertexConsumer, mainCornerCeilingPiece, light, x, heightOffset, z, (float) -Math.PI / 2);
                                                }
                                                if (!edge1X && edge2X && edge1Z && !edge2Z && width > 2) {
                                                        renderPart(matrixStack, vertexConsumer, mainCornerPiece, light, x, 0, z, (float) Math.PI);
                                                        if (renderStage == RenderStage.INTERIOR) {
                                                                renderWall(matrixStack, vertexConsumer, mainCornerWallPiece, light, x, z, (float) Math.PI);
                                                        }
                                                        renderPart(matrixStack, vertexConsumer, mainCornerCeilingPiece, light, x, heightOffset, z, (float) Math.PI);
                                                }

                                                break;
                                }
                        }
                }

                if (renderStage == RenderStage.INTERIOR || renderStage == RenderStage.EXTERIOR) {
                        final ModelPart doorLeftPiece = renderStage == RenderStage.INTERIOR ? doorLeft : doorLeftExterior;
                        final ModelPart doorRightPiece = renderStage == RenderStage.INTERIOR ? doorRight : doorRightExterior;
                        final ModelPart doorPiece = renderStage == RenderStage.INTERIOR ? door : doorExterior;
                        final ModelPart doorWallPiece = renderStage == RenderStage.INTERIOR ? doorWall : doorWallExterior;
                        final ModelPart doorCeilingPiece = renderStage == RenderStage.INTERIOR ? doorCeiling : doorCeilingExterior;
                        final ModelPart mainEdgePiece = renderStage == RenderStage.INTERIOR ? mainEdge : mainExteriorEdge;
                        final ModelPart mainEdgeCeilingPiece = renderStage == RenderStage.INTERIOR ? mainEdgeCeiling : mainExteriorEdgeCeiling;

                        renderPartFlipped(matrixStack, vertexConsumer, doorLeftPiece, light, -doorLeftZ, 0, 8 - depth * 8);
                        renderPartFlipped(matrixStack, vertexConsumer, doorRightPiece, light, doorLeftZ, 0, 8 - depth * 8);
                        renderPartFlipped(matrixStack, vertexConsumer, doorPiece, light, 0, 0, 8 - depth * 8);
                        renderWallOnceFlipped(matrixStack, vertexConsumer, doorWallPiece, light, 0, 8 - depth * 8);
                        renderPartFlipped(matrixStack, vertexConsumer, doorCeilingPiece, light, 0, heightOffset, 8 - depth * 8);

                        if (isDoubleSided) {
                                renderPart(matrixStack, vertexConsumer, doorLeftPiece, light, -doorRightZ, 0, -8 + depth * 8, 0);
                                renderPart(matrixStack, vertexConsumer, doorRightPiece, light, doorRightZ, 0, -8 + depth * 8, 0);
                                renderPart(matrixStack, vertexConsumer, doorPiece, light, 0, 0, -8 + depth * 8, 0);
                                renderWallOnce(matrixStack, vertexConsumer, doorWallPiece, light, 0, -8 + depth * 8);
                                renderPart(matrixStack, vertexConsumer, doorCeilingPiece, light, 0, heightOffset, -8 + depth * 8, 0);
                        }

                        if (renderStage == RenderStage.INTERIOR && width == 2) {
                                renderPartFlipped(matrixStack, vertexConsumer, wallPatch, light, 0, 0, 8 - depth * 8);
                                renderWallOnceFlipped(matrixStack, vertexConsumer, wallPatchWall, light, 0, 8 - depth * 8);
                                if (isDoubleSided) {
                                        renderPart(matrixStack, vertexConsumer, wallPatch, light, 0, 0, -8 + depth * 8, 0);
                                        renderWallOnce(matrixStack, vertexConsumer, wallPatchWall, light, 0, -8 + depth * 8);
                                }
                        }

                        for (int i = 1; i < width - 2; i++) {
                                renderPartFlipped(matrixStack, vertexConsumer, mainEdgePiece, light, i * 8 - width * 8 + 4, 0, -depth * 8);
                                renderPartFlipped(matrixStack, vertexConsumer, mainEdgePiece, light, -i * 8 + width * 8 - 4, 0, -depth * 8);
                                if (renderStage == RenderStage.INTERIOR) {
                                        renderWallOnceFlipped(matrixStack, vertexConsumer, mainEdgeWall, light, i * 8 - width * 8 + 4, -depth * 8);
                                        renderWallOnceFlipped(matrixStack, vertexConsumer, mainEdgeWall, light, -i * 8 + width * 8 - 4, -depth * 8);
                                }
                                renderPartFlipped(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, i * 8 - width * 8 + 4, heightOffset, -depth * 8);
                                renderPartFlipped(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, -i * 8 + width * 8 - 4, heightOffset, -depth * 8);
                                if (isDoubleSided) {
                                        renderPart(matrixStack, vertexConsumer, mainEdgePiece, light, i * 8 - width * 8 + 4, 0, depth * 8, 0);
                                        renderPart(matrixStack, vertexConsumer, mainEdgePiece, light, -i * 8 + width * 8 - 4, 0, depth * 8, 0);
                                        if (renderStage == RenderStage.INTERIOR) {
                                                renderWallOnce(matrixStack, vertexConsumer, mainEdgeWall, light, i * 8 - width * 8 + 4, depth * 8);
                                                renderWallOnce(matrixStack, vertexConsumer, mainEdgeWall, light, -i * 8 + width * 8 - 4, depth * 8);
                                        }
                                        renderPart(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, i * 8 - width * 8 + 4, heightOffset, depth * 8, 0);
                                        renderPart(matrixStack, vertexConsumer, mainEdgeCeilingPiece, light, -i * 8 + width * 8 - 4, heightOffset, depth * 8, 0);
                                }
                        }
                }
        }

        /**
         * Renders a single model part with an extra offset (in model pixels) and Y rotation,
         * equivalent to the mapping layer's {@code ModelPartExtension.render}, which temporarily
         * overwrites the part pivot and yaw before rendering.
         */
        private static void renderPart(PoseStack matrixStack, VertexConsumer vertexConsumer, ModelPart part, int light, float x, float y, float z, float rotateY) {
                part.x = x;
                part.y = y;
                part.z = z;
                part.yRot = rotateY;
                part.render(matrixStack, vertexConsumer, light, OverlayTexture.NO_OVERLAY);
        }

        private static void renderPartFlipped(PoseStack matrixStack, VertexConsumer vertexConsumer, ModelPart part, int light, float x, float y, float z) {
                renderPart(matrixStack, vertexConsumer, part, light, -x, y, z, (float) Math.PI);
        }

        private void renderWall(PoseStack matrixStack, VertexConsumer vertexConsumer, ModelPart part, int light, float x, float z, float rotateY) {
                for (int i = 0; i < heightCount; i++) {
                        renderPart(matrixStack, vertexConsumer, part, light, x, -i * 8, z, rotateY);
                }
        }

        private void renderWallOnce(PoseStack matrixStack, VertexConsumer vertexConsumer, ModelPart part, int light, float x, float z) {
                for (int i = 0; i < heightCount; i++) {
                        renderPart(matrixStack, vertexConsumer, part, light, x, -i * 8, z, 0);
                }
        }

        private void renderWallOnceFlipped(PoseStack matrixStack, VertexConsumer vertexConsumer, ModelPart part, int light, float x, float z) {
                for (int i = 0; i < heightCount; i++) {
                        renderPartFlipped(matrixStack, vertexConsumer, part, light, x, -i * 8, z);
                }
        }
}
