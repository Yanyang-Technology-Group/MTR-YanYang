package org.mtr.mod.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.mtr.mod.render.IndexedFeedbackGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Pseudo
@Mixin(targets = "de.odysseus.ithaka.digraph.MapDigraph", remap = false)
public interface IrisGraphAccessor extends IndexedFeedbackGraph.MutableGraph {
	@Override
	@Accessor("vertexMap")
	Map<Object, Object2IntMap<Object>> mtr$getVertices();

	@Override
	@Accessor("edgeCount")
	void mtr$setEdgeCount(int edgeCount);
}
