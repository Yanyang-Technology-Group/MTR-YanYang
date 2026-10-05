package org.mtr.mixin;

import org.mtr.core.data.PathData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches the hex id of a path section.
 *
 * <p>{@code PathData#getHexId} formats both endpoint positions on every call; vehicles
 * query it repeatedly (per occupied section, per tick) while simulating, so the same
 * string was being formatted thousands of times per second. Path endpoints are final,
 * including after {@code updateData}, so the forward and reverse hex ids are computed at
 * most once per instance.</p>
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series.</p>
 */
@Mixin(value = PathData.class, remap = false)
public abstract class PathDataIdMixin {

	@Unique
	private volatile String mtr$forwardHexId;
	@Unique
	private volatile String mtr$reverseHexId;

	@Inject(method = "getHexId", at = @At("HEAD"), cancellable = true)
	private void mtr$cachedHexId(boolean reverse, CallbackInfoReturnable<String> cir) {
		final String cached = reverse ? mtr$reverseHexId : mtr$forwardHexId;
		if (cached != null) {
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "getHexId", at = @At("RETURN"))
	private void mtr$storeHexId(boolean reverse, CallbackInfoReturnable<String> cir) {
		if (reverse) {
			mtr$reverseHexId = cir.getReturnValue();
		} else {
			mtr$forwardHexId = cir.getReturnValue();
		}
	}
}
