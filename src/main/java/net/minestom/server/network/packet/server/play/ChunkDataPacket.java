package net.minestom.server.network.packet.server.play;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minestom.server.MinecraftServer;
import net.minestom.server.data.Data;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.CustomBlock;
import net.minestom.server.instance.palette.PaletteStorage;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.ServerPacketIdentifier;
import net.minestom.server.utils.BlockPosition;
import net.minestom.server.utils.Utils;
import net.minestom.server.utils.binary.BinaryReader;
import net.minestom.server.utils.binary.BinaryWriter;
import net.minestom.server.utils.cache.CacheablePacket;
import net.minestom.server.utils.cache.TemporaryPacketCache;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.world.biomes.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jglrxavpok.hephaistos.nbt.NBT;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;
import org.jglrxavpok.hephaistos.nbt.NBTException;

import java.io.IOException;
import java.util.UUID;

public class ChunkDataPacket implements ServerPacket, CacheablePacket {

    private static final BlockManager BLOCK_MANAGER = MinecraftServer.getBlockManager();
    private static final TemporaryPacketCache CACHE = new TemporaryPacketCache(10000L);

    public boolean fullChunk;
    public Biome[] biomes;
    public int chunkX, chunkZ;

    public PaletteStorage paletteStorage;
    public PaletteStorage customBlockPaletteStorage;

    public IntSet blockEntities;
    public Int2ObjectMap<Data> blocksData;

    public int[] sections;

    private static final byte CHUNK_SECTION_COUNT = 16;
    private static final int MAX_BITS_PER_ENTRY = 16;
    private static final int MAX_BUFFER_SIZE = (Short.BYTES + Byte.BYTES + 5 * Byte.BYTES + (4096 * MAX_BITS_PER_ENTRY / Long.SIZE * Long.BYTES)) * CHUNK_SECTION_COUNT + 256 * Integer.BYTES;

    // Cacheable data
    private UUID identifier;
    private long lastUpdate;

    private ChunkDataPacket() {
        this(new UUID(0, 0), 0);
    }

    public ChunkDataPacket(@Nullable UUID identifier, long lastUpdate) {
        this.identifier = identifier;
        this.lastUpdate = lastUpdate;
    }

    @Override
    public void write(@NotNull BinaryWriter writer) {
        writer.writeInt(chunkX);
        writer.writeInt(chunkZ);
        writer.writeBoolean(fullChunk);

        int mask = 0;
        ByteBuf blocks = Unpooled.buffer(MAX_BUFFER_SIZE);
        for (byte i = 0; i < CHUNK_SECTION_COUNT; i++) {
            if (fullChunk || (sections.length == CHUNK_SECTION_COUNT && sections[i] != 0)) {
                final long[] section = paletteStorage.getSectionBlocks()[i];
                if (section.length > 0) { // section contains at least one block
                    mask |= 1 << i;
                    Utils.writeBlocks(blocks, paletteStorage.getPalette(i), section, paletteStorage.getBitsPerEntry());
                } else {
                    mask |= 0;
                }
            } else {
                mask |= 0;
            }
        }

        writer.writeVarInt(mask);

        // TODO: don't hardcode heightmaps
        // Heightmap
        int[] motionBlocking = new int[16 * 16];
        int[] worldSurface = new int[16 * 16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                motionBlocking[x + z * 16] = 4;
                worldSurface[x + z * 16] = 5;
            }
        }

        {
            writer.writeNBT("",
                    new NBTCompound()
                            .setLongArray("MOTION_BLOCKING", Utils.encodeBlocks(motionBlocking, 9))
                            .setLongArray("WORLD_SURFACE", Utils.encodeBlocks(worldSurface, 9))
            );
        }

        // Biome data
        if (fullChunk) {
            writer.writeVarInt(biomes.length);
            for (Biome biome : biomes) {
                writer.writeVarInt(biome.getId());
            }
        }

        // Data
        writer.writeVarInt(blocks.writerIndex());
        writer.getBuffer().writeBytes(blocks);
        blocks.release();

        // Block entities
        if (blockEntities == null) {
            writer.writeVarInt(0);
        } else {
            writer.writeVarInt(blockEntities.size());

            for (int index : blockEntities) {
                final BlockPosition blockPosition = ChunkUtils.getBlockPosition(index, chunkX, chunkZ);

                NBTCompound nbt = new NBTCompound()
                        .setInt("x", blockPosition.getX())
                        .setInt("y", blockPosition.getY())
                        .setInt("z", blockPosition.getZ());

                if (customBlockPaletteStorage != null) {
                    final short customBlockId = customBlockPaletteStorage.getBlockAt(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());
                    final CustomBlock customBlock = BLOCK_MANAGER.getCustomBlock(customBlockId);
                    if (customBlock != null) {
                        final Data data = blocksData.get(index);
                        customBlock.writeBlockEntity(blockPosition, data, nbt);
                    }
                }
                writer.writeNBT("", nbt);
            }
        }
    }

    @Override
    public void read(@NotNull BinaryReader reader) {
        chunkX = reader.readInt();
        chunkZ = reader.readInt();
        fullChunk = reader.readBoolean();

        int mask = reader.readVarInt();
        try {
            // TODO: Use heightmaps
            // unused at the moment
            NBT heightmaps = reader.readTag();

            // Biomes
            if (fullChunk) {
                int[] biomesIds = reader.readVarIntArray();
                this.biomes = new Biome[biomesIds.length];
                for (int i = 0; i < biomesIds.length; i++) {
                    this.biomes[i] = MinecraftServer.getBiomeManager().getById(biomesIds[i]);
                }
            }

            // Data
            // TODO don't hardcode bitsPerEntry (use packet field instead)
            this.paletteStorage = new PaletteStorage(8, 1);
            int blockArrayLength = reader.readVarInt();
            for (int section = 0; section < CHUNK_SECTION_COUNT; section++) {
                boolean hasSection = (mask & 1 << section) != 0;
                if (!hasSection)
                    continue;
                short blockCount = reader.readShort();
                byte bitsPerEntry = reader.readByte();
                if (bitsPerEntry < 9) {
                    int paletteSize = reader.readVarInt();
                    for (int i = 0; i < paletteSize; i++) {
                        final int paletteValue = reader.readVarInt();
                        paletteStorage.getPaletteToBlockMaps()[section].put((short) i, (short) paletteValue);
                        paletteStorage.getBlockToPaletteMaps()[section].put((short) paletteValue, (short) i);
                    }
                }

                int dataLength = reader.readVarInt();
                long[] data = new long[dataLength];
                for (int i = 0; i < dataLength; i++) {
                    data[i] = reader.readLong();
                }
                paletteStorage.getSectionBlocks()[section] = data;
            }

            // Block entities
            int blockEntityCount = reader.readVarInt();
            blockEntities = new IntOpenHashSet();
            for (int i = 0; i < blockEntityCount; i++) {
                NBTCompound tag = (NBTCompound) reader.readTag();
                // TODO
            }
        } catch (IOException | NBTException e) {
            MinecraftServer.getExceptionManager().handleException(e);
            // TODO: should we throw to avoid an invalid packet?
        }
    }

    @Override
    public int getId() {
        return ServerPacketIdentifier.CHUNK_DATA;
    }

    @NotNull
    @Override
    public TemporaryPacketCache getCache() {
        return CACHE;
    }

    @Override
    public UUID getIdentifier() {
        return identifier;
    }

    @Override
    public long getLastUpdateTime() {
        return lastUpdate;
    }
}