# Exchange Insights Capture

Instant replay for [RuneLite](https://runelite.net/), in the style of ShadowPlay.

The plugin holds the last few seconds of play in memory and writes an MP4 when
something worth keeping happens: a death, a boss kill, a collection log slot, a
rare drop. A hotkey saves one whenever you want.

## How it works

- Frames come from RuneLite's `DrawManager` and sit in a rolling buffer as
  JPEG-compressed bytes, so memory stays bounded however long you leave it
  running.
- When a trigger fires, the buffered lead-up and a short tail are encoded to an
  `.mp4` on a background thread. The H.264 encoder and the MP4 container are
  both part of this plugin, so it has **no third-party dependencies, no native
  binaries and no external processes**.
- The first frame is coded whole and every frame after it as a difference from
  the one before. There are no further keyframes: a clip is seconds long, and
  each extra keyframe costs several times what a predicted frame does.
- A preview image is written inside each clip as cover art, so the side panel
  can show one without decoding any video.

## Configuration

**Recording**

| Setting | Default | |
|---|---|---|
| Capture mode | Automatic | Off, Automatic (always buffering) or Manual (hotkey to arm). |
| Clip length | 15s | Total length of a saved clip. |
| Post-event padding | 5s | How much is recorded after the event; the rest is the lead-up. |
| Framerate | 50 | A ceiling, not a target. **The main performance dial** — see below. |
| Quality | Medium | Low, Medium, High or Ultra. Higher looks better and costs disk. |
| Memory limit | 512 MB | Ceiling for buffered frames. Past it, the oldest are dropped. |
| Draw cursor | off | Draws a marker at the mouse; the OS cursor is not part of a captured frame. |

Clips record at the client's own resolution. Resizing the client discards the
buffer and refills at the new size, because one clip cannot mix two frame
shapes. Buffer quality adjusts itself under memory pressure and has no setting.

**Triggers**

Boss kills, chest loot, clue scroll rewards, collection log, combat
achievements, deaths, duels, friends chat kicks, kingdom rewards, league tasks,
levels, pets, PvP kills, quests, untradeable drops, valuable drops and the
wilderness loot chest. All on by default except friend and clan deaths, which
fire constantly in a raid and are somebody else's death rather than yours.

Valuable drops have a gp threshold, 100k by default. There are two hotkeys: one
arms and disarms a manual take, the other saves the last few seconds instantly
while in Automatic mode. Manual takes stop and save themselves after five
minutes.

**Output**

| Setting | Default | |
|---|---|---|
| Save folder | `.runelite/captures` | Alongside RuneLite's own `screenshots`. |
| Chat message on save | on | Confirms each clip in-game. |
| Limit storage size | on | |
| Size limit | 10 GB | |
| Storage limit mode | Delete oldest | Or stop recording when full. |
| Show status overlay | on | Armed, recording, or just saved. |

**Exchange Insights**

Linking an account lets the plugin upload clips to
[Exchange Insights](https://exchange-insights.gg), where they can be watched and
shared. Uploading is off until you link, and nothing else in the plugin touches
the network. Shared clips are never auto-deleted.

## Usage

1. Enable **Exchange Insights Capture** in the RuneLite plugin list.
2. Pick which events save a clip, and set a hotkey if you want on-demand saves.
3. Play. When a trigger fires the plugin writes an `.mp4`, the overlay flashes,
   and a chat message confirms it.

Clips are filed the way RuneLite files screenshots — by account, then by what
triggered them:

```
.runelite/captures/Spryt/Boss Kills/Chambers of Xeric(267) 2026-08-14_22-42-14.mp4
.runelite/captures/Spryt/Deaths/Death iZuex 2026-08-14_23-23-39.mp4
.runelite/captures/Spryt/Manual/2026-08-15_03-18-07.mp4
```

The side panel lists what is on disk and on your account, with search, sorting
and previews. Clicking a clip copies its path; a plugin is not allowed to open a
file manager or a player, so pasting that somewhere is as far as it goes.

## Performance

Capturing a frame means asking the client to hand back what it just rendered. On
the **GPU** and **117HD** renderers that reads pixels back from the GPU and
stalls the render pipeline, so capture costs in-game FPS.

**Framerate is the dial that matters.** A frame nobody asks for costs nothing,
so the total is linear in this setting. Measured on a machine that runs the game
comfortably: 60 is affordable, and 120 takes around 80fps off the game. If your
FPS drops, lower this before anything else.

The cost scales with display scaling too, not just canvas size. On a monitor at
150% the readback is more than twice the size for the same window.

Secondary levers:

- **Clip length** sets how many frames are held in memory at once.
- **Quality** trades clip size and encoding time against how the clip looks.

The plugin keeps at most one frame request outstanding at a time, so it cannot
pile up requests and make a struggling client worse.

## Clips

Baseline H.264 in an MP4, 4:2:0, full-range BT.601, no audio. The index is
written before the media data so a browser can start playing without fetching
the end of the file first.

At the default quality a fifteen second clip runs to tens of megabytes,
depending far more on what is happening on screen than on any setting: a raid
costs multiples of a bank stand.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
