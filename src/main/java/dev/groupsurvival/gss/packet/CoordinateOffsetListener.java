package dev.groupsurvival.gss.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.LodestoneTracker;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleTrailData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleVibrationData;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.vector.positionpath.LinearPositionPath;
import com.github.retrooper.packetevents.protocol.vector.positionpath.PositionPath;
import com.github.retrooper.packetevents.protocol.vector.positionpath.SteppedPositionPath;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.protocol.world.WorldBlockPosition;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector2i;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientGenerateStructure;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPickItemFromBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientQueryBlockNBT;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSetStructureBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSetTestBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTestInstanceBlockAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateCommandBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateJigsawBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAddTransientBlock;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkBiomes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerFacePlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerInitializeWorldBorder;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMoveMinecart;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPosition;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewPosition;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWorldBorderCenter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 좌표 숨김 월드에서 클라이언트가 보는 모든 좌표를 세션별 오프셋만큼 이동.
 * 서버 -> 클라이언트: +offset, 클라이언트 -> 서버: -offset.
 * 또한 숨김 월드에서는 reducedDebugInfo(F3 좌표 숨김)를 켜고, 다른 월드에서는 끔.
 */
public final class CoordinateOffsetListener {

    private static final int STATUS_REDUCED_DEBUG_ON = 22;
    private static final int STATUS_REDUCED_DEBUG_OFF = 23;

    private final OffsetManager offsets;
    private final HiddenWorlds hidden;
    private final boolean reducedDebug;
    private final Logger logger;
    private volatile boolean warned;

    public CoordinateOffsetListener(OffsetManager offsets, HiddenWorlds hidden, boolean reducedDebug, Logger logger) {
        this.offsets = offsets;
        this.hidden = hidden;
        this.reducedDebug = reducedDebug;
        this.logger = logger;
    }

    public PacketListener outbound() {
        return new Outbound();
    }

    public PacketListener inbound() {
        return new Inbound();
    }

    private void warnOnce(String where, Throwable t) {
        if (warned) return;
        warned = true;
        logger.warning("좌표 변환 중 오류 (" + where + "): " + t + " - 이후 같은 경고는 생략됩니다.");
    }

    // ───────────────────────── 변환 헬퍼 ─────────────────────────

    private static Vector3i shift(Vector3i v, OffsetManager.Session s, int sign) {
        return new Vector3i(v.getX() + sign * s.blockX, v.getY(), v.getZ() + sign * s.blockZ);
    }

    private static Vector3d shift(Vector3d v, OffsetManager.Session s, int sign) {
        return new Vector3d(v.getX() + sign * s.blockX, v.getY(), v.getZ() + sign * s.blockZ);
    }

    private WorldBlockPosition shiftGlobal(WorldBlockPosition pos, OffsetManager.Session s, int sign) {
        if (pos == null || !hidden.isHidden(pos.getWorld())) return pos;
        return new WorldBlockPosition(pos.getWorld(), shift(pos.getBlockPosition(), s, sign));
    }

