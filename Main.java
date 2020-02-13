import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;


abstract class ComponentType {
    public static final int MAX_ID = 255;

    public final int id;
    public final Class type;
    public final boolean isMarker;

    private static final HashMap<Integer, ComponentType> registry = new HashMap<>();

    ComponentType(int id, Class type) {
        if (id < 0) {
            throw new IllegalArgumentException("ComponentType ID must be positive integer.");
        }
        if (id > MAX_ID) {
            throw new IllegalArgumentException("ComponentType ID is too high. Support more bit flags in Archetype to support higher IDs.");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        
        this.id = id;
        this.type = type;
        isMarker = type == Void.class;

        synchronized (registry) {
            if (registry.containsKey(id)) {
                throw new RuntimeException("ComponentType ID already registered: " + id);
            }
            registry.put(id, this);
        }
    }

    public static ComponentType getComponentType(int id) {
        synchronized (registry) {
            ComponentType type = registry.get(id);
            if (type == null) {
                throw new RuntimeException("ComponentType ID not registered: " + id);
            }
            return type;
        }
    }
}

final class MarkerComponent extends ComponentType {
    public MarkerComponent(int id) {
        super(id, Void.class);
    }
}
final class ByteComponent extends ComponentType {
    public ByteComponent(int id) {
        super(id, byte.class);
    }
}
final class ShortComponent extends ComponentType {
    public ShortComponent(int id) {
        super(id, short.class);
    }
}
final class IntComponent extends ComponentType {
    public IntComponent(int id) {
        super(id, int.class);
    }
}
final class LongComponent extends ComponentType {
    public LongComponent(int id) {
        super(id, long.class);
    }
}
final class FloatComponent extends ComponentType {
    public FloatComponent(int id) {
        super(id, float.class);
    }
}
final class DoubleComponent extends ComponentType {
    public DoubleComponent(int id) {
        super(id, double.class);
    }
}
final class Component<T> extends ComponentType {
    public Component(int id, Class<T> type) {
        super(id, type);
    }
}

final class StandardComponents {
    public static final LongComponent HANDLE = new LongComponent(0);
}


final class Util {
    static long murmur64(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}


final class Archetype implements Iterable<ComponentType> {
    public final int id;
    public final Key key;
    public final long tag;

    private final long flags0, flags1, flags2, flags3;
    private final ComponentType[] components;
    private final byte[] indexes;

    private static final HashMap<Key, Archetype> registry = new HashMap<>();
    private static int counter;

    private Archetype(int id, Key key, ComponentType[] components) {
        if (components.length >= 128) {
            throw new RuntimeException("too many components in archetype");
        }
        this.id = id;
        this.key = key;
        this.tag = key.tag;
        this.flags0 = key.flags0;
        this.flags1 = key.flags1;
        this.flags2 = key.flags2;
        this.flags3 = key.flags3;
        this.components = components;
        indexes = new byte[ComponentType.MAX_ID + 1];
        for (int i = 0; i <= ComponentType.MAX_ID; ++i) {
            // point past the last component by default (there will be a null buffer there)
            indexes[i] = (byte) components.length;
        }
        for (int i = 0; i < components.length; ++i) {
            indexes[components[i].id] = (byte) i;
        }
    }

    private static Archetype getOrCreate(Key key, ComponentType[] components) {
        synchronized (registry) {
            Archetype archetype = registry.get(key);
            if (archetype == null) {
                archetype = new Archetype(counter++, key, components);
                registry.put(key, archetype);
            }
            return archetype;
        }
    }

    public static Archetype of(long tag, ComponentType... components) {
        components = normalize(components);
        long flags0 = 0, flags1 = 0, flags2 = 0, flags3 = 0;
        for (ComponentType c : components) {
            int id = c.id;
            if (id < 64) {
                flags0 |= 1L << id;
            } else if (id < 128) {
                flags1 |= 1L << (id - 64);
            } else if (id < 192) {
                flags2 |= 1L << (id - 128);
            } else if (id < 256) {
                flags3 |= 1L << (id - 192);
            } else {
                throw new UnsupportedOperationException("should not happen");
            }
        }
        return getOrCreate(new Key(tag, flags0, flags1, flags2, flags3), components);
    }

