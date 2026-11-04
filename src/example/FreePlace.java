package FreePlace;

import arc.Core;
import arc.Events;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import java.lang.reflect.Method;
import mindustry.Vars;
import mindustry.core.World;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.input.DesktopInput;
import mindustry.input.Input;
import mindustry.input.MobileInput;
import mindustry.input.PlaceMode;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * 自由放置：拖动建造时按指针轨迹逐格规划，像画笔一样连线，沿原路拖回去可以缩回。
 *
 * 本版针对手机（Mindustry v160.x / MindustryX）做了以下优化，手感与旧版一致：
 *
 *  1) 每帧只算「真的变了」的那部分：指针还停在同一格时整帧直接返回，
 *     只做一次 O(1) 的同步检查，不再每帧把整条笔迹重建一遍。
 *  2) 朝向增量刷新：只在新增格子 / 回退之后重算受影响的尾巴几格，
 *     不再每帧把最多 4096 格的 rotation 全部重排；
 *     固定朝向（ignoreLineRotation / overrideLineRotation）时，
 *     只有 rotation 真的变了才重刷整条线。
 *  3) 回退（缩回）只删尾巴多出来的格子并同步 occupied，
 *     复杂度 O(删掉的格数)，而不是原来的「清空 + 整条重扫」。
 *  4) 指针坐标每帧只取一次 mouseWorld()（旧版 X、Y 各调一次）。
 *  5) 触屏死区（TOUCH_DEADZONE）：手指压在两格边界上时不再每帧
 *     「加一格 / 缩回一格」地抖，边界抖动直接消失。桌面端不启用。
 *  6) 线内部一律用 trail.items[] 直读，省掉迭代器与 for-each 装箱。
 *  7) 起笔格放不下时不再把自己卡死，会自动从第一个能放的格子起笔。
 */
public final class FreePlace {
    /** 单条笔迹最多多少格。 */
    private static final int MAX_POINTS = 4096;
    /**
     * 触屏死区（单位：格）。手指必须越过格子边界这么多，才承认「换了一格」。
     * 越小越跟手，越大越不抖；设成 0f 就是旧版行为。
     */
    private static final float TOUCH_DEADZONE = 0.34f;

    private static final Seq<BuildPlan> trail = new Seq<>();
    private static final IntSet occupied = new IntSet();
    private static final ObjectMap<Class<?>, Boolean> customPath = new ObjectMap<>();
    private static final KeyEdge keys = new KeyEdge();

    private static boolean dragging;
    private static int failures;

    /** 上一帧已经处理完的目标格；本帧目标等于它就整帧跳过。 */
    private static int committedX = Integer.MIN_VALUE;
    private static int committedY = Integer.MIN_VALUE;
    /** 固定朝向模式下已经刷过的 rotation，避免每帧重刷整条线。 */
    private static int fixedRotation = Integer.MIN_VALUE;

    /** 本帧指针的世界坐标与所在格，由 sample() 填。 */
    private static float aimWorldX;
    private static float aimWorldY;
    private static int aimTileX;
    private static int aimTileY;

    private FreePlace() {
    }

    public static void init() {
        if (Vars.headless) {
            return;
        }
        Events.run(EventType.Trigger.update, FreePlace::update);
        Log.info("[tweakermdt] 自由放置已就绪（拖动时按鼠标轨迹规划，按 F1 开关）");
    }

    public static void toggle() {
        boolean on = !ModSettings.freePlaceEnabled();
        Core.settings.put(ModSettings.FREEPLACE_ENABLED, Boolean.valueOf(on));
        if (!on) {
            reset();
            if (Vars.control != null && Vars.control.input != null) {
                Vars.control.input.linePlans.clear();
            }
        }
        Notify.show(Core.bundle.get(on ? "tweakermdt.notify.freeplace.on" : "tweakermdt.notify.freeplace.off"));
    }

