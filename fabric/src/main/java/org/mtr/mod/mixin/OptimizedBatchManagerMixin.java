package org.mtr.mod.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Pseudo
@Mixin(targets = "org.mtr.mapping.render.batch.BatchManager", remap = false)
public abstract class OptimizedBatchManagerMixin {

	@Shadow @Final private Map<?, ?> translucentBatches;

	// drawAll(false) skips both drawing and clearing the translucent queue.
	@Inject(method = "drawAll", at = @At("RETURN"))
	private void mtr$discardUndrawnTranslucent(CallbackInfo ci) {
		translucentBatches.clear();
	}
}
