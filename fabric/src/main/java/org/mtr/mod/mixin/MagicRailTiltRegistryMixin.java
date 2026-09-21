package org.mtr.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.mtr.mod.render.RailIdNormalizationCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "org.justnoone.jme.rail.MagicRailTiltRegistry", remap = false)
public abstract class MagicRailTiltRegistryMixin {
	@Unique
	private static final ThreadLocal<RailIdNormalizationCache> mtr$normalizedRailIds = ThreadLocal.withInitial(RailIdNormalizationCache::new);

	@WrapMethod(method = "normalizeRailId(Ljava/lang/String;)Ljava/lang/String;", require = 0)
	private static String mtr$reuseNormalizedRailId(String railId, Operation<String> original) {
		return mtr$normalizedRailIds.get().normalize(railId, original::call);
	}
}