    private static ComponentType[] normalize(ComponentType[] components) {
        Arrays.sort(components, (a, b) -> {
            return a.id - b.id;
        });
        int out = 0;
        for (int inp = 1; inp < components.length; ++inp) {
            if (components[inp].id != components[out].id) {
                components[++out] = components[inp];
            }
        }
        int newLength = out + 1;
        if (newLength == components.length) {
            return components;
        }
        return Arrays.copyOf(components, newLength);
    }

    public static Archetype of(ComponentType... components) {
        return of(0, components);
    }

    public Archetype with(long tag, ComponentType... components) {
        ComponentType[] newComponents = Arrays.copyOf(this.components, this.components.length + components.length);
        System.arraycopy(components, 0, newComponents, this.components.length, components.length);
        return of(tag, newComponents);
    }

    public Archetype with(ComponentType... components) {
        return with(tag, components);
    }

    public Archetype with(long tag) {
        if (tag == this.tag) {
            return this;
        }
        return getOrCreate(new Key(tag, flags0, flags1, flags2, flags3), components);
    }

    public Archetype without(ComponentType... components) {
        int removeCount = 0;
        for (ComponentType c : components) {
            if (hasComponent(c)) {
                ++removeCount;
            }
        }
        if (removeCount == 0) {
            return this;
        }
        ComponentType[] newComponents = new ComponentType[this.components.length - removeCount];
        int i = 0;
        for (ComponentType c : this.components) {
            boolean remove = false;
            for (ComponentType c2 : components) {
                if (c.id == c2.id) {
                    remove = true;
                    break;
                }
            }
            if (!remove) {
                newComponents[i++] = c;
            }
        }
        if (i != newComponents.length) {
            throw new RuntimeException("should not happen");
        }
        return of(tag, newComponents);
    }

    public int indexOf(ComponentType c) {
        return indexes[c.id];
    }

    public boolean hasComponent(ComponentType c) {
        int id = c.id;
        if (id < 64) {
            return (flags0 & (1L << id)) != 0;
        } else if (id < 128) {
            return (flags1 & (1L << (id - 64))) != 0;
        } else if (id < 192) {
            return (flags2 & (1L << (id - 128))) != 0;
        } else if (id < 256) {
            return (flags3 & (1L << (id - 192))) != 0;
        } else {
            return false;
        }
    }

    public boolean isSupertype(Archetype t) {
        return (t.tag == 0 || t.tag == tag)
            && (flags0 & t.flags0) == t.flags0
            && (flags1 & t.flags1) == t.flags1
            && (flags2 & t.flags2) == t.flags2
            && (flags3 & t.flags3) == t.flags3;
    }

    public int size() { return components.length; }
    public ComponentType get(int i) { return components[i]; }

    @Override
    public Iterator<ComponentType> iterator() {
        return new Iterator<ComponentType>() {
            int index = 0;
            @Override
            public boolean hasNext() {
                return components.length > index;
            }
            @Override
            public ComponentType next() {
                return components[index++];
            }
        };
    }

    public static final class Key {
        private final long tag;
        private final long flags0, flags1, flags2, flags3;
        private final int hash;

        private Key(long tag, long flags0, long flags1, long flags2, long flags3) {
            this.tag = tag;
            this.flags0 = flags0;
            this.flags1 = flags1;
            this.flags2 = flags2;
            this.flags3 = flags3;
            int hash = 17;
            hash = hash * 31 + (int) Util.murmur64(tag);
            hash = hash * 31 + (int) Util.murmur64(flags0);
            hash = hash * 31 + (int) Util.murmur64(flags1);
            hash = hash * 31 + (int) Util.murmur64(flags2);
            hash = hash * 31 + (int) Util.murmur64(flags3);
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Key)) {
                return false;
            }
            Key key = (Key) obj;
            return tag == key.tag
                && flags0 == key.flags0
                && flags1 == key.flags1
                && flags1 == key.flags2
                && flags1 == key.flags3;
        }
    }
}



final class EntityChunk {
    public final int id;

    Archetype archetype;
    int capacity;
    int maxCapacity;
    int size;
    Object[] buffers;
    AtomicInteger[] refcounts;

    EntityChunk(int id, Archetype archetype, int capacity, int maxCapacity) {
        this.id = id;
        this.archetype = archetype;
        this.capacity = capacity;
        this.maxCapacity = maxCapacity;
        int bufferCount = archetype.size();
        buffers = new Object[bufferCount + 1];
        refcounts = new AtomicInteger[bufferCount + 1];
        for (int i = 0; i < bufferCount; ++i) {
            buffers[i] = Array.newInstance(archetype.get(i).type, capacity);
            refcounts[i] = new AtomicInteger(1);
        }
        refcounts[bufferCount] = new AtomicInteger(0);
    }

