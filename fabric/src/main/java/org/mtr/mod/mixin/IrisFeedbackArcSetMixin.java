package org.mtr.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.mod.render.RenderOrderCache;
import org.mtr.mod.render.IndexedFeedbackGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "de.odysseus.ithaka.digraph.util.fas.AbstractFeedbackArcSetProvider", remap = false)
public abstract class IrisFeedbackArcSetMixin {
	@Coerce
	@WrapOperation(method = "fas", at = @At(value = "INVOKE", target = "Lde/odysseus/ithaka/digraph/util/fas/AbstractFeedbackArcSetProvider;lfas(Lde/odysseus/ithaka/digraph/Digraph;Lde/odysseus/ithaka/digraph/EdgeWeights;)Lde/odysseus/ithaka/digraph/Digraph;"), require = 0)
	private Object mtr$reuseFeedbackEdges(@Coerce Object provider, @Coerce Object graph, @Coerce Object weights, Operation<Object> original) {
		return RenderOrderCache.getFeedbackEdges(provider, graph, weights, () -> IndexedFeedbackGraph.solve(provider, graph, weights, (numberedGraph, numberedWeights) -> original.call(provider, numberedGraph, numberedWeights)));
	}
}
