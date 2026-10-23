package org.mtr.mod.mixin;

import org.mtr.mod.render.RenderBatchListPool;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Pseudo
@Mixin(targets = "org.mtr.mapping.render.batch.BatchManager", remap = false)
public abstract class OptimizedBatchManagerMixin {

	@Shadow @Final private Map<?, ? extends List<?>> translucentBatches;

	@ModifyArg(method = "queue(Lorg/mtr/mapping/render/object/VertexArray;Lorg/mtr/mapping/render/vertex/VertexAttributeState;)V", at = @At(value = "INVOKE", target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"), index = 1)
	private Function<Object, List<Object>> mtr$reuseBatchLists(Function<Object, List<Object>> factory) {
		return RenderBatchListPool.factory();
	}

	@Redirect(method = "drawBatch", at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V"))
	private static void mtr$recycleBatches(Map<?, ? extends List<?>> batches) {
		RenderBatchListPool.clear(batches);
	}

	// drawAll(false) skips both drawing and clearing the translucent queue.
	@Inject(method = "drawAll", at = @At("RETURN"))
	private void mtr$discardUndrawnTranslucent(CallbackInfo ci) {
		RenderBatchListPool.clear(translucentBatches);
	}
}