    /** 자석석 나침반 목표 좌표 변환. 변경되면 true. */
    private boolean shiftItem(ItemStack item, OffsetManager.Session s, int sign) {
        if (item == null || item.isEmpty()) return false;
        Optional<LodestoneTracker> component = item.getComponent(ComponentTypes.LODESTONE_TRACKER);
        if (component == null || component.isEmpty()) return false;
        LodestoneTracker tracker = component.get();
        WorldBlockPosition target = tracker.getTarget();
        if (target == null || !hidden.isHidden(target.getWorld())) return false;
        item.setComponent(ComponentTypes.LODESTONE_TRACKER,
                new LodestoneTracker(shiftGlobal(target, s, sign), tracker.isTracked()));
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean shiftEntityData(EntityData<?> data, OffsetManager.Session s, boolean positions) {
        Object value = data.getValue();
        var type = data.getType();
        if (positions && type == EntityDataTypes.BLOCK_POSITION && value instanceof Vector3i pos) {
            ((EntityData<Vector3i>) data).setValue(shift(pos, s, 1));
            return true;
        }
        if (positions && type == EntityDataTypes.OPTIONAL_BLOCK_POSITION
                && value instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof Vector3i pos) {
            ((EntityData<Optional<Vector3i>>) data).setValue(Optional.of(shift(pos, s, 1)));
            return true;
        }
        if (type == EntityDataTypes.OPTIONAL_GLOBAL_POSITION
                && value instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof WorldBlockPosition pos
                && hidden.isHidden(pos.getWorld())) {
            ((EntityData<Optional<WorldBlockPosition>>) data).setValue(Optional.of(shiftGlobal(pos, s, 1)));
            return true;
        }
        if (type == EntityDataTypes.ITEMSTACK && value instanceof ItemStack item) {
            return shiftItem(item, s, 1);
        }
        if (type == EntityDataTypes.OPTIONAL_ITEMSTACK
                && value instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof ItemStack item) {
            return shiftItem(item, s, 1);
        }
        return false;
    }

    private static PositionPath shiftPath(PositionPath path, OffsetManager.Session s) {
        if (path instanceof LinearPositionPath linear) {
            return new LinearPositionPath(shift(linear.getEndPosition(), s, 1));
        }
        if (path instanceof SteppedPositionPath stepped) {
            List<SteppedPositionPath.Step> steps = new ArrayList<>(stepped.getSteps().size());
            for (SteppedPositionPath.Step step : stepped.getSteps()) {
                steps.add(new SteppedPositionPath.Step(shift(step.position(), s, 1), step.tickOffset()));
            }
            return new SteppedPositionPath(steps);
        }
        throw new IllegalStateException("알 수 없는 PositionPath: " + path.getClass().getName());
    }

    private static double shiftAxis(double value, RelativeFlag flags, RelativeFlag axis, int offset) {
        return flags != null && flags.has(axis) ? value : value + offset;
    }

    // ───────────────────────── 서버 -> 클라이언트 ─────────────────────────

    private final class Outbound implements PacketListener {

        @Override
        public void onUserDisconnect(UserDisconnectEvent event) {
            UUID id = event.getUser().getUUID();
            if (id != null) offsets.remove(id);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            if (event.getConnectionState() != ConnectionState.PLAY) return;
            UUID id = event.getUser().getUUID();
            if (id == null) return;
            OffsetManager.Session s = offsets.session(id);
            try {
                handle(event, event.getPacketType(), s);
            } catch (RuntimeException e) {
                warnOnce(String.valueOf(event.getPacketType()), e);
            }
        }

        private void handle(PacketSendEvent e, PacketTypeCommon t, OffsetManager.Session s) {
            // ── 월드 전환 추적 ──
            if (t == PacketType.Play.Server.JOIN_GAME) {
                var w = new WrapperPlayServerJoinGame(e);
                s.entityId = w.getEntityId();
                s.inHiddenWorld = hidden.isHidden(w.getWorldName());
                if (reducedDebug) w.setReducedDebugInfo(s.inHiddenWorld);
                if (s.hasOffset()) w.setLastDeathPosition(shiftGlobal(w.getLastDeathPosition(), s, 1));
                e.markForReEncode(true);
                return;
            }
            if (t == PacketType.Play.Server.RESPAWN) {
                var w = new WrapperPlayServerRespawn(e);
                w.getWorldName().ifPresent(name -> s.inHiddenWorld = hidden.isHidden(name));
                if (s.hasOffset()) {
                    w.setLastDeathPosition(shiftGlobal(w.getLastDeathPosition(), s, 1));
                    e.markForReEncode(true);
                }
                if (reducedDebug && s.entityId != Integer.MIN_VALUE) {
                    final int entityId = s.entityId;
                    final int status = s.inHiddenWorld ? STATUS_REDUCED_DEBUG_ON : STATUS_REDUCED_DEBUG_OFF;
                    final User user = e.getUser();
                    e.getTasksAfterSend().add(() ->
                            user.sendPacketSilently(new WrapperPlayServerEntityStatus(entityId, status)));
                }
                return;
            }
            if (t == PacketType.Play.Server.ENTITY_STATUS) {
                if (!reducedDebug) return;
                var w = new WrapperPlayServerEntityStatus(e);
                int status = w.getStatus();
                if (w.getEntityId() == s.entityId
                        && (status == STATUS_REDUCED_DEBUG_ON || status == STATUS_REDUCED_DEBUG_OFF)) {
                    int wanted = s.inHiddenWorld ? STATUS_REDUCED_DEBUG_ON : STATUS_REDUCED_DEBUG_OFF;
                    if (status != wanted) {
                        w.setStatus(wanted);
                        e.markForReEncode(true);
                    }
                }
                return;
            }

            if (!s.hasOffset()) return;

            // ── 차원이 명시된 좌표 (현재 월드와 무관) ──
            if (t == PacketType.Play.Server.SPAWN_POSITION) {
                var w = new WrapperPlayServerSpawnPosition(e);
                boolean shiftIt = w.getDimension() != null ? hidden.isHidden(w.getDimension()) : s.inHiddenWorld;
                if (shiftIt) {
                    w.setPosition(shift(w.getPosition(), s, 1));
                    e.markForReEncode(true);
                }
                return;
            }
            if (t == PacketType.Play.Server.SET_SLOT) {
                var w = new WrapperPlayServerSetSlot(e);
                if (shiftItem(w.getItem(), s, 1)) e.markForReEncode(true);
                return;
            }
            if (t == PacketType.Play.Server.WINDOW_ITEMS) {
                var w = new WrapperPlayServerWindowItems(e);
                boolean changed = false;
                for (ItemStack item : w.getItems()) changed |= shiftItem(item, s, 1);
                Optional<ItemStack> carried = w.getCarriedItem();
                if (carried != null && carried.isPresent()) changed |= shiftItem(carried.get(), s, 1);
                if (changed) e.markForReEncode(true);
                return;
            }
            if (t == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
                var w = new WrapperPlayServerSetPlayerInventory(e);
                if (shiftItem(w.getStack(), s, 1)) e.markForReEncode(true);
                return;
            }
            if (t == PacketType.Play.Server.SET_CURSOR_ITEM) {
                var w = new WrapperPlayServerSetCursorItem(e);
                if (shiftItem(w.getStack(), s, 1)) e.markForReEncode(true);
                return;
            }
            if (t == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                var w = new WrapperPlayServerEntityEquipment(e);
                boolean changed = false;
                for (Equipment equipment : w.getEquipment()) changed |= shiftItem(equipment.getItem(), s, 1);
                if (changed) e.markForReEncode(true);
                return;
            }
            if (t == PacketType.Play.Server.ENTITY_METADATA) {
                var w = new WrapperPlayServerEntityMetadata(e);
                boolean changed = false;
                for (EntityData<?> data : w.getEntityMetadata()) changed |= shiftEntityData(data, s, s.inHiddenWorld);
                if (changed) e.markForReEncode(true);
                return;
            }

            if (!s.inHiddenWorld) return;

            // ── 현재 월드의 좌표 ──
            if (t == PacketType.Play.Server.CHUNK_DATA) {
                shiftChunkData(e, s);
            } else if (t == PacketType.Play.Server.UNLOAD_CHUNK) {
                var w = new WrapperPlayServerUnloadChunk(e);
                w.setChunkX(w.getChunkX() + s.chunkX);
                w.setChunkZ(w.getChunkZ() + s.chunkZ);
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.UPDATE_LIGHT) {
                var w = new WrapperPlayServerUpdateLight(e);
                w.setX(w.getX() + s.chunkX);
                w.setZ(w.getZ() + s.chunkZ);
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.UPDATE_VIEW_POSITION) {
                var w = new WrapperPlayServerUpdateViewPosition(e);
                w.setChunkX(w.getChunkX() + s.chunkX);
                w.setChunkZ(w.getChunkZ() + s.chunkZ);
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.CHUNK_BIOMES) {
                var w = new WrapperPlayServerChunkBiomes(e);
                Map<Vector2i, WrapperPlayServerChunkBiomes.ChunkBiomeData> chunks = w.getChunks();
                Map<Vector2i, WrapperPlayServerChunkBiomes.ChunkBiomeData> shifted = new LinkedHashMap<>();
                for (var entry : chunks.entrySet()) {
                    Vector2i pos = entry.getKey();
                    shifted.put(new Vector2i(pos.getX() + s.chunkX, pos.getZ() + s.chunkZ), entry.getValue());
                }
                try {
                    chunks.clear();
                    chunks.putAll(shifted);
                    e.markForReEncode(true);
                } catch (UnsupportedOperationException ex) {
                    e.setCancelled(true); // 바이옴 갱신은 버려도 무해 (실제 좌표 노출 방지)
                }
            } else if (t == PacketType.Play.Server.BLOCK_CHANGE) {
                var w = new WrapperPlayServerBlockChange(e);
                w.setBlockPosition(shift(w.getBlockPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                var w = new WrapperPlayServerMultiBlockChange(e);
                Vector3i section = w.getChunkPosition();
                w.setChunkPosition(new Vector3i(section.getX() + s.chunkX, section.getY(), section.getZ() + s.chunkZ));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.BLOCK_ACTION) {
                var w = new WrapperPlayServerBlockAction(e);
                w.setBlockPosition(shift(w.getBlockPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
                var w = new WrapperPlayServerBlockBreakAnimation(e);
                w.setBlockPosition(shift(w.getBlockPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
                var w = new WrapperPlayServerBlockEntityData(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.ADD_TRANSIENT_BLOCK) {
                var w = new WrapperPlayServerAddTransientBlock(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.EFFECT) {
                var w = new WrapperPlayServerEffect(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.OPEN_SIGN_EDITOR) {
                var w = new WrapperPlayServerOpenSignEditor(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.SOUND_EFFECT) {
                var w = new WrapperPlayServerSoundEffect(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.PARTICLE) {
                var w = new WrapperPlayServerParticle(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                shiftParticle(w.getParticle(), s);
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.EXPLOSION) {
                var w = new WrapperPlayServerExplosion(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.SPAWN_ENTITY) {
                var w = new WrapperPlayServerSpawnEntity(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.ENTITY_TELEPORT) {
                var w = new WrapperPlayServerEntityTeleport(e);
                RelativeFlag flags = w.getRelativeFlags();
                Vector3d p = w.getPosition();
                w.setPosition(new Vector3d(
                        shiftAxis(p.getX(), flags, RelativeFlag.X, s.blockX), p.getY(),
                        shiftAxis(p.getZ(), flags, RelativeFlag.Z, s.blockZ)));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                var w = new WrapperPlayServerEntityPositionSync(e);
                PositionPath path = w.getPosition();
                if (path != null) {
                    w.setPosition(shiftPath(path, s));
                } else {
                    EntityPositionData values = w.getValues();
                    values.setPosition(shift(values.getPosition(), s, 1));
                }
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                var w = new WrapperPlayServerPlayerPositionAndLook(e);
                RelativeFlag flags = w.getRelativeFlags();
                w.setX(shiftAxis(w.getX(), flags, RelativeFlag.X, s.blockX));
                w.setZ(shiftAxis(w.getZ(), flags, RelativeFlag.Z, s.blockZ));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.VEHICLE_MOVE) {
                var w = new WrapperPlayServerVehicleMove(e);
                w.setPosition(shift(w.getPosition(), s, 1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.MOVE_MINECART) {
                var w = new WrapperPlayServerMoveMinecart(e);
                for (WrapperPlayServerMoveMinecart.MinecartStep step : w.getLerpSteps()) {
                    step.setPosition(shift(step.getPosition(), s, 1));
                }
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.INITIALIZE_WORLD_BORDER) {
                var w = new WrapperPlayServerInitializeWorldBorder(e);
                w.setX(w.getX() + s.blockX);
                w.setZ(w.getZ() + s.blockZ);
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.WORLD_BORDER_CENTER) {
                var w = new WrapperPlayServerWorldBorderCenter(e);
                w.setX(w.getX() + s.blockX);
                w.setZ(w.getZ() + s.blockZ);
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Server.FACE_PLAYER) {
                var w = new WrapperPlayServerFacePlayer(e);
                if (w.getTargetPosition() != null) {
                    w.setTargetPosition(shift(w.getTargetPosition(), s, 1));
                    e.markForReEncode(true);
                }
            } else if (t == PacketType.Play.Server.DAMAGE_EVENT) {
                var w = new WrapperPlayServerDamageEvent(e);
                if (w.getSourcePosition() != null) {
                    w.setSourcePosition(shift(w.getSourcePosition(), s, 1));
                    e.markForReEncode(true);
                }
            }
        }

        private void shiftParticle(Particle<?> particle, OffsetManager.Session s) {
            if (particle == null) return;
            Object data = particle.getData();
            if (data instanceof ParticleVibrationData vibration) {
                vibration.getBlockPosition().ifPresent(pos -> vibration.setBlockPosition(shift(pos, s, 1)));
                if (vibration.getStartingPosition() != null) {
                    vibration.setStartingPosition(shift(vibration.getStartingPosition(), s, 1));
                }
            } else if (data instanceof ParticleTrailData trail && trail.getTarget() != null) {
                trail.setTarget(shift(trail.getTarget(), s, 1));
            }
        }

        /** 청크 데이터는 크기가 크므로 버퍼의 앞 8바이트(int x, int z)만 직접 수정. */
        private void shiftChunkData(PacketSendEvent e, OffsetManager.Session s) {
            if (e.needsReEncode() && e.getLastUsedWrapper() instanceof WrapperPlayServerChunkData w) {
                Column c = w.getColumn();
                w.setColumn(new Column(c.getX() + s.chunkX, c.getZ() + s.chunkZ, c.isFullChunk(),
                        c.getChunks(), c.getTileEntities(), c.getHeightmaps()));
                return;
            }
            Object buf = e.getByteBuf();
            int start = ByteBufHelper.readerIndex(buf);
            int x = ByteBufHelper.readInt(buf);
            int z = ByteBufHelper.readInt(buf);
            ByteBufHelper.readerIndex(buf, start);
            int end = ByteBufHelper.writerIndex(buf);
            ByteBufHelper.writerIndex(buf, start);
            ByteBufHelper.writeInt(buf, x + s.chunkX);
            ByteBufHelper.writeInt(buf, z + s.chunkZ);
            ByteBufHelper.writerIndex(buf, end);
        }
    }

    // ───────────────────────── 클라이언트 -> 서버 ─────────────────────────

    private final class Inbound implements PacketListener {

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (event.getConnectionState() != ConnectionState.PLAY) return;
            UUID id = event.getUser().getUUID();
            if (id == null) return;
            OffsetManager.Session s = offsets.session(id);
            if (!s.hasOffset()) return;
            try {
                handle(event, event.getPacketType(), s);
            } catch (RuntimeException e) {
                warnOnce(String.valueOf(event.getPacketType()), e);
            }
        }

        private void handle(PacketReceiveEvent e, PacketTypeCommon t, OffsetManager.Session s) {
            if (t == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
                var w = new WrapperPlayClientCreativeInventoryAction(e);
                if (shiftItem(w.getItemStack(), s, -1)) e.markForReEncode(true);
                return;
            }
            if (!s.inHiddenWorld) return;

            if (t == PacketType.Play.Client.PLAYER_POSITION || t == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                var w = new WrapperPlayClientPlayerFlying(e);
                if (w.hasPositionChanged()) {
                    Location l = w.getLocation();
                    w.setLocation(new Location(l.getX() - s.blockX, l.getY(), l.getZ() - s.blockZ, l.getYaw(), l.getPitch()));
                    e.markForReEncode(true);
                }
            } else if (t == PacketType.Play.Client.VEHICLE_MOVE) {
                var w = new WrapperPlayClientVehicleMove(e);
                w.setPosition(shift(w.getPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.PLAYER_DIGGING) {
                var w = new WrapperPlayClientPlayerDigging(e);
                w.setBlockPosition(shift(w.getBlockPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                var w = new WrapperPlayClientPlayerBlockPlacement(e);
                w.setBlockPosition(shift(w.getBlockPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.UPDATE_SIGN) {
                var w = new WrapperPlayClientUpdateSign(e);
                w.setBlockPosition(shift(w.getBlockPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.QUERY_BLOCK_NBT) {
                var w = new WrapperPlayClientQueryBlockNBT(e);
                w.setBlockPosition(shift(w.getBlockPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.PICK_ITEM_FROM_BLOCK) {
                var w = new WrapperPlayClientPickItemFromBlock(e);
                w.setBlockPos(shift(w.getBlockPos(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.GENERATE_STRUCTURE) {
                var w = new WrapperPlayClientGenerateStructure(e);
                w.setBlockPosition(shift(w.getBlockPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.UPDATE_COMMAND_BLOCK) {
                var w = new WrapperPlayClientUpdateCommandBlock(e);
                w.setPosition(shift(w.getPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.UPDATE_JIGSAW_BLOCK) {
                var w = new WrapperPlayClientUpdateJigsawBlock(e);
                w.setPosition(shift(w.getPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.UPDATE_STRUCTURE_BLOCK) {
                var w = new WrapperPlayClientSetStructureBlock(e);
                w.setPosition(shift(w.getPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.SET_TEST_BLOCK) {
                var w = new WrapperPlayClientSetTestBlock(e);
                w.setPosition(shift(w.getPosition(), s, -1));
                e.markForReEncode(true);
            } else if (t == PacketType.Play.Client.TEST_INSTANCE_BLOCK_ACTION) {
                var w = new WrapperPlayClientTestInstanceBlockAction(e);
                w.setPosition(shift(w.getPosition(), s, -1));
                e.markForReEncode(true);
            }
        }
    }
}
