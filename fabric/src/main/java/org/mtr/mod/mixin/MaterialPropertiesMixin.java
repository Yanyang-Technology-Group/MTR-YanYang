package org.mtr.mod.mixin;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.vertex.VertexAttributeState;
import org.spongepowered.asm.mixin.*;

import java.util.Objects;

@Pseudo
@Mixin(targets = "org.mtr.mapping.render.batch.MaterialProperties", remap = false)
public abstract class MaterialPropertiesMixin {
	@Shadow private Identifier texture;
	@Shadow @Final public OptimizedModel.ShaderType shaderType;
	@Shadow @Final public VertexAttributeState vertexAttributeState;
	@Shadow @Final public boolean translucent;
	@Shadow @Final public boolean writeDepthBuf;
	@Shadow @Final public boolean cutoutHack;

	/**
	 * @author MTR-Yanyang
	 * @reason Avoid temporary hash arrays while still observing mutable textures and attributes.
	 */
	@Overwrite
	public int hashCode() {
		int hash = 31 + Objects.hashCode(shaderType);
		hash = 31 * hash + Objects.hashCode(texture);
		hash = 31 * hash + Objects.hashCode(vertexAttributeState);
		hash = 31 * hash + Boolean.hashCode(translucent);
		hash = 31 * hash + Boolean.hashCode(writeDepthBuf);
		return 31 * hash + Boolean.hashCode(cutoutHack);
	}
}