    private EntityChunk(EntityChunk chunk) {
        id = chunk.id;
        archetype = chunk.archetype;
        capacity = chunk.capacity;
        maxCapacity = chunk.maxCapacity;
        size = chunk.size;
        buffers = Arrays.copyOf(chunk.buffers, chunk.buffers.length);
        refcounts = Arrays.copyOf(chunk.refcounts, chunk.refcounts.length);
        int bufferCount = archetype.size();
        for (int i = 0; i < bufferCount; ++i) {
            refcounts[i].incrementAndGet();
        }
    }

    private EntityChunk(EntityChunk chunk, Archetype projection) {
        if (!chunk.archetype.isSupertype(projection)) {
            throw new RuntimeException("illegal projection");
        }
        id = chunk.id;
        archetype = projection;
        capacity = chunk.capacity;
        maxCapacity = chunk.maxCapacity;
        size = chunk.size;
        int bufferCount = projection.size();
        buffers = new Object[bufferCount + 1];
        refcounts = new AtomicInteger[bufferCount];
        for (int i = 0; i < bufferCount; ++i) {
            ComponentType c = projection.get(i);
            int j = chunk.archetype.indexOf(c);
            buffers[i] = chunk.buffers[j];
            AtomicInteger refcount = chunk.refcounts[j];
            refcount.incrementAndGet();
            refcounts[i] = refcount;
        }
    }

    public Archetype getArchetype() { return archetype; }

    void grow() {
        int newCapacity = Math.min(maxCapacity, capacity * 2);
        int bufferCount = archetype.size();
        for (int i = 0; i < bufferCount; ++i) {
            Object newBuffer = Array.newInstance(archetype.get(i).type, newCapacity);
            System.arraycopy(buffers[i], 0, newBuffer, 0, size);
            buffers[i] = newBuffer;
            refcounts[i] = new AtomicInteger(1);
        }
    }

    void copyEntity(int fromIndex, int toIndex) {
        int bufferCount = archetype.size();
        for (int i = 0; i < bufferCount; ++i) {
            Object buffer = getBufferForWriting(i);
            System.arraycopy(buffer, fromIndex, buffer, toIndex, 1);
        }
    }

    public EntityChunk copy() { return new EntityChunk(this); }
    public EntityChunk copyProjection(Archetype projection) { return new EntityChunk(this, projection); }
    public void destroy() { // call destroy() on copied chunks when done with them (enables avoidance of COW copying)
        int bufferCount = archetype.size();
        for (int i = 0; i < bufferCount; ++i) {
            refcounts[i].decrementAndGet();
        }
        archetype = null;
        capacity = 0;
        maxCapacity = 0;
        size = 0;
        buffers = null;
        refcounts = null;
    }

    public boolean isFull() { return size >= maxCapacity; }
    public int size() { return size; }
    public int capacity() { return capacity; }

    private Object getBufferForWriting(int i) {
        AtomicInteger refcount = refcounts[i];
        if (refcount.get() <= 1) {
            return buffers[i];
        }
        Object newBuffer =  Array.newInstance(archetype.get(i).type, capacity);
        System.arraycopy(buffers[i], 0, newBuffer, 0, size);
        refcount.decrementAndGet();
        buffers[i] = newBuffer;
        refcounts[i] = new AtomicInteger(1);
        return newBuffer;
    }

    public byte[] write(ByteComponent c) { return (byte[]) getBufferForWriting(archetype.indexOf(c)); }
    public short[] write(ShortComponent c) { return (short[]) getBufferForWriting(archetype.indexOf(c)); }
    public int[] write(IntComponent c) { return (int[]) getBufferForWriting(archetype.indexOf(c)); }
    public long[] write(LongComponent c) { return (long[]) getBufferForWriting(archetype.indexOf(c)); }
    public float[] write(FloatComponent c) { return (float[]) getBufferForWriting(archetype.indexOf(c)); }
    public double[] write(DoubleComponent c) { return (double[]) getBufferForWriting(archetype.indexOf(c)); }
    @SuppressWarnings("unchecked")
    public<T> T[] write(Component<T> c) { return (T[]) getBufferForWriting(archetype.indexOf(c)); }

