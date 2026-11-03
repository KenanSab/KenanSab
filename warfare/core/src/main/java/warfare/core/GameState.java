package warfare.core;

import warfare.core.NationState.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** The complete, serialisable game simulation. Advance with {@link #advanceDay()}. */
public final class GameState implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final LocalDate START = LocalDate.of(2026, 11, 1);
    public static final int REBELS = -1;

    public transient WorldData world;
    public final int N, P;
    public int day;
    public final int player;
    public Random rng;

    // province dynamic state
    public final int[] owner, controller;
    public final byte[] infra, factory, fort, airfield, radar, silo;
    public final float[] unrest, occupy, devastation;
    // nation dynamic state
    public final NationState[] ns;
    public final boolean[][] war, ally, secret, sanction;
    public final float[][] opinion;
    public final int[][] peaceCooldown, allianceCooldown, warStart, truceUntil, offerCooldown;
    public int lastPeaceOfferDay = -1000;
    public final List<Deal> deals = new ArrayList<>();
    public final Map<Integer, Army> armies = new LinkedHashMap<>();
    public final Map<Integer, Combat.Battle> battles = new HashMap<>();
    public final Intel[] intel;
    public int nextArmyId = 1, nextBattleId = 1, nextWingId = 1;
    public double[] baseUnits;            // starting force size per nation, used to cap AI build-up
    public final List<int[]> nukesInFlight = new ArrayList<>();  // {nation, target, arrivalDay}

    // player-facing feeds
    public final List<String> news = new ArrayList<>();
    public final List<Combat.Report> reports = new ArrayList<>();
    public final List<GameEvent> pendingEvents = new ArrayList<>();
    public boolean pauseRequested;
    public boolean ironman;
    // achievement statistics for the player
    public int stCaptures, stBattlesWon, stTacticWins, stAmbushWins, stCapitals, stNukes;
    public final Set<Integer> awaitingTactic = new LinkedHashSet<>();

    // ------------------------------------------------------------------ setup
    public GameState(WorldData w, int player, long seed) { this(w, player, seed, Scenario.QUIET); }

    public GameState(WorldData w, int player, long seed, Scenario scenario) {
        this.world = w; this.N = w.nations.size(); this.P = w.provinces.size();
        this.player = player; this.rng = new Random(seed);
        owner = new int[P]; controller = new int[P];
        infra = new byte[P]; factory = new byte[P]; fort = new byte[P]; airfield = new byte[P]; radar = new byte[P]; silo = new byte[P];
        unrest = new float[P]; occupy = new float[P]; devastation = new float[P];
        ns = new NationState[N];
        war = new boolean[N][N]; ally = new boolean[N][N]; secret = new boolean[N][N]; sanction = new boolean[N][N];
        opinion = new float[N][N]; peaceCooldown = new int[N][N]; allianceCooldown = new int[N][N]; warStart = new int[N][N]; truceUntil = new int[N][N]; offerCooldown = new int[N][N];
        intel = new Intel[N];
        for (int p = 0; p < P; p++) {
            owner[p] = controller[p] = w.provinces.get(p).sea ? -2 : w.provinces.get(p).nation;
            devastation[p] = 1f;
        }
        for (int n = 0; n < N; n++) {
            ns[n] = new NationState(n);
            WorldData.NationInfo ni = w.nations.get(n);
            double rich = ni.gdp / Math.max(1, ni.population / 1e6);
            ns[n].money = 60 + Math.sqrt(ni.gdp) * 0.4;
            ns[n].manpower = manpowerMax(n) * 0.6;
            ns[n].oil = 60 + ni.oilProduction * 40;
            ns[n].nukes = ni.nukes;
            ns[n].subs = ni.nukes >= 8 ? 2 : ni.nukes >= 5 ? 1 : 0;      // ballistic-missile submarines can launch without a silo
            ns[n].transports = (int) Math.max(0, Math.min(12, Math.sqrt(ni.gdp) / 120));
            ns[n].gov = Government.of(ni.tag);
            ns[n].nextElection = 200 + Math.abs(ni.tag.hashCode()) % Politics.ELECTION_DAYS;
            ns[n].infraIndex = Math.max(0.1, Math.min(0.6, 0.15 + 0.45 * Math.min(1.0, rich / 30000.0)));
            ns[n].stability = 55 + (rich > 15000 ? 12 : 0) + (Math.abs(ni.tag.hashCode()) % 20);
            if (rich > 20000) ns[n].tech[0] = 1;
            if (ni.nukes > 0) { ns[n].tech[2] = 2; ns[n].tech[1] = 1; }
        }
        for (int n = 0; n < N; n++) {
            WorldData.NationInfo ni = w.nations.get(n);
            for (int pid : ni.provinces) {
                WorldData.ProvinceInfo pi = w.provinces.get(pid);
                if (pid == ni.capital) { factory[pid] = 1; infra[pid] = 1; }
                if (pi.gdp > ni.gdp / Math.max(8, ni.provinces.size()) * 2.5) factory[pid] = (byte) Math.max(factory[pid], 1);
            }
            if (ni.nukes > 0) {
                List<Integer> ps = new ArrayList<>(ni.provinces);
                ps.sort(Comparator.comparingDouble(p -> dist(p, ni.capital)));
                for (int k = 0; k < Math.min(3, ps.size()); k++) silo[ps.get(k * Math.min(2, ps.size() / 3))] = 1;
            }
        }
        for (String[] pair : new String[][]{{"RUS", "IRN"}, {"RUS", "PRK"}, {"CHN", "PAK"}, {"CHN", "IRN"}, {"USA", "ISR"}, {"FRA", "MAR"}}) {
            Integer a = w.nationByTag.get(pair[0]), b = w.nationByTag.get(pair[1]);
            if (a != null && b != null) { ally[a][b] = ally[b][a] = true; secret[a][b] = secret[b][a] = true; }
        }
        for (int n = 0; n < N; n++) {
            intel[n] = null;
        }
        Relations.init(this);
        initForces();
        scenario.apply(this);
        Vision.updateAll(this);
    }

    public void attach(WorldData w) { this.world = w; }

    void initForces() {
        baseUnits = new double[N];
        for (int n = 0; n < N; n++) {
            WorldData.NationInfo ni = world.nations.get(n);
            double rich = ni.gdp / Math.max(1, ni.population / 1e6);
            int total = (int) Math.max(4, Math.min(260, Math.round(3 * Math.pow(ni.population / 1e6, 0.45) + Math.sqrt(ni.gdp / 1e5) * 1.2)));
            baseUnits[n] = total;
            int perArmy = 8;
            int count = Math.max(1, (int) Math.ceil(total / (double) perArmy));
            List<Integer> border = new ArrayList<>();
            for (int pid : ni.provinces)
                for (int nb : world.provinces.get(pid).neighbours)
                    if (!world.provinces.get(nb).sea && owner[nb] != n) { border.add(pid); break; }
            if (border.isEmpty()) border.addAll(ni.provinces);
            double mechShare = rich > 25000 ? 0.22 : rich > 8000 ? 0.12 : 0.04;
            double armorShare = rich > 25000 ? 0.14 : rich > 8000 ? 0.10 : 0.05;
            for (int k = 0; k < count; k++) {
                int at = k == 0 ? ni.capital : border.get(rng.nextInt(border.size()));
                Army a = new Army(nextArmyId++, n, at, ordinal(k + 1) + " Army");
                int bn = Math.min(perArmy, total - k * perArmy);
                if (bn <= 0) bn = 2;
                a.units[UnitType.INFANTRY.ordinal()] = Math.max(1, Math.round(bn * (1 - mechShare - armorShare - 0.17)));
                a.units[UnitType.MECHANIZED.ordinal()] = Math.round(bn * mechShare);
                a.units[UnitType.ARMOR.ordinal()] = Math.round(bn * armorShare);
                a.units[UnitType.ARTILLERY.ordinal()] = Math.max(0, Math.round(bn * 0.1));
                a.units[UnitType.ANTI_TANK.ordinal()] = Math.round(bn * 0.04);
                a.units[UnitType.ANTI_AIR.ordinal()] = Math.round(bn * 0.04);
                a.units[UnitType.SPECIAL_FORCES.ordinal()] = rich > 15000 && k == 0 ? 1 : 0;
                a.morale = 70 + rng.nextInt(10);
                armies.put(a.id, a);
            }
            if (ni.nukes > 0 || rich > 25000) {
                int wings = ni.nukes > 0 ? 3 : 1;
                for (int k = 0; k < wings; k++) {
                    AirWing aw = new AirWing();
                    aw.id = nextWingId++; aw.base = ni.capital; aw.mission = Mission.RECON; aw.target = -1;
                    ns[n].airWings.add(aw);
                }
                airfield[ni.capital] = 1;
            }
        }
    }

    static String ordinal(int i) {
        int m = i % 100;
        String s = (m >= 11 && m <= 13) ? "th" : switch (i % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return i + s;
    }

    // ------------------------------------------------------------------ queries
    public LocalDate date() { return START.plusDays(day); }
    public String nationName(int n) { return n < 0 ? "Rebels" : world.nations.get(n).name; }
    public String nationTag(int n) { return n < 0 ? "REB" : world.nations.get(n).tag; }
    public WorldData.ProvinceInfo prov(int p) { return world.provinces.get(p); }
    public boolean isSea(int p) { return world.provinces.get(p).sea; }

    public double dist(int a, int b) {
        WorldData.ProvinceInfo x = world.provinces.get(a), y = world.provinces.get(b);
        double midLat = Math.toRadians((x.lat + y.lat) / 2);
        double dx = (x.cx - y.cx) * WorldData.DEG_PER_PX * Math.cos(midLat) * 111.2;
        double dy = (x.cy - y.cy) * WorldData.DEG_PER_PX * 111.2;
        return Math.hypot(dx, dy);
    }

    public boolean atWar(int a, int b) {
        if (a == b) return false;
        if (a < 0 || b < 0) return true;
        return war[a][b];
    }

    public boolean atWarAny(int n) { for (int i = 0; i < N; i++) if (war[n][i] && ns[i].alive) return true; return false; }

    public boolean isAlly(int a, int b) {
        if (a == b) return true;
        if (a < 0 || b < 0) return false;
        if (ally[a][b]) return true;
        String ba = world.nations.get(a).bloc;
        return !ba.equals("NONE") && ba.equals(world.nations.get(b).bloc);
    }

    private transient boolean[][] borderCache;

    public boolean sharesBorder(int a, int b) {
        if (borderCache == null) {
            borderCache = new boolean[N][N];
            for (WorldData.ProvinceInfo p : world.provinces) {
                if (p.sea || p.nation < 0) continue;
                for (int nb : p.neighbours) {
                    WorldData.ProvinceInfo q = world.provinces.get(nb);
                    if (!q.sea && q.nation >= 0 && q.nation != p.nation) borderCache[p.nation][q.nation] = true;
                }
            }
        }
        return borderCache[a][b];
    }

    public int directAlliances(int n) { int c = 0; for (int m = 0; m < N; m++) if (m != n && ally[n][m] && ns[m].alive) c++; return c; }

    public double opinionOf(int observer, int target) { return opinion[observer][target]; }

    public int ownedProvinces(int n) { int c = 0; for (int p = 0; p < P; p++) if (owner[p] == n) c++; return c; }

    public double manpowerMax(int n) { return world.nations.get(n).population * 0.03 / 1000.0 * Budget.MOB_MANPOWER[ns[n].budget().mobilization]; }

    public double nationHp(int n) {
        double s = 0;
        for (Army a : armies.values()) if (a.owner == n) s += a.hp();
        return s;
    }

    public double popOf(int n) {
        double s = 0;
        for (int p = 0; p < P; p++) if (owner[p] == n) s += world.provinces.get(p).pop * devastation[p];
        return s;
    }

    public double gdpOf(int n) {
        double s = 0;
        for (int p = 0; p < P; p++) if (owner[p] == n) s += world.provinces.get(p).gdp * devastation[p] * (1 + 0.15 * factory[p]);
        return s * ns[n].gdpIndex * (1 + 0.10 * ns[n].infraIndex);
    }

    public double unitsOf(int n) {
        double s = 0;
        for (Army a : armies.values()) if (a.owner == n && !a.decoy) s += a.totalUnits();
        for (NationState.Recruit r : ns[n].recruitQueue) for (double u : r.units) s += u;
        return s;
    }

    public int productionCapacity(int n) {
        double f = 0;
        for (int p = 0; p < P; p++) if (owner[p] == n) f += factory[p];
        return (int) Math.max(1, Math.round((Math.sqrt(gdpOf(n)) / 60.0 + f * 0.5) * (1 + 0.3 * ns[n].budget().industryFocus)));
    }

    public boolean needsIntel(int n) {
        return ns[n].alive && (n == player || atWarAny(n) || day - ns[n].lastWarDay < 40);
    }

    public Intel intelOf(int n) {
        if (intel[n] == null) {
            Intel in = new Intel(P);
            for (int p = 0; p < P; p++) { in.knownOwner[p] = owner[p]; in.lastSeen[p] = -40; }
            intel[n] = in;
        }
        return intel[n];
    }

    /** Armies of {@code a.owner} (and allies) can see this province? */
    public boolean provinceVisible(int observer, int p) { return intelOf(observer).quality[p] > 0; }

    public List<Army> armiesIn(int province) {
        List<Army> r = new ArrayList<>();
        for (Army a : armies.values()) if (a.province == province) r.add(a);
        return r;
    }

    /** Alliances this observer may know about; secret pacts need an intelligence network. */
    public boolean allianceKnownTo(int observer, int a, int b) {
        if (!ally[a][b]) return false;
        if (!secret[a][b]) return true;
        if (observer == a || observer == b) return true;
        return agentLevel(observer, a) >= 25 || agentLevel(observer, b) >= 25;
    }

    public double agentLevel(int observer, int target) { return ns[observer].agents.getOrDefault(target, 0.0); }

    public double airSupport(int nation, int province) {
        double s = 0;
        for (AirWing w : ns[nation].airWings)
            if (w.mission == Mission.CLOSE_SUPPORT && w.target == province) s += 0.08 * w.strength * (1 + 0.15 * ns[nation].tech[1]);
        return Math.min(0.3, s);
    }

    public double landTechMult(int n) { return 1 + 0.06 * ns[n].tech[0]; }

    // ------------------------------------------------------------------ orders
    public static final class Result {
        public final boolean ok; public final String message;
        public Result(boolean ok, String m) { this.ok = ok; this.message = m; }
    }
    static Result ok(String m) { return new Result(true, m); }
    static Result no(String m) { return new Result(false, m); }

    public boolean canEnter(int nation, int q, Army a) {
        if (isSea(q)) {
            if (ns[nation].transports <= 0) return false;
            if (a.embarked) return true;
            long at = armies.values().stream().filter(x -> x.owner == nation && x.embarked).count();
            return at < ns[nation].transports;
        }
        int o = owner[q], c = controller[q];
        if (o == nation || c == nation) return true;
        if (isAlly(nation, o) || isAlly(nation, c)) return true;
        return atWar(nation, o) || atWar(nation, c);
    }

    public double stepDays(Army a, int from, int to) {
        WorldData.ProvinceInfo f = world.provinces.get(from), t = world.provinces.get(to);
        double km = 100;
        for (int k = 0; k < f.neighbours.length; k++) if (f.neighbours[k] == to) { km = f.distKm[k]; break; }
        if (t.sea) return km / 45.0;
        double inf = 1 + 0.18 * Math.min(infra[to], infra[from] == 0 ? infra[to] : infra[from]);
        return km / a.speed() * t.terrain.moveCost / inf;
    }

    public List<Integer> findPath(Army a, int dest) {
        if (dest == a.province || dest < 0 || dest >= P) return List.of();
        double[] best = new double[P];
        Arrays.fill(best, Double.MAX_VALUE);
        int[] prev = new int[P];
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0]));
        best[a.province] = 0;
        pq.add(new double[]{0, a.province});
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            int p = (int) cur[1];
            if (cur[0] > best[p]) continue;
            if (p == dest) break;
            for (int q : world.provinces.get(p).neighbours) {
                if (!canEnter(a.owner, q, a)) continue;
                if (isSea(p) && !isSea(q) && owner[q] >= 0 && !(owner[q] == a.owner || atWar(a.owner, owner[q]) || isAlly(a.owner, owner[q]))) continue;
                double c = cur[0] + stepDays(a, p, q);
                if (c < best[q]) { best[q] = c; prev[q] = p; pq.add(new double[]{c, q}); }
            }
        }
        if (prev[dest] == -1) return List.of();
        LinkedList<Integer> path = new LinkedList<>();
        for (int p = dest; p != a.province; p = prev[p]) path.addFirst(p);
        return path;
    }

    public Result orderMove(Army a, int dest) {
        if (a.battleId >= 0) return no("Army is engaged in battle.");
        List<Integer> path = findPath(a, dest);
        if (path.isEmpty()) return no("No route (borders closed, or no transports for sea crossing).");
        a.path.clear(); a.path.addAll(path);
        a.progressKm = 0;
        return ok("Moving to " + prov(dest).name + " (" + path.size() + " provinces).");
    }

    public Result splitArmy(Army a, double fraction) {
        if (a.totalUnits() < 2 || a.battleId >= 0) return no("Cannot split.");
        Army b = new Army(nextArmyId++, a.owner, a.province, a.name + " (detachment)");
        for (int i = 0; i < a.units.length; i++) { double mv = Math.floor(a.units[i] * fraction); a.units[i] -= mv; b.units[i] = mv; }
        if (!b.alive()) { nextArmyId--; return no("Too small to split."); }
        b.morale = a.morale; b.mode = a.mode; b.embarked = a.embarked;
        armies.put(b.id, b);
        return ok("Split off " + b.name + ".");
    }

    public Result recruit(int n, int province, double[] composition) {
        NationState s = ns[n];
        if (owner[province] != n || controller[province] != n) return no("You must own and control that province.");
        if (s.recruitQueue.size() >= productionCapacity(n)) return no("Production capacity full (" + productionCapacity(n) + " slots).");
        double cost = 0, men = 0, days = 0;
        for (UnitType t : UnitType.values()) {
            double c = composition[t.ordinal()];
            cost += c * t.cost; men += c * t.manpower; days = Math.max(days, t.buildDays * (c > 0 ? 1 : 0));
        }
        if (men <= 0) return no("Nothing to recruit.");
        if (s.money < cost) return no(String.format("Need %.0f money (have %.0f).", cost, s.money));
        if (s.manpower < men) return no(String.format("Need %.0f manpower.", men));
        s.money -= cost; s.manpower -= men;
        Recruit r = new Recruit();
        r.province = province; r.daysLeft = (int) Math.max(3, days - 0.2 * s.tech[0] * 2);
        System.arraycopy(composition, 0, r.units, 0, UnitType.N);
        s.recruitQueue.add(r);
        return ok("Recruiting at " + prov(province).name + " (" + r.daysLeft + " days).");
    }

    public Result build(int n, int province, Building b) {
        NationState s = ns[n];
        if (owner[province] != n || controller[province] != n) return no("You must own and control that province.");
        int lvl = level(b, province);
        for (Construction c : s.constructions) if (c.province == province && c.type == b) return no("Already under construction.");
        if (lvl >= b.maxLevel) return no("Already at maximum level.");
        if (b == Building.SILO && world.nations.get(n).nukes <= 0 && s.nukes <= 0) return no("Only nuclear powers can build silos.");
        double cost = b.cost * (1 + lvl);
        if (s.money < cost) return no(String.format("Need %.0f money.", cost));
        s.money -= cost;
        Construction c = new Construction();
        c.province = province; c.type = b; c.daysLeft = b.days;
        s.constructions.add(c);
        return ok("Building " + b.label + " in " + prov(province).name + ".");
    }

    public int level(Building b, int p) {
        return switch (b) {
            case FACTORY -> factory[p]; case INFRASTRUCTURE -> infra[p]; case FORTIFICATION -> fort[p];
            case AIRFIELD -> airfield[p]; case RADAR -> radar[p]; case SILO -> silo[p];
        };
    }

    void setLevel(Building b, int p, int v) {
        switch (b) {
            case FACTORY -> factory[p] = (byte) v; case INFRASTRUCTURE -> infra[p] = (byte) v; case FORTIFICATION -> fort[p] = (byte) v;
            case AIRFIELD -> airfield[p] = (byte) v; case RADAR -> radar[p] = (byte) v; case SILO -> silo[p] = (byte) v;
        }
    }

    public Result research(int n, Track t) {
        NationState s = ns[n];
        if (s.researching != null) return no("Already researching " + s.researching.label + ".");
        int lvl = s.tech[t.ordinal()];
        if (lvl >= 5) return no("Maximum level.");
        double cost = 60 * (lvl + 1);
        if (s.money < cost) return no(String.format("Need %.0f money.", cost));
        s.money -= cost;
        s.researching = t;
        s.researchLeft = 40.0 * (lvl + 1) / (1 + productionCapacity(n) / 20.0);
        return ok("Researching " + t.label + " level " + (lvl + 1) + " (" + (int) s.researchLeft + " days).");
    }

    public Result addAirWing(int n, int base) {
        NationState s = ns[n];
        if (owner[base] != n || airfield[base] == 0) return no("Needs one of your airfields.");
        if (s.money < 60 || s.manpower < 8) return no("Need 60 money and 8 manpower.");
        s.money -= 60; s.manpower -= 8;
        AirWing w = new AirWing();
        w.id = nextWingId++; w.base = base;
        s.airWings.add(w);
        return ok("Air wing formed at " + prov(base).name + ".");
    }

    public Result setAirMission(int n, AirWing w, Mission m, int target) {
        if (target >= 0 && dist(w.base, target) > 1400 + 250 * ns[n].tech[1]) return no("Target out of range.");
        w.mission = m; w.target = target;
        return ok("Air wing assigned: " + m + (target >= 0 ? " over " + prov(target).name : ""));
    }

    /** Runs a covert operation against another nation; see {@link Ops}. */
    public Result runOp(int a, int b, Ops.Op op) { return Ops.run(this, a, b, op); }

    public Ops.Estimate opsEstimate(int a, int b, Ops.Op op) { return Ops.estimate(this, a, b, op); }

    public Result assignAgent(int n, int target) {
        NationState s = ns[n];
        if (n == target || !ns[target].alive) return no("Invalid target.");
        if (s.money < 25) return no("Need 25 money.");
        s.money -= 25;
        s.agents.merge(target, 5.0, Double::sum);
        return ok("Agent sent to " + nationName(target) + ".");
    }

    public Result declareWar(int a, int b) {
        if (a == b || !ns[b].alive) return no("Invalid target.");
        if (war[a][b]) return no("Already at war.");
        if (isAlly(a, b)) return no("They are your ally.");
        if (day < truceUntil[a][b]) return no("A recent peace treaty still holds for " + (truceUntil[a][b] - day) + " more days.");
        war[a][b] = war[b][a] = true;
        warStart[a][b] = warStart[b][a] = day;
        ns[a].lastWarDay = ns[b].lastWarDay = day;
        ns[a].stability = Math.max(0, ns[a].stability - 3);
        news("WAR: " + nationName(a) + " declares war on " + nationName(b) + "!");
        Relations.both(this, a, b, -60);
        for (int c = 0; c < N; c++) if (c != a && c != b && ns[c].alive) {
            if (isAlly(c, b)) Relations.both(this, c, a, -40);
            else Relations.change(this, c, a, -6 + (opinion[c][b] > 30 ? -8 : 0));
        }
        for (int c = 0; c < N; c++) {
            if (c == a || c == b || !ns[c].alive) continue;
            if (isAlly(c, b) && !isAlly(c, a) && !war[c][a] && rng.nextDouble() < 0.85) joinWar(c, a, "defence of " + nationName(b));
            else if (isAlly(c, a) && !isAlly(c, b) && !war[c][b] && rng.nextDouble() < 0.5) joinWar(c, b, "support of " + nationName(a));
        }
        return ok("War declared on " + nationName(b) + ".");
    }

    void joinWar(int c, int enemy, String why) {
        war[c][enemy] = war[enemy][c] = true;
        warStart[c][enemy] = warStart[enemy][c] = day;
        ns[c].lastWarDay = ns[enemy].lastWarDay = day;
        news(nationName(c) + " joins the war against " + nationName(enemy) + " in " + why + ".");
    }

    /** How {@code b} would answer {@code a}'s proposal, without doing anything. */
    public Peace.Talk assessPeace(int a, int b, Peace.Term term) { return Peace.assess(this, a, b, term); }

    public Peace.Talk assessPeace(int a, int b, Peace.Term term, java.util.Set<Integer> demand) { return Peace.assess(this, a, b, term, demand); }

    public Result negotiatePeace(int a, int b, Peace.Term term, java.util.Set<Integer> demand) { return Peace.negotiate(this, a, b, term, demand); }

    /** What the war looks like to {@code a}: land held, armies, exhaustion. */
    public Peace.Situation peaceSituation(int a, int b) { return Peace.situation(this, a, b); }

    /** Proposes peace on the given terms; the other side may refuse. */
    public Result negotiatePeace(int a, int b, Peace.Term term) { return Peace.negotiate(this, a, b, term); }

    /** A plain white-peace proposal (used by the AI). */
    public Result offerPeace(int a, int b) { return negotiatePeace(a, b, Peace.Term.WHITE); }

    public void makePeace(int a, int b) {
        war[a][b] = war[b][a] = false;
        truceUntil[a][b] = truceUntil[b][a] = day + Peace.TRUCE_DAYS;
        news("PEACE: " + nationName(a) + " and " + nationName(b) + " end their war.");
        for (Army x : new ArrayList<>(armies.values())) {
            if ((x.owner == a && owner[x.province] == b) || (x.owner == b && owner[x.province] == a)) {
                if (x.battleId >= 0) endBattleQuiet(x.battleId);
                int dest = nearestFriendly(x);
                if (dest >= 0) { x.province = dest; x.path.clear(); x.embarked = false; }
            }
        }
    }

    int nearestFriendly(Army x) {
        Deque<Integer> q = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        q.add(x.province); seen.add(x.province);
        while (!q.isEmpty()) {
            int p = q.poll();
            if (!isSea(p) && (owner[p] == x.owner || isAlly(x.owner, owner[p]))) return p;
            for (int nb : world.provinces.get(p).neighbours) if (seen.add(nb)) q.add(nb);
        }
        return -1;
    }

    public Result formAlliance(int a, int b) {
        if (a == b || !ns[b].alive) return no("Invalid target.");
        if (isAlly(a, b)) return no("Already allied.");
        if (war[a][b]) return no("At war.");
        if (day < allianceCooldown[a][b]) return no(nationName(b) + " recently turned you down. Try again in " + (allianceCooldown[a][b] - day) + " days.");
        Relations.Verdict v = Relations.allianceVerdict(this, a, b);
        if (!v.accept()) {
            allianceCooldown[a][b] = allianceCooldown[b][a] = day + 60;
            Relations.change(this, b, a, -2);
            return no(String.format("%s refuses (score %.0f, needs %.0f): %s.", nationName(b), v.score(), v.needed(), v.reason()));
        }
        ally[a][b] = ally[b][a] = true;
        Relations.both(this, a, b, 8);
        news(nationName(a) + " and " + nationName(b) + " sign an alliance.");
        return ok(String.format("%s accepts (score %.0f): %s.", nationName(b), v.score(), v.reason()));
    }

    public Result breakAlliance(int a, int b) {
        if (!ally[a][b]) return no("No direct alliance.");
        ally[a][b] = ally[b][a] = secret[a][b] = secret[b][a] = false;
        Relations.both(this, a, b, -25);
        allianceCooldown[a][b] = allianceCooldown[b][a] = day + 180;
        return ok("Alliance dissolved.");
    }

    /** Democracies and republics may call an election at once: a gamble that resets the four-year clock. */
    public Result callElection(int n) {
        NationState s = ns[n];
        if (!s.gov.elections) return no(s.gov.label + "s do not hold elections.");
        if (day < s.nextElection - 1200) return no("An election was held recently. Wait for the public mood to change.");
        s.nextElection = day;
        return ok("Early elections called. The result will be known tomorrow.");
    }

    /** Seize emergency powers: no more elections and less war weariness, at a heavy cost in stability. */
    public Result seizePower(int n) {
        NationState s = ns[n];
        if (s.gov == Government.AUTOCRACY || s.gov == Government.JUNTA || s.gov == Government.ONE_PARTY) return no("You already rule without elections.");
        s.gov = Government.AUTOCRACY;
        s.stability = Math.max(0, s.stability - 15);
        s.nextElection = Integer.MAX_VALUE;
        for (int c = 0; c < N; c++) if (c != n) Relations.change(this, c, n, -10);
        news(nationName(n) + " suspends its constitution. Emergency powers are declared.");
        return ok("Emergency powers declared. Stability -15; the world is watching.");
    }

    public Result toggleSanction(int a, int b) {
        sanction[a][b] = !sanction[a][b];
        if (sanction[a][b]) { news(nationName(a) + " imposes sanctions on " + nationName(b) + "."); Relations.change(this, b, a, -25); }
        return ok(sanction[a][b] ? "Sanctions imposed." : "Sanctions lifted.");
    }

    public Result sendAid(int a, int b, double amount) {
        if (ns[a].money < amount) return no("Not enough money.");
        ns[a].money -= amount; ns[b].money += amount;
        Relations.change(this, b, a, Math.min(12, amount / 4));
        return ok("Sent " + (int) amount + " to " + nationName(b) + ".");
    }

    public Result oilDeal(int seller, int buyer) {
        if (ns[seller].oil < 80) return no(nationName(seller) + " has no oil to spare.");
        for (Deal d : deals) if (d.seller == seller && d.buyer == buyer) return no("Deal already exists.");
        if (war[seller][buyer] || sanction[seller][buyer]) return no("Trade is blocked.");
        Deal d = new Deal();
        d.seller = seller; d.buyer = buyer; d.oilPerDay = 0.4; d.moneyPerDay = 0.5;
        deals.add(d);
        Relations.both(this, seller, buyer, 8);
        return ok("Oil trade agreed.");
    }

    /** Arms for a proxy: a small force appears at the recipient's capital. Only for nations that are not your enemy. */
    public Result armsDeal(int a, int b) {
        if (a == b || !ns[b].alive) return no("Invalid target.");
        if (war[a][b]) return no("You are at war with them.");
        if (ns[a].money < 40) return no("Need 40 money.");
        int cap = world.nations.get(b).capital;
        if (owner[cap] != b || controller[cap] != b) return no(nationName(b) + " has lost its capital region.");
        ns[a].money -= 40;
        Army x = new Army(nextArmyId++, b, cap, "Supplied by " + nationName(a));
        x.units[UnitType.INFANTRY.ordinal()] = 2;
        x.units[UnitType.ANTI_TANK.ordinal()] = 1;
        x.morale = 75;
        armies.put(x.id, x);
        return ok("Arms delivered to " + nationName(b) + ".");
    }

    public Result launchNuke(int n, int target) {
        NationState s = ns[n];
        if (s.nukes <= 0) return no("No nuclear weapons.");
        boolean site = s.subs > 0;
        for (int p = 0; p < P && !site; p++) if (owner[p] == n && silo[p] > 0) site = true;
        if (!site) return no("No operational silo or missile submarine.");
        if (isSea(target)) return no("Target must be land.");
        s.nukes--;
        if (n == player) stNukes++;
        for (int c = 0; c < N; c++) if (c != n) Relations.change(this, c, n, -45);
        nukesInFlight.add(new int[]{n, target, day + 1});
        news("ALERT: " + nationName(n) + " launches a nuclear missile at " + prov(target).name + " (" + nationName(owner[target]) + ")!");
        return ok("Missile launched. Impact in 1 day.");
    }

    /** Cheap fake army: from afar it reads as {@code fake} battalions; close-up vision exposes it. */
    public Result deployDecoy(int n, int province, int fake) {
        if (owner[province] != n || controller[province] != n) return no("You must own and control that province.");
        int existing = 0;
        for (Army a : armies.values()) if (a.owner == n && a.decoy) existing++;
        if (existing >= 4) return no("You already field 4 decoy forces.");
        double cost = fake * 2.0;
        if (ns[n].money < cost) return no(String.format("Need %.0f money.", cost));
        ns[n].money -= cost;
        Army a = new Army(nextArmyId++, n, province, "Decoy Force " + nextArmyId);
        a.units[UnitType.INFANTRY.ordinal()] = 1;
        a.decoy = true; a.fake = fake; a.morale = 60;
        armies.put(a.id, a);
        return ok("Decoy deployed. Enemies far away will read it as " + fake + " battalions.");
    }

    /** Special forces strike an enemy province's installations. Risky: failure costs a special forces unit. */
    public Result raid(Army a, int target) {
        int sf = UnitType.SPECIAL_FORCES.ordinal();
        if (a.units[sf] < 1) return no("This army has no special forces.");
        if (a.raidCooldown > 0) return no("Special forces are recovering (" + a.raidCooldown + " days).");
        if (isSea(target) || !atWar(a.owner, owner[target])) return no("Raids need a land province of a nation you are at war with.");
        if (dist(a.province, target) > 450) return no("Target is out of reach (450 km).");
        a.raidCooldown = 12;
        double chance = 0.45 + 0.12 * Math.min(3, a.units[sf]) + 0.03 * ns[a.owner].tech[0];
        if (fort[target] > 0) chance -= 0.08 * fort[target];
        if (rng.nextDouble() < chance) {
            String hit = "supply depots";
            if (fort[target] > 0) { fort[target]--; hit = "fortifications"; }
            else if (factory[target] > 0) { factory[target]--; hit = "a factory"; }
            else if (radar[target] > 0) { radar[target]--; hit = "a radar station"; }
            else if (airfield[target] > 0) { airfield[target]--; hit = "an airfield"; }
            else unrest[target] = Math.min(100, unrest[target] + 10);
            news("Special forces sabotaged " + hit + " in " + prov(target).name + ".");
            return ok("Raid succeeded: " + hit + " destroyed.");
        }
        a.units[sf] -= 1;
        return no("The raid failed and a special forces unit was lost.");
    }

    public void setTactic(Army a, Tactic t) { a.mode = Army.Mode.MANUAL; a.tactic = t; awaitingTactic.remove(a.id); }
    public void setGeneralMode(Army a) { a.mode = Army.Mode.GENERAL; a.tactic = null; awaitingTactic.remove(a.id); }

    public Result answerEvent(GameEvent ev, int option) {
        pendingEvents.remove(ev);
        return Events.apply(this, ev, option);
    }

    public void news(String s) {
        news.add(date() + "  " + s);
        if (news.size() > 400) news.subList(0, 100).clear();
    }

    // ------------------------------------------------------------------ simulation
    public void advanceDay() {
        day++;
        economy();
        for (int n = 0; n < N; n++) if (ns[n].alive) Politics.daily(this, n);
        Relations.drift(this);
        movement();
        War.contacts(this);
        War.resolveBattles(this);
        War.occupation(this);
        War.mergeStacks(this);
        War.unrest(this);
        nukes();
        airMissions();
        Vision.updateAll(this);
        for (int n = 0; n < N; n++) if (ns[n].alive && n != player && (day + n) % 3 == 0) Ai.step(this, n);
        Events.daily(this);
        eliminationCheck();
    }

    void economy() {
        double[] gdp = new double[N], oilOut = new double[N], units = new double[N];
        int[] provs = new int[N];
        for (int p = 0; p < P; p++) {
            int o = owner[p];
            if (o < 0) continue;
            WorldData.ProvinceInfo pi = world.provinces.get(p);
            double occupiedPenalty = controller[p] == o ? 1.0 : 0.0;
            gdp[o] += pi.gdp * devastation[p] * (1 + 0.15 * factory[p]) * occupiedPenalty * (1 - unrest[p] / 220.0);
            oilOut[o] += pi.oil * devastation[p] * occupiedPenalty;
            provs[o]++;
        }
        for (Army a : armies.values()) if (a.owner >= 0) units[a.owner] += a.totalUnits();
        for (int n = 0; n < N; n++) {
            NationState s = ns[n];
            if (!s.alive) continue;
            boolean sanctioned = false, blockaded = false;
            for (int m = 0; m < N; m++) {
                if (sanction[m][n] && ns[m].alive) sanctioned = true;
                if (war[m][n] && ns[m].transports > ns[n].transports + 2) blockaded = true;
            }
            s.blockaded = blockaded;
            Budget b = s.budget();
            b.clamp();
            double dealIncome = 0;
            for (Deal d : deals) if (d.seller == n) dealIncome += d.moneyPerDay;
            double lim = (sanctioned ? 0.15 : 0) + (blockaded ? 0.2 : 0);
            Budget.Ledger led = ledger(n, gdp[n], units[n], lim, dealIncome);
            s.ledger = led;
            s.lastIncome = led.net();
            s.inflation = Math.max(0, Math.min(0.6, s.inflation + led.inflationDelta()));
            s.gdpIndex = Math.max(0.4, Math.min(3.0, s.gdpIndex * (1 + led.growth() / 365.0)));
            s.infraIndex = Math.max(0, Math.min(1, s.infraIndex + b.infra * 0.012 - 0.0006));
            s.money = Math.max(b.printMoney ? -600 : -300, s.money + led.net());
            if (s.money < 0) s.stability -= 0.02;
            double max = manpowerMax(n) * provs[n] / Math.max(1, world.nations.get(n).provinces.size());
            s.manpower = Math.min(max, s.manpower + Math.max(0.05, max * 0.0015));
            double oilDelta = oilOut[n] * 0.05;
            for (Deal d : deals) { if (d.buyer == n) oilDelta += d.oilPerDay; if (d.seller == n) oilDelta -= d.oilPerDay; }
            s.lastOilDelta = oilDelta;
            s.oil = Math.max(0, s.oil + oilDelta);
            double target = s.ledger.stabilityTarget();
            if (atWarAny(n)) { s.warWeariness = Math.min(100, s.warWeariness + 0.08); s.stability -= 0.025; s.stability += (target - 60) * 0.002; }
            else { s.warWeariness = Math.max(0, s.warWeariness - 0.15); s.stability += (target - s.stability) * 0.004; }
            s.stability = Math.max(0, Math.min(100, s.stability));
            // research
            if (s.researching != null && (s.researchLeft -= Math.max(0.4, 1 + 6 * (b.edu - 0.05))) <= 0) {
                s.tech[s.researching.ordinal()]++;
                if (n == player) news("Research complete: " + s.researching.label + " level " + s.tech[s.researching.ordinal()] + ".");
                s.researching = null;
            }
            // construction
            for (Iterator<Construction> it = s.constructions.iterator(); it.hasNext(); ) {
                Construction c = it.next();
                if (--c.daysLeft <= 0) {
                    if (owner[c.province] == n) setLevel(c.type, c.province, level(c.type, c.province) + 1);
                    if (n == player) news(c.type.label + " completed in " + prov(c.province).name + ".");
                    it.remove();
                }
            }
            // recruitment
            for (Iterator<Recruit> it = s.recruitQueue.iterator(); it.hasNext(); ) {
                Recruit r = it.next();
                r.daysLeft -= rng.nextDouble() < Math.max(0, (b.military - 0.05) * 3) ? 2 : 1;
                if (r.daysLeft <= 0) {
                    if (owner[r.province] == n && controller[r.province] == n) {
                        Army a = new Army(nextArmyId++, n, r.province, "New Army " + nextArmyId);
                        System.arraycopy(r.units, 0, a.units, 0, UnitType.N);
                        a.morale = 70;
                        armies.put(a.id, a);
                        if (n == player) news("New forces ready at " + prov(r.province).name + ".");
                    }
                    it.remove();
                }
            }
            // low stability bleeds morale of the whole nation's armies (checked lazily in War)
        }
    }

    /** Pure accounts for one nation given its raw (unscaled) province GDP and unit count. */
    public Budget.Ledger ledger(int n, double rawGdp, double units, double revenueLoss, double dealIncome) {
        NationState s = ns[n];
        Budget b = s.budget();
        boolean war = atWarAny(n);
        double focusGdp = 1 - 0.05 * Math.max(0, b.industryFocus) + 0.03 * Math.max(0, -b.industryFocus);
        double gdp = rawGdp * s.gdpIndex * (1 + 0.10 * s.infraIndex) * Budget.MOB_GDP[b.mobilization] * focusGdp;
        double eff = Math.max(0.35, Math.min(1.0, 0.58 + 0.30 * Math.min(1.2, b.security / 0.10) + (s.stability - 50) / 250.0 - s.inflation * 0.3));
        double revenue = gdp / 365.0 * 0.002 * (b.tax / Budget.TAX_DEFAULT) * eff + 0.2;
        revenue *= Math.max(0, 1 - revenueLoss);
        double infl = -0.0012;
        if (b.printMoney) { revenue *= 1.30; infl = 0.004; }
        revenue *= 1 - s.inflation * 0.5;
        double upkeep = units * 0.014 * (b.fuelRationing ? 0.95 : 1.0);
        double dev = revenue * b.dev, infra = revenue * b.infra, edu = revenue * b.edu, wel = revenue * b.welfare, sec = revenue * b.security, mil = revenue * b.military;
        double net = revenue - upkeep - (dev + infra + edu + wel + sec + mil) + dealIncome;
        double growth = 0.02 + 0.12 * b.dev + 0.05 * b.edu + 0.03 * s.infraIndex - 0.10 * (b.tax - Budget.TAX_DEFAULT)
                - (war ? 0.02 : 0) - 0.01 * b.mobilization - s.inflation * 0.08;
        growth = Math.max(-0.08, Math.min(0.10, growth));
        double target = 60 + 30 * (b.welfare - 0.08) - 40 * (b.tax - Budget.TAX_DEFAULT) - 30 * s.inflation - Budget.MOB_STABILITY[b.mobilization] - (b.fuelRationing ? 2 : 0);
        return new Budget.Ledger(gdp, revenue, upkeep, dev, infra, edu, wel, sec, mil, dealIncome, net, eff, growth, target, infl);
    }

    /** Recomputes the accounts for the current policy without advancing time (used by the UI for live previews). */
    public Budget.Ledger previewLedger(int n) {
        double raw = 0, units = 0;
        for (int p = 0; p < P; p++) {
            if (owner[p] != n || controller[p] != n) continue;
            WorldData.ProvinceInfo pi = world.provinces.get(p);
            raw += pi.gdp * devastation[p] * (1 + 0.15 * factory[p]) * (1 - unrest[p] / 220.0);
        }
        for (Army a : armies.values()) if (a.owner == n) units += a.totalUnits();
        boolean sanctioned = false, blockaded = false;
        for (int m = 0; m < N; m++) { if (sanction[m][n] && ns[m].alive) sanctioned = true; if (war[m][n] && ns[m].transports > ns[n].transports + 2) blockaded = true; }
        double deal = 0;
        for (Deal d : deals) if (d.seller == n) deal += d.moneyPerDay;
        return ledger(n, raw, units, (sanctioned ? 0.15 : 0) + (blockaded ? 0.2 : 0), deal);
    }

    public double oilFactor(int n) { return n >= 0 && ns[n].budget().fuelRationing ? 0.7 : 1.0; }

    /** Builds {@code count} levels of one building type in the richest eligible provinces until money runs out. */
    public Result autoBuild(int n, Building type, int count) {
        List<Integer> candidates = new ArrayList<>();
        for (int p = 0; p < P; p++) {
            if (owner[p] != n || controller[p] != n || level(type, p) >= type.maxLevel) continue;
            boolean busy = false;
            for (Construction c : ns[n].constructions) if (c.province == p && c.type == type) busy = true;
            if (!busy) candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble((Integer p) -> -world.provinces.get(p).gdp * devastation[p]));
        int built = 0;
        double spent = 0, before = ns[n].money;
        for (int p : candidates) {
            if (built >= count) break;
            Result r = build(n, p, type);
            if (!r.ok) { if (r.message.startsWith("Need")) break; continue; }
            built++;
        }
        spent = before - ns[n].money;
        if (built == 0) return no("Nothing built (no eligible province or not enough money).");
        return ok(String.format("Started %d x %s for %.0f money.", built, type.label, spent));
    }

    void movement() {
        for (Army a : armies.values()) if (a.raidCooldown > 0) a.raidCooldown--;
        for (Army a : new ArrayList<>(armies.values())) {
            if (a.path.isEmpty() || a.battleId >= 0 || !a.alive()) continue;
            NationState s = a.owner >= 0 ? ns[a.owner] : null;
            double oilNeed = a.oilUsePerDay();
            boolean hasOil = s == null || s.oil > 0;
            if (s != null) s.oil = Math.max(0, s.oil - oilNeed * oilFactor(a.owner));
            double speed = a.speed() * (hasOil ? 1 : (oilNeed > 0.01 ? 0.5 : 1)) * (s == null ? 1 : (1 + 0.15 * s.infraIndex) * (s.budget().fuelRationing ? 0.93 : 1.0));
            double budget = speed;
            while (!a.path.isEmpty() && budget > 0) {
                int next = a.path.peek();
                if (!canEnter(a.owner, next, a)) { a.path.clear(); break; }
                WorldData.ProvinceInfo f = prov(a.province), t = prov(next);
                double km = 100;
                for (int k = 0; k < f.neighbours.length; k++) if (f.neighbours[k] == next) { km = f.distKm[k]; break; }
                double eff = t.sea ? 45 : speed;
                double inf = t.sea ? 1 : 1 + 0.18 * Math.min(infra[next], infra[a.province] == 0 ? infra[next] : infra[a.province]);
                double terr = t.sea ? 1 : t.terrain.moveCost * Terrain.weather(t.lat, day, t.terrain);
                double rate = eff / terr * inf;
                double need = km - a.progressKm;
                double canDo = rate * (budget / speed);
                if (canDo >= need) {
                    budget -= need / rate * speed;
                    a.progressKm = 0;
                    a.prevProvince = a.province;
                    a.province = a.path.poll();
                    a.embarked = isSea(a.province);
                    // stop when we run into an enemy army
                    boolean contact = false;
                    for (Army o : armies.values()) if (o != a && o.province == a.province && atWar(a.owner, o.owner)) contact = true;
                    if (contact) { a.path.clear(); break; }
                } else { a.progressKm += canDo; budget = 0; }
            }
        }
    }

    void nukes() {
        for (Iterator<int[]> it = nukesInFlight.iterator(); it.hasNext(); ) {
            int[] n = it.next();
            if (n[2] > day) continue;
            it.remove();
            int t = n[1];
            int victim = owner[t];
            devastation[t] = 0.1f; fort[t] = 0; factory[t] = 0; infra[t] = 0; radar[t] = 0; airfield[t] = 0; silo[t] = 0;
            unrest[t] = 100;
            for (Army a : armiesIn(t)) {
                if (a.battleId >= 0) endBattleQuiet(a.battleId);
                a.units[0] = 0; for (int i = 0; i < a.units.length; i++) a.units[i] = 0;
            }
            armies.values().removeIf(a -> !a.alive());
            if (victim >= 0) {
                ns[victim].stability = Math.max(0, ns[victim].stability - 12);
                news("NUCLEAR STRIKE: " + prov(t).name + " (" + nationName(victim) + ") has been devastated by " + nationName(n[0]) + ".");
                if (!war[n[0]][victim]) { war[n[0]][victim] = war[victim][n[0]] = true; ns[n[0]].lastWarDay = ns[victim].lastWarDay = day; }
            }
        }
    }

    void airMissions() {
        for (int n = 0; n < N; n++) {
            if (!ns[n].alive || ns[n].airWings.isEmpty()) continue;
            for (AirWing w : ns[n].airWings) {
                if (w.mission != Mission.BOMBING || w.target < 0) continue;
                for (Army a : armiesIn(w.target)) {
                    if (!atWar(n, a.owner) || a.embarked) continue;
                    double aa = a.share(UnitType.ANTI_AIR);
                    Combat.applyLoss(a, a.hp() * 0.012 * w.strength * (1 - Math.min(0.7, aa * 4)));
                }
                if (owner[w.target] >= 0 && atWar(n, owner[w.target]) && rng.nextDouble() < 0.01 * w.strength && fort[w.target] > 0) fort[w.target]--;
            }
        }
        armies.values().removeIf(a -> !a.alive());
    }

    void eliminationCheck() {
        for (int n0 = 0; n0 < N; n0++) {
            final int n = n0;
            if (!ns[n].alive) continue;
            if (ownedProvinces(n) == 0) {
                ns[n].alive = false;
                news(nationName(n) + " has been eliminated.");
                for (int m = 0; m < N; m++) war[n][m] = war[m][n] = false;
                armies.values().removeIf(a -> a.owner == n);
            }
        }
    }

    void endBattleQuiet(int id) {
        Combat.Battle b = battles.remove(id);
        if (b == null) return;
        Army a = armies.get(b.attacker), d = armies.get(b.defender);
        if (a != null) a.battleId = -1;
        if (d != null) d.battleId = -1;
    }

    // ------------------------------------------------------------------ persistence
    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeObject(this);
        }
    }

    public static GameState load(Path file, WorldData w) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            GameState gs = (GameState) in.readObject();
            gs.attach(w);
            return gs;
        }
    }
}
