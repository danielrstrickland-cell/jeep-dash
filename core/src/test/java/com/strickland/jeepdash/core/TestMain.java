package com.strickland.jeepdash.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Runs every public static void no-arg method (not starting with "_") of the listed test classes. */
public final class TestMain {
    static final Class<?>[] CLASSES = {
            XtoolFrameTest.class,
            DongleSessionTest.class,
            ProtocolsTest.class,
            CatalogTest.class,
            LiveValuesTest.class,
            PollSchedulerTest.class,
            PollRunnerTest.class,
            LayoutTest.class,
            LayoutCodecTest.class,
            DriveLogWriterTest.class,
            ReleaseTagTest.class,
            SpectrumTest.class,
            VizTuneTest.class,
            MotionTest.class,
            KwpTest.class,
            SciCodesTest.class,
            FastRefreshTest.class,
            GearsTest.class,
            KwpCodesTest.class,
            UnitsTest.class,
            NightHoursTest.class,
            FuelUseTest.class,
    };

    public static void main(String[] args) {
        int passed = 0, failed = 0;
        for (Class<?> c : CLASSES) {
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isStatic(mod) || !Modifier.isPublic(mod) || m.getParameterCount() != 0
                        || m.getReturnType() != void.class || m.getName().startsWith("_")) continue;
                try {
                    m.invoke(null);
                    passed++;
                } catch (InvocationTargetException e) {
                    failed++;
                    System.out.println("FAIL " + c.getSimpleName() + "." + m.getName() + ": " + e.getCause());
                } catch (IllegalAccessException e) {
                    failed++;
                    System.out.println("FAIL " + c.getSimpleName() + "." + m.getName() + ": " + e);
                }
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
