package org.mtr.mod.resource;

import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mod.resource.nativeobj.NativeObjModel;

/**
 * The geometry of one OBJ or MQO model, whichever backend built it.
 *
 * <p>MTR's model pipeline only ever asks a model five things: give me a copy, tell me your
 * bounding box, and translate, rotate, scale or mirror yourself. This interface is exactly
 * those five things, which is what lets the native backend be a drop-in for
 * {@code OptimizedModel.ObjModel} without either side knowing about the other.</p>
 *
 * <p>The per-part copy matters beyond convenience. {@code ObjModel.addTransformation}
 * appends placed copies into the instance's own accumulated model, so sharing one instance
 * across parts accumulates every part's placements and renders every variant at once - the
 * cross-condition rendering bug this branch already fixed once in
 * {@code ModelPropertiesPart}. Both implementations must therefore return a genuinely
 * independent copy.</p>
 *
 * @see JavaObjModel
 * @see NativeObjModel
 */
public interface OptimizedObjModel {

	/**
	 * An independent instance over the same source meshes, so placements added to one never
	 * appear in the other.
	 */
	OptimizedObjModel copy();

	/** Appends a placed copy of every source mesh. */
	void addTransformation(OptimizedModel.ShaderType shaderType, double x, double y, double z, boolean flipped);

	float getMinX();

	float getMinY();

	float getMinZ();

	float getMaxX();

	float getMaxY();

	float getMaxZ();

	/** {@code ObjectResource}-level translation, applied to the shared source meshes. */
	void applyTranslation(double x, double y, double z);

	/** {@code ObjectResource}-level rotation in degrees per axis. */
	void applyRotation(double x, double y, double z);

	/** {@code ObjectResource}-level scale. */
	void applyScale(double x, double y, double z);

	/** {@code ObjectResource}-level mirror. */
	void applyMirror(boolean x, boolean y, boolean z);
}
