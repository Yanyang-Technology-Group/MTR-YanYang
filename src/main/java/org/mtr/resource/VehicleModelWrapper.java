package org.mtr.resource;

import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.client.CustomResourceLoader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Utilities;
import org.mtr.generated.resource.VehicleModelWrapperSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;

import java.util.Locale;

public final class VehicleModelWrapper extends VehicleModelWrapperSchema {

	VehicleModelWrapper(
		String modelResource,
		String textureResource,
		String minecraftModelPropertiesResource,
		String minecraftPositionDefinitionsResource,
		boolean flipTextureV,
		ObjectArrayList<ModelPropertiesPartWrapper> parts,
		double modelYOffset,
		String gangwayInnerSideResource,
		String gangwayInnerTopResource,
		String gangwayInnerBottomResource,
		String gangwayOuterSideResource,
		String gangwayOuterTopResource,
		String gangwayOuterBottomResource,
		double gangwayWidth,
		double gangwayHeight,
		double gangwayYOffset,
		double gangwayZOffset,
		String barrierInnerSideResource,
		String barrierInnerTopResource,
		String barrierInnerBottomResource,
		String barrierOuterSideResource,
		String barrierOuterTopResource,
		String barrierOuterBottomResource,
		double barrierWidth,
		double barrierHeight,
		double barrierYOffset,
		double barrierZOffset
	) {
		super(
			modelResource,
			textureResource,
			minecraftModelPropertiesResource,
			minecraftPositionDefinitionsResource,
			flipTextureV,
			modelYOffset,
			gangwayInnerSideResource,
			gangwayInnerTopResource,
			gangwayInnerBottomResource,
			gangwayOuterSideResource,
			gangwayOuterTopResource,
			gangwayOuterBottomResource,
			gangwayWidth,
			gangwayHeight,
			gangwayYOffset,
			gangwayZOffset,
			barrierInnerSideResource,
			barrierInnerTopResource,
			barrierInnerBottomResource,
			barrierOuterSideResource,
			barrierOuterTopResource,
			barrierOuterBottomResource,
			barrierWidth,
			barrierHeight,
			barrierYOffset,
			barrierZOffset
		);
		this.parts.addAll(parts);
	}

	public VehicleModelWrapper(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
	}

	VehicleModel toVehicleModel(
		ResourceProvider resourceProvider,
		@Nullable Object2ObjectArrayMap<String, ModelProperties> modelPropertiesMap,
		@Nullable Object2ObjectArrayMap<String, PositionDefinitions> positionDefinitionsMap,
		String vehicleId,
		String modelType,
		int modelIndex
	) {
		final ObjectArrayList<ModelPropertiesPart> modelPropertiesPartList = new ObjectArrayList<>();
		final ObjectArrayList<PositionDefinition> positionDefinitionList = new ObjectArrayList<>();
		parts.forEach(part -> {
			final ObjectObjectImmutablePair<ModelPropertiesPart, PositionDefinition> modelPropertiesPartAndPositionDefinition = part.toModelPropertiesPartAndPositionDefinition();
			modelPropertiesPartList.add(modelPropertiesPartAndPositionDefinition.left());
			positionDefinitionList.add(modelPropertiesPartAndPositionDefinition.right());
		});

		final boolean isMinecraftResource = CustomResourceLoader.getMinecraftModelResources().stream().anyMatch(minecraftModelResource -> minecraftModelResource.matchesModelResource(modelResource));
		final String modelPropertiesResource = isMinecraftResource ? minecraftModelPropertiesResource : formatCustomResourceIdentifier("properties", vehicleId, modelType, modelIndex);
		final ModelProperties modelProperties = new ModelProperties(
			modelPropertiesPartList,
			modelYOffset,
			gangwayInnerSideResource,
			gangwayInnerTopResource,
			gangwayInnerBottomResource,
			gangwayOuterSideResource,
			gangwayOuterTopResource,
			gangwayOuterBottomResource,
			gangwayWidth,
			gangwayHeight,
			gangwayYOffset,
			gangwayZOffset,
			barrierInnerSideResource,
			barrierInnerTopResource,
			barrierInnerBottomResource,
			barrierOuterSideResource,
			barrierOuterTopResource,
			barrierOuterBottomResource,
			barrierWidth,
			barrierHeight,
			barrierYOffset,
			barrierZOffset
		);
		final String positionDefinitionsResource = isMinecraftResource ? minecraftPositionDefinitionsResource : formatCustomResourceIdentifier("definition", vehicleId, modelType, modelIndex);
		final PositionDefinitions positionDefinitions = new PositionDefinitions(positionDefinitionList);

		if (!isMinecraftResource && modelPropertiesMap != null && positionDefinitionsMap != null) {
			modelPropertiesMap.put(modelPropertiesResource, modelProperties);
			positionDefinitionsMap.put(positionDefinitionsResource, positionDefinitions);
		}

		return new VehicleModel(
			modelResource,
			textureResource,
			modelPropertiesResource,
			positionDefinitionsResource,
			flipTextureV,
			identifier -> {
				final String identifierString = identifier.toString();
				if (!isMinecraftResource) {
					if (identifierString.equals(modelPropertiesResource)) {
						return Utilities.getJsonObjectFromData(modelProperties).toString();
					} else if (identifierString.equals(positionDefinitionsResource)) {
						return Utilities.getJsonObjectFromData(positionDefinitions).toString();
					} else {
						return resourceProvider.get(identifier);
					}
				} else {
					return resourceProvider.get(identifier);
				}
			}
		);
	}

	/**
	 * Builds a readable resource identifier such as {@code properties_<vehicleId>_models.json} (or {@code _bogie1_models.json} /
	 * {@code _bogie2_models.json} for bogie models), placed in the same directory as the model resource. If a vehicle has
	 * multiple models of the same type, a numeric suffix is appended to keep the file names unique.
	 */
	private String formatCustomResourceIdentifier(String prefix, String vehicleId, String modelType, int modelIndex) {
		final String sanitizedModelResource = CustomResourceTools.formatIdentifierString(modelResource);
		final int separatorIndex = sanitizedModelResource.indexOf(':');
		final String rawNamespace = separatorIndex > 0 ? sanitizedModelResource.substring(0, separatorIndex) : "";
		final String namespace = rawNamespace.matches("[a-z0-9_.-]+") ? rawNamespace : MTR.MOD_ID;
		final String sanitizedPath = separatorIndex >= 0 ? sanitizedModelResource.substring(separatorIndex + 1) : sanitizedModelResource;
		final int lastSlashIndex = sanitizedPath.lastIndexOf('/');
		final String directory = lastSlashIndex >= 0 ? sanitizedPath.substring(0, lastSlashIndex + 1) : "";
		final String sanitizedVehicleId = vehicleId.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9_-]", "_").replaceAll("^_+|_+$", "");
		final String safeVehicleId = sanitizedVehicleId.isEmpty() ? "vehicle" : sanitizedVehicleId;
		final String uniqueSuffix = modelIndex > 0 ? String.format("_%d", modelIndex) : "";
		return ResourceLocation.fromNamespaceAndPath(namespace, String.format("%s%s_%s_%s%s.json", directory, prefix, safeVehicleId, modelType, uniqueSuffix)).toString();
	}

	void clean() {
		parts.removeIf(modelPropertiesPartWrapper -> modelPropertiesPartWrapper.getName().isEmpty());
	}
}
