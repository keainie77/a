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
import mindustry.input.InputHandler;
import mindustry.input.MobileInput;
import mindustry.input.PlaceMode;
import mindustry.mod.Mod;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * 自由放置：拖动建造时按指针轨迹逐格规划，像画笔一样连线；沿原路拖回去可以缩回。
 *
 * 这一版修的是移动端上的两个问题：
 *
 *  1) 只能横平竖直拖
 *     原因不在画线算法，而在时序：本 mod 挂在 Trigger.update 上（Logic.update 的最前面），
 *     原版 MobileInput.update() 在这之后才跑。原版每帧算 lx = tileX(mouseX())，
 *     只要 lx/lastLineX 不同就调用 updateLine(lineStartX, lineStartY, lx, ly)，
 *     用 Placement.normalizeLine() 把 linePlans 整条重建 —— 而 normalizeLine 只沿主轴走，
 *     画出来就是纯横或纯竖的一条线。它正好把本 mod 刚写进去的轨迹整条覆盖掉，
 *     所以屏幕上看到的是原版直线，而不是自定义轨迹。
 *     修法：画完之后把 lastLineX/lastLineY 对齐到「原版自己算出来的那个格」(rawX/rawY)，
 *     原版就认为指针没换格，不再重建 linePlans；lineStartX/lineStartY 也不再被每帧改写，
 *     保持原版长按时的起点（原版 isLinePlacing() 等逻辑依赖它）。
 *
 *  2) 第二次及以后不生效 / 虚影没了
 *     笔触状态没有可靠的「结束」判定：
 *      - 桌面端松手后原版把 selectX/selectY 置为 -1，但本 mod 只检查 block/mode，
 *        dragging 永远为 true、trail 永远不清，下一次点击只是接着上一笔继续画，
 *        sync() 还会把上一次的旧轨迹反复塞回 linePlans。
 *      - 移动端同样只靠 lineMode 收尾，任何一次漏判都会让状态粘住。
 *     修法：把原版自己的「笔触还在不在」信号当作唯一依据
 *     （桌面端 selectX/selectY >= 0，移动端 lineMode），一旦结束就 reset()；
 *     另外新增「可以覆盖自己之前的待建造计划」的判定，避免第二笔画在同一片区域时
 *     被原版 validPlace() 全部判 false（那样也是一个格子都画不出来）。
 */
public class FreePlace extends Mod {
    /** 设置项 key（默认开启）。 */
    private static final String SETTING_KEY = "tweakermdt-freeplace-enabled";

    /** 单条笔迹最多多少格。 */
    private static final int MAX_POINTS = 4096;

    /** 一个格子的世界像素尺寸。Mindustry 固定为 8，这里不依赖 Vars 的字段名。 */
    private static final float TILE = 8f;

    /**
     * 触屏死区（单位：格）。手指压在两格边界上时格子会每帧来回跳，
     * 造成「加一格 / 缩回一格」的抖动；要求越过边界这么多才承认换格。
     * 设成 0f 就是完全不设防区。
     */
    private static final float TOUCH_DEADZONE = 0.3f;

    private static final Seq<BuildPlan> trail = new Seq<>();
    private static final IntSet occupied = new IntSet();
    private static final ObjectMap<Class<?>, Boolean> customPath = new ObjectMap<>();

    /** 一次笔触是否正在进行。 */
    private static boolean dragging;
    /** 防止 mod 被重复加载时注册两次监听。 */
    private static boolean registered;
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

    @Override
    public void loadContent() {
        setup();
    }

    public static void setup() {
        if (Vars.headless || registered) {
            return;
        }
        registered = true;
        Events.run(EventType.Trigger.update, FreePlace::update);
        Log.info("[FreePlace] 自由放置已就绪（拖动时按指针轨迹规划）");
    }

    /** 开关自由放置；默认开启。想接快捷键 / 设置项时调用它即可。 */
    public static void toggle() {
        boolean on = !freePlaceEnabled();
        Core.settings.put(SETTING_KEY, Boolean.valueOf(on));
        if (!on) {
            reset();
            if (Vars.control != null && Vars.control.input != null) {
                Vars.control.input.linePlans.clear();
            }
        }
        notify(on ? "自由放置：已开启（拖动即画笔）" : "自由放置：已关闭");
    }

