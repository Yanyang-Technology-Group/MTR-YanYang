package org.mtr.mod.mixin;

import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = OcclusionCullingInstance.class, remap = false)
public abstract class OcclusionSafepointMixin {

	@Unique private int mtr$occlusionWork;

	@ModifyArg(method = {"isAABBVisible", "stepRay"}, at = @At(value = "INVOKE", target = "Lcom/logisticscraft/occlusionculling/OcclusionCullingInstance;getCacheValue(III)I"), index = 0, require = 1)
	private int mtr$pollCacheScan(int x) {
		mtr$pollSafepoint();
		return x;
	}

	@ModifyArg(method = "isAABBVisible", at = @At(value = "INVOKE", target = "Ljava/util/BitSet;get(I)Z"), index = 0, require = 1)
	private int mtr$pollFaceScan(int index) {
		mtr$pollSafepoint();
		return index;
	}

	@Unique
	private void mtr$pollSafepoint() {
		// C2 can leave large nested voxel loops without a timely safepoint poll.
		// A native transition every 65K visits bounds that delay without changing culling.
		if ((++mtr$occlusionWork & 0xFFFF) == 0) {
			Thread.yield();
		}
	}
}
