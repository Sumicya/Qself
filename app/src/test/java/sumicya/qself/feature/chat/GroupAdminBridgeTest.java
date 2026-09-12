package sumicya.qself.feature.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * The v1b keyword/shape matcher: name keyword AND parameter shape must both
 * hold before a live service method is invoked.
 */
public class GroupAdminBridgeTest {

    private static Method m(String name, Class<?>... ps) throws Exception {
        return GroupAdminBridgeTest.class.getDeclaredMethod("dummy");
    }

    // real matching exercised via surface proxies below
    public void dummy() {
    }

    public void groupShutUp(String a, String b, long c) {
    }

    public void setGroupCard(String a, String b, String c) {
    }

    public void kickMember(String a, String b) {
    }

    public void muteMemberWrongShape(String a, String b, String c) {
    }

    @Test
    public void matchesByNameAndShape() throws Exception {
        Method shut = GroupAdminBridgeTest.class.getMethod("groupShutUp", String.class, String.class, long.class);
        assertTrue(GroupAdminBridge.matchesAction(shut, "shutup", 3, String.class, String.class, long.class));

        Method card = GroupAdminBridgeTest.class.getMethod("setGroupCard", String.class, String.class, String.class);
        assertTrue(GroupAdminBridge.matchesAction(card, "card", 3, String.class, String.class, String.class));

        Method kick = GroupAdminBridgeTest.class.getMethod("kickMember", String.class, String.class);
        assertTrue(GroupAdminBridge.matchesAction(kick, "kick", 2, String.class, String.class));
    }

    @Test
    public void rejectsWrongShapeOrKeyword() throws Exception {
        Method wrong = GroupAdminBridgeTest.class.getMethod("muteMemberWrongShape", String.class, String.class, String.class);
        assertFalse(GroupAdminBridge.matchesAction(wrong, "mute", 3, String.class, String.class, long.class));

        Method card = GroupAdminBridgeTest.class.getMethod("setGroupCard", String.class, String.class, String.class);
        assertFalse(GroupAdminBridge.matchesAction(card, "kick", 3, String.class, String.class, String.class));
        assertFalse(GroupAdminBridge.matchesAction(card, "card", 2, String.class, String.class));
    }
}