    private static boolean freePlaceEnabled() {
        return Core.settings.getBool(SETTING_KEY, true);
    }

    private static void notify(String text) {
        if (Vars.ui != null && Vars.ui.hudfrag != null) {
            Vars.ui.hudfrag.showToast(text);
        }
    }

    private static void update() {
        if (Vars.headless || Vars.ui == null) {
            return;
        }
        InputHandler in = Vars.control == null ? null : Vars.control.input;
        // 先做最便宜的判断：不满足条件的帧连鼠标坐标都不取。
        if (in == null
                || !freePlaceEnabled()
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
                Log.err("[FreePlace] 自由放置执行失败", t);
            }
        }
    }

    private static void updateDesktop(DesktopInput in) {
        Block block = in.block;
        // 原版在按下时把 selectX/selectY 记成起点，松手时置为 -1。
        // 用它判断「这一笔还在不在」，松手后状态一定会被清掉。
        if (block == null || in.mode != PlaceMode.placing || in.selectX < 0 || in.selectY < 0 || custom(block)) {
            reset();
            return;
        }
        sample(block);
        int rawX = aimTileX;
        int rawY = aimTileY;

        if (!dragging) {
            begin(in, block, in.rotation, in.selectX, in.selectY);
        }

        draw(in, block, in.rotation, in.overrideLineRotation, rawX, rawY);

        // 关键：对齐原版自己算出来的 cursor 格，原版就不会调用 updateLine() 重建 linePlans。
        in.lastLineX = rawX;
        in.lastLineY = rawY;
    }

    private static void updateMobile(MobileInput in) {
        Block block = in.block;
        if (block == null || in.mode != PlaceMode.placing || !in.lineMode || custom(block)) {
            reset();
            return;
        }
        sample(block);
        int rawX = aimTileX;
        int rawY = aimTileY;

        if (!dragging) {
            // 起点用原版长按记下的 lineStartX/lineStartY，并且之后不再每帧改写它，
            // 否则原版 isLinePlacing()（依赖起点到手指的距离）会一直判 false。
            begin(in, block, in.rotation, in.lineStartX, in.lineStartY);
        }

        // 触屏死区：以尾巴格中心为基准，越过边界 TOUCH_DEADZONE 格才承认换格。
        int tx = rawX;
        int ty = rawY;
        if (!trail.isEmpty()) {
            BuildPlan tail = trail.peek();
            tx = snapTile(rawX, tail.x, aimWorldX - block.offset);
            ty = snapTile(rawY, tail.y, aimWorldY - block.offset);
        }

        draw(in, block, in.rotation, in.overrideLineRotation, tx, ty);

        // 关键：lastLine 必须等于原版下一帧自己算出来的那个格（tileX(mouseX)/tileY(mouseY)），
        // 这样原版就不会 updateLine() 把 linePlans 覆盖成「横平竖直」的原版线段。
        // 注意这里是 rawX/rawY（原始格），不是 tx/ty（死区处理后的目标格）。
        in.lastLineX = rawX;
        in.lastLineY = rawY;
    }

    /** 起笔：清状态 + 铺第一格；第一格放不下也不卡死，交给 draw() 再起。 */
    private static void begin(InputHandler in, Block block, int rotation, int seedX, int seedY) {
        trail.clear();
        occupied.clear();
        in.linePlans.clear();
        dragging = true;
        fixedRotation = Integer.MIN_VALUE;
        committedX = Integer.MIN_VALUE;
        committedY = Integer.MIN_VALUE;
        add(block, rotation, seedX, seedY);
    }

    /** 本帧真正的工作：查回退 → 补线 → 增量重排朝向 → 同步给 linePlans。 */
    private static void draw(InputHandler in, Block block, int rotation, boolean overrideRotation, int tx, int ty) {
        // 目标格和上一帧一样：什么都不会变，只确认一次 linePlans 没被游戏改掉。
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
            BuildPlan tail = trail.peek();
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

    /**
     * 沿着 (x0,y0) → (x1,y1) 逐格补格子。
     * 用 Bresenham 分配两个轴上的步进（每步只走一个轴，保证每一格都被 add() 检查到），
     * 斜拖时 X / Y 大致按比例交替，不会出现「先斜 45° 再拉直」的怪路径。
     */
    private static void line(Block block, int rot, int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int x = x0;
        int y = y0;
        int err = dx - dy;
        int guard = dx + dy + 2;
        while ((x != x1 || y != y1) && guard-- > 0) {
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            } else {
                err += dx;
                y += sy;
            }
            add(block, rot, x, y);
        }
    }

    /** 把新格子加进笔迹；放不下就返回 false（不打断后面的格子，与旧版一致）。 */
    private static boolean add(Block block, int rot, int x, int y) {
        if (trail.size >= MAX_POINTS || Vars.world == null || block == null) {
            return false;
        }
        if (x < 0 || y < 0 || x >= Vars.world.width() || y >= Vars.world.height()) {
            return false;
        }
        if (Vars.control == null || Vars.control.input == null) {
            return false;
        }
        InputHandler in = Vars.control.input;
        int size = Math.max(1, block.size);
        if (!in.validPlace(x, y, block, rot)) {
            // 这一格已经有一个「待建造计划」时原版会判 false（playerPlanTree 命中）。
            // 允许覆盖自己之前的计划 —— 原版 flushSelectPlans() 也是这个语义：
            // 同一位置同尺寸的新计划会替换旧的。否则第二笔在同一片区域会一格都放不下去。
            BuildPlan ignore = planAt(x, y, size);
            if (ignore == null || !in.validPlace(x, y, block, rot, ignore)) {
                return false;
            }
        }
        if (!occupy(x, y, size)) {
            return false;
        }
        BuildPlan plan = new BuildPlan(x, y, rot, block, block.nextConfig());
        plan.animScale = 1.0f;
        trail.add(plan);
        return true;
    }

    /** 找 (x,y) 上尺寸为 size 的待建造计划（用来覆盖自己上一笔）。 */
    private static BuildPlan planAt(int x, int y, int size) {
        if (Vars.player == null || Vars.player.unit() == null || Vars.player.unit().plans == null) {
            return null;
        }
        // 不同版本这里可能是 Seq 也可能是 Queue，统一按 Iterable 遍历，不绑定具体类型。
        for (BuildPlan p : Vars.player.unit().plans) {
            if (p != null && !p.breaking && p.block != null && p.block.size == size && p.x == x && p.y == y) {
                return p;
            }
        }
        return null;
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
     * 两者用的是同一批 BuildPlan 对象，所以「格数一样 + 首尾是同一批对象」就说明没被动过，
     * 直接返回；真需要重建时逐格引用复制（避开不同 arc 版本 addAll 签名差异）。
     */
    private static void sync(Seq<BuildPlan> plans) {
        int n = trail.size;
        if (plans.size == n) {
            if (n == 0 || (plans.items[0] == trail.items[0] && plans.items[n - 1] == trail.items[n - 1])) {
                return;
            }
        }
        plans.clear();
        for (int i = 0; i < n; i++) {
            plans.add(trail.items[i]);
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
     * 触屏死区：raw 是手指所在格，current 是尾巴所在格，pos 是「减掉 block.offset 后」的世界坐标。
     * 只有越过格中心 0.5 + TOUCH_DEADZONE 格才承认换格，边界抖动就不会来回翻。
     */
    private static int snapTile(int raw, int current, float pos) {
        if (raw == current) {
            return current;
        }
        float center = (current + 0.5f) * TILE;
        if (Math.abs(pos - center) < (0.5f + TOUCH_DEADZONE) * TILE) {
            return current;
        }
        return raw;
    }

    /** 每帧只取一次 mouseWorld，X / Y 都从同一个结果读。 */
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