    private static void update() {
        if (Vars.headless || Vars.ui == null) {
            return;
        }
        Input in = Vars.control == null ? null : Vars.control.input;
        // 先做最便宜的判断：不满足条件的帧连鼠标坐标都不取。
        if (in == null
                || !ModSettings.freePlaceEnabled()
                || Vars.state == null
                || !Vars.state.isGame()
                || Vars.player == null
                || Vars.player.dead()
                || (Vars.ui.chatfrag != null && Vars.ui.chatfrag.shown())) {
            reset();
            return;
        }
        try {
            if (in instanceof DesktopInput) {
                updateDesktop((DesktopInput) in);
            } else if (in instanceof MobileInput) {
                updateMobile((MobileInput) in);
            } else {
                reset();
            }
        } catch (Throwable t) {
            reset();
            if (failures++ % 120 == 0) {
                Log.err("[tweakermdt] 自由放置执行失败", t);
            }
        }
    }

    private static void updateDesktop(DesktopInput in) {
        Block block = in.block;
        if (block == null || in.mode != PlaceMode.placing || custom(block)) {
            reset();
            return;
        }
        sample(block);
        int tx = aimTileX;
        int ty = aimTileY;

        if (!dragging) {
            // 鼠标按下那一格作为起笔点（selectX < 0 时退回当前格）。
            begin(in, block, in.rotation, in.selectX >= 0 ? in.selectX : tx, in.selectY >= 0 ? in.selectY : ty);
        }

        draw(in, block, in.rotation, in.overrideLineRotation, tx, ty);

        in.selectX = tx;
        in.selectY = ty;
        in.lastLineX = tx;
        in.lastLineY = ty;
    }

    private static void updateMobile(MobileInput in) {
        Block block = in.block;
        if (block == null || in.mode != PlaceMode.placing || !in.lineMode || custom(block)) {
            reset();
            return;
        }
        sample(block);
        int tx = aimTileX;
        int ty = aimTileY;

        if (!dragging) {
            begin(in, block, in.rotation, in.lineStartX, in.lineStartY);
        }

        // 触屏死区：手指压在两格边界上时格子会每帧来回跳，导致「加一格 / 缩回一格」
        // 反复重建笔迹。要求确实越过边界才承认换格，抖动与掉帧一起解决。
        int n = trail.size;
        if (n > 0) {
            BuildPlan tail = trail.items[n - 1];
            tx = snap(aimTileX, tail.x, aimWorldX);
            ty = snap(aimTileY, tail.y, aimWorldY);
        }

        draw(in, block, in.rotation, in.overrideLineRotation, tx, ty);

        in.lineStartX = tx;
        in.lineStartY = ty;
        in.lastLineX = tx;
        in.lastLineY = ty;
    }

    /** 起笔：清状态 + 铺第一格；第一格放不下也不卡死，交给 draw() 再起。 */
    private static void begin(Input in, Block block, int rotation, int seedX, int seedY) {
        trail.clear();
        occupied.clear();
        in.linePlans.clear();
        dragging = true;
        fixedRotation = Integer.MIN_VALUE;
        committedX = Integer.MIN_VALUE;
        committedY = Integer.MIN_VALUE;
        if (seedX >= 0 && seedY >= 0) {
            add(block, rotation, seedX, seedY);
        }
    }

    /** 本帧真正的工作：查回退 → 补线 → 增量重排朝向 → 同步给 linePlans。 */
    private static void draw(Input in, Block block, int rotation, boolean overrideRotation, int tx, int ty) {
        // 目标格和上一帧一样：什么都不会变，只确认一次 linePlans 没被游戏清掉。
        if (tx == committedX && ty == committedY) {
            sync(in.linePlans);
            return;
        }

        // 撞到自己：把那一格之后的全部撤掉（原来的 truncate）。
        int hit = indexOf(tx, ty);
        if (hit >= 0 && hit < trail.size - 1) {
            truncateTo(block, hit);
        }

        int before = trail.size;
        if (before == 0) {
            add(block, rotation, tx, ty);
        } else {
            BuildPlan tail = trail.items[before - 1];
            if (tail.x != tx || tail.y != ty) {
                line(block, rotation, tail.x, tail.y, tx, ty);
            }
        }

        // 从「追加前最后一格」开始重排：它会因为有了后继而改变朝向，
        // 更前面的格子朝向不变，不需要碰。
        orient(block, rotation, overrideRotation, before > 0 ? before - 1 : 0);

        sync(in.linePlans);
        committedX = tx;
        committedY = ty;
    }

