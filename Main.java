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

    private final long tag;
    private final long flags0, flags1, flags2, flags3;
    private final ComponentType[] components;
    private final byte[] indexes;

    private static final HashMap<Key, Archetype> registry = new HashMap<>();
    private static int counter;

    private Archetype(int id, Key key, ComponentType[] components) {
        if (components.length > 128) {
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
            indexes[i] = -1;
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

    public int size() {
        return components.length;
    }

    public ComponentType get(int i) {
        return components[i];
    }

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

    private Archetype archetype;
    private Object[] buffers;
    long[] handles;
    int count;

    EntityChunk(int id, Archetype archetype, int capacity) {
        this.id = id;
        this.archetype = archetype;
        handles = new long[capacity];
        buffers = new Object[archetype.size()];
        for (int i = 0; i < archetype.size(); ++i) {
            buffers[i] = Array.newInstance(archetype.get(i).type, capacity);
        }
        count = 0;
    }

    public Archetype getArchetype() { return archetype; }

    void setArchetype(Archetype newArchetype) {
        Object[] newBuffers = new Object[newArchetype.size()];
        for (int i = 0; i < newArchetype.size(); ++i) {
            ComponentType c = newArchetype.get(i);
            if (archetype.hasComponent(c)) {
                newBuffers[i] = buffers[archetype.indexOf(c)];
            } else {
                newBuffers[i] = Array.newInstance(c.type, handles.length);
            }
        }
        archetype = newArchetype;
        buffers = newBuffers;
    }

    void grow() {
        int newCapacity = handles.length * 2;
        handles = Arrays.copyOf(handles, newCapacity);
        for (int i = 0; i < buffers.length; ++i) {
            Object newBuffer = Array.newInstance(archetype.get(i).type, newCapacity);
            System.arraycopy(buffers[i], 0, newBuffer, 0, count);
            buffers[i] = newBuffer;
        }
    }

    void copyEntity(int fromIndex, int toIndex) {
        for (Object buffer : buffers) {
            System.arraycopy(buffer, fromIndex, buffer, toIndex, 1);
        }
        handles[toIndex] = handles[fromIndex];
    }

    public boolean isFull() { return count == handles.length; }
    public int size() { return count; }
    public int capacity() { return handles.length; }

    public long getHandle(int i) { return handles[i]; }

    public byte[] get(ByteComponent c) { return (byte[]) buffers[archetype.indexOf(c)]; }
    public short[] get(ShortComponent c) { return (short[]) buffers[archetype.indexOf(c)]; }
    public int[] get(IntComponent c) { return (int[]) buffers[archetype.indexOf(c)]; }
    public long[] get(LongComponent c) { return (long[]) buffers[archetype.indexOf(c)]; }
    public float[] get(FloatComponent c) { return (float[]) buffers[archetype.indexOf(c)]; }
    public double[] get(DoubleComponent c) { return (double[]) buffers[archetype.indexOf(c)]; }
    @SuppressWarnings("unchecked")
    public<T> T[] get(Component<T> c) { return (T[]) buffers[archetype.indexOf(c)]; }
}

final class EntityProxy {
    long handle;
    EntityChunk chunk;
    int index;

    public void setTarget(EntityChunk chunk, int index) {
        handle = chunk.handles[index];
        this.chunk = chunk;
        this.index = index;
    }

    public long getHandle() {
        return handle;
    }

    public byte get(ByteComponent c) { return chunk.get(c)[index]; }
    public short get(ShortComponent c) { return chunk.get(c)[index]; }
    public int get(IntComponent c) { return chunk.get(c)[index]; }
    public long get(LongComponent c) { return chunk.get(c)[index]; }
    public float get(FloatComponent c) { return chunk.get(c)[index]; }
    public double get(DoubleComponent c) { return chunk.get(c)[index]; }
    public<T> T get(Component<T> c) { return chunk.get(c)[index]; }

    public void set(ByteComponent c, byte value) { chunk.get(c)[index] = value; }
    public void set(ShortComponent c, short value) { chunk.get(c)[index] = value; }
    public void set(IntComponent c, int value) { chunk.get(c)[index] = value; }
    public void set(LongComponent c, long value) { chunk.get(c)[index] = value; }
    public void set(FloatComponent c, float value) { chunk.get(c)[index] = value; }
    public void set(DoubleComponent c, double value) { chunk.get(c)[index] = value; }
    public<T> void set(Component<T> c, T value) { chunk.get(c)[index] = value; }
}

final class EntityCollection {
    private final ArrayList<ArchetypePool> pools = new ArrayList<>();
    private ArchetypePool[] poolsByArchetype = new ArchetypePool[128];

    private EntityChunk[] chunks = new EntityChunk[128];
    private int chunkCount;

    private long[] entities = new long[1024];
    private int entitiesCount;
    private int[] entitiesFreelist = new int[256];
    private int entitiesFreelistCount;

    public boolean getEntity(long handle, EntityProxy result) {
        long slotInfo = entities[getHandleEntityIndex(handle)];
        if (getHandleGeneration(handle) == getSlotGeneration(slotInfo)) {
            EntityChunk chunk = chunks[getSlotChunkId(slotInfo)];
            int chunkIndex = getSlotChunkIndex(slotInfo);
            if (chunk.handles[chunkIndex] == handle) {
                result.handle = handle;
                result.chunk = chunk;
                result.index = chunkIndex;
                return true;
            }
            throw new RuntimeException("inconsistent state");
        }
        return false;
    }

    private ArchetypePool getPool(Archetype archetype) {
        while (archetype.id >= poolsByArchetype.length) {
            poolsByArchetype = Arrays.copyOf(poolsByArchetype, poolsByArchetype.length * 2);
        }
        ArchetypePool pool = poolsByArchetype[archetype.id];
        if (pool == null) {
            pool = new ArchetypePool(archetype);
            poolsByArchetype[archetype.id] = pool;
            pools.add(pool);
        }
        return pool;
    }

    private EntityChunk getNonEmptyChunk(Archetype archetype) {
        ArchetypePool pool = getPool(archetype);
        if (pool.notFullChunks.size() > 0) {
            return pool.notFullChunks.get(0);
        }
        int id = chunkCount++;
        EntityChunk chunk = new EntityChunk(id, archetype, 10000);
        if (id == chunks.length) {
            chunks = Arrays.copyOf(chunks, chunks.length * 2);
        }
        chunks[id] = chunk;
        pool.allChunks.add(chunk);
        pool.notFullChunks.add(chunk);
        return chunk;
    }

    public long newEntity(Archetype archetype, EntityProxy result) {
        EntityChunk chunk = getNonEmptyChunk(archetype);
        int chunkIndex = chunk.count++;
        if (chunk.isFull()) {
            getPool(archetype).onChunkFull(chunk);
        }

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
        int generation = getSlotGeneration(prevSlotInfo);
        entities[entityIndex] = makeSlotInfo(chunk.id, chunkIndex, generation);

        long handle = makeHandle(entityIndex, generation);
        chunk.handles[chunkIndex] = handle;

        if (result != null) {
            result.handle = handle;
            result.chunk = chunk;
            result.index = chunkIndex;
        }
        return handle;
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
        if (chunk.handles[chunkIndex] != handle) {
            throw new RuntimeException("inconsistent state");
        }

        if (chunkIndex != chunk.count - 1) {
            // move back last entity, so chunk remains dense
            chunk.copyEntity(chunk.count - 1, chunkIndex);
            long movedHandle = chunk.handles[chunkIndex];
            int movedEntityIndex = getHandleEntityIndex(movedHandle);
            long movedSlotInfo = entities[movedEntityIndex];
            int movedGeneration = getSlotGeneration(movedSlotInfo);
            if (getHandleGeneration(movedHandle) != movedGeneration) {
                throw new RuntimeException("inconsistent state");
            }
            if (getSlotChunkId(movedSlotInfo) != chunk.id) {
                throw new RuntimeException("inconsistent state");
            }
            if (getSlotChunkIndex(movedSlotInfo) != chunk.count - 1) {
                throw new RuntimeException("inconsistent state");
            }
            entities[movedEntityIndex] = makeSlotInfo(chunk.id, chunkIndex, movedGeneration);
        }

        if (chunk.isFull()) {
            getPool(chunk.getArchetype()).onChunkNotFull(chunk);
        }
        --chunk.count;

        entities[entityIndex] = makeSlotInfo(-1, -1, incGeneration(generation));

        if (entitiesFreelistCount == entitiesFreelist.length) {
            entitiesFreelist = Arrays.copyOf(entitiesFreelist, entitiesFreelist.length * 2);
        }
        entitiesFreelist[entitiesFreelistCount++] = entityIndex;

        return true;
    }

    public void changeArchetype(EntityChunk chunk, Archetype newArchetype) {
        Archetype archetype = chunk.getArchetype();
        if (newArchetype == archetype) {
            return;
        }
        ArchetypePool srcPool = getPool(archetype);
        ArchetypePool dstPool = getPool(newArchetype);
        chunk.setArchetype(newArchetype);
        srcPool.removeChunk(chunk);
        dstPool.addChunk(chunk);
    }

    public void addComponents(EntityChunk chunk, ComponentType... components) {
        changeArchetype(chunk, chunk.getArchetype().with(components));
    }

    public void removeComponents(EntityChunk chunk, ComponentType... components) {
        changeArchetype(chunk, chunk.getArchetype().without(components));
    }

    private static int incGeneration(int generation) {
        if (generation == 32767) {
            return 0;
        }
        return generation + 1;
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

    public ArrayList<EntityChunk> chunksMatching(Archetype archetype) {
        ArrayList<EntityChunk> result = new ArrayList<>();
        for (ArchetypePool pool : pools) {
            if (pool.archetype.isSupertype(archetype)) {
                result.addAll(pool.allChunks);
            }
        }
        return result;
    }

    public ArrayList<EntityChunk> chunksMatchingExactly(Archetype archetype) {
        ArrayList<EntityChunk> result = new ArrayList<>();
        for (ArchetypePool pool : pools) {
            if (pool.archetype == archetype) {
                result.addAll(pool.allChunks);
            }
        }
        return result;
    }

    private static final class ArchetypePool {
        final Archetype archetype;
        final ArrayList<EntityChunk> allChunks = new ArrayList<>();
        final ArrayList<EntityChunk> fullChunks = new ArrayList<>();
        final ArrayList<EntityChunk> notFullChunks = new ArrayList<>();

        ArchetypePool(Archetype archetype) {
            this.archetype = archetype;
        }

        void addChunk(EntityChunk chunk) {
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




final class FileObject {
    public final long objectId;
    public final long size;
    public final long remoteId1;
    public final long remoteId2;
    public final long checksum1;
    public final long checksum2;
    public final int checksum3;

    FileObject(long objectId, long size) {
        this.objectId = objectId;
        this.size = size;
        remoteId1 = 0;
        remoteId2 = 0;
        checksum1 = 0;
        checksum2 = 0;
        checksum3 = 0;
    }
}

public class Main {
    static final LongComponent OBJECT_ID = new LongComponent(0);
    static final LongComponent REMOTE_ID1 = new LongComponent(1);
    static final LongComponent REMOTE_ID2 = new LongComponent(2);
    static final DoubleComponent CTIME = new DoubleComponent(3);
    static final DoubleComponent MTIME = new DoubleComponent(4);
    static final LongComponent SIZE = new LongComponent(5);
    static final LongComponent CHECKSUM1 = new LongComponent(6);
    static final LongComponent CHECKSUM2 = new LongComponent(7);
    static final IntComponent CHECKSUM3 = new IntComponent(8);

    public static void main(String[] args) {
        Archetype fileType = Archetype.of(
            OBJECT_ID,
            SIZE,
            REMOTE_ID1,
            REMOTE_ID2,
            CHECKSUM1,
            CHECKSUM2,
            CHECKSUM3
        );
        
        Archetype a = Archetype.of(OBJECT_ID);
        Archetype b = a.with(
            SIZE,
            REMOTE_ID1,
            REMOTE_ID2,
            CHECKSUM1,
            CHECKSUM2,
            CHECKSUM3
        );

        System.out.println(fileType == a);
        System.out.println(fileType == b);

        {
            EntityCollection collection = new EntityCollection();
    
            EntityProxy file1 = new EntityProxy();
            collection.newEntity(fileType, file1);
            file1.set(OBJECT_ID, 1);
            file1.set(SIZE, 123);
    
            EntityProxy file2 = new EntityProxy();
            collection.newEntity(fileType, file2);
            file2.set(OBJECT_ID, 2);
            file2.set(SIZE, 666);
    
            EntityProxy file3 = new EntityProxy();
            collection.newEntity(fileType, file3);
            file3.set(OBJECT_ID, 3);
            file3.set(SIZE, 667);
    
            EntityProxy temp = new EntityProxy();
            System.out.println(collection.getEntity(file2.handle, temp));
            collection.freeEntity(file2.handle);
            System.out.println(collection.getEntity(file2.handle, temp));
    
            EntityProxy file4 = new EntityProxy();
            collection.newEntity(fileType, file4);
            file4.set(OBJECT_ID, 4);
            file4.set(SIZE, 668);
    
            EntityProxy file5 = new EntityProxy();
            collection.newEntity(fileType, file5);
            file5.set(OBJECT_ID, 5);
            file5.set(SIZE, 669);

            for (EntityChunk chunk : collection.chunksMatching(Archetype.of(OBJECT_ID))) {
                collection.addComponents(chunk, CTIME);
            }

            for (EntityChunk chunk : collection.chunksMatching(Archetype.of(CTIME))) {
                int count = chunk.size();
                long[] objectIds = chunk.get(OBJECT_ID);
                double[] ctimes = chunk.get(CTIME);
                for (int i = 0; i < count; ++i) {
                    long id = objectIds[i];
                    System.out.println("id: " + id + ", entityIndex: " + (int) chunk.handles[i] + ", ctime: " + ctimes[i]);
                }
            }
        }

        int N = 30000000;

        long startCreate2 = System.currentTimeMillis();
        EntityCollection collection = new EntityCollection();
        EntityProxy file = new EntityProxy();
        for (int i = 0; i < N; ++i) {
            collection.newEntity(fileType, file);
            file.set(OBJECT_ID, i);
            file.set(SIZE, i);
        }
        long endCreate2 = System.currentTimeMillis();
        System.out.println("Create2: " + (endCreate2 - startCreate2));

        int chunks = 0;
        long startSum2 = System.currentTimeMillis();
        long sum2 = 0;
        for (EntityChunk chunk : collection.chunksMatching(fileType)) {
            int count = chunk.size();
            long[] sizes = chunk.get(SIZE);
            for (int i = 0; i < count; ++i) {
                sum2 += sizes[i];
            }
            ++chunks;
        }
        long endSum2 = System.currentTimeMillis();
        System.out.println("Sum2: " + (endSum2 - startSum2) + " - " + sum2);
        System.out.println("chunks: " + chunks);
        
        long startCreate1 = System.currentTimeMillis();
        FileObject[] files = new FileObject[N];
        for (int i = 0; i < N; ++i) {
            files[i] = new FileObject(i, i);
        }
        long endCreate1 = System.currentTimeMillis();
        System.out.println("Create1: " + (endCreate1 - startCreate1));

        long startSum1 = System.currentTimeMillis();
        long sum1 = 0;
        for (int i = 0; i < N; ++i) {
            sum1 += files[i].size;
        }
        long endSum1 = System.currentTimeMillis();
        System.out.println("Sum1: " + (endSum1 - startSum1) + " - " + sum1);
    }
}
