package org.mtr.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.mod.render.FeedbackGraphTraversal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import java.util.Collection;
import java.util.Set;

@Pseudo
@Mixin(targets = "de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider", remap = false)
public abstract class IrisFeedbackTraversalMixin {
	@WrapOperation(method = "lfas", at = @At(value = "INVOKE", target = "Lde/odysseus/ithaka/digraph/Digraphs;dfs(Lde/odysseus/ithaka/digraph/Digraph;Ljava/lang/Object;Ljava/util/Set;Ljava/util/Collection;)V"), require = 0)
	private void mtr$traverseSnapshot(@Coerce Object graph, Object root, Set<Object> visited, Collection<Object> result, Operation<Void> original) {
		if (!FeedbackGraphTraversal.tryTraverse(graph, root, visited, result)) original.call(graph, root, visited, result);
	}
}
