package openhouse.mod;

public final class RoomCap {
    private RoomCap() { }

    public static final int CAP = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("OPENHOUSE_TEST_ROOMCAP", "9")));
    public static final String FULL = "This world is full (9 players). Ask the host to move it to OpenHost for more players.";
}