    public byte[] read(ByteComponent c) { return (byte[]) buffers[archetype.indexOf(c)]; }
    public short[] read(ShortComponent c) { return (short[]) buffers[archetype.indexOf(c)]; }
    public int[] read(IntComponent c) { return (int[]) buffers[archetype.indexOf(c)]; }
    public long[] read(LongComponent c) { return (long[]) buffers[archetype.indexOf(c)]; }
    public float[] read(FloatComponent c) { return (float[]) buffers[archetype.indexOf(c)]; }
    public double[] read(DoubleComponent c) { return (double[]) buffers[archetype.indexOf(c)]; }
    @SuppressWarnings("unchecked")
    public<T> T[] read(Component<T> c) { return (T[]) buffers[archetype.indexOf(c)]; }
}

final class EntityProxy {
    private EntityChunk chunk;
    private int index;

    public void setTarget(EntityChunk chunk, int index) {
        this.chunk = chunk;
        this.index = index;
    }

    public void set(ByteComponent c, byte value) { chunk.write(c)[index] = value; }
    public void set(ShortComponent c, short value) { chunk.write(c)[index] = value; }
    public void set(IntComponent c, int value) { chunk.write(c)[index] = value; }
    public void set(LongComponent c, long value) { chunk.write(c)[index] = value; }
    public void set(FloatComponent c, float value) { chunk.write(c)[index] = value; }
    public void set(DoubleComponent c, double value) { chunk.write(c)[index] = value; }
    public<T> void set(Component<T> c, T value) { chunk.write(c)[index] = value; }

    public byte get(ByteComponent c) { return chunk.read(c)[index]; }
    public short get(ShortComponent c) { return chunk.read(c)[index]; }
    public int get(IntComponent c) { return chunk.read(c)[index]; }
    public long get(LongComponent c) { return chunk.read(c)[index]; }
    public float get(FloatComponent c) { return chunk.read(c)[index]; }
    public double get(DoubleComponent c) { return chunk.read(c)[index]; }
    public<T> T get(Component<T> c) { return chunk.read(c)[index]; }
}

final class ArchetypeConfig {
    public int initialChunkCapacity = 1024;
    public int maxChunkCapacity = 1024;
}

interface ConfigDelegate {
    void configure(Archetype archetype, ArchetypeConfig config);
}

final class EntityCollection {
    private final ConfigDelegate configDelegate;

    private final ArrayList<ArchetypePool> pools = new ArrayList<>();
    private ArchetypePool[] poolsByArchetype = new ArchetypePool[128];

    private EntityChunk[] chunks = new EntityChunk[64];
    private int chunkCount;
    private int[] chunksFreelist = new int[32];
    private int chunksFreelistCount;

    private long[] entities = new long[128];
    private int entitiesCount;
    private int[] entitiesFreelist = new int[32];
    private int entitiesFreelistCount;

    public EntityCollection() {
        configDelegate = (archetype, config) -> {};
    }
    public EntityCollection(ConfigDelegate configDelegate) {
        this.configDelegate = configDelegate;
    }

    public boolean getEntity(long handle, EntityProxy result) {
        long slotInfo = entities[getHandleEntityIndex(handle)];
        if (getHandleGeneration(handle) == getSlotGeneration(slotInfo)) {
            result.setTarget(chunks[getSlotChunkId(slotInfo)], getSlotChunkIndex(slotInfo));
            return true;
        }
        return false;
    }

    private ArchetypePool getOrCreatePool(Archetype archetype) {
        while (archetype.id >= poolsByArchetype.length) {
            poolsByArchetype = Arrays.copyOf(poolsByArchetype, poolsByArchetype.length * 2);
        }
        ArchetypePool pool = poolsByArchetype[archetype.id];
        if (pool == null) {
            pool = new ArchetypePool(archetype, configDelegate);
            poolsByArchetype[archetype.id] = pool;
            pools.add(pool);
        }
        return pool;
    }

    private EntityChunk getNonEmptyChunk(Archetype archetype) {
        ArchetypePool pool = getOrCreatePool(archetype);
        if (pool.notFullChunks.size() > 0) {
            return pool.notFullChunks.get(0);
        }
        int id;
        if (chunksFreelistCount > 0) {
            id = chunksFreelist[--chunksFreelistCount];
        } else {
            id = chunkCount++;
            if (id == chunks.length) {
                chunks = Arrays.copyOf(chunks, chunks.length * 2);
            }
        }
        EntityChunk chunk = new EntityChunk(id, archetype, pool.config.initialChunkCapacity, pool.config.maxChunkCapacity);
        chunks[id] = chunk;
        pool.addChunk(chunk);
        return chunk;
    }