    /** 沿着 (x0,y0) → (x1,y1) 逐格补格子；步进方式与旧版一致（X/Y 交替的阶梯）。 */
    private static void line(Block block, int rot, int x0, int y0, int x1, int y1) {
        int x = x0;
        int y = y0;
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int guard = dx + dy + 2;
        boolean alongX = dx >= dy;
        for (int i = 0; i < guard; i++) {
            if (x == x1 && y == y1) {
                return;
            }
            if ((alongX && dx > 0) || dy == 0) {
                x += sx;
                dx--;
            } else {
                y += sy;
                dy--;
            }
            alongX = !alongX;
            add(block, rot, x, y);
        }
    }

    /** 把新格子加进笔迹；放不下就返回 false（不打断后面的格子，与旧版一致）。 */
    private static boolean add(Block block, int rot, int x, int y) {
        if (trail.size >= MAX_POINTS || Vars.world == null) {
            return false;
        }
        if (x < 0 || y < 0 || x >= Vars.world.width() || y >= Vars.world.height()) {
            return false;
        }
        if (Vars.control == null || Vars.control.input == null || !Vars.control.input.validPlace(x, y, block, rot)) {
            return false;
        }
        int size = Math.max(1, block.size);
        if (!occupy(x, y, size)) {
            return false;
        }
        BuildPlan plan = new BuildPlan(x, y, rot, block, block.nextConfig());
        plan.animScale = 1.0f;
        trail.add(plan);
        return true;
    }

    private static boolean occupy(int x, int y, int size) {
        int half = (size - 1) / 2;
        int lo = -half;
        int hi = size - half;
        // 先确认整块都空着，再一次性占掉，避免半占状态。
        for (int dx = lo; dx < hi; dx++) {
            for (int dy = lo; dy < hi; dy++) {
                if (occupied.contains(Point2.pack(x + dx, y + dy))) {
                    return false;
                }
            }
        }
        for (int dx = lo; dx < hi; dx++) {
            for (int dy = lo; dy < hi; dy++) {
                occupied.add(Point2.pack(x + dx, y + dy));
            }
        }
        return true;
    }

    private static void unoccupy(int x, int y, int size) {
        int half = (size - 1) / 2;
        int lo = -half;
        int hi = size - half;
        for (int dx = lo; dx < hi; dx++) {
            for (int dy = lo; dy < hi; dy++) {
                occupied.remove(Point2.pack(x + dx, y + dy));
            }
        }
    }

    /** 从尾巴往回找：笔画通常刚画完，多数命中都在尾巴附近。 */
    private static int indexOf(int x, int y) {
        BuildPlan[] items = trail.items;
        for (int i = trail.size - 1; i >= 0; i--) {
            BuildPlan p = items[i];
            if (p.x == x && p.y == y) {
                return i;
            }
        }
        return -1;
    }

    /** 把笔迹缩到 index 为止，只清理被删掉的那几格，不重扫整条线。 */
    private static void truncateTo(Block block, int index) {
        int size = Math.max(1, block.size);
        BuildPlan[] items = trail.items;
        while (trail.size > index + 1) {
            BuildPlan p = items[trail.size - 1];
            unoccupy(p.x, p.y, size);
            trail.remove(trail.size - 1);
        }
    }

