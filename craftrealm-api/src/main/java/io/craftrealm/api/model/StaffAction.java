package io.craftrealm.api.model;

/** Actions that are always written to the staff audit trail. */
public final class StaffAction {

    public static final String BALANCE_SET = "BALANCE_SET";
    public static final String BALANCE_GIVE = "BALANCE_GIVE";
    public static final String BALANCE_TAKE = "BALANCE_TAKE";
    public static final String ROLLBACK = "ROLLBACK";
    public static final String KICK = "KICK";
    public static final String BAN = "BAN";
    public static final String UNBAN = "UNBAN";
    public static final String MUTE = "MUTE";
    public static final String UNMUTE = "UNMUTE";
    public static final String WARN = "WARN";
    public static final String FREEZE = "FREEZE";
    public static final String UNFREEZE = "UNFREEZE";
    public static final String TELEPORT = "TELEPORT";
    public static final String VANISH = "VANISH";
    public static final String INVENTORY_VIEW = "INVENTORY_VIEW";
    public static final String NOTE_ADD = "NOTE_ADD";
    public static final String REPORT_RESOLVE = "REPORT_RESOLVE";
    public static final String LAG_CLEANUP = "LAG_CLEANUP";
    public static final String EVENT_TRIGGER = "EVENT_TRIGGER";
    public static final String CONSOLE_COMMAND = "CONSOLE_COMMAND";
    public static final String WEB_LOGIN = "WEB_LOGIN";

    private StaffAction() {
    }
}
