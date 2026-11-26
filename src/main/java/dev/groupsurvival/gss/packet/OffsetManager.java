package dev.groupsurvival.gss.packet;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 접속(세션)마다 무작위 좌표 오프셋을 발급. 재접속하면 새 오프셋.
 * 오프셋은 청크 단위(16블록 배수)라서 청크 경계/지형이 그대로 맞음.
 */
public final class OffsetManager {

    private final boolean enabled;
    private final int minChunks;
    private final int maxChunks;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public OffsetManager(boolean enabled, int minChunks, int maxChunks) {
        this.enabled = enabled;
        // 클라이언트 좌표 한계(약 ±3천만 블록) 안에 머물도록 제한
        int max = Math.min(Math.max(1, maxChunks), 500_000);
        this.minChunks = Math.min(Math.max(0, minChunks), max);
        this.maxChunks = max;
    }

    public Session session(UUID uuid) {
        return sessions.computeIfAbsent(uuid, id -> create());
    }

    public void remove(UUID uuid) {
        sessions.remove(uuid);
    }

    private Session create() {
        if (!enabled) return new Session(0, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new Session(randomChunks(random), randomChunks(random));
    }

    private int randomChunks(ThreadLocalRandom random) {
        int magnitude = minChunks == maxChunks ? minChunks : random.nextInt(minChunks, maxChunks + 1);
        return random.nextBoolean() ? magnitude : -magnitude;
    }

    public static final class Session {
        /** 청크 단위 오프셋 */
        public final int chunkX;
        public final int chunkZ;
        /** 블록 단위 오프셋 */
        public final int blockX;
        public final int blockZ;
        /** 클라이언트가 현재 좌표 숨김 월드에 있는지 (JoinGame/Respawn 패킷으로 갱신) */
        public volatile boolean inHiddenWorld;
        public volatile int entityId = Integer.MIN_VALUE;

        Session(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.blockX = chunkX << 4;
            this.blockZ = chunkZ << 4;
        }

        /** 좌표 변환이 필요한 상태인지 */
        public boolean shifting() {
            return inHiddenWorld && (chunkX != 0 || chunkZ != 0);
        }

        public boolean hasOffset() {
            return chunkX != 0 || chunkZ != 0;
        }
    }
}
