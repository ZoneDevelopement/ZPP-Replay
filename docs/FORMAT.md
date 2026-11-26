# `.zrp` replay file format

Version 2. Files are big-endian `DataOutputStream` data: `<id>.zrp` in the addon's `replays/` folder.

Header (uncompressed): `int magic 0x5A525031`, `short version 2`, `UTF id`, `UTF matchId`, `UTF ladder`,
`UTF matchType`, `UTF originalArena`, `UTF arena`, `long startedAtMillis`, `int durationTicks`,
`boolean build`, `double sizeX, sizeY, sizeZ` (arena bounds size), `int bodyLength`, then `bodyLength` bytes =
deflated body (`java.util.zip.Deflater`, default level).

Body records: `byte type` + payload. `varint` = unsigned LEB128 int. Positions are relative to the arena bounds
minimum corner. Angles are bytes: `(byte) Math.floorMod(Math.round(deg * 256f / 360f), 256)`.

| type | name | payload |
|---|---|---|
| 0 | END | — |
| 1 | TICK | varint delta (≥ 1) |
| 2 | POS | varint actor, float x, y, z, byte yaw, byte pitch, byte headYaw |
| 3 | STATE | varint actor, byte flags (bit0 sneak, 1 sprint, 2 swimming, 3 gliding, 4 handRaised, 5 raised hand is off hand, 6 on fire, 7 invisible), byte pose (`org.bukkit.entity.Pose` ordinal) |
| 4 | SWING | varint actor, byte hand (0 main, 1 off) |
| 5 | HURT | varint actor |
| 6 | EQUIP | varint actor, byte slot (0 main hand, 1 off hand, 2 feet, 3 legs, 4 chest, 5 head), varint length, bytes (`ItemStack#serializeAsBytes`; length 0 = empty) |
| 7 | VISIBLE | varint actor, boolean |
| 8 | PALETTE | varint index, UTF block data (`BlockData#getAsString`) |
| 9 | BLOCK | short x, y, z (relative), varint palette index |
| 10 | ENTITY_SPAWN | varint id, UTF entity type key, float x, y, z, byte yaw, byte pitch, varint length, bytes (item bytes for items and thrown potions, else empty) |
| 11 | ENTITY_MOVE | varint id, float x, y, z, byte yaw, byte pitch |
| 12 | ENTITY_REMOVE | varint id |
| 13 | EFFECT | varint kind (0 potion splash, 1 explosion), float x, y, z, then for kind 0: int rgb |
| 14 | ROUND | varint round |
| 15 | ACTOR | varint index, UTF uuid, UTF name, boolean bot, UTF skin value (`""` = none), UTF skin signature |
| 16 | EQUIP_PATCH | varint actor, byte slot, varint amount, varint damage (0 = no DAMAGE component, else damage + 1). Same item as the slot's previous EQUIP/EQUIP_PATCH; only amount and DAMAGE changed |
