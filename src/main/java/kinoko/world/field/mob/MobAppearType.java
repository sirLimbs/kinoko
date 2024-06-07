package kinoko.world.field.mob;

public enum MobAppearType {
    // MOBAPPEAR
    NORMAL(-1),
    REGEN(-2),
    REVIVED(-3),
    SUSPENDED(-4),
    DELAY(-5),
    EFFECT(0);

    private final byte value;

    MobAppearType(int value) {
        this.value = (byte) value;
    }

    public final byte getValue() {
        return value;
    }
}