    public long newEntity(Archetype archetype, EntityProxy result) {
        EntityChunk chunk = getNonEmptyChunk(archetype);
        int chunkIndex = chunk.size++;
        if (chunk.isFull()) {
            poolsByArchetype[archetype.id].onChunkFull(chunk);
        }
        if (chunkIndex >= chunk.capacity) {
            chunk.grow();
        }
        if (result != null) {
            result.setTarget(chunk, chunkIndex);
        }
        long[] handles = chunk.write(StandardComponents.HANDLE);
        if (handles != null) {
            long handle = allocHandle(chunk, chunkIndex);
            handles[chunkIndex] = handle;
            return handle;
        }
        return 0;
    }
    public long newEntity(Archetype archetype) {
        return newEntity(archetype, null);
    }

    public boolean freeEntity(long handle) {
        int entityIndex = getHandleEntityIndex(handle);
        int generation = getHandleGeneration(handle);
        long slotInfo = entities[entityIndex];
        if (generation != getSlotGeneration(slotInfo)) {
            return false;
        }

        int chunkId = getSlotChunkId(slotInfo);
        int chunkIndex = getSlotChunkIndex(slotInfo);
        EntityChunk chunk = chunks[chunkId];
        long[] handles = chunk.read(StandardComponents.HANDLE);
        if (handles[chunkIndex] != handle) {
            throw new RuntimeException("inconsistent state");
        }

        if (chunkIndex != chunk.size - 1) {
            // move back last entity, so chunk remains dense
            chunk.copyEntity(chunk.size - 1, chunkIndex);
            long movedHandle = handles[chunkIndex];
            int movedEntityIndex = getHandleEntityIndex(movedHandle);
            long movedSlotInfo = entities[movedEntityIndex];
            int movedGeneration = getSlotGeneration(movedSlotInfo);
            if (getHandleGeneration(movedHandle) != movedGeneration) {
                throw new RuntimeException("inconsistent state");
            }
            if (getSlotChunkId(movedSlotInfo) != chunk.id) {
                throw new RuntimeException("inconsistent state");
            }
            if (getSlotChunkIndex(movedSlotInfo) != chunk.size - 1) {
                throw new RuntimeException("inconsistent state");
            }
            entities[movedEntityIndex] = makeSlotInfo(chunk.id, chunkIndex, movedGeneration);
        }

        if (chunk.isFull()) {
            poolsByArchetype[chunk.archetype.id].onChunkNotFull(chunk);
        }
        --chunk.size;

        deallocHandle(entityIndex, generation);
        return true;
    }


    private long allocHandle(EntityChunk chunk, int chunkIndex) {
        int entityIndex;
        if (entitiesFreelistCount > 0) {
            entityIndex = entitiesFreelist[--entitiesFreelistCount];
        } else {
            entityIndex = entitiesCount++;
            if (entityIndex == entities.length) {
                entities = Arrays.copyOf(entities, entities.length * 2);
            }
        }
        long prevSlotInfo = entities[entityIndex];
        int generation = incGeneration(getSlotGeneration(prevSlotInfo));
        entities[entityIndex] = makeSlotInfo(chunk.id, chunkIndex, generation);
        return makeHandle(entityIndex, generation);
    }
    private void deallocHandle(int entityIndex, int generation) {
        entities[entityIndex] = makeSlotInfo(-1, -1, incGeneration(generation));
        if (entitiesFreelistCount == entitiesFreelist.length) {
            entitiesFreelist = Arrays.copyOf(entitiesFreelist, entitiesFreelist.length * 2);
        }
        entitiesFreelist[entitiesFreelistCount++] = entityIndex;
    }
    private void deallocHandle(long handle) {
        deallocHandle(getHandleEntityIndex(handle), getHandleGeneration(handle));
    }

    public void freeChunk(EntityChunk chunk) {
        ArchetypePool pool = poolsByArchetype[chunk.archetype.id];
        pool.removeChunk(chunk);
        chunks[chunk.id] = null;

        if (chunksFreelistCount == chunksFreelist.length) {
            chunksFreelist = Arrays.copyOf(chunksFreelist, chunksFreelist.length * 2);
        }
        chunksFreelist[chunksFreelistCount++] = chunk.id;

        long[] handles = chunk.read(StandardComponents.HANDLE);
        if (handles != null) {
            for (int i = 0; i < chunk.size; ++i) {
                deallocHandle(handles[i]);
            }
        }
    }

