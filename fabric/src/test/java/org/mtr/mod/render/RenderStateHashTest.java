package org.mtr.mod.render;

import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.Matrix4f;
import org.mtr.mapping.holder.Vector3f;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.vertex.VertexAttributeState;
import org.mtr.mod.mixin.MaterialPropertiesMixin;
import org.mtr.mod.mixin.VertexAttributeStateMixin;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class RenderStateHashTest {
	@Test
	void vertexHashMatchesObjectsHashWithNullsAndMutableMatrices() {
		final VertexAttributeStateMixin state = new VertexAttributeStateMixin() {};
		check(state);
		state.color = 0xAB123456;
		state.lightmapUV = 0x00F000A0;
		state.textureU = 0.75F;
		state.textureV = -0.0F;
		state.position = new Vector3f(1, 2, 3);
		state.normal = new Vector3f(0, 1, 0);
		state.overlayUV = 12345;
		final org.joml.Matrix4f matrix = new org.joml.Matrix4f();
		state.matrix4f = new Matrix4f(matrix);
		check(state);
		matrix.translate(8, -3, 100);
		check(state);
		state.color = null;
		state.textureU = Float.NaN;
		check(state);
	}

	@Test
	void materialHashTracksTextureChangesAndAllRenderFlags() throws Exception {
		final MaterialPropertiesMixin state = new MaterialPropertiesMixin() {};
		final var textureField = MaterialPropertiesMixin.class.getDeclaredField("texture");
		textureField.setAccessible(true);
		for (OptimizedModel.ShaderType shaderType : OptimizedModel.ShaderType.values()) {
			state.shaderType = shaderType;
			state.vertexAttributeState = new VertexAttributeState(0xFF010203, null);
			for (int flags = 0; flags < 8; flags++) {
				state.translucent = (flags & 1) != 0;
				state.writeDepthBuf = (flags & 2) != 0;
				state.cutoutHack = (flags & 4) != 0;
				for (Identifier texture : new Identifier[]{null, new Identifier("mtr", "a"), new Identifier("mtr", "b")}) {
					textureField.set(state, texture);
					assertEquals(Objects.hash(shaderType, texture, state.vertexAttributeState, state.translucent, state.writeDepthBuf, state.cutoutHack), state.hashCode());
				}
			}
		}
	}

	private static void check(VertexAttributeStateMixin state) {
		assertEquals(Objects.hash(state.position, state.color, state.textureU, state.textureV, state.lightmapUV, state.normal, state.overlayUV, state.matrix4f), state.hashCode());
	}
}
