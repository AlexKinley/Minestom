package net.minestom.server.instance;

import com.extollit.gaming.ai.path.model.ColumnarOcclusionFieldList;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.pathfinding.PFBlock;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.utils.ArrayUtils;
import net.minestom.server.utils.PacketUtils;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.world.biomes.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a {@link Chunk} which store each individual block in memory.
 * <p>
 * WARNING: not thread-safe.
 */
public class DynamicChunk extends Chunk {

    protected final Int2ObjectAVLTreeMap<Section> sectionMap = new Int2ObjectAVLTreeMap<>();

    // Key = ChunkUtils#getBlockIndex
    protected final Int2ObjectOpenHashMap<Block> entries = new Int2ObjectOpenHashMap<>();
    protected final Int2ObjectOpenHashMap<Block> tickableMap = new Int2ObjectOpenHashMap<>();

    private volatile long lastChangeTime;

    private ByteBuffer cachedChunkBuffer;
    private ByteBuffer cachedLightBuffer;
    private long cachedPacketTime;

    public DynamicChunk(@NotNull Instance instance, @Nullable Biome[] biomes, int chunkX, int chunkZ) {
        super(instance, biomes, chunkX, chunkZ, true);
    }

    @Override
    public void setBlock(int x, int y, int z, @NotNull Block block) {
        this.lastChangeTime = System.currentTimeMillis();
        // Update pathfinder
        if (columnarSpace != null) {
            final ColumnarOcclusionFieldList columnarOcclusionFieldList = columnarSpace.occlusionFields();
            final var blockDescription = PFBlock.get(block);
            columnarOcclusionFieldList.onBlockChanged(x, y, z, blockDescription, 0);
        }
        Section section = getSection(ChunkUtils.getSectionAt(y));
        section.setBlockAt(x, y, z, block.stateId());

        final int index = ChunkUtils.getBlockIndex(x, y, z);
        // Handler
        final BlockHandler handler = block.handler();
        if (handler != null || block.hasNbt()) {
            this.entries.put(index, block);
        } else {
            this.entries.remove(index);
        }
        // Block tick
        if (handler != null && handler.isTickable()) {
            this.tickableMap.put(index, block);
        } else {
            this.tickableMap.remove(index);
        }
    }

    @Override
    public @NotNull Map<Integer, Section> getSections() {
        return sectionMap;
    }

    @Override
    public @NotNull Section getSection(int section) {
        return sectionMap.computeIfAbsent(section, key -> new Section());
    }

    @Override
    public void tick(long time) {
        if (tickableMap.isEmpty())
            return;
        for (var entry : tickableMap.int2ObjectEntrySet()) {
            final int index = entry.getIntKey();
            final Block block = entry.getValue();
            final var handler = block.handler();
            if (handler != null) {
                final int x = ChunkUtils.blockIndexToChunkPositionX(index);
                final int y = ChunkUtils.blockIndexToChunkPositionY(index);
                final int z = ChunkUtils.blockIndexToChunkPositionZ(index);
                final Vec blockPosition = new Vec(x, y, z);
                handler.tick(new BlockHandler.Tick(block, instance, blockPosition));
            }
        }
    }

    @Override
    public @Nullable Block getBlock(int x, int y, int z, @NotNull Condition condition) {
        // Verify if the block object is present
        final var entry = !entries.isEmpty() ?
                entries.get(ChunkUtils.getBlockIndex(x, y, z)) : null;
        if (entry != null || condition == Condition.CACHED) {
            return entry;
        }
        // Retrieve the block from state id
        final Section section = getOptionalSection(y);
        if (section == null)
            return Block.AIR;
        final short blockStateId = section.getBlockAt(x, y, z);
        return blockStateId > 0 ?
                Objects.requireNonNullElse(Block.fromStateId(blockStateId), Block.AIR) : Block.AIR;
    }