    public void changeArchetype(EntityChunk chunk, Archetype newArchetype) {
        Archetype archetype = chunk.archetype;
        if (newArchetype == archetype) {
            return;
        }

        long[] handles = chunk.read(StandardComponents.HANDLE);

        int bufferCount = newArchetype.size();
        Object[] newBuffers = new Object[bufferCount + 1];
        AtomicInteger[] newRefcounts = new AtomicInteger[bufferCount];
        for (int i = 0; i < bufferCount; ++i) {
            ComponentType c = newArchetype.get(i);
            if (archetype.hasComponent(c)) {
                int j = archetype.indexOf(c);
                newBuffers[i] = chunk.buffers[j];
                newRefcounts[i] = chunk.refcounts[j];
            } else {
                newBuffers[i] = Array.newInstance(c.type, chunk.capacity);
                newRefcounts[i] = new AtomicInteger(1);
            }
        }
        
        chunk.archetype = newArchetype;
        chunk.buffers = newBuffers;
        chunk.refcounts = newRefcounts;

        long[] newHandles = chunk.write(StandardComponents.HANDLE);
        if (newHandles != null && handles == null) {
            for (int i = 0; i < chunk.size; ++i) {
                newHandles[i] = allocHandle(chunk, i);
            }
        } else if (newHandles == null && handles != null) {
            for (int i = 0; i < chunk.size; ++i) {
                deallocHandle(handles[i]);
            }
        }

        poolsByArchetype[archetype.id].removeChunk(chunk);
        getOrCreatePool(newArchetype).addChunk(chunk);
    }

    public void addComponents(EntityChunk chunk, ComponentType... components) {
        changeArchetype(chunk, chunk.archetype.with(components));
    }
    
    public void removeComponents(EntityChunk chunk, ComponentType... components) {
        changeArchetype(chunk, chunk.archetype.without(components));
    }

    private static int incGeneration(int generation) {
        return (generation + 1) & 32767;
    }

    private static long makeHandle(int entityIndex, int generation) {
        return (((long) generation) << 32) | (entityIndex & 0xffffffffL);
    }
    private static int getHandleEntityIndex(long entityHandle) {
        return (int) entityHandle;
    }
    private static int getHandleGeneration(long entityHandle) {
        return (int) (entityHandle >> 32);
    }

    private static long makeSlotInfo(int chunkId, int chunkIndex, int generation) {
        if (chunkIndex >= 32768) {
            throw new RuntimeException("chunk index out of range");
        }
        int chunkIndexAndGeneration = (generation << 16) | ((short) chunkIndex & 0xffff);
        return (((long) chunkIndexAndGeneration) << 32) | (chunkId & 0xffffffffL);
    }
    private static int getSlotChunkId(long slotInfo) {
        return (int) slotInfo;
    }
    private static int getSlotGeneration(long slotInfo) {
        int chunkIndexAndGeneration = (int) (slotInfo >> 32);
        return (int) (short) (chunkIndexAndGeneration >> 16);
    }
    private static int getSlotChunkIndex(long slotInfo) {
        int chunkIndexAndGeneration = (int) (slotInfo >> 32);
        return (int) (short) chunkIndexAndGeneration;
    }

    public interface ArchetypeMatcher {
        boolean matches(Archetype archetype);
    }
    public ArrayList<EntityChunk> findChunks(ArchetypeMatcher matcher) {
        ArrayList<EntityChunk> result = new ArrayList<>();
        for (ArchetypePool pool : pools) {
            if (matcher.matches(pool.archetype)) {
                result.addAll(pool.allChunks);
            }
        }
        return result;
    }
    public ArrayList<EntityChunk> findChunksMatching(Archetype archetype) {
        return findChunks(a -> a.isSupertype(archetype));
    }
    public ArrayList<EntityChunk> findChunksMatchingExactly(Archetype archetype) {
        return findChunks(a -> a == archetype);
    }

    private static final class ArchetypePool {
        final Archetype archetype;
        final ArchetypeConfig config = new ArchetypeConfig();
        final ArrayList<EntityChunk> allChunks = new ArrayList<>();
        final ArrayList<EntityChunk> fullChunks = new ArrayList<>();
        final ArrayList<EntityChunk> notFullChunks = new ArrayList<>();