    /**
     * 朝向重排。
     * 非固定朝向：第 i 格指向下一格，最后一格沿用「上一格 → 它」的方向。
     * 因此新增 / 删除尾巴时只有尾巴附近会变，从 from 开始扫即可。
     */
    private static void orient(Block block, int rotation, boolean overrideRotation, int from) {
        int n = trail.size;
        if (n == 0) {
            return;
        }
        BuildPlan[] items = trail.items;

        if (block.ignoreLineRotation || overrideRotation) {
            // 整条线统一用当前 rotation，但只有 rotation 变了才需要重刷。
            if (fixedRotation == rotation) {
                return;
            }
            fixedRotation = rotation;
            for (int i = 0; i < n; i++) {
                items[i].rotation = rotation;
            }
            return;
        }

        int start = from < 0 ? 0 : from;
        for (int i = start; i < n; i++) {
            BuildPlan plan = items[i];
            int rot;
            if (i < n - 1) {
                BuildPlan next = items[i + 1];
                rot = Tile.relativeTo(plan.x, plan.y, next.x, next.y);
            } else if (i > 0) {
                BuildPlan prev = items[i - 1];
                rot = Tile.relativeTo(prev.x, prev.y, plan.x, plan.y);
            } else {
                rot = rotation;
            }
            if (rot != -1) {
                plan.rotation = rot;
            }
        }
    }

    /**
     * 把笔迹同步到游戏的 linePlans。
     * 两者用的是同一批 BuildPlan 对象，所以「格数一样 + 尾巴是同一个对象」就说明没被游戏清过，
     * 直接返回；真需要重建时用 Seq.addAll(Seq) 一次 arraycopy，省掉逐格 add 的循环。
     */
    private static void sync(Seq<BuildPlan> plans) {
        int n = trail.size;
        if (plans.size == n && (n == 0 || plans.items[n - 1] == trail.items[n - 1])) {
            return;
        }
        plans.clear();
        if (n > 0) {
            plans.addAll(trail);
        }
    }

    /** 按类缓存：只有真的重写了 changePlacementPath 的方块才交回原版处理。 */
    private static boolean custom(Block block) {
        Class<?> type = block.getClass();
        Boolean cached = customPath.get(type);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean result;
        try {
            Method two = type.getMethod("changePlacementPath", Seq.class, Integer.TYPE);
            Method three = type.getMethod("changePlacementPath", Seq.class, Integer.TYPE, Boolean.TYPE);
            result = two.getDeclaringClass() != Block.class || three.getDeclaringClass() != Block.class;
        } catch (Throwable t) {
            result = false;
        }
        customPath.put(type, Boolean.valueOf(result));
        return result;
    }

    /**
     * 触屏死区：raw 是手指所在格，current 是尾巴所在格。
     * 只有越过边界 0.5 + TOUCH_DEADZONE 格才承认换格，边界抖动就不会来回翻。
     */
    private static int snap(int raw, int current, float world) {
        if (raw == current) {
            return current;
        }
        float d = world - (current + 0.5f);
        if (Math.abs(d) < 0.5f + TOUCH_DEADZONE) {
            return current;
        }
        return raw;
    }

    /** 每帧只取一次 mouseWorld，X / Y 都从同一个结果读（旧版取了两次）。 */
    private static void sample(Block block) {
        Vec2 v = Core.input.mouseWorld(Core.input.mouseX(), Core.input.mouseY());
        aimWorldX = v.x;
        aimWorldY = v.y;
        float off = block.offset;
        aimTileX = World.toTile(aimWorldX - off);
        aimTileY = World.toTile(aimWorldY - off);
    }

    /** 注意：这里不清 linePlans —— 每帧都会调用，清了会打乱原版自己的连线。 */
    private static void reset() {
        dragging = false;
        trail.clear();
        occupied.clear();
        fixedRotation = Integer.MIN_VALUE;
        committedX = Integer.MIN_VALUE;
        committedY = Integer.MIN_VALUE;
    }
}
