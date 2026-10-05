package org.mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import org.mtr.core.data.Passenger;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/**
 * Renders AI passengers with a directly-driven {@link PlayerModel} instead of going through
 * the entity render dispatcher. This gives full control over:
 *
 * <ul>
 *   <li><b>Walking animation</b> — limb swing phase and amplitude are integrated from
 *       the passenger's analytical walking speed, with smooth amount transitions between walking
 *       and standing. Mirrors the vanilla {@code WalkAnimationState} semantics exactly:
 *       {@code limbSwing} is the equivalent of {@code walkAnimation.position()} (accumulates the
 *       smoothed per-tick speed, which vanilla derives as {@code min(distancePerTick * 4, 1)}),
 *       and {@code limbSwingAmount} is the equivalent of {@code walkAnimation.speed()}.</li>
 *   <li><b>Idle head movement</b> — standing passengers slowly look around with deterministic
 *       per-passenger targets.</li>
 *   <li><b>Phone pose</b> — a deterministic subset of standing passengers (platform waiters
 *       and on-board standees) look down at a phone: both forearms raised, head tilted
 *       forward, with subtle scrolling micro-movement.</li>
 *   <li><b>Skins</b> — textures come from {@link PassengerSkinManager} (vanilla default pool
 *       plus optional online skins); slim skins select the slim arm model.</li>
 * </ul>
 *
 * <p>The pose is applied by writing the {@link ModelPart} rotations directly (using the
 * vanilla {@code HumanoidModel.setupAnim} formulas, verified against the 1.21.1 bytecode),
 * so the renderer is independent of the entity-vs-render-state model API change in 1.21.4+.
 * The expected matrix state on entry is identical to what the previous
 * {@code EntityRenderDispatcher.render(entity, 0, 0, 0, 0, ...)} call expected: translated to
 * the passenger position with the body yaw already applied. The vanilla
 * {@code LivingEntityRenderer} core transform (model flip, 0.9375 scale, -1.501 offset,
 * 180-degree body rotation) is applied internally.</p>
 */
public final class PassengerModelRenderer {

	private static final int ANIMATION_CACHE_LIMIT = 4096;
	private static final float MIN_WALK_AMOUNT = 0.12F;
	/**
	 * Vanilla smooths the walk amount with a 0.4 lerp per tick; at 20 ticks/s that is a
	 * continuous rate of -20*ln(0.6) ≈ 10.2/s.
	 */
	private static final float AMOUNT_SMOOTHING_PER_SECOND = 10.2F;
	private static final float PHONE_POSE_CHANCE = 0.35F;
	private static final float PHONE_BLEND_PER_SECOND = 4F;

	private static final long GOLDEN_RATIO_FRACTION = 0x9E3779B97F4A7C15L;
	private static final int RESOLUTION = 1000000;

	private static final float DEG_TO_RAD = (float) (Math.PI / 180);

	private static final Long2ObjectLinkedOpenHashMap<AnimationState> animationStates = new Long2ObjectLinkedOpenHashMap<>();

	private static PlayerModel wideModel;
	private static PlayerModel slimModel;

	private PassengerModelRenderer() {
	}

