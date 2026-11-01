# OpenHouse (OH)

![OpenHouse](art/openhouse-logo-x8.png)

Play Better than Adventure with your friends using a room code. No port forwarding, no IP
addresses, and your friends get your mods automatically. Want a world that stays up when you're
not playing? OpenHost is a whole server in one file.

Made for BTA 8.0.1 with Babric/Fabric. Get the mod, OpenHost and the helper from
[Releases](https://github.com/colinthepwner/OpenHouse-bta/releases).

## Playing with friends

1. Put the mod in your `mods` folder and open a single-player world.
2. Press Esc and click **Open to Friends**.
3. Your room code shows up in chat. Send it to your friends.

When you're done, press Esc and click **Close World**. Everything's saved, and you can keep
playing the world on your own.

## Joining a friend

1. Put the mod in your `mods` folder.
2. Go to **Multiplayer**, pick **Room Code**, type the code and click **Join**.

If your friend has mods you don't, OH offers to grab them and restart the game with them. Your
own `mods` folder stays the way it was. Only take mods from people you trust.

## Running a server: OpenHost

`OpenHost.jar` is a whole BTA 8.0.1 server, for a world your friends can join any time, even
when you're not playing. Put it in a folder and run it:

    java -jar OpenHost.jar

(or just double-click it). The first start downloads what it needs, then the console shows the
room code people join with. It's a new one every time the server starts. To keep one, pick
your own: type `oh set MYWORLD` in the console. Picked codes are 6 to 16 letters or digits. If
another server is already using yours, the console tells you so you can pick another.

Its window shows the room code, who's on and the console. Commands can be typed loosely, like
`give steve 64 oak planks` or `tp steve to alex`, and Tab finishes what you're typing. Prefer
BTA's classic server window, or no window at all? Set `openhost-ui` to `classic` or `console` in
`server.properties`.

Its Settings tab also sets the server's password and whitelist (see below) and how much memory
the server gets, with a button to restart it.

Mods go in its `mods` folder like on any server, and players who join get them, along with the
server's mod settings. You can drop a whole modpack's mods in: ones that only work in a player's
own game are set aside (renamed to `.disabled`) and the console says which. OpenHost keeps itself
up to date. Needs Java 17 or newer.

## Already have a Babric server?

Put the mod in its `mods` folder. The room code shows up in the console, and it's a new one every
time the server starts unless you pick your own with `/oh set <name>`. Type `/oh` to see it. Ops
can also use `/oh new`, `/oh on` and `/oh off`.

The server keeps OpenHouse up to date by itself; a new version goes in the next time it restarts.

## Passwords and whitelists

To keep out people who only have the code, give your world or server a password: type
`/oh password <password>` in your opened world or in the server console. Friends with OpenHouse
type it once when they join, and you can change it any time without kicking anyone.
`/oh password off` removes it.

A whitelist lets in only the players you list: `/oh whitelist on`, then
`/oh whitelist add <name>`. It goes by name, so friends without a Minecraft account can be on it
too. A name is easy to borrow, though, so use a password if it really matters.

Players without the mod can't join a world or server that has a password.

## No mod loader?

`openhouse-helper.jar` works with any BTA version, no Fabric needed:

- To join: run `java -jar openhouse-helper.jar join K7QXD`, then Direct Connect to the address
  it shows. You can also just double-click it.
- To host: start your server, then run `java -jar openhouse-helper.jar host` in its folder. For
  a code that stays the same, add the address and your own: `host localhost:25565 MYWORLD`.

The helper doesn't hand out mods, so everyone needs the same ones. It updates itself when a new
version is out.

## Good to know

- Everyone in a world needs the same version of OpenHouse. When a new one is out, OH tells you
  and can update itself with one click.
- Friends can join with or without a Minecraft account. Without one, they show up with the skin
  that goes with their name.
- Two people can't play under the same name at once.
- Mods that only work in the game stay out of your world's server by themselves, and friends get
  your mod settings along with your mods.
- If a mod crashes your open world, OH tells you which one.
- With BTA's Discord presence on, click your room code in the pause menu to show it on your
  Discord profile so friends can press Join.
- The first time you use it, it downloads a couple of things it needs.
- Some school, work and phone networks block this kind of connection.
- If codes stop working for everybody at once, the free matchmaking service it uses is probably
  down. Try again later.

## Building

`sh build.sh` builds the helper. `sh mod/build.sh` builds the mod and `OpenHost.jar` (needs JDK 21).
