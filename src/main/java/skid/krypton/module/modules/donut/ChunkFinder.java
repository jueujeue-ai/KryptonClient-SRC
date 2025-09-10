package skid.krypton.module.modules.donut;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import skid.krypton.event.EventListener;
import skid.krypton.event.events.ChunkDataEvent;
import skid.krypton.event.events.Render3DEvent;
import skid.krypton.event.events.SetBlockStateEvent;
import skid.krypton.module.Category;
import skid.krypton.module.Module;
import skid.krypton.module.setting.BooleanSetting;
import skid.krypton.module.setting.ModeSetting;
import skid.krypton.module.setting.NumberSetting;
import skid.krypton.utils.BlockUtil;
import skid.krypton.utils.EncryptedString;
import skid.krypton.utils.RenderUtils;

import java.awt.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChunkFinder extends Module {
    public enum Mode {
        CHAT("Chat", 0),
        TOAST("Toast", 1),
        BOTH("Both", 2);

        Mode(final String name, final int ordinal) {
        }
    }

    // General settings
    private final NumberSetting chunkHighlightRed = new NumberSetting(EncryptedString.of("Highlight Red"), 0.0, 255.0, 255.0, 1.0);
    private final NumberSetting chunkHighlightGreen = new NumberSetting(EncryptedString.of("Highlight Green"), 0.0, 255.0, 255.0, 1.0);
    private final NumberSetting chunkHighlightBlue = new NumberSetting(EncryptedString.of("Highlight Blue"), 0.0, 255.0, 0.0, 1.0);
    private final NumberSetting chunkHighlightAlpha = new NumberSetting(EncryptedString.of("Highlight Alpha"), 0.0, 255.0, 66.0, 1.0);
    
    private final NumberSetting surfaceThickness = new NumberSetting(EncryptedString.of("Surface Thickness"), 0.05, 2.0, 0.1, 0.05).getValue(EncryptedString.of("Thickness of the surface highlight in blocks"));
    
    private final ModeSetting<Mode> notificationMode = new ModeSetting<>(EncryptedString.of("Notification Mode"), Mode.BOTH, Mode.class).setDescription(EncryptedString.of("How to notify when suspicious chunks are detected"));

    // Detection settings
    private final BooleanSetting detectDeepslate = new BooleanSetting(EncryptedString.of("Detect Deepslate"), true).setDescription(EncryptedString.of("Detect regular deepslate blocks"));
    
    private final BooleanSetting detectRotatedDeepslate = new BooleanSetting(EncryptedString.of("Detect Rotated Deepslate"), true).setDescription(EncryptedString.of("Detect rotated deepslate variants"));
    
    private final BooleanSetting detectOneByOneHoles = new BooleanSetting(EncryptedString.of("Detect 1x1 Holes"), true).setDescription(EncryptedString.of("Detect 1x1x1 air holes (player-made)"));

    // Range settings
    private final NumberSetting minY = new NumberSetting(EncryptedString.of("Min Y"), -64.0, 128.0, 9.0, 1.0).getValue(EncryptedString.of("Minimum Y level to scan"));
    
    private final NumberSetting maxY = new NumberSetting(EncryptedString.of("Max Y"), -64.0, 320.0, 15.0, 1.0).getValue(EncryptedString.of("Maximum Y level to scan"));
    
    private final NumberSetting highlightLayer = new NumberSetting(EncryptedString.of("Highlight Layer"), -64.0, 320.0, 52.0, 1.0).getValue(EncryptedString.of("Y level where the chunk highlight will be rendered"));

    // Configurable thresholds
    private final NumberSetting deepslateThreshold = new NumberSetting(EncryptedString.of("Deepslate Threshold"), 1.0, 20.0, 2.0, 1.0).getValue(EncryptedString.of("Minimum deepslate blocks needed to mark chunk as suspicious"));
    
    private final NumberSetting rotatedDeepslateThreshold = new NumberSetting(EncryptedString.of("Rotated Deepslate Threshold"), 1.0, 20.0, 2.0, 1.0).getValue(EncryptedString.of("Minimum rotated deepslate blocks needed to mark chunk as suspicious"));
    
    private final NumberSetting oneByOneHoleThreshold = new NumberSetting(EncryptedString.of("1x1 Hole Threshold"), 1.0, 10.0, 1.0, 1.0).getValue(EncryptedString.of("Minimum 1x1x1 holes needed to mark chunk as suspicious"));

    // Threading settings
    private final BooleanSetting useThreading = new BooleanSetting(EncryptedString.of("Enable Threading"), true).setDescription(EncryptedString.of("Use multi-threading for chunk scanning (better performance)"));
    
    private final NumberSetting threadPoolSize = new NumberSetting(EncryptedString.of("Thread Pool Size"), 1.0, 4.0, 1.0, 1.0).getValue(EncryptedString.of("Number of threads to use for scanning"));
    
    private final NumberSetting scanDelay = new NumberSetting(EncryptedString.of("Scan Delay"), 0.0, 500.0, 1.0, 1.0).getValue(EncryptedString.of("Delay between chunk scans in milliseconds (prevents lag)"));

    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, ChunkScanResult> chunkResults = new ConcurrentHashMap<>();
    private final Set<ChunkPos> processedChunks = ConcurrentHashMap.newKeySet();

    private ExecutorService threadPool;
    private volatile boolean isScanning = false;

    public ChunkFinder() {
        super(EncryptedString.of("Chunk Finder"), EncryptedString.of("Finds and highlights chunks with deepslate, rotated deepslate, and 1x1x1 holes around Y level 10."), -1, Category.DONUT);
        this.addSettings(
            this.chunkHighlightRed, this.chunkHighlightGreen, this.chunkHighlightBlue, this.chunkHighlightAlpha,
            this.surfaceThickness, this.notificationMode,
            this.detectDeepslate, this.detectRotatedDeepslate, this.detectOneByOneHoles,
            this.minY, this.maxY, this.highlightLayer,
            this.deepslateThreshold, this.rotatedDeepslateThreshold, this.oneByOneHoleThreshold,
            this.useThreading, this.threadPoolSize, this.scanDelay
        );
    }

    @Override
    public void onEnable() {
        if (this.mc.world == null) {
            this.toggle();
            return;
        }

        if (this.useThreading.getValue()) {
            this.threadPool = Executors.newFixedThreadPool(this.threadPoolSize.getIntValue());
        }

        this.suspiciousChunks.clear();
        this.chunkResults.clear();
        this.processedChunks.clear();
        this.isScanning = true;

        if (this.useThreading.getValue()) {
            this.threadPool.submit(() -> {
                try {
                    for (WorldChunk chunk : BlockUtil.getLoadedChunks().toList()) {
                        if (chunk != null && this.isScanning) {
                            this.threadPool.submit(() -> this.scanChunkForSuspiciousActivity(chunk));
                            Thread.sleep(this.scanDelay.getIntValue());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } else {
            new Thread(() -> {
                try {
                    for (WorldChunk chunk : BlockUtil.getLoadedChunks().toList()) {
                        if (chunk != null && this.isScanning) {
                            this.scanChunkForSuspiciousActivity(chunk);
                            Thread.sleep(this.scanDelay.getIntValue());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.isScanning = false;

        if (this.threadPool != null && !this.threadPool.isShutdown()) {
            this.threadPool.shutdown();
            try {
                if (!this.threadPool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    this.threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            this.threadPool = null;
        }

        this.suspiciousChunks.clear();
        this.chunkResults.clear();
        this.processedChunks.clear();

        super.onDisable();
    }

    @EventListener
    private void onChunkLoad(final ChunkDataEvent event) {
        if (!this.isScanning) return;

        final ChunkPos chunkPos = new ChunkPos(event.packet.getChunkX(), event.packet.getChunkZ());
        if (this.processedChunks.contains(chunkPos)) return;

        final WorldChunk chunk = (WorldChunk) this.mc.world.getChunk(chunkPos.x, chunkPos.z);

        if (this.useThreading.getValue() && this.threadPool != null && !this.threadPool.isShutdown()) {
            this.threadPool.submit(() -> {
                try {
                    Thread.sleep(this.scanDelay.getIntValue());
                    this.scanChunkForSuspiciousActivity(chunk);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } else {
            new Thread(() -> {
                try {
                    Thread.sleep(this.scanDelay.getIntValue());
                    this.scanChunkForSuspiciousActivity(chunk);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    @EventListener
    private void onBlockUpdate(final SetBlockStateEvent event) {
        if (!this.isScanning) return;

        final BlockPos pos = event.pos;
        final ChunkPos chunkPos = new ChunkPos(pos);

        if (pos.getY() < this.minY.getIntValue() || pos.getY() > this.maxY.getIntValue()) return;

        final WorldChunk chunk = (WorldChunk) this.mc.world.getChunk(chunkPos.x, chunkPos.z);

        final Runnable updateTask = () -> {
            try {
                Thread.sleep(this.scanDelay.getIntValue() / 4);
                this.scanChunkForSuspiciousActivity(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        if (this.useThreading.getValue() && this.threadPool != null && !this.threadPool.isShutdown()) {
            this.threadPool.submit(updateTask);
        } else {
            new Thread(updateTask).start();
        }
    }

    private void scanChunkForSuspiciousActivity(final WorldChunk chunk) {
        if (!this.isScanning) return;

        final ChunkPos cpos = chunk.getPos();

        if (this.processedChunks.contains(cpos)) return;
        this.processedChunks.add(cpos);

        final int xStart = cpos.getStartX();
        final int zStart = cpos.getStartZ();
        final int yMin = Math.max(chunk.getBottomY(), this.minY.getIntValue());
        final int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), this.maxY.getIntValue());

        final ChunkScanResult result = new ChunkScanResult();
        final int step = 1;

        for (int x = xStart; x < xStart + 16; x += step) {
            for (int z = zStart; z < zStart + 16; z += step) {
                for (int y = yMin; y < yMax; y += step) {
                    if (!this.isScanning) return;

                    final BlockPos pos = new BlockPos(x, y, z);
                    final BlockState state = chunk.getBlockState(pos);

                    if (this.detectDeepslate.getValue() && this.isRegularDeepslate(state, y)) {
                        result.deepslateCount++;
                    }

                    if (this.detectRotatedDeepslate.getValue() && this.isRotatedDeepslate(state, y)) {
                        result.rotatedDeepslateCount++;
                    }

                    if (this.detectOneByOneHoles.getValue() && y % 2 == 0 && this.isOneByOneHole(pos)) {
                        result.oneByOneHoleCount++;
                    }
                }
            }
        }

        this.chunkResults.put(cpos, result);

        boolean shouldHighlight = false;
        final StringBuilder reasons = new StringBuilder();

        if (this.detectDeepslate.getValue() && result.deepslateCount >= this.deepslateThreshold.getIntValue()) {
            shouldHighlight = true;
            reasons.append("Deepslate: ").append(result.deepslateCount).append(" ");
        }

        if (this.detectRotatedDeepslate.getValue() && result.rotatedDeepslateCount >= this.rotatedDeepslateThreshold.getIntValue()) {
            shouldHighlight = true;
            reasons.append("Rotated Deepslate: ").append(result.rotatedDeepslateCount).append(" ");
        }

        if (this.detectOneByOneHoles.getValue() && result.oneByOneHoleCount >= this.oneByOneHoleThreshold.getIntValue()) {
            shouldHighlight = true;
            reasons.append("1x1 Holes: ").append(result.oneByOneHoleCount).append(" ");
        }

        if (shouldHighlight) {
            final boolean wasNewChunk = this.suspiciousChunks.add(cpos);
            if (wasNewChunk) {
                final String message = "Suspicious base at " + cpos.x + "," + cpos.z + " - " + reasons.toString().trim();

                switch (this.notificationMode.getValue()) {
                    case CHAT -> {
                        if (this.mc.player != null) {
                            this.mc.player.sendMessage(Text.of("§6[§eChunkFinder§6] §e" + message), false);
                        }
                    }
                    case TOAST -> {
                        // Krypton doesn't have toast system, so we'll use chat as fallback
                        if (this.mc.player != null) {
                            this.mc.player.sendMessage(Text.of("§6[§eChunkFinder§6] §e" + message), true);
                        }
                    }
                    case BOTH -> {
                        if (this.mc.player != null) {
                            this.mc.player.sendMessage(Text.of("§6[§eChunkFinder§6] §e" + message), false);
                            this.mc.player.sendMessage(Text.of("§6[§eChunkFinder§6] §e" + message), true);
                        }
                    }
                }
            }
        } else {
            this.suspiciousChunks.remove(cpos);
        }
    }

    private boolean isRegularDeepslate(final BlockState state, final int y) {
        return y >= this.minY.getIntValue() && y <= this.maxY.getIntValue() && state.getBlock() == Blocks.DEEPSLATE;
    }

    private boolean isRotatedDeepslate(final BlockState state, final int y) {
        if (y < this.minY.getIntValue() || y > this.maxY.getIntValue()) return false;
        if (!state.contains(Properties.AXIS)) return false;

        final Direction.Axis axis = state.get(Properties.AXIS);
        if (axis == Direction.Axis.Y) return false;

        return state.isOf(Blocks.DEEPSLATE) ||
            state.isOf(Blocks.POLISHED_DEEPSLATE) ||
            state.isOf(Blocks.DEEPSLATE_BRICKS) ||
            state.isOf(Blocks.DEEPSLATE_TILES) ||
            state.isOf(Blocks.CHISELED_DEEPSLATE);
    }

    private boolean isOneByOneHole(final BlockPos pos) {
        if (this.mc.world == null) return false;

        if (pos.getY() < this.minY.getIntValue() || pos.getY() > this.maxY.getIntValue()) return false;

        final BlockState selfState = this.mc.world.getBlockState(pos);

        // Only highlight holes above Y level 1
        if (pos.getY() <= 1) return false;
        if (selfState.getBlock() != Blocks.AIR) return false;

        // Check if all 6 sides are solid blocks
        for (final Direction direction : Direction.values()) {
            final BlockState neighborState = this.mc.world.getBlockState(pos.offset(direction));
            if (!neighborState.isSolidBlock(this.mc.world, pos.offset(direction))) {
                return false;
            }
        }

        // Check 5x5x5 area around the hole
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;

                    final BlockPos checkPos = pos.add(x, y, z);
                    final BlockState checkState = this.mc.world.getBlockState(checkPos);

                    if (!checkState.isSolidBlock(this.mc.world, checkPos)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    @EventListener
    private void onRender(final Render3DEvent event) {
        if (this.mc.player == null) return;

        final Color chunkColor = new Color(
            this.chunkHighlightRed.getIntValue(),
            this.chunkHighlightGreen.getIntValue(),
            this.chunkHighlightBlue.getIntValue(),
            this.chunkHighlightAlpha.getIntValue()
        );

        for (final ChunkPos chunkPos : this.suspiciousChunks) {
            final int xStart = chunkPos.getStartX();
            final int zStart = chunkPos.getStartZ();
            final int xEnd = chunkPos.getEndX();
            final int zEnd = chunkPos.getEndZ();

            final double surfaceY = this.highlightLayer.getValue();
            final double thickness = this.surfaceThickness.getValue();

            RenderUtils.renderFilledBox(
                event.matrixStack,
                (float) xStart,
                (float) surfaceY,
                (float) zStart,
                (float) (xEnd + 1),
                (float) (surfaceY + thickness),
                (float) (zEnd + 1),
                chunkColor
            );
        }
    }

    private static class ChunkScanResult {
        int deepslateCount = 0;
        int rotatedDeepslateCount = 0;
        int oneByOneHoleCount = 0;
    }
}