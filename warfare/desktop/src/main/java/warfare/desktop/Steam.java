package warfare.desktop;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Optional Steamworks bridge. It talks to steamworks4j through reflection, so the game builds and runs without it.
 * To enable: add com.code-disaster.steamworks:steamworks4j to the runtime classpath, ship its native libraries,
 * and place steam_appid.txt next to the game while testing. Achievement API names are "WF_" + the achievement id.
 * If anything fails the bridge turns itself off and the game carries on with its local profile.
 */
final class Steam {
    private Steam() {}

    private static boolean ready;
    private static Object stats;
    private static Method runCallbacks, setAchievement, storeStats;

    static boolean isReady() { return ready; }

    static void init() {
        try {
            Class<?> api = Class.forName("com.codedisaster.steamworks.SteamAPI");
            api.getMethod("loadLibraries").invoke(null);
            if (!(Boolean) api.getMethod("init").invoke(null)) return;
            runCallbacks = api.getMethod("runCallbacks");
            Class<?> cb = Class.forName("com.codedisaster.steamworks.SteamUserStatsCallback");
            Object callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb}, (p, m, a) -> null);
            Class<?> us = Class.forName("com.codedisaster.steamworks.SteamUserStats");
            Constructor<?> c = us.getConstructor(cb);
            stats = c.newInstance(callback);
            us.getMethod("requestCurrentStats").invoke(stats);
            setAchievement = us.getMethod("setAchievement", String.class);
            storeStats = us.getMethod("storeStats");
            ready = true;
            javax.swing.Timer t = new javax.swing.Timer(1000, e -> pump());
            t.setRepeats(true);
            t.start();
        } catch (Throwable ignored) {
            ready = false;
        }
    }

    private static void pump() {
        try { runCallbacks.invoke(null); } catch (Throwable e) { ready = false; }
    }

    static void unlock(String id) {
        if (!ready) return;
        try { setAchievement.invoke(stats, "WF_" + id); storeStats.invoke(stats); } catch (Throwable e) { ready = false; }
    }
}
