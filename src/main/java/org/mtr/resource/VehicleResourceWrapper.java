package org.mtr.resource;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.generated.resource.VehicleResourceWrapperSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

public final class VehicleResourceWrapper extends VehicleResourceWrapperSchema {

	VehicleResourceWrapper(
		String id,
		String name,
		String color,
		TransportMode transportMode,
		double length,
		double width,
		double bogie1Position,
		double bogie2Position,
		double couplingPadding1,
		double couplingPadding2,
		String description,
		String wikipediaArticle,
		ObjectArrayList<String> tags,
		ObjectArrayList<VehicleModelWrapper> models,
		ObjectArrayList<VehicleModelWrapper> bogie1Models,
		ObjectArrayList<VehicleModelWrapper> bogie2Models,
		boolean hasGangway1,
		boolean hasGangway2,
		boolean hasBarrier1,
		boolean hasBarrier2,
		double legacyRiderOffset,
		String bveSoundBaseResource,
		String legacySpeedSoundBaseResource,
		long legacySpeedSoundCount,
		boolean legacyUseAccelerationSoundsWhenCoasting,
		boolean legacyConstantPlaybackSpeed,
		String legacyDoorSoundBaseResource,
		double legacyDoorCloseSoundTime
	) {
		super(
			id,
			name,
			color,
			transportMode,
			length,
			width,
			bogie1Position,
			bogie2Position,
			couplingPadding1,
			couplingPadding2,
			description,
			wikipediaArticle,
			hasGangway1,
			hasGangway2,
			hasBarrier1,
			hasBarrier2,
			legacyRiderOffset,
			bveSoundBaseResource,
			legacySpeedSoundBaseResource,
			legacySpeedSoundCount,
			legacyUseAccelerationSoundsWhenCoasting,
			legacyConstantPlaybackSpeed,
			legacyDoorSoundBaseResource,
			legacyDoorCloseSoundTime
		);
		this.tags.addAll(tags);
		this.models.addAll(models);
		this.bogie1Models.addAll(bogie1Models);
		this.bogie2Models.addAll(bogie2Models);
	}

	public VehicleResourceWrapper(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
	}

	public VehicleResource toVehicleResource(
		ResourceProvider resourceProvider,
		@Nullable Object2ObjectArrayMap<String, ModelProperties> modelPropertiesMap,
		@Nullable Object2ObjectArrayMap<String, PositionDefinitions> positionDefinitionsMap
	) {
		return new VehicleResource(
			id,
			name,
			color,
			transportMode,
			length,
			width,
			capacity,
			bogie1Position,
			bogie2Position,
			couplingPadding1,
			couplingPadding2,
			description,
			wikipediaArticle,
			tags,
			toVehicleModels(models, "models", resourceProvider, modelPropertiesMap, positionDefinitionsMap),
			toVehicleModels(bogie1Models, "bogie1_models", resourceProvider, modelPropertiesMap, positionDefinitionsMap),
			toVehicleModels(bogie2Models, "bogie2_models", resourceProvider, modelPropertiesMap, positionDefinitionsMap),
			hasGangway1,
			hasGangway2,
			hasBarrier1,
			hasBarrier2,
			legacyRiderOffset,
			bveSoundBaseResource,
			legacySpeedSoundBaseResource,
			legacySpeedSoundCount,
			legacyUseAccelerationSoundsWhenCoasting,
			legacyConstantPlaybackSpeed,
			legacyDoorSoundBaseResource,
			legacyDoorCloseSoundTime,
			resourceProvider
		);
	}

	public String getId() {
		return id;
	}

	private ObjectArrayList<VehicleModel> toVehicleModels(
			ObjectArrayList<VehicleModelWrapper> modelWrappers,
			String modelType,
			ResourceProvider resourceProvider,
			@Nullable Object2ObjectArrayMap<String, ModelProperties> modelPropertiesMap,
			@Nullable Object2ObjectArrayMap<String, PositionDefinitions> positionDefinitionsMap
	) {
		final ObjectArrayList<VehicleModel> vehicleModels = new ObjectArrayList<>();
		for (int i = 0; i < modelWrappers.size(); i++) {
			vehicleModels.add(modelWrappers.get(i).toVehicleModel(resourceProvider, modelPropertiesMap, positionDefinitionsMap, id, modelType, modelWrappers.size() > 1 ? i + 1 : 0));
		}
		return vehicleModels;
	}

	void clean() {
		models.forEach(VehicleModelWrapper::clean);
	}
}
