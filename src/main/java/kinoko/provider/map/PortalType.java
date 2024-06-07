package kinoko.provider.map;

public enum PortalType {
    // PORTALTYPE
    STARTPOINT(0),
    INVISIBLE(1),
    VISIBLE(2),
    COLLISION(3),
    CHANGEABLE(4),
    CHANGEABLE_INVISIBLE(5),
    TOWNPORTAL_POINT(6),
    SCRIPT(7),
    SCRIPT_INVISIBLE(8),
    COLLISION_SCRIPT(9),
    HIDDEN(10),
    SCRIPT_HIDDEN(11),
    COLLISION_VERTICAL_JUMP(12),
    COLLISION_CUSTOM_IMPACT(13);

    private final int type;

    PortalType(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }

    public static PortalType getByValue(int type) {
        for (PortalType pt : PortalType.values()) {
            if (pt.getType() == type) {
                return pt;
            }
        }
        return null;
    }
}