	/**
	 * Renders one AI passenger.
	 *
	 * @param clientLevel   the client level (used for the animation clock)
	 * @param passenger     the passenger (id drives all deterministic randomisation)
	 * @param walkingSpeed  the analytical horizontal walking speed in blocks per second;
	 *                      {@code <= 0} means standing
	 * @param matrixStack   the matrix stack, already translated and rotated to the passenger
	 *                      position / body yaw
	 * @param bufferSource  the buffer source to draw the model into
	 * @param light         the packed light at the passenger position
	 */
	public static void renderPassenger(ClientLevel clientLevel, Passenger passenger, double walkingSpeed, PoseStack matrixStack, MultiBufferSource.BufferSource bufferSource, int light) {
		final long passengerId = passenger.getId();
		final AnimationState state = getAnimationState(passengerId);
		final boolean standing = walkingSpeed <= 0.001;
		state.update(passengerId, walkingSpeed, standing);

		final PlayerSkin skin = PassengerSkinManager.getSkin(passengerId);
		final PlayerModel model = skin.model() == PlayerSkin.Model.SLIM ? getSlimModel() : getWideModel();

		final float ageInTicks = clientLevel.getGameTime() % 100000 + 1;

		matrixStack.pushPose();
		// Vanilla LivingEntityRenderer core transform for players. The ordering is exactly
		// equivalent to the vanilla sequence (setupRotations YP180, then flip, then 0.9375
		// scale, then the -1.501 offset): all factors are diagonal so they commute, and the
		// Y translation is unaffected by the YP180 rotation.
		matrixStack.scale(-1.0F, -1.0F, 1.0F);
		matrixStack.scale(0.9375F, 0.9375F, 0.9375F);
		matrixStack.translate(0.0F, -1.501F, 0.0F);
		matrixStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));

		// Standard walk cycle and head angles from the animation state.
		applyWalkPose(model, state);

		// Phone pose override on top of the standard pose.
		final float phoneBlend = state.phoneBlend;
		if (phoneBlend > 0.001F) {
			applyPhonePose(model, phoneBlend, ageInTicks);
		}

		// The overlay layers (sleeves, pants, jacket, hat) are separate parts; re-copy the
		// modified angles into them (vanilla does this inside setupAnim before any pose
		// overrides, so this must run after the overrides).
		copyPart(model.hat, model.head);
		copyPart(model.leftSleeve, model.leftArm);
		copyPart(model.rightSleeve, model.rightArm);
		copyPart(model.leftPants, model.leftLeg);
		copyPart(model.rightPants, model.rightLeg);
		copyPart(model.jacket, model.body);

		final VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(skin.texture()));
		model.renderToBuffer(matrixStack, vertexConsumer, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);

		matrixStack.popPose();
	}

	/**
	 * Applies the walk cycle and head angles, using the vanilla {@code HumanoidModel.setupAnim}
	 * formulas (1.21.1 bytecode; the elytra divisor is fixed at 1 because passengers never
	 * fall-fly):
	 *
	 * <pre>
	 * rightLeg.xRot = cos(limbSwing * 0.6662) * 1.4 * amount
	 * leftLeg.xRot  = cos(limbSwing * 0.6662 + pi) * 1.4 * amount
	 * rightArm.xRot = cos(limbSwing * 0.6662 + pi) * amount        (2 * 0.5)
	 * leftArm.xRot  = cos(limbSwing * 0.6662) * amount
	 * </pre>
	 */
	private static void applyWalkPose(PlayerModel model, AnimationState state) {
		final float limbSwing = state.limbSwing;
		final float amount = state.limbSwingAmount;

		model.rightLeg.xRot = Mth.cos(limbSwing * 0.6662F) * 1.4F * amount;
		model.leftLeg.xRot = Mth.cos(limbSwing * 0.6662F + (float) Math.PI) * 1.4F * amount;
		model.rightArm.xRot = Mth.cos(limbSwing * 0.6662F + (float) Math.PI) * amount;
		model.leftArm.xRot = Mth.cos(limbSwing * 0.6662F) * amount;
		model.rightArm.zRot = 0;
		model.leftArm.zRot = 0;
		model.body.yRot = 0;

		// Head angles are stored in degrees; converted like vanilla setupAnim.
		model.head.yRot = state.headYaw * DEG_TO_RAD;
		model.head.xRot = state.headPitch * DEG_TO_RAD;
	}

	/**
	 * Overlays the "looking at a phone" pose on the standard animation: both forearms raised
	 * in front of the chest, head tilted down, with a subtle scrolling micro-movement.
	 */
	private static void applyPhonePose(PlayerModel model, float blend, float ageInTicks) {
		final float scrollWobble = (float) Math.sin(ageInTicks * 0.23F) * 2;
		final float scrollWobbleSlow = (float) Math.sin(ageInTicks * 0.11F) * 1.5F;

		lerpRot(model.rightArm, (float) Math.toRadians(-67 + scrollWobbleSlow), (float) Math.toRadians(-14), (float) Math.toRadians(-6), blend);
		lerpRot(model.leftArm, (float) Math.toRadians(-60 - scrollWobbleSlow), (float) Math.toRadians(12), (float) Math.toRadians(7), blend);
		lerpRot(model.head, (float) Math.toRadians(34 + scrollWobble * 0.5F), (float) Math.toRadians(7), 0, blend);
	}

	private static void lerpRot(ModelPart part, float xRot, float yRot, float zRot, float blend) {
		part.xRot = lerp(part.xRot, xRot, blend);
		part.yRot = lerp(part.yRot, yRot, blend);
		part.zRot = lerp(part.zRot, zRot, blend);
	}

	private static float lerp(float from, float to, float factor) {
		return from + (to - from) * factor;
	}

	/**
	 * Copies pose fields from one part to another, mirroring the vanilla private
	 * {@code PlayerModel.copyPropertiesToLayer}.
	 */
	private static void copyPart(ModelPart layer, ModelPart base) {
		layer.copyFrom(base);
	}

	private static AnimationState getAnimationState(long passengerId) {
		final AnimationState cached = animationStates.getAndMoveToFirst(passengerId);
		if (cached != null) {
			return cached;
		}
		final AnimationState state = new AnimationState(passengerId);
		animationStates.putAndMoveToFirst(passengerId, state);
		while (animationStates.size() > ANIMATION_CACHE_LIMIT) {
			animationStates.removeLast();
		}
		return state;
	}

	/**
	 * Clears the cached animation states (called on resource reload so that a skin pool
	 * change re-sees a clean slate; harmless for in-progress animations).
	 */
	public static void clear() {
		animationStates.clear();
	}

	private static PlayerModel getWideModel() {
		if (wideModel == null) {
			wideModel = new PlayerModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
		}
		return wideModel;
	}

	private static PlayerModel getSlimModel() {
		if (slimModel == null) {
			slimModel = new PlayerModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
		}
		return slimModel;
	}

	/**
	 * Per-passenger animation state. Updated exactly once per rendered frame (per passenger),
	 * on the render thread.
	 */
	private static final class AnimationState {

		final boolean looksAtPhone;

		float limbSwing;
		float limbSwingAmount;
		float headYaw;
		float headPitch;
		float phoneBlend;

		long lastUpdateTime;
		long headTargetChangeTime;
		float headYawTarget;
		float headPitchTarget;

		AnimationState(long passengerId) {
			looksAtPhone = hash(passengerId, 1) < PHONE_POSE_CHANCE;
			limbSwing = hash(passengerId, 2) * 10F;
		}

		void update(long passengerId, double walkingSpeed, boolean standing) {
			final long now = System.currentTimeMillis();
			final float deltaTime = lastUpdateTime == 0 ? 0 : Math.max(0, Math.min(0.25F, (now - lastUpdateTime) / 1000F));
			lastUpdateTime = now;

			// Walk cycle, in exact vanilla semantics (verified against 1.21.1 bytecode):
			// LivingEntity#updateWalkAnimation passes min(distancePerTick * 4, 1) as the
			// per-tick speed (distancePerTick = walkingSpeed / 20), WalkAnimationState
			// smooths it with a 0.4 lerp per tick, and position accumulates the smoothed
			// speed each tick. Integrated continuously here from the speed in blocks/s.
			final float targetAmount = standing ? 0 : Math.max(MIN_WALK_AMOUNT, Math.min(1, (float) walkingSpeed / 5F));
			limbSwingAmount += (targetAmount - limbSwingAmount) * Math.min(1, deltaTime * AMOUNT_SMOOTHING_PER_SECOND);
			if (targetAmount == 0 && limbSwingAmount < 0.01F) {
				// Vanilla snaps the speed to 0 below 0.01 so the legs fully settle.
				limbSwingAmount = 0;
			}
			// position += speed per tick (20 ticks/s).
			limbSwing += limbSwingAmount * 20 * deltaTime;

			// Phone pose: blend in while standing (if this passenger is a phone-watcher),
			// blend out as soon as they walk.
			final float targetPhone = standing && looksAtPhone ? 1 : 0;
			final float phoneDelta = targetPhone - phoneBlend;
			final float phoneStep = deltaTime * PHONE_BLEND_PER_SECOND;
			phoneBlend += Math.abs(phoneDelta) <= phoneStep ? phoneDelta : Math.signum(phoneDelta) * phoneStep;

			// Head: while walking, look ahead (with a slight per-passenger bias); while
			// standing, slowly look around at deterministic targets. Head angles are in
			// DEGREES; applyWalkPose multiplies by pi/180 like vanilla setupAnim (verified
			// against the 1.21.1 HumanoidModel bytecode).
			if (standing) {
				if (phoneBlend > 0.5F) {
					// Phone watchers keep a mostly fixed, slightly tilted head.
					headYawTarget = 6;
					headPitchTarget = 10;
				} else if (now > headTargetChangeTime) {
					final long interval = (long) (3000 + hash(passengerId, 3) * 5000);
					final long segment = now / interval;
					headTargetChangeTime = (segment + 1) * interval;
					headYawTarget = (hash(passengerId ^ segment, 4) - 0.5F) * 140;
					headPitchTarget = (hash(passengerId ^ segment, 5) - 0.5F) * 20;
				}
			} else {
				headYawTarget = (hash(passengerId, 6) - 0.5F) * 16;
				headPitchTarget = 0;
				headTargetChangeTime = 0;
			}

			final float smoothing = Math.min(1, deltaTime * 5F);
			headYaw += (headYawTarget - headYaw) * smoothing;
			headPitch += (headPitchTarget - headPitch) * smoothing;
		}

		private static float hash(long seed, int salt) {
			return Math.floorMod((seed + salt * 0x9E3779B9L) * GOLDEN_RATIO_FRACTION, RESOLUTION) / (float) RESOLUTION;
		}
	}
}
