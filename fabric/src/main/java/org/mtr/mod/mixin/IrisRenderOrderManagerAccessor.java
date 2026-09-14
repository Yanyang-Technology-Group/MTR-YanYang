package org.mtr.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.EnumMap;
import java.util.List;

@Pseudo
@Mixin(targets = "net.irisshaders.batchedentityrendering.impl.ordering.GraphTranslucencyRenderOrderManager", remap = false)
public interface IrisRenderOrderManagerAccessor {
	@Accessor("types")
	EnumMap<?, ?> mtr$getTypes();

	@Invoker("getRenderOrder")
	List<?> mtr$getRenderOrder();
}