        ArchetypePool(Archetype archetype, ConfigDelegate configDelegate) {
            this.archetype = archetype;
            configDelegate.configure(archetype, config);
        }

        void addChunk(EntityChunk chunk) {
            chunk.maxCapacity = Math.max(chunk.capacity, config.maxChunkCapacity);
            allChunks.add(chunk);
            if (chunk.isFull()) {
                fullChunks.add(chunk);
            } else {
                notFullChunks.add(chunk);
            }
        }
        void removeChunk(EntityChunk chunk) {
            allChunks.remove(chunk);
            if (chunk.isFull()) {
                fullChunks.remove(chunk);
            } else {
                notFullChunks.remove(chunk);
            }
        }
        void onChunkFull(EntityChunk chunk) {
            notFullChunks.remove(chunk);
            fullChunks.add(chunk);
        }
        void onChunkNotFull(EntityChunk chunk) {
            fullChunks.remove(chunk);
            notFullChunks.add(chunk);
        }
    }
}





public class Main {
    static final LongComponent HANDLE = StandardComponents.HANDLE;

    static final LongComponent OBJECT_ID = new LongComponent(10);
    static final LongComponent REMOTE_ID1 = new LongComponent(11);
    static final LongComponent REMOTE_ID2 = new LongComponent(12);
    static final DoubleComponent CTIME = new DoubleComponent(13);
    static final DoubleComponent MTIME = new DoubleComponent(14);
    static final LongComponent SIZE = new LongComponent(15);
    static final LongComponent CHECKSUM1 = new LongComponent(16);
    static final LongComponent CHECKSUM2 = new LongComponent(17);
    static final IntComponent CHECKSUM3 = new IntComponent(18);
    static final Component<String> ASSET_ID = new Component<>(19, String.class);
    static final MarkerComponent IS_LOCAL_ASSET = new MarkerComponent(20);
    static final MarkerComponent IS_TIMELINE_FILE = new MarkerComponent(21);
    static final Component<String> FILENAME = new Component<>(22, String.class);

    static final Archetype TIMELINE_FILE = Archetype.of(
        IS_TIMELINE_FILE,
        HANDLE,
        REMOTE_ID1,
        REMOTE_ID2,
        FILENAME,
        SIZE,
        CTIME,
        MTIME,
        CHECKSUM1,
        CHECKSUM2,
        CHECKSUM3,
        ASSET_ID
    );

    static final Archetype LOCAL_ASSET = Archetype.of(
        IS_LOCAL_ASSET,
        ASSET_ID,
        FILENAME,
        SIZE,
        CTIME,
        MTIME,
        CHECKSUM1,
        CHECKSUM2,
        CHECKSUM3
    );

    static final ConfigDelegate CONFIG = (archetype, config) -> {
        if (archetype.tag != 0) {
            // Tagged archetypes is intended to define groupings.
            // Simplify things by making sure there is only one chunk per grouping.
            config.maxChunkCapacity = Integer.MAX_VALUE;
        }
    };

    static class Asset {
        String id;
        String filename;
        long size;
        double ctime;
        double mtime;
    }

    private static long timestampTag(double timestamp) {
        return (long) timestamp / (3600 * 24 * 30);
    }

    public static void main(String[] args) {
        EntityCollection collection = new EntityCollection(CONFIG);

        ArrayList<Asset> assets = generateAssets(10000);

        EntityProxy entity = new EntityProxy();
        Archetype archetype = LOCAL_ASSET;
        for (Asset asset : assets) {
            archetype = archetype.with(timestampTag(asset.ctime));
            collection.newEntity(archetype, entity);
            entity.set(ASSET_ID, asset.id);
            entity.set(FILENAME, asset.filename);
            entity.set(SIZE, asset.size);
            entity.set(CTIME, asset.ctime);
            entity.set(MTIME, asset.mtime);
        }
    }

    private static ArrayList<Asset> generateAssets(int count) {
        ArrayList<Asset> result = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            Asset asset = new Asset();
            asset.id = "" + i;
            asset.filename = i + ".jpg";
            asset.size = i + 1000;
            asset.ctime = 1581369296.0 + i * 3600.0;
            asset.mtime = asset.ctime;
            result.add(asset);
        }
        return result;
    }
}