    @Override
    public long getLastChangeTime() {
        return lastChangeTime;
    }

    @Override
    public synchronized void sendChunk(@NotNull Player player) {
        if (!isLoaded()) return;
        final PlayerConnection connection = player.getPlayerConnection();
        if (connection instanceof PlayerSocketConnection) {
            final long lastChange = getLastChangeTime();
            ByteBuffer chunkPacket = cachedChunkBuffer;
            ByteBuffer lightPacket = cachedLightBuffer;
            if (lastChange > cachedPacketTime || (chunkPacket == null || lightPacket == null)) {
                chunkPacket = PacketUtils.allocateTrimmedPacket(createChunkPacket());
                lightPacket = PacketUtils.allocateTrimmedPacket(createLightPacket());
                this.cachedChunkBuffer = chunkPacket;
                this.cachedLightBuffer = lightPacket;
                this.cachedPacketTime = lastChange;
            }
            PlayerSocketConnection socketConnection = (PlayerSocketConnection) connection;
            socketConnection.write(lightPacket);
            socketConnection.write(chunkPacket);
        } else {
            connection.sendPacket(createLightPacket());
            connection.sendPacket(createChunkPacket());
        }
    }

    @Override
    public synchronized void sendChunk() {
        if (!isLoaded()) return;
        sendPacketToViewers(createLightPacket());
        sendPacketToViewers(createChunkPacket());
    }

    @NotNull
    @Override
    public Chunk copy(@NotNull Instance instance, int chunkX, int chunkZ) {
        DynamicChunk dynamicChunk = new DynamicChunk(instance, biomes.clone(), chunkX, chunkZ);
        for (var entry : sectionMap.int2ObjectEntrySet()) {
            dynamicChunk.sectionMap.put(entry.getIntKey(), entry.getValue().clone());
        }
        dynamicChunk.entries.putAll(entries);
        return dynamicChunk;
    }

    @Override
    public void reset() {
        this.sectionMap.values().forEach(Section::clear);
        this.entries.clear();
    }

    private @NotNull ChunkDataPacket createChunkPacket() {
        ChunkDataPacket packet = new ChunkDataPacket();
        packet.biomes = biomes;
        packet.chunkX = chunkX;
        packet.chunkZ = chunkZ;
        packet.sections = sectionMap.clone(); // TODO deep clone
        packet.entries = entries.clone();
        return packet;
    }

    private @NotNull UpdateLightPacket createLightPacket() {
        long skyMask = 0;
        long blockMask = 0;
        List<byte[]> skyLights = new ArrayList<>();
        List<byte[]> blockLights = new ArrayList<>();

        UpdateLightPacket updateLightPacket = new UpdateLightPacket();
        updateLightPacket.chunkX = getChunkX();
        updateLightPacket.chunkZ = getChunkZ();

        updateLightPacket.skyLight = skyLights;
        updateLightPacket.blockLight = blockLights;

        final var sections = getSections();
        for (var entry : sections.entrySet()) {
            final int index = entry.getKey() + 1;
            final Section section = entry.getValue();

            final var skyLight = section.getSkyLight();
            final var blockLight = section.getBlockLight();

            if (!ArrayUtils.empty(skyLight)) {
                skyLights.add(skyLight);
                skyMask |= 1L << index;
            }
            if (!ArrayUtils.empty(blockLight)) {
                blockLights.add(blockLight);
                blockMask |= 1L << index;
            }
        }

        updateLightPacket.skyLightMask = new long[]{skyMask};
        updateLightPacket.blockLightMask = new long[]{blockMask};
        updateLightPacket.emptySkyLightMask = new long[0];
        updateLightPacket.emptyBlockLightMask = new long[0];
        return updateLightPacket;
    }

    private @Nullable Section getOptionalSection(int y) {
        final int sectionIndex = ChunkUtils.getSectionAt(y);
        return sectionMap.get(sectionIndex);
    }
}
