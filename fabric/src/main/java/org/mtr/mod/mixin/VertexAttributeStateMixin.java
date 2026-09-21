package org.mtr.mod.mixin;

import org.mtr.mapping.holder.Matrix4f;
import org.mtr.mapping.holder.Vector3f;
import org.mtr.mapping.render.vertex.VertexAttributeType;
import org.mtr.mod.render.MatrixUploadBuffer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Objects;

@Pseudo
@Mixin(targets = "org.mtr.mapping.render.vertex.VertexAttributeState", remap = false)
public abstract class VertexAttributeStateMixin {
	@Shadow @Final public Integer color;
	@Shadow @Final public Integer lightmapUV;
	@Shadow @Final public Vector3f position;
	@Shadow @Final public Float textureU;
	@Shadow @Final public Float textureV;
	@Shadow @Final public Integer overlayUV;
	@Shadow @Final public Vector3f normal;
	@Shadow @Final public Matrix4f matrix4f;
	@Unique private static final VertexAttributeType[] mtr$attributeTypes = VertexAttributeType.values();

	@Redirect(method = "apply", at = @At(value = "INVOKE", target = "Lorg/mtr/mapping/render/vertex/VertexAttributeType;values()[Lorg/mtr/mapping/render/vertex/VertexAttributeType;"))
	private VertexAttributeType[] mtr$reuseAttributeTypes() {
		return mtr$attributeTypes;
	}

	@Redirect(method = "apply", at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;allocate(I)Ljava/nio/ByteBuffer;"))
	private ByteBuffer mtr$reuseMatrixBytes(int capacity) {
		return MatrixUploadBuffer.bytes(capacity);
	}

	@Redirect(method = "apply", at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;asFloatBuffer()Ljava/nio/FloatBuffer;"))
	private FloatBuffer mtr$reuseMatrixFloats(ByteBuffer bytes) {
		return MatrixUploadBuffer.floats(bytes);
	}

	/**
	 * @author MTR-Yanyang
	 * @reason Preserve Objects.hash semantics without allocating its varargs array per draw.
	 */
	@Overwrite
	public int hashCode() {
		int hash = 31 + Objects.hashCode(position);
		hash = 31 * hash + Objects.hashCode(color);
		hash = 31 * hash + Objects.hashCode(textureU);
		hash = 31 * hash + Objects.hashCode(textureV);
		hash = 31 * hash + Objects.hashCode(lightmapUV);
		hash = 31 * hash + Objects.hashCode(normal);
		hash = 31 * hash + Objects.hashCode(overlayUV);
		return 31 * hash + Objects.hashCode(matrix4f);
	}
}
