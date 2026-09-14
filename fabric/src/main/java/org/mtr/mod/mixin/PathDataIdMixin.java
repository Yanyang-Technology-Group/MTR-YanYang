package org.mtr.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = PathData.class, remap = false)
public class PathDataIdMixin {

	@Unique private volatile String mtr$forwardHexId;
	@Unique private volatile String mtr$reverseHexId;

	// PathData endpoints and Position coordinates are final, including after updateData.
	@WrapOperation(method = "getHexId", at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/TwoPositionsBase;getHexIdRaw(Lorg/mtr/core/data/Position;Lorg/mtr/core/data/Position;)Ljava/lang/String;"), require = 2)
	private String mtr$cachedHexId(Position from, Position to, Operation<String> original, boolean reverse) {
		final String cached = reverse ? mtr$reverseHexId : mtr$forwardHexId;
		if (cached != null) {
			return cached;
		}
		final String result = original.call(from, to);
		if (reverse) {
			mtr$reverseHexId = result;
		} else {
			mtr$forwardHexId = result;
		}
		return result;
	}
}
