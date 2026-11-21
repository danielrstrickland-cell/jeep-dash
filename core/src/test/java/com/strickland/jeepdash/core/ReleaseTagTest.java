package com.strickland.jeepdash.core;

public class ReleaseTagTest {
    public static void releaseTagParsing() {
        T.eq(12, ReleaseTag.build("dash-12"), "dash tag");
        T.eq(-1, ReleaseTag.build("link-test-3"), "link test tag is not a dash build");
        T.eq(-1, ReleaseTag.build(null), "missing tag");
        T.ok(ReleaseTag.isNewer("dash-12", 11), "12 is newer than 11");
        T.ok(!ReleaseTag.isNewer("dash-12", 12), "same build is not newer");
        T.ok(!ReleaseTag.isNewer("link-test-40", 1), "non-dash tag never offered");
    }
}
