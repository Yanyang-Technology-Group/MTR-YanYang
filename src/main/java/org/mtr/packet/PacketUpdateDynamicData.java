package org.mtr.packet;

import org.jspecify.annotations.Nullable;
import org.mtr.MTRClient;
import org.mtr.client.MinecraftClientData;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.PathData;
import org.mtr.core.operation.DynamicDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.tool.Utilities;
import org.mtr.data.VehicleExtension;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.render.RenderVehicles;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

public final class PacketUpdateDynamicData extends PacketRequestResponseBase {

        public PacketUpdateDynamicData(PacketBufferReceiver packetBufferReceiver) {
                super(packetBufferReceiver);
        }

        public PacketUpdateDynamicData(DynamicDataResponse dynamicDataResponse) {
                super(Utilities.getJsonObjectFromData(dynamicDataResponse).toString());
        }

        private PacketUpdateDynamicData(String content) {
                super(content);
        }

        @Override
        protected void runClientInbound(JsonReader jsonReader) {
                final MinecraftClientData minecraftClientData = MinecraftClientData.getInstance();
                final DynamicDataResponse dynamicDataResponse = new DynamicDataResponse(jsonReader, minecraftClientData);
                final boolean hasUpdate1 = updateVehiclesLiftsOrPassengers(minecraftClientData.vehicles, dynamicDataResponse::iterateVehiclesToKeep, dynamicDataResponse::iterateVehiclesToUpdate, VehicleExtension::dispose, vehicleUpdate -> vehicleUpdate.getVehicle().getId(), vehicleUpdate -> {
                        final VehicleExtension vehicleExtension = new VehicleExtension(vehicleUpdate, minecraftClientData);
                        // Resolve the path rail cache and speed limits eagerly for the (re)created vehicle
                        // only, against the real client data whose rail graph is already populated — this
                        // used to run for every vehicle against a fresh empty data instance, which always
                        // missed the rail lookup and allocated a fallback rail per path segment.
                        PathData.writePathCache(vehicleExtension.vehicleExtraData.immutablePath, minecraftClientData, vehicleExtension.getTransportMode());
                        return vehicleExtension;
                });
                final boolean hasUpdate2 = updateVehiclesLiftsOrPassengers(minecraftClientData.lifts, dynamicDataResponse::iterateLiftsToKeep, dynamicDataResponse::iterateLiftsToUpdate, null, NameColorDataBase::getId, lift -> lift);
                final boolean hasUpdate3 = updateVehiclesLiftsOrPassengers(minecraftClientData.passengers, dynamicDataResponse::iteratePassengersToKeep, dynamicDataResponse::iteratePassengersToUpdate, null, NameColorDataBase::getId, passenger -> passenger);

                dynamicDataResponse.iterateSignalBlockUpdates(signalBlockUpdate -> {
                        minecraftClientData.railIdToPreBlockedSignalColors.put(signalBlockUpdate.getRailId(), signalBlockUpdate.getPreBlockedSignalColors());
                        minecraftClientData.railIdToCurrentlyBlockedSignalColors.put(signalBlockUpdate.getRailId(), signalBlockUpdate.getCurrentlyBlockedSignalColors());
                });

                if (hasUpdate1 || hasUpdate2 || hasUpdate3) {
                        if (hasUpdate1) {
                                MTRClient.HIDDEN_PLAYERS.clear();
                                minecraftClientData.vehicles.forEach(vehicle -> vehicle.vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> MTRClient.HIDDEN_PLAYERS.add(vehicleRidingEntity.uuid)));
                                RenderVehicles.RIDING_PLAYER_INTERPOLATIONS.removeIf(ridingPlayerInterpolation -> !MTRClient.HIDDEN_PLAYERS.contains(ridingPlayerInterpolation.uuid));
                        }
                        // Vehicle, lift and passenger snapshots never change the rail graph or stations;
                        // only refresh the dynamic wrappers instead of the full sync.
                        minecraftClientData.syncDynamic();
                }
        }

        @Override
        protected PacketRequestResponseBase getInstance(String content) {
                return new PacketUpdateDynamicData(content);
        }

        @Override
        protected SerializedDataBase getDataInstance(JsonReader jsonReader) {
                return new SerializedDataBase() {
                        @Override
                        public void updateData(ReaderBase readerBase) {
                        }

                        @Override
                        public void serializeData(WriterBase writerBase) {
                        }
                };
        }

        @Override
        protected String getKey() {
                return OperationProcessor.UPDATE_DATA;
        }

        @Override
        protected ResponseType responseType() {
                return ResponseType.NONE;
        }

        private static <T extends NameColorDataBase, U> boolean updateVehiclesLiftsOrPassengers(ObjectArraySet<T> dataSet, Consumer<LongConsumer> iterateKeep, Consumer<Consumer<U>> iterateUpdate, @Nullable Consumer<T> onRemove, ToLongFunction<U> getId, Function<U, T> createInstance) {
                final LongOpenHashSet keepIds = new LongOpenHashSet();
                iterateKeep.accept(keepIds::add);

                final LongOpenHashSet updateIds = new LongOpenHashSet();
                final ObjectArrayList<U> dataSetToUpdate = new ObjectArrayList<>();
                iterateUpdate.accept(dataToUpdate -> {
                        dataSetToUpdate.add(dataToUpdate);
                        updateIds.add(getId.applyAsLong(dataToUpdate));
                });

                final Long2ObjectOpenHashMap<T> removedItems = new Long2ObjectOpenHashMap<>();
                final boolean itemRemoved = dataSet.removeIf(data -> {
                        boolean shouldBeRemoved = !keepIds.contains(data.getId());
                        if (shouldBeRemoved) {
                                removedItems.put(data.getId(), data);
                        }
                        return shouldBeRemoved || updateIds.contains(data.getId());
                });
                dataSetToUpdate.forEach(dataToUpdate -> dataSet.add(createInstance.apply(dataToUpdate)));
                dataSet.forEach(e -> removedItems.remove(e.getId()));

                if (onRemove != null) {
                        removedItems.values().forEach(onRemove);
                }

                return !dataSetToUpdate.isEmpty() || itemRemoved;
        }
}
